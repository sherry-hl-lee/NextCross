# Multipart trip report email verification — 2026-10-05

Fresh Python 3.12 virtual environment; installed this package's requirements.txt.
Full regression: **168 passed, 5 skipped**, one Starlette/AnyIO deprecation warning.
Multipart feature suite: **29 passing cases** included in the above count.

Verified offline: text-only submission; meta as field/file; PDF, PNG and JPEG attachments;
provider attachment construction (SMTP MIME and Resend base64); preservation of report body;
fixed attachment filenames; matching retry skips send; changed report/attachment conflicts;
invalid email/header/body/time/count/selection/extra fields; missing route; bad MIME/signature;
file/body/meta limits including body without Content-Length; duplicate parts/JSON keys;
allowlist, daily budget, pending dispatch; provider failed/unknown/rate-limited responses;
no automatic retries; hash-only storage and privacy-safe application logs.
Existing historical, speed, notification, AI and legacy report suites also passed offline.

SMTP/Resend tests use fake transports. No real mail was sent. Five live database tests
were skipped because external DB verification was not enabled. No Neon migration,
Render deployment, Android adapter, real receipt or device sharing was tested.
Attachment signature checks are not a full document/image validator or malware scanner.
Client-selected text/attachment contents cannot be authenticated against a saved backend
report; local report isolation and selection must be verified in Android.
