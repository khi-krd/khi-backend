# Database Documentation

The KHI Backend stores everything in **PostgreSQL**, accessed through Spring Data JPA / Hibernate.
This folder describes that schema: what the tables are, how they relate, what the fields mean, and
how schema changes get applied.

**84 tables · 72 relationships · 38 enum-backed columns · verified against source 2026-09-03**

---

## Read this first

There is **no migration tool** in this project. `pom.xml` contains neither Flyway nor Liquibase,
and `application.yaml` sets:

```yaml
spring.jpa.hibernate.ddl-auto: update
```

So the live schema is whatever Hibernate infers from the entity classes and applies at startup.
**The entity classes are the source of truth, not a SQL file.** Before you edit an entity, read
[`MIGRATIONS.md`](MIGRATIONS.md) — it explains what `update` will and will not do to a live
database, and it will save you from a surprise in production.

---

## What is in this folder

| Document | Read it when |
|----------|--------------|
| [`SCHEMA.md`](SCHEMA.md) | You need the definitive column list for a table — types, nullability, keys, constraints, indexes. Every table, mechanically complete |
| [`ERD.md`](ERD.md) | You need to see how tables connect. 14 Mermaid diagrams: one system overview plus one per domain, rendered inline by GitHub |
| [`FIELDS.md`](FIELDS.md) | You need to know what a field *means*. Slugs, the bilingual pattern, Tiptap bodies, S3 URLs, flags, ordering, audit columns, personal data. The fields people get wrong |
| [`MIGRATIONS.md`](MIGRATIONS.md) | You are about to change the schema, or you want to adopt Flyway properly |
| [`schema.sql`](schema.sql) | You need executable DDL — provisioning a fresh database, or diffing one that has drifted. 84 tables, generated from the entities by [`../../scripts/render-schema.sh`](../../scripts/render-schema.sh); nothing runs it at startup |

`SCHEMA.md` is breadth; `FIELDS.md` is depth. If you are new to the codebase, read `ERD.md` then
`FIELDS.md`, and treat `SCHEMA.md` as a lookup table.

---

## How the schema is shaped

A few patterns repeat across almost every domain. Learn them once and most of the schema becomes
predictable.

**Bilingual content is embedded, not joined.** Classes like `NewsContent`, `AboutContent`,
`VideoContent` and `WritingContent` are `@Embeddable`, flattened twice into their owning table with
`@AttributeOverrides` — producing `title_ckb` / `title_kmr` column pairs rather than a translations
table. There is no `news_content` table. `FIELDS.md` explains the consequences for querying.

**Some collections are JSON, not tables.** `News.mediaGallery` and `Project.mediaGallery` persist
as `jsonb` columns via `@JdbcTypeCode(SqlTypes.JSON)`. Individual gallery entries have no primary
key and cannot be constrained or indexed from SQL. Same for `About.stats`.

**Audit columns come from a mapped superclass.** `AuditableEntity` supplies `created_at`,
`updated_at`, `created_by` and `updated_by`, populated by `AuditorAwareImpl`. Not every entity
extends it — `SCHEMA.md` lists which do.

**The featured rail is polymorphic.** `featured_items` points at rows in many different tables.
Check `ERD.md` for whether that reference is enforced by the database or only by application code
before you rely on it.

**The users table is called `users_tbl`.** Not `users`. It is declared explicitly via
`@Table(name = "users_tbl")`. This catches people out.

Physical names marked `†` in these documents were **derived** from Hibernate's
`SpringPhysicalNamingStrategy` rather than declared in the entity. The distinction matters: a
derived name can shift if the field is renamed.

---

## Connecting

The datasource is environment-driven, loaded via `spring-dotenv`:

```bash
PGHOST=localhost
PGPORT=5432
PGDATABASE=khi
PGUSER=khi_app
PGPASSWORD=...
```

```bash
psql "postgresql://$PGUSER:$PGPASSWORD@$PGHOST:$PGPORT/$PGDATABASE"
```

Tests run against **H2**, not PostgreSQL. Dialect differences are real — see
[`MIGRATIONS.md`](MIGRATIONS.md) before trusting a test to validate hand-written SQL.

---

## Related

- [`../diagrams/ER.md`](../diagrams/ER.md) — conceptual ER views and the aggregate template
- [`../external/README.md`](../external/README.md) — the public API over this data
- [`../internal/README.md`](../internal/README.md) — the admin API that writes this data
- [`../../CHANGELOG.md`](../../CHANGELOG.md) — when schema changes shipped
- [`../../scripts/sql/`](../../scripts/sql/) — hand-written SQL patches
