# Ideas & polish backlog

Candidate features that would be *genuinely useful* for a one-person habit + watch terminal, and
the polish items found while auditing every screen at 0.3.2. Ordered by value-for-effort.
Effort: **S** = an evening, **M** = a weekend, **L** = multi-week.

**Shipped in 0.3.4:** A1 sleep anchors, A2 pause-until, A3 repair window, A5 weekly-quota nudge,
A8 strap swap log, A9 backup heartbeat + verify, A11 `stats <habit>`, A13 morning briefing.

## Features worth building

| # | Idea | Why it earns its place | Effort |
|---|------|------------------------|--------|
| A1 ✓ | **Bedtime / wake anchors** – `sleep 23:30` / `wake 06:45` / `slept 23:30 06:45` from the prompt, a tap-to-edit line on Today, Health Connect sleep sessions fill the gaps (manual wins). Insights gets a *sleep* panel: average night and "you are 40% more likely to do X after 6h30+". | Sleep is the single biggest predictor of whether the rest of the day's habits happen; the correlation engine already exists. | M |
| A2 ✓ | **Habit pause with a return date** – `pause run until 2026-10-01` / `pause run 2w` / `resume run`, or the *pause* panel in habit edit (1w · 2w · 1m · date). The habit leaves Today, the widget and reminders, the streak is bridged, and it returns by itself. | Injuries and busy months; avoids the temptation to archive and forget. | S |
| A3 ✓ | **Streak repair window** – until 12:00 an unlogged previous scheduled day shows `[ did it yesterday ]` on the row (and `yesterday <habit>` at the prompt); it is ticked late with a "logged late" note, no shield spent. After noon the shield path takes over as before. | Most "broken" streaks are forgotten logs, not missed habits. | S |
| A4 ✅ | **Numeric targets that ramp** (`read 10 → 30 pages over 6 weeks`) – the target increases weekly. | Progressive overload is how habits actually grow; a static target is either too easy in week 1 or too hard in week 6. | M · shipped 0.3.5 |
| A5 ✓ | **Weekly quota display for `x/week` habits** – the row now reads `1/3 · 2 more, 4 days left`, turns yellow at `last chance today` / `every day now (2 left)` and red at `week missed`; the evening streak-risk notice includes last-chance quotas. | Weekly habits are the ones that silently fail on Sunday night. | S |
| A6 | **Wear-photo contact sheet** – `watch shots speedy` renders a month of wrist shots as a monospace-captioned grid PNG for sharing. | The photo log is the most personal data in the app and there is no way to look at it in bulk. | M |
| A7 | **Water resistance / service warnings on the wear log** – a small `⚠ service overdue` / `⚠ not for swimming` badge next to the watch when logging. | The service log exists; surfacing it at the moment of choice is what makes it useful. | S |
| A8 ✓ | **Strap swap log** – `strap bond nato speedy` / `strap bond nato drawer` (or the fit row on the straps page) appends to a `strap_swaps` table; today's wear entry inherits the strap; the straps page shows `on speedy · 23d` and a swap log panel. | Straps are half the hobby; today the strap↔watch link is a single mutable field. | S |
| A9 ✓ | **Backup health line on the profile** – `last backup 3 h ago · 1.2 MB · 214 photos` (red when older than 2× the interval), last-verified line, `[ self-check ]` writes + reads back an archive locally; settings › drive gets `[ verify ]`, which downloads the newest archive and dry-runs a restore (decrypt, parse, photo audit) without touching the database. | Backups fail silently; a visible heartbeat is the cheapest insurance. | S |
| A10 | **Data export as SQLite** (`export db`) alongside CSV. | One file, every table, opens in DB Browser; the app already owns the Room file. | S |
| A11 ✓ | **`stats <habit>` command** – 30/90/365-day bars, streak/best/shields, best weekday, totals/averages, skips & notes, repair hint; bare `stats` lists every habit's 30-day rate. | Keeps the terminal promise: everything the screens show should be askable. | S |
| A12 | **Voice / quick-tile "done" for the next due habit** – a QS tile that ticks the top undone habit and shows its name. | The timer tile proved the pattern; this covers the 90 % case with zero taps into the app. | S |
| A13 ✓ | **Morning briefing notification** (opt-in, settings › notifications, default 07:30) – `3 habits · 2 this morning · slept 7h 15m · wear: 62MAS · streak at risk: journal · last chance: workout`, expanded body lists each item. | Replaces several nudges with one glanceable line. | M |
| A14 ✅ | **Habit dependencies** (`journal` after `meditate`) – the dependent habit is dimmed until its parent is done, then highlighted. | Habit stacking is the technique; the UI can enforce the order gently. | M · shipped 0.3.5 |
| A15 | **Local on-device widgets for the vault** – a 2×2 widget showing today's watch photo and a `next: Cartier (14 d)` line. | The watch side has no widget yet. | M |

## Next majors (B-list, surveyed September 2026)

What the established habit apps (Init Habits, Loop, Habitify, Atoms, Way of Life, Beeminder), the
dedicated watch-collection apps (Lugs, WristTrack, Horologe) and the focus timers (Tide, Forest,
Session) do that Personal Terminal does not yet – filtered for a one-person, no-store, terminal-styled
app. Generic mechanics only; nothing that copies another app's names, copy or branding.

### Habits

| # | Idea | Why it earns its place | Effort |
|---|------|------------------------|--------|
| B1 ✅ | **Habit strength** – a Loop-style exponential score per habit (`strength 87%`) that decays a little on a miss and recovers with completions, next to the streak. Sparkline on the row, "weakest first" sort on Today, strength in `stats`, review and achievements. | Streaks are binary; strength is the forgiving number that tells you a habit is *slipping* before it breaks | M · shipped 0.3.5 |
| B2 ✅ | **Minimum version + ramping targets** – `min 2 pages` on a 20-page habit: hitting the minimum keeps the streak as a partial `[~] min`, stats show full vs minimum rate; `target 10 → 30 over 8 weeks` ramps the target automatically (A4). | The two-minute rule / lazy-day version is the strongest anti-abandonment idea in the genre | M · shipped 0.3.5 |
| B3 | **Challenges & bounded habits** – start and end dates (`day 12/30`), biweekly and monthly schedules, a challenge card with a progress bar, a monospace certificate when it completes, optional XP stake that is lost on failure. | 30-day challenges, monthly reviews and "every 2 weeks" are real habits the scheduler cannot express today | M |
| B4 ✅ | **Habit stacking** – `after meditate` anchors a habit to another; Today shows the stack `meditate → journal → stretch`, greys the follower until the anchor is done, and can *remind when the anchor completes* instead of at a clock time (A14). | Event-based reminders are the one reminder type no habit app offers; stacking is how habits are actually built | S/M · shipped 0.3.5 |
| B5 ✅ | **Comeback nudge + evening summary** – gone quiet for 3 days → one gentle line ("3 habits waiting, streaks are shielded"); an optional 21:30 summary that can replace the per-habit check-ins. | Reminders get muted when they are noisy; a single adaptive line survives | S · shipped 0.3.5 |
| B6 ✅ | **Life areas & balance** – tag habits body / mind / work / people, an ASCII radar in the weekly review, "nothing for *people* in 9 days". | Shows the shape of the week, not just the count | S/M · shipped 0.3.5 |

### Watches

| # | Idea | Why it earns its place | Effort |
|---|------|------------------------|--------|
| B7 ✅ | **Watch lifecycle** (shipped 0.3.6) – status owned / in repair / sold (sale price + date → realised gain), in-repair banner tied to the service drop-off, collection value over time as a sparkline. | Collections change; archived-or-not loses the money story | M |
| B8 ✅ | **Wishlist** (shipped 0.3.6) – brand, model, target price, link, notes, photo, "saved so far"; converts to an owned watch carrying the price. Profile line: `next: BB58 · 62% funded`. | Every collector app has one; ours can tie the fund to XP milestones | M |
| B9 ✅ | **`uptime` for mechanical watches** (shipped 0.3.6) – power-reserve hours per watch + last worn → "running / stopped ~2 d ago", a "set date" nudge after 30-day months and February for non-perpetual calendars, a wind-and-set checklist before wearing, moon-phase age for moonphase dials. | Genuinely useful and nobody does it as a first-class feature; the pun writes itself | S/M |
| B10 | **Collection report + documents** – multiple photos per watch (dial, caseback, papers), receipt / warranty attachments, and an insurance-style PDF: photo, reference, serial, purchase, value, service history, totals. | The one export that matters when something goes wrong | M |
| B11 ✅ | **Rotation challenges** (shipped 0.3.6) – "wear every watch once this month 3/4", "no repeats this week"; folds into B3. | Turns `watch next` into a game for bigger collections | S |

### Focus & terminal feel

| # | Idea | Why it earns its place | Effort |
|---|------|------------------------|--------|
| B12 ✅ | **Ambient sounds** (roadmap 2.4) – procedurally generated brown / pink noise, rain and mechanical-keyboard clicks (no audio assets), independent volume, stops on the break. | The last open focus-timer item; the sound becomes the cue for focus | M · shipped 0.3.8 |
| B13 ✅ | **ASCII garden** – every focus session grows a plant on a `garden` screen (box/Braille art), idle days wilt it, a year becomes a field. | Forest-style growth without a cartoon; pure delight | M · shipped 0.3.8 |
| B14 ✅ | **Screensaver + keyboard TUI** – matrix rain / game of life / starfield after idle (opt-in, for a docked phone or tablet), `j`/`k`/`space` on hardware keyboards. | Cheap character for the terminal metaphor | S/M · shipped 0.3.8 |
| B15 ✅ | **App lock** – biometric / PIN on launch and after N minutes, optional secure-window flag so valuations never show in recents. | Watch values and wrist shots are personal | S · shipped 0.3.8 |
| B16 | **`sshd` – terminal over LAN** – the app serves a token-protected text UI on Wi-Fi (`http://phone:7331`) with the same command shell (`done`, `wear`, `stats`) and a read-only heatmap; a keyboard on a laptop drives the phone. | The honest answer to "web app + sync" for a one-person, one-phone setup | L |
| B17 | **App blocking during focus** – usage-stats + overlay that bounces chosen apps while a session runs. | Real focus protection – but hard to verify headlessly and fragile across OEMs | L |

Suggested bundles: **0.3.5** ✅ = B1 + B2 + B4 + B5 + B6 (habit loop, shipped) · **0.3.6** ✅ = B7 + B8 + B9 + B11 (watches, shipped) ·
**0.3.7** ✅ = upgrade gate (pinned release key, `UpgradeTest` / `LegacyBackupTest` / `ContractTest`, advisory install-over-install job) ·
**0.3.8** ✅ = B12 + B13 + B14 + B15 (focus & feel, shipped) · **0.4.0** = B3 + B10, then B16 on its own.
Every version from here on is released one bundle at a time and has to pass the gate before it is published (see README › Upgrade gate).

## Polish audit (0.3.2)

Fixed in 0.3.3:

- Buttons clipped their labels (`[ watch` / `[ man` / `[ habit`): `TermButton` now steps the font size
  down instead of clipping; long labels were shortened (`vault`, `stats`, `achievements`, `+ add`).
- Prompt headers that overflowed on a 412 dp phone (`habit add --templa…`, `history | grep foc…`,
  `vim ~/.co…`) now use short commands.

Still on the list:

- **Habit row density**: the Today row shows streak, schedule tag and best in a second line; on a
  10-habit day that is a lot of scrolling. A "compact" toggle (single line per habit, progress as a
  6-char bar) would help.
- **Empty states** are plain comments (`# no watches yet`). A three-line ASCII illustration + a single
  primary action would read better on first launch.
- **Timeline day cards** repeat the full `EEE dd MMM yyyy` title; the month is already in the header.
- **Snackbar-style feedback** for prompt commands is a one-line `Comment` that disappears on the next
  recomposition; a small history (`↑` to recall the last command, like a shell) would fit the metaphor.
- **Colour picker in habit edit** uses eight swatches without names; add the name under the selected
  swatch for accessibility mode.
- **Light themes**: `bgHighlight` on Solarized Light and Rosé Pine Dawn is close to `bgAlt`; selected
  rows need a border or a stronger tint.
- **Tablet split panes** show the same header on both sides; the right pane could drop the prompt.
- **Loading flashes**: heavy screens (year review, insights) show `# crunching…` for a frame or two;
  keep the previous result on screen while recomputing.
