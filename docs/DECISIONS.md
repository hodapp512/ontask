# OnTask — Decision log

Why things are the way they are. Each entry records a decision, the reasons, and what was considered and rejected,
so a later change can start from the original intent instead of guessing. Newest topics last. DESIGN.md describes how
the system works now; this file explains how it got that way.

Format: **Decision** — why. *Rejected:* alternatives, and why not.

---

## 2026-10-30 · Foundations

- **Successor to BlockMe, not a fork.** BlockMe (a Java hosts-file blocker) proved that blocking with friction works;
  OnTask keeps that core and adds measurement, daily rhythm and an accountability partner. BlockMe's repo is left
  untouched.
- **Blocking via `/etc/hosts`, written by a small root bash daemon.** It's what worked in BlockMe, needs no browser
  extension, and covers every browser. *Rejected:* a browser extension (path-level rules) until clearly needed;
  a tamper monitor that restores the file (complexity for little gain against a determined user).
- **Kotlin + Compose Desktop, Gradle, no Spring; a Swift helper for macOS APIs.** Compose Multiplatform's compiler
  plugin and native packaging are only supported via Gradle. Window titles, OCR, idle time and battery need Apple
  APIs, so a long-lived Swift helper speaks JSON lines over stdio.
- **SQLite on the Mac; raw activity rolls up into hourly summaries.** Resource-light and private. The raw log never
  leaves the Mac. *Rejected:* a user-facing retention setting (removed later: nobody should need to think about it).
- **One PWA for phone and partner, on Firebase.** No app store, no fee, works on iPhone and Android with web push.
  Cloud Functions send every outgoing message (push, text, email) so reminders fire while the Mac sleeps.
- **No personal data in the public repo.** Contact details live in Firebase, API keys in the macOS Keychain or
  Firebase Secret Manager. The Firebase *web* config (project ID, web API key) is in the repo on purpose: it's public
  by design (the website ships it to every browser); data is protected by Auth and Firestore rules.
- **The partner never approves anything.** Accountability is the user's initiative; the partner gets messages and
  can react. *Rejected:* partner-approved unblocks, partner-initiated nudging.

## 2026-10-30 · Linking, Home and wording

- **Mac ↔ phone linking with a 5-character code that expires in 5 minutes.** Typing a short code beats pasting a
  long token. The Mac ships the Firebase project config so the user never sees project IDs or keys.
- **One-screen phone app** (your day, today's plan, partner, people you support). Tabs felt clumsy on Android.
- **One Home screen on the Mac** combining report and today; work hours, block list and other setup live in Settings.
  More actions are buttons (Unblock, Focus) instead of always-visible panels.
- **Plain words over jargon:** "Deep focus" → "Focused"; "deep-work sessions" → "Longest focused sessions"; evening
  rating uses Rough / Meh / Okay / Good / Great instead of 1–5; the partner message opens "Here is X's evening
  review."; Diagnostics explain each number.
- **Today's plan is one free-form note**, not a list of priorities. Later (2026-10-31) it moved to the top of Home as
  a one-line box that starts empty: pre-filling from yesterday made people keep stale plans.
- **Timelines zoom to the data and show what you did on hover (Mac) or tap (phone).** A 24-hour strip was mostly empty.
- **Focus session lengths 20 / 30 / 45 / 60 / 90 min.**

## 2026-10-30 · macOS integration

- **Menu bar only, no Dock icon** (`LSUIElement`). OnTask runs all day; a Dock icon is clutter. The window is brought
  forward explicitly since menu-bar apps aren't activated automatically.
- **Start at login and reopen after quit or crash, both on by default**, via a LaunchAgent with
  `KeepAlive{SuccessfulExit=false}`; Quit exits with code 75 when "reopen" is on. As in BlockMe: quitting shouldn't
  be an easy way out. Both can be turned off in Settings.
- **Builds are signed with a local "OnTask Local Signing" certificate** (scripts/make-signing-cert.sh). macOS ties
  Accessibility and Screen Recording grants to the signature; ad-hoc signing made every rebuild lose them.
- **Only macOS's own Accessibility prompt**, not that plus a System Settings window; the banner clears itself once
  access is granted, and "Fix it" resets a stale entry from an older build.

## 2026-10-30 · Categories

- **Categories are data, not an enum:** rename, change what each counts as, add your own, delete. Deleting removes
  its rules and re-sorts its history with the remaining rules; what nothing matches becomes Unclassified.
  *Rejected:* fixed categories (everyone's work is different).
- **A "Varies" category** for sites that can be work or not (YouTube first): judged per page or video instead of as
  a whole site.
- **Reclassify from any dropdown** (Home, phone, Diagnostics); a correction saves a rule and fixes past history.

## 2026-10-31 · Blocking model (grilled)

- **The block list is blocked 24/7, as in BlockMe; work hours only change how hard unblocking is.** Blocking only in
  work hours meant evenings had no protection at all, unlike BlockMe. *Rejected:* work-hours-only blocking.
- **During work hours or a Focus session, unblocking asks "for work?"**
  - *For work:* state a purpose, 15 min–3 h, no delay, partner always told (honest use shouldn't be punished, but is
    visible).
  - *Not for work:* growing delay (10 s, 30 s, 2 min, 5 min), 5 / 10 / 15 min, a corner nudge at start and end
    (OK / I'm struggling), and the partner is told every time, whether or not you agree. The friction is the point.
- **Outside work hours: instant, 5 min–2 h, ends when work hours start, not counted, nobody told.** Evenings are yours;
  the block list just stops mindless visits.
- **Merged BlockMe's active sites; YouTube left off the starter list** and judged per video instead (it's often work).
- **Ad and malware blocking from StevenBlack's list, refreshed by itself at startup and daily**; a single
  "Disable ad blocking" switch. *Rejected:* a "Refresh now" menu item (nothing for the user to manage).
- **Drifting alerts to the partner (binge + work-hours unblocks) are capped at 2 before noon and 2 after, on the
  server;** extras go into the evening review with their times. Check-ins, missed check-ins, work-hours changes and
  work-purpose unblocks are never capped. A partner flooded with alerts stops reading them.

## 2026-10-31 · Installing and removing the root helper (grilled)

- **Installing the helper is prompted, not buried in Settings.** At setup and on any launch where it's missing,
  outdated or not working, one explanation screen, then macOS's password prompt (at most once per launch). A
  dismissible Home banner returns on the next launch or after 4 hours; a Permissions row offers the same button.
  When it works, it's invisible (Diagnostics only).
- **A password is unavoidable.** Writing `/etc/hosts` needs root. *Rejected:* SMAppService (needs an Apple
  Developer ID, which a self-signed build doesn't have; and it wouldn't clean up hosts entries anyway).
- **No Off / Test / On switch.** Helper installed means blocking is on; test mode is a developer setting
  (`ONTASK_BLOCKING_TEST=1`).
- **"Healthy" means the helper is running the current script, set up for this app, and `/etc/hosts` matches what
  OnTask staged** — not merely that its files exist.
- **Uninstall leaves nothing behind:** Settings → Uninstall OnTask (top of Settings) removes the helper, OnTask's
  sections of `/etc/hosts` and the login item, optionally the data (off by default), then trashes the app. **If the
  app is just deleted, the helper cleans up by itself after 24 hours** — long enough that "delete the app" isn't a
  quick way around blocking, short enough that a real uninstall clears by the next day. *Rejected:* a root-owned
  app bundle (every update would need a password).
- *Rejected:* telling the partner when blocking is off; detecting or removing BlockMe's daemon.

## 2026-10-31 · Menu bar and Settings polish

- Menu bar: status line only in work hours / Focus; **Show OnTask** replaces Today's report / Settings / Open; Focus
  durations at the top level; Unblock sits with Add site and Ad blocking; plain "Quit OnTask".
- Settings subtitles are one short line, like a normal app. Home dropped the "Right now" card (it duplicated the menu
  bar and the summary).
- **The timeline covers only time at the computer:** away time doesn't widen it, and a session with under 5 minutes
  more than an hour from the rest (the Mac waking at night) is ignored.

## 2026-10-31 · Logo

- **A shield (like BlockMe's) bearing a clock's two hands at a right angle**, blue-to-teal with white hands and an
  orange centre; the menu bar draws the same shield with the hands cut out (template image). Drawn in code
  (desktop/icons/MakeIcons.java) so every size stays crisp. *Replaced:* a check + chevron mark.

## 2026-10-31 · AI: decision models instead of chat models (tested)

- **Why the change:** YouTube always showed as Mixed. Ollama's `qwen3-vl:4b` tag is a *thinking* model: it spent its
  whole answer budget reasoning and returned nothing (even with 1,024 tokens), so no video was ever judged. Chat
  models also give confidence numbers that mean nothing.
- **Tested on 30 real and realistic titles labelled by the user:**

  | Model | Correct | Confident ≥ 60% → right | Speed | Memory |
  |---|---|---|---|---|
  | Kev-4B | 22/30 | 9 of 10 | 0.37 s | 9.5 GB |
  | Qwen3-VL 8B Instruct | 20/30 | said 80–98% on everything, incl. all 10 misses | 0.85 s | ~6–7 GB |
  | Kev-0.8B | 20/30 | 14 of 18 | 0.08 s | 2.7 GB |

- **Kev-0.8B on this Mac is the default.** Its probabilities track whether it's right, which is what "ask when unsure"
  needs; it's nearly as accurate as 4B with a quarter of the memory, and its mistakes are cheap to fix (one dropdown,
  and source rules learn from it). Kev-4B and TypeSafe's **Jev** (cloud, API key in the Keychain, one-time privacy
  notice) are options. *Rejected:* Qwen (confidence useless); Jev as default (sends browsing to a third party).
- **Ask when the top answer is below 60%.** Chosen by the user from the test's threshold table.
- **Kev runs only when needed:** OnTask installs it (git + `uv`), starts it on demand and stops it after 10 idle
  minutes to free memory.
- **Text only; screenshots dropped.** Kev can't see images, and title + address (+ on-screen text) were enough.
  On-screen text is still read with Apple's own OCR when Screen Recording is allowed.
- **"Memory" lives in OnTask, not the prompt.** Giving Kev past decisions as examples made it *worse* (22 → 18–19 of
  30): it's trained to judge the text in front of it. So memory is (1) the same page reuses its verdict, and
  (2) **two matching answers of yours for the same source** (subreddit, Facebook group, YouTube channel, Medium
  author, Substack publication) make a rule, listed and removable in Settings → Categories. Fine-tuning Kev on your
  own labels is possible later, once there are a few hundred.
- **Look up what the title doesn't say:** a video's channel via YouTube/Vimeo oEmbed, and other pages' own
  description tags, fetched without your cookies. Facebook, LinkedIn and Instagram need sign-in, so they're skipped.
- *Removed with chat models:* the AI-written sentence in partner alerts (the plain template stays).

## 2026-10-31 · Pages on Varies sites (grilled)

- **Only sites in the Varies category are judged page by page;** moving a site out of Varies gives the whole site one
  category. Defaults: YouTube, Vimeo, Reddit, Facebook, LinkedIn, Medium, Substack.
- **Feeds always count as time-wasting**, with no AI and no question: YouTube home and Shorts, Facebook home / Watch /
  Reels, Reddit front page / r/popular / r/all, Vimeo home, LinkedIn feed. Groups, marketplace, a subreddit, a thread,
  a video or an article are judged.
- **A page gets one of your categories**, not just "work / not work": "YouTube: Learning Java" can be Work while
  "YouTube: Funny Cat Video" is Entertainment.
- **Order:** remembered verdict → source rule → Kev on title, address and looked-up facts → Kev with on-screen text →
  ask you. Judged again only when the title or address changes (no timer).
- **"I can't tell whether this is work. What is it?"** with your categories as buttons, Not now, and Pause asking
  (15 min / 30 min / 1 h / 2 h / 1 day; Resume in the menu bar). At most once per page and every 2 minutes, at any
  time of day. Undecided pages show as **Unsure** (counted as Mixed). Catch-up judging of earlier pages never asks.
- **Home shows each page as its own row** ("YouTube: Learning Java"), colored by its category; its dropdown changes
  just that page. (May get cluttered; revisit if so.)
- **Titles stay private by default:** the partner's report folds them into per-site totals; the titled rows go to a
  Firestore document only you can read, for your phone. A switch, "Allow partner to see individual website page
  titles", is off by default and only matters with the full report shared.

## 2026-10-31 · Default categories

- **Work, Work adjacent, Entertainment / News / Social, Personal admin** (plus Varies). Simple is better, and the app
  is about staying on work. Learning, Communication, Social, News and Shopping were retired; existing history in them
  is sorted again once on upgrade. Work adjacent counts as Mixed by default.

## 2026-10-31 · Pop-ups, API key, day planning

- **"I can't tell whether this is work" opens in the center of the screen you're working on** (the one with the
  pointer), as do the other pop-ups that need an answer. Disruptive is fine: a corner window was easy to miss.
  Small nudges (unblock start/end, the first drift prompt) stay in the corner.
- **The Firebase web API key is restricted to the five APIs OnTask uses** (Identity Toolkit, Token Service, Cloud
  Firestore, Firebase Installations, FCM Registration). The key is public by design, so the risk was billing: an
  unrestricted key works with every API enabled in the project (it had 25, including Firebase AI Logic, which can
  call Gemini). No application restriction: the Mac app calls the APIs directly, without a website referrer.
- **No day-off planning prompts.** Asking "any work today?" on days without work hours, and offering a work block when
  work is detected on a day off, were considered and dropped.

## 2026-10-31 · Daily report, morning check, partner view (grilled)

- **A daily report goes to the partner every day, automatically,** whether or not you did the evening check-in, so
  they always see how the time was spent. It includes the check-in (how it felt, what helped and hurt, tomorrow's
  change, an optional note) when there was one, otherwise "No reflection today". It replaces the "missed check-in"
  message and the separate evening-review message. It's the "Daily report" message type and is never capped.
- **When it goes:** after work hours end (6 PM on days off), once you've had **30 minutes without activity**, and by
  **8 PM** at the latest (or when work ends, if that's later). Activity = keyboard, mouse or a video in front of you
  (the same as Away detection). The Mac reports when you were last active; the server decides, so a closed laptop
  still gets its report out. *Rejected:* a fixed 6 PM / 7 PM time (mid-work on long days, or long after you stopped).
- **The evening check-in comes 30 minutes before work hours end** (Settings → Daily rhythm: 15–60 min), while you're
  still at the desk, so it can be part of the report. Saving it no longer sends anything by itself.
- **You get your own copy by email** (on by default, to your sign-in address), with page titles; the partner's copy
  follows their channel and your title setting. Both link to the full day in the phone app.
- **The partner sees the same "Today so far" view as you:** bar, timeline (tap for what you did), where the time went,
  longest focused sessions, distractions; live as your Mac syncs, with earlier days. Read-only.
- **Morning check on work days:** "Working 9:00 AM–5:00 PM today?" — Yes / Start later or finish early / Day off, in
  the same window as Today's plan. Shortening still asks for a reason and tells the partner.
- **Today's plan stays as it is** (AI context, the unblock delay screen, the midday check-in, the report).
  *Rejected:* a "plan vs. reality" comparison; dropping it.
- **Timeline hover shows the category** ("Entertainment / News / Social", "Unsure" for undecided pages) instead of
  Focused/Mixed/Wasted, names videos and pages ("youtube.com – Learning Java"), and puts the time on a smaller line
  below. Stretches are split by category (still colored by level).

## 2026-10-31 · Bedtime (built without a grilling; open to change)

- **A bedtime, 10:30 PM by default** (Settings → Daily rhythm; 8 PM–2 AM in 15-minute steps; on by default). The night
  runs from bedtime to 5 AM.
- **At bedtime, if you're at the computer, one reminder** in the center of the screen you're using. It doesn't come
  if you're already away; it comes when you sit down if you return during the night.
- **Then every 15 minutes while you're still active and not doing Work** ("clearly still surfing"). Working late
  isn't screen addiction, so Work (Focused) time doesn't trigger follow-ups. Each is firmer ("You're 45m past
  bedtime"); from the third, the window can't be closed for 20 seconds.
- **Buttons:** Going to bed (turns the display off: a nudge to actually stop), 10 more minutes, and from the second
  reminder on, I'm working (30 minutes). Going to bed doesn't silence the night: if you keep going, the next reminder
  still comes 15 minutes later.
- Each reminder is logged as a `bedtime_nudge` event on the Mac. Nothing goes to the partner or the phone yet.

## 2026-11-01 · Bedtime tweaks, dates, zoomable timeline

- **Default bedtime 9:30 PM.**
- **An unanswered bedtime reminder closes by itself** once you've been away for 10 minutes (you went to bed) or when
  the night ends at 5 AM, so the morning doesn't start with last night's pop-up.
- **Your phone gets a push at bedtime** too ("Time to put the screens away"), sent by the server from the bedtime in
  your synced schedule, once per night.
- **Dates read "Nov 1, 2026" everywhere** (with the weekday where it reads naturally: "Sunday, Nov 1, 2026"),
  including the report email's subject. Stored and synced dates stay ISO (yyyy-MM-dd).
- **Home follows the new day** after midnight unless you picked another date; it used to stay on the day OnTask was
  opened.
- **The timeline zooms:** on the Mac, scroll over it to zoom around the pointer, drag to move, − / + / Reset; on the
  phone, pinch or − / +, drag to move. Down to a 5-minute window; clock ticks go down to 5-minute steps.

## 2026-11-01 · Site rules cover the whole site

- **Moving a site to a category covers the whole site,** not the exact address of the page you had open: language,
  mobile and www prefixes are dropped ("en.m.wikipedia.org" → wikipedia.org), meaningful subdomains stay
  ("mail.google.com"). A mobile-page rule had left normal Wikipedia pages on the built-in Work adjacent rule. Existing
  rules were widened once.
- **Moving a site into Varies re-sorts its history page by page:** past pages become their own rows (Unsure until
  judged, feeds straight to time-wasting); moving it out folds them back into the site.
- **Any site gets a readable name** ("Wikipedia: Eric Gill"), not just the ones OnTask knows.

## 2026-11-01 · Bedtime push only while something is in use (reverted the same day)

*Reverted: the push goes out at bedtime every night, in use or not, as originally built. The phone app still records
when it comes to the front.*

- **The phone's bedtime push only goes out while you're using something:** the Mac (keyboard, mouse or a video) or
  the OnTask phone app, within the last 5 minutes. A phone lying on the nightstand stays quiet. If nothing is in use
  at bedtime, the push comes the moment you pick something up later that night (until 5 AM), once per night.
- A web app can't see general phone use, only when OnTask itself is open; the phone app now records that each time
  it comes to the front.
- **Phone timeline, reworked after real use:** a taller bar (40 px) to make pinching easy; gestures are tracked on a
  container that's never redrawn (redrawing the bar mid-gesture made pinch glitchy and broke taps after zooming); the
  description has its own line and wraps; edge clock labels stay on screen; the hint is just "Tap to see what you
  did · pinch to zoom".
- **Icon links carry a version (`?v=3`), and the manifest isn't cached,** so phones fetch a new logo instead of
  showing a cached one after it changes. Bump the version whenever the icons change.

## 2026-11-01 · Home trims

- **Screen time on Home** (Mac and phone): focused + mixed + wasted, i.e. everything but Away, next to "% focused".
- **Removed:** the "N focused sessions · N distractions · most wasted around…" line (not useful; Longest focused
  sessions stays), the timeline hints ("Tap to see…", "Hover to see…"), and "Labeled wrong? Change it here" above
  Where the time went: the dropdowns show it's editable.
- **Trackpad pinch zooms the Mac timeline,** through Apple's gesture events (com.apple.eawt.event, opened with
  --add-exports; Compose doesn't deliver pinches). Pinches go to the timeline under the pointer; elsewhere nothing.
- **The totals bar and its legend show screen time only** (Focused, Mixed, Wasted); Away was noise there. The
  timeline still shows away stretches as gaps.
- **"During work hours: …" is a tooltip** on the day's totals (Mac), not its own line.

## 2026-11-01 · Bedtime reminders while working

- **Follow-up bedtime reminders come every 15 minutes whatever you're doing, Work included.** The first version
  skipped Work time ("working late isn't screen addiction"), which left the user working at 10:15 PM with no reminder
  after a 9:41 one. "I'm working" still buys 30 minutes.
- **Clock times are computed as clock times on that date,** not "midnight + minutes": on the day clocks fall back
  (Nov 1, 2026) the latter was an hour off, which showed "105 minutes past bedtime" at 10:15 PM for a 9:30 bedtime
  and would have shifted the evening check-in, the next work start and the timeline's clock labels.

## 2026-11-01 · Build tools in Where the time went; short rows; phone clock times

- **"GradleWorkerMain" and "SourceLauncher" were OnTask's own builds**, not the user's activity: the test runner and
  the logo script briefly took the front on macOS. Tests and the logo script now run headless, the tracker counts any
  such Java tool's moment in front toward what was open before, and the existing entries (8½ minutes over three days)
  were removed.
- **Where the time went leaves out rows under 10 seconds** (a glance isn't where the time went); they still count in
  the totals.
- **The phone shows real clock times** for the timeline, its hover text and focused sessions: the report now carries
  the day's start and time zone, so clock-change days and partners in other time zones read right.

- **"Going to bed" just closes the bedtime reminder;** it no longer turns the display off. The reminders are what's
  wanted.

## 2026-11-02 · Firmer drift prompts (grilled)

- **Why:** a morning of YouTube got one small corner prompt the user never noticed, and 54 of the 59 wasted minutes
  were before 9:00, when prompts didn't run at all.
- **Prompts run from your first sit-down on a work day**, not only in work hours. Before work hours it's prompts only:
  no partner messages, relaxed unblocking; step 3 repeats the firmer window instead of offering a partner message.
- **The first prompt is centered on the screen you're using, larger, on top, and takes the focus;** it stays until
  answered.
- **Faster:** 2 min to the first prompt, +3 to the firmer window, +5 to step 3; 5 minutes of grace after Back to work.
- **Drifting again within an hour picks up where the last drift left off** (step 2, then step 3).
- **Every prompt offers "Block <site> for 15 minutes"** (the site you were drifting on, even YouTube), counted as Back
  to work; the partner isn't told; lifting it early goes through the usual unblock.
- Only Wasted time counts as drifting; Mixed and Unsure don't.
- *Rejected:* dimming every screen behind the prompt; pausing the video.

## 2026-11-02 · Ask about unclassified sites
- **Why:** a long stretch on griswoldpflugerville.clearcareonline.com stayed Unclassified, so OnTask couldn't tell
  whether the user was on task. Unclassified has to be resolved quickly, not left for a dropdown on Home.
- **After 15 s on a site or app no rule covers, once AI has had its try, OnTask asks "Which category is this?"**
  (centered, every category including Varies). The answer is saved as a rule and relabels past activity.
- Not now asks again in 15 minutes (not once per run, as for Varies pages, because Unclassified must not linger);
  Pause asking covers both questions.
- *Rejected:* waiting for the user to fix it on Home (they only noticed after the fact); treating Unclassified as
  Wasted (punishes new work tools).

## 2026-11-02 · Timeline gestures and slice details (Mac)
- **Two-finger sideways swipe moves along the day; up/down scrolls the page,** as in a browser. Vertical scrolling
  used to zoom in big 20% steps, so a slightly diagonal swipe zoomed and panned at once ("wonky"). Zoom is the pinch,
  ⌘/Ctrl-scroll (in small steps), or the buttons.
- **Clicking a stretch opens its details:** time, length, every app, site, page or video in it with its duration and
  a category dropdown. A drag of 4 px or more is a pan, not a click. Changing a category there is the same as on
  Where the time went (a rule, or just that page for Varies sites) and relabels history.
- *Rejected:* plain scroll-to-zoom kept for mouse wheels (can't tell a wheel from a trackpad, and it caused the bug).

## 2026-11-02 · Earned breaks and a drift prompt people answer (grilled)
- **Why:** "I'm struggling" on the first prompt would never be clicked, and the prompt had no honest answer for "I'm
  taking a break". Goals: prompts that get answered, a gentle path from time-wasting back to work, breaks that are
  fine after real work, and a push toward longer focused stretches.
- **Breaks are earned by focus:** 5 min per 25 min focused (10 max under an hour, 15 after); nothing under 25 min.
  Time that isn't focused subtracts minute for minute (dips under a minute ignored) instead of resetting the count;
  5+ min away resets it.
- **With a break earned, a minute on something Wasted asks "Is this a break?"**, so you know you're taking one and
  that it ends soon; it ends with "That's 5 minutes. Back to work?" (5 more once). Breaks aren't offered otherwise.
- **Without one, step 1 says "It looks like you're drifting"** (not "You've drifted"; firm, not soft) with how many
  more focused minutes earn a break, and no break button: just get to work.
- **"Back to work" starts a 25-minute Focus**; a small "just close" remains. "Yes, it's work" became "I'm working".
- **"I'm struggling" became "Help me get started" on steps 2–3**, holding the next-step picker, a 10-minute start,
  the block-the-site offer and the partner message. The block offer left step 1.
- **Lunch on demand** 11:00–2:00 once a day: asks at 30 min, 15 more allowed, 45 max; working during it doesn't end it.
- **Prompts closed with the window's close button come back** (30 s; category questions 1 min) until answered.
- Prompts after a break show break totals for the last hour and the day.
- *Rejected:* a two-step "Is this work? → No → break or drift?" (two clicks for one answer); offering break lengths;
  breaks under 25 min focused, even short ones; showing break time as neutral instead of red; a fixed lunch window
  in the schedule (would allow an hour of YouTube on days without lunch); requiring 2 hours of work before Lunch.

## 2026-11-02 · Midday check-in that helps, and pop-ups that stay (grilled)
- **Why:** the midday check-in only asked "Anything to adjust for the afternoon?", and its window fell behind other
  windows as soon as you clicked away; a menu-bar app can then be ignored all day.
- **Every pop-up is always on top.** Check-ins get Later (10 min / 30 min / 1 h); closing counts as 10 min; after two
  snoozes the window stays until answered.
- **Midday asks "What did you get done?" and "What should you have gotten done?"** (the user's wording, instead of
  "what's going well / not well"), then lets you revise the plan. Done items are ticked in the plan itself.
- **The morning's numbers come first** (focused %, wasted, longest stretch, top apps, timeline) so the answers rest on
  facts; the report gets one line, "Morning X% · Afternoon Y%", not the whole card.
- **"What's in the way?" chips only after a struggling morning** (under 60% focused or 30+ min wasted, not improving
  in the last hour before lunch with breaks and away left out, so a lunch break doesn't count against you); a link
  opens them otherwise. Each chip has one suggestion; Other is only recorded. Chips are recorded for patterns.
- **The card ends with "Done: start 50 min Focus"** so the afternoon starts from the check-in.
- The partner sees the midday answers in the daily report; no separate message.
- *Rejected:* an afternoon commitment line checked in the evening (not now); "Lunch first" on the card.
- *Revised after first use (same day):* the card was too long. Plan-line checkboxes → free text; the morning summary
  is one line (focused, wasted, longest stretch; no timeline, no top apps); Distracted and Low energy merged; "Too
  much on my plate" → Overwhelmed; Focus from the card is 30 min, not 50; "Do this later" moved to the top.
- *Timing:* the midday check-in comes **30 minutes before** the midpoint of the working day (e.g. 12:32 instead of
  1:02 for a 9:05 start and 5 PM end), so it lands before lunch and leaves more afternoon to act on it.
- *Plan section:* titled "This afternoon's plan", subtitle "Still the same as this morning?"; the "Add to this
  afternoon's plan" button was removed (not wanted).

## 2026-11-02 · No phone push when a check-in is snoozed; no duplicate pushes
- Snoozing a check-in on the Mac ("Do this later" or closing it) pushed "Don't forget your check-in." to the phone.
  You're at the Mac and the prompt returns there, so this was noise (and arrived after the check-in was done). Now
  it's only logged (`checkin_snoozed`). The phone still gets the evening check-in when you've left the Mac.
- Every push showed twice: Firebase displays notification pushes itself, and the service worker showed them again.
  The worker now only shows data-only messages.

## 2026-11-02 · Focus over the year (grilled)
- **A GitHub-style year of squares at the bottom of Home** (and on the phone and in the partner's view), like
  BlockMe's unblock calendar but for focus.
- **Score = focused time × (0.5 + 0.5 × work-hours focused %) × (0.8 + 0.2 × off-hours not-wasted %).** Time
  dominates (the user's wish); work-hours focus can halve it; evenings can take off up to 20%, but only for wasted
  time, so bills and email at night cost nothing. Focused time is the whole day's, so long days of real work count.
  A side with no time counts as 1 (not using the computer never costs).
- **Fixed shades** (under 1h, then 1.5-hour steps to 5.5h+) so the graph darkens as you improve; 7h and 12h look
  the same on purpose (no reward for overwork).
- Streak = consecutive days at 2.5h+, skipping days off.
- *Rejected:* shades relative to your own best days (hides progress); a plain focused % for evenings (would trim
  almost every day by ~20%); starting the grid at the first tracked day (the empty year is the year to fill).

## 2026-11-02 · Work hours on Home, editable
- Today's work hours sit in the Home subtitle and open today's editor on click; changes apply everywhere at once and
  re-mark today's earlier activity, so prompts, totals and reports agree with the new hours.

## 2026-11-02 · Android app, and the cloud as the hub (grilled)
- **A native Android app** (sideloaded APK, Android 10+) replaces the web app on the user's phone and adds tracking
  (usage access + an Accessibility service), then prompts; blocking comes in v2 (Accessibility overlay + local DNS
  VPN, since `/etc/hosts` needs root). Stay Focused keeps blocking meanwhile. The web app stays for partners.
- **Each device uploads its own day report; the server merges them.** Merging reports (totals, timelines, sites,
  heat numbers are all additive) is small and needs no device to be on, so a phone-only weekend is reported the same
  day. *Rejected:* the Mac as the hub (phone days wait for the Mac); merging raw activity on the server (would
  duplicate the report logic and move raw data off devices).
- **Settings in Firestore, local copies everywhere, three-way merge per item.** *Rejected:* the Mac owning settings.
- **Phone judging goes through a Cloud Function to Jev; the Mac keeps Kev** (local, free, works well).
- Uploads every 2 minutes in use and at once on lock/away, so the merged day is current all day.
- Breaks and check-ins are shared across devices; the work day still starts at the first sit-down on the Mac.
- iPhone and Windows explored only (docs/PLATFORMS.md).
- *Step 4 details:* phone steps 2–3 are an activity started by the Accessibility service (allowed without the
  "Display over other apps" permission). "Back to work" on the phone goes to the home screen (no phone blocking
  until v2). Focus since the last break is counted by the Mac only (phone focus is rare); phone breaks are shared.
  Phone check-in answers live in their own document so the Mac's check-in upload can never overwrite one.
