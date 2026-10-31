export type LevelKey = "DEEP" | "MIXED" | "WASTED" | "AWAY";
export const LEVELS: { key: LevelKey; label: string; color: string; short: string }[] = [
  { key: "DEEP", label: "Deep focus", color: "#2e9e5b", short: "D" },
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
export type Seg = { a: number; b: number; k: string; t?: { n: string; s: number }[] };
/** Whole hours around the data (at least 2 h); the full day when there is none. */
export function timelineWindow(segs: Seg[]): [number, number] {
  if (!segs.length) return [0, 86400];
  let from = Math.floor(Math.min(...segs.map((s) => s.a)) / 3600) * 3600;
  let to = Math.min(86400, Math.ceil(Math.max(...segs.map((s) => s.b)) / 3600) * 3600);
  if (to - from < 7200) { to = Math.min(86400, from + 7200); from = Math.max(0, to - 7200); }
  return [from, to];
}

export function timelineSvg(segs: Seg[], width = 320, height = 22): string {
  const color = (k: string) => LEVELS.find((l) => l.short === k)?.color ?? "#ccc";
  const [from, to] = timelineWindow(segs);
  const x = (t: number) => ((t - from) / (to - from)) * width;
  const rects = segs.map(({ a, b, k }, i) => `<rect data-i="${i}" x="${x(a)}" y="0" width="${Math.max(x(b) - x(a), 1)}" height="${height}" fill="${color(k)}"/>`).join("");
  const hours = (to - from) / 3600, step = [1, 2, 3, 4, 6].find((n) => hours / n <= 5) ?? 6;
  const labels: string[] = [];
  for (let hr = 0; hr <= hours; hr += step) labels.push(`<span>${clock(from / 60 + hr * 60).replace(":00", "")}</span>`);
  return `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" preserveAspectRatio="none" role="img" aria-label="Timeline"><rect width="${width}" height="${height}" fill="#e6ebe6" rx="4"/>${rects}</svg><div class="axis">${labels.join("")}</div>`;
}

export const REACTIONS = [
  { kind: "thumbs_up", label: "👍" },
  { kind: "proud", label: "Proud of you" },
  { kind: "call_me", label: "Call me when you can" },
  { kind: "take_a_walk", label: "Take a walk" },
] as const;

export const MESSAGE_TYPES: { key: string; label: string }[] = [
  { key: "binge_alert", label: "Binge alerts" },
  { key: "checkin", label: "Daily check-ins" },
  { key: "work_hours_change", label: "Work-hours changes" },
  { key: "missed_checkin", label: "Missed check-ins" },
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
export function segAt(segs: Seg[], frac: number): Seg | undefined {
  const [from, to] = timelineWindow(segs);
  const t = from + frac * (to - from), slack = (to - from) * 0.015;
  return segs.find((s) => t >= s.a && t < s.b)
    ?? segs.filter((s) => t >= s.a - slack && t < s.b + slack).sort((p, q) => Math.min(Math.abs(t - p.a), Math.abs(t - p.b)) - Math.min(Math.abs(t - q.a), Math.abs(t - q.b)))[0];
}

/** "Wasted · 2:10 PM–2:40 PM · 30m — youtube.com 25m, reddit.com 5m". */
export function segDetail(s: Seg): string {
  const level = LEVELS.find((l) => l.short === s.k)?.label ?? "";
  const what = (s.t ?? []).map((x) => `${x.n} ${fmtDuration(x.s)}`).join(", ") || (s.k === "A" ? "away from the computer" : "no app recorded");
  return `${level} · ${clock(Math.floor(s.a / 60))}–${clock(Math.floor(s.b / 60))} · ${fmtDuration(s.b - s.a)} — ${what}`;
}
