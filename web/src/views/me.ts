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
  let countdown: number | undefined;
  const makeCode = async () => {
    const r = await httpsCallable<unknown, { code: string; expiresAt: number }>(fns, "createLinkCode")({});
    const left = h("p", { class: "muted" });
    const tick = () => {
      const s = Math.max(0, Math.round((r.data.expiresAt - Date.now()) / 1000));
      left.textContent = s > 0 ? `Expires in ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}` : "Expired — make a new code.";
      if (s === 0) clearInterval(countdown);
    };
    clearInterval(countdown); countdown = window.setInterval(tick, 1000); tick();
    device.replaceChildren(h("p", {}, "On your Mac, open OnTask and type this code:"), h("code", { class: "big" }, r.data.code), left);
  };

  const habits = ((sched.data()?.habits ?? []) as any[]);
  return h("main", {},
    h("h1", {}, `Hi, ${auth.currentUser?.displayName ?? "there"}`),
    h("div", { class: "card" }, h("h2", {}, "Your partner"), shareRows.length ? shareRows : h("p", { class: "muted" }, "Nobody is paired yet."),
      h("button", { class: "primary", onclick: makeInvite }, "Invite a partner"), invite),
    h("div", { class: "card" }, h("h2", {}, "Join as a partner"), h("p", { class: "muted" }, "Got a code from someone?"),
      h("div", { class: "row" }, (() => { const i = h("input", { placeholder: "CODE", class: "grow" }) as HTMLInputElement; return [i, h("button", { onclick: () => { if (i.value.trim()) location.hash = `#/join/${i.value.trim().toUpperCase()}`; } }, "Join")]; })())),
    h("div", { class: "card" }, h("h2", {}, "Link my Mac"), h("button", { onclick: makeCode }, "Show code"), device),
    h("div", { class: "card" }, h("h2", {}, "Habit prompts"), h("p", { class: "muted" }, "Set on your Mac; delivered here even when you're away from it."),
      habits.length ? habits.map((x) => h("p", {}, `${x.time}  ${x.name}`)) : h("p", { class: "muted" }, "None yet.")),
    out,
  );
}
