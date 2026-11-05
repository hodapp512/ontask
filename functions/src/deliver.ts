import type { Firestore } from "firebase-admin/firestore";
import { FieldValue } from "firebase-admin/firestore";
import { firstName, partnerMessage, withClose, type EventDoc } from "./logic/messages";
import { DRIFT_TYPES, decide, driftAllowed, halfDayStart, inQuietHours } from "./logic/routing";
import { DEFAULT_PARTNER_PREFS, type MessageType, type PartnerPrefs } from "./logic/types";
import type { Senders } from "./senders";

export interface Link { trackedUid: string; partnerUid: string; partnerPrefs?: PartnerPrefs; status: string }

const TITLES: Record<MessageType, string> = {
  binge_alert: "Check-in from {name}", unblock_alert: "{name} unblocked a site", checkin: "{name}'s day", work_hours_change: "{name} changed their work hours",
  work_unblock: "{name} unblocked a site for work",
};

/** Sends one message to one partner over their configured channel and records it on the link. */
export async function deliverToPartner(
  db: Firestore, senders: Senders, linkId: string, link: Link, trackedName: string, type: MessageType, text: string, now: Date, opts: { force?: boolean; html?: string } = {},
): Promise<{ delivered: boolean; reason?: string; channel?: string }> {
  const prefs = link.partnerPrefs ?? DEFAULT_PARTNER_PREFS;
  const msgs = db.collection("links").doc(linkId).collection("messages");
  const body = withClose(type, text);

  if (DRIFT_TYPES.includes(type) && !opts.force) {
    // Half-days are in the tracked person's time zone (from their schedule).
    const tz = ((await db.doc(`users/${link.trackedUid}/schedule/current`).get()).get("tz") as string | undefined) ?? "UTC";
    const snap = await msgs.where("ts", ">=", halfDayStart(now, tz)).get();
    const sent = snap.docs.filter((d) => DRIFT_TYPES.includes(d.get("type")) && !d.get("queuedForCheckin") && !d.get("includedInCheckin")).length;
    if (!driftAllowed(sent)) {
      await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, queuedForCheckin: true, delivery: { channel: "none", ok: false, detail: "over the limit of 2 alerts per morning/afternoon; listed in the evening review" } });
      return { delivered: false, reason: "rate_limited" };
    }
  }

  const [userSnap, contactSnap] = await Promise.all([db.doc(`users/${link.partnerUid}`).get(), db.doc(`users/${link.partnerUid}/contact/main`).get()]);
  const tokens = Object.keys((userSnap.get("fcmTokens") as Record<string, unknown> | undefined) ?? {});
  const phone = contactSnap.get("phone") as string | undefined;
  // Email: an address they saved earlier, else the Google account they signed in with.
  const email = (contactSnap.get("email") as string | undefined) || (await accountEmail(link.partnerUid));
  const d = decide(prefs, type, now, { lastSeenMs: userSnap.get("lastSeenMs"), hasPushToken: tokens.length > 0, hasPhone: !!phone, hasEmail: !!email });

  if (d.channel === "off") {
    await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, delivery: { channel: "off", ok: true, detail: "partner turned this message type off (still visible in the app)" } });
    return { delivered: false, reason: "off" };
  }
  if (d.defer && !opts.force) {
    await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, deferred: true, delivery: { channel: d.channel, ok: false, detail: "held for quiet hours" } });
    return { delivered: false, reason: "quiet_hours", channel: d.channel };
  }

  const title = TITLES[type].replace("{name}", firstName(trackedName));
  let res: { ok: boolean; detail: string };
  try {
    if (d.channel === "push") {
      const r = await senders.push(tokens, title, body, { linkId, type });
      res = r;
      if (r.invalidTokens.length) await db.doc(`users/${link.partnerUid}`).update(Object.fromEntries(r.invalidTokens.map((t) => [`fcmTokens.${t}`, FieldValue.delete()])));
    } else if (d.channel === "text") res = await senders.text(phone!, `${title}: ${body}`);
    else res = await senders.email(email!, title, body, opts.html);
  } catch (e) {
    // A failing channel must not lose the message: it is still recorded (and visible in the app) with the error.
    res = { ok: false, detail: `send failed: ${String(e).slice(0, 200)}` };
  }

  await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, delivery: { channel: d.channel, ok: res.ok, detail: res.detail, fellBackToText: d.fellBackToText ?? false } });
  return { delivered: res.ok, channel: d.channel };
}

/** Handles one Mac-written event: turn it into partner messages for every active link. */
export async function routeEvent(db: Firestore, senders: Senders, uid: string, ev: EventDoc, now: Date): Promise<number> {
  const userSnap = await db.doc(`users/${uid}`).get();
  const name = (userSnap.get("displayName") as string | undefined) ?? "Your friend";
  const m = partnerMessage(ev, name);
  if (!m) return 0;
  const links = await db.collection("links").where("trackedUid", "==", uid).where("status", "==", "active").get();
  let n = 0;
  for (const l of links.docs) {
    const text = m.text;
    const r = await deliverToPartner(db, senders, l.id, l.data() as Link, name, m.type, text, now);
    if (r.delivered) n++;
  }
  return n;
}

/** Alerts held back by the cap today, with their times, for the daily report ("Also today"); marks them as included. */
export async function takeQueuedAlerts(db: Firestore, linkId: string, tz: string): Promise<string[]> {
  const queued = await db.collection("links").doc(linkId).collection("messages").where("queuedForCheckin", "==", true).get();
  const at = (ms: number) => new Date(ms).toLocaleTimeString("en-US", { timeZone: tz, hour: "numeric", minute: "2-digit" });
  const extra = queued.docs.sort((a, b) => a.get("ts") - b.get("ts")).map((q) => `${String(q.get("text")).split("\n")[0]} (${at(q.get("ts"))})`).filter(Boolean);
  await Promise.all(queued.docs.map((q) => q.ref.update({ queuedForCheckin: false, includedInCheckin: true })));
  return extra;
}

/** Sends messages held for quiet hours once the window has ended. */
export async function releaseDeferred(db: Firestore, senders: Senders, now: Date): Promise<number> {
  const snap = await db.collectionGroup("messages").where("deferred", "==", true).get();
  let n = 0;
  for (const d of snap.docs) {
    const linkRef = d.ref.parent.parent!;
    const link = (await linkRef.get()).data() as Link | undefined;
    if (!link) continue;
    if (link.partnerPrefs && inQuietHours(link.partnerPrefs, now)) continue;
    const name = (await db.doc(`users/${link.trackedUid}`).get()).get("displayName") ?? "Your friend";
    await d.ref.delete();
    const r = await deliverToPartner(db, senders, linkRef.id, link, name, d.get("type") as MessageType, String(d.get("text")), now, { force: true });
    if (r.delivered) n++;
  }
  return n;
}

/** The email of the Google account [uid] signed in with (none in tests or for accounts without one). */
async function accountEmail(uid: string): Promise<string | undefined> {
  try { return (await (await import("firebase-admin")).auth().getUser(uid)).email ?? undefined; } catch { return undefined; }
}
