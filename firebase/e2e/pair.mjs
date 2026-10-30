// End-to-end helper against the emulators: creates a tracked user and a partner, pairs them, mints a device token.
// Usage: node pair.mjs  (emulators running)  -> writes /tmp/ontask-e2e.json
import { initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInAnonymously, updateProfile } from "firebase/auth";
import { connectFirestoreEmulator, doc, getFirestore, setDoc } from "firebase/firestore";
import { connectFunctionsEmulator, getFunctions, httpsCallable } from "firebase/functions";
import { writeFileSync } from "node:fs";

async function client(name) {
  const app = initializeApp({ apiKey: "demo", projectId: "demo-ontask", appId: "x" }, name);
  const auth = getAuth(app), db = getFirestore(app), fns = getFunctions(app);
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
  connectFunctionsEmulator(fns, "127.0.0.1", 5001);
  const { user } = await signInAnonymously(auth);
  return { auth, db, fns, uid: user.uid };
}
const a = await client("alice"), b = await client("bob");
await setDoc(doc(a.db, "users", a.uid), { displayName: "Alice", createdAt: Date.now() });
await setDoc(doc(b.db, "users", b.uid), { displayName: "Bob", createdAt: Date.now(), lastSeenMs: Date.now(), fcmTokens: { "fake-token": Date.now() } });
const { code } = (await httpsCallable(a.fns, "createInvite")({})).data;
const { linkId } = (await httpsCallable(b.fns, "acceptInvite")({ code })).data;
const { code: linkCode } = (await httpsCallable(a.fns, "createLinkCode")({})).data;
writeFileSync("/tmp/ontask-e2e.json", JSON.stringify({ trackedUid: a.uid, partnerUid: b.uid, linkId, linkCode }));
console.log("paired", linkId);
process.exit(0);
