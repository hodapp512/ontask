import type { User } from "firebase/auth";
import { addDoc, collection, doc, getDoc, getDocs, limit, orderBy, query, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { db, fns } from "../firebase";
import { h } from "../dom";
import { LEVELS, REACTIONS, deepShare, fmtDuration, timelineSvg, todayKey, type LevelKey, type Seg } from "../format";

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
    return h("div", { class: "msg" }, h("small", {}, new Date(d.ts).toLocaleString() + " · " + String(d.type).replace(/_/g, " ")), d.text);
  }) : [h("p", { class: "muted" }, "No messages yet. You'll see check-ins and alerts here.")];

  let reportCard: HTMLElement;
  if (link.share === "report") {
    const rep = await getDoc(doc(db, "users", tracked, "reports", todayKey())).catch(() => null);
    reportCard = rep?.exists() ? reportCardOf(rep.data()!) : h("div", { class: "card" }, h("h2", {}, "Today"), h("p", { class: "muted" }, "No report yet today."));
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

function reportCardOf(r: Record<string, any>): HTMLElement {
  const totals = (r.totals ?? {}) as Partial<Record<LevelKey, number>>;
  const sum = LEVELS.reduce((a, l) => a + (totals[l.key] ?? 0), 0) || 1;
  const share = deepShare(totals);
  const bar = h("div", { class: "bar" }); bar.innerHTML = LEVELS.map((l) => `<div style="width:${((totals[l.key] ?? 0) / sum) * 100}%;background:${l.color}"></div>`).join("");
  const tl = h("div", {}); tl.innerHTML = timelineSvg((r.timeline ?? []) as Seg[]);
  return h("div", { class: "card" },
    h("h2", {}, "Today"),
    share !== null && h("p", {}, `${share}% deep focus`),
    bar,
    h("div", { class: "legend" }, LEVELS.map((l) => h("span", {}, h("span", { class: "dot", style: `background:${l.color}` }), `${l.label} ${fmtDuration(totals[l.key] ?? 0)}`))),
    tl,
    h("p", { class: "muted" }, `${(r.deepSessions ?? []).length} deep-work sessions · ${r.distractionEvents ?? 0} distraction events` + (r.worstHour ? ` · worst hour ${String(r.worstHour.hour).padStart(2, "0")}:00` : "")),
    (r.topSites ?? []).length > 0 && h("div", {}, h("p", { class: "muted" }, "Where the time went"), (r.topSites as any[]).slice(0, 6).map((s) => h("p", {}, `${s.name} — ${fmtDuration(s.seconds)}`))),
  );
}
