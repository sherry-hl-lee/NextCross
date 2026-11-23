# NextCross Backend

**Start with [`Backen_I2_V2/`](Backen_I2_V2/README.md).** This is the current consolidated FastAPI application. Use this directory for development, testing, and deployment. The folder name retains its existing spelling.

## Repository layout

```text
backend/
├── README.md
├── Backen_I2_V2/                         # Current application
└── archive/
    ├── README.md
    ├── Backend_I2/                       # Earlier historical-crash / discussion backend
    └── US2.3-Permitted-Area-Breach-Backend/ # Earlier standalone breach-notification backend
```

The [archive](archive/README.md) preserves the previous implementations and their existing local changes. Archived folders are reference material; select `Backen_I2_V2` as the application root.

## Consolidated features

All routes below are registered by `Backen_I2_V2/app/main.py`. Interactive API documentation is available at `/docs` when the service is running.

| Feature | Endpoint |
| --- | --- |
| Permitted-area breach email, event deduplication, and per-trip cooldown | `POST /api/v1/notification-events` |
| End-trip notification cleanup | `DELETE /api/v1/notification-events/{trip_id}` |
| Historical crash context | `GET /api/v1/historical-crash-context` |
| Rule-based discussion prompts | `POST /api/v1/trip-discussion-prompts` |
| Post-trip speed analysis | `POST /api/v1/trips/{trip_id}/speed-analysis` |
| Speed-limit lookup | `GET /api/v1/speed-limit` |
| Two-turn Groq reflection | `POST /api/v1/ai/reflection/question`, `POST /api/v1/ai/reflection/advice` |
| Earlier single-turn reflection | `POST /api/v1/ai/reflection` (deprecated) |
| JSON report preview / email | `POST /api/v1/reports/preview`, `POST /api/v1/reports/email` |
| Multipart trip-report email and attachments | `POST /api/v1/trip-reports/email` |
| Process health | `GET /health` |

## Local setup

Use Python 3.12, matching the supplied Docker image. From the repository root on macOS/Linux:

```sh
cd Backen_I2_V2
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m pytest -q
python -m uvicorn app.main:app --reload --no-access-log
```

Open `http://127.0.0.1:8000/docs`. Windows setup is in the [application README](Backen_I2_V2/README.md#windows-setup). The application reads process environment variables; `.env` files are not automatically loaded. With no configured database, notifications use local SQLite, while historical and speed queries require their database configuration for real results.

For deployment, set the service root directory to `Backen_I2_V2` and use its [Dockerfile](Backen_I2_V2/Dockerfile). The container uses the platform's `PORT` value, defaulting to `8000`.

## Configuration and handovers

| Area | Configuration / guide |
| --- | --- |
| Notification storage | `RUNTIME_DATABASE_URL`, then `DATABASE_URL`; local fallback: `DATABASE_PATH` |
| Breach delivery | Gmail: `GMAIL_ADDRESS` + `GMAIL_APP_PASSWORD`; otherwise Resend: `RESEND_API_KEY` + `REPORT_FROM_EMAIL`; otherwise console fallback |
| Notification cooldown | `BREACH_EMAIL_COOLDOWN_SECONDS` (default: `600`) |
| Historical queries | `HISTORICAL_DATABASE_URL`, then the notification database URL; [database handover](Backen_I2_V2/docs/database_handover.md) |
| Speed queries | `SPEED_DATABASE_URL`, then `HISTORICAL_DATABASE_URL`, then the notification database URL; [speed-analysis handover](Backen_I2_V2/docs/SPEED_ANALYSIS_HANDOVER.md) |
| AI reflection | `GROQ_API_KEY`, optional `GROQ_MODEL`; [Groq setup](Backen_I2_V2/docs/GROQ_SETUP.md), [two-turn contract](Backen_I2_V2/docs/AI_TWO_TURN_HANDOVER.md) |
| Report delivery | `REPORT_EMAIL_ENABLED`, `REPORT_EMAIL_PROVIDER`, `REPORT_FROM_EMAIL`, and provider settings; [email handover](Backen_I2_V2/docs/REPORT_EMAIL_HANDOVER.md) |
| Report dispatch storage | `REPORT_EMAIL_DATABASE_URL`, then `RUNTIME_DATABASE_URL`, then `DATABASE_URL`; persistent storage is required on Render |
| Multipart report contract | [client contract](Backen_I2_V2/docs/TRIP_REPORT_EMAIL_CLIENT_CONTRACT.md) |

Breach delivery selects Gmail first when both Gmail variables are configured. Resend in the consolidated application uses `REPORT_FROM_EMAIL`; the standalone US2.3 variable `RESEND_FROM_EMAIL` is no longer used here. Without a configured breach provider, `sent` records completion of the console fallback and does not establish inbox delivery. The notification contract accepts email only; Android must omit `parentMobileNumber`.
