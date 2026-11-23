# Temporary email test without Neon dispatch storage

For the existing client multipart endpoint POST /api/v1/trip-reports/email ONLY.
Patch is based on NextCross_Backend_Client_Report_Email; keep all other files.

1. Upload app/trip_report_email.py to GitHub Backen_I2_V2/app/trip_report_email.py.
2. Render backend-8sxu Environment: REPORT_EMAIL_TEST_BYPASS_STORE=true.
3. Keep REPORT_EMAIL_ENABLED=true, REPORT_EMAIL_PROVIDER=resend, RESEND_API_KEY,
   and REPORT_FROM_EMAIL configured. Format validation and attachment limits still apply;
   there is no recipient allowlist.
4. Save/deploy, wait until live. Send one synthetic request to a real inbox you control.
5. Acceptance returns status=accepted, testMode=true, dispatchStore=bypassed.
   Confirm receipt in the mailbox. This is a genuine provider call, not a mock success.
6. When testing ends, set REPORT_EMAIL_TEST_BYPASS_STORE=false and redeploy.
   Resolve Neon table/permissions/URL to restore normal email operation.

Default is false. Merely adding this environment variable to the OLD code does nothing;
this one-file patch must be deployed first. Do not remove the normal database path.
While enabled, database deduplication AND its shared daily limit are bypassed. No dispatch
record is written. Provider rate limits and Resend Idempotency-Key remain, but this is
not verification of application-level deduplication. Avoid repeated sends, and turn
test mode off after the test. Do not automatically retry an uncertain response with a
fresh request ID.

This verifies HTTP → validation → provider → receipt, not Neon or normal duplicate handling.
Legacy /api/v1/reports/email is unaffected and still requires its normal store.
Offline tests check bypass, failure propagation and switch-off.
No credentials are included. This patch was not pushed/deployed and sent no actual mail.
