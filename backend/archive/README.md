# Archived backend implementations

Use [`../Backen_I2_V2/`](../Backen_I2_V2/README.md) for the current application. These earlier folders were moved here after checking the consolidated source at `origin/main` commit `1e0dc49` on 11 October 2026.

| Folder | Original purpose |
| --- | --- |
| [`Backend_I2/`](Backend_I2/README.md) | Historical-crash queries and rule-based discussion prompts, with the earlier notification prototype |
| [`US2.3-Permitted-Area-Breach-Backend/`](US2.3-Permitted-Area-Breach-Backend/README.md) | Standalone permitted-area breach notifications, Gmail/Resend delivery, deduplication, and cooldown |

The source, tests, handover files, and existing local files were preserved. Existing US2.3 README/repository edits and its local `.gitignore` are included. Its two links to the sibling data-pipeline repository were adjusted for the new location.

US2.3's local notification-table migration is partial: event registration/status/update queries use `iteration2_v2.notification_event`, while the cooldown query still uses `iteration1_v2.notification_event`. The current consolidated application continues to use `iteration1_v2` throughout.

Older setup instructions and historical handovers remain here for reference. If an archived implementation must be run again, recreate its virtual environment at its new location; existing virtual-environment scripts can contain absolute paths to the original folder.
