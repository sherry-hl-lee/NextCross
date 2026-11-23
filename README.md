# NextCross

**Teen driving safety companion** — an Android app that records a supported trip on-device, detects hard acceleration / hard braking, monitors a permitted driving area, and can notify a parent when the trip leaves that area. Supporting services load Victoria open road-safety data (speed rules, young-driver crash hotspots) and expose APIs for speed lookup, historical context, AI reflection, and trip-report email.

This repository is a **portfolio archive** that consolidates three related workspaces from a university team project (Monash FIT5120) into one place for interview review:

| Folder | What it is |
|--------|------------|
| [`android/`](android/) | Kotlin + Jetpack Compose client (recording, detection, UI, local Room storage) |
| [`backend/`](backend/) | FastAPI services (breach email, speed analysis, crash context, AI reflection, reports) |
| [`database/`](database/) | Data platform formerly **`sdg-platform`**: PostgreSQL/PostGIS pipelines for statewide speed, calendar, and crash datasets |

> **Note for recruiters:** This is coursework / prototype software, not a production safety or enforcement product. Recorded events support post-trip discussion; they do not prove fault or cause.

---

## Links

| | |
|--|--|
| **Product site / APK download** | [https://nextcross.tech/](https://nextcross.tech/) |
| **Domain** | `nextcross.tech` (registered for the project) |
| **Product video** | Embedded on the website (no separate public URL) |

---

## Why this project

Young drivers face elevated crash risk. NextCross focuses on **privacy-aware monitoring**:

- Continuous GPS stays **on the phone** during the trip (Room).
- Parent alerts are **event-based** (e.g. permitted-area breach), not live tracking streams.
- Open VicRoads / Transport Victoria datasets power speed and crash context for discussion after the drive.

---

## Demo flow (Android)

```text
Splash → Trip setup (Places) → Pre-trip map → Review + consent
      → Active recording (foreground GPS)
      → Trip summary (route + hard events)
      → Optional report / parent email / AI reflection
```

**Typical interviewer talking points**

1. Start / End trip gates recording (no idle capture).
2. Hard accel / brake from GPS speed change (`a = Δv / Δt`, threshold 3.0 m/s²).
3. Circular permitted area; `inside → outside` triggers one notification path.
4. Backend deduplicates / cooldown for breach email; database pipeline supplies speed & crash tables for lookups.

---

## Architecture

```text
┌──────────────────────────────┐
│  Android (Compose + Room)    │
│  GPS recording · detection   │
│  permitted-area monitor      │
└──────────────┬───────────────┘
               │ HTTPS JSON
               ▼
┌──────────────────────────────┐     ┌─────────────────────────────┐
│  Backend (FastAPI)           │────▶│  PostgreSQL / PostGIS       │
│  notifications · speed       │     │  (loaded by data pipeline)  │
│  crash context · AI · email  │     │  speed · crash · calendar   │
└──────────────────────────────┘     └─────────────────────────────┘
```

| Layer | Highlights |
|-------|------------|
| **Android** | Foreground location service, Room (`trips`, `gps_points`, `driving_events`, reports), Places / Maps, unit-tested client logic |
| **Backend** | `POST /api/v1/notification-events`, speed analysis, historical crash context, Groq reflection, report email — see [`backend/README.md`](backend/README.md) |
| **Database** | `iteration3_v1` pipeline → schema `iteration2_v2` (9 tables: road/speed, calendar, crash hotspots/events, notification, report dispatch) — see [`database/README.md`](database/README.md) |

---

## Tech stack

- **Mobile:** Kotlin, Jetpack Compose, Room, Foreground Service, Google Maps Platform (Places / Maps)
- **API:** Python 3.12, FastAPI, Uvicorn, Docker
- **Data:** PostgreSQL, PostGIS, Python ETL / loaders, GitHub Actions (scheduled candidates)
- **Integrations:** Parent email (Gmail / Resend), optional Groq for reflection

---

## My contributions

I owned the **Android client logic end-to-end**, plus deployment and the public download site:

- **Android client (`android/`)** — trip lifecycle (Start / End), foreground GPS recording, on-device Room storage, hard acceleration / hard braking detection from GPS speed change, permitted-area monitoring hooks, and related client contracts used by the UI
- **Parent notifications** — breach-event transport to the backend notification API; end-to-end wiring so leaving the permitted area can email a parent
- **Trip report delivery** — report email / share path from the client through the backend report endpoints
- **Backend deployment & testing** — deployed and verified the FastAPI service (health, notification and report flows); kept local and hosted checks runnable for demos
- **Product presence** — registered **`nextcross.tech`**, built/hosted the **download website**, and published the product video on [https://nextcross.tech/](https://nextcross.tech/)

Team-mates owned other slices (UI polish, data-pipeline tables, additional API features). This archive keeps the full system so interviewers can see how the pieces connect.

---

## Quick start

### Android

```text
1. Open android/ in Android Studio
2. Create untracked secrets.properties (see android/README.md)
3. Run on device/emulator; grant precise location
```

Details: [`android/README.md`](android/README.md)

### Backend

```text
cd backend/Backen_I2_V2
python -m venv .venv
# activate venv, then:
pip install -r requirements.txt
uvicorn app.main:app --reload
# open http://127.0.0.1:8000/docs
```

Details: [`backend/README.md`](backend/README.md) · [`backend/Backen_I2_V2/README.md`](backend/Backen_I2_V2/README.md)

### Database / data pipeline (`sdg-platform`)

```text
cd database/data-pipeline/iteration3_v1
# Python 3.12+, PostgreSQL/PostGIS, env for DATABASE_URL
# see pipeline README for migrate / load / deploy commands
```

Details: [`database/README.md`](database/README.md) · [`database/data-pipeline/iteration3_v1/README.md`](database/data-pipeline/iteration3_v1/README.md)

---

## Privacy & secrets

Do **not** commit:

- `secrets.properties`, `.env`, API keys, Gmail app passwords
- Personal parent emails or identifiable demo inboxes

The product design keeps continuous trip GPS on-device; breach payloads are intentionally limited (no live track dump).

---

## Repository status

| Item | Detail |
|------|--------|
| Purpose | Personal portfolio mirror of team coursework |
| Branch | `main` |
| Origin team work | Monash FIT5120 (team org repos were source imports) |
| Maturity | Prototype / academic — expect demo limits (emulator GPS, API quotas, hosted free tiers) |

---

## Licence / attribution

University coursework produced with teammates. Included here for interview discussion of my role and the system design. Ask before reusing team-owned assets in another product.
