# OnTask on other platforms

What each platform allows for OnTask's three jobs — **see what's on screen**, **judge it**, **block it** — and the
catches. Written 2026-11-02 when the Android app was started; iPhone and Windows are explored, not built.

## Android (being built)

| Job | How | Catches |
|---|---|---|
| Which app is in front | `UsageStatsManager` (user grants **Usage access** in Settings) | Cheap and reliable; polled only while the screen is on |
| Which site / video / page | An **Accessibility service** reads the browser's address bar and on-screen text (e.g. a YouTube title) | User must turn it on; Android may show warnings; Play Store requires a declaration and review for non-accessibility use (we sideload instead) |
| Judging | Rules and categories from Firestore; unknown apps and Varies pages go to a Cloud Function that asks **Jev**; if unsure, a notification asks the user | Kev (local model) is too heavy for a phone; titles leave the phone (one-time notice) |
| Prompts | Heads-up notification (step 1); full-screen screen over the app (steps 2–3) | Needs **Display over other apps** |
| Blocking apps (v2) | The Accessibility service sees a blocked app and covers it / goes home (Stay Focused's method) | Can be turned off in Settings; device-admin makes uninstalling harder; full lockdown needs device-owner setup over USB |
| Blocking sites (v2) | `/etc/hosts` needs root, so instead a **local DNS VPN** (`VpnService`, like NetGuard/Blokada): DNS filtered on the phone, nothing sent anywhere | Only one VPN at a time; "Private DNS: strict" can bypass it |
| Staying alive | Foreground service + Accessibility | Samsung kills background apps: add OnTask to **Never sleeping apps** |

- A **PWA can't do any of this**: no access to other apps, the screen, or the network stack. The web app stays for
  partners (and anyone without the Android app).
- Distribution: **sideloaded APK** (installed with `adb`), so no Play policy review. Android 10+ (tested on a Galaxy
  A36, Android 15 / One UI 7).
- Shared Kotlin (`shared/`) holds the classifier, categories, effective-focus math and report merging, used by the
  Mac app and the Android app.

## iPhone (explored only)

- **Screen Time API** (FamilyControls, ManagedSettings, DeviceActivity) can block apps, categories and websites
  reliably ("shields"), and schedule it.
- But Apple hides *what* you used: apps and sites are **opaque tokens**; usage reports render only inside an app
  extension that **can't send data off the phone**. So: no per-site/per-video tracking, no phone data in the partner
  report, no AI judging. A local summary on the phone is possible.
- Needs a paid developer account ($99/year) and Apple's **Family Controls** entitlement for distribution.
- Safari content blockers can block sites inside Safari only.
- Verdict: blocking and a private local summary are feasible; OnTask's "is this work?" model mostly isn't.

## Windows (explored only)

- Closest to the Mac. Front window: `GetForegroundWindow` / process name. Browser address: **UI Automation**
  (the accessibility API) reads the address bar of Chrome/Edge/Firefox. On-screen text: UI Automation or Windows OCR.
- Blocking: `C:\Windows\System32\drivers\etc\hosts`, edited by a small **Windows service** running as SYSTEM (the
  counterpart of the Mac's root daemon); apps can be closed by the tracker.
- Kotlin/Compose Desktop runs as-is; Kev runs via `uv` on Windows too (CPU).
- Work: port the Swift helper (front app, idle time, OCR, battery) and the bash daemon; installer (MSI) and signing.
- Verdict: the most feasible next platform.
