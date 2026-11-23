"""Define paths and source conventions shared within this iteration.

Paths are resolved from this file, so the folder can be moved without reading
another iteration's files. RAW_DIR and PROCESSED_DIR hold pipeline artifacts.
Defining these paths does not create directories or open files. DIRECTIONS is used to
interpret road and sign orientation during the speed build.
Connection URLs are selected by the CLI or supplied directly to functions,
not stored here. All heading values are degrees clockwise from north.
"""

from pathlib import Path

# Resolve paths from this source file, not the terminal's current directory.
# parents[2] climbs past the package and src directories to iteration3_v1.
ITERATION_DIR = Path(__file__).resolve().parents[2]
# Download stages write raw snapshots here; build stages read them unchanged.
RAW_DIR = ITERATION_DIR / "data/raw"
# Build stages write CSVs/reports here; load stages use these prepared artifacts.
PROCESSED_DIR = ITERATION_DIR / "data/processed"
# Match the source dataset's label exactly when selecting ordinary speed zones.
NORMAL_OPERATION = "Normal Operation"
# Convert the eight compass labels to clockwise headings: North=0, East=90,
# South=180 and West=270. Each successive label adds 45 degrees.
DIRECTIONS = {name: i * 45 for i, name in enumerate(
    ("North", "North East", "East", "South East", "South", "South West", "West", "North West")
)}
