# OnTask — Flows

Diagrams of how OnTask decides and acts. [DESIGN.md](DESIGN.md) has the full rules and
[DECISIONS.md](DECISIONS.md) the reasons. **Keep these current:** a change to one of these flows updates its diagram in
the same commit. GitHub draws the Mermaid blocks; any Mermaid viewer works offline.

1. [How a category is decided](#1-how-a-category-is-decided)
2. [Drift prompts (the ladder)](#2-drift-prompts-the-ladder)
3. [Telling your partner when step 3 is ignored](#3-telling-your-partner-when-step-3-is-ignored)
4. [The daily rhythm](#4-the-daily-rhythm)
5. [Unblocking a site](#5-unblocking-a-site)
6. [Partner messages](#6-partner-messages)
7. [Mac and phone sync](#7-mac-and-phone-sync)

---

## 1. How a category is decided

Every tick (a few seconds on the Mac, 5 s on the phone) the app, window title and address in front are classified.

```mermaid
flowchart TD
    T[Tick: app, title, address] --> AW{Away?<br/>idle 2 min, locked, asleep,<br/>not if media or mic/camera}
    AW -- yes --> AWAY[Away: left out of ratios]
    AW -- no --> S{Search results page?<br/>Google, Bing, DuckDuckGo,<br/>Brave, Yahoo}
    S -- yes --> V
    S -- no --> R{A rule matches?<br/>yours first, then built-in,<br/>title, then site, then app}
    R -- "no" --> U[Unclassified]
    R -- "yes, Varies site" --> V[Varies: judge this page]
    R -- "yes, a category" --> C[That category's level:<br/>Focused, Neutral or Wasted]
    R -- "yes, Other<br/>new tab, notification, Spotlight…" --> O{On it under 2 min?}
    O -- yes --> PREV[Counts as what came just before]
    O -- no --> NEU[Neutral]

    V --> F{A feed?<br/>YouTube home, Shorts,<br/>Reddit front, LinkedIn feed…}
    F -- yes --> W[Entertainment: Wasted]
    F -- no --> VD{Verdict remembered<br/>for this page?}
    VD -- yes --> C
    VD -- no --> SR{Source rule?<br/>2 matching answers for its<br/>subreddit, channel, group…}
    SR -- yes --> C
    SR -- no --> AI1{AI sure, 60%+?<br/>Kev on the Mac, Jev for the phone,<br/>once per page}
    AI1 -- yes --> C
    AI1 -- no --> UN[Unsure, counted as Neutral]

    U --> AI2{AI sure about<br/>the site or app?}
    AI2 -- yes --> C
    AI2 -- no --> ASK

    UN --> ASK[After 15 s: screenshot of the front window,<br/>then ask: Which category is this? / Is this work?]
    ASK -- answered --> RULE[Rule or verdict, shared by both devices,<br/>past activity relabeled, screenshot deleted]
    ASK -- "Not now / closed" --> LATER[Asked again later, and listed in<br/>Help sort these after the next check-in]
    RULE --> C
```

- Timings: unclassified question after 15 s; at most once per page and every 2 minutes; Not now = 15 min; closing
  with the X = 1 min. Screenshots are kept on the device that took them, at most 7 days.
- Changing a category anywhere (timeline pop-up, Where the time went, Help sort these) makes the same rule or verdict.
- A browser window with no readable address is named from its title ("Google Chrome – Gmail"); its rule covers only
  that browser's windows with that title.

---

## 2. Drift prompts (the ladder)

In work hours (and on work days, before work hours, once the morning plan is done). A site you unblocked
outside work hours isn't drifting while the unblock lasts. Drift = time actually on a Wasted category: a minute back
at work or away pauses it; a minute off ends it.

```mermaid
stateDiagram-v2
    [*] --> Working
    Working --> Step1: drift 2 min (1 min with a break earned)<br/>or 2nd unblock within the hour
    Step1 --> Step2: +3 min, or 3rd unblock
    Step2 --> Step3: +5 min, or 4th unblock
    Step1 --> Break: Yes, 5-min break / Lunch
    Step1 --> Working: I'm working (rule or verdict)
    Step2 --> Working: It's actually work
    Step1 --> Check: Back to work
    Step2 --> Check: Back to work
    Step3 --> Check: Not now / Send, back to work
    Step3 --> AutoSend: ignored (see flow 3)

    state Check {
        [*] --> Switching
        Switching --> Switching: still on it, a reminder every 30 s
        Switching --> Watch: 30 s off time-wasting
    }
    Check --> Step2: still on it after 5 min
    Watch --> NextStep: 30 s back on time-wasting within 10 min
    Watch --> Working: 10 min on task
    NextStep --> Step2
    NextStep --> Step3

    Break --> Working: ends quietly after 2 min of focus
    Break --> BreakOver: time's up, "That's 5 minutes. Back to work?"
    BreakOver --> Check: Back to work
    BreakOver --> Step2: still drifting a minute later
```

- **Earned breaks:** 5 min per 25 focused (at most 10 under an hour, 15 after); unfocused time is taken off minute
  for minute. **Lunch:** 11–2, once a day, "Is lunch over?" at 30 min, 15 more allowed, 45 max.
- **Picking up where it left off:** a new drift within the hour starts one step higher (second at step 2, third at
  step 3).
- Closing a prompt with its X brings it back after 30 s. Nothing on a prompt makes you wait.
- Before work hours, step 3 is the step-2 window again (no partner). On the phone: step 1 is a notification, steps
  2–3 full screen; Back to work goes to the home screen.

---

## 3. Telling your partner when step 3 is ignored

Work hours, with a partner. At most one message per drift, and the server's cap applies (flow 6).

```mermaid
flowchart TD
    S3[Step 3: Want to let your partner know?] --> A{What do you do?}
    A -- "Send, and back to work" --> SENT_SELF[Your message goes.<br/>Nothing more for this drift] --> CHK
    A -- "Not now, back to work" --> CHK[Back-to-work check:<br/>5 min to switch, reminder every 30 s]
    A -- "nothing, or the X,<br/>1 min more of drifting" --> CD1[Countdown, 10 s<br/>'You've been on reddit.com for 12 min<br/>and didn't answer the check-in.']

    CHK -- "still on it after 5 min" --> CD2[Countdown, 10 s<br/>'…still on reddit.com after 5 minutes.']
    CHK -- "switched (and Not now was pressed)" --> WATCH
    CHK -- "switched (after your own send)" --> DONE[Done]

    CD1 --> END{When the 10 s are up}
    CD2 --> END
    CD1 -. "I'm actually on task" .-> PICK[Pick what was work:<br/>a page or the whole site] --> WORK[Filed as Work, nothing sent]
    CD2 -. "I'm actually on task" .-> PICK

    END -- "still on time-wasting" --> SEND
    END -- "back at work" --> WATCH[10-minute watch]
    WATCH -- "30 s back on time-wasting" --> SEND
    WATCH -- "10 min on task" --> DONE

    SEND[OnTask's message goes to your partner,<br/>listed in your own daily report] --> REM[Reminders every 30 s until you switch,<br/>nothing more is sent]
```

- *I'm back to work* and the X only close the countdown window; what you do next decides.
- On the phone the countdown is full screen, and the screen being off isn't drifting.

---

## 4. The daily rhythm

Anchored to your first sit-down on a work day. Check-ins appear on the device in use (the phone after 5 minutes
away from the Mac).

```mermaid
flowchart TD
    D[First sit-down on a work day] --> P[Today's plan, tasks to check off,<br/>with yesterday's to carry over +<br/>Working 9:00–5:00 today?]
    P --> M[Midday check-in<br/>30 min before the midpoint]
    M --> MS{Done or later?}
    MS -- "Do this later<br/>(twice a day at most)" --> M
    MS -- Done --> H1{Anything unsorted?}
    H1 -- yes --> SORT1[Help sort these:<br/>screenshot, name, time, category] --> E
    H1 -- no --> E
    E[Evening check-in<br/>30 min before work ends] --> ES{Done or later?}
    ES -- later --> E
    ES -- Done --> H2{Anything unsorted?}
    H2 -- yes --> SORT2[Help sort these] --> R
    H2 -- no --> R
    R[Daily report<br/>30 min inactive after work, or 8 PM] --> PART[To each partner]
    R --> ME[To you by email:<br/>titles + automatic messages]
    R --> B[Bedtime 9:30 PM:<br/>reminder every 15 min while still on]
```

- Skipped sorting comes back in the next check-in (today's and yesterday's items).
- The plan's tasks can be checked off all day: Home, both check-ins and every nudge. Start my day starts the day.
- The midday check-in's "What's in the way?" chips appear only after a struggling morning.

---

## 5. Unblocking a site

The block list is blocked around the clock; work hours only change how hard it is to unblock.

```mermaid
flowchart TD
    U[Unblock a site] --> WH{In work hours?}
    WH -- no --> FREE[Instant: 5, 10, 15, 30 min or 1 h,<br/>ends when work starts, nobody told]
    WH -- yes --> FW{For work?}
    FW -- yes --> PUR[Purpose + 15 min to 3 h, no delay] --> TOLD1[Partner told:<br/>work unblock with the purpose]
    FW -- no --> DELAY[Delay that grows each time today:<br/>10 s, 30 s, 2 min, then 5 min]
    DELAY --> LEN[5, 10 or 15 min] --> TOLD2[Partner told:<br/>unblock alert, capped]
    LEN --> NUDGE[Corner nudge at start and end:<br/>OK / I'm struggling]
    LEN --> LADDER[2nd–4th unblock within the hour<br/>escalates the ladder]
    FREE --> END[Time's up: blocked again,<br/>an open tab goes to the blocked page]
    PUR --> END
    LEN --> END
```

---

## 6. Partner messages

Devices only write events; Cloud Functions send everything.

```mermaid
flowchart TD
    EV[Event from the Mac or phone] --> T{Type}
    T -- "binge alert (yours or automatic),<br/>work-hours unblock" --> CAP{Drift alerts this half-day<br/>under 2? midnight–noon, noon–midnight}
    CAP -- no --> HOLD[Held: listed in the daily report<br/>'Also today']
    CAP -- yes --> Q
    T -- "work unblock, work-hours change" --> Q
    T -- "daily report" --> Q
    Q{Partner's quiet hours?} -- yes --> DEF[Deferred, sent when they end]
    Q -- no --> CH{Partner's channel<br/>for this type}
    CH -- push --> PUSH[Push, text instead if their app<br/>hasn't been opened in 3 days]
    CH -- text --> TXT[Text]
    CH -- email --> EM[Email]
    CH -- off --> NONE[Not sent]
```

- Drift messages end with "No need to reply — but a 👍 helps." Partners can react with one tap; reactions reach the
  Mac and the phone.
- Automatic messages are also recorded for your own daily report.

---

## 7. Mac and phone sync

```mermaid
flowchart LR
    subgraph Mac
        MT[Tracking + Kev] --> MR[deviceReports, deviceReportItems,<br/>deviceHeat: every 2 min, and on stepping away]
        MS[Settings, rules, verdicts]
        MST[state/breaks, state/rhythm,<br/>schedule/current]
    end
    subgraph Phone
        PT[Accessibility tracking] --> PR[Its own day report and heat:<br/>every 2 min and on screen-off]
        PS[Settings, rules, verdicts]
        PJ[Unknown items] --> JS[judgeSeen: Jev on the server]
    end
    subgraph Firebase
        MERGE[Merge functions:<br/>phone wins over overlapping Mac time] --> RD[reports, reportItems, heat/year]
        SYNC[sync/*, verdicts/*:<br/>three-way merge on each device]
        EVT[events → partner messages]
    end
    MR --> MERGE
    PR --> MERGE
    MS <--> SYNC
    PS <--> SYNC
    MST --> PH[Phone reads breaks, due check-in, work hours]
    RD --> VIEW[Home on Mac and phone,<br/>Your patterns, partner view]
```

- Raw activity and screenshots never leave the device; only day reports, settings, verdicts and events do.
- A break or lunch started on either device is shared through `state/breaks`; check-ins answered on the phone go to
  `phoneCheckins/{date}` and the Mac takes them as done.
