import { describe, expect, it, vi } from "vitest";
import { partnerMessage, textBeltSafe, withClose } from "../src/logic/messages";
import { decide, driftAllowed, halfDayStart, inQuietHours, localParts } from "../src/logic/routing";
import { checkinState, habitsDue } from "../src/logic/schedule";
import { buildPrompt, ollamaLibraryUrl, parseCandidates, validateCandidates } from "../src/logic/modelrec";
import { DEFAULT_PARTNER_PREFS, type PartnerPrefs, type ScheduleDoc } from "../src/logic/types";

const at = (iso: string) => new Date(iso);

describe("messages", () => {
  it("maps events to partner messages and ignores private ones", () => {
    expect(partnerMessage({ type: "work_unblock", ts: 1, payload: { domain: "youtube.com", minutes: 60, purpose: "Acme onboarding" } }, "Sam")!.text).toContain("Acme onboarding");
    expect(partnerMessage({ type: "checkin", ts: 1, payload: { message: "Today: 3h deep." } }, "Sam Rivera")!.text).toBe("Here is Sam's evening review.\n\nToday: 3h deep.");
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
  it("escalates reminders, then reports a missed check-in after the 2h grace period", () => {
    expect(checkinState(s, false, at("2026-10-30T21:00:00Z")).state).toBe("not_due");                       // 16:00 local
    expect(checkinState(s, false, at("2026-10-30T22:20:00Z"))).toMatchObject({ state: "remind", level: 2 }); // 17:20
    expect(checkinState(s, false, at("2026-10-30T23:05:00Z")).state).toBe("remind");                         // 18:05
    expect(checkinState(s, false, at("2026-10-31T00:05:00Z")).state).toBe("missed");                         // 19:05
    expect(checkinState(s, true, at("2026-10-31T00:05:00Z")).state).toBe("done");
  });
  it("honours 'never' and treats days off as not a missed check-in", () => {
    expect(checkinState({ ...s, missedCheckinGraceMin: 0 }, false, at("2026-10-31T03:00:00Z")).state).toBe("remind");
    expect(checkinState(s, false, at("2026-10-31T20:00:00Z")).state).toBe("no_work_day");                    // Saturday
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
