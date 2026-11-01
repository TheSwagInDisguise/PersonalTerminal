# Automation & command line

Personal Terminal exposes one shell in three places: the prompt on the **Today** screen, the
`AutomationReceiver` broadcast API (Tasker, MacroDroid, Automate, `adb`) and the widget /
notification actions. Everything below works without opening the app.

## The prompt (Today screen)

```
done <habit>              toggle / complete            undo <habit>         un-complete
add <n> <habit>           counter +n / timer +n min    set <n> <habit>      absolute value
skip <habit> -- reason    skip today (bridges streak)  unskip <habit>
slip <habit>              log a slip on an avoid-habit note <habit> -- text  completion note
mood <1-5> [habit]        mood for a habit             timer [min] [habit]  start a focus session
timer stop|pause|resume   control the timer            stopwatch [habit]    count-up session
tick <habit> <item|n>     tick / untick a checklist item (habit completes when all are ticked)
wear <watch>              log today's watch            watch next           rotation suggestion
watch vault               collection grid by neglect   watch stats          collection stats
watch repair <watch>      send for service (status in repair, out of `watch next`) · watch back <watch> [invoice]
watch sold <watch> [price] mark sold – history kept, realised gain booked · `watch back <watch>` undoes it
wish [brand model [target]] wishlist: bare lists it, with words adds one · `wish rm <watch>`
save <amount> <watch>     add to a wishlist fund (`save -50 …` withdraws) · watch buy <watch> [paid]  → owned
uptime                    power-reserve status of the mechanical watches (stopped / low / running) + wind-and-set steps
watch challenges          rotation challenges (every watch this month, no repeats this week, dust off, balanced quarter)
watch report | insure     insurance-style collection PDF (reference, purchase, value, services, docs, totals)
watch sheet | contact     A6 contact-sheet PNG of the collection's shots (150 dpi grid, shareable)
shield <habit>            repair the latest gap        habit add [template] new habit / from template
challenge <habit> <days> [stake]  bounded run: bar on Today, certificate on settle (×10 win / ×5 miss XP)
challenge <habit> off     cancel an unarmed challenge  · status prints the window when it is open
yesterday <habit>         late-log the previous scheduled day (repair window: before 12:00, no shield spent)
pause <habit> [until yyyy-mm-dd | 2w | 10d | 1m]      hide a habit until a date (streak bridged)
pause                     list paused habits           resume <habit>       bring it back early
sleep 23:30 · wake 06:45  sleep anchors for the night  slept 23:30 06:45    both at once · `sleep` shows stats
stats [habit]             30/90/365-day block (bare `stats` = one line per habit)
strength [habit]          habit strength, weakest first (sparkline + trend) · `sort strength|routine` orders Today
min <habit> <n>           minimum version: n keeps the streak as `[~] min` · `min <habit>` logs it · `min <habit> 0` clears
ramp <habit> <to> <weeks> grow the target one step a week (`ramp read 30 8`) · `ramp <habit> off` · bare shows
after <habit> <anchor>    habit stacking: <habit> follows <anchor> (greyed until it is ticked, nudged then) · `after <habit> none`
area <habit> <area>       life area body|mind|work|people|home|money · bare `area` draws the balance radar
strap <strap> <watch>     fit a strap (logged as a swap) strap <strap> drawer take it off · strap <strap> where is it
remind <habit> 07:30      set / `off` the reminder     remind <habit> checkin on|off   evening check-in
remind                    list reminders
away <reason> [3d | yyyy-mm-dd [yyyy-mm-dd]]           streak insurance: auto-skip a date range
away <reason>             open-ended (until `back`)    away                 list rules
lan on | lan off          serve the shell over Wi-Fi at http://phone:7331 (token-protected) · lan = status
export db                 WAL-checkpoint + share the Room file (every table) as a .db
back                      end every open-ended rule today
ls · status · review · review year · achievements · insights · timeline · journal · settings
theme <name> · font <name|theme> · icon <name> · dark · light · backup · help
```

Habits and watches are matched by id, exact name, unique prefix, substring or initials
(`dw` → "drink water"). A bare habit name is treated as `done <habit>`. Anything after ` -- `
(or ` # `) is a note / reason.

## Broadcast API (Tasker etc.)

Send an **explicit broadcast** to package `dev.personalterminal`, receiver
`dev.personalterminal.automation.AutomationReceiver` (Tasker: *Send Intent* → Target: Broadcast
Receiver, Package `dev.personalterminal`, Class `dev.personalterminal.automation.AutomationReceiver`).

| Action | Extras |
|---|---|
| `dev.personalterminal.action.COMPLETE` | `habit` (string) or `id` (long) |
| `dev.personalterminal.action.INCREMENT` | `habit`, `amount` (int, default 1) |
| `dev.personalterminal.action.SET` | `habit`, `value` (int) |
| `dev.personalterminal.action.SKIP` | `habit`, `reason` (string) |
| `dev.personalterminal.action.WEAR` | `watch` (string: nickname / model / id) |
| `dev.personalterminal.action.TIMER_START` | optional `habit`, `minutes` (int) |
| `dev.personalterminal.action.TIMER_STOP` | – |
| `dev.personalterminal.action.BACKUP` | – (requests a Drive backup) |

Every action also accepts `command` (string) with any prompt line, e.g. `--es command "done stretch -- felt great"`.

```bash
adb shell am broadcast -n dev.personalterminal/.automation.AutomationReceiver \
  -a dev.personalterminal.action.COMPLETE --es habit "stretch"
adb shell am broadcast -n dev.personalterminal/.automation.AutomationReceiver \
  -a dev.personalterminal.action.TIMER_START --es habit "focus session" --ei minutes 50
adb shell am broadcast -n dev.personalterminal/.automation.AutomationReceiver \
  -a dev.personalterminal.action.WEAR --es command "wear speedy -- office day"
```

Results are logged under the tag `PTAutomation`; the widget refreshes after every action.

## Other entry points

- **Widgets** – *today* (tap a row to tick / +1 / +5 min / tick the next checklist item, header
  opens the app), *status* (level, XP, streak, shields), *timer* (start / pause / stop).
- **Quick Settings tile** – "Focus timer": tap to start with the global pomodoro lengths, tap
  again to stop.
- **App shortcuts** – long-press the icon: `timer`, `wear`, `habit add`, `review`.
- **Reminder notifications** – "done" / "+1" action button.
- **Deep links** – `MainActivity` extra `route` with any in-app route (`timer`, `review`,
  `habit/42`, `wear/log`).
- **Health Connect** – link a counter/timer habit to steps, exercise minutes, sleep hours or
  hydration in `habit edit`; the value is pulled hourly and on app start.
- **Streak insurance** – rules are re-applied on every launch, by the daily reminder worker and after
  each rule edit, over a window of 14 days back / 60 days ahead. Auto-skips show up on Today as
  `[»] skipped: <reason>`; completing or un-skipping such a day by hand overrides the rule for that day.
