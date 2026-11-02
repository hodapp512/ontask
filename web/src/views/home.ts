import type { User } from "firebase/auth";
import { addDoc, collection, collectionGroup, doc, getDoc, getDocs, limit, onSnapshot, orderBy, query, updateDoc, where, type DocumentData } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import QRCode from "qrcode";
import { db, fns } from "../firebase";
import { h } from "../dom";
import { LEVELS, REACTIONS, ago, clock, clockAt, fmtDate, type DayClock, deepShare, firstName, fmtDuration, greeting, segAt, segDetail, timelineSvg, timelineWindow, todayKey, type LevelKey, type Seg } from "../format";
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
      h("div", {}, h("h1", {}, `${greeting()}, ${name}`), h("p", { class: "muted" }, fmtDate(new Date(), true))),
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
  // Titled rows ("YouTube: Learning Java") live in a document only you can read; the shared report has per-site totals.
  let items: DocumentData | undefined;
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
        report ? reportBody(items?.topSites ? { ...report, topSites: items.topSites } : report, { uid: u.uid, categories: (schedule?.categories ?? []) as Cat[] }) : h("p", { class: "muted" }, "Nothing tracked yet today. Your Mac sends an update every few minutes while you work."),
      ));
    }
    kids.push(scheduleCard(schedule, eveningDone));
    body.replaceChildren(...kids.filter((k): k is HTMLElement => !!k));
  };

  onLeave(onSnapshot(collection(db, "users", u.uid, "devices"), (s) => { macs = s.docs.map((d) => d.data()).filter((d) => d.kind === "mac"); draw(); }, () => draw()));
  onLeave(onSnapshot(doc(db, "users", u.uid, "reports", todayKey()), (s) => { report = s.data(); draw(); }, () => draw()));
  onLeave(onSnapshot(doc(db, "users", u.uid, "reportItems", todayKey()), (s) => { items = s.data(); draw(); }, () => draw()));
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

type Cat = { id: string; label: string; level: string; variable?: boolean };

/**
 * Sends a category correction to the Mac, which applies it on its next sync (about a minute). With an item key (a
 * page or video on a Varies site) only that one changes; otherwise the whole app or site.
 */
async function sendCorrection(uid: string, subject: string, category: string, itemKey?: string, title?: string) {
  await addDoc(collection(db, "users", uid, "labelRequests"), { subject, category, ts: Date.now(), ...(itemKey ? { itemKey } : {}), ...(title ? { title } : {}) });
}

/** The "Today so far" view: the same on your phone and for your partner (who can't relabel anything). */
export function reportBody(r: DocumentData, edit?: { uid: string; categories: Cat[] }): HTMLElement {
  const totals = (r.totals ?? {}) as Partial<Record<LevelKey, number>>;
  // Screen time only: Away isn't part of the bar or the totals under it.
  const shown = LEVELS.filter((l) => l.key !== "AWAY");
  const sum = shown.reduce((a, l) => a + (totals[l.key] ?? 0), 0) || 1;
  const share = deepShare(totals);
  const bar = h("div", { class: "bar" }); bar.innerHTML = shown.map((l) => `<div style="width:${((totals[l.key] ?? 0) / sum) * 100}%;background:${l.color}"></div>`).join("");
  const day: DayClock | undefined = typeof r.dayStart === "number" && r.tz ? { start: r.dayStart, tz: r.tz } : undefined;
  const tl = timelineEl((r.timeline ?? []) as Seg[], day);
  const sessions = (r.deepSessions ?? []).length;
  return h("div", { class: "stack" },
    h("div", { class: "row between" },
      h("p", { class: "big" }, share !== null ? `${share}% focused` : `${fmtDuration(totals.DEEP ?? 0)} focused`),
      h("p", { class: "screen-time" }, h("span", { class: "muted small" }, "Screen time"), h("strong", {}, fmtDuration((totals.DEEP ?? 0) + (totals.MIXED ?? 0) + (totals.WASTED ?? 0))))),
    bar,
    h("div", { class: "legend" }, shown.map((l) => h("span", {}, h("span", { class: "dot", style: `background:${l.color}` }), `${l.label} ${fmtDuration(totals[l.key] ?? 0)}`))),
    tl,
    sessions > 0 && h("details", {}, h("summary", {}, "Longest focused sessions"),
      ([...(r.deepSessions ?? [])] as { a: number; b: number }[]).sort((x, y) => (y.b - y.a) - (x.b - x.a)).slice(0, 5)
        .map((s) => h("p", { class: "row between" }, h("span", {}, `${clockAt(s.a, day)}–${clockAt(s.b, day)}`), h("span", { class: "muted" }, fmtDuration(s.b - s.a))))),
    (r.topSites ?? []).length > 0 && h("details", {}, h("summary", {}, "Where the time went"),
      (r.topSites as any[]).slice(0, 8).map((s) => siteRow(s, edit))),
  );
}

function siteRow(s: { name: string; category?: string; categoryId?: string; seconds: number; itemKey?: string; title?: string }, edit?: { uid: string; categories: Cat[] }): HTMLElement {
  if (!edit?.categories.length) return h("div", { class: "site" },
    h("div", { class: "site-line" }, h("span", { class: "site-name" }, s.name), h("span", { class: "muted" }, fmtDuration(s.seconds))),
    s.category && h("span", { class: "muted small" }, s.category));
  const note = h("span", { class: "muted small" });
  // A page or video gets one of your categories; "Varies" is for whole sites.
  const options = s.itemKey ? edit.categories.filter((c) => !c.variable) : edit.categories;
  const sel = h("select", {
    class: "cat",
    onchange: async () => {
      sel.disabled = true;
      try { await sendCorrection(edit.uid, s.name, sel.value, s.itemKey, s.title); note.textContent = "Updating on your Mac…"; }
      catch (e: any) { note.textContent = e.message; sel.disabled = false; }
    },
  }, options.map((c) => h("option", { value: c.id, selected: c.id === s.categoryId }, c.label)),
    !options.some((c) => c.id === s.categoryId) && h("option", { value: "", selected: true, disabled: true }, s.category ?? "Unclassified")) as HTMLSelectElement;
  return h("div", { class: "site" }, h("div", { class: "site-line" }, h("span", { class: "site-name" }, s.name), h("span", { class: "muted" }, fmtDuration(s.seconds))), h("div", { class: "row" }, sel, note));
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
    h("a", { href: `#/person/${encodeURIComponent(linkId)}` }, "Full day, earlier days and messages →"),
  );
}

/**
 * Timeline whose stretches show what you did when tapped (or hovered with a mouse). Pinch (or − / +) to zoom into busy
 * stretches, drag to move along the day. Gestures are tracked on the container, which is never redrawn, so a pinch or
 * drag keeps working while the bar inside is redrawn.
 */
export function timelineEl(segs: Seg[], day?: DayClock): HTMLElement {
  const detail = h("p", { class: "muted small tl-detail" });
  const base = timelineWindow(segs);
  let win: [number, number] = [base[0], base[1]];
  let selected: Seg | undefined;
  const box = h("div", { class: "tl" });
  const reset = h("button", { class: "small", hidden: true }, "Reset") as HTMLButtonElement;
  let frame = 0;
  const draw = () => {
    frame = 0;
    box.innerHTML = timelineSvg(segs, 320, 40, win, day);
    if (selected) box.querySelectorAll("rect[data-i]").forEach((el) => el.classList.toggle("dim", el.getAttribute("data-i") !== String(segs.indexOf(selected!))));
    reset.hidden = win[0] === base[0] && win[1] === base[1];
  };
  const redraw = () => { if (!frame) frame = requestAnimationFrame(draw); };
  const clampTo = (a: number, span: number): [number, number] => { const x = Math.max(0, Math.min(86400 - span, a)); return [x, x + span]; };
  const zoom = (factor: number, frac = 0.5) => {
    const span = win[1] - win[0], next = Math.min(86400, Math.max(300, span * factor));
    win = clampTo(win[0] + span * frac - next * frac, next); redraw();
  };
  const bar = () => box.querySelector("svg")?.getBoundingClientRect() ?? box.getBoundingClientRect();
  const fracAt = (x: number) => { const r = bar(); return Math.min(1, Math.max(0, (x - r.left) / (r.width || 1))); };
  const pick = (x: number) => {
    selected = segAt(segs, fracAt(x), win);
    if (selected) detail.textContent = segDetail(selected, day);
    redraw();
  };

  // One finger: a tap shows that stretch, a drag moves along the day. Two fingers: pinch zooms around their middle.
  const pts = new Map<number, number>();
  let start: { x: number; win: [number, number] } | null = null, pinch: { d: number; mid: number; win: [number, number] } | null = null, moved = false;
  box.addEventListener("pointerdown", (e) => {
    box.setPointerCapture(e.pointerId);
    pts.set(e.pointerId, e.clientX);
    if (pts.size === 2) {
      const [p, q] = [...pts.values()];
      pinch = { d: Math.max(Math.abs(p - q), 20), mid: fracAt((p + q) / 2), win: [win[0], win[1]] }; start = null; moved = true;
    } else if (pts.size === 1) { start = { x: e.clientX, win: [win[0], win[1]] }; moved = false; }
  });
  box.addEventListener("pointermove", (e) => {
    if (!pts.has(e.pointerId)) { if (e.pointerType === "mouse") pick(e.clientX); return; }
    pts.set(e.pointerId, e.clientX);
    if (pinch && pts.size >= 2) {
      const [p, q] = [...pts.values()], span = pinch.win[1] - pinch.win[0];
      const next = Math.min(86400, Math.max(300, span * pinch.d / Math.max(Math.abs(p - q), 20)));
      win = clampTo(pinch.win[0] + span * pinch.mid - next * pinch.mid, next); redraw();
    } else if (start && Math.abs(e.clientX - start.x) > 6) {
      const span = start.win[1] - start.win[0];
      win = clampTo(start.win[0] - ((e.clientX - start.x) / (bar().width || 1)) * span, span); moved = true; redraw();
    }
  });
  const end = (e: PointerEvent) => {
    if (!moved && pts.size === 1 && e.type === "pointerup") pick(e.clientX);
    pts.delete(e.pointerId);
    if (pts.size < 2) pinch = null;
    if (pts.size === 1) { const [x] = [...pts.values()]; start = { x, win: [win[0], win[1]] }; } // the other finger keeps dragging
    if (!pts.size) start = null;
  };
  box.addEventListener("pointerup", end); box.addEventListener("pointercancel", end);

  reset.onclick = () => { win = [base[0], base[1]]; redraw(); };
  draw();
  return h("div", {}, box,
    h("div", { class: "row between" }, h("span", {}),
      h("div", { class: "row tl-zoom" },
        reset, // on the left, so it never lands under a second quick tap on +
        h("button", { class: "small", "aria-label": "Zoom out", onclick: () => zoom(1.5) }, "−"),
        h("button", { class: "small", "aria-label": "Zoom in", onclick: () => zoom(1 / 1.5) }, "+"))),
    detail);
}

const initial = (name?: string) => (name ?? "?").trim().charAt(0).toUpperCase() || "?";
