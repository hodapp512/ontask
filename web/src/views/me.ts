import type { User } from "firebase/auth";
import { collection, doc, getDoc, getDocs, query, updateDoc, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import QRCode from "qrcode";
import { auth, db, fns } from "../firebase";
import { h } from "../dom";

/** My own view: who sees what, inviting a partner, linking my Mac, my habits. */
export async function meView(u: User): Promise<HTMLElement> {
  const links = await getDocs(query(collection(db, "links"), where("trackedUid", "==", u.uid)));
  const sched = await getDoc(doc(db, "users", u.uid, "schedule", "current"));
  const out = h("div", { class: "card" });

  const shareRows = links.docs.map((d) => {
    const sel = h("select", { onchange: async () => { await updateDoc(doc(db, "links", d.id), { share: sel.value }); } }, h("option", { value: "report", selected: d.get("share") === "report" }, "Full daily report (timeline and sites)"), h("option", { value: "messages", selected: d.get("share") === "messages" }, "Only the messages I send")) as HTMLSelectElement;
    return h("label", {}, `${d.get("partnerName") ?? "Partner"} sees`, sel);
  });

  const invite = h("div", {});
  const makeInvite = async () => {
    const r = await httpsCallable<unknown, { code: string }>(fns, "createInvite")({});
    const link = `${location.origin}/#/join/${r.data.code}`;
    const canvas = h("canvas", {}) as HTMLCanvasElement;
    await QRCode.toCanvas(canvas, link, { width: 180 });
    invite.replaceChildren(h("code", { class: "big" }, r.data.code), h("p", { class: "muted" }, "Share this link or QR code. It works once and expires in 7 days."), h("p", {}, link), canvas);
  };

  const device = h("div", {});
  const makeToken = async () => {
    const r = await httpsCallable<unknown, { token: string }>(fns, "createDeviceToken")({});
    device.replaceChildren(h("p", { class: "muted" }, "Paste this into OnTask on your Mac (Settings → Accountability partner → Link this Mac). It's valid for one hour."), h("code", { class: "big", style: "font-size:11px;letter-spacing:0" }, r.data.token));
  };

  const habits = ((sched.data()?.habits ?? []) as any[]);
  return h("main", {},
    h("h1", {}, `Hi, ${auth.currentUser?.displayName ?? "there"}`),
    h("div", { class: "card" }, h("h2", {}, "Your partner"), shareRows.length ? shareRows : h("p", { class: "muted" }, "Nobody is paired yet."),
      h("button", { class: "primary", onclick: makeInvite }, "Invite a partner"), invite),
    h("div", { class: "card" }, h("h2", {}, "Join as a partner"), h("p", { class: "muted" }, "Got a code from someone?"),
      h("div", { class: "row" }, (() => { const i = h("input", { placeholder: "CODE", class: "grow" }) as HTMLInputElement; return [i, h("button", { onclick: () => { if (i.value.trim()) location.hash = `#/join/${i.value.trim().toUpperCase()}`; } }, "Join")]; })())),
    h("div", { class: "card" }, h("h2", {}, "Link my Mac"), h("button", { onclick: makeToken }, "Create link code"), device),
    h("div", { class: "card" }, h("h2", {}, "Habit prompts"), h("p", { class: "muted" }, "Set on your Mac; delivered here even when you're away from it."),
      habits.length ? habits.map((x) => h("p", {}, `${x.time}  ${x.name}`)) : h("p", { class: "muted" }, "None yet.")),
    out,
  );
}
