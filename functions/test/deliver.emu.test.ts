import * as admin from "firebase-admin";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { releaseDeferred, routeEvent, takeQueuedAlerts } from "../src/deliver";
import type { Senders } from "../src/senders";

// Runs against the Firestore emulator (FIRESTORE_EMULATOR_HOST is set by `firebase emulators:exec`).
const TRACKED = "alice", PARTNER = "bob", LINK = `${TRACKED}_${PARTNER}`;
let db: FirebaseFirestore.Firestore;
const sent: { kind: string; to: string; body: string }[] = [];
const senders: Senders = {
  async push(tokens, title, body) { sent.push({ kind: "push", to: tokens.join(","), body }); return { ok: true, detail: "ok", invalidTokens: [] }; },
  async text(phone, body) { sent.push({ kind: "text", to: phone, body }); return { ok: true, detail: "ok" }; },
  async email(to, _s, body) { sent.push({ kind: "email", to, body }); return { ok: true, detail: "ok" }; },
};

beforeAll(() => { admin.initializeApp({ projectId: "demo-ontask" }); db = admin.firestore(); });
beforeEach(async () => {
  sent.length = 0;
  for (const col of ["links", "users"]) for (const d of (await db.collection(col).get()).docs) await db.recursiveDelete(d.ref);
  await db.doc(`users/${TRACKED}`).set({ displayName: "Alice" });
  await db.doc(`users/${PARTNER}`).set({ displayName: "Bob", fcmTokens: { tok1: 1 }, lastSeenMs: Date.now() });
  await db.doc(`users/${PARTNER}/contact/main`).set({ phone: "+15550001111", email: "bob@example.com" });
  await db.doc(`links/${LINK}`).set({ trackedUid: TRACKED, partnerUid: PARTNER, participants: [TRACKED, PARTNER], status: "active", share: "report" });
});

const msgs = async () => (await db.collection(`links/${LINK}/messages`).get()).docs.map((d) => d.data());

describe("event routing", () => {
  it("sends a work unblock by push and records the message", async () => {
    const n = await routeEvent(db, senders, TRACKED, { type: "work_unblock", ts: 1, payload: { domain: "youtube.com", minutes: 60, purpose: "Acme onboarding" } }, new Date());
    expect(n).toBe(1); expect(sent[0].kind).toBe("push"); expect(sent[0].body).toContain("Acme onboarding");
    expect((await msgs())[0].delivery.channel).toBe("push");
  });

  it("ignores events partners never see", async () => {
    expect(await routeEvent(db, senders, TRACKED, { type: "focus_start", ts: 1 }, new Date())).toBe(0);
    expect(sent).toHaveLength(0);
  });

  it("caps drifting alerts (binge + unblock) at 2 per half-day and lists extras in the daily report", async () => {
    const now = new Date();
    await routeEvent(db, senders, TRACKED, { type: "binge_alert", ts: 1, payload: { message: "alert 1" } }, now);
    await routeEvent(db, senders, TRACKED, { type: "unblock", ts: 2, payload: { domains: "reddit.com", minutes: 10, count_today: 1, work_hours: true } }, now);
    await routeEvent(db, senders, TRACKED, { type: "unblock", ts: 3, payload: { domains: "x.com", minutes: 5, count_today: 2, work_hours: true } }, now);
    expect(sent).toHaveLength(2);
    expect(sent[1].body).toContain("unblocked reddit.com for 10 minutes during work hours");
    // Work-purpose unblocks are never capped.
    await routeEvent(db, senders, TRACKED, { type: "work_unblock", ts: 4, payload: { domain: "youtube.com", minutes: 30, purpose: "Training" } }, now);
    expect(sent).toHaveLength(3);
    const extra = await takeQueuedAlerts(db, LINK, "America/Chicago");
    expect(extra.join(" ")).toMatch(/unblocked x\.com for 5 minutes .*\(\d{1,2}:\d{2} [AP]M\)/);
    expect(await takeQueuedAlerts(db, LINK, "America/Chicago")).toHaveLength(0); // listed once
    // The check-in itself goes out in the daily report, not as its own message.
    expect(await routeEvent(db, senders, TRACKED, { type: "checkin", ts: 9, payload: { rating: 4 } }, now)).toBe(0);
  });

  it("falls back to text when the partner has no push token / hasn't opened the app", async () => {
    await db.doc(`users/${PARTNER}`).set({ displayName: "Bob", fcmTokens: {}, lastSeenMs: 0 });
    await routeEvent(db, senders, TRACKED, { type: "work_hours_change", ts: 1, payload: { reason: "hi", meaning: "" } }, new Date());
    expect(sent[0]).toMatchObject({ kind: "text", to: "+15550001111" });
  });

  it("respects an 'off' preference but keeps the message visible in the app", async () => {
    await db.doc(`links/${LINK}`).update({ partnerPrefs: { channels: { checkin: "push", binge_alert: "push", work_hours_change: "off", work_unblock: "push" } } });
    await routeEvent(db, senders, TRACKED, { type: "work_hours_change", ts: 1, payload: { reason: "hi", meaning: "" } }, new Date());
    expect(sent).toHaveLength(0); expect((await msgs())[0].delivery.channel).toBe("off");
  });

  it("holds messages in quiet hours and releases them afterwards", async () => {
    await db.doc(`links/${LINK}`).update({ partnerPrefs: { channels: { checkin: "push", binge_alert: "push", work_hours_change: "push", work_unblock: "push" }, quietHours: { start: "22:00", end: "07:00", tz: "UTC" } } });
    await routeEvent(db, senders, TRACKED, { type: "work_hours_change", ts: 1, payload: { reason: "late", meaning: "" } }, new Date("2026-10-30T23:00:00Z"));
    expect(sent).toHaveLength(0); expect((await msgs())[0].deferred).toBe(true);
    expect(await releaseDeferred(db, senders, new Date("2026-10-30T23:30:00Z"))).toBe(0); // still quiet
    expect(await releaseDeferred(db, senders, new Date("2026-10-31T08:00:00Z"))).toBe(1);
    expect(sent[0].body).toContain("late");
  });
});
