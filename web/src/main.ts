import "./style.css";
import { onAuthStateChanged, type User } from "firebase/auth";
import { auth, configured, useEmulators } from "./firebase";
import { h, clear } from "./dom";
import { loginView } from "./views/login";
import { personView, joinView } from "./views/partner";
import { homeView } from "./views/home";
import { settingsView } from "./views/settings";
import { ensureUser, registerServiceWorker } from "./session";

const root = document.getElementById("app")!;

let current: User | null = null;
// Live listeners of the screen on display; stopped when another screen renders.
let cleanups: (() => void)[] = [];
const onLeave = (fn: () => void) => { cleanups.push(fn); };

async function render() {
  try { await renderInner(); } catch (e: any) { clear(root); root.append(h("main", {}, h("div", { class: "card" }, h("h2", {}, "Something went wrong"), h("p", { class: "error" }, String(e?.message ?? e))))); }
}

async function renderInner() {
  cleanups.forEach((f) => f()); cleanups = [];
  clear(root);
  if (!configured) {
    root.append(h("main", {}, h("h1", {}, "OnTask"), h("div", { class: "card" }, h("p", {}, "This app isn't connected to a Firebase project yet."), h("p", { class: "muted" }, "Copy web/.env.example to web/.env.local and fill it in (see SETUP.md), or run with the emulators: npm run dev -- --mode emulator."))));
    return;
  }
  if (!current) { root.append(loginView()); return; }
  const route = location.hash.replace(/^#/, "") || "/";
  const join = route.match(/^\/join\/([A-Za-z0-9]+)/);
  const person = route.match(/^\/person\/([^/]+)/);
  if (join) root.append(await joinView(current, join[1]));
  else if (person) root.append(await personView(current, decodeURIComponent(person[1])));
  else if (route.startsWith("/settings")) root.append(await settingsView(current));
  else root.append(await homeView(current, onLeave));
}

onAuthStateChanged(auth, async (u) => {
  current = u;
  if (u) { await ensureUser(u); registerServiceWorker(); }
  render();
});
window.addEventListener("hashchange", render);
if (useEmulators) console.info("OnTask web: using Firebase emulators");
