import * as admin from "firebase-admin";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { defineSecret } from "firebase-functions/params";
import { randomBytes } from "node:crypto";
import { deliverToPartner, releaseDeferred, routeEvent, takeQueuedAlerts, type Link } from "./deliver";
import { firstName } from "./logic/messages";
import { autoSendLine, fmtDate, ownParts, partnerParts, reportDue, reportHtml, reportText, type CheckinDoc, type ReportDoc } from "./logic/report";
import { bedtimeNight, bedtimePushDue, checkinState, habitsDue, isWorkDay } from "./logic/schedule";
import { mergeDay, mergeHeat, type DayParts, type DeviceItems, type DeviceReport } from "./logic/merge";
import { judge, type Cat } from "./logic/judge";
import { localParts } from "./logic/routing";
import { buildPrompt, ollamaLibraryUrl, parseCandidates, validateCandidates, type Recommendation } from "./logic/modelrec";
import type { ScheduleDoc } from "./logic/types";
import { realSenders, type Secrets } from "./senders";
import { makeLinkCode, normalizeLinkCode } from "./logic/linkcode";

admin.initializeApp();
const db = admin.firestore();

// Secrets live in Firebase Secret Manager (see docs/SETUP.md). Nothing secret is in this repo.
const TEXTBELT_KEY = defineSecret("TEXTBELT_KEY");
const SMTP_URL = defineSecret("SMTP_URL");
const SMTP_FROM = defineSecret("SMTP_FROM");
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
// Jev (typesafe.ai) key for judging what's on the phone; set with `firebase functions:secrets:set JEV_API_KEY`.
const JEV_API_KEY = defineSecret("JEV_API_KEY");
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
  // (Snoozing a check-in on the Mac used to push "Don't forget your check-in." here; it comes back on the Mac instead.)
  if (ev.type === "evening_checkin_phone") {
    await pushTo(uid, await tokensOf(uid), "OnTask", "Time for your evening check-in.", { type: String(ev.type) });
  }
  const n = await routeEvent(db, senders, uid, { type: ev.type, ts: ev.ts, payload: ev.payload }, now);
  await event.data!.ref.update({ processedAt: now.getTime(), delivered: n });
  // Messages OnTask sent by itself are listed in your own daily report ("Sent to your partner automatically").
  const auto = ev.payload?.auto as string | undefined;
  if (ev.type === "binge_alert" && auto) {
    const tz = ((await db.doc(`users/${uid}/schedule/current`).get()).get("tz") as string | undefined) ?? "America/Chicago";
    const at = now.toLocaleTimeString("en-US", { timeZone: tz, hour: "numeric", minute: "2-digit" });
    await db.doc(`users/${uid}/sweepState/${localParts(now, tz).date}`).set({ autoSends: admin.firestore.FieldValue.arrayUnion(autoSendLine(at, auto, ev.payload?.site as string | undefined, n)) }, { merge: true });
  }
});

// ---- Per-device reports -> the merged day everyone reads -------------------------------------------------------

/**
 * A device's day report or private copy changed: merge every device's for that day into reports/{date} (shared),
 * reportItems/{date} (titled rows and refs, yours only), and the overlap's negative heat numbers (deviceHeat/overlap).
 */
async function mergeDayFor(uid: string, date: string) {
  const [reps, items] = await Promise.all([
    db.collection(`users/${uid}/deviceReports`).where("date", "==", date).get(),
    db.collection(`users/${uid}/deviceReportItems`).where("date", "==", date).get(),
  ]);
  const m = mergeDay(reps.docs.map((d) => d.data() as DeviceReport), items.docs.map((d) => ({ date, ...(d.data() as object) }) as DeviceItems));
  const now = Date.now();
  await Promise.all([
    m.report ? db.doc(`users/${uid}/reports/${date}`).set(m.report) : Promise.resolve(),
    db.doc(`users/${uid}/reportItems/${date}`).set({ ...m.items, updatedAt: now }),
    db.doc(`users/${uid}/deviceHeat/overlap`).set({ days: { [date]: m.overlap ?? { deep: 0, active: 0, workDeep: 0, workActive: 0, offWasted: 0, offActive: 0 } }, updatedAt: now }, { merge: true }),
  ]);
}

export const onDeviceReportWritten = onDocumentWritten({ document: "users/{uid}/deviceReports/{id}" }, async (event) => {
  const date = (event.data?.after.get("date") ?? event.data?.before.get("date")) as string | undefined;
  if (date) await mergeDayFor(event.params.uid, date);
});

export const onDeviceReportItemsWritten = onDocumentWritten({ document: "users/{uid}/deviceReportItems/{id}" }, async (event) => {
  const date = (event.data?.after.get("date") ?? event.data?.before.get("date")) as string | undefined;
  if (date) await mergeDayFor(event.params.uid, date);
});

/** A device's year of heat numbers changed: recompute the merged heat/year (scores, total, streak). */
export const onDeviceHeatWritten = onDocumentWritten({ document: "users/{uid}/deviceHeat/{id}" }, async (event) => {
  const uid = event.params.uid;
  const [all, sched] = await Promise.all([db.collection(`users/${uid}/deviceHeat`).get(), db.doc(`users/${uid}/schedule/current`).get()]);
  const s = sched.data() as ScheduleDoc | undefined;
  const today = localParts(new Date(), s?.tz ?? "America/Chicago").date;
  const heat = mergeHeat(all.docs.map((d) => (d.get("days") ?? {}) as Record<string, DayParts>), today, (d) => isWorkDay(s, d));
  await db.doc(`users/${uid}/heat/year`).set({ ...heat, updatedAt: Date.now() });
});

/**
 * The phone asks which category an app, page or video is. Uses the account's own categories (sync/categories,
 * falling back to the Mac's list in schedule/current). Returns { category } or { category: null } when unsure.
 */
export const judgeSeen = onCall({ invoker: "public", secrets: [JEV_API_KEY] }, async (req) => {
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const key = safe(JEV_API_KEY);
  if (!key) return { category: null, reason: "no-key" };
  const d = (req.data ?? {}) as { app?: string; title?: string; url?: string; screenText?: string };
  const [syncCats, sched] = await Promise.all([db.doc(`users/${uid}/sync/categories`).get(), db.doc(`users/${uid}/schedule/current`).get()]);
  const chk = await db.doc(`users/${uid}/checkins/${localParts(new Date(), sched.get("tz") ?? "UTC").date}`).get();
  const listed = ((sched.get("categories") ?? []) as { id: string; label: string; variable?: boolean }[]);
  const cats: Cat[] = listed.length ? listed : Object.entries((syncCats.get("items") ?? {}) as Record<string, { label: string; variable?: boolean; deleted?: boolean }>).map(([id, v]) => ({ id, ...v }));
  const r = await judge({ app: d.app?.slice(0, 100), title: d.title?.slice(0, 300), url: d.url?.slice(0, 500), screenText: d.screenText?.slice(0, 1500), plan: chk.get("plan") }, cats, key).catch(() => null);
  return { category: r?.category ?? null, probability: r?.probability ?? null };
});

export const onReactionCreated = onDocumentCreated({ document: "links/{linkId}/reactions/{id}" }, async (event) => {
  const r = event.data?.data(); if (!r) return;
  const labels: Record<string, string> = { thumbs_up: "👍", proud: "Proud of you", call_me: "Call me when you can", take_a_walk: "Take a walk" };
  const body = labels[r.kind] + (r.reply ? ` — "${r.reply}"` : "");
  await pushTo(r.trackedUid, await tokensOf(r.trackedUid), "From your partner", body, { type: "reaction" });
});

async function tokensOf(uid: string): Promise<string[]> {
  return Object.keys(((await db.doc(`users/${uid}`).get()).get("fcmTokens") as Record<string, unknown> | undefined) ?? {});
}

/**
 * Pushes to a user's own devices and forgets registrations that no longer exist (an uninstalled app or web app),
 * so a removed one doesn't linger.
 */
async function pushTo(uid: string, tokens: string[], title: string, body: string, data: Record<string, string>) {
  const r = await senders.push(tokens, title, body, data);
  if (r.invalidTokens.length) await db.doc(`users/${uid}`).update(Object.fromEntries(r.invalidTokens.map((t) => [`fcmTokens.${t}`, admin.firestore.FieldValue.delete()]))).catch(() => {});
  return r;
}

// ---- Callable: pairing and device linking ---------------------------------------------------------------------

const LINK_CODE_TTL_MS = 5 * 60_000;
const linkCode = () => makeLinkCode((n) => randomBytes(n));

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
      partnerPrefs: { channels: { binge_alert: "push", unblock_alert: "push", checkin: "push", work_hours_change: "push", work_unblock: "push" }, quietHours: null },
    });
    tx.update(ref, { used: true, usedBy: req.auth!.uid });
    return { linkId, trackedUid };
  });
});

/**
 * Linking a Mac: the signed-in phone asks for a short code, the Mac redeems it once for a sign-in token.
 * Codes are 5 characters, expire after 5 minutes and work once.
 */
export const createLinkCode = onCall(PUBLIC, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  const now = Date.now();
  // Drop this user's older unused codes so only the newest one works.
  const old = await db.collection("linkCodes").where("uid", "==", req.auth.uid).get();
  const batch = db.batch(); old.docs.forEach((d) => batch.delete(d.ref));
  let code = linkCode();
  for (let i = 0; i < 5 && (await db.doc(`linkCodes/${code}`).get()).exists; i++) code = linkCode();
  batch.set(db.doc(`linkCodes/${code}`), { uid: req.auth.uid, createdAt: now, expiresAt: now + LINK_CODE_TTL_MS });
  await batch.commit();
  return { code, expiresAt: now + LINK_CODE_TTL_MS };
});

/** Called by the Mac without signing in: trades a valid code for a custom token for that user. */
export const redeemLinkCode = onCall(PUBLIC, async (req) => {
  const code = normalizeLinkCode(String(req.data?.code ?? ""));
  if (!code) throw new HttpsError("invalid-argument", "Enter the 5-character code shown on your phone");
  const ref = db.doc(`linkCodes/${code}`);
  const uid = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || (snap.get("expiresAt") as number) < Date.now()) throw new HttpsError("not-found", "That code is wrong or has expired. Make a new one on your phone.");
    tx.delete(ref);
    return snap.get("uid") as string;
  });
  // Who the Mac is now signed in as, so it can show it right away.
  const [user, profile] = await Promise.all([admin.auth().getUser(uid), db.doc(`users/${uid}`).get()]);
  return {
    token: await admin.auth().createCustomToken(uid, { device: "mac" }),
    name: (profile.get("displayName") as string | undefined) ?? user.displayName ?? null,
    email: user.email ?? null,
  };
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

// ---- Schedules: habit prompts, check-in reminders, daily reports ----------------------------------------------

/** Runs every 5 minutes so habit prompts fire even while the Mac sleeps. */
export const sweep = onSchedule({ schedule: "every 5 minutes", secrets: MSG_SECRETS }, async () => {
  const now = new Date();
  const users = await db.collectionGroup("schedule").get();
  for (const doc of users.docs) {
    if (doc.id !== "current") continue;
    const uid = doc.ref.parent.parent!.id;
    const s = doc.data() as ScheduleDoc;
    const tokens = await tokensOf(uid);
    for (const h of habitsDue(s.habits ?? [], s.tz, now)) await pushTo(uid, tokens, "OnTask", h.name, { type: "habit", habitId: h.id });
    await checkinSweep(uid, s, now, tokens);
    const night = bedtimeNight(s, now);
    if (night) {
      const flag = db.doc(`users/${uid}/sweepState/${night}`);
      if (bedtimePushDue(s, now, !!(await flag.get()).get("bedtimePushed"))) {
        await pushTo(uid, tokens, "Bedtime", "Time to put the screens away and get some sleep.", { type: "bedtime" });
        await flag.set({ bedtimePushed: true }, { merge: true });
      }
    }
  }
  await releaseDeferred(db, senders, now);
});

async function checkinSweep(uid: string, s: ScheduleDoc, now: Date, tokens: string[]) {
  const date = localParts(now, s.tz).date;
  const done = (await db.doc(`users/${uid}/checkins/${date}`).get()).get("evening") === true;
  const st = checkinState(s, done, now);
  const flag = db.doc(`users/${uid}/sweepState/${date}`);
  const state = (await flag.get()).data() ?? {};
  if (st.state === "remind" && !state.reportSent && st.level > (state.reminded ?? 0)) {
    await pushTo(uid, tokens, "OnTask", st.level >= 3 ? "Your check-in is overdue." : "Time for your evening check-in.", { type: "checkin_reminder" });
    await flag.set({ reminded: st.level }, { merge: true });
  }
  await dailyReportSweep(uid, s, now, date, state);
}

const WEB_URL = "https://ontask-ed2df.web.app";

/**
 * The daily report: once you've wrapped up (30 minutes without activity after work ends; 6 PM on days off) or at
 * 8 PM, every partner gets it over their "Daily report" channel, and you get it by email (with page titles).
 */
async function dailyReportSweep(uid: string, s: ScheduleDoc, now: Date, date: string, state: Record<string, unknown>) {
  if (state.reportSent) return;
  const macs = await db.collection(`users/${uid}/devices`).where("kind", "==", "mac").get();
  const lastActive = Math.max(0, ...macs.docs.map((d) => Number(d.get("lastActiveMs") ?? 0)));
  if (!reportDue(s, now, lastActive || undefined)) return;
  await db.doc(`users/${uid}/sweepState/${date}`).set({ reportSent: true, reportSentAt: now.getTime() }, { merge: true });

  const [userSnap, rep, items, chk] = await Promise.all([
    db.doc(`users/${uid}`).get(), db.doc(`users/${uid}/reports/${date}`).get(), db.doc(`users/${uid}/reportItems/${date}`).get(), db.doc(`users/${uid}/checkins/${date}`).get(),
  ]);
  const name = (userSnap.get("displayName") as string | undefined) ?? "Your friend";
  const first = firstName(name);
  const report = rep.data() as ReportDoc | undefined, checkin = chk.data() as CheckinDoc | undefined;
  const link = `${WEB_URL}/#/`;

  const links = await db.collection("links").where("trackedUid", "==", uid).where("status", "==", "active").get();
  const alsoToday: string[] = [];
  for (const l of links.docs) {
    const extra = await takeQueuedAlerts(db, l.id, s.tz);
    extra.forEach((e) => { if (!alsoToday.includes(e)) alsoToday.push(e); });
    const parts = { ...partnerParts(first), report, checkin, alsoToday: extra, link: `${WEB_URL}/#/person/${encodeURIComponent(l.id)}` };
    await deliverToPartner(db, senders, l.id, l.data() as Link, name, "checkin", reportText(parts), now, { html: reportHtml(parts) });
  }
  if (s.reportEmail !== false) {
    const email = await admin.auth().getUser(uid).then((u) => u.email).catch(() => undefined);
    if (email) {
      // Yours has the titled rows (only you can read them).
      const mine = { ...ownParts, report: { ...report, topSites: (items.get("topSites") as ReportDoc["topSites"]) ?? report?.topSites }, checkin, alsoToday, link, autoSends: (state.autoSends as string[] | undefined) ?? [] };
      const r = await senders.email(email, `Your day: ${fmtDate(date)}`, reportText(mine), reportHtml(mine));
      if (!r.ok) console.warn(`report email to ${uid} failed: ${r.detail}`);
    }
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
