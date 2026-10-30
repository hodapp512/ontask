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
export type Seg = { a: number; b: number; k: string };
export function timelineSvg(segs: Seg[], width = 320, height = 22): string {
  const color = (k: string) => LEVELS.find((l) => l.short === k)?.color ?? "#ccc";
  const rects = segs.map(({ a, b, k }) => `<rect x="${(a / 86400) * width}" y="0" width="${Math.max(((b - a) / 86400) * width, 1)}" height="${height}" fill="${color(k)}"/>`).join("");
  return `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" preserveAspectRatio="none" role="img" aria-label="Timeline"><rect width="${width}" height="${height}" fill="#e6ebe6" rx="4"/>${rects}</svg>`;
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
