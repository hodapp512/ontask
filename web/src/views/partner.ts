import type { User } from "firebase/auth";
import { addDoc, collection, doc, getDoc, getDocs, limit, onSnapshot, orderBy, query, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { db, fns } from "../firebase";
import { h } from "../dom";
import { REACTIONS, fmtDate, fmtDateTime, todayKey } from "../format";
import { reportBody } from "./home";
import { heatmapEl } from "./heat";

export async function joinView(u: User, code: string): Promise<HTMLElement> {
  const out = h("div", { class: "card" }, h("p", {}, "Joining…"));
  try {
    const r = await httpsCallable<{ code: string }, { linkId: string }>(fns, "acceptInvite")({ code });
    location.hash = "#/";
  } catch (e: any) { out.replaceChildren(h("p", { class: "error" }, e.message ?? "That invite didn't work."), h("a", { href: "#/" }, "Home")); }
  return h("main", {}, h("header", { class: "top" }, h("a", { href: "#/", class: "icon-btn", "aria-label": "Back" }, "←"), h("h1", { class: "grow" }, "Pairing")), out);
}

/** One tracked person: messages, today's report (if they share it) and one-tap reactions. */
export async function personView(u: User, linkId: string): Promise<HTMLElement> {
  const linkSnap = await getDoc(doc(db, "links", linkId));
  if (!linkSnap.exists()) return h("main", {}, h("p", { class: "error" }, "Link not found."));
  const link = linkSnap.data()!;
  const tracked = link.trackedUid as string;
  const status = h("p", { class: "muted" });

  const msgs = await getDocs(query(collection(db, "links", linkId, "messages"), orderBy("ts", "desc"), limit(30)));
  const feed = msgs.docs.length ? msgs.docs.map((m) => {
    const d = m.data();
    return h("div", { class: "msg" }, h("small", {}, fmtDateTime(d.ts) + " · " + String(d.type).replace(/_/g, " ")), d.text);
  }) : [h("p", { class: "muted" }, "No messages yet. You'll see check-ins and alerts here.")];

  // The same "Today so far" view they see, live, with earlier days a tap away.
  let reportCard: HTMLElement;
  if (link.share === "report") {
    const body = h("div", {});
    const planSlot = h("div", {});
    let day = new Date(); let stop: (() => void) | undefined; let stopPlan: (() => void) | undefined;
    const label = h("strong", { class: "grow", style: "text-align:center" });
    const next = h("button", { class: "icon-btn", "aria-label": "Next day" }, "→") as HTMLButtonElement;
    const show = () => {
      stop?.(); stopPlan?.();
      const key = todayKey(day), today = key === todayKey();
      label.textContent = today ? "Today" : fmtDate(day, true);
      next.disabled = today;
      body.replaceChildren(h("p", { class: "muted" }, "Loading…"));
      stop = onSnapshot(doc(db, "users", tracked, "reports", key), (s) => {
        body.replaceChildren(s.exists() ? reportBody(s.data()) : h("p", { class: "muted" }, today ? `${link.trackedName ?? "They"} hasn't been at the computer yet today.` : "No report for this day."));
      }, () => body.replaceChildren(h("p", { class: "muted" }, "Couldn't load this day.")));
      // Their plan for the day: finished tasks struck through.
      stopPlan = onSnapshot(doc(db, "users", tracked, "plans", key), (s) => planSlot.replaceChildren(...planEl(s.get("items"))), () => planSlot.replaceChildren());
    };
    next.onclick = () => { day = new Date(day.getTime() + 86_400_000); show(); };
    const prev = h("button", { class: "icon-btn", "aria-label": "Previous day", onclick: () => { day = new Date(day.getTime() - 86_400_000); show(); } }, "←");
    show();
    window.addEventListener("hashchange", () => { stop?.(); stopPlan?.(); }, { once: true });
    reportCard = h("div", { class: "card" }, h("div", { class: "row" }, prev, label, next), planSlot, body);
    // Their year; tapping a day opens it above.
    const heatSlot = h("div", {});
    getDoc(doc(db, "users", tracked, "heat", "year")).then((s) => { if (s.exists()) heatSlot.replaceChildren(heatmapEl(s.data(), (d) => { day = d; show(); reportCard.scrollIntoView({ behavior: "smooth" }); })); }).catch(() => {});
    reportCard = h("div", { class: "stack" }, reportCard, heatSlot);
  } else reportCard = h("div", { class: "card" }, h("h2", {}, "Today"), h("p", { class: "muted" }, `${link.trackedName} shares messages only.`));

  const reply = h("input", { placeholder: "Add a short note (optional)", maxlength: "280" }) as HTMLInputElement;
  const send = (kind: string) => async () => {
    try {
      await addDoc(collection(db, "links", linkId, "reactions"), { kind, fromUid: u.uid, trackedUid: tracked, ts: Date.now(), ...(reply.value.trim() ? { reply: reply.value.trim() } : {}) });
      status.textContent = "Sent. ✔"; reply.value = "";
    } catch (e: any) { status.textContent = e.message; status.className = "error"; }
  };

  return h("main", {},
    h("header", { class: "top" }, h("a", { href: "#/", class: "icon-btn", "aria-label": "Back" }, "←"), h("h1", { class: "grow" }, link.trackedName ?? "Friend")),
    reportCard,
    h("div", { class: "card" }, h("h2", {}, "Send encouragement"), h("div", { class: "row" }, REACTIONS.map((r) => h("button", { onclick: send(r.kind) }, r.label))), reply, status),
    h("div", { class: "card" }, h("h2", {}, "Messages"), feed),
  );
}

/** "Plan · 1 of 3 done" and the tasks, finished ones struck through. */
function planEl(raw: unknown): HTMLElement[] {
  const items = (Array.isArray(raw) ? raw : []).filter((x: any) => x && typeof x.text === "string" && x.text.trim()) as { text: string; done?: boolean }[];
  if (!items.length) return [];
  return [
    h("h3", {}, `Plan · ${items.filter((x) => x.done).length} of ${items.length} done`),
    h("ul", { class: "plan" }, items.map((x) => h("li", x.done ? { style: "text-decoration:line-through;opacity:.6" } : {}, x.text))),
  ];
}
