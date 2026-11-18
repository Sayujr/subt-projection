# Training Today: Android widget for intervals.icu

![Widget previews (sample data): Full, Full with Fitness+Fatigue / Form, Full large, Trends, Compact, Workouts](preview.png)

## The app

Opening **Training Today** shows the full view at full-screen size, with today's workouts above one large
Fitness / Fatigue / Form chart. Tabs along the bottom open the **Week**, **Race**, **Recovery** and **SubT** screens.
These are the same views as the widgets, drawn at full size. ⚙ opens your account settings.

![App home (sample data)](preview-app.png)

## Widgets

All widgets share one intervals.icu connection. You pick them from the widget picker:

| Widget | Default size | What it shows |
|---|---|---|
| **Full** | 4×3 | Today's workouts above the long combined chart. Make it taller to list more workouts. |
| **Trends** | 4×2 | Graphs only, with today's numbers. |
| **Compact** | 4×2 | A one-row workout strip you can scroll, with the trend chart underneath. Workouts still to do come first. |
| **Workouts** | 4×1 | A compact list you can scroll. `+2 ↓` tells you how many more there are. |
| **This week** | 4×2 | Load per day, Mon–Sun: done (green), still planned (amber) and missed (dashed). Shows totals compared with last week, and lists the remaining sessions when tall. |
| **Race countdown** | 2×2 | Days to your next A/B/C race and the projected race-day Fitness and Form (zone-coloured). Lists further races when tall. |
| **Recovery** | 4×2 | HRV, resting HR and last night's sleep. Each shows its 7-day average against your baseline and a trend line. |
| **SubT pace** | 2×2 | This month's median SubT lap pace, the change from last month, a monthly chart (faster plots higher) and a 6-month trend. It uses this repo's `data.json`, so it needs no login. |

![New widgets: week, recovery, race, SubT (SubT uses real data.json; others sample)](preview-extra.png)

**How the race projection works:** starting from today's Fitness and Fatigue, it adds the load of every planned workout
up to race day. It uses the standard 42-day and 7-day averages, the same model intervals.icu uses. It covers races up to
12 weeks away, and it's only as good as your planned workouts.

### Per-widget settings

Long-press a Full, Trends, Compact or Recovery widget and tap the **pencil (Settings)**. You can choose:

- **Graphs**
  - **Automatic** (the default): all three in one chart, on every widget.
  - **Three separate graphs**
  - **Fitness + Fatigue together, Form separate**
  - **All three in one chart:** Fitness and Fatigue on top, zone-coloured Form below. When the chart is tall enough it adds date and value labels.
- **Time period shown:** 2 weeks, 4 weeks, **6 weeks (default)**, 3 months, 6 months or 1 year.
  The 7-day change and today's numbers stay the same whatever period you choose.

Each widget keeps its own settings, so you can place two Trends widgets, for example one showing 6 weeks and one showing 1 year.

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
3. Long-press your home screen, choose **Widgets** > **Training Today**, and drag out the widget you want: Full, Trends, Compact or Workouts.
   You can add several, and they all share the same login.
4. Enter your **Athlete ID** and **API key**. You can find both on intervals.icu under **Settings** > **Developer Settings**.
   Tap **Test and save**.

The key is stored only on the phone and is sent only to `intervals.icu`.

## Build

GitHub Actions (`.github/workflows/android-widget.yml`) builds the APK on every push that changes `android-widget/`.
To build locally, open this folder in Android Studio, or run `./gradlew assembleDebug`.

Every build is signed with the same key, `keystore/training-widget.jks`, and gets a higher version number, so a new APK
installs as an update. Your placed widgets and settings stay in place.

The repository is public, so anyone can read this key. It stops being a protection only if you install an APK from
somewhere other than this repo's release page. For stronger protection, move the keystore and its password into GitHub
Actions secrets.
