import { Channel, DEFAULT_PARTNER_PREFS, MessageType, PartnerPrefs } from "./types";

export function localParts(now: Date, tz: string): { hour: number; minute: number; isoWeekday: number; date: string } {
  const f = new Intl.DateTimeFormat("en-CA", { timeZone: tz, hour12: false, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", weekday: "short" });
  const parts = Object.fromEntries(f.formatToParts(now).map((x) => [x.type, x.value]));
  const wd = ({ Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 } as Record<string, number>)[parts.weekday];
  return { hour: Number(parts.hour) % 24, minute: Number(parts.minute), isoWeekday: wd, date: `${parts.year}-${parts.month}-${parts.day}` };
}

const toMin = (hhmm: string) => { const [h, m] = hhmm.split(":").map(Number); return h * 60 + m; };

export function inQuietHours(prefs: PartnerPrefs, now: Date): boolean {
  const q = prefs.quietHours;
  if (!q) return false;
  const { hour, minute } = localParts(now, q.tz);
  const cur = hour * 60 + minute, a = toMin(q.start), b = toMin(q.end);
  return a === b ? false : a < b ? cur >= a && cur < b : cur >= a || cur < b; // window may wrap midnight
}

export interface Decision {
  channel: Channel;
  /** true: hold until quiet hours end (the next scheduled sweep delivers it) */
  defer: boolean;
  fellBackToText?: boolean;
}

export interface Reachability { lastSeenMs?: number; hasPushToken: boolean; hasPhone: boolean; hasEmail: boolean }

/**
 * Chooses how to deliver. Push is the default; text is the fallback when the PWA hasn't been opened in a while
 * (or there is no push token). A channel without contact details degrades to the next available one.
 */
export function decide(prefs: PartnerPrefs | undefined, type: MessageType, now: Date, r: Reachability, staleAfterDays = 3): Decision {
  const p = prefs ?? DEFAULT_PARTNER_PREFS;
  let channel = p.channels[type] ?? "push";
  if (channel === "off") return { channel: "off", defer: false };
  let fellBack = false;
  if (channel === "push") {
    const stale = r.lastSeenMs !== undefined && now.getTime() - r.lastSeenMs > staleAfterDays * 86_400_000;
    if (!r.hasPushToken || stale) {
      if (r.hasPhone) { channel = "text"; fellBack = true; }
      else if (r.hasEmail) { channel = "email"; fellBack = true; }
    }
  }
  if (channel === "text" && !r.hasPhone) channel = r.hasEmail ? "email" : "off";
  if (channel === "email" && !r.hasEmail) channel = r.hasPhone ? "text" : "off";
  return { channel, defer: inQuietHours(p, now), fellBackToText: fellBack && channel === "text" };
}

/** Alerts about drifting share one budget; everything else (check-ins, work-hours changes, …) is never capped. */
export const DRIFT_TYPES: readonly MessageType[] = ["binge_alert", "unblock_alert"];
export const DRIFT_ALERTS_PER_HALF_DAY = 2;

/** Start of the current half-day — midnight to noon, noon to midnight — in [tz], as epoch ms. */
export function halfDayStart(now: Date, tz: string): number {
  const { hour, minute } = localParts(now, tz);
  return now.getTime() - (((hour % 12) * 60 + minute) * 60_000 + now.getUTCSeconds() * 1000 + now.getUTCMilliseconds());
}

/** At most 2 drifting alerts in the morning and 2 in the afternoon; extras are listed in the evening review. */
export function driftAllowed(sentThisHalfDay: number, cap = DRIFT_ALERTS_PER_HALF_DAY): boolean {
  return sentThisHalfDay < cap;
}
