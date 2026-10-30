import * as admin from "firebase-admin";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { defineSecret } from "firebase-functions/params";
import { randomBytes } from "node:crypto";
import { deliverToPartner, releaseDeferred, routeEvent, type Link } from "./deliver";
import { checkinState, habitsDue } from "./logic/schedule";
import { localParts } from "./logic/routing";
import { buildPrompt, ollamaLibraryUrl, parseCandidates, validateCandidates, type Recommendation } from "./logic/modelrec";
import type { ScheduleDoc } from "./logic/types";
import { realSenders, type Secrets } from "./senders";

admin.initializeApp();
const db = admin.firestore();

// Secrets live in Firebase Secret Manager (see SETUP.md). Nothing secret is in this repo.
const TEXTBELT_KEY = defineSecret("TEXTBELT_KEY");
const SMTP_URL = defineSecret("SMTP_URL");
const SMTP_FROM = defineSecret("SMTP_FROM");
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
const safe = (s: { value(): string }) => { try { return s.value() || undefined; } catch { return undefined; } };
const secrets = (): Secrets => ({ textbeltKey: safe(TEXTBELT_KEY), smtpUrl: safe(SMTP_URL), smtpFrom: safe(SMTP_FROM) });
const senders = realSenders(secrets);
const MSG_SECRETS = [TEXTBELT_KEY, SMTP_URL, SMTP_FROM];

// ---- Mac events -> partner messages ---------------------------------------------------------------------------

export const onEventCreated = onDocumentCreated({ document: "users/{uid}/events/{eventId}", secrets: MSG_SECRETS }, async (event) => {
  const ev = event.data?.data();
  if (!ev) return;
  const uid = event.params.uid;
  const now = new Date();
  // Events addressed to the tracked user's own phone.
  if (ev.type === "checkin_reminder" || ev.type === "evening_checkin_phone") {
    const tokens = await tokensOf(uid);
    await senders.push(tokens, "OnTask", ev.type === "evening_checkin_phone" ? "Time for your evening check-in." : "Don't forget your check-in.", { type: String(ev.type) });
  }
  const n = await routeEvent(db, senders, uid, { type: ev.type, ts: ev.ts, payload: ev.payload }, now);
  await event.data!.ref.update({ processedAt: now.getTime(), delivered: n });
});

export const onReactionCreated = onDocumentCreated({ document: "links/{linkId}/reactions/{id}" }, async (event) => {
  const r = event.data?.data(); if (!r) return;
  const labels: Record<string, string> = { thumbs_up: "👍", proud: "Proud of you", call_me: "Call me when you can", take_a_walk: "Take a walk" };
  const body = labels[r.kind] + (r.reply ? ` — "${r.reply}"` : "");
  await senders.push(await tokensOf(r.trackedUid), "From your partner", body, { type: "reaction" });
});

async function tokensOf(uid: string): Promise<string[]> {
  return Object.keys(((await db.doc(`users/${uid}`).get()).get("fcmTokens") as Record<string, unknown> | undefined) ?? {});
}

// ---- Callable: pairing and device linking ---------------------------------------------------------------------

const code8 = () => randomBytes(5).toString("hex").toUpperCase().slice(0, 8);

/** Callables check req.auth themselves; Cloud Run must still let the browser reach them. */
const PUBLIC = { invoker: "public" as const };

export const createInvite = onCall(PUBLIC, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  const code = code8();
  await db.doc(`invites/${code}`).set({ trackedUid: req.auth.uid, createdAt: Date.now(), expiresAt: Date.now() + 7 * 86_400_000, used: false });
  return { code };
});

export const acceptInvite = onCall(PUBLIC, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  const code = String(req.data?.code ?? "").toUpperCase().trim();
  const ref = db.doc(`invites/${code}`);
  return db.runTransaction(async (tx) => {
    const inv = await tx.get(ref);
    if (!inv.exists || inv.get("used") || inv.get("expiresAt") < Date.now()) throw new HttpsError("not-found", "This invite is invalid or expired");
    const trackedUid = inv.get("trackedUid") as string;
    if (trackedUid === req.auth!.uid) throw new HttpsError("failed-precondition", "You can't pair with yourself");
    const linkId = `${trackedUid}_${req.auth!.uid}`;
    const [tu, pu] = await Promise.all([tx.get(db.doc(`users/${trackedUid}`)), tx.get(db.doc(`users/${req.auth!.uid}`))]);
    tx.set(db.doc(`links/${linkId}`), {
      trackedUid, partnerUid: req.auth!.uid, trackedName: tu.get("displayName") ?? "Your friend", partnerName: pu.get("displayName") ?? "Your partner", participants: [trackedUid, req.auth!.uid], status: "active", share: "report", createdAt: Date.now(),
      partnerPrefs: { channels: { binge_alert: "push", checkin: "push", work_hours_change: "push", missed_checkin: "push", work_unblock: "push" }, quietHours: null },
    });
    tx.update(ref, { used: true, usedBy: req.auth!.uid });
    return { linkId, trackedUid };
  });
});

/** The Mac signs in with a custom token minted for the signed-in PWA user (no passwords on the Mac). */
export const createDeviceToken = onCall(PUBLIC, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  return { token: await admin.auth().createCustomToken(req.auth.uid, { device: "mac" }) };
});

export const sendTestMessage = onCall({ ...PUBLIC, secrets: MSG_SECRETS }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  const linkId = String(req.data?.linkId ?? "");
  const snap = await db.doc(`links/${linkId}`).get();
  const link = snap.data() as Link | undefined;
  if (!link || !(snap.get("participants") as string[]).includes(req.auth.uid)) throw new HttpsError("permission-denied", "Not your link");
  const name = (await db.doc(`users/${link.trackedUid}`).get()).get("displayName") ?? "Your friend";
  const r = await deliverToPartner(db, senders, linkId, link, name, "checkin", "This is a test message from OnTask.", new Date(), { force: true });
  return r;
});

// ---- Schedules: habit prompts, check-in reminders, missed check-ins -------------------------------------------

/** Runs every 5 minutes so habit prompts fire even while the Mac sleeps. */
export const sweep = onSchedule({ schedule: "every 5 minutes", secrets: MSG_SECRETS }, async () => {
  const now = new Date();
  const users = await db.collectionGroup("schedule").get();
  for (const doc of users.docs) {
    if (doc.id !== "current") continue;
    const uid = doc.ref.parent.parent!.id;
    const s = doc.data() as ScheduleDoc;
    const tokens = await tokensOf(uid);
    for (const h of habitsDue(s.habits ?? [], s.tz, now)) await senders.push(tokens, "OnTask", h.name, { type: "habit", habitId: h.id });
    await checkinSweep(uid, s, now, tokens);
  }
  await releaseDeferred(db, senders, now);
});

async function checkinSweep(uid: string, s: ScheduleDoc, now: Date, tokens: string[]) {
  const date = localParts(now, s.tz).date;
  const done = (await db.doc(`users/${uid}/checkins/${date}`).get()).get("evening") === true;
  const st = checkinState(s, done, now);
  const flag = db.doc(`users/${uid}/sweepState/${date}`);
  const state = (await flag.get()).data() ?? {};
  if (st.state === "remind" && st.level > (state.reminded ?? 0)) {
    await senders.push(tokens, "OnTask", st.level >= 3 ? "Your check-in is overdue." : "Time for your evening check-in.", { type: "checkin_reminder" });
    await flag.set({ reminded: st.level }, { merge: true });
  }
  if (st.state === "missed" && !state.missedNotified) {
    const name = (await db.doc(`users/${uid}`).get()).get("displayName") ?? "Your friend";
    const links = await db.collection("links").where("trackedUid", "==", uid).where("status", "==", "active").get();
    for (const l of links.docs) await deliverToPartner(db, senders, l.id, l.data() as Link, name, "missed_checkin", `${name} hasn't checked in today yet.`, now);
    await flag.set({ missedNotified: true }, { merge: true });
  }
}

// ---- Weekly model recommendation (Gemini + Google Search grounding) -------------------------------------------

export const modelRecommendation = onSchedule({ schedule: "every monday 06:00", timeZone: "America/Chicago", secrets: [GEMINI_API_KEY], timeoutSeconds: 300 }, async () => {
  const current = "Qwen3-VL 4B", hardware = "Apple Silicon, 16 GB unified memory";
  const key = safe(GEMINI_API_KEY);
  if (!key) { console.warn("GEMINI_API_KEY not set; skipping"); return; }
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${key}`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ contents: [{ parts: [{ text: buildPrompt(current, hardware) }] }], tools: [{ google_search: {} }] }),
  });
  if (!r.ok) throw new Error(`Gemini ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const j = (await r.json()) as { candidates?: { content?: { parts?: { text?: string }[] } }[] };
  const text = j.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("") ?? "";
  const { valid, dropped } = await validateCandidates(parseCandidates(text), async (c) => {
    const url = c.source === "huggingface" ? `https://huggingface.co/api/models/${c.id}` : ollamaLibraryUrl(c.id);
    return (await fetch(url, { method: "GET", redirect: "follow" })).ok;
  });
  const rec: Recommendation = { generatedAt: Date.now(), hardware, current, candidates: valid, dropped };
  await db.doc("modelRecommendation/latest").set(rec);
});
