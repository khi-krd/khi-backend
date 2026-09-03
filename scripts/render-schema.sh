#!/usr/bin/env bash
#
# Regenerate docs/database/schema.sql — the full PostgreSQL DDL for this project.
#
# The JPA entity classes are the source of truth. This script compiles them, asks
# Hibernate for the DDL it derives under the PostgreSQL dialect with Spring Boot's
# naming strategies, and writes a formatted script. No database is contacted.
#
# Usage:  ./scripts/render-schema.sh
# Needs:  JDK 21+ and Maven. Runs offline against the local ~/.m2 cache.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="docs/database/schema.sql"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Compiling entities and resolving classpath..."
mvn -q -o compile dependency:build-classpath \
    -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=runtime

CP="target/classes:$(cat "$WORK/cp.txt")"

echo "Generating DDL from entity metadata..."
javac -cp "$CP" -d "$WORK" scripts/schema-gen/GenSchema.java
java -cp "$WORK:$CP" GenSchema target/classes "$WORK/raw.sql"

echo "Formatting..."
python3 scripts/schema-gen/format_schema.py "$WORK/raw.sql" "$OUT"

echo "Done. $(grep -c '^create table' "$WORK/raw.sql") tables -> $OUT"
