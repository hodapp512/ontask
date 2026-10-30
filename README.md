# OnTask

A personal macOS productivity tool: blocks distractions, adds friction to unblocking, tracks focus, and supports an accountability partner. Successor to BlockMe. See [DESIGN.md](DESIGN.md) for the full design.

**Status: phase 1 (tracking only).** OnTask records what you're doing and reports on it. It never touches `/etc/hosts`, so it runs fine alongside BlockMe.

## Layout

| Dir | What |
|---|---|
| `desktop/` | Kotlin + Compose Desktop app (Gradle). Tray icon, report, diagnostics, settings. SQLite storage. |
| `helper/` | Swift command-line helper: active app, window title, browser URL, idle time, lock/sleep state. JSON lines over stdin/stdout. |

`daemon/`, `web/`, `functions/` and `firebase/` arrive in later phases.

## Run

Requires macOS 14+, a JDK (17+), and the Swift toolchain (Xcode or Command Line Tools).

```bash
cd desktop
./gradlew run     # builds the helper (swift build -c release), then launches the app
./gradlew test
```

Data lives in `~/Library/Application Support/OnTask/ontask.db` (override with `-Dontask.data=<dir>`).

### Permissions

- **Accessibility** (optional): needed for window titles of non-browser apps. Grant it to the process that launches the helper (your terminal or IDE when using `./gradlew run`, the app itself once packaged). Diagnostics has a button that opens the system prompt.
- **Automation** (optional): the first time Chrome/Safari/Brave/Edge/Arc is frontmost, macOS asks whether the helper may control it. This is how the page URL and title are read.

Without either, tracking still works with app names only.

## Helper protocol

One JSON object per line. Request `{"id":1,"cmd":"sample"}` → `{"id":1,"ok":true,"result":{...}}`. Commands: `ping`, `sample`, `request_accessibility`. New commands (screenshot, OCR) are added to the `handlers` table in `helper/Sources/ontask-helper/main.swift`.

## License

MIT
