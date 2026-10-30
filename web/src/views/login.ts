import { GoogleAuthProvider, isSignInWithEmailLink, sendSignInLinkToEmail, signInAnonymously, signInWithEmailLink, signInWithPopup, signInWithRedirect } from "firebase/auth";
import { auth, useEmulators } from "../firebase";
import { h } from "../dom";

export function loginView(): HTMLElement {
  const msg = h("p", { class: "muted" });
  const email = h("input", { type: "email", placeholder: "you@example.com", autocomplete: "email" }) as HTMLInputElement;
  const say = (t: string, err = false) => { msg.textContent = t; msg.className = err ? "error" : "muted"; };

  // Finishing a passwordless sign-in: the link opens the app with the sign-in token in the URL.
  if (isSignInWithEmailLink(auth, location.href)) {
    const saved = localStorage.getItem("ontask.emailForSignIn");
    const addr = saved || prompt("Confirm your email to finish signing in") || "";
    signInWithEmailLink(auth, addr, location.href).then(() => { localStorage.removeItem("ontask.emailForSignIn"); history.replaceState({}, "", location.pathname + location.hash); }).catch((e) => say(String(e.message), true));
  }

  const google = async () => {
    const provider = new GoogleAuthProvider();
    try { await signInWithPopup(auth, provider); }
    catch (e: any) {
      // Home-screen apps on iOS can block popups; fall back to a full-page redirect.
      if (e?.code === "auth/popup-blocked" || e?.code === "auth/operation-not-supported-in-this-environment") await signInWithRedirect(auth, provider);
      else if (e?.code !== "auth/popup-closed-by-user") say(e.message, true);
    }
  };

  return h("main", {},
    h("h1", {}, "OnTask"),
    h("p", { class: "muted" }, "Stay on task together. Sign in to get started."),
    h("div", { class: "card" },
      h("button", { class: "primary", onclick: google }, "Continue with Google"),
      h("label", {}, "Or get a sign-in link by email (no password)", email),
      h("button", {
        onclick: async () => {
          try {
            await sendSignInLinkToEmail(auth, email.value.trim(), { url: location.origin + "/", handleCodeInApp: true });
            localStorage.setItem("ontask.emailForSignIn", email.value.trim());
            say("Check your inbox for the link. Open it on this device.");
          } catch (e: any) { say(e.message, true); }
        },
      }, "Email me a link"),
      useEmulators && h("button", { onclick: () => signInAnonymously(auth).catch((e) => say(e.message, true)) }, "Dev sign-in (emulator only)"),
      msg,
    ),
  );
}
