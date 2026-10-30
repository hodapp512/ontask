# OnTask — Design

OnTask is a personal productivity tool for Mac + phone. Open source, MIT licensed, public repo.

## Philosophy

- **Blocking with friction is the primary lever** — it's what has demonstrably worked.
- **Measurement** catches drift *in the moment* (not just after the fact) and feeds reports.
- **Accountability** is optimized for the user taking the initiative with their partner. The partner is never burdened with approving anything.
- **Resource-light**, especially when idle.
- **No personal data in the repo.** Phone numbers, emails, API keys, etc. live in settings, macOS Keychain, or Firebase Secret Manager.

## Blocking (Mac)

- **Mechanism:** `/etc/hosts`, written by a small root bash daemon. Tamper monitor/restore is dropped. A browser extension (path-level rules) is deferred until clearly needed.
- **Work hours = strict mode by default.** No opt-in focus mode. An on-demand **Focus session** applies work-hours rules for a chosen duration outside work hours, logged as a deep-work attempt.
- **Work hours** are built in: weekly template + per-day exceptions, edited via a visual calendar UI. Configured at first run, editable anytime in settings.
  - Removing/shortening work hours (any time) → asks for a reason → partner is notified with the reason and its meaning.
  - Adding hours never notifies.
- **Regular unblocks**
  - Delay always applies (no "it's for work" bypass). Escalates per day: 1st 10s, 2nd 30s, 3rd 2m, 4th+ 5m. Resets at 4am.
  - Unblocks auto-expire: choose 5 / 15 / 30 minutes up front.
  - Delay screen shows today's unblock count, today's priorities, and either the user's pre-written static message (default) or a random alternative (setting).
  - All numbers configurable.
- **Purpose-bound work unblocks**
  - User states site, purpose (e.g. "Acme customer: onboarding videos"), and duration (up to 3h).
  - No delay. Partner is always notified.
  - Monitoring checks screen content against the stated purpose; drift triggers the intervention ladder and can end the unblock early.
  - Reported separately from regular unblocks.
- Non-blocklisted sites (e.g. a customer's domain) are tracked and classified with no friction.

## Monitoring & classification (Mac)

### Layered pipeline
1. **Metadata** every few seconds: active app, window title, URL, input idle time. Near-free.
2. **OCR** every ~30s via Apple Vision on captured screens (all monitors) → text + metadata to a **text model**.
3. **Vision model fallback** when 1–2 are ambiguous (included in the first version, not deferred).

- Skip ticks when idle or when the screen hasn't changed.
- Screenshots and OCR text are deleted immediately after classification.
- At 20% battery, ask the user whether to switch to metadata-only (user decides in the moment).

### Levels & categories
- **Levels:** Deep focus / Mixed / Wasted, plus **Away** (excluded from ratios).
- **Categories:** Core work, Work support (email, admin), Learning, Communication, Entertainment, Social, News, Shopping, Personal admin.
- **Goal-alignment score** 1–3 against today's priorities.
- Context comes from the calendar: anything goes outside work hours/Focus sessions; strict inside.
- "Deep work session" ≈ 25+ minutes without distraction events.

### Away detection
- Signals: input idle time, screen locked/asleep, media playing (watching a video with no input ≠ away).
- On return after >10 min away during work hours: one-tap prompt — *Meeting / Break / Off-computer work / On my phone*. Off-computer work counts as work; On my phone counts as distracted.

### Rules & learning
- Every in-the-moment answer ("Yes, it's work", category picks) becomes a **local rule** (app / domain / title keyword → category).
- Rules are evaluated **before** any AI call; AI only sees what no rule covers. Recent labels are included as few-shot examples.
- Rules are reviewable/editable in diagnostics.

### AI providers
- Common interface over an OpenAI-compatible API.
- Provider + model chosen **separately for the text tier and the vision tier**, changeable anytime.
- Options: **Local** — Ollama if detected, otherwise a bundled **llama.cpp `llama-server`** started on demand; **Claude**, **OpenAI**, **Gemini**.
- Default: local for both tiers.
- API keys in macOS Keychain. Choosing a cloud provider shows a one-time notice that OCR text/screenshots will be sent to it. Diagnostics show calls/day and estimated cost.
- Local models: downloaded on first run (not bundled in the `.dmg`); listed in settings and deletable.
- Model options: **Qwen3-VL 4B (default)**, Gemma 3 4B, Qwen2.5-VL 7B (all 4-bit). One multimodal model serves both tiers locally so only one model is loaded.
- Local model unloaded shortly after last use (Ollama `keep_alive` / stop llama-server).

### Model recommendations
- A weekly scheduled Cloud Function queries **Gemini with Google Search grounding**, given the current default model and hardware tier, for substantially better local models. Structured output: candidate IDs (Ollama / Hugging Face), size, rationale, sources.
- The function **validates that each model ID exists** before publishing; hallucinated IDs are dropped.
- Result written to Firestore `modelRecommendation/latest`. The app reads it weekly — no local querying.
- If better than current: notify → user approves → download → one-week trial; diagnostics compare speed and the user-correction rate vs. the previous model → keep or roll back.

## Interventions (binge ladder)

Active during work hours and Focus sessions. Thresholds are defaults, configurable.

| Step | Trigger | Response |
|---|---|---|
| 1 | 3 min continuous "wasted", or 2nd unblock within 60 min | Gentle prompt: *Yes, it's work / Back to work / I'm struggling* |
| 2 | +5 min drift, or 3rd unblock within 60 min | Normal-sized window, **not dismissable for 30s** (adjustable 30s–3min). Shows drift time, priorities, the user's message, unblock count. Buttons: *Back to work* (10-min grace) / *It's actually work* (labels + creates rule) / *I'm struggling* (→ step 3) |
| 3 | +10 min drift, or 4th unblock within 60 min | Offer to message partner. User confirms; AI fills in a one-line context on the existing message template |

- "Yes, it's work" at any step resets the ladder and creates a rule.
- Partner binge alerts are **rate-limited to 1–2/day**; extras are queued into the next check-in.
- Messages end with a low-effort prompt (e.g. "No need to reply — but a 👍 helps") and say what the user is doing about it.

## Daily rhythm

- The day is anchored to **first presence at the computer** on a work day, not clock time.
- **Planning** on arrival: pre-filled with yesterday's priorities and "change for tomorrow"; linked to long-term goals/projects; edit only what changed. Shorter version if arriving after midday.
- **Midday check-in** at the midpoint of actual working time; skipped if the day started < ~2h before end of work hours.
- **Evening check-in + reflection** (one prompt) at end of work hours:
  1. Rate today 1–5
  2. What helped / what hurt (one line each)
  3. Optional change for tomorrow
  4. Review the drafted check-in (stats + trend, e.g. "better than yesterday afternoon") → send via the partner's configured channels
  - If the user has already left the computer, wait for return or prompt on the phone in the evening.
- Check-ins: must be marked done; escalating reminders on Mac + phone if not.
- **Habit prompts** (e.g. morning prayer): user-defined (add/edit/remove), clock-scheduled, delivered to phone even when away from the computer.
- A work day with no computer use is reported as "no computer today", not a missed check-in.
- Weekly report compares self-rating vs. measured data.

## Reports & diagnostics

### Daily report
Deep / mixed / wasted / away totals; timeline strip colored by level; deep-work sessions (count, longest); distraction events; regular vs. work unblocks; check-ins done/missed; priorities vs. where time went; worst hour; weekly heat map by hour.

### Diagnostics
Activity log with filters; classification source per tick (rule / metadata / OCR / vision / user label) as %; avg processing time per tick and per model; ticks skipped (idle/unchanged); CPU and memory; classifier-vs-user-label disagreement rate (the key accuracy metric); rules list; cloud API calls and estimated cost; sync and notification delivery status.

**All computed on demand** via SQL when the screen is opened — nothing runs live in the background.

## Resource budget & storage

- App RSS ≲ 250 MB idle; < 1% average CPU outside classification ticks.
- **SQLite** on the Mac, batched writes. Only today's running totals held in memory.
- Retention: raw ticks 90 days → rolled into per-hour summaries kept forever. OCR text never persisted.

## Phone & partner (PWA)

- **One PWA** for both roles, on iPhone and Android, installed via "Add to Home Screen" with web push. Hosted on Firebase Hosting. No app store, no fee. A native Android app only if web push proves unreliable.
- **No phone tracking or blocking.** Phone role for the user: redundant notifications, reminders when away from the Mac, habit prompts, check-ins, reports.
- **Auth:** Firebase Auth (Google sign-in or passwordless email link).
- **Pairing:** invite link / QR generated in OnTask; partner opens, signs in, paired. Pairing can be skipped at setup.
- **Data model** supports groups and many-to-many relationships (anyone can be a tracked user, a partner, or both) from day one; v1 UI is one user ↔ one partner.
- **What the partner sees:** configured by the user. Minimum = the messages sent to them; **default = full report** (timeline, sites). Raw activity log never.
- **Partner settings:** per message type (binge alert, check-in, work-hours change, missed check-in, work unblock) choose push / text / email / off; quiet hours. Default push, text fallback if the PWA hasn't been opened in a while.
- **Partner reactions:** one-tap (👍 / Proud of you / Call me when you can / Take a walk) + optional short reply → notification on user's Mac and phone. No partner-initiated nudging, no unblock approvals.
- **Missed check-in:** partner notified after a grace period (~2h) by default; user can change to summary-only or never.

## Server (Firebase)

- Blaze plan (needed for scheduled functions; expected cost ≈ $0 at this scale).
- Firestore: aggregates, activity log, schedules, check-ins, events, partner prefs. Secured by Auth + security rules (no extra client-side encryption). OCR text never uploaded.
- **Cloud Functions send all outgoing messages** — push, TextBelt texts, email — and run scheduled pushes (habits, check-in reminders) so they fire while the Mac sleeps. The Mac just writes events.
- Secrets (TextBelt key, SMTP creds) in Firebase Secret Manager.
- Weekly model-recommendation function (see above).

## Stack & repo layout

```
ontask/
  desktop/    Kotlin + Compose Multiplatform (desktop JVM), Gradle Kotlin DSL, no Spring
  helper/     Swift helper: ScreenCaptureKit, Vision OCR, active window, idle; JSON over stdin/stdout, long-lived
  daemon/     root bash hosts daemon
  web/        PWA (TypeScript)
  functions/  Firebase Cloud Functions (TypeScript)
  firebase/   Firestore rules, indexes, hosting config
```

- Gradle, not Maven: Compose Multiplatform's compiler plugin, desktop dependencies, resources and native packaging are officially supported only via Gradle.
- Platform-specific code (hosts, helper, notifications) behind interfaces so a Windows port is possible later.
- **Packaging:** `./gradlew packageDmg` → `.dmg` with bundled JRE. On first launch the app installs its root helper (one admin prompt) and registers a login item. Uninstall from settings.
- **Releases:** GitHub Action on a version tag builds the `.dmg` on a macOS runner and publishes to GitHub Releases; the app checks Releases and offers updates.

## First-run setup

Sensible defaults; every step skippable and finishable later in settings except role and permissions.

1. Role
2. Work-hours weekly template
3. Block list (built-in defaults, optional import from a file)
4. Habit prompts (e.g. morning prayer + time)
5. Planning / midday / evening times
6. Pair with partner (skippable)
7. macOS permissions: Screen Recording, Accessibility
8. AI provider(s) & model download (Ollama detected or built-in)
9. Partner channels + test message

## Build phases

1. **Tracking-only** — desktop shell, SQLite, Swift helper (active window, idle), metadata classification + rules, daily report, diagnostics. Never touches the hosts file.
2. **Take over blocking** — daemon, escalating delay, expiring + purpose-bound unblocks, work-hours calendar, Focus session, first-run setup, installer.
3. **Intelligence** — OCR, text + vision models, provider options, intervention ladder, planning, evening reflection, battery prompt.
4. **Firebase + PWA** — auth, pairing, server-sent messages, scheduled pushes, partner view/settings/reactions, model recommendations, releases/update check.
5. **Later** — group UI, browser extension, Windows / native Android if needed.
