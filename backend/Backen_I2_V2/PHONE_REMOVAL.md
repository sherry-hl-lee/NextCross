# Notification contract update

Removed the parentMobileNumber request field and all SMS gateway calls.
Android must omit parentMobileNumber entirely; extra fields still return HTTP 422.
The endpoint remains POST /api/v1/notification-events.

Example request:
```json
{"tripId":"planned-trip-001","eventId":"breach-001","eventType":"PERMITTED_AREA_BREACH","occurredAtEpochMillis":1788231600000,"parentEmail":"parent@example.com"}
```

The email gateway remains a console-only prototype. Its `sent` response means the prototype call completed, not actual email delivery. No real email provider is added by this patch.

## Deploy
Replace the extracted source files in the existing backend directory on the GitHub branch linked to Render. Do not upload only this ZIP or change the directory nesting. Auto-deploy can deploy the commit; otherwise use Manual Deploy > Deploy latest commit. Keep existing Render environment variables and start command.

## Validation
Local regression run: 63 passed, 5 skipped. Live database tests were not enabled in this run. No deployment or provider integration test was performed for this patch.
