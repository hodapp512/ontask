# Setup — what you need to do yourself

Everything in this repo works locally and against the Firebase **emulators** without any cloud account.
This file lists the steps that need *you*: accounts, secrets, and the cutover from BlockMe. Nothing here has been
done for you, and nothing in the repo contains your phone number, email, API keys or project IDs.

## 0. Try it locally first (no accounts)

```bash
# Mac app (builds the Swift helper, then launches)
cd desktop && ./gradlew run

# Firebase emulators + PWA, for developing phases 3–4 without a cloud project
cd functions && npm install && npm run build && cp .secret.local.example .secret.local
cd ../firebase && npm install && npx firebase emulators:start --project demo-ontask
cd ../web && npm install && npx vite --mode emulator     # http://localhost:5173 -> "Dev sign-in"
```

In the Mac app: Settings → Accountability partner → project ID `demo-ontask`, switch on **Use local emulators**.
In the web app: Me → Link my Mac → paste the code into the Mac app.

## 1. Cut over from BlockMe (phase 2)

OnTask ships in **Dry run** blocking mode: it writes its own hosts file to `~/Library/Application Support/OnTask/`
and never touches `/etc/hosts`. When you are ready:

1. Quit BlockMe and uninstall its daemon: `~/workspace/blockme/installation/uninstall.sh` (asks for your password).
   (OnTask's installer refuses to run while BlockMe's daemon is present, because both would overwrite `/etc/hosts`.)
2. In OnTask: Blocking → Advanced → **Install helper…** (macOS asks for your administrator password once).
   It installs `/Library/PrivilegedHelperTools/dev.ontask.daemon.sh` and a LaunchDaemon on `127.0.0.1:12901`
   (BlockMe used 12900).
3. Set the blocking mode to **Live**.
4. Settings → **Start OnTask at login** (only available in the installed `.app`, not under `./gradlew run`).
5. To remove: Blocking → Advanced → Uninstall, or `sudo daemon/uninstall.sh`.

Build the installable app: `cd desktop && ./gradlew packageDmg` →
`desktop/build/compose/binaries/main/dmg/OnTask-*.dmg`. It is unsigned; first launch needs right-click → Open
(or sign and notarize with your Apple Developer ID — not set up).

## 2. macOS permissions

| Permission | Why | How |
|---|---|---|
| Automation (per browser) | page URL/title from Chrome, Safari, Brave, Edge, Arc | macOS prompts the first time the browser is in front |
| Accessibility | window titles of non-browser apps | Settings → Privacy & Security → Accessibility → add OnTask |
| Screen Recording | OCR / screenshots for AI classification (optional) | Privacy & Security → Screen & System Audio Recording |

## 3. AI classification (phase 3, optional)

* **Local (default):** install [Ollama](https://ollama.com) (or put a `llama-server` binary on your `PATH`),
  then Settings → Smarter classification → Advanced → Download *Qwen3-VL 4B*. Models can be deleted there too.
  The llama.cpp GGUF repo IDs in `ModelCatalog` were not verified against Hugging Face; fix them if a download 404s.
* **Cloud (Claude / OpenAI / Gemini):** paste an API key in Settings; it is stored in the macOS Keychain
  (`security` tool, service `dev.ontask`). You'll see a one-time notice that on-screen text is sent to that provider.
* Nothing is sent anywhere until you switch AI on.

## 4. Firebase (phase 4)

You need a Google account. Do these in order.

1. **Create a project** at <https://console.firebase.google.com> (any name; note the *project ID*).
2. **Upgrade to the Blaze plan** (pay-as-you-go). Required for scheduled functions and outbound network calls
   from functions. At this scale the expected cost is about $0; set a budget alert anyway.
3. **Enable products:** Authentication (Google provider + *Email link* provider), Firestore (production mode, a
   region near you), Cloud Messaging, Hosting.
4. **Register a Web app** (Project settings → Your apps). Copy the config into `web/.env.local`
   (template: `web/.env.example`). Under Cloud Messaging → *Web Push certificates*, generate a key pair and put the
   public key in `VITE_FIREBASE_VAPID_KEY`.
5. **Authorize your domain** for sign-in: Authentication → Settings → Authorized domains → add `<project>.web.app`.
6. **Point the CLI at the project** (the repo uses a `demo-ontask` placeholder):
   `cd firebase && npx firebase login && npx firebase use --add` (choose your project; this edits `.firebaserc`,
   so don't commit that change if you want the project ID to stay out of the repo — or use `--project` each time).
7. **Secrets** (Secret Manager; each command prompts for the value, nothing is written to disk):
   ```bash
   cd firebase
   npx firebase functions:secrets:set TEXTBELT_KEY     # textbelt.com key, for text fallback
   npx firebase functions:secrets:set SMTP_URL         # e.g. smtps://user:pass@smtp.example.com:465
   npx firebase functions:secrets:set SMTP_FROM        # e.g. OnTask <ontask@example.com>
   npx firebase functions:secrets:set GEMINI_API_KEY   # Google AI Studio key, for weekly model recommendations
   ```
8. **IAM for device linking:** `createDeviceToken` mints custom tokens. Grant the Cloud Functions runtime service
   account (`<project-number>-compute@developer.gserviceaccount.com`) the role **Service Account Token Creator**
   on itself (IAM → roles). Without it, "Link my Mac" fails in production (it works in the emulator).
9. **Deploy:**
   ```bash
   cd web && npm ci && npm run build
   cd ../functions && npm ci
   cd ../firebase && npx firebase deploy
   ```
   (the weekly model-recommendation function and the 5-minute sweep are created by this deploy.)
10. **Mac app:** Settings → Accountability partner → paste the project ID and the web API key, then in the web app
    Me → Link my Mac → paste the code. Then *Invite a partner* shows a link and QR code for your partner.
11. **iPhone push:** open the site in Safari → Share → *Add to Home Screen* → open it from there → Settings →
    *Turn on notifications*. (iOS only delivers web push to installed Home Screen apps.)
12. Text fallback needs the partner's phone number, which they enter themselves in the web app (stored privately in
    their account, never in the repo). Test delivery with *Send test message* in the web app.

Cost/abuse notes: the Mac only uploads events, daily aggregates, the schedule and check-in status; the raw
activity log and OCR text never leave the Mac.

## 5. Releases and updates

* Push a tag `vX.Y.Z` and `.github/workflows/release.yml` builds the `.dmg` on a macOS runner and attaches it to a
  GitHub Release. That needs the repo on GitHub (not created for you).
* In the app: Settings → Updates → enter `owner/repo`. It checks weekly and offers the download; it never installs by
  itself.
* The workflow does not sign or notarize. Add your Developer ID certificate and notarization secrets if you want that.

## 6. Things not done (by design or time)

See the "Known gaps" list in `README.md`.
