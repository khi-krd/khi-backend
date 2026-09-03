# About API — Internal (Authenticated)

The write half of the About domain. These four endpoints back the admin dashboard's About screen:
creating and editing the bilingual institutional pages, deleting them, and toggling the
page-level `featured` highlight. Every one of them requires a JWT belonging to a user with role
`ADMIN` or `SUPER_ADMIN`. The anonymous read endpoints live in the
[external About API](../external/ABOUT_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/about` |
| **Audience** | Admin dashboard (JWT required, `ADMIN` or `SUPER_ADMIN`) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/about/AboutController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/about/AboutService.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` (featured toggle) |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/about/AboutDTOs.java`, `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java` |
| **Entities** | `About`, `AboutContent` (embeddable), `StatItem` (JSONB element) |
| **Table** | `about_pages` |
| **Response envelope** | **None** — these endpoints return a bare `AboutResponse` or no body at all |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/about` | JWT | `ADMIN`, `SUPER_ADMIN` | Create an About page |
| 2 | `PUT` | `/api/v1/about/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Full replace of an existing page |
| 3 | `DELETE` | `/api/v1/about/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a page |
| 4 | `PATCH` | `/api/v1/about/{id}/featured` | JWT | `ADMIN`, `SUPER_ADMIN` | Feature / unfeature the page and set its hero image |

---

## Authentication and authorization

**Carrying the token.** Two equivalent carriers, resolved in this order by
`JWTAuthenticationFilter`:

1. `Authorization: Bearer <jwt>` header (prefix is literally `"Bearer "`, with the space).
2. The HttpOnly cookie named by the `JWT_COOKIE_NAME` environment variable, set automatically by
   `POST /api/auth/login`. Browsers send it on same-site requests; `curl` must set it explicitly.

Sessions are stateless (`SessionCreationPolicy.STATELESS`) — nothing is stored server-side beyond
the token blacklist that backs logout.

**Where the rules come from.** Two independent layers apply, and both must pass:

| Layer | Rule | Applies to |
|-------|------|------------|
| `SecurityConfig` filter chain | `GET /api/v1/about/**` → `permitAll`, then `/api/v1/about/**` (any other method) → `hasAnyRole("ADMIN","SUPER_ADMIN")` | All four endpoints on this page |
| Method security (`@PreAuthorize`) | `hasAnyRole('ADMIN','SUPER_ADMIN')` | Endpoint 4 only |

`POST`, `PUT` and `DELETE` carry **no** `@PreAuthorize` — their authorization comes entirely from
the catch-all `/api/v1/about/**` rule. `PATCH /{id}/featured` is annotated as well; the annotation
grants exactly the same two roles as the filter chain, so it neither widens nor narrows anything.
It is defence in depth, not a different policy.

> **Note:** because the filter chain runs before the dispatcher, an authorization failure is
> almost always rejected by `SecurityConfig`, not by `@PreAuthorize`. That matters for the
> response body: the `GlobalExceptionHandler`'s `AccessDeniedException` handler — the one that
> produces a proper `ApiErrorResponse` with `"code": "FORBIDDEN"` — only fires for exceptions
> raised **inside** the dispatcher, i.e. from `@PreAuthorize`. A rejection at the filter chain
> never reaches it, so a `403` from these endpoints comes back as Spring Boot's default error
> JSON (`{"timestamp":…,"status":403,"error":"Forbidden","path":…}`) rather than the bilingual
> envelope. Do not parse `code` / `messageEn` out of a `403`.

> **Note:** `SecurityConfig` never calls `.exceptionHandling(...)`, so the project's
> `JwtAuthenticationEntryPoint` bean (`user/exceptions/JwtAuthenticationEntryPoint.java`) is
> never wired in. Spring Security therefore falls back to its default
> `Http403ForbiddenEntryPoint`, which means an **anonymous** request to any of these four
> endpoints answers `403 Forbidden`, not `401 Unauthorized`. Do not use `401` as your
> "log in again" trigger for this domain.

**Token-level failures bypass the envelope entirely.** `JWTAuthenticationFilter` writes its own
minimal JSON when the token itself is bad:

| Situation | Status | Body |
|-----------|--------|------|
| Token expired | `401` | `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}` |
| Token malformed or unverifiable | `403` | `{"error":"INVALID_TOKEN","message":"Invalid token"}` |
| Token blacklisted (user logged out) | `401` | `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}` |

These are **not** `ApiErrorResponse`. There is no `code`, no `traceId`, no `messageKu`. In the
first and third cases the auth cookie is cleared on the way out.

**Roles.** The four roles in the system are `GUEST` (assigned on self-registration), `EMPLOYEE`,
`ADMIN` and `SUPER_ADMIN`. Granted authorities are `ROLE_<NAME>` plus the permission strings
`user:create`, `user:read`, `user:update`, `user:delete`. An `EMPLOYEE` token — which *can* write
News, Projects, Videos, Writings and so on — is **rejected** by every endpoint on this page.
About is admin-only in both directions.

---

## The request DTO — `AboutDTOs.AboutRequest`

Endpoints 1 and 2 share one request body. It is bound with a plain `@RequestBody`; there is
**no** `@Valid` on either handler, and `AboutRequest` declares no `jakarta.validation`
annotations at all. Every rule below is enforced imperatively inside `AboutService`, which throws
`IllegalArgumentException` and therefore always produces `400` with `"code": "BAD_REQUEST"` —
never `"VALIDATION_ERROR"`, and never a populated `fieldErrors` array.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `slugCkb` | string | **Yes** | Non-blank; trimmed; unique across `slug_ckb`; must differ from `slugKmr`; DB column is `varchar(200)` | Sorani route slug |
| `slugKmr` | string | No | Blank → stored as `null`; trimmed; unique across `slug_kmr` when present; must differ from `slugCkb`; `varchar(200)` | Kurmanji route slug |
| `ckbContent` | object | Conditional | See below. At least one of `ckbContent.title` / `kmrContent.title` must be non-blank | Sorani page content |
| `kmrContent` | object | Conditional | Same | Kurmanji page content |
| `stats` | array | No | Entries with all three fields blank are silently dropped. `null` or `[]` → stored as an empty array | Structured statistics strip |
| `founderNameCkb` | string | No | Blank → `null`; trimmed; `varchar(300)` | Founder name, Sorani |
| `founderNameKmr` | string | No | Blank → `null`; trimmed; `varchar(300)` | Founder name, Kurmanji |
| `founderBioCkb` | string | No | Blank → `null`; trimmed; `TEXT` | Founder biography, Sorani. Plain text — **not** Tiptap, not processed for base64 |
| `founderBioKmr` | string | No | Blank → `null`; trimmed; `TEXT` | Founder biography, Kurmanji |
| `founderImageUrl` | string | No | Blank → `null`; trimmed; `TEXT` | S3 URL of the founder portrait. Upload it first via `POST /api/v1/media/upload` |
| `heroVideoUrl` | string | No | Blank → `null`; trimmed; `TEXT` | S3 URL of the page-top video |
| `heroPosterUrl` | string | No | Blank → `null`; trimmed; `TEXT` | S3 URL of the poster frame for `heroVideoUrl` |
| `active` | boolean | No | On create: `null` → `true`. On update: `null` → leave the stored value alone | Controls inclusion in `GET /api/v1/about` |
| `displayOrder` | integer | No | On create: `null` → `0`. On update: `null` → leave the stored value alone | Ascending sort key for the public list |

`featured`, `featuredOrder` and `featureImageUrl` are **not** fields of `AboutRequest`. Sending
them here does nothing; they are written exclusively through endpoint 4.

### `ckbContent` / `kmrContent` — `AboutDTOs.AboutContentRequest`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `title` | string | Conditional | `varchar(300)` | Page heading. At least one of the two languages' titles must be non-blank |
| `subtitle` | string | No | `varchar(500)` | Sub-heading |
| `metaDescription` | string | No | `varchar(2500)` | SEO description |
| `body` | string (HTML) | No | `TEXT`, unlimited | Tiptap editor output — see the media section below |

None of these four are trimmed or blank-normalised; they are stored exactly as sent (after the
`body` passes through `TiptapHtmlProcessor`). A blank `subtitle` is persisted as an empty string,
not as `null`.

### `stats[]` — `AboutDTOs.StatItemDto`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `labelCkb` | string | No | — | Sorani label, e.g. `"کتێب"` |
| `labelKmr` | string | No | — | Kurmanji label, e.g. `"Pirtûk"` |
| `value` | string | No | — | Display value, kept as a string: `"12,400+"`, `"١٨"`, `"2007"` |

An element survives if **any** of the three is non-blank; an element where all three are blank
(or the element itself is `null`) is dropped without error. Order is preserved and stored in the
`stats` JSONB column exactly as sent.

---

## Media: how pictures, video, audio and documents get into an About page

About has **no** file-upload endpoint and **no** multipart handler. All four endpoints on this
page consume `application/json` only. Media reaches an About page by one of two routes.

**Route A — the normal one.** The dashboard uploads each asset once through the shared
`POST /api/v1/media/upload` endpoint (also `ADMIN` / `SUPER_ADMIN` only), receives the S3 URL,
and bakes that URL into the Tiptap document as an `<img src>`, `<video src>`, `<audio src>` or
`<a href>` before submitting the JSON body here. The same applies to `founderImageUrl`,
`heroVideoUrl`, `heroPosterUrl` and `featureImageUrl` — send URLs, never bytes.

**Route B — the safety net.** If the editor leaves an inline base64 payload in the HTML,
`TiptapHtmlProcessor` catches it on save. It runs on `ckbContent.body` and `kmrContent.body`
(and on nothing else) before the row is persisted:

- Matches `src="data:<mime>;base64,…"` on `<img>`, `<video>`, `<audio>`, `<source>` and
  `href="data:<mime>;base64,…"` on `<a>`.
- Decodes each payload, uploads it to S3 under the MIME-derived folder — `images/`, `video/`,
  `audio/` or `files/` — as `khi-web-folders/<folder>/<uuid>-tiptap-<nanotime>.<ext>`.
- Rewrites the attribute to the resulting public URL. The database never holds base64.

Behaviour worth relying on:

- **Idempotent.** HTML containing no `data:` substring is returned untouched (fast early-out), so
  re-saving an unchanged page uploads nothing.
- **Null- and blank-safe.** A `null` or empty `body` passes straight through.
- **Non-fatal on failure.** A malformed base64 payload or a failed S3 upload is logged and the
  original attribute is left in place. The save still succeeds. There is **no** error field in
  the response telling you an asset failed to hoist — verify by re-reading the saved `body` and
  checking for surviving `data:` URIs.

Upload limits are `1GB` per file and `1GB` per request (`spring.servlet.multipart`), but those
bind on `POST /api/v1/media/upload`, not here. What binds here is the JSON body size — a base64
payload inflates by roughly 4/3, so Route B is only viable for small inline images.

S3 target: region `us-east-1`, bucket `s3-khiwebsite`, base folder `khi-web-folders`. Public URLs
are formed as `https://s3-khiwebsite.s3.us-east-1.amazonaws.com/<key>`.

---

## 1. `POST /api/v1/about` — Create an About page

Creates a new row in `about_pages`. Runs slug validation, then content validation, then hoists any
inline base64 from both Tiptap bodies to S3, then saves. The new record is **not** featured — the
entity default is `featured = false` and `AboutRequest` has no way to set it. Use endpoint 4
afterwards.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | None |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

See [the request DTO section](#the-request-dto--aboutdtosaboutrequest) above for the full field
table.

```json
{
  "slugCkb": "dezgay-kelepuri-kurdi",
  "slugKmr": "saziya-mirateya-kurdi",
  "ckbContent": {
    "title": "دەزگای کەلەپووری کوردی",
    "subtitle": "پاراستن و لێکۆڵینەوە لە کەلەپووری کوردی لە سلێمانی",
    "metaDescription": "دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزراوە بۆ کۆکردنەوە و پاراستنی کەلەپووری کوردی.",
    "body": "<h2>مێژووی دەزگا</h2><p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە شاری سلێمانی دامەزرا.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f3c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-archive-hall.jpg\" alt=\"هۆڵی ئەرشیف\"><p>ئێستا زیاتر لە ١٢ هەزار کتێب و دەستنووس دەپارێزێت.</p>"
  },
  "kmrContent": {
    "title": "Saziya Mîrateya Kurdî",
    "subtitle": "Parastin û lêkolîn li ser mîrateya kurdî li Silêmaniyê",
    "metaDescription": "Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin ji bo berhevkirin û parastina mîrateya kurdî.",
    "body": "<h2>Dîroka saziyê</h2><p>Saziya Mîrateya Kurdî di sala 2007an de li bajarê Silêmaniyê hat damezrandin.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f3c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-archive-hall.jpg\" alt=\"Salona arşîvê\"><p>Îro zêdetir ji 12 hezar pirtûk û destnivîs diparêze.</p>"
  },
  "stats": [
    { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,400+" },
    { "labelCkb": "دەستنووس", "labelKmr": "Destnivîs", "value": "3,150" },
    { "labelCkb": "ساڵ خزمەت", "labelKmr": "Sal xizmet", "value": "18" }
  ],
  "founderNameCkb": "د. ئاراس عەبدولڕەحمان",
  "founderNameKmr": "Dr. Aras Evdilrehman",
  "founderBioCkb": "توێژەری کەلەپووری کوردی و دامەزرێنەری دەزگا.",
  "founderBioKmr": "Lêkolînerê mîrateya kurdî û damezrînerê saziyê.",
  "founderImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/4a1d0c62-7b13-4e88-9a5f-2c6d8e0f1a37-founder.jpg",
  "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero.mp4",
  "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero-poster.jpg",
  "active": true,
  "displayOrder": 0
}
```

**Response `201 Created`**

A **bare** `AboutResponse`. There is no `ApiResponse` wrapper on this endpoint — the controller
returns `ResponseEntity.status(CREATED).body(aboutService.create(request))` directly. No
`Location` header is set.

```json
{
  "id": 1,
  "slugCkb": "dezgay-kelepuri-kurdi",
  "slugKmr": "saziya-mirateya-kurdi",
  "ckbContent": {
    "title": "دەزگای کەلەپووری کوردی",
    "subtitle": "پاراستن و لێکۆڵینەوە لە کەلەپووری کوردی لە سلێمانی",
    "metaDescription": "دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزراوە بۆ کۆکردنەوە و پاراستنی کەلەپووری کوردی.",
    "body": "<h2>مێژووی دەزگا</h2><p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە شاری سلێمانی دامەزرا.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f3c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-archive-hall.jpg\" alt=\"هۆڵی ئەرشیف\"><p>ئێستا زیاتر لە ١٢ هەزار کتێب و دەستنووس دەپارێزێت.</p>"
  },
  "kmrContent": {
    "title": "Saziya Mîrateya Kurdî",
    "subtitle": "Parastin û lêkolîn li ser mîrateya kurdî li Silêmaniyê",
    "metaDescription": "Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin ji bo berhevkirin û parastina mîrateya kurdî.",
    "body": "<h2>Dîroka saziyê</h2><p>Saziya Mîrateya Kurdî di sala 2007an de li bajarê Silêmaniyê hat damezrandin.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f3c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-archive-hall.jpg\" alt=\"Salona arşîvê\"><p>Îro zêdetir ji 12 hezar pirtûk û destnivîs diparêze.</p>"
  },
  "active": true,
  "stats": [
    { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,400+" },
    { "labelCkb": "دەستنووس", "labelKmr": "Destnivîs", "value": "3,150" },
    { "labelCkb": "ساڵ خزمەت", "labelKmr": "Sal xizmet", "value": "18" }
  ],
  "founderNameCkb": "د. ئاراس عەبدولڕەحمان",
  "founderNameKmr": "Dr. Aras Evdilrehman",
  "founderBioCkb": "توێژەری کەلەپووری کوردی و دامەزرێنەری دەزگا.",
  "founderBioKmr": "Lêkolînerê mîrateya kurdî û damezrînerê saziyê.",
  "founderImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/4a1d0c62-7b13-4e88-9a5f-2c6d8e0f1a37-founder.jpg",
  "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero.mp4",
  "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero-poster.jpg",
  "displayOrder": 0,
  "featured": false,
  "createdAt": "2026-08-26 09:14:22",
  "updatedAt": "2026-08-26 09:14:22"
}
```

`featuredOrder` and `featureImageUrl` are `null` on a fresh record and therefore omitted from the
JSON (`spring.jackson.default-property-inclusion` is `non_null`).

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `slugCkb` missing or blank — `"CKB slug is required"` |
| `400` | `BAD_REQUEST` | `slugCkb` already used by another row — `"CKB slug already exists: <slug>"` |
| `400` | `BAD_REQUEST` | `slugKmr` already used by another row — `"KMR slug already exists: <slug>"` |
| `400` | `BAD_REQUEST` | `slugCkb` equals `slugKmr` — `"CKB slug and KMR slug must be different: <slug>"` |
| `400` | `BAD_REQUEST` | Neither `ckbContent.title` nor `kmrContent.title` is non-blank — `"At least one localized About title is required"` |
| `400` | `BAD_REQUEST` | Body is empty, is not valid JSON, or contains an unrecognised field |
| `403` | — | Anonymous request (default `Http403ForbiddenEntryPoint`), or a token whose role is `GUEST` / `EMPLOYEE`. Body is Spring Boot's default error JSON, not `ApiErrorResponse` |
| `401` / `403` | — | Token expired / revoked / malformed. Body is `{"error":"…","message":"…"}` from `JWTAuthenticationFilter` |
| `409` | `CONFLICT` | A DB constraint fires despite validation: a concurrent insert wins the unique-slug race, or a value overruns its column (`slug` > 200, `title` > 300, `subtitle` > 500, `metaDescription` > 2500) |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

A slug conflict looks like this. Note that `code` is `BAD_REQUEST`, not `CONFLICT`, and that
`fieldErrors` is `null` (and therefore absent) because no bean validation ran:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/about",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "BAD_REQUEST",
  "message": "CKB slug already exists: dezgay-kelepuri-kurdi",
  "messageEn": "CKB slug already exists: dezgay-kelepuri-kurdi",
  "messageKu": "CKB slug already exists: dezgay-kelepuri-kurdi",
  "details": { "reason": "CKB slug already exists: dezgay-kelepuri-kurdi" }
}
```

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/about \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "slugCkb": "dezgay-kelepuri-kurdi",
        "slugKmr": "saziya-mirateya-kurdi",
        "ckbContent": {
          "title": "دەزگای کەلەپووری کوردی",
          "body": "<p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزرا.</p>"
        },
        "kmrContent": {
          "title": "Saziya Mîrateya Kurdî",
          "body": "<p>Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin.</p>"
        },
        "stats": [
          { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,400+" }
        ],
        "active": true,
        "displayOrder": 0
      }'
```

---

## 2. `PUT /api/v1/about/{id}` — Replace an About page

Loads the record, re-runs the same slug and content validation as create (excluding this row from
the uniqueness check), re-processes both Tiptap bodies, and saves. `updatedAt` is refreshed by
Hibernate's `@UpdateTimestamp`. `featured`, `featuredOrder` and `featureImageUrl` are untouched.

**This is a full replace, not a patch.** Read the callout below before you build the form.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | int64 | Yes | Primary key of the About record. Slugs are **not** accepted here — unlike the public detail endpoint, this route binds to `Long` |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

Identical schema to `POST`. The two update-specific behaviours:

| Field group | Behaviour when the field is omitted or `null` |
|-------------|-----------------------------------------------|
| `active`, `displayOrder` | **Preserved.** The service only assigns them when the incoming value is non-null |
| `slugCkb` | Required — omitting it is a `400`, it is never preserved |
| `slugKmr`, `ckbContent`, `kmrContent`, `stats`, `founderNameCkb`, `founderNameKmr`, `founderBioCkb`, `founderBioKmr`, `founderImageUrl`, `heroVideoUrl`, `heroPosterUrl` | **Overwritten with empty.** `slugKmr` and all founder/hero fields become `null`; `ckbContent` / `kmrContent` become a blank `AboutContent`; `stats` becomes `[]` |

> **Note:** there is no partial-update path for About. `AboutService.update()` unconditionally
> reassigns every field in the second group from the request. Submitting
> `{"slugCkb":"dezgay-kelepuri-kurdi","ckbContent":{"title":"…"}}` to fix a typo in the Sorani
> title will silently wipe `slugKmr`, the entire `kmrContent`, every stat, both founder names,
> both founder bios, the founder portrait, the hero video and the hero poster. Always `GET` the
> record first, mutate the object you got back, and `PUT` the whole thing.

```json
{
  "slugCkb": "dezgay-kelepuri-kurdi",
  "slugKmr": "saziya-mirateya-kurdi",
  "ckbContent": {
    "title": "دەزگای کەلەپووری کوردی",
    "subtitle": "پاراستن و لێکۆڵینەوە لە کەلەپووری کوردی لە سلێمانی",
    "metaDescription": "دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزراوە.",
    "body": "<h2>مێژووی دەزگا</h2><p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە شاری سلێمانی دامەزرا و ئێستا زیاتر لە ١٢ هەزار کتێب دەپارێزێت.</p>"
  },
  "kmrContent": {
    "title": "Saziya Mîrateya Kurdî",
    "subtitle": "Parastin û lêkolîn li ser mîrateya kurdî li Silêmaniyê",
    "metaDescription": "Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin.",
    "body": "<h2>Dîroka saziyê</h2><p>Saziya Mîrateya Kurdî di sala 2007an de hat damezrandin û îro zêdetir ji 12 hezar pirtûk diparêze.</p>"
  },
  "stats": [
    { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,900+" },
    { "labelCkb": "دەستنووس", "labelKmr": "Destnivîs", "value": "3,220" },
    { "labelCkb": "ساڵ خزمەت", "labelKmr": "Sal xizmet", "value": "19" }
  ],
  "founderNameCkb": "د. ئاراس عەبدولڕەحمان",
  "founderNameKmr": "Dr. Aras Evdilrehman",
  "founderBioCkb": "توێژەری کەلەپووری کوردی و دامەزرێنەری دەزگا.",
  "founderBioKmr": "Lêkolînerê mîrateya kurdî û damezrînerê saziyê.",
  "founderImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/4a1d0c62-7b13-4e88-9a5f-2c6d8e0f1a37-founder.jpg",
  "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero.mp4",
  "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero-poster.jpg",
  "active": true,
  "displayOrder": 0
}
```

An `id` key in the body is tolerated on purpose — `JacksonConfig` installs a
`DeserializationProblemHandler` that skips an unknown `id` property and only that one, so you can
`PUT` a response-shaped payload straight back. Every other unknown field is still rejected.

**Response `200 OK`**

A bare `AboutResponse`, same shape as the `201` body above, with `updatedAt` advanced and
`featured` / `featuredOrder` / `featureImageUrl` reflecting whatever endpoint 4 last set.

```json
{
  "id": 1,
  "slugCkb": "dezgay-kelepuri-kurdi",
  "slugKmr": "saziya-mirateya-kurdi",
  "ckbContent": {
    "title": "دەزگای کەلەپووری کوردی",
    "subtitle": "پاراستن و لێکۆڵینەوە لە کەلەپووری کوردی لە سلێمانی",
    "metaDescription": "دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزراوە.",
    "body": "<h2>مێژووی دەزگا</h2><p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە شاری سلێمانی دامەزرا و ئێستا زیاتر لە ١٢ هەزار کتێب دەپارێزێت.</p>"
  },
  "kmrContent": {
    "title": "Saziya Mîrateya Kurdî",
    "subtitle": "Parastin û lêkolîn li ser mîrateya kurdî li Silêmaniyê",
    "metaDescription": "Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin.",
    "body": "<h2>Dîroka saziyê</h2><p>Saziya Mîrateya Kurdî di sala 2007an de hat damezrandin û îro zêdetir ji 12 hezar pirtûk diparêze.</p>"
  },
  "active": true,
  "stats": [
    { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,900+" },
    { "labelCkb": "دەستنووس", "labelKmr": "Destnivîs", "value": "3,220" },
    { "labelCkb": "ساڵ خزمەت", "labelKmr": "Sal xizmet", "value": "19" }
  ],
  "founderNameCkb": "د. ئاراس عەبدولڕەحمان",
  "founderNameKmr": "Dr. Aras Evdilrehman",
  "founderBioCkb": "توێژەری کەلەپووری کوردی و دامەزرێنەری دەزگا.",
  "founderBioKmr": "Lêkolînerê mîrateya kurdî û damezrînerê saziyê.",
  "founderImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/4a1d0c62-7b13-4e88-9a5f-2c6d8e0f1a37-founder.jpg",
  "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero.mp4",
  "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero-poster.jpg",
  "displayOrder": 0,
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/b7e3f108-9c4a-42d6-8e15-3f7a6b2c9d40-about-feature.jpg",
  "createdAt": "2026-03-11 08:42:17",
  "updatedAt": "2026-08-26 09:31:50"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Same five validation failures as `POST` (missing `slugCkb`, either slug taken by a *different* row, slugs identical, no localized title) |
| `400` | `BAD_REQUEST` | Body empty, malformed JSON, or an unrecognised field other than `id` |
| `403` | — | Anonymous, or role below `ADMIN` |
| `404` | `NOT_FOUND` | No record with that id — `details.resource` is `"About not found: <id>"` |
| `409` | `CONFLICT` | Unique-slug race, or a value overruns its column length |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number (`PUT /api/v1/about/abc`) — the type-mismatch has no dedicated handler and falls through to the catch-all; also any unexpected fault |

```json
{
  "timestamp": "2026-08-26T09:31:50Z",
  "status": 404,
  "path": "/api/v1/about/99",
  "method": "PUT",
  "traceId": "8b7a6d5c-4e33-4c0d-9f1e-3f9c1b7e2a44",
  "code": "NOT_FOUND",
  "message": "About not found: 99",
  "messageEn": "About not found: 99",
  "messageKu": "About not found: 99",
  "details": { "resource": "About not found: 99" }
}
```

Note the message wording differs between endpoints: the public reads say `"About page not
found: …"`, while `PUT` and `DELETE` say `"About not found: …"`. Match on `status` and `code`,
not on the string.

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/about/1 \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @about-1-full.json
```

---

## 3. `DELETE /api/v1/about/{id}` — Delete an About page

Hard-deletes the row. There is no soft-delete on this path — set `active: false` through `PUT` if
you want the page hidden but recoverable. The service loads the entity first so a missing id
returns a clean `404` rather than a silent no-op, then calls `aboutRepository.delete(about)` and
logs `Deleted about page id=<id>`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** not applicable (no request body)

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | int64 | Yes | Primary key of the About record |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

None. Any body sent is ignored.

**Response `204 No Content`**

Empty body. No `ApiResponse` envelope, no confirmation payload.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Anonymous, or role below `ADMIN` |
| `404` | `NOT_FOUND` | No record with that id — `"About not found: <id>"` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number; unexpected fault |

**Example**

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X DELETE http://localhost:8080/api/v1/about/3 \
  -H "Authorization: Bearer $KHI_TOKEN"
```

> **Note:** deletion is Postgres-only. The S3 objects referenced from `body_ckb`, `body_kmr`,
> `founderImageUrl`, `heroVideoUrl`, `heroPosterUrl` and `featureImageUrl` stay in the bucket
> forever. `AboutService.delete()` never touches `S3Service`. Every About page you delete leaks
> its media; budget for a periodic orphan sweep against `khi-web-folders/`.

---

## 4. `PATCH /api/v1/about/{id}/featured` — Feature or unfeature a page

Toggles the page-level highlight. This is the **only** way to write `featured`, `featuredOrder`
and `featureImageUrl` — none of the three is a field of `AboutRequest`, so the ordinary About form
cannot touch them. Handled by `SiteContentService.setAboutFeatured()`, not `AboutService`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
(both `SecurityConfig` and `@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")`)
**Content-Type:** `application/json`

### What featuring an About page actually does

About's `featured` flag is a **page-level highlight**, not a homepage carousel slide. Three
consequences, all visible in `SiteContentService`:

1. **No slide cap.** The six publication types (News, Projects, Writings, Videos, Sound Tracks,
   Image Collections) and the Donation settings row all run a
   `countAllFeatured() >= getMaxFeaturedSlides()` check before turning `featured` on. About does
   **not**. Any number of About pages may be featured at once, and featuring one never blocks a
   news article from being featured.
2. **Not in `GET /api/v1/featured`.** `getFeatured()` explicitly skips About when it pools the
   hero carousel. A featured About page will never appear in that response, nor in the legacy
   `GET /featured` alias.
3. **`featureImageUrl` is mandatory, not an override.** For the six publication types
   `featureImageUrl` overrides a cover image that already exists. About has no cover — every
   picture lives inline in the Tiptap body — so `featureImageUrl` is the *only* image the
   highlight can render. The service therefore refuses to turn `featured` on while it is blank.

The controller Javadoc is explicit that this is ordinary page content, which is why `SUPER_ADMIN`
may write it — unlike the six carousel toggles, which the project keeps `ADMIN`-only.

To render the public About page: fetch `GET /api/v1/about`, keep the records with
`featured: true`, sort by `featuredOrder` ascending. The first leads `/about`; the rest render as
highlighted sections below it.

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | int64 | Yes | Primary key of the About record |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body — `SiteContentDtos.FeaturedRequest`**

`FeaturedRequest` is a shared DTO used by every featured toggle in the codebase. Only three of its
fields are read on this path; the rest are inert here.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | boolean | No | `null` or omitted → treated as **`true`** | `true` features the page, `false` unfeatures it |
| `featuredOrder` | integer | No | — | Highlight order, lower shows first. Set only when featuring; **cleared to `null`** whenever `featured` resolves to `false` |
| `featureImageUrl` | string | No | Tri-state, see below | Wide hero image for the About page highlight |

`featureImageUrl` is tri-state and the distinction matters:

| Sent as | Effect |
|---------|--------|
| omitted / `null` | Leave the stored value alone. This is what makes a plain feature/unfeature call work without re-sending the image |
| `""` (or whitespace) | Clear it to `null`. Combined with `featured: true` this fails validation |
| a URL | Trimmed and stored |

The order of operations is: apply `featureImageUrl` if it was sent → if turning on and the stored
`featureImageUrl` is now blank, reject with `400` → write `featured` → write or clear
`featuredOrder` → save.

The remaining `FeaturedRequest` fields — `type`, `slug`, `title`, `description`, `imageUrl`,
`imageAlt`, `locale`, `displayOrder`, `active` — are **ignored** by `setAboutFeatured()`. Send
them and nothing happens.

> **Note:** `FeaturedRequest` declares `@NotBlank` on `type`, `slug`, `title`, `description` and
> `imageUrl`, which would make all five mandatory. Those constraints never fire, because the
> handler binds the body with a plain `@RequestBody` and no `@Valid` — the same is true of all
> nine controllers that use this DTO. The five fields are effectively optional and unused. If
> `@Valid` is ever added, every existing client of this endpoint breaks at once.

Feature a page and set its hero image:

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/b7e3f108-9c4a-42d6-8e15-3f7a6b2c9d40-about-feature.jpg"
}
```

Re-feature or re-order without touching the stored image:

```json
{
  "featured": true,
  "featuredOrder": 2
}
```

Unfeature (the stored `featureImageUrl` survives, `featuredOrder` is cleared to `null`):

```json
{
  "featured": false
}
```

An empty object `{}` is a valid body and means "feature this page, keep the stored image, clear
`featuredOrder`" — because `featured: null` resolves to `true` and `featuredOrder: null` is
written as-is.

**Response `204 No Content`**

Empty body. The controller returns `ResponseEntity.noContent().build()`. Re-read the record
through `GET /api/v1/about/{id}` if you need the resulting state.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Turning `featured` on while the stored `featureImageUrl` is blank (and the request did not supply one) — `"featureImageUrl is required to feature an About page — it becomes the About page hero image."` |
| `400` | `BAD_REQUEST` | Body is malformed JSON, or carries an unrecognised field |
| `403` | — | Anonymous, or role below `ADMIN`. `EMPLOYEE` is rejected here even though it may PATCH videos |
| `404` | `NOT_FOUND` | No record with that id — `"About page not found: <id>"` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number; unexpected fault |

```json
{
  "timestamp": "2026-08-26T09:40:11Z",
  "status": 400,
  "path": "/api/v1/about/1/featured",
  "method": "PATCH",
  "traceId": "2a44c0d9-f1e8-4b7a-6d5c-4e333f9c1b7e",
  "code": "BAD_REQUEST",
  "message": "featureImageUrl is required to feature an About page — it becomes the About page hero image.",
  "messageEn": "featureImageUrl is required to feature an About page — it becomes the About page hero image.",
  "messageKu": "featureImageUrl is required to feature an About page — it becomes the About page hero image.",
  "details": {
    "reason": "featureImageUrl is required to feature an About page — it becomes the About page hero image."
  }
}
```

**Example**

```bash
# feature, with the hero image
curl -s -o /dev/null -w '%{http_code}\n' \
  -X PATCH http://localhost:8080/api/v1/about/1/featured \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "featured": true,
        "featuredOrder": 1,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/b7e3f108-9c4a-42d6-8e15-3f7a6b2c9d40-about-feature.jpg"
      }'

# unfeature
curl -s -o /dev/null -w '%{http_code}\n' \
  -X PATCH http://localhost:8080/api/v1/about/1/featured \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": false}'
```

---

## Enums used by this API

No field on any of these four endpoints is enum-typed. Two enums are relevant indirectly:

| Enum | Values | Relevance |
|------|--------|-----------|
| `Language` (`khi_app.enums.Language`) | `CKB` — Central Kurdish (Sorani); `KMR` — Northern Kurdish (Kurmanji) | Never serialised in About payloads. The language is encoded in the field name instead: `slugCkb` / `slugKmr`, `ckbContent` / `kmrContent`, `labelCkb` / `labelKmr`, `founderNameCkb` / `founderNameKmr`, `founderBioCkb` / `founderBioKmr` |
| `ProjectMediaType` | `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT` | Used internally by `TiptapHtmlProcessor` to choose the S3 folder (`images/`, `video/`, `audio/`, `files/`) for a hoisted base64 asset. Never appears in an About request or response |

`ErrorCode` values you can actually see from this domain: `BAD_REQUEST`, `NOT_FOUND`, `CONFLICT`,
`METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`. There is no About-specific `ErrorCode` — unlike News
(`NEWS_NOT_FOUND`), Videos (`VIDEO_VALIDATION`) or Writings (`WRITING_NOT_FOUND`), the About
module throws plain `EntityNotFoundException` / `IllegalArgumentException` and lands on the
generic codes.

---

## Notes & gotchas

**`PUT` is destructive by omission.** Stated above and worth repeating: every field except
`active` and `displayOrder` is unconditionally overwritten from the request. Round-trip the full
object.

**Slug uniqueness is checked per column, and that leaves a hole.** `validateSlugs()` compares
`slugCkb` only against `slug_ckb` and `slugKmr` only against `slug_kmr`. Row A with
`slugCkb = "armancen-me"` and row B with `slugKmr = "armancen-me"` both pass validation and both
satisfy the DB unique constraints (which are also per column). The public
`GET /api/v1/about/slug/armancen-me` then matches two rows through
`findBySlugCkbOrSlugKmr`, Spring Data cannot squeeze them into an `Optional`, and the read fails
with `500 INTERNAL_ERROR`. Enforce cross-language slug uniqueness in the dashboard; the backend
does not.

**Slugs are stored verbatim.** There is no slugification step anywhere in this domain — no
lowercasing, no transliteration, no space-to-hyphen, no character whitelist. Whatever string you
send (after `trim()`) becomes the URL segment. `slugCkb: "ئامانجەکانمان"` is accepted and stored
as-is; the public route then requires percent-encoding. Generate clean ASCII slugs client-side.

**Numeric slugs are unreachable through `GET /api/v1/about/{identifier}`.** That route parses
numbers as ids first. A page with `slugCkb: "2007"` can only be read through
`GET /api/v1/about/slug/2007`. Avoid purely numeric slugs.

**`team` and `partners` are reserved segments.** `POST|PUT|DELETE /api/v1/about/team/**` and
`/api/v1/about/partners/**` belong to `PublicSiteController` (team members and partner
organisations) and have their own `SecurityConfig` entries. Do not create About pages with those
slugs — the literal routes win over `/{identifier}`.

**No caching, no eviction needed.** `AboutService` declares no `@Cacheable` or `@CacheEvict`, and
`about` is not one of the Redis cache names in `CacheConfig` (`news`, `projects`, `soundTracks`,
`imageCollections`, `services`). A write here is visible on the very next public read — there is
no 10-minute TTL to flush. `SiteContentService.setAboutFeatured()` likewise carries no
`@CacheEvict`, unlike its `setServiceFeatured()` sibling, and correctly so: nothing caches About.

**`featured` cannot be set at creation time.** A new record is always `featured: false`. Creating
and featuring a page is a two-call sequence: `POST /api/v1/about`, then
`PATCH /api/v1/about/{id}/featured` with a `featureImageUrl`.

**Both response bodies are unwrapped.** `POST` and `PUT` return a bare `AboutResponse`; `DELETE`
and `PATCH` return nothing. Only the three public `GET` endpoints use the
`ApiResponse<T>` envelope. If your dashboard client unwraps `response.data.data` globally, it
will break on this controller.

**Timestamps are UTC strings.** `createdAt` / `updatedAt` are pre-formatted in `AboutService` as
`yyyy-MM-dd HH:mm:ss` from a `LocalDateTime` that Postgres stores in UTC
(`hibernate.jdbc.time_zone=UTC`). They carry no offset and are not converted to `Asia/Baghdad`.
The `timestamp` on error bodies is a real `Instant` and does end in `Z`.

**Nulls are omitted from responses.** `spring.jackson.default-property-inclusion` is `non_null`.
An optional field that is null in the database is absent from the JSON, not `null`.

**`X-Trace-Id`.** `TraceIdFilter` accepts an inbound `X-Trace-Id`, echoes it on the response, and
puts it in `ApiErrorResponse.traceId`. Send one per dashboard action to make server logs
searchable.

**Localised error text barely exists on this path.** `messageEn` and `messageKu` are always
populated, but for every About error they are set to the raw exception message — English, from
the service. Sending `Accept-Language: ckb` swaps the top-level `message` to the Sorani bundle
string for the generic key (`error.bad_request` → a Sorani phrase), which loses the specific
reason. `details.reason` / `details.resource` always carries the precise English text. Key your
UI off `status` and `code`.

> **Note:** the English message bundle is named `" messages_en.properties"` — with a leading
> space — inside `src/main/resources/i18n/`. `I18nConfig` loads
> `classpath:i18n/messages`, so the file never resolves and the `en` locale silently falls back
> to the hard-coded default strings in `GlobalExceptionHandler`. Separately, `messageKu` is
> resolved against `Locale.forLanguageTag("ku")`, for which no bundle exists at all (the project
> ships `messages_ckb` and `messages_kmr`), so it always falls back too. Net effect: the
> `messages_ckb` / `messages_kmr` bundles only ever influence the top-level `message` field, and
> only when the client sends `Accept-Language: ckb` or `kmr`.

**Live spec.** Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs`. Use the **Authorize**
button to paste a Bearer token; `persist-authorization` is on, so it survives a page reload.
About is tagged **About**. Note that springdoc's `internal` group (`/v3/api-docs/internal`) only
matches `/api/auth/**`, `/api/user/**` and `/api/v1/media/**` — these four admin endpoints appear
in the `public` group instead, because that group matches `/api/v1/about/**` wholesale. Group
membership is a documentation convenience, not an authorization statement; `SecurityConfig` is
the only authority.

**Servers.** `http://localhost:8080` locally; production is deployed on Railway. The
`https://api.khi.local` server listed in `OpenApiConfig` is an unresolved placeholder.

---

## Related documentation

- Counterpart (public reads): [`../external/ABOUT_API.md`](../external/ABOUT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
