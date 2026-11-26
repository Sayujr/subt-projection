# Run windows: spec

This is the agreed design for the "best run window" feature. It is built in two parts:
- **Part 1**: forecasts, scoring, the Today card and day plan, notifications, the Week list, and Settings.
- **Part 2**: widgets, self-scoring, "outside now" buttons, "watch closely", split runs, and the swap button.

## 1. Data sources (all fetched on the phone)

| Source | Endpoint | Gives | Use |
|---|---|---|---|
| Rainbow Weather API | `GET https://api.rainbow.ai/weather/v1/forecast/{lon}/{lat}?forecast_days=7&forecast_hours=168` | Hourly fields: temperature, feelsLikeTemperature, temperatureDewPoint, uvIndex, precipitationChance (%), precipitationAmount (mm), precipitationRate, precipitationType, windSpeed (m/s), windGust, visibility (m), humidity, condition (e.g. Clear, PartlyCloudy, MostlyCloudy, Cloudy, Drizzle, Rain, HeavyRain, Breezy, Snow). Times are UTC ISO strings. | Full vote. The user's trusted source, so it also gets its own "Rainbow pick". |
| Rainbow Nowcast | `GET https://api.rainbow.ai/nowcast/v1/precip/{lon}/{lat}` | `forecast[]` holds `{precipRate, precipType, timestampBegin, timestampEnd}` per minute, for about 4 h. `summary.intensity`. | Exact rain start and stop over the next 4 h. Drives the "window shifted" check. |
| Open-Meteo | `GET https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&hourly=temperature_2m,apparent_temperature,dew_point_2m,cloud_cover,precipitation,precipitation_probability,uv_index,wind_speed_10m,wind_gusts_10m,weather_code,is_day,visibility&daily=sunrise,sunset&models=ecmwf_ifs025,gfs_seamless,ncep_hrrr_conus,ncep_nbm_conus&timezone=auto&forecast_days=7&wind_speed_unit=kmh` | One set of hourly arrays per model, with the model name as a suffix (e.g. `cloud_cover_ecmwf_ifs025`). Models that don't cover the location return nulls. | Votes for cloud % and the rest. Sunrise and sunset come from here. |
| Open-Meteo air quality | `GET https://air-quality-api.open-meteo.com/v1/air-quality?latitude=..&longitude=..&hourly=us_aqi&timezone=auto&forecast_days=5` | Hourly US AQI | 😷 caution |
| NWS (US only) | `GET https://api.weather.gov/points/{lat},{lon}`, then `properties.forecastGridData` gives `properties.skyCover.values[]` as `{validTime: "ISO/PT1H", value}` | Official sky cover % | Main cloud % vote in the US. Must send a generic `User-Agent: TrainingToday/1.0` and nothing personal. A 404 means outside the US, so skip it. |

Rules for every source:
- Each source is optional. A failed or missing source is skipped and never blanks the card.
- Cache the last good result per source with its fetch time. Show "as of HH:MM" when the data is stale (older than 3 h).
- Rainbow calls are counted in Prefs per month and shown in Settings, against the free plan's 10,000 calls a month.

## 2. Combining sources

Build one combined hour record per hour:
- **Temperature, feels-like, dew point, wind, gusts:** median of the available sources.
- **UV:** the maximum of Rainbow and the Open-Meteo models. The user is sensitive to UV, so the cautious value wins.
- **Rain:**
  - mm/h is the median.
  - Chance is the max of Rainbow and Open-Meteo.
  - For the next 4 h, the Rainbow nowcast replaces the rain amount minute by minute (use the max over each 10-minute step).
- **Cloud %:** median of NWS, NBM, HRRR, ECMWF and GFS. Rainbow's `condition` is mapped to a cloud % (Clear 5, MostlyClear 25, PartlyCloudy 50, MostlyCloudy 75, Cloudy/Overcast/Drizzle/Rain 95) and counts as one more vote.
- **Agreement, for each hour:**
  - "cloudy" means cloud % of 60 or more.
  - It is the share of sources that agree with the median on cloudy vs not, and on wet vs dry (wet means more than 0.25 mm/h).
  - The window's agreement is the minimum across its hours. Show it as "4/5 agree".

Two picks:
1. **Overall**: from the combined hours.
2. **Rainbow**: from Rainbow's hourly data plus the nowcast only.

If they are the same, or overlap by 80% or more, show one window marked "✓ sources agree". Otherwise show both.

## 3. Hour classification and colour (shows the weather itself, the same for every run type)

Temperature bands use **feels-like** when it is below 5 °C or above 25 °C, and the air temperature otherwise:
- cold: below the cold setting (default 5 °C)
- cool: from cold up to 14 °C
- warm: from 14 °C up to the hot setting
- hot: at or above the hot setting (default 15 °C November–March, 20 °C April–October)

Other conditions:
- humid: dew point at or above 16 °C (setting)
- sunny: cloud below 60% and UV at or above 2
- wet: rain above 0.5 mm/h (light rain, up to 0.25 mm/h or a 20% chance, counts as dry)

Colours, from best to worst:

| Colour | Hex | Hours |
|---|---|---|
| best | `#7FB59A` | cool, cloudy, low UV |
| chill | `#9AA7B4` | cold, cloudy, low UV; also below 0 °C with UV 0–1 |
| lo1 | `#FBE0B5` | cold and sunny |
| lo2 | `#F8C07A` | warm, cloudy, low UV; or cool, sunny and humid |
| or | `#F59E42` | warm, humid, cloudy |
| orr | `#F7793F` | warm and sunny; or hot, humid, cloudy |
| red | `#F05A4A` | hot and humid, with rain or sun |
| dred | `#D93A2B` | hot and sunny |
| deep | `#A3241C` | hot, sunny and humid (worst) |

- Night hours (before sunrise or after sunset) are drawn as `#2a303d`.
- Icons on the cells: ☁ cloud of 60% or more; ☀ UV above the user's limit; 💧 wet; 🧊 ice or snow (frozen precipitation type, or temperature at or below 0 °C within 12 h after rain); 😷 AQI above 100; 💨 only on TT/CPT days, when gusts are over 30 km/h.

## 4. Window scoring for each run type (picks the window)

**Plan for the day.** Take the planned run from the intervals.icu week (`Snapshot.week` / `workouts`), using its planned duration. If there is no duration, estimate it from the distance at 5:30/km. With nothing planned, use 60 min. Always add a 30-minute buffer (setting).

**Run type:**
- TT/CPT when `Streaks.isTtName` matches, or the run is a race
- SubT when `SubtWorkouts.isSubtWorkout` matches
- Long when `Zones.isLong` matches
- Easy otherwise

**Candidate windows:**
- Start every 30 minutes inside the allowed hours.
- Allowed hours are Auto (daylight: sunrise − 30 min to sunset + 30 min) or Custom (separate summer and winter from–to times).
- Night windows are a fallback, used only if no daylight window fits. They are marked 🌙 and get a lights reminder.

**Hour penalty for each run type** (lower is better; a window's score is the mean of its hours plus its worst hour × 0.5):

| Run type | Order of importance | UV | Heat | Cold |
|---|---|---|---|---|
| TT/CPT | lowest temperature ≥ 5 °C (Apr–Oct) → lowest dew point → wind/gusts → rain | weak penalty: may break the limit, then 🧢 | strong | strong below 5 °C |
| SubT | temperature and dew point (extra weight) → UV → rain | full limit | strong | mild |
| Long | UV (limit − 1, strictest) → feels-like below 5 °C (strong, 🧤) → cloud → rain | strict | **none** (heat training) | strong |
| Easy | UV → cloud → rain | full limit | **none** | mild |

Rules for every run type:
- Light rain is no penalty. Between 0.25 and 0.5 mm/h is a mild penalty. Above 0.5 mm/h is a strong penalty and adds 💧 "gym or outside".
- Ice, snow, or AQI above 150 make the hour **dangerous**. If every candidate window has a dangerous hour, the day shows ⛔ "even the best window is risky: gym recommended".
- Narrow ⚠: the best window's good stretch is no more than the needed length plus 15 minutes.
- Sunny only 🧢: every candidate breaks the UV limit.

## 5. UI

**Today: "Best run window" card** (below today's call):
- Collapsed: big window time, ⚠ and other icons, run name and a short summary, the strip, and one line for tomorrow.
- The strip:
  - covers allowed hours ± 2 h (or 05:00–21:00)
  - has the white box drawn around the window, with the start and end times in bold above the box
  - has a 3-hourly time axis
  - shows a blue "now" line on today's strip
  - shows 🌅 sunrise and 🌇 sunset ticks
- Tap to open the **day plan** screen.

**Day plan screen** (a new activity, opened for today, tomorrow, or any day from the Week list):
- **Summary box first.** Plain language:
  - the day's shape
  - the overall pick, and the Rainbow pick when it differs (with why)
  - source agreement, and which source dissents
  - one suggestion: go at X, gym, or swap with day Y (swap is text only in Part 1)
  - warnings
- Then the strips for that day, an hour table for the window (temperature / feels-like, cloud %, UV, dew point, rain mm/h and %, wind), sunrise and sunset, and a sources list showing each source's view of the window.
- An "Open in Rainbow" button (launches the Rainbow app if installed, otherwise its website).
- A **Refresh now** button.

**Week screen:** a "Run windows · 7 days" list. Each row has the day, the planned run, the best window with icons and a one-line summary, and a mini strip. Rows fade further out. Tap a row to open the day plan. A Refresh now button.

**Notifications** (each with its own on/off and time, added to `NotifySettings`):
- Tomorrow's window at 20:00.
- Today's window, added to the 04:00 workout notification.
- **Window shifted**: whenever a refresh moves today's or tomorrow's best window by 30 minutes or more, or changes its warnings (rain arriving or leaving, ⛔, ⚠). Always on by default. Message: "Run window shifted: … → best is HH:MM–HH:MM (was …)".

**Refresh:**
- A WorkManager job every 3 h.
- On the day of a planned run, every 1 h from 05:00 until the window starts.
- Plus manual Refresh now.
- Quiet hours 22:00–05:00 (no checks).

**Settings → new collapsible "Run windows" section:**
- Location: a place name or lat/lon, with a "Use where I am now" button that asks for location permission with Android's "only this time" option. No background location.
- Running hours: Auto (daylight) or Custom (summer and winter from–to).
- UV limit (default 2).
- Hot (Nov–Mar) and hot (Apr–Oct).
- Cold.
- Humid (dew point).
- Buffer.
- Rainbow API key: masked to its last 4 characters, with Test key / Remove buttons and the calls used this month.
- Notification rows go in the existing notification section.

**Rainbow key security:**
- Stored encrypted with an Android Keystore AES-GCM key (no extra library).
- Excluded from Backup export and never written to CrashLog or logs.
- Sent only to `api.rainbow.ai`, in the header `Ocp-Apim-Subscription-Key`.
- Never in code or tests.

## 6. Part 2 (later)

- **Widgets:** A (one line plus a mini strip added to the existing Today widget) and C (medium: today and tomorrow strips).
- **Self-scoring:**
  - Keep about 60 days of forecasts and compare them with the Rainbow cloud tile "now" and with "outside now" taps.
  - Learn the weight of each source and show "cloud calls right 82%".
- **"Watch today closely"** mode: checks every 30 minutes, auto-on for narrow or disputed windows.
- **Split-run suggestion** when no window fits.
- **Swap with day Y** button: writes to intervals.icu only on an explicit tap and confirmation.

## Queued: winter evening hours (not built)
- In winter, runs that **start** up to 18:30 count as normal daytime windows, even though it's dark. They are not night fallbacks.
- After an 18:30 start, hours count as night: used only as a fallback, with 🌙 and a lights reminder. The latest finish is still 20:00.
- **Mornings:** keep the current rule (from sunrise minus 30 min) in winter, spring and autumn.
- **Summer exception:** in summer, also allow early pre-sunrise starts as normal windows, to avoid UV. Earliest start follows the summer custom/auto hours (e.g. from 05:00).
