# OnTask — Design

OnTask is a personal productivity tool for Mac + phone. Open source, MIT licensed, public repo.

This file describes how OnTask works now. **Why** each choice was made, and what was rejected, is in
[DECISIONS.md](DECISIONS.md), and diagrams of the main flows are in [FLOWS.md](FLOWS.md). Change them together.

## Philosophy

- **Blocking with friction is the primary lever** — it's what has demonstrably worked.
- **Measurement** catches drift *in the moment* (not just after the fact) and feeds reports.
- **Accountability** is optimized for the user taking the initiative with their partner. The partner is never burdened with approving anything.
- **Resource-light**, especially when idle.
- **No personal data in the repo.** Phone numbers, emails, API keys, etc. live in settings, macOS Keychain, or Firebase Secret Manager.

## Blocking (Mac)

- **Mechanism:** `/etc/hosts`, written by a small root bash daemon (`daemon/`) that copies OnTask's staged hosts file.
  No tamper monitor; no browser extension for now.
- **The block list is blocked around the clock.** Work hours only change how hard unblocking is.
- **Work hours** are built in: weekly template + per-day exceptions, edited in a visual calendar.
  - Removing/shortening work hours → asks for a reason → partner is told. Adding hours never notifies.
- **Unblocking in work hours** first asks "for work?":
  - **For work:** purpose (e.g. "Acme customer: onboarding videos"), 15 min / 30 min / 1 h / 2 h / 3 h, no delay,
    partner always told.
  - **Not for work:** delay that grows per day (10 s, 30 s, 2 min, then 5 min; resets at 4 AM), 5 / 10 / 15 min,
    corner nudge at start and end (OK / I'm struggling → intervention), partner told every time.
  - The delay screen shows today's unblock count, today's plan, and the user's message (or a random alternative).
- **A browser tab on a blocked site goes to OnTask's "blocked" page** (checked every tick, front tab, Chrome-family
  browsers and Safari, via the same Automation permission that reads the address). The hosts file only stops new
  connections; Chrome keeps reusing a live one, so a tab open when an unblock ends would otherwise keep working.
- **Unblocking outside work hours:** instant, 5 (default), 10, 15, 30 min or 1 h, ends when work hours start, not
  counted, nobody told. Buttons say "Unblock", not "Open".
- **Ad and malware blocking:** StevenBlack's hosts list (fakenews-porn variant), refreshed at startup and daily,
  cached offline; one "Disable ad blocking" switch (Home and menu bar).
- **The root helper** is installed with one admin prompt (setup, or any launch where it's missing / outdated / not
  applying the file; at most once per launch). Healthy = current script, set up for this app, `/etc/hosts` matches the
  staged file. Hidden when healthy; a dismissible Home banner and a Permissions row when not. Developer test mode:
  `ONTASK_BLOCKING_TEST=1`.
- **Uninstall:** Settings → Uninstall OnTask removes the helper, OnTask's `/etc/hosts` sections and the login item
  (optionally the data), then trashes the app. If the app is simply deleted, the helper cleans up after 24 hours.
- Sites not on the block list are tracked and classified with no friction.

## Monitoring & classification (Mac)

### Layered pipeline
1. **Metadata** every few seconds: active app, window title, URL, input idle time. **Rules** (built-in + yours)
   classify most of it for free.
2. **AI** (a decision model, see below) for what no rule covers, from app / title / address, plus **on-screen text**
   (Apple Vision OCR) when Screen Recording is allowed. Answers below 60% count as unsure.
3. **Varies sites** are judged page by page (see *Pages on Varies sites*).

- Skip ticks when idle or when the screen hasn't changed. OCR text is never stored.
- At 20% battery, ask whether to switch to metadata-only.

### Levels & categories
- **Levels:** Focused / Mixed / Wasted, plus **Away** (excluded from ratios).
- **Categories are editable data** (rename, change level, add, delete → history re-sorted). Defaults:
  Work (Focused), Work adjacent (Mixed), Entertainment / News / Social (Wasted), Personal admin (Mixed), and
  **Varies** (judged per page).
- "Focused session" ≈ 25+ minutes without distraction events.

### Pages on Varies sites
- Default Varies sites: YouTube, Vimeo, Reddit, Facebook, LinkedIn, Medium, Substack. Only Varies sites are judged
  page by page.
- **Searches are judged search by search:** a results page on Google, Bing, DuckDuckGo, Brave Search or Yahoo is
  Varies whatever rule the search engine has, keyed by what you searched ("Google search: excalidraw or mermaid").
  A search nobody could place is Unsure, not time-wasting; the site you open from it is classified as usual.
- **Feeds** (YouTube home/Shorts, Facebook home/Watch/Reels, Reddit front/popular/all, Vimeo home, LinkedIn feed)
  count as Entertainment / News / Social outright.
- Otherwise, in order: remembered verdict → **source rule** (two matching answers of yours for the same subreddit,
  Facebook group, YouTube channel, Medium author or Substack publication) → AI on title, address and looked-up facts
  (oEmbed channel; page description) → AI with on-screen text → **"I can't tell whether this is work. What is it?"**
  (category buttons, Not now, Pause asking 15 min–1 day; at most once per page and every 2 minutes).
- Undecided pages are **Unsure** (counted as Mixed). Each page has its own category ("YouTube: Learning Java" →
  Work) and its own row on Home; its dropdown changes only that page. Catch-up judging of the last week (pages never judged when
  seen, e.g. AI off or not running) runs every 3 hours and never asks. **Each page, video or unknown app is judged by
  the AI once** (`ai_tried`): an answer, sure or not, is never asked for again; only a failed call is retried. Kev
  stops after 5 idle minutes.

### Away detection
- Signals: input idle time (**away after 2 minutes** with no keyboard or mouse; Settings), screen locked/asleep,
  media playing (watching a video with no input ≠ away), microphone or camera in use (a call or meeting ≠ away; the
  helper only asks whether a device is running, nothing is recorded).
- On return after >10 min away during work hours: one-tap prompt — *Meeting / Break / Off-computer work / On my phone*. Off-computer work counts as work; On my phone counts as distracted.

### Unclassified sites and apps
- A site or app no rule covers is **Unclassified**, so OnTask can't tell whether you're on task. AI gets the first
  try; if it can't place it (or AI is off), after 15 s there **"Which category is this?"** pops up, centered, naming
  the site (e.g. griswoldpflugerville.clearcareonline.com) or app, with every category including Varies.
- At that point (and for a page still Unsure) OnTask keeps **one screenshot of the front window** for *Help sort
  these* (see Daily rhythm). It stays on the device that took it, is never uploaded, and is deleted once the item is
  sorted, or after 7 days.
- The answer becomes a rule for that site or app and fixes its past activity. Not now asks again in 15 minutes;
  Pause asking (shared with the Varies question) holds it.

### Rules & learning
- Corrections from any dropdown (Mac or phone) become **local rules** (app / domain → category) and fix past history.
- Rules run **before** AI; AI only sees what no rule covers. Learned source rules for Varies sites are listed and
  removable in Settings → Categories. Past decisions are *not* fed to the model as examples (testing showed it hurt).

### AI providers
- **Kev** (open-source decision model, github.com/jaredpalmer/kev) **on this Mac, 0.8B by default** (4B optional).
  OnTask installs it into its data folder (git + `uv`), starts it on demand, stops it after 10 idle minutes.
- **Jev** (TypeSafe's cloud model, same System One API) as an option: API key in the Keychain, one-time notice that
  page titles and on-screen text are sent to TypeSafe.
- Both answer a choice question (your categories, described) with calibrated probabilities. Text only: no
  screenshots are sent (the ones kept for sorting are only shown to you). Diagnostics show calls, speed, failures with reasons, and Jev's estimated cost.
- The weekly model-recommendation Cloud Function (Gemini + search) targeted the old chat models and is no longer used
  by the Mac.

## Interventions (binge ladder)

Active during work hours, and on work days **from your first sit-down until work hours start**
(prompts only then: no partner messages, unblocking stays relaxed). Drifting = time in a Wasted category. **Only time
actually on it counts:** under a minute back at work (or away) pauses the count instead of ending the drift, and that
minute isn't added to it.

**Breaks are earned.** Focused minutes since your last break add up; time that isn't focused (Mixed or Wasted) is
taken off minute for minute once a stretch lasts a minute; 5+ min away, a break, or lunch starts it again. Earned:
nothing under 25 min focused, then 5 min per 25 (at most 10 under an hour, 15 after).

| Step | Trigger | Response |
|---|---|---|
| 1 (break earned) | 1 min of drifting | Centered, takes focus: **"Is this a break? You've focused 40 min, so you've got 5 minutes."** *Yes, 5-min break / I'm working / Back to work* (+ *Lunch*) |
| 1 (not earned) | 2 min of drifting, or 2nd unblock within 60 min | Centered, takes focus: **"It looks like you're drifting."** + "You've focused 12 min since your last break. 13 min more earns a 5-minute break." *Back to work / I'm working* (+ *Lunch*) |
| 2 | +3 min, or 3rd unblock within 60 min | Firmer centered window (answerable at once): drift time, your message, today's plan, break totals; *Back to work / It's actually work / Help me get started* |
| 3 | +5 min, or 4th unblock within 60 min | Work hours: offer to message the partner ("It's your call. You can edit the message."): *Send, and back to work / Not now, back to work / Help me get started*. Ignored, it's sent automatically (below). Before work hours: the step-2 window again |

- **During a break** there are no drift prompts. It ends quietly after 2 min of focused work, or with **"That's 5
  minutes. Back to work?"** (break totals: last hour and today) → *Back to work / 5 more minutes* (once). Still drifting a minute after a break or lunch → straight to step 2.
- **Lunch** (11:00–2:00, once a day, offered on step 1): no drift prompts; **"Is lunch over?" at 30 min** (*Back to work / 15
  more minutes*), over at 45 min. Working during lunch doesn't end it. Lunch time isn't work time in the totals.
- **Help me get started** (steps 2–3): today's plan with "pick the smallest next step and start it
  now", *Block <site> for 15 minutes*, and in work hours *Message my partner*.
- **Closing any prompt with the window's close button only hides it:** drift prompts and break cards come back after
  30 s, category questions after a minute, until answered.
- Break and lunch time on time-wasting sites still counts as Wasted (red).
- **"Back to work" checks, it doesn't grant grace:** up to **5 minutes** to switch, with a reminder every 30 s
  (*"Still on youtube.com. Back to work?"*) while still on time-wasting; still there after 5 minutes → step 2. Once
  switched (30 s off it), a **10-minute watch**: back on time-wasting, 30 s bring the next step. Same on the phone.
- **No waits anywhere on prompts** (drift windows, bedtime). The unblock delay in work hours stays.
- Drifting again **within the hour picks up where the last drift left
  off** (second drift at step 2, third at step 3); an hour clear resets it. "I'm working" resets; on a Varies site it
  marks that page or video as Work, elsewhere it creates a rule.
- **Step 3 ignored → your partner is told, never by surprise** (work hours, with a partner):
  - Step 3 open (or closed with its X) and unanswered while you keep drifting for **1 minute** (time away doesn't
    count) → a centered **countdown**: "Sending a message to your partner in 10…", one line saying why ("You've been
    on reddit.com for 12 min and didn't answer the check-in."), the message word for word, *I'm back to work* /
    *I'm actually on task*.
  - Closing it or *I'm back to work* only closes the window. When the 10 seconds are up: still on time-wasting →
    sent; back at work → a **10-minute watch**, where 30 s back on time-wasting sends it at once ("You went back to
    reddit.com after saying you'd get back to work."); 10 minutes on task → nothing is sent.
  - *Not now, back to work* on step 3 starts the usual 5-minute check; still there at the end → the same countdown
    ("…you're still on reddit.com after 5 minutes"), not step 3 again; after switching, the same 10-minute watch.
  - *I'm actually on task* lists this drift's sites, apps, pages and videos (the longest first; a page can be just
    this one or the whole site) and files the one you pick as Work, like "It's actually work". Nothing is sent.
  - The message comes from OnTask: "Dennis has been drifting on reddit.com for 12 min and didn't answer OnTask's
    check-in." / "…said they'd get back to work but is still on reddit.com 5 minutes later." / "…said they'd get back
    to work but drifted again on reddit.com."
  - Afterwards the "Still on X" reminders keep coming every 30 s until you switch, but **at most one message per
    drift** (yours from step 3 counts). No notice; it's listed in your own daily report ("Sent to your partner
    automatically: 10:42 AM (you didn't answer the check-in, reddit.com)").
  - Same on the phone (full screen; the screen being off doesn't count as drifting).
- Drifting alerts to the partner (binge, automatic or not, + work-hours unblocks) are capped **2 before noon and 2 after**, on the server;
  extras go into the daily report with their times.
- Messages end with a low-effort prompt (e.g. "No need to reply — but a 👍 helps").

## Daily rhythm

- The day is anchored to **first presence at the computer** on a work day, not clock time.
- **Today's plan** on arrival: one free-form note at the top of Home, starting empty (yesterday's "change for
  tomorrow" is shown as a hint).
- **Midday check-in** 30 minutes before the midpoint of actual working time (first sit-down → end of work hours); skipped if the day started < ~2h before end of work hours.
  - **Do this later ▾** at the top (10 min / 30 min / 1 h).
  1. **This morning:** one line: focused time, wasted time, longest focused stretch.
  2. **How's the day going?** *What did you get done?* and *What should you have gotten done?* (free text).
  3. **This afternoon's plan** (today's plan, editable): "Still the same as this morning?"
  4. **What's in the way?** Chips (Distracted / low energy, Interruptions/meetings, Unclear what to do, Stuck on a
     problem, Overwhelmed, Other), each with one suggestion (Distracted / low energy: *Start break*, 10 min). Shown
     only after a struggling morning: under 60% focused or 30+ min wasted, and the last hour before lunch (breaks and
     away left out) no better than the morning; otherwise a *Something in the way?* link.
  5. **Done**.
- **Help sort these** (after the midday and evening check-ins, in their own window, only when something is
  waiting): sites and apps with no category and pages or videos still Unsure, from both devices, today and
  yesterday, 30 s or more, the 10 with the most time. Each row: screenshot (click for full size; "📱 picture on your
  phone" / "💻 on your Mac" when the other device took it), name, title, time, category picker. *Skip for now* (or
  closing it) leaves them for the next check-in. Same on the phone after a check-in done there.
- **Every pop-up stays on top of other windows**, and only the pop-up comes forward (OnTask's main window stays
  where it was). Check-ins can be put off twice a day each (Do this later, or closing
  the window = 10 min); after that the window stays until answered.
- The daily report gets "Morning 64% focused · Afternoon 48%" (split at lunch, or noon) and the midday answers.
- **Morning check** (work days, with Today's plan): "Working 9:00–5:00 today?" Yes / start later or finish early / day off.
- **Evening check-in + reflection** (one prompt) **30 minutes before work hours end** (15–60, in Settings):
  1. Rate today: Rough / Meh / Okay / Good / Great
  2. What helped / what hurt (one line each)
  3. Optional change for tomorrow
  4. An optional note for the partner
  - Saving it sends nothing by itself; it goes out in the daily report.
- **Daily report**, every day: after work ends (6 PM on days off), once you've been inactive for 30 minutes, 8 PM at
  the latest. Decided on the server from the Mac's "last active" time. To each partner over their "Daily report"
  channel (titles per your setting), and to you by email (with titles; on by default). Includes totals, longest
  focused sessions, where the time went, the plan, the check-in or "No reflection today", alerts held back by the cap,
  and a link to the full day. Your own copy also lists messages OnTask sent your partner by itself.
  - If the user has already left the computer, wait for return or prompt on the phone in the evening.
- Check-ins: escalating phone reminders after work ends until done or the report goes. No "missed check-in" message.
- **Bedtime** (9:30 PM default): a reminder at bedtime if you're at the computer, then every 15 minutes while you're
  still at it, work included, firmer each time (the third and later full screen on the phone; always answerable at once). An unanswered one
  closes after 10 minutes away or at 5 AM. Your phone gets a push at bedtime.
- **Habit prompts** (e.g. morning prayer): user-defined (add/edit/remove), clock-scheduled, delivered to phone even when away from the computer.
- A work day with no computer use is reported as "no computer today", not a missed check-in.
- Weekly report compares self-rating vs. measured data.

## Reports & diagnostics

### Focus over the year (heat map)
At the bottom of Home (Mac and phone) and in the partner's view: 52 weeks of squares, weeks as columns starting
Sunday, shaded by **effective focus** = focused time (whole day, so evening work counts) × (0.5 + 0.5 × work-hours
focused %) × (0.8 + 0.2 × off-hours not-wasted %); a side with no time counts as 1. Shades: empty (no computer),
under 1h, 1–2.5h, 2.5–4h, 4–5.5h, 5.5h+. Hover/tap: "Nov 2, 2026 · 3h 10m focused (68%)"; click opens that day (Mac,
partner). Summary: "212h focused in the last year · 5-day streak" (streak = days with 2.5h+; days off never break
it; today doesn't until it's over). The Mac uploads one small `heat/year` document (shared like reports).

### Devices and sync
- **Each device uploads its own day report** (`deviceReports/{date}_{device}`, titled rows in `deviceReportItems`,
  heat numbers in `deviceHeat/{device}`) every 2 minutes while in use and at once on stepping away. Cloud Functions
  merge them into `reports/{date}`, `reportItems/{date}` and `heat/year`, which every view reads (devices can no
  longer write those directly). Phone stretches carry `d: "phone"` on the timeline.
- **Settings live in Firestore** and every device keeps a local copy: `sync/schedule` (work week, each day's
  exception, bedtime, report email, check-in timing), `sync/categories`, `sync/rules`, `sync/sourceRules`, and one
  document per verdict in `verdicts/`. Each pass does a three-way merge per item (last synced copy vs. here vs. there;
  each side keeps its own changes, deletions included); changes from elsewhere are applied as if made here (rules
  relabel history, work-hour changes re-mark today). `schedule/current` is still written by the Mac for the server.
- Platform notes for Android, iPhone and Windows: [PLATFORMS.md](PLATFORMS.md).

### Android app
- Native app (`android/`, sideloaded APK) with everything the web app did on the phone: Home (merged day, plan, heat
  map), Mac linking, partner and supporter views, settings, native push. Shares `shared/` Kotlin with the Mac
  (models, classifier, default rules incl. Android packages, page logic, heat score).
- **Tracking** via an Accessibility service: the app in front, browsers' address bars, the YouTube app's video
  title; every 5 s while the screen is on and unlocked (home screens and keyboards don't count). Classified with the
  shared rules, categories, source rules and verdicts from Firestore. Uploads its own day report and heat numbers
  every 2 minutes and on screen-off; raw activity stays on the phone.
- **Unknown apps and undecided pages** go to the `judgeSeen` Cloud Function (Jev, key in Secret Manager; one-time
  notice on the phone). Sure (60%+) → a rule or verdict for every device; otherwise a notification asks.
- The Mac's Home and heat map add the phone's day (rows marked 📱; on the timeline phone stretches share the one bar, 📱 on hover).
- **Timelines don't draw Away time** (Mac, phone, web): the gaps say it.
- **No overlaps: while the phone is in use, you're on the phone.** The phone counts only while its screen is on and
  unlocked; the Mac keeps counting for minutes after the last keypress. So Mac time overlapping phone use is cut from
  the timeline, the totals (work-hours totals lose the same share), "Where the time went" (from the row of that
  name, the rest in proportion) and the heat map (a negative `deviceHeat/overlap` entry per day), on the server and
  on the Mac's Home.
- **Reclassify from the timeline popup, either device's stretches:** each device uploads privately what every
  timeline name refers to (`refs` in its report copy: the app or site, or the page/video with its key); the popup's
  menu makes a rule (apps, sites) or a verdict (pages, videos) shared by both devices.
- **Updates without a cable:** `android/release.sh` publishes the APK and `version.json` with the web app
  (`/android/`, no-cache); the phone checks every 6 hours and in Settings → App, downloads, and Android's installer asks
  to confirm. Version = commit count.
- **Prompts on the phone** (work hours only; the work day still starts at the Mac): the same ladder (`shared/`).
  Step 1 is a heads-up notification you can't swipe away ("Is this a break?" after 25+ min focus, else "It looks like
  you're drifting"), with Yes break / I'm working / Back to work (goes to the home screen) / Lunch. Steps 2–3 are a
  full-screen OnTask screen (answerable at once; Back hides it for 30 s; Help me get started; message your partner
  in step 3). Started from the Accessibility service, so no "Display over other apps" permission is needed.
- **One break count:** the Mac publishes focus since the last break, any running break or lunch and break totals in
  `state/breaks`; a break or lunch started on the phone is written there and taken over by the Mac.
- **Check-ins on the device in use:** the Mac publishes the one that's due (`state/rhythm`); when you're on the
  phone and haven't used the Mac for 5 minutes, a notification opens it on the phone (every 20 minutes until done).
  The phone's answers go to `phoneCheckins/{date}` and the Mac saves them as if done there.
- **Bedtime on the phone:** while you're using it past bedtime, the Mac's reminder schedule (every 15 minutes,
  firmer; from the third a full screen, answerable at once). "Going to bed" just closes it.

### Work hours on Home
The Home subtitle shows today's work hours ("Work hours 9:00 AM–5:00 PM", or "Day off"); clicking it changes them
for today (or takes the day off; shorter hours need a reason, and the partner is told). Everything reads the current
schedule, so prompts, blocking, check-in times and the server follow at once, and today's earlier activity is
re-marked as in or out of work hours.

### Daily report
Focused / mixed / wasted / away totals; timeline strip colored by level, spanning only the time at the computer (away
time and stray night-time minutes don't widen it), hover/tap for what you did. **Glances under 15 s** (switching
windows) are drawn as part of the stretch around them, listed under "Also in this stretch" with their own category
(Mac, phone, partner page); they still count in the totals. On the Mac, click a stretch for a
pop-up with its time and everything in it, each with a category dropdown (same as Where the time went). Zoom with a
trackpad pinch, ⌘-scroll or − / + / Reset; move along the day with a two-finger sideways swipe or a drag; up/down
scrolling scrolls the page; focused sessions of 25+ min (count, longest); distraction events; regular vs. work unblocks; check-ins done/missed; priorities vs. where time went; worst hour; weekly heat map by hour.

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
- **What the partner sees:** configured by the user. Minimum = the messages sent to them; **default = full report**
  (timeline, sites). Page and video titles on Varies sites are folded into per-site totals unless "Allow partner to
  see individual website page titles" is on; your own phone reads them from `users/{uid}/reportItems` (owner-only).
  Raw activity log never.
- **Partner settings:** per message type (struggling alerts, unblocks during work hours, check-ins, work-hours changes,
  missed check-ins, work unblocks) choose push / text / email / off; quiet hours. Default push, text fallback if the PWA hasn't been opened in a while.
- **Partner reactions:** one-tap (👍 / Proud of you / Call me when you can / Take a walk) + optional short reply → notification on user's Mac and phone. No partner-initiated nudging, no unblock approvals.
- **Partner view:** the same "Today so far" view you see (bar, timeline, where the time went, longest focused
  sessions), live, with earlier days.

## Server (Firebase)

- Blaze plan (needed for scheduled functions; expected cost ≈ $0 at this scale).
- Firestore: aggregates, activity log, schedules, check-ins, events, partner prefs. Secured by Auth + security rules (no extra client-side encryption). OCR text never uploaded.
- **Cloud Functions send all outgoing messages** — push, TextBelt texts, email — and run scheduled pushes (habits, check-in reminders) so they fire while the Mac sleeps. The Mac just writes events.
- Secrets (TextBelt key, SMTP creds) in Firebase Secret Manager.
- Weekly model-recommendation function (legacy; see AI providers).

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
- **Packaging:** `./gradlew packageDmg` → `.dmg` with bundled JRE. Menu bar only (`LSUIElement`). Starts at login and
  reopens after quit/crash by default (LaunchAgent). Prompts to install its root helper. Uninstall from the top of
  Settings. Local builds are signed with "OnTask Local Signing" so macOS keeps permissions across rebuilds.
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
8. AI: Kev on this Mac (install) or Jev (API key)
9. Partner channels + test message

## Build phases

1. **Tracking-only** — desktop shell, SQLite, Swift helper (active window, idle), metadata classification + rules, daily report, diagnostics. Never touches the hosts file.
2. **Take over blocking** — daemon, escalating delay, expiring + purpose-bound unblocks, work-hours calendar, Focus session, first-run setup, installer.
3. **Intelligence** — OCR, text + vision models, provider options, intervention ladder, planning, evening reflection, battery prompt.
4. **Firebase + PWA** — auth, pairing, server-sent messages, scheduled pushes, partner view/settings/reactions, model recommendations, releases/update check.
5. **Later** — group UI, browser extension, Windows / native Android if needed.
