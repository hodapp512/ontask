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

### Blocking

Your block list is blocked around the clock; work hours and Focus sessions only make unblocking harder. Blocking
works by updating `/etc/hosts`, which needs a small root helper:

* At setup, and whenever OnTask opens and finds the helper missing, outdated or not working, it explains why and
  shows macOS's password prompt (at most once per launch). Until it's installed, a banner on Home and a row in
  Settings → Permissions offer the same **Install…** button. Once it works, it doesn't show up anywhere except
  Diagnostics.
* The helper is a root LaunchDaemon (`dev.ontask.daemon`, listening on `127.0.0.1:12901`) that copies OnTask's
  hosts file to `/etc/hosts`. If another app or script manages `/etc/hosts`, turn it off first: both would
  overwrite the same file.
* Developing OnTask: set `ONTASK_BLOCKING_TEST=1` (or the `blocking.test_mode` preference) to stage the hosts file in
  OnTask's own folder and block nothing.

Ad and malware blocking (a public list from GitHub, refreshed at startup and daily) is on by default; toggle it from
the menu bar or Home.

### Uninstalling

Use **Settings → Uninstall OnTask…** (the button at the top). After your administrator password it:

1. removes the blocking helper and OnTask's sections of `/etc/hosts` (your own entries stay);
2. removes the login item (`~/Library/LaunchAgents/dev.ontask.app.plist`);
3. optionally deletes your history and settings (`~/Library/Application Support/OnTask`; kept by default, so a
   reinstall picks up where you left off);
4. moves OnTask to the Trash and quits.

**If you just drag OnTask to the Trash**, the helper cleans up by itself: it checks every 5 minutes that the app is
still where it was installed, and once it has been gone for **24 hours** it removes OnTask's lines from `/etc/hosts`,
the login item, and itself. Sites stay blocked until then; the wait keeps "delete the app" from being a quick way
around blocking, and covers updates that briefly replace the app. Your data folder is left in place. Moving the app
somewhere else counts as gone too; open it from its new place and it asks to reinstall the helper.

To remove the helper by hand right away: `sudo bash daemon/uninstall.sh --target /etc/hosts` from this repository.

## 2. AI classification (optional)

Settings → AI labeling → **Use AI**, then where it runs:

* **Kev, on this Mac** (default): needs `uv` (`brew install uv`) and git. Press **Install Kev**; the model (0.8B,
  about 2 GB on disk, ~3 GB of memory while running) downloads the first time it's used. OnTask starts it when there's
  something to decide and stops it after 10 idle minutes. The 4B size makes fewer mistakes but needs ~10 GB of memory.
* **Jev, TypeSafe cloud:** paste a TypeSafe API key; it's stored in the macOS Keychain. Page titles and on-screen text
  are then sent to TypeSafe (you'll be told once).

Reading on-screen text needs the Screen Recording permission; without it, AI works from app names, titles and
addresses. Why decision models instead of chat models: see [docs/DECISIONS.md](docs/DECISIONS.md).

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
