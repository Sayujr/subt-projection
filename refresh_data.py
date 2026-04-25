"""
Pull all SubT-qualifying running laps from intervals.icu since 2025-02-01,
regenerate data.json for the projection page.

Runs daily via .github/workflows/refresh-data.yml.

Required env vars:
  INTERVALS_ICU_API_KEY     — set as repo secret
  INTERVALS_ICU_ATHLETE_ID  — set as repo secret (e.g. "i252754")
"""
from __future__ import annotations
import asyncio
import json
import os
import sys
import time
from datetime import date

import httpx

API = "https://intervals.icu/api/v1"
KEY = os.environ.get("INTERVALS_ICU_API_KEY", "")
ATH = os.environ.get("INTERVALS_ICU_ATHLETE_ID", "")
START = "2025-02-01"

if not KEY or not ATH:
    print("ERROR: INTERVALS_ICU_API_KEY and INTERVALS_ICU_ATHLETE_ID must be set")
    sys.exit(1)


def client():
    return httpx.AsyncClient(
        base_url=API,
        auth=("API_KEY", KEY),
        timeout=60.0,
    )


async def list_runs():
    today = date.today().isoformat()
    async with client() as c:
        r = await c.get(
            f"/athlete/{ATH}/activities",
            params={"oldest": START, "newest": today, "limit": 2000},
        )
        r.raise_for_status()
        all_acts = r.json()
    runs = [a for a in all_acts if (a.get("type") or "").lower() in ("run", "trailrun", "treadmill")]
    print(f"Got {len(all_acts)} activities, {len(runs)} runs since {START}")
    return runs


async def fetch_intervals(activity_id, sem):
    async with sem, client() as c:
        for attempt in range(3):
            try:
                r = await c.get(f"/activity/{activity_id}/intervals")
                if r.status_code == 404:
                    return []
                r.raise_for_status()
                return r.json().get("icu_intervals") or []
            except Exception as e:
                if attempt == 2:
                    print(f"  WARN {activity_id}: {e}")
                    return []
                await asyncio.sleep(2)
    return []


async def main():
    runs = await list_runs()
    sem = asyncio.Semaphore(8)
    print(f"Fetching intervals for {len(runs)} runs...")
    t0 = time.time()
    results = await asyncio.gather(*[fetch_intervals(r["id"], sem) for r in runs])
    print(f"Done in {time.time()-t0:.1f}s")

    # Filter to SubT-qualifying laps
    all_repeats = []
    for run, ivl_list in zip(runs, results):
        run_date = (run.get("start_date_local") or "")[:10]
        for ivl in ivl_list:
            avg_hr = ivl.get("average_heartrate") or 0
            max_hr = ivl.get("max_heartrate") or 0
            elapsed = ivl.get("elapsed_time") or 0
            avg_speed = ivl.get("average_speed") or 0
            if avg_speed <= 0 or avg_hr <= 0 or elapsed <= 0:
                continue
            duration_min = elapsed / 60
            pace_min_per_km = (1000 / avg_speed) / 60
            if not (3.5 <= pace_min_per_km <= 9.0):
                continue
            # SubT filter (matches the projection model)
            if not (avg_hr > 160 and max_hr <= 172
                    and 1.5 <= duration_min <= 15):
                continue
            all_repeats.append({
                "date": run_date,
                "pace": round(pace_min_per_km, 4),
                "duration": round(duration_min, 2),
                "hr": round(avg_hr, 1),
            })

    # Build monthly medians
    from collections import defaultdict
    by_month = defaultdict(list)
    for r in all_repeats:
        by_month[r["date"][:7]].append(r["pace"])
    monthly = []
    for month in sorted(by_month.keys()):
        paces = sorted(by_month[month])
        med = paces[len(paces)//2] if len(paces) % 2 else (paces[len(paces)//2-1] + paces[len(paces)//2]) / 2
        monthly.append({
            "date": f"{month}-01",
            "n": len(paces),
            "pace": round(med, 4),
            "is_outlier": month == "2025-04",
        })

    out = {"all_repeats": all_repeats, "monthly": monthly}
    out_path = os.path.join(os.path.dirname(__file__), "data.json")

    # SAFETY GUARD: if new data is worse than existing, refuse to overwrite.
    # Protects against ICU API hiccups, auth failures returning partial data, etc.
    if os.path.exists(out_path):
        with open(out_path) as f:
            existing = json.load(f)
        existing_repeats = len(existing.get("all_repeats", []))
        existing_months = len(existing.get("monthly", []))
        if len(all_repeats) < existing_repeats:
            print(f"REFUSING to overwrite: new has {len(all_repeats)} repeats vs existing {existing_repeats} (-{existing_repeats - len(all_repeats)})")
            print("This usually means an ICU API hiccup. Existing data preserved.")
            sys.exit(1)
        if len(monthly) < existing_months:
            print(f"REFUSING to overwrite: new has {len(monthly)} monthly points vs existing {existing_months} (-{existing_months - len(monthly)})")
            sys.exit(1)
        # Per-month sanity: each existing month should still have ≥80% of its previous count
        existing_by_month = {m['date']: m['n'] for m in existing.get("monthly", [])}
        for m in monthly:
            old_n = existing_by_month.get(m['date'])
            if old_n is not None and m['n'] < old_n * 0.8:
                print(f"REFUSING to overwrite: month {m['date']} has {m['n']} laps vs existing {old_n} (>20% drop)")
                sys.exit(1)

    with open(out_path, "w") as f:
        json.dump(out, f, indent=1)
    print(f"Wrote {len(all_repeats)} repeats and {len(monthly)} monthly points to {out_path}")
    print(f"Latest month: {monthly[-1] if monthly else 'none'}")


if __name__ == "__main__":
    asyncio.run(main())
