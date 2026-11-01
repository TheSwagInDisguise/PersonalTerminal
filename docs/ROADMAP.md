# Roadmap

Ideas for where Personal Terminal can go next, grouped by theme and roughly ordered by
value-for-effort inside each group. Effort: **S** = an evening, **M** = a weekend, **L** = a multi-week feature.

**Status (0.3.7):** everything marked ✅ has shipped – see the release notes and
[docs/AUTOMATION.md](AUTOMATION.md) for the command line / intent API. Unmarked rows are still open.
Tags v0.3.3–v0.3.7 were published retroactively so the tag line matches the shipped bundles; the
next release continues from v0.3.8 (see the B-list bundle plan in [IDEAS.md](IDEAS.md)).

**Scope note.** Personal Terminal is a *personal-use* project that leans heavily on the ideas in
Init Habits. It will not be published to any store and will not grow a Wear OS companion; rows that
only made sense for a public product (store listing, localisation for other people, Wear OS tile)
have been removed rather than left open.

## 1. Habit loop – make the daily check-in stickier

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 1.1 ✅ | **Reminders per habit / routine** (`notify 07:30 morning`) with quiet hours and a "nudge only if not done" rule | The single biggest retention lever in habit apps; the app currently never reaches out | M |
| 1.2 ✅ | **Notes on a completion** (`why`, mood 1–5, a one-liner) shown in the habit log | Turns the log into a journal; pairs with the CLI aesthetic ("git commit -m") | S |
| 1.3 ✅ | **Negative / "avoid" habits** (no sugar, no doom-scrolling) that count *clean days* and break on a slip | Common request in Init Habits-style apps; the streak engine already models gaps | M |
| 1.4 ✅ | **Skip-with-reason** (`sick`, `travel`) that neither breaks nor extends the streak | Reduces "all-or-nothing" abandonment; complements shields | S |
| 1.5 ✅ | **Time-of-day sections** (morning / afternoon / evening / any) instead of only routines – opt-in in settings, per-habit override, current section highlighted | Today screen reads like a day plan | S |
| 1.6 ✅ | **Habit templates** (`habit add --from library`) with ~30 curated presets incl. units and targets | Faster onboarding | S |
| 1.7 ✅ | **Sub-tasks / checklist habits** (pack gym bag → 4 items) – tick items on Today, in the widget and with `tick <habit> <item>` | Routine = habits, habit = steps | M |
| 1.8 ✅ | **Streak insurance rules**: auto-skip date ranges (`away travel 3d`, open-ended until `back`) or weekly rest days, per habit or global – the chain survives without spending a shield | Travel and sick days should not cost a 90-day streak | M |
| 1.9 ✅ | **Repair window, pause-until, weekly-quota nudge** (0.3.4): an unlogged previous day can be ticked late until noon without a shield (`yesterday <habit>`); `pause run 2w` hides a habit until a date and bridges the streak; `x/week` rows read `2/3 · last chance today` and the evening notice includes them | The three most common ways a streak "breaks" without the habit actually being missed | S |
| 1.10 ✅ | **Morning briefing** (opt-in, one line at 07:30): habits due, sleep, watch suggestion, streaks at risk, unlogged yesterday | Replaces several nudges with one glanceable line | S |
| 1.11 ✅ | **Habit strength** (0.3.5): a forgiving exponentially-weighted rate next to the streak (one miss ≈ −7, one day back ≈ +7), sparkline + trend arrow on the detail screen, profile and review, `strength` at the prompt, "weakest first" sort on Today, `slipping:` line in the review, four new achievements | Streaks are binary; strength shows a habit fading *before* the chain breaks | M |
| 1.12 ✅ | **Minimum version & ramping targets** (0.3.5): `min read 2` – reaching the floor logs a partial `[~] min` day that keeps the streak (half XP, counted separately in `stats`); `ramp read 30 8` – the effective target grows one step a week from the stored value, every day judged against *its* target | The two-minute rule and progressive overload as fields, not discipline | M |
| 1.13 ✅ | **Habit stacking** (0.3.5): `after journal read` anchors a habit to one you already do – Today shows the chain `read → journal`, greys the follower until the anchor is ticked and (optionally) fires the follower's reminder the moment the anchor completes | "After I X, I will Y" is the most reliable cue there is | S |
| 1.14 ✅ | **Comeback nudge & evening summary** (0.3.5): after three quiet days one line (`quiet for 4 days · 3 habits waiting · 2 shields ready`) with ×2 back-off that resets on the first log; an opt-in 21:30 summary (`4/6 done · open: read, journal`) replaces N per-habit check-ins | Coming back should be cheap; nagging is not the same as helping | S |
| 1.15 ✅ | **Life areas** (0.3.5): body / mind / work / people / home / money per habit; the weekly review and `area` draw a six-spoke ASCII radar of the week's balance | "8 of 10 done" hides *which* part of life got the 2 | S |
| 1.16 ✅ | **Challenges & new schedules** (0.4.0): `challenge read 30 5` runs a habit for N days with an optional XP stake; Today shows a `[####----] 12/30` bar, the detail screen a boxed ASCII certificate on settle, `challenge read off` cancels an unarmed challenge; win pays stake × 10, a miss costs stake × 5; new *biweekly* (`every 2 weeks` from the start date) and *monthly* (`day 15`) schedules with `isDue`/`describe` support | 30-day challenges, monthly reviews and "every other Monday" are real habits the scheduler could not express | M |
| 1.8 ✅ | **Evening check-in** per habit ("did you do X today?" with done / skip actions), **streak-at-risk** notice, **wear-log** notice, and a **per-notification settings matrix** (each kind switchable with its own time, one master switch, shared quiet hours) | Reminders nudge *before*; check-ins catch what was done but never logged – and nobody wants all of them | M |

## 2. Focus timer

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 2.1 ✅ | **Session history + per-day focus minutes** on the profile heatmap (second colour channel or toggle) | Timer data is already credited to habits but never visualised on its own | S |
| 2.2 ✅ | **Stopwatch mode** (count up, stop when done) for open-ended habits like "practice guitar" | Some habits have no fixed length | S |
| 2.3 ✅ | **Custom intervals per habit** (25/5 vs 50/10) and *auto-start next phase* toggle | Power-user ergonomics | S |
| 2.4 ✅ | **Ambient sounds / tick** (rain, brown noise, mechanical-keyboard clicks) with independent volume (shipped 0.3.8: procedurally generated, `amb` at the prompt, chips on the timer screen) | Popular in pomodoro apps, fits the vibe | M |
| 2.5 ✅ | **Do-Not-Disturb during focus** (request `ACCESS_NOTIFICATION_POLICY`) | Real focus protection | S |
| 2.8 ✅ | **Timer resilience** (0.3.4): partial wake lock while running, a 30-second exact-alarm heartbeat that repaints the island/chip and flips phases on time under doze, and session persistence so a process kill resumes the countdown | Long focus sessions on aggressive OEM battery management | S |
| 2.7 ✅ | Live-Update polish for the ColorOS island: works once "live updates" is allowed for the app in system settings; a **compact live update** switch trims the notification to logo + time left so the island stays small | Vendor-specific | S |

## 3. Watch tracker

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 3.1 ✅ | **Collection stats**: wear share per watch (ASCII pie / bars), days since last worn, "neglected" list, most-worn-per-month | Turns logs into insight; the data is already there | S |
| 3.2 ✅ | **Service & maintenance log** per watch (service date, cost, warranty until, strap changes) with a reminder when the service interval elapses | Watch owners track this in spreadsheets today | M |
| 3.3 ✅ | **Accuracy tracking**: log "watch shows / reference time" pairs, compute s/day drift with a sparkline; optional atomic-clock sync via NTP | Mechanical-watch enthusiasts love this (see WatchCheck / Twixt) | M |
| 3.4 ✅ | **Strap library**: straps as their own entity, log which strap was on which watch, gallery per combo | Very common collector behaviour | M |
| 3.5 ✅ | **Watch box view**: grid of thumbnails colour-coded by days since worn (green → red), sortable by neglect / wears / name, one-tap `wear` | Visual browse mode for bigger collections | S |
| 3.6 ✅ | **"On this day" memories**: wrist shots from 1 / 3 / 6 / 12 … months ago on the timeline, tap → that day | The photo log becomes something you revisit | S |
| 3.6 ✅ | **Rotation suggester** (`watch next`): proposes tomorrow's watch weighted toward neglected pieces and the day's habits (e.g. G-Shock on workout days) | Fun, tiny algorithm, uses both halves of the app | S |
| 3.7 ✅ | **Purchase details & valuation**: price paid, date, box/papers, current estimate; collection total on the profile | Insurance / resale bookkeeping | S |
| 3.8 ✅ | **Import / export CSV** of the collection and wear log | Data portability | S |
| 3.10 ✅ | **Strap swap log** (0.3.4): every fit / removal is recorded (`strap bond nato speedy`), today's wear entry inherits the strap, straps show `on speedy · 23d` and a swap-log panel | Straps are half the hobby | S |
| 3.11 ✅ | **Watch lifecycle** (0.3.6): status owned → in repair → sold; `watch repair speedy` parks it (out of `watch next`, the challenges and the wear notice) and `watch back speedy 320` books the invoice; `watch sold khaki 650` keeps every log and photo but moves the watch to a sold ledger with the realised gain | Collections change; archiving a sold watch threw away the money story | M |
| 3.12 ✅ | **Wishlist** (0.3.6): `wish Tudor BB58 5200` adds a watch with a target price and link, `save 200 BB58` grows the fund (`62% funded` on the profile), `watch buy BB58` converts it into an owned watch carrying the price | Every collector has one; the fund makes it a habit | M |
| 3.13 ✅ | **`uptime`** (0.3.6): power reserve + complications per watch; `uptime` prints `speedy  stopped ~2 d ago  wind · set time · set date`, low-reserve warnings, a "check date" nudge after 30-day months and February, moon age for moon-phase dials, a wind-and-set checklist on the watch page, and the morning briefing flags a stopped suggestion | Nobody does this as a first-class feature and it is genuinely useful | S/M |
| 3.14 ✅ | **Rotation challenges** (0.3.6): every watch this month `3/4`, no repeats this week, dust off the most neglected piece, balanced quarter (top watch ≤ 50 %) – a panel on the watches page, `watch challenges` at the prompt | Turns `watch next` into a small game | S |
| 3.9 ✅ | **Wrist-shot camera frame** (0.4.0): rule-of-thirds grid and centre-square guides over the viewfinder, EXIF description stamp (`personal-terminal wrist shot v0.4.0 · speedy · 1 Nov 2026`) on every in-app capture, optional centre-square crop available in the pipeline | Better photos with zero effort | M |
| 3.15 ✅ | **Gallery, documents & exports** (0.4.0): extra photos per watch (dial / caseback / papers kinds) in a scrolling gallery panel, receipt & warranty attachments listed per watch, an insurance-style **collection report PDF** (`watch report` — reference, purchase, value, service history, documents, thumbnails, totals) and an **A6 contact sheet** PNG (`watch sheet`, 150 dpi, 4×3 grid with captions) | The one export that matters when something goes wrong, and the most personal one to look at | M |

## 4. Insights & gamification

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 4.1 ✅ | **Weekly review** (`report --week`): completion %, best/worst habit, focus minutes, watches worn, XP gained — as a shareable monospace card (PNG) | Reflection loop + organic sharing | M |
| 4.2 ✅ | **Achievements / badges** rendered as man-page style entries (`STREAK(7)`, `PERFECT_WEEK(1)`) | Cheap dopamine layered on the XP system | S |
| 4.3 ✅ | **Per-habit heatmap + best streak + completion trend** on the habit detail screen | Detail screen currently shows only the current streak | S |
| 4.4 ✅ | **Correlations** ("you complete *read* 40 % more on days you *meditate*") | Small statistics, big "aha" | M |
| 4.6 ✅ | **Sleep anchors → correlations** (0.3.4): `sleep 23:30` / `wake 06:45` (or Health Connect sleep) per night; insights shows average night and "you are 40 % more likely to do X after 6h30+" | Sleep is the biggest predictor of whether the rest of the day happens | M |
| 4.7 ✅ | **`stats <habit>`** – 30/90/365-day bars, streaks, best weekday, totals as a monospace block at the prompt | Everything the screens show should be askable | S |
| 4.5 ✅ | **Year in review** (`review --year`): completions, perfect days, best streaks, month sparkline, focus hours, most-worn watch – shareable card | Seasonal delight | M |

## 5. Widgets & system integration

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 5.1 ✅ | **Widget variants**: single-habit 1×1 tile, streak/heatmap 4×2, "today's watch" tile | Current widget is the checklist only | M |
| 5.2 ✅ | **Quick Settings tile** to start/pause the focus timer | One-swipe access | S |
| 5.3 ✅ | **App shortcuts** (long-press icon): add habit, start timer, log wear | Cheap wins | S |
| 5.4 ✅ | **Lock-screen / AOD** timer via the existing Live Update – works on Pixel and, since 0.3.3, in the ColorOS / OxygenOS island once *Live alerts › App alert* is allowed for the app | — | — |
| 5.5 ✅ | **Tasker / intent API** (`dev.personalterminal.ACTION_TOGGLE` with habit name) and an **Android Assistant / App Actions** "mark X done" | Automation crowd | S |
| 5.7 ✅ | **Live widgets** (0.3.4): the home-screen widgets observe the database (a tap or an in-app change re-renders immediately) and roll over at midnight by themselves | Stale widgets were the #1 annoyance | S |
| 5.6 ✅ | **Health Connect** read (steps, sleep, workouts) to auto-complete matching habits | Removes manual logging for the most common habits | L |

## 6. Terminal experience

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 6.1 ✅ | **Real command line**: a prompt at the bottom of Today that accepts `done stretch`, `add water 2`, `wear speedy`, `timer 25`, `theme nord`, `help` — with tab-completion chips and history | The app's whole identity; power users would live in it | M |
| 6.2 ✅ | **Custom themes** (import an iTerm2/Windows-Terminal JSON colour scheme) + more built-ins — 14 shipped: Tokyo Night, One Dark, Rosé Pine, Everforest, Amber CRT, Hacker, Synthwave '84 added in 0.3.3, each paired with its own typeface | Themes are the most requested cosmetic feature in this genre | S |
| 6.3 ✅ | **Font choice** — nine bundled families (JetBrains Mono, Fira Code, Roboto Mono, IBM Plex Mono, Source Code Pro, Victor Mono, Space Mono, VT323, system); themes carry a paired font, manual picks pin it. **Selectable launcher icon** (six variants) landed alongside in 0.3.3 | — | S |
| 6.4 ✅ | **CRT / scanline shader** done properly with `RenderEffect` (currently a simple overlay), plus an optional typing sound | Nostalgia dial | S |
| 6.5 ✅ | **Landscape / tablet layout**: tmux-style split panes (today | timeline) | Foldables and tablets | M |
| 6.6 ✅ | **Accessibility pass**: TalkBack labels for ASCII bars/checkboxes, larger-font layouts, reduced-motion toggle | Broadens the audience and is the right thing to do | S |

## 7. Data, sync & platform

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 7.1 ✅ | **Restore-from-Drive on first launch** ("found a backup from 3 days ago — restore?") | Completes the backup story for phone migrations | S |
| 7.2 ✅ | **Encrypted backups** (passphrase → AES-GCM over the zip) | Wear photos + notes are personal | S |
| 7.3 | **Conflict-free multi-device sync** (append-only log of events replayed on each device) | Real sync instead of last-writer-wins backup — big, only if a second device matters | L |
| 7.4 ✅ | **Import from other apps** (Loop Habit Tracker CSV, Habitica, Streaks) | Lowers the switching cost | M |
| 7.6 ✅ | **Backup heartbeat + verify** (0.3.4): profile shows `last backup 3 h ago · 1.2 MB · 214 photos`, `verify` downloads the newest Drive archive and dry-runs a restore (decrypt, parse, photo audit), `self-check` round-trips an archive locally | Backups fail silently; a visible heartbeat is the cheapest insurance | S |
| 7.5 ✅ | **Room migrations test + schema CI check** and **Compose screenshot tests in CI** (the `screenshots` task already renders every screen; assert against golden PNGs) | Guard rails as the codebase grows | S |

## Notes on the shipped items

- **7.5** ships as `./gradlew verifyScreenshots` (golden PNGs in `screenshots/`, pixel diff with a 1.5 % tolerance,
  diffs uploaded as a CI artifact) plus a Room migration test (1→2→3→4→5 against the exported schemas).
- **1.8 streak insurance** writes skip logs tagged with the rule id over a rolling window (14 days back,
  60 ahead) on every launch, every daily worker run and every rule edit; deleting or ending a rule retracts only
  the skips it created, and a day you completed or un-skipped by hand is never touched again.
- **5.6 Health Connect** reads steps, exercise sessions, sleep sessions and hydration; the device needs the Health
  Connect app (Android 14 has it built in).

## Release discipline (0.3.7 →)

One bundle per version, and every version must pass the **upgrade gate** before it is published:
migrations from every shipped schema (`UpgradeTest`), restore of every backup format ever written
(`LegacyBackupTest`), no public component renamed (`ContractTest`), release APK signed with the pinned key,
plus the advisory install-over-install rehearsal on an emulator. Details in the README.

## Still open

1. **Multi-device sync (7.3)** – encrypted backups + Drive folder give it a base to stand on.

Further candidates — a wrist-shot contact sheet, service warnings at wear time, SQLite export, a "done next habit" quick tile and a vault widget — live in [IDEAS.md](IDEAS.md) with an honest effort estimate for each, together with the **B-list** of next majors (0.3.6 watch lifecycle / wishlist / `uptime` / rotation challenges ✅ → 0.3.8 focus & feel: ambient sounds / ASCII garden / screensaver / app lock ✅ → 0.4.0 challenges / gallery & documents / collection report / contact sheet ✅ → B16 `sshd` → 0.5.0 multi-device sync). The 0.3.5 bundle (strength, minimum & ramp, stacking, comeback nudge, evening summary, life areas) is ticked there.
