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
