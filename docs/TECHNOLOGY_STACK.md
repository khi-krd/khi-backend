# Technology Stack

> **Audience:** anyone joining the project, evaluating it, or deciding whether to add a dependency ·
> **Scope:** every technology used by `khi_backend`, what it does here, and why it was chosen ·
> **Sources read:** `pom.xml`, `src/main/resources/application.yaml`, every class in
> `khi_app/config/` and `user/configs/`, `user/jwt/JwtTokenProvider.java`, `scripts/README.md`,
> plus an import-level audit of the whole `src/main/java` tree ·
> **Verified:** 2026-09-01

This is the *what and why*. For how the pieces connect to the frontends see
[`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md); for endpoint behaviour see
[`external/`](external/) and [`internal/`](internal/); for the data model see
[`database/`](database/).

---

## 1. At a glance

| Layer | Technology | Version | Role in this project |
|---|---|---|---|
| Language | Java | 21 (target) | records, sealed types, pattern matching, virtual-thread-ready |
| Framework | Spring Boot | 4.0.2 | the whole application container |
| Web | Spring MVC (`spring-boot-starter-webmvc`) | Boot-managed | 18 controllers, ~171 request mappings |
| JSON | Jackson (`databind` + `datatype-jsr310`) | 2.21.0 | serialization, `java.time`, strict unknown-field policy |
| Validation | Hibernate Validator (`starter-validation`) | Boot-managed | `@Valid` on every request DTO |
| Persistence | Spring Data JPA + Hibernate ORM | Boot-managed | 52 `@Entity` classes → 83 tables |
| Database | PostgreSQL | 18 locally, Railway in production | the only datastore of record |
| Cache | Redis + Lettuce (`starter-data-redis`, `starter-cache`) | Boot-managed | 32 `@Cacheable` methods across 5 cache names |
| Security | Spring Security | Boot-managed | stateless filter chain, method security, BCrypt |
| Tokens | `com.auth0:java-jwt` | 4.4.0 | HS256 JWTs, issue and verify |
| Object storage | AWS SDK for Java v2 — S3 | 2.20.30 | all uploaded media |
| Media metadata | `com.drewnoakes:metadata-extractor` | 2.19.0 | dimensions/duration without ffprobe |
| API docs | springdoc-openapi | 2.8.6 | OpenAPI 3 + Swagger UI at `/swagger-ui.html` |
| Ops | Spring Boot Actuator | Boot-managed | present but not exposed — see §9 |
| Config | `me.paulschwarz:spring-dotenv` | 4.0.0 | `.env` → Spring properties |
| Boilerplate | Lombok | Boot-managed | `@Data`, `@Builder`, `@RequiredArgsConstructor` |
| Nullability | JSpecify | 1.0.0 | `@Nullable` / `@NonNull` annotations |
| Tests | JUnit 5, Mockito, Spring Boot test slices, H2 | Boot-managed | 31 test classes |
| Build | Maven + wrapper (`./mvnw`) | 3.9.14 available | `spring-boot-maven-plugin`, `production` profile |

---

## 2. Language and runtime

`pom.xml` sets `<java.version>21</java.version>`, which the Spring Boot parent turns into
`maven.compiler.release=21`. The only JDK installed on the development Mac is **OpenJDK 25.0.2**, so
builds compile *to* 21 while running *on* 25. That combination is supported and currently works; if a
library ever misbehaves on 25, install a JDK 21 and point the IDE at it rather than lowering
`java.version`.

Spring Boot **4.0.2** is a deliberately current major. Two consequences show up in the code:

- The starter is `spring-boot-starter-webmvc`, not the older `spring-boot-starter-web`.
- `DaoAuthenticationProvider` is built with the constructor that takes a `UserDetailsService`
  (`AppConfig`), because the setter-based style was removed.

The Maven wrapper (`./mvnw`) is committed, so no local Maven install is required. A `production`
profile sets `skipTests=true` for deployment builds.

---

## 3. Web layer

**Spring MVC** serves 18 controller classes: 15 `*Controller` under `khi_app/api/**` for content, and
`UserAPI`, `UserProfileAPI`, `SessionAPI` under `user/api/` for identity.

**Jackson** is configured in two places:

- `application.yaml` — `indent_output: true`, `default-property-inclusion: non_null` (null fields are
  omitted from every response, which is why clients must treat absence as null), and
  `time-zone: Asia/Baghdad`.
- `JacksonConfig` — a custom `ObjectMapper` with a `DeserializationProblemHandler` that **rejects
  unknown request fields**, with one exception: an `id` in the body of an update is tolerated, since
  update IDs come from the URL and clients often round-trip a response object. Anything else unknown
  is an error rather than a silent drop.

`jackson-datatype-jsr310` is pinned at 2.21.0 so `LocalDate` / `Instant` serialize as ISO-8601
strings rather than epoch arrays.

**Validation** is Jakarta Bean Validation via Hibernate Validator. `spring.web.error` is set to
include messages and binding errors but never stack traces, so a 400 tells the client which field
failed without leaking internals.

**Multipart** is documented by `MultipartJsonConfig` — a configuration class that intentionally
declares no beans. Under Boot 3/4 the `@RequestPart("data") MyDto` + `@RequestPart("file")
MultipartFile` pattern works with the auto-configured converters, and the class exists to record that
fact (and the Postman recipe) so nobody re-adds a redundant converter. Limits are 1 GB file / 1 GB
request, with matching Tomcat settings.

---

## 4. Persistence

**Spring Data JPA over Hibernate**, against **PostgreSQL**. 52 `@Entity` classes map to 83 tables
(see [`database/SCHEMA.md`](database/SCHEMA.md)).

Three settings define how it behaves:

| Setting | Value | Why it matters |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `update` | **There is no Flyway or Liquibase.** Hibernate applies the schema at startup. `update` adds tables and columns but never drops or narrows one, so a rename leaves the old column behind. Read [`database/MIGRATIONS.md`](database/MIGRATIONS.md) before editing an entity. |
| `spring.jpa.open-in-view` | `false` | No session held open for the view layer. Lazy associations must be fetched inside the service, or you get a `LazyInitializationException` — deliberate, because the alternative hides N+1 queries. |
| `hibernate.jdbc.time_zone` | `UTC` | Timestamps are stored in UTC and rendered in `Asia/Baghdad` by Jackson. Storage and presentation are separate on purpose. |

**JPA auditing** is enabled in `AuditingConfig` (`@EnableJpaAuditing(auditorAwareRef = "auditorAware")`)
with `AuditorAwareImpl` supplying the current principal, so `@CreatedBy` / `@LastModifiedBy` /
`@CreatedDate` / `@LastModifiedDate` populate themselves on entities under `model/audit`.

---

## 5. Caching — Redis

`spring-boot-starter-cache` + `spring-boot-starter-data-redis` (Lettuce client), turned on by
`CacheConfig` — another class with no beans, only `@EnableCaching` and a long comment that is
required reading. 32 `@Cacheable` methods share five cache names: `news`, `projects`, `soundTracks`,
`imageCollections`, `services`. Every create/update/delete path carries
`@CacheEvict(allEntries = true)`, so writes never leave stale reads behind.

Everything is tuned in `application.yaml`, not in Java:

```yaml
spring.cache.type: redis
spring.cache.redis.time-to-live: 600000       # 10 minutes
spring.cache.redis.key-prefix: "khi:"
spring.cache.redis.cache-null-values: false
```

`CacheConfig` deliberately does **not** define a `RedisCacheConfiguration` bean, because doing so
replaces the property-derived configuration wholesale and silently drops the TTL and key prefix.

### The serialization contract

Boot's Redis cache uses **JDK serialization**, so every type reachable from a cached return value must
implement `Serializable` or the first cache write throws `SerializationFailedException` at runtime.
The five cached DTO graphs satisfy this, and each pins `serialVersionUID = 1L` on purpose — without
it the JVM derives the ID from the class structure, and merely *adding a field* makes every entry
written before a deploy fail with `InvalidClassException` until the TTL flushes it.

`CacheSerializationTests` round-trips each cached page shape through `ObjectOutputStream` and fails
the build if a non-serializable field creeps in. **Before adding a new `@Cacheable`, add its DTO to
that test.**

### Redis is on the request path

Lettuce connects lazily, so before caching was enabled the app ran fine without Redis. It no longer
does: an unreachable Redis now surfaces on every cached read. `REDIS_HOST` / `REDIS_PORT` /
`REDIS_PASSWORD` must be set in every environment. Locally there is no `redis-server` binary on this
Mac — Redis runs in Docker (see [`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md) §7).

---

## 6. Security and identity

**Spring Security** with `@EnableWebSecurity` and `@EnableMethodSecurity`, configured in
`user/configs/SecurityConfig.java`:

- `SessionCreationPolicy.STATELESS` — no HTTP session, no `SecurityContext` caching. A role change
  therefore takes effect on the **next token**, not the next request.
- CSRF disabled, which is correct for a token-authenticated API with no cookie-driven state.
- `JWTAuthenticationFilter` inserted before `UsernamePasswordAuthenticationFilter`.
- A long, ordered `authorizeHttpRequests` chain: public reads (`GET /api/v1/**`), `EMPLOYEE+` writes,
  `ADMIN+` deletes, `SUPER_ADMIN` user management, and three anonymous POSTs for visitor submissions.
- Method-level `@PreAuthorize` for the cases the path matchers cannot express — e.g. the
  `/{id}/featured` toggles that sit under an `EMPLOYEE`-writable prefix but must stay `ADMIN`-only.

**Passwords** use `BCryptPasswordEncoder` (`AppConfig`) behind a `DaoAuthenticationProvider`.

**Tokens** are HS256 JWTs issued and verified by `com.auth0:java-jwt` in `JwtTokenProvider`. Claims
carry the user id, role, authorities array and a `sessionId`, which is what lets a session be revoked
server-side even though the token itself is stateless. `JwtCookieService` additionally sets the token
as an HTTP cookie whose every attribute — name, `Secure`, `HttpOnly`, `SameSite`, path, max-age —
comes from environment variables.

> **Two JWT libraries are declared; only one is used.** `io.jsonwebtoken:jjwt-{api,impl,jackson}`
> 0.12.3 appears in `pom.xml`, but an import audit of `src/main` and `src/test` finds **zero**
> references to `io.jsonwebtoken`. All JWT work goes through `com.auth0:java-jwt`. See §14.

---

## 7. Object storage and media

**AWS SDK for Java v2** (`software.amazon.awssdk:s3` 2.20.30). `S3Config` builds a single `S3Client`
with `DefaultCredentialsProvider`, so credentials resolve from environment variables,
`~/.aws/credentials`, or an instance role — whichever is present.

The bucket coordinates are **hardcoded in `application.yaml`**, not env-driven:

```yaml
aws.s3.region: us-east-1
aws.s3.bucket: s3-khiwebsite
aws.s3.base-folder: khi-web-folders
```

Changing environment means editing that file. (The Archive Platform backend does the opposite and
drives all four from env — a difference worth knowing if you move between the two repos.)

Uploaded objects are exposed to clients as **absolute S3 URLs**. The public website consumes them
directly with `next/image` in `unoptimized` mode. Object-level access therefore depends on the
bucket policy, not on the application.

**`metadata-extractor` 2.19.0** reads image dimensions and media duration in pure Java — no `ffprobe`
binary to install, which is why it was chosen. It is used in exactly one place,
`MediaMetadataExtractor`, and covered by both a unit test and a real-file test.

---

## 8. API documentation

**springdoc-openapi 2.8.6** generates OpenAPI 3 from the controllers and serves Swagger UI.

- `/swagger-ui.html` and `/v3/api-docs`, both `permitAll()` in `SecurityConfig`.
- Scans `ak.dev.khi_backend`, matches `/api/**`, hides actuator.
- `OpenApiConfig` registers two security schemes — HTTP `bearer`/JWT and an API-key-in-cookie scheme —
  and two `GroupedOpenApi` groups, **public** and **internal**, mirroring the `external/` and
  `internal/` split of this documentation.
- `persist-authorization: true` keeps a pasted token across page reloads; `doc-expansion: none`
  collapses the list, which matters with this many endpoints.

---

## 9. Observability

**Actuator** is on the classpath, but there is no `management:` block in `application.yaml` and no
`/actuator/**` matcher in `SecurityConfig`. Actuator endpoints therefore fall through to
`anyRequest().authenticated()` and answer **401**. Do not wire a health probe to it without
explicitly permitting the path first.

**`TraceIdFilter`** (a `OncePerRequestFilter`) accepts an inbound `X-Trace-Id`, generates a UUID when
absent, puts it in the SLF4J **MDC** under `traceId`, echoes it back on the response, and clears it in
a `finally`. Clients can therefore correlate a report with a request.

> **Gap worth fixing:** the console pattern in `application.yaml` is
> `"%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n"` — it does **not** include `%X{traceId}`.
> The id is returned to the caller but never printed in the logs, so it cannot actually be used to
> find the request. Adding `[%X{traceId}]` to the pattern makes the filter useful. (The Archive
> Platform backend has the mirror-image problem: the pattern includes it, nothing populates it.)

Logging is otherwise tuned for production quiet: `root: WARN`, application code at `INFO`, Hibernate
SQL at `WARN`.

---

## 10. Internationalization

`I18nConfig` registers a `ResourceBundleMessageSource` over `classpath:i18n/messages` in three
locales:

| Locale tag | Language | File |
|---|---|---|
| `en` | English | `messages_en.properties` |
| `ckb` | Sorani (Central Kurdish, Arabic script) | `messages_ckb.properties` |
| `kmr` | Kurmanji (Northern Kurdish, Latin script) | `messages_kmr.properties` |

A missing key returns the key itself rather than throwing. Only the `message` field of an error
response is localised, resolved from the request's `Accept-Language` — which is why the dashboard
pins `Accept-Language: ckb` on every call.

---

## 11. Configuration and secrets

**`spring-dotenv` 4.0.0** makes a `.env` file at the repository root readable as Spring properties, so
`${PGHOST}` in `application.yaml` resolves from it. No `.env` is committed (`.gitignore` lists it) and
the IntelliJ run configuration defines no environment block, so a local `.env` is what you need to
create — template in [`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md) §7.

Variables with **no default** — the app will not start without them: `PGHOST`, `PGPORT`, `PGDATABASE`,
`PGUSER`, `PGPASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, and all six `JWT_COOKIE_*`.
Variables **with** defaults: `REDIS_HOST` (`localhost`), `REDIS_PORT` (`6379`), `REDIS_PASSWORD` (empty).

`server.forward-headers-strategy: framework` makes Spring trust the reverse proxy's `X-Forwarded-*`,
which is what keeps generated URLs and `Secure`-cookie decisions correct behind Railway.

---

## 12. Testing

31 test classes, run by `spring-boot-starter-{webmvc,data-jpa,security}-test` — which pull in JUnit 5,
Mockito, AssertJ, and Spring's test slices.

| Kind | Examples |
|---|---|
| Contract / integration | `PublicApiContractIntegrationTests`, `SecurityFilterChainIntegrationTests`, `NoHandlerFoundIntegrationTests`, `NavMenuIntegrationTests` |
| Service unit tests | the `*ServiceUpdateTests` / `*ServiceDeleteTests` family across project, news, video, image, sound, writing, site |
| Infrastructure contracts | `CacheSerializationTests` (§5), `JacksonConfigTest`, `S3ServiceTests` |
| Media | `MediaMetadataExtractorTests` plus a real-file variant |
| Data | `SeedDataFilesTests` validates `scripts/seed-data/*.json` |

**H2** is the test-scope database. `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
selects `mock-maker-subclass` instead of Mockito's default inline mock maker — the inline maker
attaches a Java agent at runtime, which newer JDKs warn about and will eventually block.

---

## 13. Build and repository tooling

- **Maven wrapper** — `./mvnw` is committed; `mvn` 3.9.14 is also installed on this Mac.
- **`spring-boot-maven-plugin`** builds the executable jar, excluding Lombok from the artifact.
- **Lombok** is `provided` scope and registered as an annotation processor.
- **`scripts/`** is real tooling, not scratch files:
  - `seed-about-services.sh` — loads bilingual CKB + KMR demo content for About and Services
    **through the REST API**, so slug validation, language checks and the TipTap HTML processor all
    run exactly as in production. Works against local or Railway via `BASE=`.
  - `render-diagrams.sh` — extracts every fenced ` ```mermaid ` block from `docs/diagrams/*.md` and
    renders it to SVG with mermaid-cli. The markdown is the source of truth; hand edits to an SVG are
    lost on the next run.
  - `render-schema.sh` + `schema-gen/` — regenerates `docs/database/schema.sql` from the entities.
  - `sql/` — the hand-written migrations that `ddl-auto: update` cannot perform.

---

## 14. Declared but unused dependencies

An import audit of the entire `src/main` and `src/test` tree found four dependencies in `pom.xml` with
**zero** references anywhere in the source:

| Dependency | Version | Status |
|---|---|---|
| `io.jsonwebtoken:jjwt-api` / `-impl` / `-jackson` | 0.12.3 | Superseded by `com.auth0:java-jwt`. Three artifacts, no usages. |
| `com.google.guava:guava` | 32.1.3-jre | No `com.google.common` import anywhere. |
| `org.apache.commons:commons-lang3` | 3.18.0 | No `org.apache.commons.lang3` import anywhere. |
| `net.bytebuddy:byte-buddy` | 1.14.10 | Explicit version pin of a transitive dependency; not imported directly. |

This is worth acting on. Guava and commons-lang3 are large, jjwt is a *cryptographic* library, and an
unused crypto dependency is still something a CVE scanner will flag and someone will have to triage.
`byte-buddy` is a different case — pinning a transitive version can be intentional, but if it was
pinned to solve a problem that no longer exists, it now just holds Mockito and Hibernate back from
their tested versions.

Suggested order: drop the three jjwt artifacts first (largest security-surface win), then guava and
commons-lang3, then check whether the byte-buddy pin is still needed. Run the full test suite after
each removal.

---

## 15. The frontend stack this backend serves

Documented in full in [`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md); summarised here so the
whole picture is in one place.

### `khi-webiste` — the public site

| Area | Technology |
|---|---|
| Framework | Next.js 16.2.6 (App Router, `output: "standalone"`) · React 19.2.4 |
| Language | TypeScript 5 |
| Styling | Tailwind CSS 4 |
| i18n | next-intl 4 — `messages/ckb.json`, `messages/ku.json` |
| Validation | Zod 4 — every API response is schema-validated server-side |
| Media | `@vidstack/react` (player), `react-pdf` + pdf.js, `sharp` |
| Content safety | `sanitize-html`, `marked` |
| Motion | `motion` 12, `embla-carousel-react` |
| Forms | react-hook-form + `@hookform/resolvers` |
| Tooling | Biome (lint + format), Vitest, pnpm 11 |

### `khi-dashboard` — the admin console

| Area | Technology |
|---|---|
| Framework | Next.js 16.1.7 · React 19.2.4 · TypeScript 5.9 |
| Data | TanStack Query 5 · TanStack Table 8 · TanStack Virtual 3 |
| State | Zustand 5 (auth store) |
| HTTP | axios 1.15 through the same-origin `/railway-proxy` route |
| Editor | TipTap 3 (`starter-kit` + image, link, table, text-align, underline, markdown) |
| UI | Base UI, shadcn, Tailwind 4, `next-themes`, `sonner` toasts, Recharts |
| Interaction | dnd-kit (drag-and-drop ordering) |
| Validation | Zod 3 + react-hook-form |
| Media | `jsmediatags`, `dompurify`, `marked` |
| Tooling | ESLint 9, Prettier 3 + Tailwind plugin |

Note the deliberate split: the **website** validates with Zod 4 and lints with Biome; the
**dashboard** is on Zod 3 with ESLint + Prettier. They are separate applications with separate
release cycles, so the versions are allowed to drift — but a shared type or schema copied between
them will not necessarily behave the same.

---

## See also

- [`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md) — how these technologies meet the frontends
- [`database/SCHEMA.md`](database/SCHEMA.md) — every table and column
- [`database/MIGRATIONS.md`](database/MIGRATIONS.md) — the `ddl-auto: update` consequences in detail
- [`diagrams/UML_COMPONENT.md`](diagrams/UML_COMPONENT.md) — components, filter chain, deployment
- `../CHANGELOG.md` — release history
