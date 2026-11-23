# Data pipeline

[iteration3_v1](iteration3_v1/README.md) is the current baseline for
development and deployment. It contains the speed, crash, calendar and
notification schema, with eight database tables.

The directory is `iteration3_v1` and the Python package is
`iteration3_v1_pipeline`. The PostgreSQL schema remains `iteration2_v2` so
existing backend queries and notification history continue to use the same tables.
See its [deployment guide](iteration3_v1/deploy/README.md) for operation.

Previous tracked versions are preserved in [archive](../archive/README.md).
