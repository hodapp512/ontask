import { describe, expect, it } from "vitest";
import { deepShare, fmtDuration, timelineSvg, todayKey, ago, firstName, greeting, timelineWindow, segAt, segDetail, timelineTicks, fmtDate } from "../src/format";

describe("format", () => {
  it("formats durations", () => { expect(fmtDuration(45)).toBe("45s"); expect(fmtDuration(300)).toBe("5m"); expect(fmtDuration(5400)).toBe("1h 30m"); });
  it("computes deep share excluding away and ignoring tiny samples", () => {
    expect(deepShare({ DEEP: 7200, MIXED: 1800, WASTED: 1800, AWAY: 9999 })).toBe(67);
    expect(deepShare({ DEEP: 60 })).toBeNull();
  });
  it("draws the timeline", () => {
    const svg = timelineSvg([{ a: 0, b: 43200, k: "D" }, { a: 43200, b: 86400, k: "W" }], 100);
    expect(svg).toContain('width="50"'); expect(svg).toContain("#d64545");
  });
  it("keys days as yyyy-MM-dd", () => expect(todayKey(new Date(2026, 9, 5))).toBe("2026-10-05"));
});

describe("home screen helpers", () => {
  it("uses first names", () => { expect(firstName("Sam Rivera")).toBe("Sam"); expect(firstName("Sam")).toBe("Sam"); });
  it("says how long ago", () => {
    const now = 1_000_000_000_000;
    expect(ago(now - 10_000, now)).toBe("just now");
    expect(ago(now - 5 * 60_000, now)).toBe("5 min ago");
    expect(ago(now - 3 * 3_600_000, now)).toBe("3 h ago");
    expect(ago(now - 86_400_000, now)).toBe("yesterday");
  });
  it("greets by time of day", () => {
    expect(greeting(new Date(2026, 0, 1, 9))).toBe("Good morning");
    expect(greeting(new Date(2026, 0, 1, 14))).toBe("Good afternoon");
    expect(greeting(new Date(2026, 0, 1, 20))).toBe("Good evening");
  });
});

describe("timeline zoom", () => {
  it("fits whole hours around the data", () => {
    expect(timelineWindow([])).toEqual([0, 86400]);
    expect(timelineTicks(14 * 3600 + 42 * 60, 15 * 3600 + 5 * 60).map((t) => t.label)).toEqual(["2:45", "2:50", "2:55", "3 PM", "3:05"]);
    expect(fmtDate(new Date(2026, 10, 1))).toBe("Nov 1, 2026");
    // Away overnight and a 2-minute wake at 3 AM don't stretch the day back to midnight.
    expect(timelineWindow([{ a: 0, b: 2 * 3600 + 360, k: "A" }, { a: 3 * 3600, b: 3 * 3600 + 120, k: "M" }, { a: 8 * 3600 + 600, b: 12 * 3600, k: "D" }])).toEqual([8 * 3600, 12 * 3600]);
    expect(timelineWindow([{ a: 8 * 3600 + 600, b: 12 * 3600, k: "D" }, { a: 13 * 3600, b: 16 * 3600 + 60, k: "W" }])).toEqual([8 * 3600, 17 * 3600]);
    expect(timelineWindow([{ a: 9 * 3600 + 60, b: 9 * 3600 + 600, k: "D" }])).toEqual([9 * 3600, 11 * 3600]);
  });
});

describe("timeline details", () => {
  const segs = [{ a: 9 * 3600, b: 10 * 3600, k: "D", t: [{ n: "Xcode", s: 3000 }] }, { a: 10 * 3600, b: 10 * 3600 + 600, k: "W", t: [{ n: "youtube.com", s: 600 }] }];
  it("finds the stretch under the pointer", () => {
    expect(segAt(segs, 0.25)?.k).toBe("D");
    expect(segAt(segs, 0.55)?.k).toBe("W");
  });
  it("describes it", () => expect(segDetail(segs[1])).toContain("youtube.com 10m"));
});
