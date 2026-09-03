# Database migrations

How the PostgreSQL schema of the KHI Backend is produced today, how to change it safely, and
how to move onto a real migration tool.

Everything below was verified against the repository and against a live PostgreSQL 18 instance
on 2026-08-26. Where a claim comes from an experiment rather than from a file, the experiment is
described so you can repeat it.

---

## 1. Current state

**This project has no migration tool.**

- [`../../pom.xml`](../../pom.xml) declares neither `org.flywaydb:flyway-core` nor
  `org.liquibase:liquibase-core`. There is no `src/main/resources/db/migration` directory and no
  changelog file.
- [`../../src/main/resources/application.yaml`](../../src/main/resources/application.yaml) sets:

  ```yaml
  spring:
    jpa:
      hibernate:
        ddl-auto: update
  ```

The live schema is therefore whatever Hibernate infers from the JPA entity classes and applies to
the database at application startup. The entity classes are the single source of truth; there is
no versioned record, anywhere, of what the database currently looks like or how it got there.

There is exactly one hand-written SQL file in the repository:
[`../../scripts/sql/2026-08-17-featured-about-service-donation.sql`](../../scripts/sql/2026-08-17-featured-about-service-donation.sql).
It is a repair patch, applied by hand, not a migration in any managed sense. Section 4 covers it.

### Connection details

The datasource is entirely environment-driven:

```yaml
datasource:
  url: jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
  username: ${PGUSER}
  password: ${PGPASSWORD}
```

Five variables, no defaults: `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`. The
application will not start without all five. They are read either from the real process
environment or from a `.env` file in the repository root, loaded by `me.paulschwarz:spring-dotenv`
(version 4.0.0, declared in [`../../pom.xml`](../../pom.xml)). `.env` is listed in
[`../../.gitignore`](../../.gitignore) and is not checked in.

These are the standard libpq variable names, which is convenient: once they are exported, `psql`,
`pg_dump` and `pg_restore` all connect to the same database with no flags at all. Every command in
this document relies on that.

The application also sets `hibernate.jdbc.time_zone: UTC`, so every timestamp Hibernate writes is
UTC regardless of the host clock.

### Tests do not use PostgreSQL

[`../../src/test/resources/application-test.yaml`](../../src/test/resources/application-test.yaml)
points the `test` profile at an in-memory H2 database:

```yaml
datasource:
  url: jdbc:h2:mem:khi_backend_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
jpa:
  hibernate:
    ddl-auto: create-drop
```

`com.h2database:h2` is a `test`-scoped dependency, and twelve test classes activate the profile
with `@ActiveProfiles("test")`. H2's `MODE=PostgreSQL` is a compatibility mode, not PostgreSQL: it
does not implement `jsonb`, `CREATE INDEX CONCURRENTLY`, `pg_trgm`, or PostgreSQL's
`information_schema` in full. Hibernate hides that difference because it emits per-dialect DDL from
the same entity classes. **Hand-written SQL gets no such translation.** This matters twice: for the
patches under `scripts/sql/` today, and — more seriously — the moment migrations start running
inside the test suite. Section 6 says what to do about it.

---

## 2. What `ddl-auto: update` will and will not do

`update` compares the mapping metadata against the live database catalogue and issues only additive
statements. It never issues a destructive one.

| It does | It does not |
| --- | --- |
| Create tables that are missing | Drop a table whose entity was deleted |
| Add columns that are missing | Drop a column whose field was deleted |
| Add missing indexes declared with `@Index` | Drop an index you removed from the mapping |
| Add missing foreign-key and unique constraints (dialect permitting) | Narrow, widen or otherwise change an existing column's type |
| Create missing sequences | Change an existing column's nullability or default |
| | Rename anything — a rename is an add of the new name, and the old column stays behind |
| | Insert, backfill or transform any data |
| | Reorder columns |
| | Apply a set of related changes as one reviewable, revertible unit |

### Practical consequences

**Schema drift between environments is guaranteed, not hypothetical.** Two databases that have
seen different sequences of deploys end up with different schemas, and nothing detects it. Concrete
evidence from this machine: the developer's local `khi_web_db` has 71 tables. A schema generated
fresh from the current entity classes has 83. Eight of the 71 (`about_blocks`, `news_media`,
`project_media`, `project_contents`, `project_content_map_ckb`, `project_content_map_kmr`,
`service_media_collections`, `service_media_files`) correspond to no entity that exists any more —
the javadoc on
[`Project.java`](../../src/main/java/ak/dev/khi_backend/khi_app/model/project/Project.java) says
"the old `project_media` table … dropped" and
[`Service.java`](../../src/main/java/ak/dev/khi_backend/khi_app/model/service/Service.java) says
`service_media_collections` and `service_media_files` "have been removed", yet all three are still
sitting in the database. Meanwhile the whole Site module (`site_settings`, `nav_menu_items`,
`donation_settings`, `contact_messages`, …) and every `featured` / `featured_order` /
`feature_image_url` column are absent from that database entirely.

**Dead columns and tables accumulate silently.** Nothing prunes them. They still consume storage,
still appear in `pg_dump` output, and still confuse anyone reading the live catalogue instead of
the entity classes.

**There is no rollback.** A bad deploy that added a column leaves the column behind after you
redeploy the previous build. If the previous build then reads a row Hibernate cannot map, it fails
at runtime rather than at startup.

**A `NOT NULL` column added to a populated table is not added at all.** Hibernate emits
`alter table … add column … not null` with no default; PostgreSQL rejects it if the table has rows;
Hibernate logs the failure and carries on starting. Verified in the DDL that this project's entities
actually generate:

```sql
create table news (
    featured boolean not null,       -- no default
    ...
create table projects (
    featured boolean not null,       -- no default
```

`about_pages`, `services` and `donation_settings` were given `@ColumnDefault("false")` and generate
`featured boolean default false not null`; `news` and `projects` were not. On a long-lived database
those two columns may simply not exist. Check before relying on them.

**The mirror-image failure has already bitten this project in production.** `services.active`
generates as a *nullable* `boolean` while the Java field is a primitive:

```sql
create table services (
    active boolean,                          -- nullable
    featured boolean default false not null,
```

When `update` adds a nullable column to a populated table, existing rows get `NULL`, and Hibernate
cannot map `NULL` into a primitive `boolean` — every read of the row throws. That is exactly the
outage described in the header of
[`2026-08-17-featured-about-service-donation.sql`](../../scripts/sql/2026-08-17-featured-about-service-donation.sql).
`donation_settings.financial_enabled` and `donation_settings.archive_enabled` still have the same
shape today.

**Column types are frozen once created.** `Instant` fields generate as
`timestamp(6) with time zone` under Hibernate 7.2 with the PostgreSQL dialect (verified in the
generated DDL for `sessions` and `users_tbl`). A database first created by an older Hibernate may
hold plain `timestamp` in those columns, and `update` will never correct it. This becomes visible
the first time you switch to `ddl-auto: validate` — see section 6.

### The urgent one: `ddl-auto` has been committed as `create-drop` six times

`create-drop` drops every table at startup. Tracing the value through the history of
`application.yaml`:

```
2026-04-01  f5f0227  added as: update
2026-04-14  99d0ea8  update      -> create-drop
2026-05-03  441462a  create-drop -> update
2026-05-22  a3b6118  update      -> create-drop
2026-05-22  cf1d7d6  create-drop -> update
2026-05-23  ae6dc7c  update      -> create-drop
2026-05-23  9e3a23f  create-drop -> update
2026-05-24  e11e798  update      -> create-drop
2026-05-24  506fa41  create-drop -> update
2026-06-30  8beb59c  update      -> create-drop
2026-06-30  7feff7e  create-drop -> update
2026-06-30  824d32b  update      -> create-drop
2026-06-30  272f1f2  create-drop -> update
```

Reproduce with:

```bash
git log --format="COMMIT %h %ad" --date=short -p -- src/main/resources/application.yaml \
  | grep -E "^COMMIT|ddl-auto"
```

Six separate commits put `create-drop` into the file that ships to production. Each was reverted,
the last one within the same day, but the window between the two commits is a window in which a
deploy destroys the production database. There is no deployment manifest in the repository —
no `railway.json`, `Dockerfile`, `Procfile` or `nixpacks.toml` — so unless a
`SPRING_JPA_HIBERNATE_DDL_AUTO` variable is set in the Railway dashboard to override it, the value
that reaches production is whatever is committed in `application.yaml`.

**Set `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` (or `none`) as an environment variable on the
production Railway service today.** Spring Boot's relaxed binding maps that variable onto
`spring.jpa.hibernate.ddl-auto`, and an environment variable outranks `application.yaml`. It costs
nothing, needs no code change, and makes the file-level value irrelevant to production. Do it before
anything else in this document.

---

## 3. Making a schema change today

The procedure until Flyway is adopted. It is manual by necessity; the discipline is the only thing
standing in for a tool.

### 1. Edit the entity

Change the JPA class. Everything else follows from it. Two rules learned the hard way:

- If the Java field is a **primitive** `boolean`/`int`/`long`, write
  `@Column(nullable = false)` **and** `@ColumnDefault("false")` (or the appropriate literal).
  Without both, you get one of the two failure modes in section 2.
- If you are renaming, treat it as an add plus a later drop. See the expand/contract pattern in
  section 7.

### 2. Point at a scratch database, never at anything real

```bash
createdb khi_scratch
export PGHOST=localhost PGPORT=5432 PGDATABASE=khi_scratch PGUSER="$(whoami)" PGPASSWORD=
```

If you keep a `.env`, override it on the command line rather than editing it, so you cannot forget
to change it back.

### 3. Capture the SQL Hibernate would emit, and read it

This is the review step that `ddl-auto: update` otherwise skips. Hibernate can write the full
`CREATE` script for the current entity model to a file instead of touching a database:

```bash
./mvnw -DskipTests spring-boot:run -Dspring-boot.run.arguments="\
--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create \
--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/schema-preview.sql \
--spring.jpa.properties.hibernate.hbm2ddl.delimiter=; \
--spring.jpa.properties.hibernate.format_sql=true \
--server.port=0"
```

Then stop the app (`Ctrl-C`) and read `target/schema-preview.sql`.

Three things worth knowing about that command, all verified rather than assumed:

- The property names are `jakarta.persistence.schema-generation.scripts.action` and
  `…scripts.create-target`. These are the Jakarta Persistence names Hibernate 7.2 actually reads
  (`org.hibernate.cfg.SchemaToolingSettings`). There is no `hibernate.hbm2ddl.scripts.action`
  setting; the `javax.persistence.*` spellings still work but are the legacy aliases.
- **The run does not touch the database.** Hibernate's `ActionGrouping.interpret()` falls back to
  `hibernate.hbm2ddl.auto` only when *both* JPA schema-generation actions are unset. Supplying the
  scripts action alone sets the database action to `NONE`, which overrides `ddl-auto: update`.
  Confirmed empirically: the run above produced 47 KB of DDL containing 83 `create table`
  statements and left the target database with zero tables.
- The generated script is a full `CREATE` of the whole model, not a diff against your database.
  It tells you what the entities *say*; comparing it against the live catalogue is still on you.

For the running commentary on what Hibernate does apply, add `--spring.jpa.show-sql=true` to a
normal `ddl-auto: update` run against the scratch database.

### 4. Run normally against the scratch database and inspect the result

```bash
./mvnw -DskipTests spring-boot:run
```

Then:

```bash
psql -c '\d+ services'
psql -c '\d+ news'
```

Check the three things `update` gets wrong: nullability, defaults, and whether a `NOT NULL` column
was skipped entirely. This query lists every nullable boolean, which is the specific hazard in this
codebase:

```sql
SELECT table_name, column_name, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND data_type = 'boolean'
  AND is_nullable = 'YES'
ORDER BY table_name, column_name;
```

Also re-run the check against a *populated* copy, not just an empty scratch database — the failures
in section 2 only appear when the table already has rows.

### 5. Write a hand patch for anything `update` cannot do

Anything in the right-hand column of the table in section 2 needs SQL. Backfills, `SET NOT NULL`,
`SET DEFAULT`, type changes, drops, renames, data corrections. Put it in `scripts/sql/` following
the convention in section 4.

### 6. Back up production before deploying

Custom-format dump, compressed and restorable table by table:

```bash
pg_dump --format=custom --no-owner --no-privileges \
        --file="khi-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

`pg_dump` reads `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD` from the environment, so no
connection flags are needed as long as those five point at production.

A plain-SQL dump, if you would rather be able to read and grep it:

```bash
pg_dump --no-owner --no-privileges \
        --file="khi-$(date -u +%Y%m%dT%H%M%SZ).sql"
```

**A backup you have not restored is not a backup.** Prove it:

```bash
createdb khi_restore_check
pg_restore --dbname=khi_restore_check --no-owner --no-privileges \
           khi-20260826T090000Z.dump
psql -d khi_restore_check -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
dropdb khi_restore_check
```

Use a `pg_dump` at least as new as the server you are dumping. An older `pg_dump` against a newer
server refuses to run.

### 7. Deploy, then apply the patch, then verify

Order matters and depends on the change:

- **Additive change** (new nullable column, new table): deploy first, then apply the patch. The old
  code ignores the new column.
- **Tightening change** (`SET NOT NULL`, new unique constraint): apply the patch *after* the new
  code is live and has stopped writing values that would violate it.
- **Removal**: deploy the code that no longer reads the column, confirm it is healthy, and only
  then drop the column — in a later, separate change.

Verify afterwards with `\d+ <table>` and by exercising the endpoints that read the affected tables.
A read that returns HTTP 500 on every row is the signature of the nullable-primitive problem.

---

## 4. The `scripts/sql/` convention

One file exists:
[`../../scripts/sql/2026-08-17-featured-about-service-donation.sql`](../../scripts/sql/2026-08-17-featured-about-service-donation.sql).

### Naming

```
scripts/sql/YYYY-MM-DD-short-description.sql
```

Date first so an alphabetical listing is also a chronological one. Lower case, hyphen separated,
no spaces.

### What the existing file does

It repairs the damage described in section 2, on three tables. For each of `about_pages`,
`services` and `donation_settings` it:

1. `ADD COLUMN IF NOT EXISTS featured boolean`, `featured_order integer`,
   `feature_image_url text`;
2. `UPDATE … SET featured = false WHERE featured IS NULL` to backfill the rows `update` left
   `NULL`;
3. `ALTER COLUMN featured SET DEFAULT false` then `SET NOT NULL`, so the nullable-primitive
   mapping error cannot recur.

It also adds `service_contents.feature_description varchar(1000)`. The whole thing runs inside
`BEGIN … COMMIT`, and ends with a `SELECT` against `information_schema.columns` that prints the
resulting shape of every column it touched, so you can see whether it worked without writing a
second query.

Its header is worth reading in full: it records *why* the file exists (columns missing on Railway,
producing HTTP 500 on every read) and notes that the SQL alone was insufficient — the `/featured`
endpoint also needed a code fix, because `SELECT DISTINCT` combined with `ORDER BY COALESCE(...)`
is invalid on PostgreSQL. That is the level of context a patch file should carry.

### Rules

- **Idempotent.** `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`,
  `DROP … IF EXISTS`, `INSERT … ON CONFLICT DO NOTHING`, `UPDATE … WHERE col IS NULL`. Assume the
  file will be run twice, because with no tool tracking it, sooner or later it will be.
- **One logical change per file.** A file should be describable in its own filename.
- **A comment header saying why.** What symptom prompted it, which environments it was run against,
  and whether it needs a code deploy alongside it.
- **Wrap it in a transaction** (`BEGIN` / `COMMIT`) unless it contains something PostgreSQL forbids
  inside one, such as `CREATE INDEX CONCURRENTLY`.
- **End with a verification query** that shows the resulting state.
- **Applied manually, in date order.** Nothing applies these automatically. Nothing records that
  they were applied. Whether a given environment has seen a given file is tribal knowledge — which
  is the single strongest argument for section 6.

### Applying one

With the `PG*` variables exported for the target database:

```bash
psql --set=ON_ERROR_STOP=1 -f scripts/sql/2026-08-17-featured-about-service-donation.sql
```

`ON_ERROR_STOP=1` matters: without it `psql` reports an error and keeps going, and a transactional
script will run every remaining statement in a failed transaction before rolling the whole thing
back — with a wall of confusing errors on the way.

Against Railway you can either export the `PG*` variables from the service's Variables tab, or use
the connection string directly:

```bash
psql "$DATABASE_URL" --set=ON_ERROR_STOP=1 \
  -f scripts/sql/2026-08-17-featured-about-service-donation.sql
```

The Railway dashboard's Postgres "Query" tab also works for pasting a script in, which is what the
file's own header suggests.

---

## 5. Seeding

Seed data is **not** migration data. Nothing under `scripts/seed-data/` should ever end up in a
migration file, and the seeding script must never be part of a deploy.

### What exists

- [`../../scripts/seed-about-services.sh`](../../scripts/seed-about-services.sh) — the loader.
- [`../../scripts/seed-data/about.json`](../../scripts/seed-data/about.json) — 3 About pages,
  short set.
- [`../../scripts/seed-data/about-detailed.json`](../../scripts/seed-data/about-detailed.json) —
  7 About pages, long-form institutional set.
- [`../../scripts/seed-data/services.json`](../../scripts/seed-data/services.json) — 8 services.
- [`../../scripts/README.md`](../../scripts/README.md) — the full reference, including the media
  and featured-slide options.

Every entry carries full CKB (Sorani) and KMR (Kurmanji) text and real media URLs from the
project's own S3 bucket.

### How it works

The script writes **through the REST API, not through SQL**. It logs in via
`POST /api/auth/login`, then `POST`s or `PUT`s each record. That is a deliberate choice: slug
validation, the one-row-per-language check and the Tiptap HTML processor all run exactly as they do
in production, so the seeded rows are indistinguishable from rows a person created in the
dashboard. A SQL seed would bypass all of it.

It is an upsert and safe to re-run — About is matched by `slugCkb`, services by `navAnchorId` —
so existing records are updated rather than duplicated, and nothing is deleted.

### Running it

```bash
# local, against a locally running backend; logs in on its own
./scripts/seed-about-services.sh

# one module only
./scripts/seed-about-services.sh about
./scripts/seed-about-services.sh services

# a different backend
BASE=https://blissful-spontaneity-production.up.railway.app ./scripts/seed-about-services.sh

# a different account, or a token you already hold
SEED_USER=someone SEED_PASS=… ./scripts/seed-about-services.sh
TOKEN=<admin jwt>             ./scripts/seed-about-services.sh
```

Requires `curl` and `jq`. The account needs the `ADMIN` role. The script has a committed default
`SEED_USER` / `SEED_PASS` for local convenience; do not create an account with that password
anywhere that matters.

`SeedDataFilesTests` deserialises both JSON files into the request DTOs with a strict Jackson
mapper, so a renamed DTO field breaks the test rather than the seed run.

### These are development fixtures

They describe the Kurdish Heritage Institute's real content, but they are demo data: re-runnable,
disposable, and not versioned against the schema. When Flyway arrives, resist the temptation to
convert them into `R__seed.sql` repeatables. Reference data that the *application* depends on to
function — a required `site_settings` row, a fixed set of lookup values — is a different thing and
does belong in a migration. Content does not.

---

## 6. Recommended: adopt Flyway

### Why Flyway, and why not Liquibase

Liquibase's selling point is a database-agnostic changelog in XML/YAML/JSON that it translates per
dialect. This project gains nothing from that. It targets exactly one database, PostgreSQL, and it
already uses PostgreSQL-specific features that a portable changelog would obstruct: `jsonb` columns
on `about_pages`, `news` and `projects`, and the `pg_trgm` GIN indexes the repositories' javadoc
keeps asking for.

Flyway's default format is plain `.sql` files applied in order. That is precisely what
`scripts/sql/` already is. Adopting Flyway is therefore not a new working practice — it is the
existing practice, with a version table, ordering, checksums and automatic application added. The
files being moved are files people here already know how to write.

The trade-off, stated up front: Flyway Community has no `undo`. Section "Rollback" below covers
what you do instead.

### Dependencies

Spring Boot 4.0.2's parent POM manages Flyway at **11.14.1**, so no `<version>` is needed. Add to
[`../../pom.xml`](../../pom.xml):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

Both are required, for reasons specific to these versions:

- **Spring Boot 4 is modular.** Flyway's auto-configuration is no longer in
  `spring-boot-autoconfigure` — in 4.0.2 that artifact declares a single dependency
  (`spring-boot`) and its configuration metadata contains no `spring.flyway.*` keys at all. The
  auto-configuration lives in `org.springframework.boot:spring-boot-flyway`, which
  `spring-boot-starter-flyway` pulls in along with `flyway-core` and `spring-boot-starter-jdbc`.
  Adding `flyway-core` on its own would give you the library and no Spring integration.
- **Flyway 11 does not ship PostgreSQL support in the core jar.** The plugin registry inside
  `flyway-core-11.14.1.jar` (`META-INF/services/org.flywaydb.core.extensibility.Plugin`) registers
  exactly three database types: H2, SQLite and TestContainers. Without
  `flyway-database-postgresql`, Flyway will not recognise the connection.

If you prefer to avoid the starter, `org.springframework.boot:spring-boot-flyway` plus
`org.flywaydb:flyway-database-postgresql` is the minimal equivalent.

### Configuration

In [`../../src/main/resources/application.yaml`](../../src/main/resources/application.yaml),
change `ddl-auto` and add a `flyway` block:

```yaml
spring:
  jpa:
    open-in-view: false
    show-sql: false
    hibernate:
      ddl-auto: validate      # was: update
    properties:
      hibernate:
        jdbc:
          time_zone: UTC

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 1
    baseline-description: "Schema as generated by ddl-auto before Flyway"
    validate-on-migrate: true
    clean-disabled: true
```

What each setting does here:

- `ddl-auto: validate` — Hibernate stops writing DDL and instead checks, at startup, that the
  database matches the mappings. A mismatch fails the boot. This is the whole point: from now on the
  only thing that changes the schema is a migration file.
- `locations: classpath:db/migration` — the Spring Boot default, stated explicitly so it is
  obvious where migrations live.
- `baseline-on-migrate: true` — when Flyway meets a **non-empty** schema with no
  `flyway_schema_history` table, it writes a baseline row instead of refusing to start. This is what
  lets the existing production database join the scheme without being rebuilt.
- `baseline-version: 1` — the baseline is recorded as version 1, and migrations at or below
  version 1 are skipped. So `V1__baseline.sql` runs on a fresh empty database and is skipped on the
  existing one. Both behaviours are correct, from one configuration.
- `validate-on-migrate: true` — checksums every already-applied migration against the file on
  disk and refuses to run if one was edited. This is what makes "applied migrations are immutable"
  enforceable rather than aspirational.
- `clean-disabled: true` — `flyway clean` drops every object in the schema. Given this project's
  history with `create-drop`, disable it explicitly.

**Also disable Flyway in the test profile.** Add to
[`../../src/test/resources/application-test.yaml`](../../src/test/resources/application-test.yaml):

```yaml
spring:
  flyway:
    enabled: false
  jpa:
    hibernate:
      ddl-auto: create-drop
```

Otherwise the suite will try to run PostgreSQL-specific SQL against H2 and every
`@SpringBootTest` will fail. Keeping H2 with `create-drop` preserves the current fast suite at the
cost of never exercising the migrations. The better answer, when someone has time for it, is
Testcontainers with a real PostgreSQL image and Flyway enabled — that turns the migration files
into something CI actually verifies. Until then, be aware the migrations are untested by the build.

### Bootstrapping `V1__baseline.sql`

The production database already exists. The baseline's job is to describe it, so that a *new*
environment can be built from migrations alone, while the existing one is simply marked as
already there.

**1. Dump the current schema.** Do this against production, not a local database — production is
the schema that has to keep working:

```bash
mkdir -p src/main/resources/db/migration

pg_dump --schema-only --no-owner --no-privileges --schema=public \
        --file=src/main/resources/db/migration/V1__baseline.sql
```

**2. Clean it up.** `pg_dump` output is not a migration; edit it until it is:

- Delete the `\restrict` line at the top and the `\unrestrict` line at the bottom. `pg_dump` 18
  emits these; they are `psql` meta-commands, and Flyway runs SQL through JDBC, where a backslash
  command is a syntax error.
- Delete the `SET` preamble (`SET statement_timeout`, `SET lock_timeout`,
  `SET transaction_timeout`, `SET client_encoding`, `SET row_security`, and the
  `SELECT pg_catalog.set_config('search_path', '', false)` line). Dropping the `search_path` line is
  safe: `pg_dump` writes every object fully qualified as `public.<name>` anyway.
- Delete `CREATE SCHEMA public` / `COMMENT ON SCHEMA public` — the schema already exists.
- Delete any `CREATE EXTENSION` you do not actually need; keep the ones you do, written as
  `CREATE EXTENSION IF NOT EXISTS`.
- Delete `ALTER … OWNER TO` and `GRANT` / `REVOKE` lines if `--no-owner --no-privileges` missed
  any.
- Leave the tables, sequences, defaults, constraints and indexes.

**3. Sanity-check it on an empty database.** This is the step people skip and regret:

```bash
createdb khi_baseline_check
psql -d khi_baseline_check --set=ON_ERROR_STOP=1 \
     -f src/main/resources/db/migration/V1__baseline.sql

# should be the same count as production
psql -d khi_baseline_check -c \
  "SELECT count(*) FROM information_schema.tables
   WHERE table_schema='public' AND table_type='BASE TABLE';"
```

Then start the application against `khi_baseline_check` with `ddl-auto: validate` and Flyway
enabled. If it boots, the baseline is faithful enough for Hibernate. Drop the database afterwards.

**4. Baseline the existing databases.** With `baseline-on-migrate: true` this is automatic: the
first startup after the cutover sees a populated schema with no history table, writes a single
baseline row at version 1, and applies nothing. Confirm it:

```sql
SELECT installed_rank, version, description, type, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;
```

You should see exactly one row, `version = 1`, `type = BASELINE`.

Do this on **every** environment that has a database — production, and each developer's local
database. Given the drift documented in section 2, a stale local database will very likely fail
`ddl-auto: validate` on the first boot. That is the tool working: it is showing you a difference
that has been there, invisible, for months. The fastest fix for a local database is to drop it and
let `V1__baseline.sql` build it fresh.

### Naming and the immutability rule

```
src/main/resources/db/migration/
  V1__baseline.sql
  V2__add_featured_columns_to_news_and_projects.sql
  V3__backfill_featured_defaults.sql
  R__refresh_search_indexes.sql
```

- `V` + version + `__` (**two** underscores) + a `snake_case` description + `.sql`.
- Versions are applied in numeric order. Plain integers are easier to reason about than dotted
  versions; if two people are likely to open a PR the same week, `V20260826_1__…` style timestamps
  avoid collisions.
- The description becomes the row in `flyway_schema_history`. Write it for someone reading that
  table at 3 a.m.
- `R__` marks a **repeatable** migration: no version, re-applied whenever its checksum changes,
  always after the versioned ones. Good for views, functions and triggers. Not for anything that is
  not idempotent.

**An applied migration is immutable.** Once a file has run anywhere it must never be edited — not
to fix a typo in a comment, not to reformat it. Flyway stores a checksum, `validate-on-migrate`
compares it on every startup, and a changed file stops the application from booting. Fix a bad
migration with a new migration.

Corollary: never edit a migration between deploying it to staging and deploying it to production.
If you need to change it, change the version number too, and clean the staging database.

### Worked example: adding a `NOT NULL` column to a populated table

The change that `ddl-auto: update` cannot make at all. Say `news` needs a non-null
`featured boolean default false`. It takes two migrations and two deploys.

**Migration V2 — add it nullable, backfill, set the default.** Cheap, non-blocking, and safe to run
while the old code is live:

```sql
-- V2__add_featured_to_news.sql
--
-- Adds news.featured for the homepage carousel.
-- Nullable for now: the column is tightened in V3, after the code that
-- always writes a value is live. Deploy order is documented in
-- docs/database/MIGRATIONS.md section 6.

ALTER TABLE news ADD COLUMN IF NOT EXISTS featured boolean;

UPDATE news SET featured = false WHERE featured IS NULL;

ALTER TABLE news ALTER COLUMN featured SET DEFAULT false;
```

Adding a nullable column with no default is a catalogue-only change in PostgreSQL — no table
rewrite. Setting the default afterwards is also catalogue-only (PostgreSQL 11+ stores it as a
"missing value" rather than rewriting rows).

**Deploy A.** Ship the application build whose entity has the field mapped as nullable
(`Boolean featured`, `@Column(nullable = true)`) and whose service layer always writes a value. Old
rows are `false`, new rows get an explicit value, and nothing reads a `NULL` into a primitive.

**Migration V3 — tighten it.** Only after Deploy A is live and healthy:

```sql
-- V3__make_news_featured_not_null.sql
--
-- Tightens news.featured now that every write path sets it (Deploy A).
-- The NOT VALID / VALIDATE / SET NOT NULL sequence avoids the
-- ACCESS EXCLUSIVE full-table scan that a bare SET NOT NULL performs.

SET lock_timeout = '3s';

ALTER TABLE news
    ADD CONSTRAINT news_featured_not_null
    CHECK (featured IS NOT NULL) NOT VALID;

ALTER TABLE news VALIDATE CONSTRAINT news_featured_not_null;

ALTER TABLE news ALTER COLUMN featured SET NOT NULL;

ALTER TABLE news DROP CONSTRAINT news_featured_not_null;
```

`ADD CONSTRAINT … NOT VALID` takes a brief lock and does not scan. `VALIDATE CONSTRAINT` scans but
holds only a `SHARE UPDATE EXCLUSIVE` lock, so reads and writes continue. From PostgreSQL 12
onward, `SET NOT NULL` can then use the validated constraint instead of scanning again. On a small
table a bare `ALTER TABLE news ALTER COLUMN featured SET NOT NULL;` is fine; use the longer form
when the table is big enough that a full scan under an exclusive lock would be noticed.

**Deploy B.** Ship the build whose entity declares `boolean featured` with
`@Column(nullable = false)` and `@ColumnDefault("false")`, matching the database. `ddl-auto:
validate` now passes.

The sequencing rule underneath the example: **the database must be compatible with both the old and
the new application build at every instant.** Widen first, deploy, then narrow.

### Rollback

Flyway Community has no `undo` — the `UndoCommandExtensionStub` in `flyway-core` is a placeholder
for the paid feature. Plan accordingly.

- **Forward-only fixes.** A broken V7 is fixed by V8, never by editing V7. This is a discipline, not
  a limitation: a corrective migration is reviewed, versioned and applied everywhere, whereas an
  edit is invisible.
- **Backups are the real rollback.** Take the dump from section 3 immediately before every
  migrating deploy. For anything destructive, take it *and* verify the restore first.
- **Make migrations reversible by construction where you can.** Prefer adding to dropping. Keep the
  drop of a column in its own migration, run days after the code stopped using it, so the window in
  which a rollback needs the column has already closed.
- **Rename with expand/contract** (section 7) rather than `ALTER … RENAME`, which is irreversible in
  practice once new code has written to the new name.
- **If you must roll the application back**, remember the database does not roll back with it. The
  previous build has to tolerate the newer schema. That is only true if you followed the
  widen-first rule.

### Cutover checklist

- [ ] `SPRING_JPA_HIBERNATE_DDL_AUTO` is set as an environment variable on the production service
      (do this first, independently of Flyway).
- [ ] Verified backup of production taken and test-restored.
- [ ] `spring-boot-starter-flyway` and `flyway-database-postgresql` added to `pom.xml`;
      `./mvnw -q -DskipTests package` succeeds.
- [ ] `V1__baseline.sql` present under `src/main/resources/db/migration/`, cleaned of `psql`
      backslash commands, `SET` preamble, ownership and grants.
- [ ] `V1__baseline.sql` applies cleanly to an empty database, and the application boots against it
      with `ddl-auto: validate`.
- [ ] Table count from the baseline matches production.
- [ ] `spring.flyway` block added; `ddl-auto` changed to `validate`; `clean-disabled: true` set.
- [ ] `spring.flyway.enabled: false` and `ddl-auto: create-drop` set in
      `application-test.yaml`; full test suite green.
- [ ] Deployed to production; startup log shows Flyway baselining at version 1 and applying no
      migrations.
- [ ] `SELECT * FROM flyway_schema_history` shows exactly one `BASELINE` row.
- [ ] `ddl-auto: validate` passed — if it failed, the pre-existing drift it found is triaged and
      recorded as a `V2` migration rather than worked around.
- [ ] A smoke-test migration (`V2`, something trivially safe such as a comment on a table) applies
      cleanly on the next deploy, proving the pipeline works before it is needed in anger.
- [ ] `scripts/sql/` given a `README` note saying it is historical, and that new changes go in
      `db/migration`.
- [ ] `CHANGELOG.md` "Known issues" entry about `ddl-auto` removed or rewritten.

---

## 7. Zero-downtime patterns

The site is live and the backend is a single service, so a migration that takes a heavy lock stops
the whole application, not one endpoint. PostgreSQL's `ACCESS EXCLUSIVE` lock queues behind running
queries *and* blocks everything that arrives after it — one slow `ALTER TABLE` behind one long
`SELECT` stalls every request to that table. Always set a guard at the top of a risky migration:

```sql
SET lock_timeout = '3s';
```

The migration then fails fast and is retried, instead of holding the site down while it waits.

### Expand and contract

The general pattern for any change that cannot be made atomically — renames, type changes, splitting
a column, changing a representation. Four migrations, three deploys, and the database is valid for
both application versions throughout.

1. **Expand.** Add the new column alongside the old one. Nullable, no constraints.
2. **Dual-write.** Deploy application code that writes both columns and still reads the old one.
   Backfill the new column from the old in a migration, in batches if the table is large.
3. **Switch reads.** Deploy code that reads the new column and still writes both. Verify.
4. **Contract.** Deploy code that only uses the new column. In a later migration, drop the old
   column and add whatever constraints the new one deserves.

Rename `news.title_ckb` to `news.headline_ckb`, for example:

```sql
-- V10__add_news_headline_ckb.sql   (expand)
ALTER TABLE news ADD COLUMN IF NOT EXISTS headline_ckb varchar(300);

-- V11__backfill_news_headline_ckb.sql
UPDATE news SET headline_ckb = title_ckb WHERE headline_ckb IS NULL;

-- V14__drop_news_title_ckb.sql     (contract — days later)
ALTER TABLE news DROP COLUMN IF EXISTS title_ckb;
```

Never `ALTER TABLE news RENAME COLUMN title_ckb TO headline_ckb`. It is instantaneous, which is the
trap: the instant it commits, every running instance of the old build starts throwing.

The same shape handles a type change: add `amount_new numeric`, backfill, switch, drop `amount`,
rename `amount_new` to `amount` in a final migration once nothing reads either.

### Indexes

An ordinary `CREATE INDEX` holds a lock that blocks writes for the whole build. Use the concurrent
form:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sessions_user_active
    ON sessions (user_id, is_active);
```

Two constraints come with it:

- **It cannot run inside a transaction.** Flyway wraps each PostgreSQL migration in one by default,
  so you must opt out per migration with a sidecar configuration file next to the SQL:

  ```
  src/main/resources/db/migration/V12__index_sessions_user_active.sql
  src/main/resources/db/migration/V12__index_sessions_user_active.sql.conf
  ```

  with the `.conf` containing:

  ```properties
  executeInTransaction=false
  ```

- **It can fail and leave an invalid index behind.** Check afterwards, and drop and retry if needed:

  ```sql
  SELECT c.relname
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  WHERE NOT i.indisvalid;
  ```

  ```sql
  DROP INDEX CONCURRENTLY IF EXISTS idx_sessions_user_active;
  ```

Put one concurrent index per migration. If the second one in a file fails, the first has already
been created outside a transaction and the file is no longer safely re-runnable.

Two indexes this schema is known to want, if you are looking for a first real migration:
`sessions (user_id, is_active)` — PostgreSQL does not index foreign keys automatically, and every
session listing and every account deletion currently sequential-scans that table — and `pg_trgm` GIN
indexes on the tag and keyword side tables, which is the only index shape that can serve the
leading-wildcard `LIKE '%x%'` searches the repositories perform.

### Cheap versus expensive, on PostgreSQL

| Cheap (catalogue only) | Expensive (rewrite or full scan) |
| --- | --- |
| `ADD COLUMN` nullable, no default | `ADD COLUMN … NOT NULL` with no default (fails outright if rows exist) |
| `ADD COLUMN … DEFAULT x` (PG 11+) | Changing a column's type in most directions |
| `DROP COLUMN` | `SET NOT NULL` without a pre-validated `CHECK` |
| `ALTER … SET DEFAULT` / `DROP DEFAULT` | `ADD CONSTRAINT` without `NOT VALID` |
| `RENAME` (instant, but breaks running code) | `CREATE INDEX` without `CONCURRENTLY` |
| `varchar(n)` widened to a larger `n`, or to `text` | `varchar(n)` narrowed |

Widening a `varchar` is free; narrowing rewrites the table and can fail on existing data. Relevant
here, because the tag columns vary in width across publishment types (60, 80 and 100 characters) and
any consolidation should widen, never narrow.

---

## 8. Environments

### Local

PostgreSQL runs on the developer's machine. Configuration comes from a `.env` file in the
repository root, read by `spring-dotenv`. It is gitignored and not checked in, so a new machine
needs one created by hand:

```bash
cat > .env <<'EOF'
PGHOST=localhost
PGPORT=5432
PGDATABASE=khi_web_db
PGUSER=your_local_user
PGPASSWORD=
JWT_SECRET=local-only-secret-long-enough-for-hmac-sha256-signing
JWT_EXPIRATION_MS=86400000
JWT_COOKIE_NAME=khi_auth
JWT_COOKIE_SECURE=false
JWT_COOKIE_HTTP_ONLY=true
JWT_COOKIE_SAME_SITE=Lax
JWT_COOKIE_PATH=/
JWT_COOKIE_MAX_AGE=86400
REDIS_HOST=localhost
REDIS_PORT=6379
EOF
```

`.env` must stay untracked. Exporting the same five `PG*` variables in your shell also gives `psql`,
`pg_dump` and `pg_restore` the same target with no flags:

```bash
export PGHOST=localhost PGPORT=5432 PGDATABASE=khi_web_db PGUSER="$(whoami)" PGPASSWORD=
psql -c '\dt'
```

Since 0.5.0, Redis is on the request path, so `REDIS_HOST` / `REDIS_PORT` need to be reachable too —
Lettuce connects lazily, so the application starts without Redis and then fails on the first cached
read.

Expect local databases to be stale. Given the drift in section 2, the safest local reset is to drop
and rebuild rather than to patch:

```bash
dropdb khi_web_db && createdb khi_web_db && ./mvnw -DskipTests spring-boot:run
```

Then re-seed with [`../../scripts/seed-about-services.sh`](../../scripts/seed-about-services.sh).

### Production (Railway)

The backend and its PostgreSQL database are both Railway services. There is no deployment manifest
in the repository — no `Dockerfile`, `railway.json`, `Procfile` or `nixpacks.toml` — so Railway
detects the Maven project and builds it itself.

The `PG*` variables come from the Railway dashboard, on the backend service's Variables tab,
normally as references to the Postgres service's own variables so they track it automatically. The
Postgres service also exposes `DATABASE_URL`, which the application does **not** read (the
`application.yaml` datasource is composed from the five `PG*` variables) but which is the most
convenient thing to hand to `psql` and `pg_dump`.

To run SQL or take a backup against production, copy the connection values from that tab, or use
Railway's own Postgres "Query" tab for one-off statements.

### Never point `ddl-auto: update` at production

Once Flyway is in place, production must run `spring.jpa.hibernate.ddl-auto=validate` — or `none` —
and nothing else. Two independent reasons:

- **`update` and Flyway will fight.** Hibernate would add a column that no migration created,
  Flyway's history would not know about it, and the next environment built from migrations alone
  would silently differ. The whole benefit of the tool is that the migration files are the complete
  and only description of the schema.
- **`create-drop` is one typo away.** As traced in section 2, that value has been committed to
  `application.yaml` on six separate occasions. `validate` fails a deploy loudly; `create-drop`
  succeeds silently and takes the data with it.

Enforce it where a file edit cannot reach it: set `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` as an
environment variable on the production Railway service. Spring Boot's relaxed binding maps it onto
`spring.jpa.hibernate.ddl-auto`, and environment variables take precedence over `application.yaml`.
This is worth doing **today**, before any of the rest of section 6, because it costs one dashboard
edit and closes the largest hole in the current setup.

---

## Related files

| Path | What it is |
| --- | --- |
| [`../../pom.xml`](../../pom.xml) | Dependencies. No Flyway, no Liquibase today. |
| [`../../src/main/resources/application.yaml`](../../src/main/resources/application.yaml) | `ddl-auto: update`, the `PG*` datasource, `hibernate.jdbc.time_zone: UTC`. |
| [`../../src/test/resources/application-test.yaml`](../../src/test/resources/application-test.yaml) | H2 in-memory, `ddl-auto: create-drop`, `test` profile. |
| [`../../scripts/sql/2026-08-17-featured-about-service-donation.sql`](../../scripts/sql/2026-08-17-featured-about-service-donation.sql) | The only hand-written patch in the repository. |
| [`../../scripts/README.md`](../../scripts/README.md) | Full documentation for the seed tooling. |
| [`../../scripts/seed-about-services.sh`](../../scripts/seed-about-services.sh) | API-driven upsert seeder for About and Services. |
| [`../../CHANGELOG.md`](../../CHANGELOG.md) | Release history, including the "Known issues" entry about `ddl-auto`. |
