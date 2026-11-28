# Prompt: convert my intervals.icu gym calendar to isometric + compound days

You have access to my intervals.icu calendar and my Hevy history. I'm a runner training for a sub-3 marathon, and I do short gym sessions after my runs. Rework my planned gym sessions for the next **6 months (1 Dec 2026 to 31 May 2027)** using the rules below.

**Before you write anything**, show me the full plan as a table (date, session type, focus, minutes, load, and what happened to the existing event) and wait for my "go". Never delete my events. Update existing ones, and add new ones only where the rules say so.

---

## 1. Context

- **Me:** about 83 kg (183 lb). I run 5–7 days a week, with SubT/TT workouts usually on **Tue, Thu, Sat** and a long run on **Sun**.
- **Gyms:** I train at two, **Potrock** and **Fox**. They have different machines (see section 5).
- **Time:** every gym session is **25 minutes total**, then I leave. I can't wait for machines, so every slot needs alternatives.
- **Current calendar:** lots of events named **"Gym#1"** (and similar). These are my isometric sessions. Each has a planned load and duration, and keeps the same day and time unless the rules below change it.
- **Logging:** I log everything in Hevy in **kg**. Use **lb** in descriptions, with kg in brackets.

## 2. Two kinds of gym day

**A. Isometric day (the default).** This is any planned gym day that isn't a compound date.
- **Pattern:** go through machines in priority order. For each machine, do 1–2 sets of 10–12 at RPE 8–8.5, then 1–2 isometric holds of 30–45 s at about double that load.
- **Logging the holds:** each hold is logged as **1 rep**.
- **Time:** as many machines as fit in 25 minutes.

**B. Compound day.** These fall only on the dates in section 3, and only ever on **Tue, Thu or Sat**.
- **Structure:** short warm-up, one heavy barbell lift (3–5 reps), then fill the rest of the 25 minutes with runner-useful accessories (section 6).
- **Isometric machines:** don't use the isometric machine list on these days.

## 3. Compound dates

There are 20 compound days, about every 9–10 days, on Tue/Thu/Sat only.

**The mix:**

| Focus | Days | Share |
|---|---|---|
| Squat | 10 | 50% |
| Deadlift | 5 | 25% |
| Barbell seated shoulder press | 3 | 15% |
| Bench press | 2 | 10% |

| # | Date | Focus |
|---|---|---|
| 1 | Tue 1 Dec 2026 | Squat |
| 2 | Thu 10 Dec 2026 | Deadlift |
| 3 | Sat 19 Dec 2026 | Squat |
| 4 | Tue 29 Dec 2026 | Shoulder press |
| 5 | Thu 7 Jan 2027 | Squat |
| 6 | Sat 16 Jan 2027 | Deadlift |
| 7 | Tue 26 Jan 2027 | Squat |
| 8 | Thu 4 Feb 2027 | Bench |
| 9 | Sat 13 Feb 2027 | Squat |
| 10 | Tue 23 Feb 2027 | Deadlift |
| 11 | Thu 4 Mar 2027 | Squat |
| 12 | Sat 13 Mar 2027 | Shoulder press |
| 13 | Tue 23 Mar 2027 | Squat |
| 14 | Thu 1 Apr 2027 | Deadlift |
| 15 | Sat 10 Apr 2027 | Squat |
| 16 | Tue 20 Apr 2027 | Bench |
| 17 | Thu 29 Apr 2027 | Squat |
| 18 | Sat 8 May 2027 | Deadlift |
| 19 | Tue 18 May 2027 | Squat |
| 20 | Thu 27 May 2027 | Shoulder press |

**Rules for these dates:**
- **Gym session already planned that day:** convert it to the compound session (rename it and replace the description).
- **No gym session that day:** add one, placed right after that day's run, around the time of my usual gym sessions.
- **A race (RACE_A/B/C) within 10 days after:** make it a lighter "technique" compound day (2 × 3 at about 80% of the working weight) and say so in the name.
- **A Saturday compound before a long run on Sunday:**
  - Squat or deadlift: keep it, but cap the top sets at RPE 8.
  - Anything else: no change.
- **Gym days not on this list:** they stay isometric days.

## 4. Load and duration (so intervals.icu counts them)

- **Type:** every event is `category: WORKOUT`, `type: WeightTraining`, `moving_time: 1500` (25 min).
- **Isometric day load:** keep the planned load already on that event. For a new isometric event, use the median load of my existing Gym#1 events.
- **Compound day load:** double that isometric load.
- **Names:**
  - `Gym · Isometric`
  - `Gym · Compound · Squat` (or `· Deadlift`, `· Press`, `· Bench`)
  - Keep names this simple: no weights, sets or gym name in the name. Those go in the description.

## 5. Isometric session: priority order with alternatives

At each step, take whichever option is free.

| # | Muscle | Potrock | Fox |
|---|---|---|---|
| 1 | Quads | Leg extension (machine) | Leg extension |
| 2 | Hamstrings | Seated leg curl | Lying leg curl / seated leg curl |
| 3 | Shoulders | Seated shoulder press (machine) | Shoulder press machine / DB seated press |
| 4 | Back (pick one) | Seated cable row · lat pulldown | Cable row · lat pulldown · chest-supported row |
| 5 | Chest | Chest press (machine) | Chest press / DB floor press |
| 6 | Glutes | Glute drive machine | Hip thrust (barbell / Smith) |
| 7 | Calves | Seated or standing calf raise | Standing calf raise / leg-press calf raise |
| 8 | Hips | Hip abduction + adduction machines | Same, or cable abduction |
| 9 | Biceps | Preacher curl machine | Bicep curl (cable or DB) |
| 10 | Triceps | Triceps extension machine | Triceps press machine / cable pushdown |
| 11 | Core | Ab crunch machine ("AB Machine Gold B") | Cable crunch / decline sit-up |

**Description format for an isometric session (pre-filled from my latest Hevy numbers):**
```
Isometric circuit · 25 min · take what's free, in this order
1. Leg extension: 2×10–12 @ 175 lb (79 kg) RPE 8 → 2× hold 30–45 s
2. Seated leg curl: 2×10–12 @ 175 lb (79 kg) RPE 8 → 2× hold
3. Seated shoulder press: 2×10–12 @ 100 lb (45 kg) → 1–2× hold
4. Row OR pulldown: 2×10–12 @ 120 lb (54 kg) / 100 lb (45 kg) → hold
5. Chest press: 2×10–12 @ 85–115 lb → hold
6+. If time: glute drive · calf raise · hip ab/adduction · biceps · triceps · ab crunch
Alternatives if busy: see list above
```

## 6. Compound session template (25 minutes)

Times below are approximate.

1. **Warm-up, about 5 min:** leg extension 1×12 light, then bodyweight squats or hip hinges, then 2–3 ramp-up sets of the main lift.
2. **Main lift, about 12 min:** 2–3 working sets of 3–5 reps at the planned weight, RPE ≤ 8. Rest 2–3 min between sets.
3. **Runner accessories, about 8 min:** pick 2–3, rotating so each week covers them all.
   - Calves: single-leg calf raise 2×12, or seated calf raise 2×15 (soleus).
   - Core: dead bug, Pallof press 2×10 each side, or ab crunch machine.
   - Hips: Copenhagen plank 2×20 s each side, or hip abduction machine.
   - Feet/shins: tibialis raise 2×15.
   - Single leg: split squat 2×8 each side (only after deadlift or bench days, not squat days).

**Description format for a compound session:**
```
Compound · SQUAT · 25 min
Warm-up: leg ext 1×12 light · 10 BW squats · ramp 45 lb×8, 95×5, 115×3
Main: Back squat 3×5 @ 135 lb (61 kg) RPE ≤8 · rest 2–3 min
Progress rule: two clean sets of 5 at RPE ≤8 → next squat day +5 lb
Accessories (8 min): seated calf raise 2×15 · Pallof press 2×10/side · Copenhagen 2×20 s/side
```

**Garmin:** write descriptions as plain step lines like the ones above, so the session text shows on my Garmin when intervals.icu syncs the planned workout. Use one line per exercise and keep each line under about 60 characters. If intervals.icu supports structured strength steps for Garmin, use them as well.

## 7. Progression and goals: put this in every compound description and in a summary

**Current estimated 1RMs** (from my latest Hevy sets; re-check them against my Hevy history and correct them if you find newer data):

| Lift | Est. 1RM now | Working weight now (5s) | First milestone |
|---|---|---|---|
| Squat | 174 lb | ~145 lb | 200 lb × 5 |
| Deadlift | 208 lb | ~175 lb | 235 lb × 5 |
| Barbell seated press | 96 lb | ~80 lb | 100 lb × 5 |
| Bench | 143 lb | ~120 lb | 150 lb × 5 |

**Progression rule:**
- **Clear:** two clean sets of 5 at RPE ≤ 8 → **+5 lb** next time that lift comes up (+10 lb for deadlift while it's below 200 lb × 5).
- **Not clear:** repeat the same weight.
- **Two misses in a row:** drop 10% and build back.

**Where I'm heading:** standards for about 183 lb bodyweight, as multiples of bodyweight. Show where I am now and when I reach each level if every session clears.

| Lift | Novice | Intermediate | Advanced |
|---|---|---|---|
| Squat | 1.0× (183 lb) | 1.5× (275 lb) | 2.0× (365 lb) |
| Deadlift | 1.25× (230 lb) | 1.75× (320 lb) | 2.25× (410 lb) |
| Bench | 0.75× (137 lb) | 1.0× (183 lb) | 1.5× (275 lb) |
| Seated/overhead press | 0.5× (92 lb) | 0.7× (128 lb) | 1.0× (183 lb) |

**Month-by-month path:**
- Give a table with each lift's planned working weight and estimated 1RM at the end of each month (Dec to May).
- Make two versions: "every session clears" and "one repeat per month".
- Count only that lift's sessions in the schedule. Squat comes up 10 times in 6 months; bench only twice.
- Say plainly which milestones fall after May at this frequency, and what frequency would reach them by May.

## 8. Output I want

1. **Dry run table:** date · existing event (name, load) · action (convert / keep / add) · new name · load · focus.
2. **Totals:**
   - compound days per lift, which must be 10/5/3/2
   - isometric days per week
   - weekly gym load before and after
3. **Progress plan:** the section 7 tables, with the standards and the monthly path.
4. **After my "go":** write the events to intervals.icu, then list every event id you changed or created.
5. **Never:** delete events, move runs, or schedule a compound day on Mon/Wed/Fri/Sun.
