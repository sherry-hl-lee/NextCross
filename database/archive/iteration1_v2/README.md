# Data pipeline - Iteration 1 v2

Iteration 1 v2 is a small Speed Zones pipeline for one backend operation:
given a GPS position, return the speed of the nearest normally operating road
within a search radius. It also creates the agreed notification delivery-state
table. The HTTP API remains a backend responsibility.

The pipeline is self-contained under this directory and follows this flow:

```text
Transport Victoria CKAN
    -> data/raw/speed-zones.geojson
    -> ijson streaming filter
    -> transactional TRUNCATE + COPY
    -> PostGIS 1 m simplification trigger
    -> iteration1_v2.road_speed
```

## Stored contract

The migration creates PostGIS, schema `iteration1_v2`, and two tables:

```sql
CREATE TABLE iteration1_v2.road_speed (
    source_speed_zone_id uuid PRIMARY KEY,
    speed_kmh smallint NOT NULL CHECK (speed_kmh BETWEEN 1 AND 200),
    road_geom geography(MultiLineString, 4326) NOT NULL
);

CREATE TABLE iteration1_v2.notification_event (
    trip_id text NOT NULL,
    event_id text NOT NULL,
    occurred_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'sent', 'failed')),
    resend_email_id text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at timestamptz,
    PRIMARY KEY (trip_id, event_id)
);
```

`road_speed` also has a nonempty-geometry check and a GiST geography index.
Every inserted or updated road geometry is transformed from EPSG:4326 to metric
EPSG:7899, simplified with `ST_SimplifyPreserveTopology(..., 1.0)`, checked for
an empty or invalid result, and transformed back to EPSG:4326. Thus every road
loaded by this version uses the same one-metre simplification. A source
LineString is wrapped as a one-part MultiLineString rather than split into
component rows.

The audited August 2026 source contained 471,572 features and produced 462,627
stored roads. These figures describe that release; a future source may differ.

## Layout

```text
iteration1_v2/
├── .env.example
├── .gitignore
├── README.md
├── requirements.txt
├── data/raw/.gitignore
├── postgresql/
│   ├── migrations/001_initial_schema.sql
│   └── queries/nearest_speed.sql
├── src/iteration1_v2_pipeline/
│   ├── __init__.py
│   ├── __main__.py
│   ├── settings.py
│   ├── download.py
│   ├── speed_zones.py
│   ├── validate.py
│   ├── migrate.py
│   ├── load.py
│   └── lookup.py
└── tests/test_iteration1_v2_pipeline.py
```

There is no resource configuration file. The CKAN endpoint, raw path, static
condition, and migration path are fixed in `settings.py`; the geometry
tolerance is fixed in the SQL migration.

## Local use

Create an environment and install the three runtime dependencies:

```bash
python3 -m venv data-pipeline/iteration1_v2/.venv
source data-pipeline/iteration1_v2/.venv/bin/activate
python -m pip install -r data-pipeline/iteration1_v2/requirements.txt
```

Download one current, plain GeoJSON file and inspect its counts:

```bash
PYTHONPATH=data-pipeline/iteration1_v2/src \
python -m iteration1_v2_pipeline download

PYTHONPATH=data-pipeline/iteration1_v2/src \
python -m iteration1_v2_pipeline validate
```

`validate` prints compact JSON containing `seen`, `accepted`, and `skipped`.
It does not connect to PostgreSQL and does not accept expected-count options.

For database commands, copy `.env.example` to an ignored local file, insert a
Neon Direct owner URL, and load it as `DATABASE_URL`. Then run:

```bash
PYTHONPATH=data-pipeline/iteration1_v2/src \
python -m iteration1_v2_pipeline migrate

PYTHONPATH=data-pipeline/iteration1_v2/src \
python -m iteration1_v2_pipeline load --expect-accepted 462627
```

The loader reads the GeoJSON exactly once while streaming rows through COPY.
It performs `TRUNCATE`, COPY, the expected-accepted comparison, and `ANALYZE`
inside one transaction. A parse, constraint, COPY, or count mismatch rolls back
the road-table replacement. `--expect-accepted` must be a positive integer when
provided. It is optional for direct local use but mandatory in the production
workflow.

Run the reference lookup with either `RUNTIME_DATABASE_URL` or `DATABASE_URL`:

```bash
PYTHONPATH=data-pipeline/iteration1_v2/src \
python -m iteration1_v2_pipeline lookup \
  --latitude -37.8136 \
  --longitude 144.9631 \
  --radius-m 30
```

The result is `{"speedKmh":60}`-shaped JSON or `{"speedKmh":null}` when no
stored road is within range. The reusable named-parameter SQL is in
`postgresql/queries/nearest_speed.sql`. Distance is the first ordering key and
source UUID is the deterministic tie-break.

Use the Direct owner credential only for migration and loading. A backend
should use a separate least-privilege pooled role for road reads and a separate
worker role if it writes notification state. Never put a database credential in
Android code or Git.

## GitHub Actions deployment

`.github/workflows/iteration1-v2-neon.yml` is manual-only and preserves two
modes:

- `validate-only` installs dependencies, runs tests, downloads the latest plain
  GeoJSON, and reports current counts without receiving a database secret;
- `deploy` runs only from `main`, uses the protected `neon-production`
  environment, requires confirmation text `DEPLOY`, and requires a positive
  `expected_accepted` value copied from a reviewed validate-only run.

Deploy installs dependencies, runs tests, downloads again, applies the schema,
and invokes `load --expect-accepted` once. There is no separate deploy-time
validation pass. If the source changed between the two workflow runs and the
accepted count also changed, the load mismatch rolls back the table
replacement. A same-count content change is not detected by this lightweight
guard. Schema changes applied by the preceding migration are a separate
committed transaction.

After loading, the workflow runs a nearest-speed smoke test. Configure this
GitHub Environment secret before deploying:

```text
Environment: neon-production
Secret:      NEON_DIRECT_DATABASE_URL
Value:       complete Neon Direct owner URL, including its password
```

The old `expected_seen` input no longer exists. Only accepted rows determine
the deployment guard because the loader can check that value during its single
streaming pass.

## Legacy raw-data incompatibility

This version does not read the former gzip snapshot layout. Files such as
`current.json`, snapshot manifests, page metadata, checksums, and
`*.geojson.gz` are ignored by the new commands. A checkout upgraded from the
older pipeline must run `download` to create:

```text
data-pipeline/iteration1_v2/data/raw/speed-zones.geojson
```

Leaving old ignored snapshot files on disk does not make them inputs. Do not
point `--input` at a manifest or gzip file. The new `--output` and `--input`
options accept a plain GeoJSON path when an explicit local path is needed.

## Deliberate simplifications

The following removals are intentional:

- `ijson` replaces the custom byte-level GeoJSON parser.
- Raw storage is one plain file, not compressed snapshots or pages, so local
  disk usage is larger than the former gzip layout.
- There are no manifests, checksums, source provenance records, snapshot
  history, retry, resume, or download deduplication.
- An atomic `.part` rename protects against an interrupted download, but an HTTP
  success can replace the prior raw file before its content is parsed. Content
  type, content length, source count, and GeoJSON structure are not prechecked.
- CKAN selection uses a small active/GeoJSON filter and published timestamp
  strings. It does not perform the former host, metadata-schema, or resource
  identity validation.
- Validation checks that at least one row is usable and reports only aggregate
  counts. It has no expected source count, minimum acceptance percentage, or
  reason-specific skip counters.
- Load has no preflight pass, snapshot verification, whole-input hash, second
  parse, post-COPY count query, table-size report, or shadow-table swap.
- Local load without `--expect-accepted` can replace the table with any nonempty
  accepted set. Production compensates with its required accepted-count guard.
- Migration applies idempotent SQL without exhaustive PostgreSQL catalog-drift
  checks and does not automatically repair every possible pre-existing drift.
- The client does not validate Neon URL structure or reject pooler hostnames;
  supplying the documented Direct URL is an operator responsibility.
- Duplicate UUIDs and database-invalid geometry abort the transaction instead
  of being deduplicated or repaired.
- The raw filter keeps only exact `Normal Operation`, whole-number speeds from
  1 through 200, UUID identifiers, and basic valid WGS 84 line coordinates.
  Conditional, school-time, activated, and variable limits are not modeled.
- Geometry simplification is a fixed one-metre database trigger rather than a
  configurable policy. It reduces storage but consumes database CPU, lengthens
  the table lock, and can alter extremely close nearest-road ties.
- Tests use fakes instead of a live CKAN service or PostGIS instance. Database
  integration errors are therefore discovered during migration or deployment.
- The workflow keeps no raw artifact, database backup, or release history. It
  has no schedule, and official actions use moving major-version tags.

The direct `TRUNCATE + COPY` transaction keeps memory and permanent table count
small, but it holds an exclusive table lock for the load. A failed transaction
restores the prior table; a successful but logically wrong load has no pipeline
snapshot to restore. Keep an external recovery option and use a maintenance
window for refreshes.

## Tests

```bash
PYTHONPATH=data-pipeline/iteration1_v2/src \
python -m unittest discover -s data-pipeline/iteration1_v2/tests -v
```
