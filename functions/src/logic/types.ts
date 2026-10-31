export type Channel = "push" | "text" | "email" | "off";

/** Message types a partner can configure (DESIGN.md: "Partner settings"). */
export const MESSAGE_TYPES = ["binge_alert", "unblock_alert", "checkin", "work_hours_change", "missed_checkin", "work_unblock"] as const;
export type MessageType = (typeof MESSAGE_TYPES)[number];

export interface PartnerPrefs {
  channels: Record<MessageType, Channel>;
  /** Local quiet hours in the partner's timezone, "HH:mm". Messages in this window are held until it ends. */
  quietHours?: { start: string; end: string; tz: string } | null;
}

export const DEFAULT_PARTNER_PREFS: PartnerPrefs = {
  channels: { binge_alert: "push", unblock_alert: "push", checkin: "push", work_hours_change: "push", missed_checkin: "push", work_unblock: "push" },
  quietHours: null,
};

export interface HabitDef { id: string; name: string; time: string; days: number[]; enabled: boolean }

export interface ScheduleDoc {
  tz: string;
  /** ISO weekday (1=Mon..7=Sun) -> [{startMin,endMin}] */
  workWeek: Record<string, { startMin: number; endMin: number }[]>;
  /** yyyy-MM-dd -> ranges (empty = day off) */
  exceptions?: Record<string, { startMin: number; endMin: number }[]>;
  habits: HabitDef[];
  missedCheckinGraceMin?: number;       // default 120; 0 = never; -1 = summary only
}
