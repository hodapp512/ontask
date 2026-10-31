import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, getDocs, setDoc, updateDoc, collection, collectionGroup, query, where, addDoc } from "firebase/firestore";
import { readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";

let env: RulesTestEnvironment;
const TRACKED = "alice", PARTNER = "bob", STRANGER = "mallory";
const LINK = `${TRACKED}_${PARTNER}`;

beforeAll(async () => {
  env = await initializeTestEnvironment({ projectId: "demo-ontask", firestore: { rules: readFileSync("firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 } });
});
afterAll(async () => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "links", LINK), { trackedUid: TRACKED, partnerUid: PARTNER, participants: [TRACKED, PARTNER], status: "active", share: "report", partnerPrefs: { channels: {} } });
    await setDoc(doc(db, "users", TRACKED, "reports", "2026-10-30"), { deepSec: 100 });
    await setDoc(doc(db, "users", TRACKED, "contact", "main"), { phone: "+10000000000" });
  });
});
const as = (uid: string) => env.authenticatedContext(uid).firestore();

describe("reports", () => {
  it("partner reads the report when share=report; strangers never", async () => {
    await assertSucceeds(getDoc(doc(as(PARTNER), "users", TRACKED, "reports", "2026-10-30")));
    await assertFails(getDoc(doc(as(STRANGER), "users", TRACKED, "reports", "2026-10-30")));
    await assertSucceeds(getDoc(doc(as(TRACKED), "users", TRACKED, "reports", "2026-10-30")));
  });
  it("partner loses the report when the user narrows sharing to messages", async () => {
    await assertSucceeds(updateDoc(doc(as(TRACKED), "links", LINK), { share: "messages" }));
    await assertFails(getDoc(doc(as(PARTNER), "users", TRACKED, "reports", "2026-10-30")));
  });
  it("partners can't write reports", async () => {
    await assertFails(setDoc(doc(as(PARTNER), "users", TRACKED, "reports", "x"), { a: 1 }));
  });
  it("page and video titles are yours only, even with the full report shared", async () => {
    await assertSucceeds(setDoc(doc(as(TRACKED), "users", TRACKED, "reportItems", "2026-10-30"), { topSites: [] }));
    await assertSucceeds(getDoc(doc(as(TRACKED), "users", TRACKED, "reportItems", "2026-10-30")));
    await assertFails(getDoc(doc(as(PARTNER), "users", TRACKED, "reportItems", "2026-10-30")));
  });
});

describe("private data", () => {
  it("contact details and the raw event outbox are owner-only", async () => {
    await assertFails(getDoc(doc(as(PARTNER), "users", TRACKED, "contact", "main")));
    await assertFails(getDoc(doc(as(STRANGER), "users", TRACKED)));
    await assertSucceeds(addDoc(collection(as(TRACKED), "users", TRACKED, "events"), { type: "checkin", ts: 1 }));
    await assertFails(addDoc(collection(as(PARTNER), "users", TRACKED, "events"), { type: "checkin", ts: 1 }));
    await assertFails(getDoc(doc(as(PARTNER), "users", TRACKED, "events", "any")));
  });
});

describe("links", () => {
  it("clients cannot create links or invites (functions only)", async () => {
    await assertFails(setDoc(doc(as(STRANGER), "links", `${TRACKED}_${STRANGER}`), { trackedUid: TRACKED, partnerUid: STRANGER, participants: [TRACKED, STRANGER], status: "active" }));
    await assertFails(setDoc(doc(as(TRACKED), "invites", "ABCD1234"), { trackedUid: TRACKED }));
  });
  it("each side can change only its own part", async () => {
    await assertSucceeds(updateDoc(doc(as(PARTNER), "links", LINK), { partnerPrefs: { channels: { checkin: "text" } } }));
    await assertFails(updateDoc(doc(as(PARTNER), "links", LINK), { share: "messages" }));
    await assertFails(updateDoc(doc(as(TRACKED), "links", LINK), { partnerPrefs: {} }));
    await assertFails(updateDoc(doc(as(STRANGER), "links", LINK), { share: "messages" }));
  });
  it("each side can list its own links with a filtered query, but not everyone's", async () => {
    await assertSucceeds(getDocs(query(collection(as(TRACKED), "links"), where("trackedUid", "==", TRACKED))));
    await assertSucceeds(getDocs(query(collection(as(PARTNER), "links"), where("partnerUid", "==", PARTNER))));
    await assertFails(getDocs(collection(as(STRANGER), "links")));
    await assertFails(getDocs(query(collection(as(STRANGER), "links"), where("trackedUid", "==", TRACKED))));
  });
  it("only participants read messages; nobody writes them from a client", async () => {
    await env.withSecurityRulesDisabled(async (c) => { await setDoc(doc(c.firestore(), "links", LINK, "messages", "m1"), { text: "hi" }); });
    await assertSucceeds(getDoc(doc(as(PARTNER), "links", LINK, "messages", "m1")));
    await assertFails(getDoc(doc(as(STRANGER), "links", LINK, "messages", "m1")));
    await assertFails(setDoc(doc(as(PARTNER), "links", LINK, "messages", "m2"), { text: "forged" }));
  });
});

describe("reactions", () => {
  const ok = { fromUid: PARTNER, trackedUid: TRACKED, kind: "thumbs_up", ts: 1 };
  it("partner can react with a preset and a short reply", async () => {
    await assertSucceeds(addDoc(collection(as(PARTNER), "links", LINK, "reactions"), { ...ok, reply: "Proud of you" }));
  });
  it("rejects unknown kinds, long replies, spoofed senders and tracked users reacting to themselves", async () => {
    await assertFails(addDoc(collection(as(PARTNER), "links", LINK, "reactions"), { ...ok, kind: "nag" }));
    await assertFails(addDoc(collection(as(PARTNER), "links", LINK, "reactions"), { ...ok, reply: "x".repeat(281) }));
    await assertFails(addDoc(collection(as(PARTNER), "links", LINK, "reactions"), { ...ok, fromUid: TRACKED }));
    await assertFails(addDoc(collection(as(TRACKED), "links", LINK, "reactions"), { ...ok, fromUid: TRACKED }));
  });
});

describe("reaction pull by the tracked user's Mac", () => {
  it("can query reactions addressed to themselves, not anyone else's", async () => {
    await env.withSecurityRulesDisabled(async (c) => { await setDoc(doc(c.firestore(), "links", LINK, "reactions", "r1"), { fromUid: PARTNER, trackedUid: TRACKED, kind: "proud", ts: 5 }); });
    await assertSucceeds(getDocs(query(collectionGroup(as(TRACKED), "reactions"), where("trackedUid", "==", TRACKED))));
    await assertFails(getDocs(query(collectionGroup(as(STRANGER), "reactions"), where("trackedUid", "==", TRACKED))));
    await assertFails(getDocs(collectionGroup(as(STRANGER), "reactions")));
  });
});

describe("model recommendation", () => {
  it("any signed-in user reads, nobody writes", async () => {
    await env.withSecurityRulesDisabled(async (c) => { await setDoc(doc(c.firestore(), "modelRecommendation", "latest"), { candidates: [] }); });
    await assertSucceeds(getDoc(doc(as(STRANGER), "modelRecommendation", "latest")));
    await assertFails(setDoc(doc(as(TRACKED), "modelRecommendation", "latest"), { candidates: [1] }));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "modelRecommendation", "latest")));
  });
  it("only you can send category corrections for your own day", async () => {
    const req = { subject: "youtube.com", category: "LEARNING", ts: 1 };
    await assertSucceeds(setDoc(doc(as(TRACKED), "users", TRACKED, "labelRequests", "r1"), req));
    await assertFails(setDoc(doc(as(PARTNER), "users", TRACKED, "labelRequests", "r2"), req));
    await assertFails(setDoc(doc(as(TRACKED), "users", TRACKED, "labelRequests", "r3"), { ...req, extra: true }));
    await assertSucceeds(setDoc(doc(as(TRACKED), "users", TRACKED, "labelRequests", "r4"), { ...req, itemKey: "youtube.com/watch?v=abc", title: "Learning Java" }));
  });
});
