# Missed-session swaps: spec (agreed design, not built yet)

This covers runs only; bike sessions are ignored. Workouts and easy days always alternate, and the long run counts as a workout.

## When a session counts as missed

A planned run that isn't done counts as missed when one of these is true:
- its best window has passed,
- the day is over,
- the weather is bad all day (⛔ dangerous, or 💧 more than 0.5 mm/h in every window).

## Rules

| Missed | What moves | Doubles | How far changes reach |
|---|---|---|---|
| Easy run | Nothing | One double: on the next easy or workout day that has two good run windows. Never on the long-run day. | Same as the week rule below |
| Workout (not Thursday) | The workout moves to the next day. Later sessions move +1 day and are re-alternated: no workouts back to back, and none the day before or after the long run. A blocked workout day becomes easy. | Every easy run pushed off its day becomes a double. It goes the same day if that day has two good windows; otherwise on the next easy or workout day with good windows. | Missed Mon–Fri: this Sunday. Missed Sat/Sun: up to next Sunday. |
| Thursday workout | Nothing | Friday gets a double | – |
| Long run | It moves to the next day as the long run. The day after can't be a workout, so it becomes easy, and the workouts move on. | Each easy run pushed off its day becomes a double | Missed Sunday: up to next Sunday |
| TT / CP test | It is **added** to another Saturday. That Saturday's workout stays. | – | – |
| Race | Never moves | – | – |

- **Double length:** a double is always **60% of the week's typical easy run** (the median planned easy duration that week; for example, 50′ becomes a 30′ double).
- **Targets:** moved workouts keep their planned power targets.
- **A workout that loses its slot** (e.g. Saturday's after a Tuesday miss): **left untouched** in intervals.icu. An easy run is added that day, and the user skips the workout. The app **never deletes anything**.
- **The week after a spill** is never touched.

## Examples

**Tuesday workout missed:**

| Day | Planned | After |
|---|---|---|
| Mon | Easy | Easy |
| Tue | A | missed |
| Wed | Easy | A + 30′ double (if two good windows) |
| Thu | B | Easy + 30′ double |
| Fri | Easy | B |
| Sat | C | Easy is added; C is left in place to skip |
| Sun | Long | Long |

**Saturday workout missed:**

| Day | Planned | After |
|---|---|---|
| Sat | Workout | missed |
| Sun | Long | Workout |
| Mon | Easy | Long |
| Tue | Workout | Easy + double |
| Wed | Easy | Workout |

From there the rest settles by next Sunday.

## How it works in the app

1. The app shows 1–3 options. Each option shows the before and after for every changed day, and where each double's windows are.
2. You pick an option, then confirm.
3. The app applies it in intervals.icu: it moves events (keeping the time of day) and adds new planned events for the doubles and added easy runs.
4. Undo restores everything the change made.

Suggestions appear on the day plan, on the Today card, and in a notification ("Missed? Options to fit it in").
