import { doc, getDoc, setDoc, updateDoc, deleteField } from "firebase/firestore";
import { getMessaging, getToken, isSupported } from "firebase/messaging";
import type { User } from "firebase/auth";
import { app, config, db, vapidKey } from "./firebase";

/** Creates/updates the user's profile and records that the app was opened (used for the text fallback). */
export async function ensureUser(u: User) {
  const ref = doc(db, "users", u.uid);
  const snap = await getDoc(ref);
  const name = u.displayName || u.email?.split("@")[0] || "Friend";
  if (!snap.exists()) await setDoc(ref, { displayName: name, createdAt: Date.now(), lastSeenMs: Date.now() });
  else await updateDoc(ref, { lastSeenMs: Date.now() });
}

export function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;
  const q = new URLSearchParams({ apiKey: config.apiKey, projectId: config.projectId, messagingSenderId: config.messagingSenderId, appId: config.appId });
  navigator.serviceWorker.register(`/firebase-messaging-sw.js?${q}`).catch(() => {});
}

/** Asks for notification permission and stores this device's push token on the user's profile. */
export async function enablePush(u: User): Promise<string> {
  if (!(await isSupported())) return "Push isn't supported in this browser. On iPhone, add OnTask to your Home Screen first.";
  if (!vapidKey) return "Push isn't configured (missing VAPID key).";
  const perm = await Notification.requestPermission();
  if (perm !== "granted") return "Notifications were not allowed.";
  const reg = await navigator.serviceWorker.ready;
  const token = await getToken(getMessaging(app), { vapidKey, serviceWorkerRegistration: reg });
  await updateDoc(doc(db, "users", u.uid), { [`fcmTokens.${token}`]: Date.now() });
  return "Push notifications are on for this device.";
}

export async function disablePush(u: User, token: string) { await updateDoc(doc(db, "users", u.uid), { [`fcmTokens.${token}`]: deleteField() }); }
