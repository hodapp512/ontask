import { describe, expect, it } from "vitest";
import { deepShare, fmtDuration, timelineSvg, todayKey } from "../src/format";

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
