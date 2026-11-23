"""Build and load Victoria speed, calendar and crash datasets.

The data pipeline downloads source snapshots, prepares speed rules, verified
yearly calendars and fixed crash clusters, and loads them into the iteration2_v2
PostgreSQL schema. Calendar publication updates one year independently of roads
and rules; source evidence and verified coverage are stored with that version.
The backend implements request-time searches against those loaded tables.
This package does not run an HTTP service or send notifications.
"""

# Python loads this file when importing the package. Keep initialization empty
# so importing a helper does not start downloads, builds or database writes.
# The command-line entry point lives in __main__.py and selects a single stage.
