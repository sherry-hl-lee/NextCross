# Turn-2 wording update (2026-10-06)

Implements BACKEND_PROMPT_ADDENDUM.md in app/ai_reflection.py.
- Address the reader as you; forbid the driver / the user / this driver in generated advice.
- Attribute ALL driver-only details (vehicle, animal, person, road condition, flippant claims),
  not just alarming details. Do not turn an account into confirmed cause.
- Recorded peak acceleration/speed change describes this event, never a prescribed safety limit.
- Concrete actions rather than vague slogans; connect advice to recorded figures descriptively.
- Established following-gap examples and transparent conversion of supplied speed are allowed
  for braking, while acceleration/type-specific guidance remains unchanged.
- Includes bad/good worked examples; example facts are not to be copied into other events.

Unchanged: endpoints, JSON schemas/fields, quality gate, status mapping, safe_context whitelist,
turn-1 prompt, one-shot legacy prompt, limits, historical/speed/report interfaces.
The original role/schema/neutral-language instructions in turn 2 remain.
No extra output rejection rules were added: this is a prompt fix, not a semantic validator.
The existing implementation already accepts GOOD/UNKNOWN for both two-turn routes, rejects
POOR before provider invocation; legacy one-shot still has its old quality gate. The addendum's
“still outstanding” gate note was not used to introduce a new gate change here. Confirm the
client uses /ai/reflection/question and /ai/reflection/advice and the latest deployed commit.

Added tests in tests/test_ai_reflection.py check prompt reaches the provider, rules/examples
are present, and three mocked outputs obey second-person/attribution/no-threshold assertions.
These are wiring and controlled-fixture assertions, NOT proof the real model obeys the prompt.
No live Groq call was made for this delivery. Existing quality/contract regression tests remain.

Manual live check (FOUR real calls, consumes quota):
python tools/Test-AdviceWording.py --base-url https://backend-8sxu.onrender.com --run
Use after deploying. Read ordinary, unverifiable hazard, flippant and gibberish answers:
- second person throughout, including acknowledgement and encouragement
- every driver detail attributed, not recorded/established cause
- no numeric target/limit made from peakAccelerationMs2, speedBeforeKmh or speedAfterKmh
- incomplete accounts acknowledged; event-specific practical suggestions
- UNKNOWN wording tentative and approximate; no guarantee/reaction praise
Do not mark this live check passed without reading actual model outputs.

## Two packages
Normal_No_Switch: complete backend with durable email dispatch records; no bypass code.
Use this for normal operation; Neon table/access must be fixed for mail sending.
With_Email_Test_Switch: same backend plus temporary REPORT_EMAIL_TEST_BYPASS_STORE flag
for /api/v1/trip-reports/email only. Default false. Set true explicitly on Render to skip
Neon dispatch storage and its dedup/global daily budget for an authorised synthetic test.
Recipient allowlist and provider checks remain. Resend provider idempotency remains.
Turn false after testing; see docs/EMAIL_TEST_MODE.md. This is not database verification.
Both packages include full backend/ and changed-files/ relative to the prior
NextCross_Backend_Client_Report_Email package. changed-files/ includes the correct email
route for its variant, so uploading normal version removes a previously added bypass.
Do not mix changed-files from both packages.

Upload changed-files CONTENTS into Backen_I2_V2, preserve directory paths and merge any
new team edits; do not create another nested backend/changed-files directory. Never upload
.env or keys. Full backend/ is provided for local use, not a second GitHub service.
Normal version: set REPORT_EMAIL_TEST_BYPASS_STORE=false/remove before deploying.
Root Directory Backen_I2_V2, usual pip build and uvicorn start command remain.
The normal report email still needs Neon report_email_dispatch.sql migration/permissions.
The with-switch package can test mail without that table but does not fix Neon connectivity.
