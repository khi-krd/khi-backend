# KHI Backend — Documentation

Reference documentation for the KHI Backend: a Spring Boot 4.0.2 / Java 21 REST API backed by
PostgreSQL, Redis and AWS S3, serving the public KHI website and the admin dashboard.

**Verified against source:** 2026-08-26 · 173 endpoints across 18 controllers · 83 database tables · 70 diagrams

---

## How this documentation is organised

Everything is split by **who is allowed to call it**. That single rule decides which folder a
document lives in, and it is the first thing to understand before reading anything else.

| Folder | Audience | Auth | Contents |
|--------|----------|------|----------|
| [`external/`](external/) | Public website, anonymous visitors | None | 80 endpoints that `SecurityConfig` marks `permitAll()` |
| [`internal/`](internal/) | Admin dashboard, staff tooling | JWT required | 91 endpoints behind `authenticated()` or a role check |
| [`database/`](database/) | Backend developers, DBAs | — | Schema, ERD, field reference, migration guide, full DDL |
| [`diagrams/`](diagrams/) | Everyone | — | 70 Mermaid diagrams plus 65 SVG exports: ER, UML class/sequence/state/component, flowcharts |
| [`TECHNOLOGY_STACK.md`](TECHNOLOGY_STACK.md) | Everyone | — | Every technology in this backend, what it does here and why it was chosen — plus the frontend stacks it serves |
| [`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md) | Full-stack developers, operators | — | How this backend connects to the `khi-webiste` public site and the `khi-dashboard` admin app: wiring, CORS, auth, env vars, local setup |
| [`_archive/`](_archive/) | — | — | Superseded documentation, kept for reference only |

An endpoint appears in **exactly one** of `external/` or `internal/`. Where a controller mixes
public reads with admin writes — which most of them do — the reads are documented in `external/`
and the writes in `internal/`, and the two files cross-link.

> `_archive/` is frozen. It holds the documentation that existed before 2026-08-26 and is not
> maintained. Do not cite it; it disagrees with the current code in several places.

---

## Directory map

```
docs/
├── README.md                  ← you are here
├── TECHNOLOGY_STACK.md        Every technology used, and why
├── FRONTEND_INTEGRATION.md    Backend ↔ frontend seam: the two frontends, CORS, auth, local setup
├── external/                  Public API — no authentication
│   ├── README.md
│   └── 14 domain documents
├── internal/                  Admin API — JWT required
│   ├── README.md
│   └── 15 domain documents
├── database/                  Data layer
│   ├── README.md
│   ├── SCHEMA.md              every table, every column
│   ├── ERD.md                 14 Mermaid entity-relationship diagrams
│   ├── FIELDS.md              the fields people get wrong, explained
│   ├── MIGRATIONS.md          how schema changes happen, and how to adopt Flyway
│   └── schema.sql             full PostgreSQL DDL, generated from the entities
├── diagrams/                  Visual reference — 70 diagrams
│   ├── README.md
│   ├── ER.md                  conceptual model and the shared aggregate shape
│   ├── UML_CLASS.md           types, inheritance, the exception tree
│   ├── UML_SEQUENCE.md        who calls what, in order
│   ├── UML_STATE.md           lifecycles and their real transitions
│   ├── UML_COMPONENT.md       components, filter chain, deployment
│   ├── FLOWCHARTS.md          decision paths through the code
│   └── *.svg                  the same 65 diagrams as standalone SVG files
└── _archive/                  superseded, frozen
```

Release history lives in [`../CHANGELOG.md`](../CHANGELOG.md).

---

## Start here

**Building the public website?** Read [`external/README.md`](external/README.md). You will not
need a token for anything in that folder.

**Building the admin dashboard?** Read [`internal/README.md`](internal/README.md), starting with
[`internal/AUTH_SESSIONS_API.md`](internal/AUTH_SESSIONS_API.md) for the login and session model.
Get a token first via [`external/AUTH_API.md`](external/AUTH_API.md).

**Joining the project, or wondering why a library is here?** Read
[`TECHNOLOGY_STACK.md`](TECHNOLOGY_STACK.md) — the full inventory, layer by layer, including the
dependencies that are declared but never used.

**Wiring the backend to a frontend, or setting the stack up on a new machine?** Read
[`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md) — the public website and the admin dashboard
connect in two completely different ways, and that document is the only place that explains both.

**New to the codebase?** Start at [`diagrams/README.md`](diagrams/README.md) — the conceptual
model and the request lifecycle will orient you faster than any endpoint table.

**Changing an entity or writing a query?** Read [`database/README.md`](database/README.md), and
read [`database/MIGRATIONS.md`](database/MIGRATIONS.md) *before* you edit an entity class — this
project has no migration tool and the schema is applied by Hibernate at startup.

---

## Platform facts that apply everywhere

| | |
|---|---|
| **Framework** | Spring Boot 4.0.2, Java 21 |
| **Database** | PostgreSQL (connection via `PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER` / `PGPASSWORD`) |
| **Cache** | Redis, key prefix `khi:`, default TTL 10 minutes |
| **File storage** | AWS S3, region `us-east-1`, bucket `s3-khiwebsite`, base folder `khi-web-folders` |
| **Auth** | JWT — `Authorization: Bearer <token>` header or the HttpOnly cookie named by `JWT_COOKIE_NAME` |
| **Roles** | `GUEST` → `EMPLOYEE` → `ADMIN` → `SUPER_ADMIN` |
| **Languages** | Bilingual content keyed by `Language` enum: `CKB` (Sorani), `KMR` (Kurmanji) |
| **Upload limits** | 1 GB per file, 1 GB per request |
| **Timestamps** | Stored UTC, serialised in `Asia/Baghdad`. Dates `yyyy-MM-dd`, date-times `yyyy-MM-dd HH:mm:ss` |
| **Live spec** | Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups: `public`, `internal`, `all`) |

### The error envelope

Every error from the `khi_app` package returns the same shape, so handle it once:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/news/42",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NEWS_NOT_FOUND",
  "message": "هەواڵەکە نەدۆزرایەوە",
  "messageEn": "News not found",
  "messageKu": "Nûçe nehat dîtin",
  "fieldErrors": null,
  "details": null
}
```

`message` follows the request's `Accept-Language`; `messageEn` and `messageKu` are always present.
Log the `traceId` — it correlates to the server-side request log.

> **The `user` package does not always use this envelope**, but not for the reason you might guess.
> There is only **one** `@RestControllerAdvice` in the application —
> `khi_app.exceptions.GlobalExceptionHandler`. The class at
> `user/exceptions/GlobalExceptionHandler.java` is an intentionally empty placeholder (a second
> advice bean with the same simple name would fail startup), so user and auth exceptions are
> handled by the `khi_app` advice too. What *does* bypass the envelope is
> `JWTAuthenticationFilter`: it runs before the advice can see the request and writes its own
> JSON — `{"error":"TOKEN_EXPIRED"}`, `{"error":"TOKEN_REVOKED"}`, `{"error":"INVALID_TOKEN"}`.
> `SecurityConfig` never calls `.exceptionHandling(...)`, so the `JwtAuthenticationEntryPoint` and
> `JwtAccessDeniedHandler` beans are never registered and never run; a request with no token at all
> gets Spring Security's default. See [`external/AUTH_API.md`](external/AUTH_API.md) and
> [`diagrams/FLOWCHARTS.md`](diagrams/FLOWCHARTS.md#error-mapping).

---

## Conventions used in these documents

- Every document opens with a fact table naming the controller and service class it was written
  from, so you can go straight to the source.
- Field tables give the real `jakarta.validation` constraints from the DTO, not a paraphrase.
- Sample payloads use realistic bilingual content and plausible S3 URLs.
- A `> **Note:**` callout marks a genuine quirk in the code — a duplicate route, a mismatch between
  `SecurityConfig` and `@PreAuthorize`, or behaviour that will surprise you. These describe the code
  as it is, not as it should be.
- Physical column names in `database/` marked `†` were derived from Hibernate's naming strategy
  rather than declared explicitly in the entity.

---

## Keeping this accurate

These documents were written directly from the source and verified endpoint-by-endpoint against
the controllers. When you change an API:

1. Update the domain document in `external/` or `internal/` — whichever side the endpoint belongs to.
2. If the auth level changed, the endpoint may need to **move between folders**. That is the one
   edit that is easy to forget and most damaging to get wrong.
3. If an entity changed, update [`database/SCHEMA.md`](database/SCHEMA.md) and, if a relationship
   changed, [`database/ERD.md`](database/ERD.md).
4. Add an entry to [`../CHANGELOG.md`](../CHANGELOG.md) under `## [Unreleased]`.
