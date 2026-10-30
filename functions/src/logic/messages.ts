import type { MessageType } from "./types";

export interface EventDoc { type: string; ts: number; payload?: Record<string, unknown> }

/** Which Mac events become partner messages, and the text partners read. Returns null for events partners never see. */
export function partnerMessage(ev: EventDoc, name: string): { type: MessageType; text: string } | null {
  const p = ev.payload ?? {};
  const s = (k: string) => (p[k] === undefined || p[k] === null ? "" : String(p[k]));
  switch (ev.type) {
    case "binge_alert":
      return { type: "binge_alert", text: s("message") || `${name} has been drifting off task and wanted you to know.` };
    case "checkin":
      return { type: "checkin", text: s("message") || `${name} checked in.` };
    case "work_hours_change":
      return { type: "work_hours_change", text: `${name} reduced their work hours. Reason: "${s("reason")}". ${s("meaning")}` };
    case "work_unblock":
      return { type: "work_unblock", text: `${name} unblocked ${s("domain")} for ${s("minutes")} minutes for work: "${s("purpose")}". Their activity is being checked against that purpose.` };
    default:
      return null;
  }
}

export const GENTLE_CLOSE = "No need to reply — but a 👍 helps.";

/** Messages that should end with the low-effort prompt. */
export function withClose(type: MessageType, text: string): string {
  return type === "binge_alert" || type === "checkin" ? (text.includes("No need to reply") ? text : `${text}\n${GENTLE_CLOSE}`) : text;
}

/** TextBelt rejects URLs; BlockMe replaced "." with ",". Same here. */
export function textBeltSafe(text: string): string {
  return text.replace(/\./g, ",");
}
