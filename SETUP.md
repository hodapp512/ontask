# Setup

Everything works locally against the Firebase **emulators** without a cloud account (section 5). Sections 1–4 set up
the real thing. Nothing in this repo contains your phone number, email, API keys or passwords.

## 1. Mac app

```bash
cd desktop && ./gradlew packageDmg     # → desktop/build/compose/binaries/main/dmg/OnTask-*.dmg
```

Open the `.dmg`, drag OnTask to Applications, and open it (first time: right-click → Open, because it is not notarized).
The first-run wizard walks through work hours, the block list, habits, check-in times and permissions.

For development, `./gradlew run` works too, but macOS then files permissions under Terminal/your IDE instead of "OnTask".

### Permissions (Settings → Permissions)

| Permission | What it's for | |
|---|---|---|
| Accessibility | Window titles of apps | **Grant…**, switch OnTask on in System Settings, then **Check again** |
| Screen Recording | Reading on-screen text for AI classification (optional) | same |
| Automation (per browser) | The current page's address and title | **Grant…** next to each browser |

The app is signed ad hoc, so macOS forgets these grants after you install a new build. Build with
`./gradlew packageDmg -PsigningIdentity="Apple Development: …"` (any code-signing certificate) to keep them.

### Turning blocking on

Blocking starts in **Test mode**: OnTask writes its hosts file to its own folder and blocks nothing.

1. If another app or script manages `/etc/hosts`, turn it off and remove it first — both would overwrite the same file.
2. Blocking → Advanced → **Install helper…** (macOS asks for your administrator password once). This installs a small
   root daemon on `127.0.0.1:12901` that copies OnTask's hosts file to `/etc/hosts`.
3. Set **Blocking mode** to **On**.
4. Settings → **Start OnTask at login**.

Ad and malware blocking (a public list from GitHub, refreshed daily) is on by default; toggle it from the menu bar or
Blocking → Ad blocking. Remove everything with Blocking → Advanced → Uninstall.

## 2. AI classification (optional)

Settings → Smarter classification. Pick a provider and model for text and for images from the dropdowns:

* **Ollama** (if installed) or the built-in **llama.cpp**: models not yet on your Mac show **Download**.
  Downloaded models can be deleted under *Advanced: local models*.
* **Claude / OpenAI / Gemini**: paste an API key; it's stored in the macOS Keychain. On-screen text is then sent to
  that provider (you'll be told once).

## 3. Firebase (phone app, partner messages, reminders)

Done once per Firebase project. In the console (<https://console.firebase.google.com>):

1. **Create a project.** Then, bottom-left, **Upgrade** → **Blaze** (pay-as-you-go; needed for scheduled reminders and
   outgoing texts/emails). Expected cost at personal scale is about $0; add a budget alert of a few dollars.
2. **Firestore:** Build → Firestore Database → **Create database** → *Standard edition* → location of your choice →
   *Start in production mode* → **Create**. (The real security rules are deployed in step 7.)
3. **Sign-in:** Build → Authentication → **Get started** → Sign-in method:
   * **Google** → Enable → set *Public-facing name* (e.g. OnTask) and *Support email* → Save.
   * **Add new provider → Email/Password** → turn on **Email/Password** *and* **Email link (passwordless sign-in)** → Save.
     (The app only offers emailed links; there are no passwords.)
4. **Web app:** ⚙ Project settings → General → *Your apps* → **</>** (Web) → register. Copy the config values into
   `web/.env.local` (template: `web/.env.example`). Set `VITE_FIREBASE_AUTH_DOMAIN` to `<project-id>.web.app` (not
   `firebaseapp.com`) so Google sign-in works inside an iPhone Home Screen app. Then allow that address for Google
   sign-in: Google Cloud console → APIs & Services → **Credentials** → *Web client (auto created by Google Service)* →
   add `https://<project-id>.web.app` under *Authorized JavaScript origins* and
   `https://<project-id>.web.app/__/auth/handler` under *Authorized redirect URIs* → Save. (Without this, Google
   sign-in fails with `redirect_uri_mismatch`.)
5. **Push key:** ⚙ Project settings → **Cloud Messaging** → *Web Push certificates* → **Generate key pair**. Copy the
   key into `VITE_FIREBASE_VAPID_KEY`.
6. **Command line** (from the repo root):
   ```bash
   cd firebase && npm install && npx firebase login
   npx firebase use --add          # pick your project, alias it "prod"
   ```
7. **Secrets** — each command prompts for the value (or pipe it in with `--data-file -`):
   ```bash
   npx firebase functions:secrets:set TEXTBELT_KEY --project prod     # textbelt.com key, for texts
   npx firebase functions:secrets:set SMTP_URL --project prod         # Gmail: smtps://you%40gmail.com:APP_PASSWORD@smtp.gmail.com:465
   npx firebase functions:secrets:set SMTP_FROM --project prod        # OnTask <you@gmail.com>
   npx firebase functions:secrets:set GEMINI_API_KEY --project prod   # free key from https://aistudio.google.com/apikey
   ```
   For Gmail use an *app password* (Google Account → Security → 2-Step Verification → App passwords), not your
   normal password; `@` in the address must be written `%40`.
8. **Deploy:**
   ```bash
   cd web && npm ci && npm run build          # builds into firebase/public
   cd ../functions && npm ci
   cd ../firebase && npx firebase deploy --project prod   # uses ../firebase.json
   ```
   The first functions deploy switches on several Google Cloud APIs; if it fails with "Failed to list functions",
   wait a minute and run it again.
9. **Allow Mac linking:** Google Cloud console → **IAM** → find
   `<project-number>-compute@developer.gserviceaccount.com` → ✏ → **Add another role** → *Service Account Token
   Creator* → Save. ("Link my Mac" needs this to mint sign-in tokens.)

### Connecting the apps

1. On your phone, open `https://<project-id>.web.app` (the Mac app shows a QR code for it) and sign in. On iPhone:
   Share → **Add to Home Screen**, open it from there, then Settings → **Turn on notifications**.
2. Phone home screen → **Link my Mac**. Type the 5-character code into OnTask on the Mac (Settings → Phone &
   partner). Codes expire after 5 minutes and work once.
3. Phone home screen → **Invite a partner**: send the link/QR code. Your partner opens it, signs in, and chooses how they want
   to be notified (push, text, email). Use **Send test message** to check delivery.

The Mac app has the project built in (`desktop/src/main/resources/cloud.properties`); if you run your own Firebase
project, put its ID, web API key and URL there before building.

## 4. Releases and updates

* Push a tag `vX.Y.Z`; `.github/workflows/release.yml` builds the `.dmg` and attaches it to a GitHub Release.
* In the app: Settings → Updates → `owner/repo`. It checks weekly and offers the download; it never installs itself.

## 5. Local development (no accounts)

```bash
cd desktop && ./gradlew run                                       # Mac app
cd functions && npm install && npm run build && cp .secret.local.example .secret.local
cd ../firebase && npm install && npx firebase emulators:start      # emulators (demo-ontask)
cd ../web && npm install && npx vite --mode emulator               # http://localhost:5173 → "Dev sign-in"
```

In the Mac app: Settings → Accountability partner → project ID `demo-ontask`, switch on **Use local emulators**.
