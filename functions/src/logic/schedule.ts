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

export function rangesFor(s: ScheduleDoc, now: Date): { startMin: number; endMin: number }[] {
  const { date, isoWeekday } = localParts(now, s.tz);
  return s.exceptions?.[date] ?? s.workWeek[String(isoWeekday)] ?? [];
}

export function workEndMin(s: ScheduleDoc, now: Date): number | null {
  const r = rangesFor(s, now);
  return r.length ? Math.max(...r.map((x) => x.endMin)) : null;
}

export type CheckinState = "not_due" | "remind" | "missed" | "done" | "no_work_day";

/**
 * Evening check-in state. Reminders escalate every 15 min after work ends; once the grace period (default 2 h)
 * has passed without a check-in the partner is told (unless configured otherwise).
 */
export function checkinState(s: ScheduleDoc, done: boolean, now: Date): { state: CheckinState; minutesSinceEnd: number; level: number } {
  const end = workEndMin(s, now);
  if (end === null) return { state: "no_work_day", minutesSinceEnd: 0, level: 0 };
  if (done) return { state: "done", minutesSinceEnd: 0, level: 0 };
  const { hour, minute } = localParts(now, s.tz);
  const since = hour * 60 + minute - end;
  if (since < 0) return { state: "not_due", minutesSinceEnd: since, level: 0 };
  const grace = s.missedCheckinGraceMin ?? 120;
  if (grace > 0 && since >= grace) return { state: "missed", minutesSinceEnd: since, level: 0 };
  return { state: "remind", minutesSinceEnd: since, level: Math.min(3, 1 + Math.floor(since / 15)) };
}
