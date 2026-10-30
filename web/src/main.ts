import "./style.css";
import { onAuthStateChanged, type User } from "firebase/auth";
import { auth, configured, useEmulators } from "./firebase";
import { h, clear } from "./dom";
import { loginView } from "./views/login";
import { partnerHome, personView, joinView } from "./views/partner";
import { meView } from "./views/me";
import { settingsView } from "./views/settings";
import { ensureUser, registerServiceWorker } from "./session";

const root = document.getElementById("app")!;

function shell(user: User, active: string, content: HTMLElement): HTMLElement {
  const tab = (href: string, label: string, key: string) => h("a", { href, class: key === active ? "on" : "" }, label);
  return h("div", {}, h("nav", {}, tab("#/", "People", "people"), tab("#/me", "Me", "me"), tab("#/settings", "Settings", "settings")), content);
}

let current: User | null = null;

async function render() {
  clear(root);
  if (!configured) {
    root.append(h("main", {}, h("h1", {}, "OnTask"), h("div", { class: "card" }, h("p", {}, "This app isn't connected to a Firebase project yet."), h("p", { class: "muted" }, "Copy web/.env.example to web/.env.local and fill it in (see SETUP.md), or run with the emulators: npm run dev -- --mode emulator."))));
    return;
  }
  if (!current) { root.append(loginView()); return; }
  const route = location.hash.replace(/^#/, "") || "/";
  const join = route.match(/^\/join\/([A-Za-z0-9]+)/);
  const person = route.match(/^\/person\/([^/]+)/);
  if (join) root.append(shell(current, "people", await joinView(current, join[1])));
  else if (person) root.append(shell(current, "people", await personView(current, decodeURIComponent(person[1]))));
  else if (route.startsWith("/me")) root.append(shell(current, "me", await meView(current)));
  else if (route.startsWith("/settings")) root.append(shell(current, "settings", await settingsView(current)));
  else root.append(shell(current, "people", await partnerHome(current)));
}

onAuthStateChanged(auth, async (u) => {
  current = u;
  if (u) { await ensureUser(u); registerServiceWorker(); }
  render();
});
window.addEventListener("hashchange", render);
if (useEmulators) console.info("OnTask web: using Firebase emulators");
