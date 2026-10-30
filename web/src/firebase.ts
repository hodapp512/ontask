import { initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth } from "firebase/auth";
import { connectFirestoreEmulator, getFirestore } from "firebase/firestore";
import { connectFunctionsEmulator, getFunctions } from "firebase/functions";

const env = import.meta.env;
export const config = {
  apiKey: env.VITE_FIREBASE_API_KEY as string,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN as string,
  projectId: env.VITE_FIREBASE_PROJECT_ID as string,
  appId: env.VITE_FIREBASE_APP_ID as string,
  messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID as string,
};
export const vapidKey = env.VITE_FIREBASE_VAPID_KEY as string | undefined;
export const useEmulators = env.VITE_USE_EMULATORS === "true";
export const configured = !!config.apiKey && !!config.projectId;

export const app = initializeApp(config);
export const auth = getAuth(app);
export const db = getFirestore(app);
export const fns = getFunctions(app);

if (useEmulators) {
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
  connectFunctionsEmulator(fns, "127.0.0.1", 5001);
}
