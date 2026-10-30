import type { User } from "firebase/auth";
import { collection, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { signOut } from "firebase/auth";
import { auth, db, fns } from "../firebase";
import { h } from "../dom";
import { MESSAGE_TYPES } from "../format";
import { enablePush } from "../session";

/** My settings as a partner: how I'm notified for each message type, quiet hours, contact details, push. */
export async function settingsView(u: User): Promise<HTMLElement> {
  const links = await getDocs(query(collection(db, "links"), where("partnerUid", "==", u.uid)));
  const contactRef = doc(db, "users", u.uid, "contact", "main");
  const contact = (await getDoc(contactRef)).data() ?? {};
  const note = h("p", { class: "muted" });
  const say = (t: string, err = false) => { note.textContent = t; note.className = err ? "error" : "muted"; };

  const phone = h("input", { type: "tel", placeholder: "+1 555 010 0000", value: contact.phone ?? "" }) as HTMLInputElement;
  const email = h("input", { type: "email", placeholder: "you@example.com", value: contact.email ?? "" }) as HTMLInputElement;

  const linkCards = links.docs.map((d) => {
    const prefs = d.get("partnerPrefs") ?? { channels: {}, quietHours: null };
    const sels: Record<string, HTMLSelectElement> = {};
    const rows = MESSAGE_TYPES.map((t) => {
      const s = h("select", {}, ["push", "text", "email", "off"].map((c) => h("option", { value: c, selected: (prefs.channels?.[t.key] ?? "push") === c }, c === "off" ? "Off" : c[0].toUpperCase() + c.slice(1)))) as HTMLSelectElement;
      sels[t.key] = s;
      return h("label", {}, t.label, s);
    });
    const qs = h("input", { type: "time", value: prefs.quietHours?.start ?? "" }) as HTMLInputElement;
    const qe = h("input", { type: "time", value: prefs.quietHours?.end ?? "" }) as HTMLInputElement;
    return h("div", { class: "card" },
      h("h2", {}, `Notifications about ${d.get("trackedName") ?? "your friend"}`), rows,
      h("div", { class: "row" }, h("label", { class: "grow" }, "Quiet from", qs), h("label", { class: "grow" }, "until", qe)),
      h("div", { class: "row" },
        h("button", { class: "primary", onclick: async () => {
          const channels = Object.fromEntries(Object.entries(sels).map(([k, s]) => [k, s.value]));
          const quietHours = qs.value && qe.value ? { start: qs.value, end: qe.value, tz: Intl.DateTimeFormat().resolvedOptions().timeZone } : null;
          await updateDoc(doc(db, "links", d.id), { partnerPrefs: { channels, quietHours } }); say("Saved.");
        } }, "Save"),
        h("button", { onclick: async () => { try { const r: any = await httpsCallable(fns, "sendTestMessage")({ linkId: d.id }); say(r.data.delivered ? `Test sent via ${r.data.channel}.` : `Not delivered (${r.data.reason ?? "see Messages"}).`); } catch (e: any) { say(e.message, true); } } }, "Send test message"),
      ));
  });

  return h("main", {},
    h("h1", {}, "Settings"),
    h("div", { class: "card" }, h("h2", {}, "This device"), h("p", { class: "muted" }, "Get push notifications here. On iPhone, add OnTask to your Home Screen first (Share → Add to Home Screen)."),
      h("button", { class: "primary", onclick: async () => say(await enablePush(u).catch((e) => String(e))) }, "Turn on notifications")),
    h("div", { class: "card" }, h("h2", {}, "Backup contact (optional)"), h("p", { class: "muted" }, "Used only if push can't reach you (for example, you haven't opened the app in a few days). Stored privately in your account, never in the code."),
      h("label", {}, "Phone (for texts)", phone), h("label", {}, "Email", email),
      h("button", { onclick: async () => { await setDoc(contactRef, { phone: phone.value.trim() || null, email: email.value.trim() || null }, { merge: true }); say("Saved."); } }, "Save contact details")),
    linkCards.length ? linkCards : h("div", { class: "card" }, h("p", { class: "muted" }, "Once you're paired with someone, you can choose how each kind of message reaches you.")),
    note,
    h("button", { onclick: () => signOut(auth) }, "Sign out"),
  );
}
