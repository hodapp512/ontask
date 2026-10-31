import { doc, getDoc, setDoc, updateDoc, deleteField } from "firebase/firestore";
import { getMessaging, getToken, isSupported } from "firebase/messaging";
import type { User } from "firebase/auth";
import { app, config, db, vapidKey } from "./firebase";

/** Creates/updates the user's profile and records that the app was opened (used for the text fallback). */
export async function ensureUser(u: User) {
  const ref = doc(db, "users", u.uid);
  const snap = await getDoc(ref);
  const name = u.displayName || u.email?.split("@")[0] || "Friend";
  if (!snap.exists()) await setDoc(ref, { displayName: name, email: u.email ?? null, createdAt: Date.now(), lastSeenMs: Date.now() });
  else await updateDoc(ref, { lastSeenMs: Date.now(), ...(u.email ? { email: u.email } : {}) });
  // So the Mac can show which phones are linked to this account.
  await setDoc(doc(db, "users", u.uid, "devices", deviceId()), { kind: "phone", name: deviceName(), lastSeenMs: Date.now() }, { merge: true }).catch(() => {});
}

function deviceId(): string {
  try {
    let id = localStorage.getItem("ontask.deviceId");
    if (!id) { id = "web-" + crypto.randomUUID(); localStorage.setItem("ontask.deviceId", id); }
    return id;
  } catch { return "web-unknown"; }
}

export function deviceName(ua = navigator.userAgent): string {
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua)) return "iPad";
  if (/Android/.test(ua)) return /Mobile/.test(ua) ? "Android phone" : "Android tablet";
  return "Web browser";
}

const isIos = () => /iPhone|iPad/.test(navigator.userAgent);
const standalone = () => matchMedia("(display-mode: standalone)").matches || (navigator as any).standalone === true;

/** Whether this device gets push: "on", "off" (can be turned on), or why it can't. */
export async function pushState(u: User): Promise<"on" | "off" | "needs-home-screen" | "unsupported-desktop"> {
  if (isIos() && !standalone()) return "needs-home-screen";
  if (!(await isSupported().catch(() => false)) || !("Notification" in window)) return isIos() ? "needs-home-screen" : "unsupported-desktop";
  if (Notification.permission !== "granted") return "off";
  const tokens = (await getDoc(doc(db, "users", u.uid))).get("fcmTokens") ?? {};
  return Object.keys(tokens).length ? "on" : "off";
}

export function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;
  const q = new URLSearchParams({ apiKey: config.apiKey, projectId: config.projectId, messagingSenderId: config.messagingSenderId, appId: config.appId });
  navigator.serviceWorker.register(`/firebase-messaging-sw.js?${q}`).catch(() => {});
}

/** Asks for notification permission and stores this device's push token on the user's profile. */
export async function enablePush(u: User): Promise<{ ok: boolean; message: string }> {
  if (!(await isSupported())) return { ok: false, message: "This browser can't receive notifications. On iPhone, add OnTask to your Home Screen first." };
  if (!vapidKey) return { ok: false, message: "Notifications aren't set up on the server yet." };
  const perm = await Notification.requestPermission();
  if (perm !== "granted") return { ok: false, message: "Notifications were blocked. Allow them for this site in your browser settings, then try again." };
  const reg = await navigator.serviceWorker.ready;
  const token = await getToken(getMessaging(app), { vapidKey, serviceWorkerRegistration: reg });
  await updateDoc(doc(db, "users", u.uid), { [`fcmTokens.${token}`]: Date.now() });
  return { ok: true, message: "Notifications are on for this device." };
}

export async function disablePush(u: User, token: string) { await updateDoc(doc(db, "users", u.uid), { [`fcmTokens.${token}`]: deleteField() }); }
