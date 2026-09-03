# Changelog

All notable changes to the KHI Backend are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Note on history:** this project had no changelog before 2026-08-26 and its commit
> messages are not descriptive — 55 of the 78 commits are titled "new commit". Releases
> 0.1.0–0.5.0 were reconstructed by inspecting the git history commit by commit; dates are
> the date of the last commit in each group. There are no git tags, so the version numbers
> below are established by this file rather than recovered from the repository.

## [Unreleased]

### Added

- **Donation type cards** — the donate page's "دەتوانم چی ببەخشم؟ / What can I donate?"
  picture cards are now database rows (`donation_type_cards`) instead of hardcoded website
  content. New public read `GET /api/v1/donations/type-cards` (with `?includeInactive=true`
  for the dashboard) and admin-only `POST` / `PUT /{id}` / `DELETE /{id}`, mirroring the
  social-links pattern: rows sorted by `displayOrder`, an `active` switch, bilingual titles
  (at least one required) and descriptions, and a required `imageUrl`. New entity
  `DonationTypeCard`, repository `DonationTypeCardRepository`, DTOs, service methods in
  `SiteContentService`, security rules in `SecurityConfig`, and unit tests. Documented in
  `docs/DONATION_TYPE_CARDS.md` (with seed data for the five current cards) and folded into
  the donate/donation API docs.
- `docs/database/schema.sql` — the full PostgreSQL DDL for all 84 tables (617 columns,
  84 indexes, 1 sequence, 63 foreign keys), generated from the JPA entities rather than
  hand-written. Build output, not source: nothing executes it at startup, since
  `ddl-auto: update` still reconciles the live database. Use it to provision a fresh
  database or to diff against an existing one.
- `scripts/render-schema.sh` regenerates that file from the entity classes with no
  database connection, alongside `scripts/schema-gen/`.

### Changed

- SVG exports now sit directly in `docs/diagrams/` rather than in `docs/diagrams/svg/`
  grouped by source document. Filenames already carried their document prefix, so the
  flattening introduced no collisions and no renames.

### Removed

- The 14 SVG exports of `docs/database/ERD.md` diagrams, which duplicated a reference that
  belongs with the schema. The 65 diagrams of the six `docs/diagrams/` documents are kept.
- `docs/diagrams/svg/README.md`, whose per-file index described the removed folder layout.
  `docs/diagrams/README.md` now explains the `<document>-<NN>-<heading>.svg` naming instead.

## [0.6.0] - 2026-08-26

### Added
- Documentation reorganised under `docs/`, split by who may call an endpoint:
  `docs/external/` documents the 80 endpoints callable without authentication, and
  `docs/internal/` the 91 endpoints requiring a JWT. `docs/database/` holds the schema
  material. Each directory carries its own `README.md` index. All 173 endpoints in the
  codebase were verified against the controllers; each is documented on exactly one side.
- Database documentation under `docs/database/`: schema reference, entity-relationship
  diagram, per-table field reference, and a migration guide.
- Visual documentation under `docs/diagrams/`: 70 Mermaid diagrams covering entity
  relationships, UML class / sequence / state / component views, and flowcharts of the
  request lifecycle, authorization ladder, media pipeline and delete cascade.
- SVG exports of all 79 diagrams (the 70 above plus the 14 in `docs/database/ERD.md`, less
  the README legend examples) under `docs/diagrams/svg/`, grouped by source document and
  indexed in `docs/diagrams/svg/README.md`.
- `scripts/render-diagrams.sh` regenerates every SVG from the Mermaid source, so the
  markdown stays the single source of truth.
- This `CHANGELOG.md`, covering the whole history from 2026-04-01 onward.
- `GET /api/v1/settings/social` accepts `?includeInactive=true`, so the dashboard can list
  social links an admin has switched off and turn them back on. The public site omits the
  parameter and keeps seeing active links only.

### Changed
- Previously scattered documentation was moved to `docs/_archive/` rather than deleted:
  the whole `new documentation/` tree (including its `external/` and `internal/`
  subdirectories) and `docs/social-links-api.md`.

### Known issues
- `app.cors.allowed-origins` is a literal list in `application.yaml` and no longer reads
  `CORS_ALLOWED_ORIGINS` (deliberate since 2026-05-03). Adding or removing a frontend
  origin requires a code change and a redeploy.
- `spring.jpa.hibernate.ddl-auto` is `update` and the schema has no migration tool behind
  it. Worse, six commits shipped `create-drop` in the main `application.yaml` — `99d0ea8`
  (2026-04-14), `a3b6118` (2026-05-22), `ae6dc7c` (2026-05-23), `e11e798` (2026-05-24),
  `8beb59c` and `824d32b` (both 2026-06-30) — each reverted to `update` afterwards. HEAD is
  currently safe, but a deploy that lands on one of those commits drops the entire schema on
  shutdown. See [`docs/database/MIGRATIONS.md`](docs/database/MIGRATIONS.md).
- The English message bundle is checked in as `src/main/resources/i18n/ messages_en.properties`
  — with a leading space in the filename. `I18nConfig` resolves basename
  `classpath:i18n/messages`, so that bundle never loads and English strings fall back to the
  hard-coded defaults passed at each call site. The `ckb` and `kmr` bundles are unaffected.
- Since caching was switched on (0.5.0), Redis is on the request path. Before that the app
  served traffic whether or not Redis was reachable, because Lettuce connects lazily and
  nothing asked it for a value. `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` must now be
  set in every environment.
- 22 `.DS_Store` files and three sample JPEGs under `uploads/` are tracked in git;
  `.gitignore` has no `.DS_Store` rule.
- `SecurityConfig` never calls `.exceptionHandling(...)`, so the `JwtAuthenticationEntryPoint`
  and `JwtAccessDeniedHandler` beans are constructed and then never registered on the filter
  chain. Neither ever runs. A request with no token receives Spring Security's default
  response rather than the intended `401` body; rejected-token responses come from
  `JWTAuthenticationFilter` writing JSON directly.
- `AuditableEntity` is extended by exactly one entity, `Project`. Every other content root has
  no `created_by` / `updated_by`, and the per-domain audit log tables hardcode their actor
  column to the literal `"system"` — `VideoService.logAction` never sets it at all, so
  `video_logs.performed_by` is NULL in every row. The audit trail does not record who acted.
- `sound_tracks.sound_type` is a free-text `varchar(100)`, not the `SoundType` enum
  (`LAWK`, `HAIRAN`). Nothing constrains the column at the database level.

## [0.5.0] - 2026-08-19

### Added
- Navigation menu API at `/api/v1/nav-menu`: `GET` (list), `GET /{id}`, `POST`, `PUT /{id}`,
  `DELETE /{id}`, backed by `NavMenuItem` / `NavMenuLink`. Reads are public, writes are
  admin-only.
- `featureImageUrl` on News, Projects, Image Collections, Sound Tracks, Videos and Writings
  — a dedicated hero image for the homepage carousel, independent of the record's cover
  image, falling back to the cover when unset.
- Page-level featured toggles for institutional content:
  `PATCH /api/v1/about/{id}/featured`, `PATCH /api/v1/services/{id}/featured`, and
  `PATCH /api/v1/donations/settings/featured`. These highlight a record on its own page and
  take no share of the homepage slide cap, so any number may be featured at once.
- Film advertisement video: `POST` / `GET` / `PATCH` / `DELETE /api/v1/videos/film-reklam-video`,
  a single global record uploaded as multipart.
- Site settings API: `GET /api/v1/site-settings` (public) and `PUT /api/v1/site-settings`
  (admin), holding `maxFeaturedSlides` and the related homepage limits.
- Caching is now actually active. `CacheConfig` adds `@EnableCaching`, so the `@Cacheable`
  and `@CacheEvict` annotations already spread across News, Projects, Sound Tracks, Image
  Collections and Services take effect against Redis (10-minute TTL, `khi:` key prefix).
- Seed tooling under `scripts/`: `seed-about-services.sh`, JSON fixtures in
  `scripts/seed-data/`, and the one-off migration `scripts/sql/2026-08-17-featured-about-service-donation.sql`.
- External and internal API references for sound advertisement video, film advertisement
  video and site settings.

### Changed
- Cached DTOs — `NewsDto`, `ProjectResponse`, `ServiceDTOs.ServiceResponse`,
  `SoundTrackDtos.Response`, `ImageCollectionDTO.Response` and every nested type they reach
  — now implement `Serializable` with a pinned `serialVersionUID = 1L`. Spring's Redis cache
  uses JDK serialization, so without the pinned ID merely adding a field would make every
  entry written before a deploy fail to read back until the TTL flushed it.
- Service featuring is scoped to the Services page hero rail rather than the homepage
  carousel, and is exempt from the slide cap.
- `PATCH /api/v1/about/{id}/featured` and `PATCH /api/v1/services/{id}/featured` accept
  `SUPER_ADMIN` as well as `ADMIN` — they are ordinary page content, unlike the six carousel
  toggles, which stay `ADMIN`-only.

### Removed
- The `api/` request collections (`kurdish-mock-data.http`, `missing-albums.http`,
  `youtube-channel-videos.http`, `clear-featured.sql`) and the entire `docs_v2/` tree,
  roughly 38,000 lines of stale material.
- Root-level `API_GET_DOCS.md`, `API_IMPLEMENTATION.md`, `PROJECT_API.md`, `SERVICE_API.md`
  and `TIPTAP.md`.

### Fixed
- Unmapped URLs return `404` instead of `500`. `NoResourceFoundException` and
  `NoHandlerFoundException` are now handled explicitly and answer with the standard
  trilingual error body plus `details.path`, `details.method` and a hint; previously the
  `Exception.class` fallback caught them and reported a server error.

### Security
- Writes to `/api/v1/nav-menu/**` (`POST`, `PUT`, `DELETE`) restricted to `ADMIN` /
  `SUPER_ADMIN`; reads stay public.
- `PATCH /api/v1/videos/**` restricted to `EMPLOYEE` and above so the film advertisement
  endpoint is covered by the filter chain, while `/{id}/featured` stays `ADMIN`-only through
  `@PreAuthorize`.

## [0.4.0] - 2026-07-22

### Added
- Per-type featured listings: `GET /api/v1/news/featured`, `/api/v1/projects/featured`,
  `/api/v1/image-collections/featured`, `/api/v1/sound-tracks/featured`,
  `/api/v1/videos/featured`, `/api/v1/writings/featured` and `/api/v1/services/featured`,
  all paged.
- Sound advertisement video: `POST` / `GET` / `PATCH` / `DELETE /api/v1/sound-tracks/sound-reklam-video`,
  a single global record uploaded as multipart.
- Multi-file video sources. A `FILM` used to keep one `sourceUrl` and silently drop every
  extra uploaded file; it now keeps an ordered list of `VideoSourceFile` entries with exactly
  one flagged as main, mirrored back onto the legacy `sourceUrl` / `sourceExternalUrl` /
  `sourceEmbedUrl` fields so existing consumers keep working.
- `videoFiles` multipart part on the Video API: for `FILM`, `videoFiles[0]` becomes the
  source; for `VIDEO_CLIP`, `videoFiles[i]` maps to `videoClipItems[i]` by index. Uploaded
  files take priority over any URL in the JSON `data` part.
- `ServiceMedia` gallery slots on Service records — an ordered `galleryMedia[]` where each
  slot is independently `IMAGE` or `VIDEO` with its own poster and alt text. The older
  `featureImageUrls` / `thumbnailUrls` string lists remain only as a fallback for records
  created before this existed.
- Streaming S3 upload (`S3Service.upload(InputStreamProvider, contentLength, …)`) so large
  media is no longer buffered whole in JVM memory.
- Donation submissions validate `currency` against `IQD` / `USD` and `materialType` against
  `PHOTOGRAPH`, `MANUSCRIPT`, `DOCUMENT`, `AUDIO`, `VIDEO`, `OTHER`, rejecting anything else.
- `FEATURED_API.md`, `DONATE_API.md` and the Services requirements document, each in both an
  `external/` and an `internal/` variant.

### Changed
- **Breaking:** content deletes return `204 No Content` with an empty body instead of `200`
  with an `ApiResponse` envelope, across `DELETE /api/v1/news/{id}`, `/delete/{id}`, `/bulk`
  and the equivalent routes on the other content types.
- Deletes are now idempotent: deleting an id that does not exist, or passing an empty bulk
  list, succeeds silently instead of returning `400`.
- An `id` field in a JSON request body is accepted and ignored on update, so a response-shaped
  payload can be sent straight back. Every other unknown field still returns `400` naming the
  offending property in `details`.
- Multipart controllers that parse their JSON `data` part directly through `ObjectMapper` now
  produce the same `400` unknown-field error shape as regular JSON endpoints, instead of
  falling through to a generic failure.
- Donation contact fields relaxed to match the live form: `email` is optional, and the archive
  donation `title` and `description` are optional free text.
- The Video API is documented in OpenAPI: multipart part names, the FILM / VIDEO_CLIP file
  mapping, the filter parameters (`videoType`, `memories`, `topicId`, `page`, `size`) and the
  topic assign / create / clear flow all appear in Swagger UI.

### Removed
- The root-level duplicates under `new documentation/` — `ABOUT_API.md`, `CONTACT_API.md`,
  `FEATURED_API.md`, `IMAGE_COLLECTION_API.md`, `NEWS_API.md`, `PROJECT_API.md`,
  `SERVICE_API.md`, `SOUNDTRACK_API.md`, `USER_AUTH_API.md`, `VIDEO_API.md`, `WRITING_API.md`
  — superseded by the `external/` + `internal/` split.

### Security
- CORS allow-list extended with the Railway dashboard and website origins
  (`https://khi-dashboard-production.up.railway.app`,
  `https://khi-website-production.up.railway.app`).

## [0.3.0] - 2026-06-30

### Added
- OpenAPI 3 and Swagger UI via springdoc-openapi 2.8.6, served at `/swagger-ui.html` and
  `/v3/api-docs`, scanning `/api/**`. Authorization is persisted across page reloads and the
  operation list starts collapsed. Every controller carries an OpenAPI tag.
- Public-site content API on `/api/v1`: homepage featured items (`/featured`), team
  (`/about/team`), partners (`/about/partners`), contact messages (`/contact/messages`),
  social links (`/settings/social`) and donations (`/donations/settings`, `/donations/types`,
  `/donations/financial`, `/donations/archive`), each with full CRUD for the dashboard.
- Visitor submissions that require no login: `POST /api/v1/contact/messages`,
  `POST /api/v1/donations/financial` and `POST /api/v1/donations/archive`.
- Featured toggles on every content type:
  `PATCH /api/v1/{news|projects|writings|videos|sound-tracks|image-collections}/{id}/featured`,
  taking `{ "featured": true, "featuredOrder": 1 }` and clearing the order on unfeature.
- `SiteSettings` with a configurable cap on how many records may occupy the homepage hero at
  once, enforced when featuring.
- Sitemap generation (`SitemapService`) over the public content types.
- Video cast members and highlight clips (`VideoCastMember`, `VideoHighlightClip`).
- Compatibility route `GET /featured` without the `/api/v1` prefix, for the public-site client
  as currently deployed.
- Two full documentation sets: `docs_v2/` (one page per module) and `new documentation/`,
  the latter split into `external/` (integrator-facing) and `internal/` (implementation)
  variants.

### Changed
- **Breaking:** featured toggles moved off the shared admin prefix. What was
  `PATCH /api/v1/admin/{type}/{id}/featured` now lives on each type's own controller at
  `PATCH /api/v1/{type}/{id}/featured`.

### Fixed
- The JWT filter no longer runs on the public auth endpoints (`/api/auth/register`,
  `/register-with-image`, `/login`, `/reset-token`, `/reset-password`, `/api/users/auth/**`),
  so a stale or malformed token can no longer make logging in itself fail.
- Restored the authorization rules in `SecurityConfig` after a commit earlier the same day
  commented the entire rule set out, leaving `.anyRequest().authenticated()` as the only rule
  and putting every public read behind a login.

### Security
- `/api/v1/media/**`, the shared S3 upload pipeline, restricted to `ADMIN` / `SUPER_ADMIN`,
  with the rule placed ahead of the blanket public `GET /api/v1/**` allowance so no future
  media read endpoint is exposed by accident.
- Contact writes (`POST /api/v1/contact`, `PUT` and `DELETE /api/v1/contact/**`) restricted to
  `ADMIN` / `SUPER_ADMIN`, while detail reads stay public.
- Public-site configuration writes (`/api/v1/featured/**`, `/api/v1/about/team/**`,
  `/api/v1/about/partners/**`, `/api/v1/settings/social/**`) restricted to `ADMIN` /
  `SUPER_ADMIN`.
- Reads and status updates on visitor submissions (`GET /api/v1/contact/messages`,
  `GET /api/v1/donations/financial`, `GET /api/v1/donations/archive`, and the corresponding
  `PATCH .../{id}/status`) restricted to `ADMIN` / `SUPER_ADMIN`, so the submit endpoints are
  public but the collected data is not.

## [0.2.0] - 2026-05-24

### Added
- Shared media upload API for the Tiptap editor: `POST /api/v1/media/upload`,
  `POST /api/v1/media/upload/multiple` and `DELETE /api/v1/media?fileUrl=…`. The editor
  uploads a file, gets back a `fileUrl`, and embeds that URL in the HTML it stores.
- `MediaItem` embeddable plus a `MediaKind` enum, giving About, Contact, News, Project and
  Service records a uniform list of attached media alongside their HTML body.
- `TiptapHtmlProcessor`, which normalises editor HTML on the way in for every module that
  stores rich text.

### Changed
- **Breaking:** News, Projects, About, Contact and Services switched from multipart writes to
  plain JSON. `POST /api/v1/news` and `PUT /api/v1/news/{id}` consume `application/json`; the
  `/with-files` variants and the `news`, `data`, `coverImage`, `cover`, `mediaFiles` and
  `media` parts are gone. Rich content is stored as Tiptap HTML in the bilingual
  `description` / `body` columns.
- **Breaking:** the Sound Track API moved from `/api/v1/soundtracks` to `/api/v1/sound-tracks`.
- **Breaking:** CORS switched from `setAllowedOrigins` to `setAllowedOriginPatterns`, so
  wildcard preview origins such as `https://khi-frontend-*.vercel.app` are accepted.
- Production-oriented defaults in `application.yaml`: SQL logging and SQL comment formatting
  off, devtools restart and livereload off, root log level `WARN` with the application package
  at `INFO`.

### Removed
- **Breaking:** Google OAuth2 login. The `spring-boot-starter-oauth2-client` dependency, the
  `/oauth2/**` and `/login/oauth2/**` routes, `CustomOAuth2User`, `CustomOAuth2UserService`,
  both OAuth2 handlers, and the `spring.security.oauth2` and `app.oauth2.redirect-uri`
  configuration are all gone. Session policy is now `STATELESS` and the JWT cookie is the only
  way in.
- **Breaking:** the per-record media entity model. `NewsMedia`, `ProjectMedia`,
  `ProjectContent`, `AboutBlock`, `AboutBlockContent`, `ServiceMediaCollection`,
  `ServiceMediaFile`, `ServiceMediaFileContent` and their repositories were replaced by Tiptap
  HTML plus `MediaItem`. `NewsMediaType` and the media-specific exception types went with them.
- `MockDataSeeder` and its `app.seed.enabled` flag, added and withdrawn again inside this
  release; bilingual fixtures moved to external files later.
- `spring.web.locale` and `spring.messages` configuration from `application.yaml`; locale
  resolution and the message source are defined in `I18nConfig` alone.

### Security
- Restored environment-only datasource configuration in `application.yaml`. A hard-coded local
  DSN (`jdbc:postgresql://localhost:5432/khi_web_db`, user `khi`, empty password) had been
  committed on 2026-04-14; `${PGHOST}`, `${PGPORT}`, `${PGDATABASE}`, `${PGUSER}` and
  `${PGPASSWORD}` are back in its place, and the Google client id and secret are again
  required rather than defaulting to empty.
- `app.cors.allowed-origins` was pinned to a literal list (Vercel production, Vercel previews,
  `localhost:5173`, `localhost:3000`), deliberately bypassing the `CORS_ALLOWED_ORIGINS`
  environment variable so the deployment platform's dashboard need not be configured. This
  trade-off is still in force — see Known issues under 0.6.0.

## [0.1.0] - 2026-04-14

### Added
- First tracked release of the KHI Backend: Spring Boot 4.0.2 on Java 21, backed by
  PostgreSQL, Redis and AWS S3.
- Content APIs at `/api/v1`: News (`/news`), Projects (`/projects`), About (`/about`),
  Contact (`/contact`), publishment topics (`/topics`), and the publishment family — Image
  Collections (`/image-collections`), Sound Tracks (`/soundtracks`), Videos (`/videos`) and
  Writings (`/writings`).
- Five search axes on the News API: `/api/v1/news/search`, `/search/keyword`, `/search/tag`,
  `/search/category` and `/search/subcategory`; Projects expose `/search/tag` and
  `/search/keyword`.
- Cross-content search at `GET /api/v1/search?q=&type=&page=&size=`, where `type` narrows to a
  single content family or `ALL`.
- Services module (`/api/v1/services`): CRUD over Service records with per-service content,
  media collections, media files and an audit log, plus a repository of filtered and
  admin-only queries.
- JWT authentication over an httpOnly cookie. `POST /api/auth/register`,
  `/register-with-image`, `/login`, `/reset-token`, `/reset-password`, plus authenticated
  `/logout` and `/logout-all`; session listing and revocation at `/api/auth/sessions`;
  self-service profile at `/api/user/**`; user administration at `/api/users/**`, restricted
  to `SUPER_ADMIN`.
- Role model of `SUPER_ADMIN`, `ADMIN` and `EMPLOYEE` with per-method rules: public reads on
  `GET /api/v1/**`, content writes for `EMPLOYEE` and above, content deletes for `ADMIN` and
  above.
- `MediaMetadataExtractor`: pure-Java metadata extraction for images, MP4, MP3 and WAV via
  metadata-extractor 2.19.0, with no `ffprobe` dependency.
- Trilingual error responses. Every error body carries `message` (client locale), `messageEn`
  and `messageKu`, resolved from `i18n/messages_{en,ckb,kmr}.properties`, plus a `traceId`
  produced by `TraceIdFilter` and echoed into every log line.
- A domain exception hierarchy per module (News, Project, Image Collection, Sound Track, Video,
  Writing — each with not-found, conflict, validation, media, storage and internal variants)
  behind an `ErrorCode` enum of about 40 codes.
- Redis cache configuration (`khi:` key prefix, 10-minute TTL, nulls not cached) and JPA
  auditing with an authenticated-user auditor.

### Changed
- CORS consolidated. Two competing `WebMvcConfigurer` beans — one allowing any `localhost:*`
  pattern, the other a fixed pair of ports — were emptied out in favour of a single Spring
  Security `CorsConfigurationSource` driven by the `app.cors.*` properties.
- JWT cookie behaviour made configurable through `jwt.cookie-name`, `cookie-secure`,
  `cookie-http-only`, `cookie-same-site`, `cookie-path` and `cookie-max-age`, with production
  defaults (`Secure`, `HttpOnly`, `SameSite=None`, 24 hours).
- `GlobalExceptionHandler` expanded from a 20-line stub to a full mapping of 15+ exception
  types onto specific statuses, including `409` for duplicate users and unique-constraint
  violations, `423` for locked accounts, `413` for oversized uploads and `405` for wrong verbs.
- Validation switched to `spring-boot-starter-validation`, with request-field patterns
  centralised in `ValidationPatterns` and applied across the register, login, profile-update
  and password-change DTOs.
- Every JWT config value gained a safe default, so a missing environment variable no longer
  prevents the application from starting.

### Removed
- `UserCrudAPI` and `WelcomeAPI`, superseded by `UserAPI` and `UserProfileAPI`.

### Security
- No credential is committed. `application.yaml` reads the Google OAuth2 client id and secret,
  the JWT signing key, the PostgreSQL connection details and the CORS origin list from
  environment variables only (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `JWT_SECRET`,
  `PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER` / `PGPASSWORD`, `CORS_ALLOWED_ORIGINS`), and
  the history begins at a squashed root commit that discarded the earlier tree.
- `/api/v1/services/**` placed behind `ADMIN` / `SUPER_ADMIN` for writes with `GET` left
  public, in the commit titled `fix: remove oauth secrets`.
- Session and logout routes (`/api/auth/sessions/**`, `/api/auth/logout`, `/api/auth/logout-all`)
  require authentication and are matched before the public `/api/auth/**` entries, so they
  cannot be reached anonymously.
