import { describe, expect, it, vi } from "vitest";
import { partnerMessage, textBeltSafe, withClose } from "../src/logic/messages";
import { decide, driftAllowed, halfDayStart, inQuietHours, localParts } from "../src/logic/routing";
import { bedtimeNight, bedtimePushDue, checkinState, habitsDue } from "../src/logic/schedule";
import { fmtDate, ownParts, partnerParts, reportDue, reportHtml, reportText } from "../src/logic/report";
import { buildPrompt, ollamaLibraryUrl, parseCandidates, validateCandidates } from "../src/logic/modelrec";
import { DEFAULT_PARTNER_PREFS, type PartnerPrefs, type ScheduleDoc } from "../src/logic/types";

const at = (iso: string) => new Date(iso);

describe("messages", () => {
  it("maps events to partner messages and ignores private ones", () => {
    expect(partnerMessage({ type: "work_unblock", ts: 1, payload: { domain: "youtube.com", minutes: 60, purpose: "Acme onboarding" } }, "Sam")!.text).toContain("Acme onboarding");
    expect(partnerMessage({ type: "checkin", ts: 1, payload: { rating: 4 } }, "Sam Rivera")).toBeNull(); // goes out in the daily report
    expect(partnerMessage({ type: "unblock", ts: 1, payload: { domains: "reddit.com,x.com", minutes: 10, count_today: 2, work_hours: true } }, "Sam Rivera")!)
      .toEqual({ type: "unblock_alert", text: "Sam unblocked reddit.com, x.com for 10 minutes during work hours (2nd today, not for work)." });
    expect(partnerMessage({ type: "unblock_leisure", ts: 1, payload: { domains: "reddit.com", minutes: 30 } }, "Sam")).toBeNull();
    expect(partnerMessage({ type: "work_hours_change", ts: 1, payload: { reason: "Doctor", meaning: "Blocking off." } }, "Sam")!.text).toContain("Doctor");
    expect(partnerMessage({ type: "unblock", ts: 1, payload: {} }, "Sam")).toBeNull();
    expect(partnerMessage({ type: "focus_start", ts: 1 }, "Sam")).toBeNull();
  });
  it("adds the low-effort close once, only to alerts and check-ins", () => {
    expect(withClose("checkin", "Hi")).toContain("a 👍 helps");
    expect(withClose("checkin", withClose("checkin", "Hi")).match(/No need to reply/g)).toHaveLength(1);
    expect(withClose("work_unblock", "x")).toBe("x");
  });
  it("makes text safe for TextBelt", () => expect(textBeltSafe("a.b.com")).toBe("a,b,com"));
});

describe("routing", () => {
  const reach = { hasPushToken: true, hasPhone: true, hasEmail: true, lastSeenMs: at("2026-10-30T12:00:00Z").getTime() };
  it("defaults to push", () => expect(decide(undefined, "checkin", at("2026-10-30T13:00:00Z"), reach).channel).toBe("push"));
  it("falls back to text when the PWA hasn't been opened in a while", () => {
    const d = decide(DEFAULT_PARTNER_PREFS, "checkin", at("2026-11-10T13:00:00Z"), reach);
    expect(d.channel).toBe("text"); expect(d.fellBackToText).toBe(true);
  });
  it("respects off and degrades missing contact details", () => {
    const off: PartnerPrefs = { ...DEFAULT_PARTNER_PREFS, channels: { ...DEFAULT_PARTNER_PREFS.channels, checkin: "off" } };
    expect(decide(off, "checkin", new Date(), reach).channel).toBe("off");
    const txt: PartnerPrefs = { ...DEFAULT_PARTNER_PREFS, channels: { ...DEFAULT_PARTNER_PREFS.channels, checkin: "text" } };
    expect(decide(txt, "checkin", new Date(), { ...reach, hasPhone: false }).channel).toBe("email");
    expect(decide(txt, "checkin", new Date(), { hasPushToken: false, hasPhone: false, hasEmail: false }).channel).toBe("off");
  });
  it("holds messages during quiet hours that wrap midnight, in the partner's timezone", () => {
    const p: PartnerPrefs = { ...DEFAULT_PARTNER_PREFS, quietHours: { start: "22:00", end: "07:00", tz: "America/Chicago" } };
    expect(inQuietHours(p, at("2026-10-30T04:00:00Z"))).toBe(true);   // 23:00 CDT
    expect(inQuietHours(p, at("2026-10-30T14:00:00Z"))).toBe(false);  // 09:00 CDT
    expect(decide(p, "checkin", at("2026-10-30T04:00:00Z"), reach).defer).toBe(true);
  });
  it("allows two drifting alerts per morning and per afternoon", () => {
    expect(driftAllowed(1)).toBe(true); expect(driftAllowed(2)).toBe(false);
    // 10:30 Chicago (CDT, UTC-5) -> the morning began at local midnight; 15:10 -> the afternoon began at noon.
    expect(new Date(halfDayStart(new Date("2026-10-30T15:30:00Z"), "America/Chicago")).toISOString()).toBe("2026-10-30T05:00:00.000Z");
    expect(new Date(halfDayStart(new Date("2026-10-30T20:10:00Z"), "America/Chicago")).toISOString()).toBe("2026-10-30T17:00:00.000Z");
  });
  it("computes local time parts", () => expect(localParts(at("2026-10-30T14:30:00Z"), "America/Chicago")).toMatchObject({ hour: 9, minute: 30, isoWeekday: 5, date: "2026-10-30" }));
});

describe("schedule", () => {
  const s: ScheduleDoc = {
    tz: "America/Chicago", workWeek: { "5": [{ startMin: 540, endMin: 1020 }] },
    habits: [{ id: "p", name: "Morning prayer", time: "06:30", days: [1, 2, 3, 4, 5, 6, 7], enabled: true }],
  };
  it("fires a habit once in its 5-minute window", () => {
    expect(habitsDue(s.habits, s.tz, at("2026-10-30T11:32:00Z"))).toHaveLength(1);   // 06:32 CDT
    expect(habitsDue(s.habits, s.tz, at("2026-10-30T11:36:00Z"))).toHaveLength(0);
    expect(habitsDue(s.habits, s.tz, at("2026-10-30T11:29:00Z"))).toHaveLength(0);
  });
  it("respects day-of-week and enabled", () => {
    expect(habitsDue([{ ...s.habits[0], days: [1] }], s.tz, at("2026-10-30T11:32:00Z"))).toHaveLength(0);
    expect(habitsDue([{ ...s.habits[0], enabled: false }], s.tz, at("2026-10-30T11:32:00Z"))).toHaveLength(0);
  });
  it("escalates reminders after work ends, and never turns into a missed check-in", () => {
    expect(checkinState(s, false, at("2026-10-30T21:00:00Z")).state).toBe("not_due");                       // 16:00 local
    expect(checkinState(s, false, at("2026-10-30T22:20:00Z"))).toMatchObject({ state: "remind", level: 2 }); // 17:20
    expect(checkinState(s, false, at("2026-10-31T00:05:00Z"))).toMatchObject({ state: "remind", level: 3 }); // 19:05
    expect(checkinState(s, true, at("2026-10-31T00:05:00Z")).state).toBe("done");
    expect(checkinState(s, false, at("2026-10-31T20:00:00Z")).state).toBe("no_work_day");                    // Saturday
  });
});

describe("bedtime push", () => {
  const s: ScheduleDoc = { tz: "America/Chicago", workWeek: {}, habits: [], bedtimeMin: 21 * 60 + 30 };
  const t = (iso: string) => at(iso).getTime();
  it("belongs to the night from bedtime to 5 AM", () => {
    expect(bedtimeNight(s, at("2026-11-02T03:29:00Z"))).toBeNull();          // 9:29 PM CST Nov 1
    expect(bedtimeNight(s, at("2026-11-02T03:31:00Z"))).toBe("2026-11-01");
    expect(bedtimeNight(s, at("2026-11-02T08:00:00Z"))).toBe("2026-11-01");  // 2 AM
    expect(bedtimeNight(s, at("2026-11-02T11:30:00Z"))).toBeNull();          // 5:30 AM
    expect(bedtimeNight({ ...s, bedtimeMin: undefined }, at("2026-11-02T03:31:00Z"))).toBeNull();
  });
  it("pushes once a night, at bedtime, whether or not anything is in use", () => {
    expect(bedtimePushDue(s, at("2026-11-02T03:31:00Z"), false)).toBe(true);
    expect(bedtimePushDue(s, at("2026-11-02T03:36:00Z"), true)).toBe(false);   // already pushed tonight
    expect(bedtimePushDue(s, at("2026-11-02T03:29:00Z"), false)).toBe(false);  // before bedtime
  });
});

describe("daily report", () => {
  // Fridays 9:00-17:00 in Chicago (UTC-5 on these dates).
  const s: ScheduleDoc = { tz: "America/Chicago", workWeek: { "5": [{ startMin: 540, endMin: 1020 }] }, habits: [] };
  const local = (day: number) => (hhmm: string) => new Date(Date.UTC(2026, 9, day, Number(hhmm.slice(0, 2)) + 5, Number(hhmm.slice(3))));
  const fri = local(30), sat = local(31);
  it("waits for work to end, then for 30 minutes without activity", () => {
    expect(reportDue(s, fri("16:50"), fri("16:40").getTime())).toBe(false);   // still working hours
    expect(reportDue(s, fri("17:20"), fri("17:10").getTime())).toBe(false);   // active 10 min ago
    expect(reportDue(s, fri("17:45"), fri("17:10").getTime())).toBe(true);    // 35 min without activity
    expect(reportDue(s, fri("17:20"), fri("16:00").getTime())).toBe(false);   // left early: 30 min counted from 5 PM
    expect(reportDue(s, fri("17:30"), fri("16:00").getTime())).toBe(true);
    expect(reportDue(s, fri("17:31"), undefined)).toBe(true);                 // laptop closed, no recent signal
  });
  it("starts at 6 PM on a day off and goes by 8 PM regardless", () => {
    expect(reportDue(s, sat("17:00"), sat("10:00").getTime())).toBe(false);
    expect(reportDue(s, sat("18:30"), sat("10:00").getTime())).toBe(true);
    expect(reportDue(s, sat("19:59"), sat("19:58").getTime())).toBe(false);
    expect(reportDue(s, sat("20:00"), sat("19:59").getTime())).toBe(true);    // still active at 8 PM
  });
  const report = { totals: { DEEP: 3 * 3600, MIXED: 3600, WASTED: 1800 }, deepSessions: [{ a: 0, b: 40 * 60 }, { a: 0, b: 90 * 60 }], topSites: [{ name: "IntelliJ IDEA", category: "Work", seconds: 7200 }] };
  it("says how the day went, with the check-in or without", () => {
    const t = reportText({ ...partnerParts("Sam"), report, checkin: { evening: true, plan: "Ship the report", feeling: "🙂 Okay", note: "Long day" }, alsoToday: ["Sam unblocked x.com (3:10 PM)"], link: "https://x/#/" });
    expect(t).toContain("Here is Sam's day.");
    expect(t).toContain("67% focused");
    expect(t).toContain("Longest focused session 1h 30m (2 in all)");
    expect(t).toContain("Plan: Ship the report");
    expect(t).toContain('"Long day"');
    expect(t).toContain("Also today: Sam unblocked x.com (3:10 PM)");
    expect(reportText({ ...partnerParts("Sam"), report, link: "l" })).toContain("No reflection today.");
    expect(reportText({ ...ownParts, link: "l" })).toContain("You didn't use the computer today.");
  });
  it("writes dates like Nov 1, 2026", () => expect(fmtDate("2026-11-01")).toBe("Nov 1, 2026"));
  it("escapes what people typed in the email", () => {
    const h = reportHtml({ ...ownParts, report, checkin: { evening: true, note: "<script>x</script>" }, link: "https://x/#/" });
    expect(h).toContain("&lt;script&gt;");
    expect(h).toContain("Open today in OnTask");
  });
});

describe("model recommendation", () => {
  it("parses fenced output and drops hallucinated ids", async () => {
    const c = parseCandidates('```json\n[{"id":"qwen3-vl:8b","source":"ollama","sizeGb":6,"rationale":"x","sources":["u"]},{"id":"fake/model","source":"huggingface"}]\n```');
    expect(c).toHaveLength(2);
    const exists = vi.fn(async (x: { id: string }) => x.id === "qwen3-vl:8b");
    const { valid, dropped } = await validateCandidates(c, exists);
    expect(valid.map((v) => v.id)).toEqual(["qwen3-vl:8b"]); expect(dropped).toEqual(["fake/model"]);
  });
  it("tolerates garbage and builds URLs/prompts", async () => {
    expect(parseCandidates("nothing here")).toEqual([]);
    expect(ollamaLibraryUrl("qwen3-vl:4b")).toBe("https://ollama.com/library/qwen3-vl:4b");
    expect(buildPrompt("Qwen3-VL 4B", "M2 16GB")).toContain("Qwen3-VL 4B");
    expect((await validateCandidates([{ id: "x", source: "ollama", sizeGb: 1, rationale: "", sources: [] }], async () => { throw new Error("net"); })).dropped).toEqual(["x"]);
  });
});

import { LINK_ALPHABET, makeLinkCode, normalizeLinkCode } from "../src/logic/linkcode";
import { randomBytes } from "node:crypto";

describe("link codes", () => {
  it("are 5 easy-to-read characters", () => {
    for (let i = 0; i < 200; i++) expect(makeLinkCode((n) => randomBytes(n))).toMatch(new RegExp(`^[${LINK_ALPHABET}]{5}$`));
    expect(LINK_ALPHABET).not.toMatch(/[01OI]/);
  });
  it("accept sloppy input but reject malformed codes", () => {
    expect(normalizeLinkCode(" k7m-2q ")).toBe("K7M2Q");
    expect(normalizeLinkCode("K7M2")).toBeNull();
    expect(normalizeLinkCode("K7M2O")).toBeNull();
  });
});
