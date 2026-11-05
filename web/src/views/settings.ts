import type { User } from "firebase/auth";
import { collection, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { signOut } from "firebase/auth";
import { auth, db, fns } from "../firebase";
import { h } from "../dom";
import { MESSAGE_TYPES, ago } from "../format";
import { enablePush, pushState } from "../session";

/** My settings as a partner: how I'm notified for each message type, quiet hours, contact details, push. */
export async function settingsView(u: User): Promise<HTMLElement> {
  const [links, devices, push] = await Promise.all([
    getDocs(query(collection(db, "links"), where("partnerUid", "==", u.uid))),
    getDocs(collection(db, "users", u.uid, "devices")),
    pushState(u),
  ]);
  const contactRef = doc(db, "users", u.uid, "contact", "main");
  const contact = (await getDoc(contactRef)).data() ?? {};
  const note = h("p", { class: "muted" });
  const say = (t: string, err = false) => { note.textContent = t; note.className = err ? "error" : "muted"; };

  const phone = h("input", { type: "tel", placeholder: "+1 555 010 0000", value: contact.phone ?? "" }) as HTMLInputElement;

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
      h("h2", {}, `Notifications for ${d.get("trackedName") ?? "your friend"}`),
      h("p", { class: "muted" }, "Pick how each kind of message reaches you. Text and email go to the number and address above."), rows,
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

  const deviceRows = devices.docs.map((d) => d.data()).sort((a, b) => (b.lastSyncMs ?? b.lastSeenMs ?? 0) - (a.lastSyncMs ?? a.lastSeenMs ?? 0)).map((x) =>
    h("p", { class: "row between" }, h("span", {}, `${x.kind === "mac" ? "💻" : "📱"} ${x.name ?? x.kind}`), h("span", { class: "muted small" }, ago(x.lastSyncMs ?? x.lastSeenMs ?? 0))));

  return h("main", {},
    h("header", { class: "top" }, h("a", { href: "#/", class: "icon-btn", "aria-label": "Back" }, "←"), h("h1", { class: "grow" }, "Settings")),
    h("div", { class: "card" }, h("h2", {}, "Account"), h("p", {}, u.displayName ?? ""), h("p", { class: "muted" }, u.email ?? ""),
      h("button", { onclick: () => signOut(auth) }, "Sign out")),
    h("div", { class: "card" }, h("h2", {}, "Notifications on this device"),
      h("p", { class: "muted" }, push === "on" ? "On ✓" : push === "needs-home-screen" ? "On iPhone, add OnTask to your Home Screen first (Share → Add to Home Screen), then open it from there." : push === "unsupported-desktop" ? "This browser can't receive notifications." : "Off"),
      push === "off" && h("button", { class: "primary", onclick: async () => { const r = await enablePush(u).catch((e) => ({ ok: false, message: String(e?.message ?? e) })); say(r.message, !r.ok); } }, "Turn on notifications")),
    h("div", { class: "card" }, h("h2", {}, "Linked devices"), deviceRows.length ? deviceRows : h("p", { class: "muted" }, "None yet. Link your Mac from the home screen.")),
    // Where texts and emails go: the phone number you give, and the email of the Google account you signed in with.
    h("div", { class: "card" }, h("h2", {}, "Your phone number and email"),
      h("p", { class: "muted" }, "For notifications by text or email: choose Text or Email for any message type below. If you haven't opened OnTask in 3 days, notifications that would go to this app come this way instead. Only you can see these."),
      h("label", {}, "Phone number (for texts)", phone),
      h("p", { class: "small" }, h("span", { class: "muted" }, "Email: "), contact.email || u.email || "none"),
      h("button", { onclick: async () => { await setDoc(contactRef, { phone: phone.value.trim() || null }, { merge: true }); say("Saved."); } }, "Save phone number")),
    linkCards.length ? linkCards : h("div", { class: "card" }, h("p", { class: "muted" }, "When you support someone, you'll choose here how their messages reach you.")),
    note,
  );
}
