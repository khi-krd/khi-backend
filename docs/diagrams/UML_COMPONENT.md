# Component and deployment views

Where the pieces are, what talks to what, and where it runs. Everything below was read out of the
source on 2026-08-26; where the diagram disagrees with an earlier description of the system, the
code won and the disagreement is written down next to the picture.

Companion documents: [`FLOWCHARTS.md`](./FLOWCHARTS.md) for request-level behaviour,
[`UML_CLASS.md`](./UML_CLASS.md) for the type model.

---

## System context

Seven things exist in production, and the interesting part is that the browser talks to two of
them directly. Media is uploaded through the backend but served straight out of S3, because
`S3Service.getPublicUrl` bakes a public bucket URL into the row and that is the URL the frontends
render. The backend is not on the media read path.

```mermaid
flowchart TD
    V(["Visitor or staff browser"])

    subgraph fe ["Frontends - separate deployments, same API"]
        PUB["Public website<br/>khi-website-production.up.railway.app<br/>khi-frontend.vercel.app"]
        ADM["Admin dashboard<br/>khi-dashboard-production.up.railway.app"]
    end

    BE["KHI Backend<br/>Spring Boot 4.0.2 on Java 21<br/>18 REST controllers, 173 endpoints"]

    PG[("PostgreSQL<br/>83 tables, schema from ddl-auto update")]
    RD[("Redis<br/>prefix khi:, TTL 10 min")]
    S3[("AWS S3<br/>s3-khiwebsite, us-east-1")]

    V -->|"HTTPS, HTML and JS"| PUB
    V -->|"HTTPS, HTML and JS"| ADM
    V -->|"HTTPS GET, direct object read"| S3

    PUB -->|"HTTPS JSON, no credentials<br/>80 permitAll endpoints"| BE
    ADM -->|"HTTPS JSON, Bearer header or JWT cookie<br/>91 authenticated endpoints"| BE

    BE -->|"JDBC, PGHOST PGPORT PGDATABASE"| PG
    BE -->|"RESP over Lettuce, cache read and write"| RD
    BE -->|"AWS SDK v2, PutObject GetObject DeleteObject"| S3
```

**What to notice**

- The two frontends are separate deployments that hit the same API with different auth. Nothing in
  the backend distinguishes them: the split is entirely enforced by
  [`SecurityConfig.authorizeHttpRequests`](../../src/main/java/ak/dev/khi_backend/user/configs/SecurityConfig.java),
  and both origins appear in the same `app.cors.allowed-origins` list.
- The browser reads media from S3 without touching the backend.
  [`S3Service`](../../src/main/java/ak/dev/khi_backend/khi_app/service/S3Service.java) returns
  `https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/<folder>/<uuid>-<name>` and that
  string is persisted. Rotating the bucket or the region breaks every stored URL, not just new uploads.
- Redis is on the request path for reads only, and only for five services. If Redis is down, those
  reads fail rather than degrade -- the header comment in
  [`CacheConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/CacheConfig.java) says so
  explicitly.
- There is no email, SMS or payment integration. `PasswordResetDeliveryService` has exactly one
  implementation, `LoggingPasswordResetDeliveryService`, which writes the reset token to the log.

---

## Internal component structure

The backend is two packages that were meant to be independent subsystems and are not quite. Both
directions of coupling are drawn below, and both are real: `user` reaches into `khi_app` for S3, and
`khi_app` reaches into `user` for two auth types. The single most load-bearing correction on this
page is in the error-handling box.

```mermaid
flowchart TD
    subgraph app ["khi_app - all site content"]
        AAPI["api<br/>15 controllers"]
        ASVC["service<br/>18 classes"]
        AREPO["repository<br/>34 Spring Data interfaces"]
        AMODEL["model<br/>41 entities"]
        AMEDIA["media pipeline<br/>S3Service, MediaService,<br/>TiptapHtmlProcessor"]
        AMETA["MediaMetadataExtractor<br/>reachable only from tests"]
        AERR["exceptions<br/>GlobalExceptionHandler<br/>the ONLY RestControllerAdvice"]
        ACFG["config<br/>11 classes, 3 of them empty"]
    end

    subgraph usr ["user - identity and sessions"]
        UAPI["api<br/>UserAPI, SessionAPI, UserProfileAPI"]
        USVC["service<br/>UserService, TokenService,<br/>UserProfileService, UserValidator"]
        UREPO["repo<br/>User, Session, TokenBlacklist"]
        USEC["jwt<br/>JWTAuthenticationFilter,<br/>JwtTokenProvider, JwtCookieService"]
        UCFG["configs<br/>SecurityConfig, AppConfig,<br/>AppCorsProperties, JwtCookieProperties"]
        UERR["exceptions<br/>JwtAuthenticationEntryPoint,<br/>JwtAccessDeniedHandler - both unwired"]
    end

    PG[("PostgreSQL")]
    RD[("Redis")]
    S3[("AWS S3")]

    AAPI --> ASVC
    ASVC --> AREPO
    ASVC --> AMEDIA
    AREPO --> AMODEL
    AREPO --> PG
    ASVC -->|"@Cacheable on 5 services"| RD
    AMEDIA --> S3

    AAPI -.->|"3 controllers inject<br/>PublishmentTopicRepository directly"| AREPO

    UAPI --> USVC
    USVC --> UREPO
    UREPO --> PG
    UAPI -.->|"2 controllers inject<br/>SessionRepository directly"| UREPO

    USEC --> USVC
    UCFG --> USEC
    USEC -->|"populates SecurityContextHolder"| AAPI
    USEC --> UAPI

    USVC -->|"UserProfileService uses S3Service"| AMEDIA
    AERR -->|"imports UserAlreadyExistsException<br/>and SecurityConstants"| UERR

    AAPI --> AERR
    UAPI -->|"user exceptions land here too"| AERR
    ACFG --> ASVC
```

**What to notice**

- **There is one exception-handling subsystem, not two.**
  [`user/exceptions/GlobalExceptionHandler.java`](../../src/main/java/ak/dev/khi_backend/user/exceptions/GlobalExceptionHandler.java)
  contains no handler at all -- it declares a package-private, empty
  `UserExceptionHandlerPlaceholder` and a comment explaining that two beans with the same simple
  name collide at startup. Every exception in the application, including `UserAlreadyExistsException`
  and `BadCredentialsException`, is handled by the 20 `@ExceptionHandler` methods in
  [`khi_app/exceptions/GlobalExceptionHandler.java`](../../src/main/java/ak/dev/khi_backend/khi_app/exceptions/GlobalExceptionHandler.java).
  That is why `ErrorCode` carries `ACCOUNT_LOCKED` and `UNAUTHORIZED` next to `NEWS_NOT_FOUND`.
- `JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` are `@Component` beans that nothing
  references. `SecurityConfig` never calls `.exceptionHandling(...)`, and Spring Security does not
  discover entry points by type, so both are constructed at startup and never invoked. See the
  filter-chain section for what happens instead.
- Layering is Controller to Service to Repository *except* in five places. `ImageCollectionController`,
  `SoundTrackController` and `WritingController` each inject `PublishmentTopicRepository` to serve
  their `/topics` sub-route, and `UserAPI` and `SessionAPI` both inject `SessionRepository`. Those
  are the dotted edges.
- `MediaMetadataExtractor` is a live `@Component` with a working implementation and two test classes,
  and no production caller anywhere. `grep -rn "metadataExtractor"` over `src/main` returns nothing
  outside the class itself. Duration, bitrate and dimensions are not being derived on any upload path
  today.
- The coupling between the two packages is bidirectional and thin: exactly one import each way.

---

## Request filter chain

This is the ordering that actually applies, which is not the ordering the class names suggest. Both
`TraceIdFilter` and `JWTAuthenticationFilter` are annotated `@Component`, so Spring Boot registers
them in the plain servlet chain at `LOWEST_PRECEDENCE`, while `springSecurityFilterChain` registers
at order -100. The whole security chain therefore runs *before* `TraceIdFilter`, and there is no
`FilterRegistrationBean` anywhere in the repository disabling either auto-registration.

```mermaid
flowchart LR
    REQ[/"HTTP request"/]
    CORS["CorsFilter<br/>origins from AppCorsProperties<br/>allowCredentials true"]
    JWT["JWTAuthenticationFilter<br/>added via addFilterBefore<br/>UsernamePasswordAuthenticationFilter"]
    ETF["ExceptionTranslationFilter<br/>Spring Security defaults<br/>no custom handler registered"]
    AUTHZ["AuthorizationFilter<br/>authorizeHttpRequests baseline<br/>per path and method"]
    TID["TraceIdFilter<br/>sets MDC traceId<br/>echoes X-Trace-Id"]
    DS["DispatcherServlet"]
    PRE{"@PreAuthorize<br/>on the handler?"}
    CTRL["Controller method"]
    ADVICE["GlobalExceptionHandler<br/>builds ApiErrorResponse"]
    RESP[/"HTTP response"/]

    REQ --> CORS --> JWT
    JWT -->|"token bad, expired or revoked"| RAW[/"raw JSON<br/>error and message only"/]
    JWT -->|"no token, or token accepted"| ETF
    ETF --> AUTHZ
    AUTHZ -->|"denied"| SE[/"container error page<br/>via response.sendError"/]
    AUTHZ -->|"allowed"| TID
    TID --> DS --> PRE
    PRE -->|"denied"| ADVICE
    PRE -->|"allowed"| CTRL
    CTRL -->|"throws"| ADVICE
    CTRL -->|"returns"| RESP
    ADVICE --> RESP
```

**What to notice**

- **Three different error shapes leave this pipeline.**
  [`JWTAuthenticationFilter.sendErrorResponse`](../../src/main/java/ak/dev/khi_backend/user/jwt/JWTAuthenticationFilter.java)
  hand-writes `{"error":"TOKEN_EXPIRED","message":"..."}`; the authorization layer calls
  `response.sendError` and gets the container's default body; only the third path produces the
  documented `ApiErrorResponse` with `traceId`, `messageEn` and `messageKu`. A client cannot parse
  all three with one shape.
- **`traceId` is absent from everything the security chain rejects.** `TraceIdFilter` runs after the
  security chain, so a 401 or 403 raised by `JWTAuthenticationFilter` or `AuthorizationFilter` is
  committed before the MDC value is set and before the `X-Trace-Id` response header is written.
- Because `SecurityConfig` registers no authentication mechanism and no `.exceptionHandling(...)`,
  `ExceptionTranslationFilter` falls back to Spring Security's default `Http403ForbiddenEntryPoint`.
  An anonymous request to a protected path is refused by the framework default, not by the project's
  own `JwtAuthenticationEntryPoint`.
- The filter has a `shouldNotFilter` escape hatch for five auth endpoints plus `/api/users/auth/**`,
  and it short-circuits `OPTIONS` with a 200 before doing any token work.
- Authentication is stateless in name only. Every authenticated request costs three PostgreSQL reads:
  `token_blacklist` by token, `sessions` by `sessionId`, and the `users_tbl` load inside
  `userDetailsService.loadUserByUsername`. `TokenService.isTokenBlacklisted` is a database check, not
  a Redis one -- `TokenBlacklist` is a JPA `@Entity`.
- Authorization is genuinely two layers, and the second can be *narrower* than the first. There are
  13 `@PreAuthorize` annotations across nine controllers, and seven of them are `hasRole('ADMIN')`
  on `/{id}/featured`
  toggles. No `RoleHierarchy` bean exists, so `hasRole('ADMIN')` does not include `SUPER_ADMIN`:
  a `SUPER_ADMIN` passes the path baseline and is then denied by the method annotation.

---

## Configuration map

Fifteen classes were named for this map. Reading them turns up that only nine of them produce
anything, two are not `@Configuration` at all, three are deliberately empty, and one -- `CorsConfig`
-- is an empty file whose job is done elsewhere. The diagram shows the wiring that has an effect;
the table below is the full inventory.

```mermaid
flowchart LR
    subgraph env ["Environment - .env locally, dashboard variables in production"]
        E_PG["PGHOST PGPORT PGDATABASE<br/>PGUSER PGPASSWORD"]
        E_RD["REDIS_HOST REDIS_PORT<br/>REDIS_PASSWORD"]
        E_JWT["JWT_SECRET JWT_EXPIRATION_MS<br/>JWT_COOKIE_ x7"]
        E_AWS["AWS_ACCESS_KEY_ID<br/>AWS_SECRET_ACCESS_KEY"]
    end

    subgraph cfg ["Classes that produce beans"]
        SEC["SecurityConfig<br/>SecurityFilterChain<br/>CorsConfigurationSource"]
        APPC["AppConfig<br/>BCryptPasswordEncoder<br/>DaoAuthenticationProvider<br/>AuthenticationManager"]
        CORSP["AppCorsProperties<br/>@ConfigurationProperties app.cors"]
        JWTP["JwtCookieProperties<br/>@ConfigurationProperties jwt"]
        S3C["S3Config<br/>S3Client, DefaultCredentialsProvider"]
        I18N["I18nConfig<br/>MessageSource, LocaleResolver,<br/>LocaleChangeInterceptor"]
        JACK["JacksonConfig<br/>ObjectMapper"]
        OAPI["OpenApiConfig<br/>OpenAPI + 3 GroupedOpenApi"]
        AUD["AuditingConfig + AuditorAwareImpl<br/>@EnableJpaAuditing"]
        CACHE["CacheConfig<br/>@EnableCaching only"]
        TRACE["TraceIdFilter<br/>@Component, not @Configuration"]
    end

    subgraph dead ["Empty by design - no beans, no effect"]
        NOOP["CorsConfig<br/>WebConfig<br/>MultipartJsonConfig"]
    end

    YAML["application.yaml<br/>aws.s3.* hardcoded<br/>cache TTL and prefix<br/>1GB multipart limits"]

    E_PG --> YAML
    E_RD --> YAML
    E_JWT --> YAML
    YAML --> JWTP
    YAML --> CORSP
    YAML --> S3C
    YAML --> CACHE
    E_AWS -->|"read by the SDK credential chain"| S3C
    CORSP --> SEC
    JWTP --> SEC
    APPC --> SEC
```

| Class | Kind | Concern | Environment it reads |
| --- | --- | --- | --- |
| [`SecurityConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/SecurityConfig.java) | `@Configuration @EnableWebSecurity @EnableMethodSecurity` | Filter chain, path/method authorization baseline, CORS source | via `AppCorsProperties` |
| [`AppConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/AppConfig.java) | `@Configuration @EnableMethodSecurity` | Password hashing, `AuthenticationProvider`, `AuthenticationManager` | none |
| [`AppCorsProperties`](../../src/main/java/ak/dev/khi_backend/user/configs/AppCorsProperties.java) | `@Component @ConfigurationProperties` | Allowed origins, methods, headers, credentials, max-age | `app.cors.*` from YAML |
| [`JwtCookieProperties`](../../src/main/java/ak/dev/khi_backend/user/configs/JwtCookieProperties.java) | `@Component @ConfigurationProperties` | Auth cookie name, secure, httpOnly, sameSite, path, max-age | `JWT_COOKIE_NAME`, `JWT_COOKIE_SECURE`, `JWT_COOKIE_HTTP_ONLY`, `JWT_COOKIE_SAME_SITE`, `JWT_COOKIE_PATH`, `JWT_COOKIE_MAX_AGE` |
| [`S3Config`](../../src/main/java/ak/dev/khi_backend/khi_app/config/S3Config.java) | `@Configuration` | `S3Client` bean, region only | `aws.s3.region` from YAML; credentials from `DefaultCredentialsProvider` |
| [`I18nConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/I18nConfig.java) | `@Configuration implements WebMvcConfigurer` | `en` / `ckb` / `kmr` message bundles, `Accept-Language` resolution, `?lang=` override | none |
| [`JacksonConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/JacksonConfig.java) | `@Configuration` | `ObjectMapper`: JSR-310, ISO dates, tolerate a stray `id` in request bodies | none |
| [`OpenApiConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/OpenApiConfig.java) | `@Configuration` | Swagger UI, `bearerAuth` + `cookieAuth` schemes, `public` / `internal` / `all` groups | none |
| [`AuditingConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/AuditingConfig.java) | `@Configuration @EnableJpaAuditing` | Turns on `@CreatedDate` / `@CreatedBy` on `AuditableEntity` | none |
| [`AuditorAwareImpl`](../../src/main/java/ak/dev/khi_backend/khi_app/config/AuditorAwareImpl.java) | **`@Component`**, not `@Configuration` | Supplies `created_by` / `updated_by`; falls back to the literal `SYSTEM` | none |
| [`CacheConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/CacheConfig.java) | `@Configuration @EnableCaching` | Switches caching on; deliberately defines no `RedisCacheConfiguration` | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` via YAML |
| [`TraceIdFilter`](../../src/main/java/ak/dev/khi_backend/khi_app/config/TraceIdFilter.java) | **`@Component`**, not `@Configuration` | MDC `traceId`, `X-Trace-Id` request/response header | none |
| [`CorsConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/CorsConfig.java) | `@Configuration` | **Empty.** CORS is configured in `SecurityConfig` | none |
| [`WebConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/WebConfig.java) | `@Configuration` | **Empty.** No body, no beans | none |
| [`MultipartJsonConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/MultipartJsonConfig.java) | `@Configuration` | **Empty by design**, documented as such -- Boot handles multipart JSON | none |

**What to notice**

- `@EnableMethodSecurity` is declared twice, on `AppConfig` and again on `SecurityConfig`. It is
  idempotent, so this is noise rather than a bug, but it means neither class is the single place to
  look when tracing method security.
- **The S3 bucket, region and base folder are not environment-driven.** `aws.s3.bucket`,
  `aws.s3.region` and `aws.s3.base-folder` are literals in
  [`application.yaml`](../../src/main/resources/application.yaml), so local development writes into
  the same production bucket unless you override them. Only the AWS *credentials* come from the
  environment, and they arrive through the SDK's `DefaultCredentialsProvider` chain rather than any
  named property this project owns.
- Five of the seven `JWT_COOKIE_*` variables have no default in the YAML. The application will not
  start without them, even though `JwtCookieProperties` carries perfectly good Java-side defaults --
  the YAML placeholder wins and fails first.
- `AuditorAwareImpl` returns `Optional.of("SYSTEM")` for anonymous requests, so a row created by a
  public visitor submission is stamped `created_by = SYSTEM`, not left null.

---

## Deployment topology

There is no deployment manifest in the repository -- no `Dockerfile`, `railway.json`, `Procfile` or
`nixpacks.toml`. The topology below is reconstructed from `app.cors.allowed-origins`, the two
Railway comments in `application.yaml`, and the environment variables the application refuses to
start without.

```mermaid
flowchart TD
    subgraph browsers ["Public internet"]
        BR(["Browser"])
    end

    subgraph railway ["Railway"]
        FE1["khi-website-production<br/>public site"]
        FE2["khi-dashboard-production<br/>admin dashboard"]
        API["khi_backend<br/>server.port 8080<br/>forward-headers-strategy framework"]
        DB[("Managed PostgreSQL")]
        CACHE[("Managed Redis")]
    end

    subgraph vercel ["Vercel"]
        FE3["khi-frontend.vercel.app<br/>plus khi-frontend-*.vercel.app previews"]
    end

    subgraph aws ["AWS us-east-1"]
        BUCKET[("s3-khiwebsite<br/>khi-web-folders/")]
    end

    subgraph secrets ["Service variables"]
        S1["PGHOST PGPORT PGDATABASE PGUSER"]
        S2["PGPASSWORD - secret"]
        S3V["REDIS_HOST REDIS_PORT"]
        S4["REDIS_PASSWORD - secret"]
        S5["JWT_SECRET - secret"]
        S6["JWT_EXPIRATION_MS, JWT_COOKIE_ x7"]
        S7["AWS_SECRET_ACCESS_KEY - secret"]
        S8["AWS_ACCESS_KEY_ID"]
    end

    BR --> FE1
    BR --> FE2
    BR --> FE3
    BR -->|"media reads"| BUCKET

    FE1 -->|"anonymous JSON"| API
    FE2 -->|"JWT cookie or Bearer"| API
    FE3 -->|"anonymous JSON"| API

    API --> DB
    API --> CACHE
    API --> BUCKET

    S1 --> API
    S2 --> API
    S3V --> API
    S4 --> API
    S5 --> API
    S6 --> API
    S7 --> API
    S8 --> API
```

Local development runs the same jar against a mix of local and remote infrastructure. Note that the
one thing you cannot make local is S3.

```mermaid
flowchart LR
    DEV(["Developer browser"])
    FE["Vite or Next dev server<br/>localhost:5173 or localhost:3000"]
    APP["mvnw spring-boot:run<br/>localhost:8080"]
    ENV[/".env at repo root<br/>loaded by spring-dotenv<br/>gitignored"/]
    LPG[("Local PostgreSQL<br/>or Railway PG* variables")]
    LRD[("Local Redis<br/>REDIS_HOST defaults to localhost")]
    S3R[("Real AWS S3<br/>same production bucket")]

    DEV --> FE
    FE -->|"CORS allows both ports"| APP
    ENV --> APP
    APP --> LPG
    APP --> LRD
    APP -->|"no local emulation"| S3R
```

**What to notice**

- **`server.port` is the literal `8080`, not `${PORT:8080}`.** Railway injects a `PORT` variable that
  Spring Boot's relaxed binding does not map onto `server.port`, so the application listens on 8080
  regardless of what the platform asks for. If the service routes correctly today, it is because the
  target port is pinned in the dashboard.
- `forward-headers-strategy: framework` is what makes `request.getRemoteAddr()` -- recorded on every
  `Session` row by `JwtTokenProvider.generateToken` -- report the client rather than the Railway proxy.
- `REDIS_HOST`, `REDIS_PORT` and `REDIS_PASSWORD` all have defaults, so a misconfigured production
  service silently starts up and tries `localhost:6379`. The five `PG*` and the `JWT_*` variables
  have no defaults and fail loudly instead. That asymmetry is worth remembering when a deploy comes
  up healthy but every cached read 500s.
- The CORS list uses `setAllowedOriginPatterns`, not `setAllowedOrigins`, which is why the wildcard
  `https://khi-frontend-*.vercel.app` preview entry is legal alongside `allowCredentials: true`.
- Tests do not touch any of this. `application-test.yaml` points at in-memory H2 with
  `MODE=PostgreSQL`, `cache.type: simple`, and Redis repositories disabled -- so `jsonb` columns,
  Redis serialization and real S3 behaviour are all unexercised by the suite. See
  [`../database/MIGRATIONS.md`](../database/MIGRATIONS.md) for the consequences.
- Actuator is on the classpath with no `management.*` block, so only `/actuator/health` is exposed,
  and `springdoc.show-actuator: false` keeps it out of the OpenAPI document.

---

## External dependencies

Eight third-party libraries were named as architecturally interesting. Grepping for each one's
package prefix across `src/main` splits them cleanly into libraries that are load-bearing and
libraries that are only on the classpath. The split is the point of the diagram.

```mermaid
flowchart TD
    subgraph live ["On a production code path"]
        A1["com.auth0:java-jwt 4.4.0<br/>sign and verify HS256 tokens"]
        A2["software.amazon.awssdk:s3 2.20.30<br/>every upload, download, delete"]
        A3["org.springdoc:springdoc-openapi 2.8.6<br/>Swagger UI and 3 API groups"]
        A4["org.projectlombok:lombok<br/>@Data @Builder @RequiredArgsConstructor"]
        A5["me.paulschwarz:spring-dotenv 4.0.0<br/>loads .env before the context starts"]
        A6["org.jspecify:jspecify 1.0.0<br/>@NonNull on 2 overrides"]
    end

    subgraph testonly ["Wired, but only tests call it"]
        B1["com.drewnoakes:metadata-extractor 2.19.0<br/>used by MediaMetadataExtractor,<br/>which has no production caller"]
    end

    subgraph classpath ["Declared, zero source references"]
        C1["io.jsonwebtoken:jjwt-api / impl / jackson 0.12.3"]
        C2["com.google.guava:guava 32.1.3-jre"]
        C3["org.apache.commons:commons-lang3 3.18.0"]
        C4["net.bytebuddy:byte-buddy 1.14.10"]
    end

    JWTP["JwtTokenProvider<br/>JWTAuthenticationFilter"] --> A1
    S3S["S3Service"] --> A2
    MME["MediaMetadataExtractor"] --> B1
    C1 -.->|"same job as java-jwt,<br/>never imported"| A1
```

| Library | Version | Why it is here | Verified usage in `src/main` |
| --- | --- | --- | --- |
| `com.auth0:java-jwt` | 4.4.0 | Builds and verifies the HS256 token, including the `sessionId`, `role` and `authorities` claims | [`JwtTokenProvider`](../../src/main/java/ak/dev/khi_backend/user/jwt/JwtTokenProvider.java), [`JWTAuthenticationFilter`](../../src/main/java/ak/dev/khi_backend/user/jwt/JWTAuthenticationFilter.java) |
| `io.jsonwebtoken:jjwt-api` + `-impl` + `-jackson` | 0.12.3 | Nothing. Three artifacts, no import | **none** |
| `software.amazon.awssdk:s3` | 2.20.30 | The whole media pipeline; `S3Client` built in `S3Config` | `S3Config`, `S3Service` |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.8.6 | Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs`, three `GroupedOpenApi` groups | `OpenApiConfig` |
| `com.drewnoakes:metadata-extractor` | 2.19.0 | Pure-Java duration, bitrate and dimensions -- chosen to avoid an `ffprobe` binary on the host | `MediaMetadataExtractor` only, which nothing calls |
| `org.projectlombok:lombok` | managed | Entity, DTO and constructor boilerplate; `provided` scope and excluded from the fat jar | project-wide |
| `me.paulschwarz:spring-dotenv` | 4.0.0 | Lets `PG*`, `JWT_*` and `REDIS_*` come from a gitignored `.env` in local development | property resolution, no imports by design |
| `com.google.guava` | 32.1.3-jre | Declared; no `com.google.common` import exists | **none** |
| `org.apache.commons:commons-lang3` | 3.18.0 | Declared; no `org.apache.commons.lang3` import exists | **none** |
| `net.bytebuddy:byte-buddy` | 1.14.10 | An explicit version pin over Hibernate's transitive copy, not a direct API use | **none** |

**What to notice**

- **Two JWT libraries are on the classpath and only one is used.** `java-jwt` does all the work;
  the three `jjwt` artifacts have no import anywhere in `src/main` or `src/test`. They cost startup
  scan time and add a second CVE surface for the same job. The `jjwt` artifacts are the ones to drop.
- `guava`, `commons-lang3` and `byte-buddy` are similarly unreferenced. `byte-buddy` at least has a
  reason to exist -- pinning it overrides whatever version Hibernate drags in -- but pinning it at
  1.14.10 against a Spring Boot 4.0.2 parent means the pin is now *older* than the managed version,
  which is the opposite of what a pin is usually for.
- `spring-boot-starter-webmvc` rather than `spring-boot-starter-web` is the Boot 4 artifact rename,
  not a different stack. `spring-boot-devtools` is present but disabled in the YAML
  (`restart.enabled: false`, `livereload.enabled: false`).
- `jackson-datatype-jsr310` is declared explicitly at 2.21.0 and registered by hand in
  `JacksonConfig`, even though the Boot parent already manages it and Boot registers it
  automatically. The hand-built `ObjectMapper` bean replaces Boot's auto-configured one wholesale,
  which is why the explicit registration is necessary at all.

---

## Related

- [`FLOWCHARTS.md`](./FLOWCHARTS.md) -- request-level and lifecycle flows through the components drawn here.
- [`UML_CLASS.md`](./UML_CLASS.md) -- the entity, DTO and enum model behind the repository layer.
- [`../database/MIGRATIONS.md`](../database/MIGRATIONS.md) -- how the PostgreSQL schema in the deployment
  diagrams is actually produced, and what `ddl-auto: update` does and does not do.
