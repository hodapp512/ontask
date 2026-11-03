export type LevelKey = "DEEP" | "MIXED" | "WASTED" | "AWAY";
export const LEVELS: { key: LevelKey; label: string; color: string; short: string }[] = [
  { key: "DEEP", label: "Focused", color: "#2e9e5b", short: "D" },
  { key: "MIXED", label: "Mixed", color: "#e0a526", short: "M" },
  { key: "WASTED", label: "Wasted", color: "#d64545", short: "W" },
  { key: "AWAY", label: "Away", color: "#9aa5b1", short: "A" },
];

export function fmtDuration(sec: number): string {
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m` : `${Math.round(sec)}s`;
}

export function todayKey(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function deepShare(totals: Partial<Record<LevelKey, number>>): number | null {
  const denom = (totals.DEEP ?? 0) + (totals.MIXED ?? 0) + (totals.WASTED ?? 0);
  return denom < 600 ? null : Math.round(((totals.DEEP ?? 0) * 100) / denom);
}

/** Timeline segments: seconds from local midnight, k = D|M|W|A (Firestore forbids nested arrays, so these are maps). */
/** A timeline stretch: seconds after midnight, level letter (color), category label (c), and what it was made of (t). */
/** [d]: "phone" for a stretch on the phone. */
/** [also]: glances under 15 s drawn as part of this stretch (see [absorbShort]). */
export type Seg = { a: number; b: number; k: string; c?: string; t?: { n: string; s: number }[]; d?: string; also?: Seg[] };

/**
 * Glances under [minSec] (switching between windows) are drawn as part of the stretch before them, or after them
 * when nothing comes just before; the two sides of a glance join up. As on the Mac and the phone.
 */
export function absorbShort(segs: Seg[], minSec = 15): Seg[] {
  const out: Seg[] = [], pending: Seg[] = [];
  for (const r of [...segs].sort((x, y) => x.a - y.a)) {
    const last = pending.length === 0 && out.length && r.a - out[out.length - 1].b <= minSec ? out[out.length - 1] : undefined;
    if (r.b - r.a < minSec) {
      if (last) out[out.length - 1] = { ...last, b: Math.max(last.b, r.b), also: [...(last.also ?? []), { ...r, also: undefined }, ...(r.also ?? [])] };
      else pending.push(r);
      continue;
    }
    let cur = r;
    if (pending.length && cur.a - pending[pending.length - 1].b <= minSec) cur = { ...cur, a: pending[0].a, also: [...pending, ...(cur.also ?? [])] };
    else out.push(...pending);
    pending.length = 0;
    const prev = out[out.length - 1];
    if (prev && cur.a - prev.b <= minSec && prev.k === cur.k && prev.c === cur.c && prev.d === cur.d) {
      const t = new Map<string, number>();
      for (const x of [...(prev.t ?? []), ...(cur.t ?? [])]) t.set(x.n, (t.get(x.n) ?? 0) + x.s);
      out[out.length - 1] = { ...prev, b: Math.max(prev.b, cur.b), t: [...t].sort((x, y) => y[1] - x[1]).map(([n, s]) => ({ n, s })), also: [...(prev.also ?? []), ...(cur.also ?? [])] };
    } else out.push(cur);
  }
  out.push(...pending);
  return out;
}
/** Whole hours around the data (at least 2 h); the full day when there is none. */
/**
 * The hours to draw: around the time you were actually at the computer (as on the Mac). Away time doesn't widen it,
 * and a stray minute far from the rest (the Mac waking at night) is left out: active stretches within an hour of each
 * other form a session, and sessions with under 5 minutes are dropped unless nothing else is left.
 */
export function timelineWindow(segs: Seg[]): [number, number] {
  const active = segs.filter((s) => s.k !== "A").sort((p, q) => p.a - q.a);
  if (!active.length) return [0, 86400];
  const sessions: Seg[][] = [[active[0]]];
  for (const s of active.slice(1)) {
    const last = sessions[sessions.length - 1];
    if (s.a - Math.max(...last.map((x) => x.b)) <= 3600) last.push(s); else sessions.push([s]);
  }
  const big = sessions.filter((ss) => ss.reduce((n, x) => n + x.b - x.a, 0) >= 300);
  const kept = (big.length ? big : sessions).flat();
  let from = Math.floor(Math.min(...kept.map((s) => s.a)) / 3600) * 3600;
  let to = Math.min(86400, Math.ceil(Math.max(...kept.map((s) => s.b)) / 3600) * 3600);
  if (to - from < 7200) { to = Math.min(86400, from + 7200); from = Math.max(0, to - 7200); }
  return [from, to];
}

/** When the day started (epoch seconds) and in which time zone, so offsets can be shown as real clock times. */
export type DayClock = { start: number; tz: string };

/** "2:45 PM" for [t] seconds into the day: the real clock time when [day] is known, else midnight + t. */
export function clockAt(t: number, day?: DayClock): string {
  if (!day) return clock(Math.floor(t / 60));
  return new Date((day.start + t) * 1000).toLocaleTimeString("en-US", { timeZone: day.tz, hour: "numeric", minute: "2-digit" });
}

/**
 * Clock ticks for a window (seconds after midnight): the smallest step from 5 minutes to 6 hours that gives at most
 * five ticks, each at a round time ("10 AM", "10:15").
 */
export function timelineTicks(from: number, to: number, day?: DayClock): { t: number; label: string }[] {
  const step = [5, 10, 15, 30, 60, 120, 180, 240, 360].map((m) => m * 60).find((s) => (to - from) / s <= 5) ?? 21600;
  const out: { t: number; label: string }[] = [];
  for (let t = Math.ceil(from / step) * step; t <= to; t += step) {
    const c = clockAt(t, day);
    out.push({ t, label: c.includes(":00 ") ? c.replace(":00", "") : c.replace(/ [AP]M$/, "") });
  }
  return out;
}

/** The timeline as SVG plus clock ticks; [win] zooms to part of the day (default: the active part). */
export function timelineSvg(segs: Seg[], width = 320, height = 22, win: [number, number] = timelineWindow(segs), day?: DayClock): string {
  const color = (k: string) => LEVELS.find((l) => l.short === k)?.color ?? "#ccc";
  const [from, to] = win;
  const x = (t: number) => ((t - from) / (to - from)) * width;
  const rects = segs.filter((s) => s.b > from && s.a < to)
    .map((s) => `<rect data-i="${segs.indexOf(s)}" x="${Math.max(0, x(s.a))}" y="0" width="${Math.max(Math.min(width, x(s.b)) - Math.max(0, x(s.a)), 1)}" height="${height}" fill="${color(s.k)}"/>`).join("");
  // Labels near the edges hang inward so they stay on screen.
  const ticks = timelineTicks(from, to, day).map(({ t, label }) => {
    const p = ((t - from) / (to - from)) * 100;
    return `<span style="left:${p}%;transform:translateX(${p < 8 ? 0 : p > 92 ? -100 : -50}%)">${label}</span>`;
  }).join("");
  return `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" preserveAspectRatio="none" role="img" aria-label="Timeline"><rect width="${width}" height="${height}" fill="#e6ebe6" rx="4"/>${rects}</svg><div class="axis">${ticks}</div>`;
}

export const REACTIONS = [
  { kind: "thumbs_up", label: "👍" },
  { kind: "proud", label: "Proud of you" },
  { kind: "call_me", label: "Call me when you can" },
  { kind: "take_a_walk", label: "Take a walk" },
] as const;

export const MESSAGE_TYPES: { key: string; label: string }[] = [
  { key: "binge_alert", label: "Struggling alerts" },
  { key: "unblock_alert", label: "Unblocks during work hours" },
  { key: "checkin", label: "Daily report" },
  { key: "work_hours_change", label: "Work-hours changes" },
  { key: "work_unblock", label: "Work unblocks" },
];

/** "Dennis Hodapp" -> "Dennis". */
export const firstName = (name: string) => name.trim().split(/\s+/)[0] || name;

export function greeting(d = new Date()): string {
  const hr = d.getHours();
  return hr < 5 ? "Good evening" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
}

/** Minutes after midnight -> local clock time, e.g. 540 -> "9:00 AM". */
export function clock(min: number): string {
  const d = new Date(2000, 0, 1, Math.floor(min / 60) % 24, min % 60);
  return d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}

/** "just now", "5 min ago", "2 h ago", "3 days ago". */
export function ago(ms: number, now = Date.now()): string {
  const s = Math.max(0, (now - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  const days = Math.floor(s / 86400);
  return days === 1 ? "yesterday" : `${days} days ago`;
}

/** The stretch at [frac] (0..1 across the drawn window), or the nearest one within a few percent. */
export function segAt(segs: Seg[], frac: number, win: [number, number] = timelineWindow(segs)): Seg | undefined {
  const [from, to] = win;
  const t = from + frac * (to - from), slack = (to - from) * 0.015;
  return segs.find((s) => t >= s.a && t < s.b)
    ?? segs.filter((s) => t >= s.a - slack && t < s.b + slack).sort((p, q) => Math.min(Math.abs(t - p.a), Math.abs(t - p.b)) - Math.min(Math.abs(t - q.a), Math.abs(t - q.b)))[0];
}

/** "Entertainment / News / Social · 2:10 PM–2:40 PM · 30m — youtube.com – Funny Cat Video 25m". */
export function segDetail(s: Seg, day?: DayClock): string {
  const level = s.c ?? LEVELS.find((l) => l.short === s.k)?.label ?? "";
  const what = (s.t ?? []).map((x) => `${x.n} ${fmtDuration(x.s)}`).join(", ") || "no app recorded";
  const also = (s.also ?? []).flatMap((g) => g.t ?? []).map((x) => `${x.n} ${fmtDuration(x.s)}`).join(", ");
  return `${s.d === "phone" ? "📱 " : ""}${level} · ${clockAt(s.a, day)}–${clockAt(s.b, day)} · ${fmtDuration(s.b - s.a)} — ${what}${also ? ` (also: ${also})` : ""}`;
}

/** Dates everywhere read like "Nov 1, 2026" ([weekday]: "Sunday, Nov 1, 2026"). */
export function fmtDate(d: Date, weekday = false): string {
  return d.toLocaleDateString("en-US", { ...(weekday ? { weekday: "long" as const } : {}), month: "short", day: "numeric", year: "numeric" });
}

/** "Nov 1, 2026, 6:50 PM" */
export function fmtDateTime(ms: number): string {
  return `${fmtDate(new Date(ms))}, ${new Date(ms).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })}`;
}
