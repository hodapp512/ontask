import { describe, expect, it } from "vitest";
import { effective, mergeHeat, mergeReports } from "../src/logic/merge";
import { isWorkDay } from "../src/logic/schedule";

const H = 3600;
describe("merging devices", () => {
  it("adds totals, combines timelines (phone marked) and sums sites", () => {
    const mac = { device: { id: "m", kind: "mac" }, date: "2026-11-02", dayStart: 1, tz: "X", totals: { DEEP: 3 * H, WASTED: H }, timeline: [{ a: 100, b: 200, k: "D" }], topSites: [{ name: "YouTube", categoryId: "ENT", seconds: 600 }], distractionEvents: 2, updatedAt: 5 };
    const phone = { device: { id: "p", kind: "phone" }, date: "2026-11-02", totals: { WASTED: H, MIXED: 60 }, timeline: [{ a: 50, b: 90, k: "W" }], topSites: [{ name: "YouTube", categoryId: "ENT", seconds: 300 }, { name: "Duolingo", seconds: 60 }], distractionEvents: 1, updatedAt: 9 };
    const m = mergeReports([phone, mac])!;
    expect(m.totals).toEqual({ DEEP: 3 * H, WASTED: 2 * H, MIXED: 60 });
    expect(m.timeline!.map((s) => [s.a, s.d])).toEqual([[50, "phone"], [100, undefined]]);
    expect(m.topSites![0]).toMatchObject({ name: "YouTube", seconds: 900 });
    expect(m.dayStart).toBe(1); expect(m.distractionEvents).toBe(3); expect(m.updatedAt).toBe(9);
    expect(m.device).toBeUndefined();
  });

  it("scores the summed heat numbers and counts the streak over days off", () => {
    const p = (deep: number) => ({ deep, active: deep, workDeep: deep, workActive: deep, offWasted: 0, offActive: 0 });
    expect(effective({ deep: 4 * H, active: 6 * H, workDeep: 4 * H, workActive: 5 * H, offWasted: H, offActive: H })).toBe(Math.floor(2.88 * H));
    const mac = { "2026-10-30": p(2 * H), "2026-11-02": p(H) };          // Fri 2h, Mon 1h so far
    const phone = { "2026-10-30": p(H), "2026-10-29": p(H) };            // Fri +1h, Thu 1h
    const heat = mergeHeat([mac, phone], "2026-11-02", (d) => !["2026-10-31", "2026-11-01"].includes(d));
    expect(heat.days["2026-10-30"].f).toBe(3 * H);
    expect(heat.totalSec).toBe(5 * H);
    expect(heat.streak).toBe(1); // Fri (3h) counts; Sat/Sun off skipped; today in progress; Thu (1h) ends it
  });

  it("knows work days from the schedule", () => {
    const s = { tz: "UTC", workWeek: { "1": [{ startMin: 540, endMin: 1020 }] }, exceptions: { "2026-11-09": [] }, habits: [] };
    expect(isWorkDay(s, "2026-11-02")).toBe(true);   // Monday
    expect(isWorkDay(s, "2026-11-03")).toBe(false);  // Tuesday: no hours
    expect(isWorkDay(s, "2026-11-09")).toBe(false);  // Monday off by exception
  });
});

import { describe as describeSeen, judge } from "../src/logic/judge";
describe("judging on the phone", () => {
  const cats = [{ id: "CORE_WORK", label: "Work" }, { id: "ENTERTAINMENT", label: "Entertainment / News / Social" }, { id: "VARIABLE", label: "Varies", variable: true }];
  const answer = (choice: string, p: number) => (async () => new Response(JSON.stringify({ answers: { c: { choice, probabilities: { [choice]: p } } } }))) as unknown as typeof fetch;
  it("describes what's seen like the Mac", () => {
    expect(describeSeen({ app: "Chrome", title: "Kotlin docs", url: "https://www.kotlinlang.org/docs" })).toBe("App: Chrome\nSite: kotlinlang.org\nTitle: Kotlin docs\nAddress: https://www.kotlinlang.org/docs");
  });
  it("returns the category id when sure, nothing when not", async () => {
    expect(await judge({ app: "Duolingo" }, cats, "k", answer("Work", 0.8))).toEqual({ category: "CORE_WORK", probability: 0.8 });
    expect(await judge({ app: "Duolingo" }, cats, "k", answer("Work", 0.4))).toBeNull();
  });
});

import { clipToPhone, mergeReports as merge2 } from "../src/logic/merge";
describe("overlaps between the Mac and the phone", () => {
  it("cuts the Mac's time where the phone was in use, and the totals with it", () => {
    // Your example: phone 7:05–7:11 (Work adjacent), Mac 7:08–7:17 (gemini.google.com).
    const m = (h: number, mi: number) => (h * 60 + mi) * 60;
    const r = clipToPhone([{ a: m(19, 8), b: m(19, 17), k: "D", t: [{ n: "gemini.google.com", s: 540 }] }], [{ a: m(19, 5), b: m(19, 11) }]);
    expect(r.pieces.map((p) => [p.a, p.b])).toEqual([[m(19, 11), m(19, 17)]]);
    expect(r.pieces[0].t).toEqual([{ n: "gemini.google.com", s: 360 }]);
    expect(r.removed).toEqual({ DEEP: 180 });
    const merged = merge2([
      { device: { id: "m", kind: "mac" }, date: "d", totals: { DEEP: 540 }, workTotals: { DEEP: 540 }, timeline: [{ a: m(19, 8), b: m(19, 17), k: "D" }] },
      { device: { id: "p", kind: "phone" }, date: "d", totals: { DEEP: 360 }, workTotals: {}, timeline: [{ a: m(19, 5), b: m(19, 11), k: "D" }] },
    ])!;
    expect(merged.totals).toEqual({ DEEP: 720 });                     // 15 minutes, not 18
    expect(merged.timeline!.map((s) => [s.a, s.b, s.d])).toEqual([[m(19, 5), m(19, 11), "phone"], [m(19, 11), m(19, 17), undefined]]);
  });
  it("splits a Mac stretch around a short phone check", () => {
    const r = clipToPhone([{ a: 0, b: 600, k: "W" }], [{ a: 200, b: 260 }]);
    expect(r.pieces.map((p) => [p.a, p.b])).toEqual([[0, 200], [260, 600]]);
    expect(r.removed).toEqual({ WASTED: 60 });
  });
});
