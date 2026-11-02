import type { HabitDef, ScheduleDoc } from "./types";
import { localParts } from "./routing";

const toMin = (hhmm: string) => { const [h, m] = hhmm.split(":").map(Number); return h * 60 + m; };

/** Habits due in the window (now-windowMin, now], so a 5-minute sweep never misses or double-fires one. */
export function habitsDue(habits: HabitDef[], tz: string, now: Date, windowMin = 5): HabitDef[] {
  const { hour, minute, isoWeekday } = localParts(now, tz);
  const cur = hour * 60 + minute;
  return habits.filter((h) => {
    if (!h.enabled || !h.days.includes(isoWeekday)) return false;
    const t = toMin(h.time);
    const diff = (cur - t + 1440) % 1440;
    return diff >= 0 && diff < windowMin;
  });
}

/** The night ends at 5 AM. */
export const NIGHT_END_MIN = 5 * 60;
/** "In use" = the Mac or the OnTask phone app was used this recently. */
export const IN_USE_MS = 5 * 60_000;

/** The night [now] falls in (the date bedtime was on, yyyy-MM-dd), or null between 5 AM and bedtime. */
export function bedtimeNight(s: ScheduleDoc, now: Date): string | null {
  if (s.bedtimeMin === undefined || s.bedtimeMin === null) return null;
  const { hour, minute, date } = localParts(now, s.tz);
  const cur = hour * 60 + minute, bed = s.bedtimeMin;
  const yesterday = () => localParts(new Date(now.getTime() - 86_400_000), s.tz).date;
  if (bed >= NIGHT_END_MIN) return cur >= bed ? date : cur < NIGHT_END_MIN ? yesterday() : null;
  return cur >= bed && cur < NIGHT_END_MIN ? yesterday() : null; // bedtime after midnight
}

/**
 * The bedtime push goes out once per night, and only while something is in use (the Mac, or the OnTask phone app
 * open, within the last 5 minutes), so it never wakes a phone lying on the nightstand. Not in use at bedtime: it
 * comes the moment you pick something up later that night.
 */
export function bedtimePushDue(s: ScheduleDoc, now: Date, lastUsedMs: number | undefined, alreadyPushed: boolean): boolean {
  return !alreadyPushed && bedtimeNight(s, now) !== null && lastUsedMs !== undefined && now.getTime() - lastUsedMs <= IN_USE_MS;
}

export function rangesFor(s: ScheduleDoc, now: Date): { startMin: number; endMin: number }[] {
  const { date, isoWeekday } = localParts(now, s.tz);
  return s.exceptions?.[date] ?? s.workWeek[String(isoWeekday)] ?? [];
}

export function workEndMin(s: ScheduleDoc, now: Date): number | null {
  const r = rangesFor(s, now);
  return r.length ? Math.max(...r.map((x) => x.endMin)) : null;
}

export type CheckinState = "not_due" | "remind" | "done" | "no_work_day";

/**
 * Evening check-in state: phone reminders escalate every 15 min after work ends. There's no "missed check-in"
 * message any more: the daily report goes to the partner either way, saying "No reflection today".
 */
export function checkinState(s: ScheduleDoc, done: boolean, now: Date): { state: CheckinState; minutesSinceEnd: number; level: number } {
  const end = workEndMin(s, now);
  if (end === null) return { state: "no_work_day", minutesSinceEnd: 0, level: 0 };
  if (done) return { state: "done", minutesSinceEnd: 0, level: 0 };
  const { hour, minute } = localParts(now, s.tz);
  const since = hour * 60 + minute - end;
  if (since < 0) return { state: "not_due", minutesSinceEnd: since, level: 0 };
  return { state: "remind", minutesSinceEnd: since, level: Math.min(3, 1 + Math.floor(since / 15)) };
}
