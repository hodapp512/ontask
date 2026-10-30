import type { Firestore } from "firebase-admin/firestore";
import { FieldValue } from "firebase-admin/firestore";
import { partnerMessage, withClose, type EventDoc } from "./logic/messages";
import { bingeAllowed, decide, inQuietHours } from "./logic/routing";
import { DEFAULT_PARTNER_PREFS, type MessageType, type PartnerPrefs } from "./logic/types";
import type { Senders } from "./senders";

export interface Link { trackedUid: string; partnerUid: string; partnerPrefs?: PartnerPrefs; status: string }

const TITLES: Record<MessageType, string> = {
  binge_alert: "Check-in from {name}", checkin: "{name}'s daily check-in", work_hours_change: "{name} changed their work hours",
  missed_checkin: "{name} hasn't checked in", work_unblock: "{name} unblocked a site for work",
};

/** Sends one message to one partner over their configured channel and records it on the link. */
export async function deliverToPartner(
  db: Firestore, senders: Senders, linkId: string, link: Link, trackedName: string, type: MessageType, text: string, now: Date, opts: { force?: boolean } = {},
): Promise<{ delivered: boolean; reason?: string; channel?: string }> {
  const prefs = link.partnerPrefs ?? DEFAULT_PARTNER_PREFS;
  const msgs = db.collection("links").doc(linkId).collection("messages");
  const body = withClose(type, text);

  if (type === "binge_alert" && !opts.force) {
    const since = now.getTime() - 86_400_000;
    const snap = await msgs.where("type", "==", "binge_alert").where("ts", ">=", since).get();
    const sent = snap.docs.filter((d) => !d.get("queuedForCheckin")).length;
    if (!bingeAllowed(sent)) {
      await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, queuedForCheckin: true, delivery: { channel: "none", ok: false, detail: "rate-limited; included in the next check-in" } });
      return { delivered: false, reason: "rate_limited" };
    }
  }

  const [userSnap, contactSnap] = await Promise.all([db.doc(`users/${link.partnerUid}`).get(), db.doc(`users/${link.partnerUid}/contact/main`).get()]);
  const tokens = Object.keys((userSnap.get("fcmTokens") as Record<string, unknown> | undefined) ?? {});
  const phone = contactSnap.get("phone") as string | undefined, email = contactSnap.get("email") as string | undefined;
  const d = decide(prefs, type, now, { lastSeenMs: userSnap.get("lastSeenMs"), hasPushToken: tokens.length > 0, hasPhone: !!phone, hasEmail: !!email });

  if (d.channel === "off") {
    await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, delivery: { channel: "off", ok: true, detail: "partner turned this message type off (still visible in the app)" } });
    return { delivered: false, reason: "off" };
  }
  if (d.defer && !opts.force) {
    await msgs.add({ type, text: body, ts: now.getTime(), fromUid: link.trackedUid, deferred: true, delivery: { channel: d.channel, ok: false, detail: "held for quiet hours" } });
    return { delivered: false, reason: "quiet_hours", channel: d.channel };
  }

  const title = TITLES[type].replace("{name}", trackedName);
  let res: { ok: boolean; detail: string };
  if (d.channel === "push") {
    const r = await senders.push(tokens, title, body, { linkId, type });
    res = r;
    if (r.invalidTokens.length) await db.doc(`users/${link.partnerUid}`).update(Object.fromEntries(r.invalidTokens.map((t) => [`fcmTokens.${t}`, FieldValue.delete()])));
  } else if (d.channel === "text") res = await senders.text(phone!, `${title}: ${body}`);
  else res = await senders.email(email!, title, body);

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
    let text = m.text;
    if (m.type === "checkin") {
      // Fold in alerts that were rate-limited earlier today.
      const queued = await l.ref.collection("messages").where("queuedForCheckin", "==", true).get();
      const extra = queued.docs.map((q) => String(q.get("text")).split("\n")[0]).filter(Boolean);
      if (extra.length) text += `\nAlso today: ${extra.join(" · ")}`;
      await Promise.all(queued.docs.map((q) => q.ref.update({ queuedForCheckin: false, includedInCheckin: true })));
    }
    const r = await deliverToPartner(db, senders, l.id, l.data() as Link, name, m.type, text, now);
    if (r.delivered) n++;
  }
  return n;
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
