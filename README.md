# OnTask

A personal macOS productivity tool: blocks distractions with friction, tracks focus and drift, and supports an
accountability partner through a phone app. Design: [DESIGN.md](DESIGN.md).
Everything you must do by hand (accounts, secrets, cutover): [SETUP.md](SETUP.md).

## Status

| Phase | What | State |
|---|---|---|
| 1 | Tracking, classification rules, report, diagnostics | done |
| 2 | Hosts blocking (daemon), escalating/purpose unblocks, work-hours calendar, Focus, setup wizard, `.dmg` | done; runs in **dry-run** until you cut over |
| 3 | OCR/screenshots, AI providers, intervention ladder, planning, evening reflection, battery prompt | done; AI is opt-in |
| 4 | Firebase rules/functions, PWA, pairing, partner messages, scheduled pushes, update check, release workflow | done; verified on emulators, not deployed |

## Layout

| Dir | What |
|---|---|
| `desktop/` | Kotlin + Compose Desktop app (Gradle). Tray icon, SQLite, all Mac-side logic. |
| `helper/` | Swift helper: active app/title/URL, idle, lock, media, OCR, screenshots, battery. JSON lines over stdio. |
| `daemon/` | Root hosts daemon + install/uninstall scripts. |
| `functions/` | Firebase Cloud Functions (TypeScript): message routing, schedules, pairing, model recommendations. |
| `firebase/` | Firestore rules, indexes, rules tests (config: `firebase.json` at the repo root). |
| `web/` | The PWA for partners and phone use (Vite + TypeScript). |
| `.github/workflows/` | CI, and the tag-triggered `.dmg` release. |

## Run and test

```bash
cd desktop && ./gradlew run          # builds the helper, launches the app
cd desktop && ./gradlew test         # unit + UI-render tests (PNGs land in desktop/build/ui-shots)
cd desktop && ./gradlew packageDmg   # installable app with bundled helper and daemon

cd functions && npm ci && npm test   # pure-logic tests
cd firebase && npm ci && npm test    # Firestore rules (needs Java for the emulator)
cd web && npm ci && npm test && npm run build
```

Opt-in integration checks: `ONTASK_OLLAMA_MODEL=<model> ./gradlew test --tests '*OllamaSmoke*'` (real Ollama), and
`firebase/e2e/pair.mjs` + `ONTASK_E2E_FILE=... ./gradlew test --tests '*EmulatorE2E*'` (Mac → Firestore → Cloud
Function → partner message, against the emulators).

## Safety and privacy

* The app never writes `/etc/hosts` unless you install the root helper **and** switch the blocking mode to On.
  If another app manages `/etc/hosts`, turn it off first.
* No personal data in the repo: contact details live in your Firebase account, API keys in the macOS Keychain or
  Firebase Secret Manager, project settings in the app's local database / `web/.env.local` (gitignored).
* Screenshots and OCR text are held in memory only for the length of one classification call.
* The raw activity log is never uploaded; only events, daily aggregates, your schedule and check-in status.

## Known gaps

* Releases are not code-signed or notarized.
* The 5 min sweep and the weekly Gemini recommendation are untested against real Google services
  (logic is unit-tested; the Gemini request shape and the Ollama/Hugging Face existence checks are untested live).
* Web push is untested on real iOS/Android devices; the service worker loads Firebase from gstatic.
* llama.cpp: no `llama-server` is bundled; GGUF repo IDs in the model catalog are unverified.
* "Drifting off-purpose ends a work unblock early" is not wired (the unblock records its purpose; the AI doesn't yet
  compare screen content against it).
* No habit editing on the phone, group UI, browser extension or Windows port (phase 5).
* Weekly report is a simple self-rating vs. measured table, not a full report.

## License

MIT
