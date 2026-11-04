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
- **Midday asks "What did you get done?" and "What should have been done or improved?"** (the user's wording, instead of
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
- iPhone and Windows explored only (PLATFORMS.md).
- *Step 4 details:* phone steps 2–3 are an activity started by the Accessibility service (allowed without the
  "Display over other apps" permission). "Back to work" on the phone goes to the home screen (no phone blocking
  until v2). Focus since the last break is counted by the Mac only (phone focus is rare); phone breaks are shared.
  Phone check-in answers live in their own document so the Mac's check-in upload can never overwrite one.

## 2026-11-02 · One timeline bar; Android updates over the air
- **Mac and phone stretches share one full-height bar** (📱 in the hover/tap details). *Rejected:* shorter phone
  bars overlaid on the Mac's (confusing, looked bad).
- **Away chunks aren't drawn** on any timeline; they carried no information the gaps don't.
- **The Android app updates itself:** releases are published with the web app on Firebase Hosting (already deployed
  and authorized from this Mac), and the app installs them through Android's installer after you confirm.
  *Rejected:* GitHub releases (needs a GitHub token and `gh`); wireless adb (still needs the computer); Play Store
  (Accessibility policy review).
- **Overlapping Mac and phone time: the phone wins.** Attention is on one device at a time, and phone time is the
  stronger signal (screen on, unlocked, app in front) while the Mac counts until its idle threshold. The overlapped
  Mac time is cut from the timeline and the totals, so nothing counts twice. *Rejected:* one combined stretch listing
  both devices (hides which one you were really on). "Where the time went" and the heat map are trimmed the same way.

## 2026-11-02 · Shorter default unblock outside work hours
- Outside work hours an unblock defaults to **5 minutes**, with 5 / 10 / 15 / 30 minutes / 1 hour (was 30 minutes
  default, up to 2 hours): a short default makes "just a quick look" the easy choice. The wording is "Unblock", not
  "Open". The work-hours "just for a bit" unblock is unchanged (5 minutes default, 15 at most).

## 2026-11-02 · Blocked tabs are sent away
- **Why:** after a 5-minute Reddit unblock ended, Reddit kept working in Chrome for 15+ minutes: `/etc/hosts` was
  restored and the DNS cache flushed (as BlockMe did), but Chrome had 9 live connections to Reddit's servers and
  reuses them without looking the name up again.
- **Now** the front browser tab on a blocked site is sent to a local "blocked" page within seconds (AppleScript, same
  permission as reading the URL). *Rejected:* closing tabs (loses your place elsewhere in the window); cutting the
  connections with the firewall (pf isn't on by default; Reddit's servers are shared CDN addresses).

## 2026-11-03 · Away after 2 minutes
- Idle time before the Mac counts you as away is **2 minutes** (was 3; still adjustable in Settings). Shorter means
  less Mac time counted while you're actually on the phone or away, so the timeline and totals are closer to real.
- **Not away while the microphone or camera is in use**, so a call or meeting without typing isn't lost (and doesn't
  reset the break count) under the shorter threshold. Any app counts (Zoom, Meet, Teams, FaceTime, dictation).
  *Rejected:* a longer threshold for everyone; a list of meeting apps (misses browser calls).

## 2026-11-03 · Kev judges each thing once
- **Why:** Kev (≈2.6 GB) never shut down: the catch-up re-judged 5 old pages every 2 minutes, and pages it couldn't
  decide were retried after every restart (568 calls in a day).
- **Each page, video or unknown app is judged by the AI once** and remembered (`ai_tried`, sure or not); unsure
  means you're asked instead. Only a failed call (AI not running) is retried — retrying an answer gets the same answer.
- **The catch-up** (pages never judged when you saw them, so the day isn't left "Unsure") runs **every 3 hours**,
  remembered across restarts. **Kev stops after 5 idle minutes** (was 10).

## 2026-11-03 · No Focus sessions; "Back to work" checks; no waits (grilled)
- **Focus sessions removed.** In work hours they changed nothing (unblocking is already strict and prompts already
  run), and "Back to work: 25 min Focus" during a Focus session made no sense. Past records stay unused; it can come
  back if needed. *Rejected:* a stricter Pomodoro-style sprint; Focus only outside work hours.
- **No waits on prompts anywhere** (the firm drift window's 30 s, bedtime's 20 s): when OnTask is wrong and you're on
  task, a countdown just distracts. The unblock delay stays (the user likes that friction).
- **"Back to work" checks instead of granting 5 minutes of grace:** 5 minutes to switch with a reminder every 30 s
  while still on time-wasting, then the firmer window; after switching, a 10-minute watch where 30 s back on
  time-wasting bring the next step. Same on the phone (which also goes to the home screen).
- **A pop-up brings only itself forward.** Activating OnTask for a pop-up's keyboard focus made macOS raise the main
  window too, so two windows came up. When OnTask wasn't in front, its other windows go back behind the app you were
  using. *Rejected:* not taking focus at all (typing in a check-in or message would need a click first); closing the
  main window (loses where you were in it).

## 2026-11-03 · Telling the partner when step 3 is ignored; sorting after check-ins; searches; glances (grilled)

- **Drift counts only time actually on time-wasting.** A 5-second look at the LinkedIn feed during the watch after
  "Back to work" counted as a 30-second drift, because the clock ran from the first second; a minute back at work now
  pauses the count. *Rejected:* ending the drift on any switch (window-hopping would reset it).
- **Step 3 ignored → OnTask tells the partner, but never by surprise.** After 1 minute more of drifting with step 3
  unanswered (or closed with its X), a 10-second countdown says why and shows the message word for word. Still on
  time-wasting when it ends → sent; back at work → a 10-minute watch where 30 s back on it sends at once. "Not now,
  back to work" leads to the same countdown if you're still there after the 5-minute check, not to step 3 again.
  *Rejected:* sending silently (the user didn't want to hide that a message goes); a 2-minute wait (1 minute is
  enough to answer); counting "Not now" as an answer that sends nothing.
- **"I'm actually on task" on the countdown asks which site, app, page or video was work** (this drift's, longest
  first; a page can be just itself or the whole site), so the right thing is filed. Nothing is sent.
- **One message per drift, inside the existing caps** (2 a day from the Mac, 2 drift alerts per half-day on the
  server). Balancing: the partner should be alerted but not badgered. After a send, the 30-second reminders go on
  until you switch, and nothing more is sent. No notice afterwards; your own daily report lists it.
- **The message comes from OnTask, about you** ("Dennis has been drifting on reddit.com for 12 min and didn't answer
  OnTask's check-in."), never a half-typed draft of yours.
- **Search results pages are Varies, judged search by search** by what you typed. Searches can be work or not
  ("excalidraw or mermaid in markdown" vs "news"); a rule on the search engine's whole site was wrong either way
  (Brave Search was Entertainment). Your google.com rule still covers the rest of Google.
- **Glances under 15 s are drawn as part of the stretch around them** (Mac, phone and partner timelines), listed in
  its details with their own category; totals are unchanged. They're window-switching, not where the time went.
  *Rejected:* dropping them (they'd vanish from the details and the category menu).
- **"Help sort these" after the midday and evening check-ins**, as its own window right after Done (the check-in
  stays short): Unclassified sites and apps and Unsure pages, both devices, today and yesterday, 30 s+, top 10.
  Skip for now carries them to the next check-in. *Rejected:* a step inside the check-in (makes it feel long).
- **Screenshots come back, for you only:** one of the front window per unsorted item after 15 s, kept on the device
  that took it (the phone through its Accessibility service), deleted when sorted or after 7 days, never uploaded or
  sent to AI. *Rejected:* uploading them so both devices see every picture (your screens would leave your devices).
- **All documentation in `docs/`** (README stays at the root), with **`docs/FLOWS.md`**: Mermaid diagrams of the main
  flows, updated with every change like DESIGN.md and this file. *Rejected:* image diagrams (they go stale).

## 2026-11-03 · Diagnostics clean-up, AI accuracy, a bigger built-in list (grilled)
- **Up to 12 clock markers under a timeline** (hourly across a working day) instead of 6; the date arrows sit right
  next to the date.
- **Kev 0.8B only.** The 4B choice is gone (it needed about 10 GB of memory for somewhat fewer mistakes); its weights are
  deleted once if they were downloaded. *Rejected:* keeping the choice hidden in settings.
- **"Can't see window titles" while OnTask looks switched on:** the grant belongs to OnTask.app (macOS checks the
  responsible app, not the helper), and every build since Oct 30 is signed with the same local certificate, so
  rebuilds keep it. The leftover is an entry from an older, differently signed build; the fix is to reset it (the
  user runs the reset, or presses "Reset and ask again") and allow again. No code bug.
- **AI accuracy = agreement with your own answers and corrections**, per model per week. The old figure counted only
  corrections, so it was always 100% wrong. Labels you never touched don't count as right. *Rejected:* counting
  untouched labels as correct (flatters the number).
- **Diagnostics problems are red with a 🔴**, plus a ⚠️ line in the menu bar menu and on Home (when Home has no banner
  for it already). The activity log and the labeling rules open in their own searchable windows, like a browser's
  cookie list. Memory is one line for both programs; CPU is "right now" and "on average", each explained.
- **About 1,200 built-in sites**, from the Tranco top list (English-language, without ad/CDN/API hosts) plus
  well-known tools and services, in a compact Kotlin file shared by Mac and phone. Adult and gambling sites are
  Entertainment like any other; news stays Entertainment; existing built-in labels were kept. Your rules still win.
  Kev was used as a second opinion, but on a bare site name it says "Work adjacent" about 80% of the time, so only
  the 55 cases where it picked something else were reviewed again (4 changed). *Rejected:* a resource file (shared
  code is a source folder on both builds, and the list is only ~19 KB).

## 2026-11-03 · Other, Neutral, email as Varies, browser windows by title (grilled)

- **New built-in category Other** (Neutral) for moments between things: New Tab, notification alerts
  (UserNotificationCenter), the login window, Spotlight, the Dock, OnTask's Blocked page… It counts as what you were
  on just before for up to 2 minutes, then as Neutral, and it's never asked about. Seconds on a new tab aren't work
  or time-wasting; they belong to what's around them. *Rejected:* asking about them (nobody can answer "what is a new
  tab"); "In between" as a name; a separate level.
- **Mixed is renamed Neutral.** "Mixed" read as "partly work"; these are things that are neither.
- **Work adjacent stays, and counts as Focused** by default. Merging it into Work was decided and then undone: the
  user finds the distinction useful.
- **Email, calendars, documents and file storage are Varies**, judged per account or document: a personal inbox and
  a work inbox are both "Gmail". *Rejected:* filing them all as work.
- **Browser windows with no readable address are named from their title** ("Google Chrome – Gmail") and sorted with
  a rule for that browser's windows with that title. Lumping them all as "Google Chrome" made one answer file all of
  Chrome.
- **Help sort these:** sorted rows stay (dimmed, "Sorted as …") instead of disappearing; Skip for now bottom left,
  Done bottom right; one line, "OnTask couldn't tell what these were." Picking a category for a site without a dot
  ("newtab") now saves a site rule (it used to save an app rule that matched nothing).
- **No pop-up brings the main window forward,** not only drift prompts: whenever OnTask comes to the front through a
  pop-up, the main window goes back behind the app you were using.

## 2026-11-03 · Timeline glances unlisted; date arrows

- **Glances under 15 s aren't listed in a stretch's hover or details** (the "also: …" lines and "Also in this
  stretch" are gone). They were in passing; listing them was clutter nobody asked for. They still count in totals.
- **The date arrows on Home sit right next to the date**: the date button is sized to its text, so the arrows move a
  little when the date gets longer. *Rejected:* a fixed width (kept them still, but too far apart).

## 2026-11-03 · Menu-bar Quit reopens; today's plan in the evening check-in

- **Quit from the menu bar now brings OnTask back** (with "reopen" on), like Cmd-Q and the Dock already did.
  Compose's `application` exited with code 0 before OnTask's own exit code (75) was set, and launchd only restarts
  after a non-zero exit, so the menu-bar Quit never reopened it since that feature arrived (Oct 30).
- **The evening check-in shows today's plan** at the top, so you can reflect against what you meant to do.


## 2026-11-03 · Phone updates asked about when the app opens

- **No notification for a new phone version.** Opening OnTask checks for one and asks: Install or Later (Later asks
  again the next time it's opened). Settings → App still shows it. *Rejected:* the notification every 6 hours (an
  interruption for something that can wait until you open the app).
- **A phone check-in already done closes itself.** Opening the phone's check-in (from an old notification, or one
  done on the Mac meanwhile) now checks both devices' records and closes if that check-in is done.

## 2026-11-04 · Before work, prompts start when you start your day (grilled)

- **Why:** an "Are you on task?" prompt at 7:45 while reading the news, more than an hour before work. Since Nov 2
  prompts ran from the first sit-down.
- **Before work hours, prompts start only once the morning plan is done** ("Start my day", on either device). Until
  then the morning is yours; the plan prompt coming back is the nudge. Work hours prompt as before. *Rejected:* no
  prompts before work at all (loses the Nov 2 fix for mornings already started), a fixed window before work hours,
  exempting news.
- **A site unblocked outside work hours isn't drifting while that unblock lasts**, even after the day has started:
  you chose it.
- The phone still prompts in work hours only; before-work time still counts in totals and reports.
- **Step 1 now says "Are you on task?"** (was "It looks like you're drifting."), on the Mac and the phone: a
  question, matching the window's title.

## 2026-11-04 · The plan as tasks you check off

- **Today's plan is a list of tasks, not a text box.** The add field says "Add your most important tasks here";
  past 5 a note says "More than 5 is hard to finish — maybe save some for tomorrow." No hard limit. *Rejected:*
  "Pick 3–5 things for today" as the hint (too prescriptive up front).
- **Checked-off tasks stay, struck through,** everywhere: Home, check-ins, every nudge, the unblock screen, the report
  email and the partner view. Texts and push can't strike through, so there a finished task gets a ✓.
- **Reorder by dragging**, edit by clicking, × to remove.
- **Yesterday's plan** (the last day with one) shows on Home and in the morning plan, where unfinished tasks are one
  tap to carry over.
- **Shared between the Mac and the phone** through `plans/{date}`, newest change wins (simple, and two devices
  editing the same list at the same second is rare). The Mac checks every 10 s while you're at it.
- **The day starts with "Start my day"**, not with the first task added (adding tasks on Home before then doesn't
  turn on the before-work prompts).
- **Old plans were split into tasks by line and by sentence.**
- **Compact list, edited in place** (after seeing it on Home): rows close together with no hover shading; no
  standing text box, just *+ Add task*; an edit saves on Enter, a click elsewhere, or starting another task.
  **Yesterday's plan only in the morning plan,** not on Home.
- **The whole row shades lightly on hover** (handle to ×), not the text alone; *+ Add task* sits 20 px lower.
- **Tests never reach the real server:** `ontask.noCloud` (set for every test run) turns off the built-in project and
  the saved sign-in. A test app had synced its sample plan over the real one.
- **Editing puts the cursor where you click**, and the × (now in a circle) stays visible and usable while editing.
  Tasks are 5 px apart, with a little more room under "Today's plan" and less above *+ Add task*.
- **Date arrows: fixed again, closer.** The date button is a fixed 130 dp (just fits "Sep 30, 2026") with narrow
  arrows, so the arrows don't move between Today and other days and sit nearer than the old 140 dp version.
- **The new-task row lines up with the tasks and says "Add your task"** (was "Add your most important tasks
  here"); the gap under "Today's plan" is 5 px smaller.
- **The delete mark is a drawn red circle with an ×**, centered exactly (the text "×" in an outlined circle sat
  off-center and looked odd).

## 2026-11-04 · Your patterns (grilled)

- **A "Your patterns" card above the heat map** on Mac and phone, after Marinara's daily distribution: when in the day
  you're focused, wasting time, or on a screen at all.
- **Focused | Wasted | Screen time**, one at a time. Screen time was added so total use can be seen too.
- **Ranges are buttons, not a calendar/rolling switch:** This week · Last 7 days · This month · Last 30 days · 6
  months · Year. *Rejected:* one Calendar | Rolling switch (an extra control to understand).
- **Every day counts toward the average, including empty ones,** but the range starts no earlier than the first
  tracked day (so Year equals Week for now). Today counts only for slots already started.
- **Default slot 1 hr**; 15 min only on the Mac (96 bars are too thin on a phone).
- **Numbers stay calendar-based** (Today, This week, This month, Since the start), independent of the range buttons.
- **Both devices, from the merged day**, so overlaps count once. Just for you; partners don't see it.
- **Nudge windows fit their content** (height sized to what's in them) instead of a fixed height plus a guess for
  the plan: the guess was short and hid the reminder's *Back to work / I'm working* buttons.
- **FLOWS.md diagrams stay in Mermaid's default look.** A custom theme (fonts, pale fills, pill-shaped questions)
  looked good in a browser but broke in IntelliJ's dark Markdown preview (clipped text, light text on pale boxes), so
  it was taken back out. Kept: readable state names in the drift diagram. *Rejected:* `%%{init}%%` themes and
  classDef colors, which depend on the viewer's theme and fonts.
- **The main window stays back for every pop-up, including the category question** (which never takes focus, so
  clicking it made macOS raise and focus the main window, and the old guard took that as you opening it). Now: while a
  pop-up is up or just closed, OnTask coming forward sends the main window back; when the last pop-up closes, the
  front goes back to the app you were in. Left alone: opening OnTask yourself, Cmd-Tab with no pop-up, and pop-ups
  that appear while you're using the main window.
- **"I'm at lunch" on every drift prompt** at lunchtime (11–2, once a day), not just the first: the firmer window,
  step 3, the countdown and the reminders too, on Mac and phone. It starts lunch and nothing is sent. A step-3
  prompt at 12:30 had no way to say so.
- **The firmer "Time to come back" window has *I'm at lunch* too** (it was missed when lunch was added to every
  prompt; a 12:40 prompt had no way to say so).
- **"Still at lunch":** for 30 minutes after lunch runs out (45 min), every drift prompt and the "Lunch is over" card
  offer it: 15 more minutes, as often as needed (Mac).
- **Small pop-ups size themselves to their content** (Are you on task?, the reminder, Is this a break?, break over):
  the window measures what it draws and takes exactly that height, with the buttons at the bottom and no empty space.
  Guessed heights left the buttons cut off or a gap under them; buttons above the plan was rejected (they belong at
  the bottom). Checked on screen with `./gradlew previewWindows`.
