import type { DocumentData } from "firebase/firestore";
import { h } from "../dom";
import { fmtDate, fmtDuration, todayKey } from "../format";

const SVG = "http://www.w3.org/2000/svg";
const WEEKS = 52, CELL = 11, GAP = 3, STEP = CELL + GAP, LEFT = 28, TOP = 16;
const ALPHA = [0, 0.25, 0.45, 0.65, 0.85, 1];

/** Shade 0 (no computer) to 5 from effective focus: under 1h, then 1.5-hour steps (same as the Mac). */
export function heatLevel(day?: { e: number; f: number }): number {
  if (!day) return 0;
  const h = day.e / 3600;
  return h < 1 ? 1 : h < 2.5 ? 2 : h < 4 ? 3 : h < 5.5 ? 4 : 5;
}

/**
 * Focus over the last 52 weeks (weeks as columns, Sunday on top), from the Mac's users/{uid}/heat/year document.
 * Tapping a square shows that day's numbers; with [onPick] it also opens that day.
 */
export function heatmapEl(doc: DocumentData | undefined, onPick?: (d: Date) => void): HTMLElement {
  const days = (doc?.days ?? {}) as Record<string, { e: number; f: number; p: number }>;
  const today = new Date(); today.setHours(12, 0, 0, 0);
  const first = new Date(today); first.setDate(first.getDate() - first.getDay() - 7 * (WEEKS - 1));
  const svg = document.createElementNS(SVG, "svg");
  const w = LEFT + WEEKS * STEP, ht = TOP + 7 * STEP;
  svg.setAttribute("width", String(w)); svg.setAttribute("height", String(ht)); svg.setAttribute("viewBox", `0 0 ${w} ${ht}`);
  const text = (x: number, y: number, s: string) => { const t = document.createElementNS(SVG, "text"); t.setAttribute("x", String(x)); t.setAttribute("y", String(y)); t.setAttribute("class", "hm-label"); t.textContent = s; svg.append(t); };
  ["Mon", "Wed", "Fri"].forEach((s, i) => text(0, TOP + (1 + 2 * i) * STEP + CELL - 2, s));
  const detail = h("p", { class: "muted small hm-detail" });
  let lastMonth = -1;
  for (let wk = 0; wk < WEEKS; wk++) {
    const ws = new Date(first); ws.setDate(first.getDate() + wk * 7);
    if (ws.getMonth() !== lastMonth) { lastMonth = ws.getMonth(); if (wk < WEEKS - 1) text(LEFT + wk * STEP, 10, ws.toLocaleDateString("en-US", { month: "short" })); }
    for (let r = 0; r < 7; r++) {
      const d = new Date(ws); d.setDate(ws.getDate() + r);
      if (d > today) continue;
      const key = todayKey(d), day = days[key], lvl = heatLevel(day);
      const rect = document.createElementNS(SVG, "rect");
      rect.setAttribute("x", String(LEFT + wk * STEP)); rect.setAttribute("y", String(TOP + r * STEP));
      rect.setAttribute("width", String(CELL)); rect.setAttribute("height", String(CELL)); rect.setAttribute("rx", "2");
      rect.setAttribute("class", lvl ? "hm-on" : "hm-off");
      if (lvl) rect.setAttribute("fill-opacity", String(ALPHA[lvl]));
      const label = `${fmtDate(d)} · ${day ? `${fmtDuration(day.f)} focused (${day.p}%)` : "no computer"}`;
      const t = document.createElementNS(SVG, "title"); t.textContent = label; rect.append(t);
      rect.addEventListener("click", () => { detail.textContent = label; onPick?.(d); });
      svg.append(rect);
    }
  }
  const scroller = h("div", { class: "hm-scroll" }, svg as unknown as HTMLElement);
  // Most recent weeks first in view on a narrow phone.
  requestAnimationFrame(() => { scroller.scrollLeft = scroller.scrollWidth; });
  const legend = h("p", { class: "muted small hm-legend" }, "Less ", ...ALPHA.map((a, i) => h("span", { class: i ? "hm-sw hm-on-bg" : "hm-sw", style: i ? `opacity:${a}` : "" })), " More");
  const total = Math.floor((doc?.totalSec ?? 0) / 3600), streak = doc?.streak ?? 0;
  return h("div", { class: "card" },
    h("div", { class: "row" }, h("h3", { class: "grow" }, "Focus over the year"), h("span", { class: "muted small" }, `${total}h focused · ${streak ? `${streak}-day streak` : "no streak yet"}`)),
    scroller, legend, detail);
}
