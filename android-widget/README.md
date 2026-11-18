# Training Today: Android widget for intervals.icu

![Widget previews (sample data): full, full large, trends, workouts](preview.png)

Three widgets that share one intervals.icu connection. You pick them from the widget picker:

| Widget | Default size | What it shows |
|---|---|---|
| **Training Today · Full** | 4×3 (resize from about 4×2 up) | Today's workouts above three tiles for Fitness, Fatigue and Form, each with its own trend line. At 300dp tall or more it switches to a large layout with bigger numbers and taller charts, and it lists more workouts as you make it taller. |
| **Training Today · Trends** | 4×2 (3×2 and up) | One chart only: Fitness (filled blue) and Fatigue (purple) on top, Form underneath on its own scale with zone colouring, and today's three numbers above. Faint lines mark each week. |
| **Training Today · Workouts** | 4×1 (any size) | A compact list. At 1 row tall you see only the top workout, and you scroll for the rest. Workouts still to do come first, then completed ones. `+2 ↓` tells you how many more there are. |

**Workouts:** `○` planned, `★` race, `✓` done. Each row shows duration and training load.

**Colours:**
- Fitness (CTL) is blue, and its 7-day change is green when it rises and red when it falls.
- Fatigue (ATL) is purple.
- Form (TSB = Fitness - Fatigue) is coloured by intervals.icu zone: High risk (< -30, red), Optimal (-30 to -10, green),
  Grey zone (-10 to 5, grey), Fresh (5 to 25, sky blue) and Transition (> 25, amber).

All widgets refresh every hour. Tap ⟳ to refresh them now. Tap workouts to open the intervals.icu calendar,
or tap charts to open the fitness page.

## Install

1. On your phone, open the **Releases** page of this repo and download `training-widget.apk` from
   *Training Today widget (latest build)*.
2. Open the file. Allow your browser or file manager to install unknown apps when Android asks.
3. Long-press your home screen, choose **Widgets** > **Training Today**, and drag out the widget you want: Full, Trends or Workouts.
   You can add several, and they all share the same login.
4. Enter your **Athlete ID** and **API key**. You can find both on intervals.icu under **Settings** > **Developer Settings**.
   Tap **Test and save**.

The key is stored only on the phone and is sent only to `intervals.icu`.

## Build

GitHub Actions (`.github/workflows/android-widget.yml`) builds the APK on every push that changes `android-widget/`.
To build locally, open this folder in Android Studio, or run `./gradlew assembleDebug`.

Builds are signed with a debug key, and each CI run generates a new one. If Android refuses to install an update over
an older copy, uninstall the old app first.
