# Training Today: Android widget for intervals.icu

A home-screen widget that shows:

- **Today's workouts**: planned workouts and races from your intervals.icu calendar (`○` planned, `★` race), plus completed activities (`✓`). Each row shows duration and training load.
- **Fitness, Fatigue and Form**: today's value, the change over the last 7 days, and a 42-day trend line for each.
  - Fitness (CTL) is blue, and its 7-day change is green when it rises and red when it falls.
  - Fatigue (ATL) is purple.
  - Form (TSB = Fitness - Fatigue): the number and the line are coloured by intervals.icu zone. The zones are
    High risk (< -30, red), Optimal (-30 to -10, green), Grey zone (-10 to 5, grey), Fresh (5 to 25, sky blue)
    and Transition (> 25, amber). The zones are also shaded behind the chart.

The widget refreshes every hour. Tap ⟳ to refresh it now. Tap the workouts to open your intervals.icu calendar,
or tap the tiles to open the fitness chart.

## Install

1. On your phone, open the **Releases** page of this repo and download `training-widget.apk` from
   *Training Today widget (latest build)*.
2. Open the file. Allow your browser or file manager to install unknown apps when Android asks.
3. Long-press your home screen, choose **Widgets** > **Training Today**, and drag it out. A 4×3 size works best,
   and you can resize it.
4. Enter your **Athlete ID** and **API key**. You can find both on intervals.icu under **Settings** > **Developer Settings**.
   Tap **Test and save**.

The key is stored only on the phone and is sent only to `intervals.icu`.

## Build

GitHub Actions (`.github/workflows/android-widget.yml`) builds the APK on every push that changes `android-widget/`.
To build locally, open this folder in Android Studio, or run `./gradlew assembleDebug`.

Builds are signed with a debug key, and each CI run generates a new one. If Android refuses to install an update over
an older copy, uninstall the old app first.
