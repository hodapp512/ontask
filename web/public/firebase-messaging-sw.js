/* Receives web push while the PWA is closed. The Firebase config arrives as query params from main.ts. */
importScripts("https://www.gstatic.com/firebasejs/12.0.0/firebase-app-compat.js");
importScripts("https://www.gstatic.com/firebasejs/12.0.0/firebase-messaging-compat.js");

const q = new URL(self.location.href).searchParams;
firebase.initializeApp({
  apiKey: q.get("apiKey"), projectId: q.get("projectId"), messagingSenderId: q.get("messagingSenderId"), appId: q.get("appId"),
});
const messaging = firebase.messaging();
// Pushes with a notification part are shown by Firebase itself; showing them here too made every push appear twice.
// Only data-only messages (none are sent today) need showing by hand.
messaging.onBackgroundMessage((payload) => {
  if (payload.notification) return;
  const d = payload.data || {};
  self.registration.showNotification(d.title || "OnTask", { body: d.body || "", icon: "/icon-192.png?v=3", data: d });
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window" }).then((cs) => (cs[0] ? cs[0].focus() : self.clients.openWindow("/"))));
});
