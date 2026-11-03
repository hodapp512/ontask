/**
 * Each device (Mac, phone) uploads its own day report; these merge them into the one report everyone reads
 * (users/{uid}/reports/{date}), and the per-device heat numbers into users/{uid}/heat/year. Pure functions.
 */

type Levels = Record<string, number>;
export interface DeviceReport {
  device?: { id: string; kind: string; name?: string };
  date: string;
  dayStart?: number;
  tz?: string;
  totals?: Levels;
  workTotals?: Levels;
  timeline?: { a: number; b: number; k: string; c?: string; t?: { n: string; s: number }[]; d?: string }[];
  deepSessions?: { a: number; b: number }[];
  distractionEvents?: number;
  worstHour?: { hour: number; wastedSec: number };
  topSites?: { name: string; category?: string; categoryId?: string; seconds: number; itemKey?: string; title?: string }[];
  halves?: { morning?: number; afternoon?: number };
  updatedAt?: number;
  [k: string]: unknown;
}

const subtract = (a: Levels, b: Levels): Levels => Object.fromEntries(Object.entries(a).map(([k, v]) => [k, Math.max(0, v - (b[k] ?? 0))]));
const add = (a: Levels = {}, b: Levels = {}): Levels => {
  const out: Levels = { ...a };
  for (const [k, v] of Object.entries(b)) out[k] = (out[k] ?? 0) + v;
  return out;
};

type Seg = NonNullable<DeviceReport["timeline"]>[number];

/**
 * Cuts [mac] stretches where the phone was in use: the phone only counts while its screen is on and unlocked with
 * an app in front, while the Mac keeps counting for minutes after your last keypress, so during an overlap your
 * attention was on the phone. Returns the remaining pieces (their per-app seconds scaled to what's left) and the
 * seconds removed per level.
 */
export function clipToPhone(mac: Seg[], phone: { a: number; b: number }[]): { pieces: Seg[]; removed: Record<string, number>; removedByName: Record<string, number> } {
  const busy = [...phone].sort((x, y) => x.a - y.a);
  const pieces: Seg[] = []; const removed: Record<string, number> = {}; const removedByName: Record<string, number> = {};
  const levelOf: Record<string, string> = { D: "DEEP", M: "MIXED", W: "WASTED", A: "AWAY" };
  for (const s of mac) {
    let parts: [number, number][] = [[s.a, s.b]];
    for (const p of busy) {
      if (p.b <= s.a || p.a >= s.b) continue;
      parts = parts.flatMap(([a, b]) => (p.b <= a || p.a >= b ? [[a, b]] : [[a, Math.min(b, p.a)], [Math.max(a, p.b), b]].filter(([x, y]) => y > x)) as [number, number][]);
    }
    const kept = parts.reduce((n, [a, b]) => n + b - a, 0), len = s.b - s.a;
    if (s.k !== "A" && kept < len) {
      removed[levelOf[s.k] ?? s.k] = (removed[levelOf[s.k] ?? s.k] ?? 0) + (len - kept);
      for (const x of s.t ?? []) removedByName[x.n] = (removedByName[x.n] ?? 0) + Math.round(x.s * (len - kept) / len);
    }
    for (const [a, b] of parts) {
      const f = (b - a) / len;
      pieces.push({ ...s, a, b, ...(s.t ? { t: s.t.map((x) => ({ n: x.n, s: Math.round(x.s * f) })).filter((x) => x.s > 0) } : {}) });
    }
  }
  return { pieces, removed, removedByName };
}

type Site = NonNullable<DeviceReport["topSites"]>[number];

/**
 * Takes the overlapped seconds out of a device's "where the time went": from the row of the same name where there is
 * one, and whatever is left proportionally from all its rows (names in the timeline and in the list don't always
 * match, e.g. pages and videos).
 */
export function trimSites(sites: Site[], byName: Record<string, number>, total: number): Site[] {
  const out = sites.map((s) => ({ ...s }));
  let rest = total;
  for (const [n, sec] of Object.entries(byName)) {
    const row = out.find((s) => s.name === n);
    if (!row) continue;
    const take = Math.min(row.seconds, sec); row.seconds -= take; rest -= take;
  }
  const sum = out.reduce((n, s) => n + s.seconds, 0);
  if (rest > 0 && sum > 0) for (const s of out) s.seconds = Math.max(0, Math.round(s.seconds - rest * s.seconds / sum));
  return out.filter((s) => s.seconds >= 10);
}

/** What the Mac's time cut by the phone takes off a day's heat numbers (as negative parts), work hours split by share. */
export function overlapParts(removed: Record<string, number>, macTotals: Levels = {}, macWork: Levels = {}): DayParts {
  const share = (k: string) => (macTotals[k] ? (macWork[k] ?? 0) / macTotals[k] : 0);
  const r = (k: string) => removed[k] ?? 0;
  const all = r("DEEP") + r("MIXED") + r("WASTED");
  const work = r("DEEP") * share("DEEP") + r("MIXED") * share("MIXED") + r("WASTED") * share("WASTED");
  return {
    deep: -r("DEEP"), active: -all, workDeep: -Math.round(r("DEEP") * share("DEEP")), workActive: -Math.round(work),
    offWasted: -Math.round(r("WASTED") * (1 - share("WASTED"))), offActive: -Math.round(all - work),
  };
}

/** Merges per-device reports for one day. Phone stretches on the timeline carry d: "phone". */
export function mergeReports(parts: DeviceReport[]): DeviceReport | null {
  if (!parts.length) return null;
  // The Mac's report is the base (it has the day's own fields: dayStart, tz, halves, worst hour…).
  const sorted = [...parts].sort((x, y) => (x.device?.kind === "mac" ? -1 : 0) - (y.device?.kind === "mac" ? -1 : 0));
  const base = sorted[0];
  // Where the phone was in use, the Mac's overlapping time is cut (timeline, totals and sites), so nothing counts twice.
  const phoneSegs = sorted.filter((p) => p.device?.kind && p.device.kind !== "mac").flatMap((p) => (p.timeline ?? []).filter((x) => x.k !== "A"));
  const clipped = new Map<DeviceReport, Seg[]>();
  const removed: Record<string, number> = {};
  const trimmed = new Map<DeviceReport, Site[]>();
  for (const p of sorted) {
    if (p.device?.kind && p.device.kind !== "mac") continue;
    const r = clipToPhone(p.timeline ?? [], phoneSegs);
    clipped.set(p, r.pieces);
    for (const [k, v] of Object.entries(r.removed)) removed[k] = (removed[k] ?? 0) + v;
    trimmed.set(p, trimSites(p.topSites ?? [], r.removedByName, Object.values(r.removed).reduce((a, b) => a + b, 0)));
  }
  const sites = new Map<string, Site>();
  for (const p of sorted) for (const s of trimmed.get(p) ?? p.topSites ?? []) {
    const key = `${s.itemKey ?? s.name}\u0001${s.categoryId ?? s.category ?? ""}`;
    const cur = sites.get(key);
    sites.set(key, cur ? { ...cur, seconds: cur.seconds + s.seconds } : { ...s });
  }
  const worst = sorted.map((p) => p.worstHour).filter((w): w is NonNullable<typeof w> => !!w).sort((a, b) => b.wastedSec - a.wastedSec)[0];
  const { device: _d, ...rest } = base;
  return {
    ...rest,
    totals: subtract(sorted.reduce((t, p) => add(t, p.totals), {} as Levels), removed),
    // Work-hours totals lose the same share of each level as the day's totals.
    workTotals: (() => {
      const all = sorted.reduce((t, p) => add(t, p.totals), {} as Levels), work = sorted.reduce((t, p) => add(t, p.workTotals), {} as Levels);
      return subtract(work, Object.fromEntries(Object.entries(removed).map(([k, v]) => [k, all[k] ? Math.round(v * (work[k] ?? 0) / all[k]) : 0])));
    })(),
    timeline: sorted.flatMap((p) => (clipped.get(p) ?? p.timeline ?? []).map((s) => (p.device?.kind && p.device.kind !== "mac" ? { ...s, d: p.device.kind } : s))).sort((a, b) => a.a - b.a),
    deepSessions: sorted.flatMap((p) => p.deepSessions ?? []).sort((a, b) => a.a - b.a),
    distractionEvents: sorted.reduce((n, p) => n + (p.distractionEvents ?? 0), 0),
    ...(worst ? { worstHour: worst } : {}),
    topSites: [...sites.values()].sort((a, b) => b.seconds - a.seconds).slice(0, 20),
    devices: sorted.map((p) => p.device?.kind ?? "mac"),
    updatedAt: Math.max(...sorted.map((p) => p.updatedAt ?? 0)),
  };
}

/** A day's heat numbers, summed across devices (all additive). */
export interface DayParts { deep: number; active: number; workDeep: number; workActive: number; offWasted: number; offActive: number }

export function addParts(a: DayParts, b: DayParts): DayParts {
  return { deep: a.deep + b.deep, active: a.active + b.active, workDeep: a.workDeep + b.workDeep, workActive: a.workActive + b.workActive, offWasted: a.offWasted + b.offWasted, offActive: a.offActive + b.offActive };
}

/** Effective focus (same formula as the Mac's DayStat): focused × (0.5 + 0.5 × work focused %) × (0.8 + 0.2 × off-hours not wasted %). */
export function effective(p: DayParts): number {
  const on = p.workActive > 0 ? 0.5 + 0.5 * p.workDeep / p.workActive : 1;
  const off = p.offActive > 0 ? 0.8 + 0.2 * (p.offActive - p.offWasted) / p.offActive : 1;
  return Math.floor(p.deep * on * off);
}

const STREAK_SEC = 2.5 * 3600;

/**
 * The merged heat/year document from each device's days. [workDay] says whether a date (yyyy-MM-dd) had work hours;
 * days off never break the streak, and neither does [today] while it's under way.
 */
export function mergeHeat(devices: Record<string, DayParts>[], today: string, workDay: (date: string) => boolean): { days: Record<string, { e: number; f: number; p: number }>; totalSec: number; streak: number } {
  const sum: Record<string, DayParts> = {};
  for (const dev of devices) for (const [d, p] of Object.entries(dev)) sum[d] = sum[d] ? addParts(sum[d], p) : p;
  const days: Record<string, { e: number; f: number; p: number }> = {};
  let totalSec = 0;
  for (const [d, p] of Object.entries(sum).sort()) {
    if (p.active <= 0) continue;
    days[d] = { e: effective(p), f: p.deep, p: Math.floor((p.deep * 100) / p.active) };
    totalSec += p.deep;
  }
  let streak = 0;
  const cur = new Date(today + "T12:00:00Z");
  for (let i = 0; i < 400; i++) {
    const key = cur.toISOString().slice(0, 10);
    if ((days[key]?.e ?? 0) >= STREAK_SEC) streak++;
    else if (workDay(key) && key !== today) break;
    cur.setUTCDate(cur.getUTCDate() - 1);
  }
  return { days, totalSec, streak };
}

/** A device's private copy: titled rows and what each timeline name refers to (for changing its category). */
export interface DeviceItems { date: string; device?: { id: string; kind: string }; topSites?: Site[]; refs?: Record<string, { subject: string; itemKey?: string; title?: string; categoryId?: string; device?: string }> }

/**
 * The whole day from every device: the shared report, the private copy (titled rows, trimmed of overlap the same
 * way, plus the merged refs) and the negative heat parts for the overlap.
 */
export function mergeDay(reports: DeviceReport[], items: DeviceItems[]): { report: DeviceReport | null; items: { topSites: Site[]; refs: Record<string, unknown> }; overlap: DayParts | null } {
  const report = mergeReports(reports);
  const phoneSegs = reports.filter((p) => p.device?.kind && p.device.kind !== "mac").flatMap((p) => (p.timeline ?? []).filter((x) => x.k !== "A"));
  const removed: Record<string, number> = {};
  const sites = new Map<string, Site>();
  let macTotals: Levels = {}, macWork: Levels = {};
  for (const it of items) {
    const rep = reports.find((r) => r.device?.id === it.device?.id);
    let rows = it.topSites ?? [];
    if (rep && (!rep.device?.kind || rep.device.kind === "mac")) {
      const r = clipToPhone(rep.timeline ?? [], phoneSegs);
      for (const [k, v] of Object.entries(r.removed)) removed[k] = (removed[k] ?? 0) + v;
      rows = trimSites(rows, r.removedByName, Object.values(r.removed).reduce((a, b) => a + b, 0));
      macTotals = add(macTotals, rep.totals); macWork = add(macWork, rep.workTotals);
    }
    for (const s of rows) {
      const key = `${s.itemKey ?? s.name}\u0001${s.categoryId ?? s.category ?? ""}`;
      const cur = sites.get(key);
      sites.set(key, cur ? { ...cur, seconds: cur.seconds + s.seconds } : { ...s, ...(it.device?.kind === "phone" ? { device: "phone" } : {}) });
    }
  }
  const refs = Object.assign({}, ...items.map((it) => Object.fromEntries(Object.entries(it.refs ?? {}).map(([n, r]) => [n, { ...r, device: it.device?.kind ?? "mac" }]))));
  const any = Object.values(removed).some((v) => v > 0);
  return { report, items: { topSites: [...sites.values()].sort((a, b) => b.seconds - a.seconds).slice(0, 20), refs }, overlap: any ? overlapParts(removed, macTotals, macWork) : null };
}
