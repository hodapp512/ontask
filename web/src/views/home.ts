import type { User } from "firebase/auth";
import { addDoc, collection, collectionGroup, doc, getDoc, getDocs, limit, onSnapshot, orderBy, query, updateDoc, where, type DocumentData } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import QRCode from "qrcode";
import { db, fns } from "../firebase";
import { h } from "../dom";
import { LEVELS, REACTIONS, ago, clock, deepShare, firstName, fmtDuration, greeting, segAt, segDetail, timelineSvg, todayKey, type LevelKey, type Seg } from "../format";
import { enablePush, pushState } from "../session";

type Cleanup = () => void;

/** The one screen of the phone app: your day, your partner, and the people you support. */
export async function homeView(u: User, onLeave: (fn: Cleanup) => void): Promise<HTMLElement> {
  const [profile, sched, myLinks, supportLinks, checkin] = await Promise.all([
    getDoc(doc(db, "users", u.uid)),
    getDoc(doc(db, "users", u.uid, "schedule", "current")),
    getDocs(query(collection(db, "links"), where("trackedUid", "==", u.uid))),
    getDocs(query(collection(db, "links"), where("partnerUid", "==", u.uid))),
    getDoc(doc(db, "users", u.uid, "checkins", todayKey())),
  ]);
  const name = firstName(profile.get("displayName") ?? u.displayName ?? "there");

  return h("main", {},
    h("header", { class: "top" },
      h("div", {}, h("h1", {}, `${greeting()}, ${name}`), h("p", { class: "muted" }, new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" }))),
      h("a", { href: "#/settings", class: "icon-btn", "aria-label": "Settings" }, "⚙︎"),
    ),
    await pushBanner(u),
    yourDay(u, sched.data(), checkin.get("evening") === true, onLeave),
    await partnerSection(u, myLinks.docs),
    await supportSection(u, supportLinks.docs),
  );
}

// ---- Notifications ------------------------------------------------------------------------------------------

async function pushBanner(u: User): Promise<HTMLElement | null> {
  const state = await pushState(u);
  if (state === "on" || state === "unsupported-desktop") return null;
  const msg = h("p", { class: "muted" });
  const box = h("div", { class: "notice row" },
    h("p", { class: "grow" }, state === "needs-home-screen"
      ? "To get reminders on iPhone, tap Share → Add to Home Screen, then open OnTask from there."
      : "Turn on notifications so reminders and your partner's messages reach this phone."),
    state !== "needs-home-screen" && h("button", { class: "primary", onclick: async () => { const r = await enablePush(u).catch((e) => ({ ok: false, message: String(e?.message ?? e) })); if (r.ok) box.remove(); else msg.textContent = r.message; } }, "Turn on"),
    msg,
  );
  return box;
}

// ---- Your day (from your Mac) -------------------------------------------------------------------------------

function yourDay(u: User, schedule: DocumentData | undefined, eveningDone: boolean, onLeave: (fn: Cleanup) => void): HTMLElement {
  const section = h("section", {}, h("h2", { class: "section" }, "Your day"));
  const body = h("div", { class: "stack" }, h("div", { class: "card" }, h("p", { class: "muted" }, "Loading…")));
  section.append(body);

  let macs: DocumentData[] = [];
  let report: DocumentData | undefined;
  let waitingForMac = false;
  const linkCard = macLinkCard(() => { waitingForMac = true; });

  const draw = () => {
    const mac = macs.sort((a, b) => (b.lastSyncMs ?? 0) - (a.lastSyncMs ?? 0))[0];
    const kids: (HTMLElement | null)[] = [];
    if (!mac) kids.push(linkCard);
    else {
      if (waitingForMac) { kids.push(h("div", { class: "notice" }, `✓ Linked to ${mac.name ?? "your Mac"}. Your day will show up here.`)); waitingForMac = false; }
      kids.push(h("div", { class: "card" },
        h("div", {},
          h("h3", {}, "Today so far"),
          h("p", { class: "muted small" }, `💻 ${mac.name ?? "Mac"} · ${mac.lastSyncMs ? "updated " + ago(mac.lastSyncMs) : "not synced yet"}`)),
        report ? reportBody(report) : h("p", { class: "muted" }, "Nothing tracked yet today. Your Mac sends an update every few minutes while you work."),
      ));
    }
    kids.push(scheduleCard(schedule, eveningDone));
    body.replaceChildren(...kids.filter((k): k is HTMLElement => !!k));
  };

  onLeave(onSnapshot(collection(db, "users", u.uid, "devices"), (s) => { macs = s.docs.map((d) => d.data()).filter((d) => d.kind === "mac"); draw(); }, () => draw()));
  onLeave(onSnapshot(doc(db, "users", u.uid, "reports", todayKey()), (s) => { report = s.data(); draw(); }, () => draw()));
  return section;
}

function macLinkCard(onCodeShown: () => void): HTMLElement {
  const area = h("div", { class: "stack" });
  let timer: number | undefined;
  const show = async (btn: HTMLButtonElement) => {
    btn.disabled = true;
    try {
      const r = await httpsCallable<unknown, { code: string; expiresAt: number }>(fns, "createLinkCode")({});
      onCodeShown();
      const left = h("p", { class: "muted small" });
      const again = h("button", { onclick: (e: Event) => show(e.currentTarget as HTMLButtonElement) }, "New code");
      const tick = () => {
        const s = Math.max(0, Math.round((r.data.expiresAt - Date.now()) / 1000));
        left.textContent = s > 0 ? `Waiting for your Mac… code expires in ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}` : "This code expired.";
        again.hidden = s > 0;
        if (s === 0) clearInterval(timer);
      };
      clearInterval(timer); timer = window.setInterval(tick, 1000); tick();
      area.replaceChildren(h("p", {}, "On your Mac, open OnTask → Settings → Phone & partner, and type:"), h("div", { class: "code" }, r.data.code), left, again);
    } catch (e: any) { area.replaceChildren(h("p", { class: "error" }, e.message ?? "Couldn't make a code."), h("button", { onclick: (ev: Event) => show(ev.currentTarget as HTMLButtonElement) }, "Try again")); }
  };
  area.append(h("button", { class: "primary", onclick: (e: Event) => show(e.currentTarget as HTMLButtonElement) }, "Link my Mac"));
  return h("div", { class: "card" },
    h("h3", {}, "Connect your Mac"),
    h("p", { class: "muted" }, "OnTask on your Mac measures your focus and blocks distractions. Link it to see your day here and get reminders on this phone."),
    area,
  );
}

function reportBody(r: DocumentData): HTMLElement {
  const totals = (r.totals ?? {}) as Partial<Record<LevelKey, number>>;
  const sum = LEVELS.reduce((a, l) => a + (totals[l.key] ?? 0), 0) || 1;
  const share = deepShare(totals);
  const bar = h("div", { class: "bar" }); bar.innerHTML = LEVELS.map((l) => `<div style="width:${((totals[l.key] ?? 0) / sum) * 100}%;background:${l.color}"></div>`).join("");
  const tl = timelineEl((r.timeline ?? []) as Seg[]);
  const sessions = (r.deepSessions ?? []).length;
  return h("div", { class: "stack" },
    h("p", { class: "big" }, share !== null ? `${share}% deep focus` : `${fmtDuration(totals.DEEP ?? 0)} deep focus`),
    bar,
    h("div", { class: "legend" }, LEVELS.map((l) => h("span", {}, h("span", { class: "dot", style: `background:${l.color}` }), `${l.label} ${fmtDuration(totals[l.key] ?? 0)}`))),
    tl,
    h("p", { class: "muted small" }, `${sessions} deep-work session${sessions === 1 ? "" : "s"} · ${r.distractionEvents ?? 0} distraction${r.distractionEvents === 1 ? "" : "s"}`),
    (r.topSites ?? []).length > 0 && h("details", {}, h("summary", {}, "Where the time went"), (r.topSites as any[]).slice(0, 8).map((s) => h("p", { class: "row between" }, h("span", {}, s.name), h("span", { class: "muted" }, fmtDuration(s.seconds))))),
  );
}

function scheduleCard(s: DocumentData | undefined, eveningDone: boolean): HTMLElement | null {
  if (!s) return null;
  const now = new Date();
  const iso = ((now.getDay() + 6) % 7) + 1;
  const ranges = (s.exceptions?.[todayKey()] ?? s.workWeek?.[String(iso)] ?? []) as { startMin: number; endMin: number }[];
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const habits = ((s.habits ?? []) as any[]).filter((x) => x.enabled && (x.days ?? []).includes(iso)).sort((a, b) => a.time.localeCompare(b.time));
  const rows = [
    h("p", { class: "row between" }, h("span", {}, "Work hours"), h("span", { class: "muted" }, ranges.length ? ranges.map((r) => `${clock(r.startMin)}–${clock(r.endMin)}`).join(", ") : "Day off")),
    ranges.length > 0 && h("p", { class: "row between" }, h("span", {}, "Evening check-in"), h("span", { class: eveningDone ? "ok" : "muted" }, eveningDone ? "Done ✓" : nowMin >= ranges[ranges.length - 1].endMin ? "Not done yet" : `Around ${clock(ranges[ranges.length - 1].endMin)}`)),
    ...habits.map((x) => { const [hh, mm] = String(x.time).split(":").map(Number); const past = hh * 60 + mm < nowMin; return h("p", { class: "row between" + (past ? " past" : "") }, h("span", {}, x.name), h("span", { class: "muted" }, clock(hh * 60 + mm))); }),
  ];
  return h("div", { class: "card" }, h("h3", {}, "Today's plan"), rows);
}

// ---- Your accountability partner ----------------------------------------------------------------------------

async function partnerSection(u: User, links: { id: string; data(): DocumentData }[]): Promise<HTMLElement> {
  const section = h("section", {}, h("h2", { class: "section" }, "Your accountability partner"));
  if (!links.length) { section.append(inviteCard()); return section; }

  const recent = await getDocs(query(collectionGroup(db, "reactions"), where("trackedUid", "==", u.uid), where("ts", ">", Date.now() - 7 * 86_400_000), orderBy("ts", "asc"))).catch(() => null);
  const reactions = (recent?.docs ?? []).slice(-3).reverse();
  const label = (k: string) => REACTIONS.find((r) => r.kind === k)?.label ?? k;

  for (const d of links) {
    const l = d.data();
    const sel = h("select", { onchange: async () => { await updateDoc(doc(db, "links", d.id), { share: sel.value }); } },
      h("option", { value: "report", selected: l.share === "report" }, "Your full daily report"),
      h("option", { value: "messages", selected: l.share === "messages" }, "Only the messages you send")) as HTMLSelectElement;
    const mine = reactions.filter((r) => r.ref.parent.parent?.id === d.id);
    section.append(h("div", { class: "card" },
      h("div", { class: "row" }, h("div", { class: "avatar" }, initial(l.partnerName)), h("div", { class: "grow" }, h("h3", {}, l.partnerName ?? "Your partner"), h("p", { class: "muted small" }, "Gets your evening review and alerts you choose to send"))),
      h("label", {}, `What ${firstName(l.partnerName ?? "they")} can see`, sel),
      mine.length > 0 && h("div", { class: "stack" }, h("p", { class: "muted small" }, "Recent encouragement"), mine.map((r) => h("div", { class: "msg" }, h("small", {}, ago(r.get("ts"))), label(r.get("kind")) + (r.get("reply") ? ` — “${r.get("reply")}”` : "")))),
    ));
  }
  return section;
}

function inviteCard(): HTMLElement {
  const area = h("div", { class: "stack" });
  const make = async (btn: HTMLButtonElement) => {
    btn.disabled = true;
    try {
      const r = await httpsCallable<unknown, { code: string }>(fns, "createInvite")({});
      const link = `${location.origin}/#/join/${r.data.code}`;
      const canvas = h("canvas", {}) as HTMLCanvasElement;
      await QRCode.toCanvas(canvas, link, { width: 180, margin: 1 });
      const status = h("p", { class: "muted small" });
      const shareText = `Will you be my accountability partner on OnTask? Open this link: ${link}`;
      area.replaceChildren(
        h("p", {}, "Send this link to the person you want. They open it, sign in, and you're paired."),
        h("div", { class: "row" },
          "share" in navigator && h("button", { class: "primary", onclick: () => navigator.share({ title: "OnTask", text: shareText }).catch(() => {}) }, "Share link…"),
          h("button", { onclick: async () => { await navigator.clipboard.writeText(link); status.textContent = "Link copied."; } }, "Copy link")),
        status,
        h("details", {}, h("summary", {}, "Show QR code (if they're next to you)"), canvas),
        h("p", { class: "muted small" }, `Code ${r.data.code} · works once, for 7 days`),
      );
    } catch (e: any) { btn.disabled = false; area.append(h("p", { class: "error" }, e.message)); }
  };
  area.append(h("button", { class: "primary", onclick: (e: Event) => make(e.currentTarget as HTMLButtonElement) }, "Invite a partner"));
  return h("div", { class: "card" },
    h("p", {}, "Someone you trust gets a short evening review from you and, if you want, a heads-up when you're struggling. They never have to approve anything."),
    area,
  );
}

// ---- People you support -------------------------------------------------------------------------------------

async function supportSection(u: User, links: { id: string; data(): DocumentData }[]): Promise<HTMLElement> {
  const section = h("section", {});
  if (!links.length) {
    const code = h("input", { placeholder: "Invite code", autocapitalize: "characters", class: "grow" }) as HTMLInputElement;
    section.append(h("details", { class: "card" },
      h("summary", {}, "Someone asked you to be their partner?"),
      h("p", { class: "muted" }, "Open the link they sent you, or type the code from it here."),
      h("div", { class: "row" }, code, h("button", { onclick: () => { const c = code.value.trim().toUpperCase(); if (c) location.hash = `#/join/${c}`; } }, "Join"))));
    return section;
  }
  section.append(h("h2", { class: "section" }, links.length === 1 ? "Someone you support" : "People you support"));
  for (const d of links) section.append(await personCard(u, d.id, d.data()));
  return section;
}

async function personCard(u: User, linkId: string, l: DocumentData): Promise<HTMLElement> {
  const who = firstName(l.trackedName ?? "Your friend");
  const [rep, msgs] = await Promise.all([
    l.share === "report" ? getDoc(doc(db, "users", l.trackedUid, "reports", todayKey())).catch(() => null) : Promise.resolve(null),
    getDocs(query(collection(db, "links", linkId, "messages"), orderBy("ts", "desc"), limit(1))),
  ]);
  const last = msgs.docs[0]?.data();
  const status = h("p", { class: "muted small" });
  const send = (kind: string) => async () => {
    try { await addDoc(collection(db, "links", linkId, "reactions"), { kind, fromUid: u.uid, trackedUid: l.trackedUid, ts: Date.now() }); status.textContent = `Sent to ${who}. ✔`; }
    catch (e: any) { status.textContent = e.message; }
  };
  return h("div", { class: "card" },
    h("div", { class: "row" }, h("div", { class: "avatar" }, initial(l.trackedName)), h("h3", { class: "grow" }, l.trackedName ?? "Your friend")),
    rep?.exists() ? reportBody(rep.data()!) : h("p", { class: "muted" }, l.share === "report" ? `${who} hasn't been at the computer yet today.` : `${who} shares only the messages they send.`),
    last && h("div", { class: "msg" }, h("small", {}, ago(last.ts)), last.text),
    h("div", { class: "row" }, REACTIONS.map((r) => h("button", { onclick: send(r.kind) }, r.label))),
    status,
    h("a", { href: `#/person/${encodeURIComponent(linkId)}` }, "All messages and a note →"),
  );
}

/** Timeline whose stretches show what you did when tapped (or hovered with a mouse). */
export function timelineEl(segs: Seg[]): HTMLElement {
  const detail = h("p", { class: "muted small tl-detail" }, segs.length ? "Tap the timeline to see what you did." : "");
  const box = h("div", { class: "tl" }); box.innerHTML = timelineSvg(segs);
  const svg = box.querySelector("svg")!;
  const show = (e: PointerEvent) => {
    const rect = svg.getBoundingClientRect();
    const seg = segAt(segs, (e.clientX - rect.left) / rect.width);
    svg.querySelectorAll("rect[data-i]").forEach((el) => el.classList.toggle("dim", !!seg && el.getAttribute("data-i") !== String(segs.indexOf(seg))));
    if (seg) detail.textContent = segDetail(seg);
  };
  svg.addEventListener("pointerdown", show);
  svg.addEventListener("pointermove", (e) => { if (e.pointerType === "mouse") show(e); });
  return h("div", {}, box, detail);
}

const initial = (name?: string) => (name ?? "?").trim().charAt(0).toUpperCase() || "?";
