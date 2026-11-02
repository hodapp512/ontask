import { localParts } from "./routing";
import { workEndMin } from "./schedule";
import type { ScheduleDoc } from "./types";

/** Days off start counting at 6 PM; the report goes by 8 PM at the latest (or when work ends, if later). */
export const DAY_OFF_START_MIN = 18 * 60;
export const LATEST_MIN = 20 * 60;
export const IDLE_MIN = 30;

/**
 * Is it time for today's daily report? After work hours end (6 PM on a day off), once you've been inactive for 30
 * minutes — or at 8 PM regardless. [lastActiveMs] is when the Mac last saw you; a closed laptop stops updating it.
 */
export function reportDue(s: ScheduleDoc, now: Date, lastActiveMs: number | undefined): boolean {
  const { hour, minute } = localParts(now, s.tz);
  const cur = hour * 60 + minute;
  const start = workEndMin(s, now) ?? DAY_OFF_START_MIN;
  if (cur < start) return false;
  if (cur >= Math.max(LATEST_MIN, start)) return true;
  const startMs = now.getTime() - (cur - start) * 60_000;
  return now.getTime() - Math.max(lastActiveMs ?? 0, startMs) >= IDLE_MIN * 60_000;
}

export interface ReportDoc {
  totals?: Record<string, number>;
  deepSessions?: { a: number; b: number }[];
  distractionEvents?: number;
  topSites?: { name: string; category?: string; seconds: number }[];
  /** Focused % before and after lunch (or noon). */
  halves?: { morning?: number; afternoon?: number };
}
export interface MiddayDoc { done?: string; should?: string; chips?: string }
export interface CheckinDoc { midday?: MiddayDoc; evening?: boolean; plan?: string; feeling?: string; helped?: string; hurt?: string; change?: string; note?: string }

/** "2026-11-01" → "Nov 1, 2026" (the date style used everywhere). */
export function fmtDate(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-US", { timeZone: "UTC", month: "short", day: "numeric", year: "numeric" });
}

export function fmtDuration(sec: number): string {
  const m = Math.round(sec / 60);
  return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}`;
}

const LEVELS: [string, string, string][] = [["DEEP", "Focused", "#2e9e5b"], ["MIXED", "Mixed", "#e0a526"], ["WASTED", "Wasted", "#d64545"]];
/** "Morning 64% focused · Afternoon 48%", or null when neither half had enough screen time. */
export function halvesLine(r?: ReportDoc): string | null {
  const h = r?.halves;
  if (h?.morning == null && h?.afternoon == null) return null;
  return [h.morning != null ? `Morning ${h.morning}% focused` : null, h.afternoon != null ? `Afternoon ${h.afternoon}% focused` : null].filter(Boolean).join(" · ");
}

/** The midday answers as label/value rows. */
export function middayRows(m?: MiddayDoc): [string, string][] {
  if (!m) return [];
  return ([["Got done by midday", m.done], ["Should have", m.should], ["In the way", m.chips]] as [string, string | undefined][])
    .filter((r): r is [string, string] => !!r[1]);
}

const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** [title]: "Here is Dennis's day" or "Your day"; [who]: "Dennis" or "You", for "didn't use the computer today". */
export interface ReportParts { title: string; who: string; report?: ReportDoc; checkin?: CheckinDoc; alsoToday?: string[]; link: string }

export const partnerParts = (first: string) => ({ title: `Here is ${first}'s day`, who: first });
export const ownParts = { title: "Your day", who: "You" };

function facts(p: ReportParts) {
  const t = p.report?.totals ?? {};
  const active = LEVELS.reduce((n, [k]) => n + (t[k] ?? 0), 0);
  const sessions = [...(p.report?.deepSessions ?? [])].sort((a, b) => (b.b - b.a) - (a.b - a.a));
  return { t, active, sessions, sites: (p.report?.topSites ?? []).slice(0, 6) };
}

/** The daily report as text (push, text message, and the email's plain-text part). */
export function reportText(p: ReportParts): string {
  const { t, active, sessions, sites } = facts(p);
  const lines: string[] = [`${p.title}.`];
  if (!active) lines.push(`${p.who} didn't use the computer today.`);
  else {
    const share = Math.round(((t.DEEP ?? 0) / active) * 100);
    lines.push(`${share}% focused · ` + LEVELS.map(([k, l]) => `${l} ${fmtDuration(t[k] ?? 0)}`).join(" · "));
    const halves = halvesLine(p.report);
    if (halves) lines.push(halves);
    if (sessions.length) lines.push(`Longest focused session ${fmtDuration(sessions[0].b - sessions[0].a)} (${sessions.length} in all)`);
    if (sites.length) lines.push("Where the time went: " + sites.map((s) => `${s.name} ${fmtDuration(s.seconds)}`).join(", "));
  }
  const c = p.checkin;
  if (c?.plan) lines.push(`Plan: ${c.plan.replace(/\n/g, "; ")}`);
  middayRows(c?.midday).forEach(([l, v]) => lines.push(`${l}: ${v}`));
  if (c?.evening) {
    lines.push(`Felt: ${c.feeling ?? "—"}`);
    if (c.helped) lines.push(`Helped: ${c.helped}`);
    if (c.hurt) lines.push(`Hurt: ${c.hurt}`);
    if (c.change) lines.push(`Tomorrow: ${c.change}`);
    if (c.note) lines.push(`"${c.note}"`);
  } else lines.push("No reflection today.");
  if (p.alsoToday?.length) lines.push(`Also today: ${p.alsoToday.join(" · ")}`);
  lines.push(`Full day: ${p.link}`);
  return lines.join("\n");
}

/** The same report as a simple formatted email. */
export function reportHtml(p: ReportParts): string {
  const { t, active, sessions, sites } = facts(p);
  const c = p.checkin;
  const bar = active ? LEVELS.map(([k, , color]) => `<td style="background:${color};height:10px;width:${((t[k] ?? 0) / active) * 100}%"></td>`).join("") : "";
  const row = (label: string, value: string) => `<tr><td style="color:#667;padding:2px 12px 2px 0;vertical-align:top">${label}</td><td style="padding:2px 0">${value}</td></tr>`;
  const parts = [
    `<h2 style="margin:0 0 4px">${esc(p.title)}</h2>`,
    active ? `<p style="margin:0 0 8px;font-size:18px">${Math.round(((t.DEEP ?? 0) / active) * 100)}% focused</p><table style="width:100%;border-collapse:collapse;border-radius:4px;overflow:hidden"><tr>${bar}</tr></table>
      <p style="color:#556">${LEVELS.map(([k, l]) => `${l} ${fmtDuration(t[k] ?? 0)}`).join(" · ")}${p.report?.distractionEvents ? ` · ${p.report.distractionEvents} distractions` : ""}</p>`
      : `<p>${esc(p.who)} didn't use the computer today.</p>`,
    active && halvesLine(p.report) ? `<p style="margin:0;color:#556">${esc(halvesLine(p.report)!)}</p>` : "",
    sessions.length ? `<h3 style="margin:16px 0 4px">Longest focused sessions</h3><p style="margin:0">${sessions.slice(0, 3).map((s) => fmtDuration(s.b - s.a)).join(" · ")}</p>` : "",
    sites.length ? `<h3 style="margin:16px 0 4px">Where the time went</h3><table>${sites.map((s) => row(fmtDuration(s.seconds), `${esc(s.name)}${s.category ? ` <span style="color:#889">· ${esc(s.category)}</span>` : ""}`)).join("")}</table>` : "",
    c?.plan ? `<h3 style="margin:16px 0 4px">Plan</h3><p style="margin:0;white-space:pre-wrap">${esc(c.plan)}</p>` : "",
    middayRows(c?.midday).length ? `<h3 style="margin:16px 0 4px">Midday check-in</h3><table>${middayRows(c?.midday).map(([l, v]) => row(l, esc(v))).join("")}</table>` : "",
    `<h3 style="margin:16px 0 4px">Evening check-in</h3>` + (c?.evening
      ? `<table>${row("Felt", esc(c.feeling ?? "—"))}${c.helped ? row("Helped", esc(c.helped)) : ""}${c.hurt ? row("Hurt", esc(c.hurt)) : ""}${c.change ? row("Tomorrow", esc(c.change)) : ""}</table>${c.note ? `<p style="margin:8px 0 0">“${esc(c.note)}”</p>` : ""}`
      : `<p style="margin:0;color:#667">No reflection today.</p>`),
    p.alsoToday?.length ? `<h3 style="margin:16px 0 4px">Also today</h3><p style="margin:0">${p.alsoToday.map(esc).join("<br>")}</p>` : "",
    `<p style="margin:20px 0"><a href="${p.link}" style="background:#2563eb;color:#fff;padding:10px 16px;border-radius:8px;text-decoration:none">Open today in OnTask</a></p>`,
  ];
  return `<div style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:560px;color:#123">${parts.join("")}</div>`;
}
