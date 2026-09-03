# Services API — Internal (Authenticated)

Everything the admin dashboard needs to run the services catalogue: the two admin reads that expose
inactive records, full create/update, the soft-active toggle, the Services-page featured toggle, and
single plus bulk delete. All of it requires a JWT carrying `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`. The
read-only routes the public website uses are in the [external counterpart](../external/SERVICE_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/services` |
| **Audience** | Admin dashboard (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/service/ServiceController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/service/ServiceService.java` |
| **Featured writer** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` (`setServiceFeatured`) |
| **DTOs** | `ServiceDTOs.java`, `SiteContentDtos.FeaturedRequest` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/service/ServiceRepository.java` |
| **Entities** | `Service`, `ServiceContent`, `ServiceMedia`, `ServiceAuditLog` |
| **Authorization** | `SecurityConfig`: `GET` on `/api/v1/services/admin/**` and `/api/v1/services/search/admin` → `hasAnyRole("ADMIN","SUPER_ADMIN")`; every non-`GET` method on `/api/v1/services/**` → `hasAnyRole("ADMIN","SUPER_ADMIN")`. Plus `@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")` on the featured toggle. |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either way — the filter checks the header first, then the cookie:

```
Authorization: Bearer <token>
```

or the HttpOnly cookie named by `${JWT_COOKIE_NAME}`. Sessions are stateless
(`SessionCreationPolicy.STATELESS`), so every request must carry the token. `EMPLOYEE` is **not**
enough anywhere in this domain — services writes require `ADMIN` or `SUPER_ADMIN`, unlike the
`/api/v1/news`, `/api/v1/projects` and other content modules where `EMPLOYEE` may create and update.

Failure modes before the controller is reached. **None of them produce the `ApiErrorResponse` shape** —
they are all raised inside the servlet filter chain, before the `@RestControllerAdvice` can see them:

| Situation | Status | Body |
|-----------|--------|------|
| No token at all | `403` | Spring Boot default error JSON — `{"timestamp","status","error","path"}` |
| Valid token, role below `ADMIN` | `403` | Spring Boot default error JSON |
| Token present but expired | `401` | `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}` |
| Token malformed / signature invalid | `403` | `{"error":"INVALID_TOKEN","message":"Invalid token"}` |
| Token blacklisted after logout | `401` | `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}` |

The last three are two-field JSON written directly by `JWTAuthenticationFilter`, which also clears the
auth cookie on the way out. Do not branch on `code` for authorization failures — branch on the status.

> **Note:** the first two rows are `403`, not `401`, even for a completely anonymous caller.
> `SecurityConfig` never calls `.exceptionHandling(...)`, so Spring Security's defaults apply:
> `Http403ForbiddenEntryPoint` for anonymous requests and `AccessDeniedHandlerImpl` for
> authenticated-but-under-privileged ones. Both call `response.sendError(403)`, which Boot renders
> through `/error`. The repository does contain a `JwtAuthenticationEntryPoint` (which would answer
> `401`) and a `JwtAccessDeniedHandler`, both annotated `@Component`, but nothing wires them into the
> filter chain — they have no references anywhere in `src/`.
>
> `GlobalExceptionHandler`'s `AccessDeniedException` handler — the one that returns
> `ApiErrorResponse` with `code: FORBIDDEN` and `details.path` / `details.method` — only fires for
> method-security denials that reach the controller layer. In this domain the URL rules in
> `SecurityConfig` always deny first, so that body is effectively unreachable here, including on the
> `@PreAuthorize`-annotated featured toggle.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/services/admin/all` | JWT | `ADMIN`, `SUPER_ADMIN` | Every service including inactive ones, paginated |
| 2 | `GET` | `/api/v1/services/search/admin` | JWT | `ADMIN`, `SUPER_ADMIN` | Search across all services including inactive ones |
| 3 | `POST` | `/api/v1/services` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a service |
| 4 | `PUT` | `/api/v1/services/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Full replace of a service |
| 5 | `PATCH` | `/api/v1/services/{id}/active` | JWT | `ADMIN`, `SUPER_ADMIN` | Soft show/hide |
| 6 | `PATCH` | `/api/v1/services/{id}/featured` | JWT | `ADMIN`, `SUPER_ADMIN` | Feature / unfeature on the Services page |
| 7 | `DELETE` | `/api/v1/services/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard delete one service |
| 8 | `DELETE` | `/api/v1/services/bulk` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard delete many services |

All eight are JSON in / JSON out. **There are no multipart endpoints in this domain.** Files are
uploaded once through the shared media pipeline (`POST /api/v1/media/upload`, also
`ADMIN`/`SUPER_ADMIN`) and the returned S3 URL is baked into the JSON body sent here.

---

## Response envelope

Endpoints 1–5, 7 and 8 return the `ApiResponse<T>` envelope:

```json
{
  "success": true,
  "message": "Service created successfully",
  "data": {}
}
```

Endpoint 6 (`PATCH /{id}/featured`) is the exception — it returns `204 No Content` with an empty body.

`spring.jackson.default-property-inclusion` is `non_null`, so **null fields are omitted**, not emitted
as `null`. On the delete endpoints `data` is null and therefore absent:
`{"success": true, "message": "Service deleted successfully"}`.

List endpoints put a Spring Data `Page` under `data` using the default `DIRECT` serialization —
`content`, `totalElements`, `totalPages`, `number`, `size`, `numberOfElements`, `first`, `last`,
`empty`, plus the `pageable` / `sort` internals. See
[the external doc's page-shape section](../external/SERVICE_API.md#page-shape) for the full example.

---

## 1. `GET /api/v1/services/admin/all` — All services including inactive

The dashboard's list view. Returns every row in `services`, active or not, paginated. This is the only
list endpoint that exposes `active = false` records. Cached in Redis for 10 minutes under
`khi:services::all:p{page}:s{size}`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | None |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | No | `0` | Zero-based page index. Must be `>= 0`. |
| `size` | int | No | `20` | Page size. Must be `>= 1`. No upper bound. |

There is no `?type=` filter on this route — type filtering exists only on the public
`GET /api/v1/services` and covers active services only.

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "All services fetched successfully",
  "data": {
    "content": [
      {
        "id": 18,
        "serviceType": "Workshop",
        "location": "هەولێر — ناوەندی کولتووری کوردی",
        "active": false,
        "sortOrder": 40,
        "layoutType": "DEFAULT",
        "navAnchorId": "manuscript-workshop",
        "galleryMedia": [],
        "featureImageUrls": [],
        "thumbnailUrls": [],
        "partnerIds": [],
        "contents": [
          {
            "id": 55,
            "languageCode": "CKB",
            "title": "وۆرکشۆپی پاراستنی دەستنووسی کوردی",
            "description": "<p>وۆرکشۆپێکی سێ ڕۆژە بۆ فێربوونی ڕێبازەکانی پاراستنی دەستنووس.</p>"
          },
          {
            "id": 56,
            "languageCode": "KMR",
            "title": "Atolyeya Parastina Destnivîsên Kurdî",
            "description": "<p>Atolyeyeke sê rojan li ser rêbazên parastina destnivîsan.</p>"
          }
        ],
        "featured": false,
        "createdAt": "2026-07-02 08:15:00",
        "updatedAt": "2026-07-19 14:02:31"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "empty": true, "sorted": false, "unsorted": true },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 9,
    "totalPages": 1,
    "last": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 9,
    "first": true,
    "empty": false
  }
}
```

Note how `publishedAt`, `heroVideoUrl`, `heroPosterUrl`, `featuredOrder`, `featureImageUrl` and
`featureDescription` are simply missing from this example — they are null on that record.

**Ordering:** `COALESCE(sortOrder, 2147483647) ASC, publishedAt DESC, createdAt DESC`. PostgreSQL puts
`NULL` first on a `DESC` column, so unpublished drafts float to the top of their `sortOrder` bucket.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1`; `details.reason` carries the Spring message |
| `403` | — | No token, or a role below `ADMIN` (Boot default error body, see [Authentication](#authentication)) |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer (see [Gotchas](#non-numeric-path-and-query-values-return-500)) |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/services/admin/all?page=0&size=50" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 2. `GET /api/v1/services/search/admin` — Search including inactive

Same four-column `LIKE '%q%'` search as the public route, minus the `active = true` filter. Covers
`services.service_type`, `services.location`, and every `service_contents.title` and
`service_contents.description` row in both languages. Cached under
`khi:services::adminSearch:{q lowercased}:p{page}:s{size}`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `q` | string | **Yes** | — | Search term, trimmed before use. Must not be blank. |
| `page` | int | No | `0` | Zero-based page index. Must be `>= 0`. |
| `size` | int | No | `20` | Page size. Must be `>= 1`. |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Admin search results fetched",
  "data": {
    "content": [],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "empty": true, "sorted": false, "unsorted": true },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 0,
    "totalPages": 0,
    "last": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 0,
    "first": true,
    "empty": true
  }
}
```

Non-empty results carry the same `ServiceResponse` objects as endpoint 1.

**Ordering:** `publishedAt DESC, createdAt DESC`. `sortOrder` is not applied to search results.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `q` absent; `details.missingParameter` is `"q"` |
| `400` | `BAD_REQUEST` | `q` present but blank; `details.field` is `"q"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |
| `403` | — | No token, or a role below `ADMIN` (Boot default error body, see [Authentication](#authentication)) |
| `500` | `INTERNAL_ERROR` | `page`/`size` not an integer |

> **Note:** the JPQL behind both search routes is
> `SELECT DISTINCT s.id FROM Service s LEFT JOIN s.contents c WHERE … ORDER BY s.publishedAt DESC, s.createdAt DESC`.
> PostgreSQL rejects a `SELECT DISTINCT` whose `ORDER BY` references columns outside the select list
> (`ERROR 42P10`) — the exact rule `ServiceRepository` documents in the comment above
> `findFeaturedWithContents()`, where `DISTINCT` was removed for this reason. A `500 INTERNAL_ERROR`
> on a well-formed `q` is this. Verify against the deployed database before building on it.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/services/search/admin?q=workshop&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 3. `POST /api/v1/services` — Create a service

Creates one service plus its bilingual content rows in a single transaction, writes a `CREATE` row to
`service_audit_logs`, and evicts the entire `services` Redis cache.

Side effects:

- `active` is hard-coded to `true` — a service cannot be created hidden. Follow up with
  [`PATCH /{id}/active?value=false`](#5-patch-apiv1servicesidactive--soft-showhide) to stage a draft.
- `featured`, `featuredOrder` and `featureImageUrl` are ignored here — they are not fields on
  `ServiceRequest`. Use [`PATCH /{id}/featured`](#6-patch-apiv1servicesidfeatured--feature--unfeature).
- `contents[].description` is run through `TiptapHtmlProcessor`, which finds inline `data:` URIs in
  `src`/`href` attributes, uploads the decoded bytes to S3 and rewrites the attribute to the S3 URL.
  HTML with no `data:` substring passes through untouched.
- `contents[].featureDescription` is tag-stripped, whitespace-collapsed and truncated to 1000 chars.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json` (declared with `consumes`; any other content type is rejected)

**Request body** — `ServiceDTOs.ServiceRequest`

There are **no** `jakarta.validation` annotations on `ServiceRequest` and the handler does not use
`@Valid`. Every rule below is enforced imperatively in `ServiceService`, and the first failure wins,
so you get one error at a time rather than a `fieldErrors` array.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `serviceType` | string | **Yes** | Non-blank after trim; column max 100 chars | Free-text type label — the taxonomy is open, see [Service-type taxonomy](#service-type-taxonomy) |
| `location` | string | No | Trimmed; blank becomes `null`; column max 200 chars | Physical or virtual location |
| `publishedAt` | string | No | `yyyy-MM-dd HH:mm:ss` **or** ISO-8601 offset date-time; blank/absent becomes `null` | Explicit publish timestamp. `null` means "never formally published" — it does **not** hide the record |
| `sortOrder` | int | No | Any integer, including negative | Display order on the public page, lower first, `null` last |
| `layoutType` | string | No | Uppercased, then must be one of `MEDIA_HERO`, `FEATURE_GRID`, `DEFAULT`; blank becomes `null` | Rendering hint |
| `heroVideoUrl` | string | No | Trimmed; blank becomes `null`; TEXT column | S3 URL of the section's hero video |
| `heroPosterUrl` | string | No | Trimmed; blank becomes `null`; TEXT column | Poster frame for `heroVideoUrl` |
| `navAnchorId` | string | No | Must match `^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$`; globally unique, case-insensitive; column max 160 chars | Slug used for `#anchor` deep links |
| `galleryMedia` | array of [`MediaItem`](#mediaitem-request) | No | See below | Ordered gallery slots — the recommended model |
| `featureImageUrls` | array of string | No | Nulls and blanks dropped, trimmed, de-duplicated, order preserved | Legacy gallery fallback |
| `thumbnailUrls` | array of string | No | Same cleaning as above | Legacy thumbnail fallback |
| `partnerIds` | array of long | No | Nulls dropped, de-duplicated, order preserved. **Not validated against the `partners` table** | Ids from `GET /api/v1/about/partners` |
| `contents` | array of [`ServiceContentRequest`](#servicecontentrequest) | No | See below | Bilingual rows |

#### `MediaItem` (request)

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `type` | string | No | Uppercased; must be `IMAGE` or `VIDEO` when present | Omit it and the server auto-detects: a URL whose path (query string stripped) ends in `.mp4`, `.webm`, `.mov`, `.m4v`, `.ogv` or `.ogg` becomes `VIDEO`, everything else `IMAGE` |
| `url` | string | **Yes in practice** | Trimmed; a slot with a null/blank URL is **silently dropped**, not rejected. Duplicate URLs within one request are dropped, first occurrence wins | S3 URL of the image or video |
| `posterUrl` | string | No | Trimmed; blank becomes `null` | Poster frame — supply it for `VIDEO` slots |
| `alt` | string | No | Trimmed; blank becomes `null`; column max 500 chars | Accessibility text |

#### `ServiceContentRequest`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `languageCode` | string | **Yes** | Non-blank; uppercased; must be `CKB` or `KMR`; must be unique within the array | Language of this row |
| `title` | string | **Yes** | Non-blank; trimmed; column max 300 chars | Localised title |
| `description` | string | No | TEXT column; Tiptap HTML; inline `data:` URIs hoisted to S3 on save | Rich-text body — this is where all inline media lives |
| `featureDescription` | string | No | HTML stripped, whitespace collapsed, truncated to 1000 chars; empty result becomes `null` | Short plain-text line for the highlight rail |

`contents` may be omitted or empty — `validateContents` returns immediately in that case, so a service
with **zero** titles is created without complaint. It will render as a nameless section on the public
site. Send both `CKB` and `KMR` rows.

```json
{
  "serviceType": "Training",
  "location": "سلێمانی — بنکەی کەلەپووری کوردی",
  "publishedAt": "2026-09-01 09:00:00",
  "sortOrder": 10,
  "layoutType": "MEDIA_HERO",
  "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/archive-training-intro.mp4",
  "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-training-poster.jpg",
  "navAnchorId": "archival-training",
  "galleryMedia": [
    {
      "type": "IMAGE",
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/archive-room-01.jpg",
      "alt": "ژووری ئەرشیف"
    },
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/archive-lesson.mp4",
      "posterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-lesson-poster.jpg",
      "alt": "وانەی ئەرشیفکردن"
    }
  ],
  "featureImageUrls": [],
  "thumbnailUrls": [],
  "partnerIds": [3, 7],
  "contents": [
    {
      "languageCode": "CKB",
      "title": "ڕاهێنان لەسەر ئەرشیفکردنی کەلەپووری کوردی",
      "description": "<p>خولێکی شەش هەفتەیی بۆ فێربوونی ڕێبازە نێودەوڵەتییەکانی ئەرشیفکردن.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-shelf.jpg\" alt=\"ڕەفەی ئەرشیف\">",
      "featureDescription": "خولێکی شەش هەفتەیی لە ئەرشیفکردنی کەلەپووری کوردی."
    },
    {
      "languageCode": "KMR",
      "title": "Perwerdehiya Arşîvkirina Mîrateya Kurdî",
      "description": "<p>Kursek şeş hefteyî ji bo fêrbûna rêbazên navneteweyî yên arşîvkirinê.</p>",
      "featureDescription": "Kursek şeş hefteyî li ser arşîvkirina mîrateya kurdî."
    }
  ]
}
```

The second gallery slot omits `type` on purpose — `.mp4` makes the server store it as `VIDEO`.

**Response `201 Created`**

```json
{
  "success": true,
  "message": "Service created successfully",
  "data": {
    "id": 23,
    "serviceType": "Training",
    "location": "سلێمانی — بنکەی کەلەپووری کوردی",
    "active": true,
    "publishedAt": "2026-09-01 09:00:00",
    "sortOrder": 10,
    "layoutType": "MEDIA_HERO",
    "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/archive-training-intro.mp4",
    "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-training-poster.jpg",
    "navAnchorId": "archival-training",
    "galleryMedia": [
      {
        "type": "IMAGE",
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/archive-room-01.jpg",
        "alt": "ژووری ئەرشیف"
      },
      {
        "type": "VIDEO",
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/archive-lesson.mp4",
        "posterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-lesson-poster.jpg",
        "alt": "وانەی ئەرشیفکردن"
      }
    ],
    "featureImageUrls": [],
    "thumbnailUrls": [],
    "partnerIds": [3, 7],
    "contents": [
      {
        "id": 71,
        "languageCode": "CKB",
        "title": "ڕاهێنان لەسەر ئەرشیفکردنی کەلەپووری کوردی",
        "description": "<p>خولێکی شەش هەفتەیی بۆ فێربوونی ڕێبازە نێودەوڵەتییەکانی ئەرشیفکردن.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-shelf.jpg\" alt=\"ڕەفەی ئەرشیف\">",
        "featureDescription": "خولێکی شەش هەفتەیی لە ئەرشیفکردنی کەلەپووری کوردی."
      },
      {
        "id": 72,
        "languageCode": "KMR",
        "title": "Perwerdehiya Arşîvkirina Mîrateya Kurdî",
        "description": "<p>Kursek şeş hefteyî ji bo fêrbûna rêbazên navneteweyî yên arşîvkirinê.</p>",
        "featureDescription": "Kursek şeş hefteyî li ser arşîvkirina mîrateya kurdî."
      }
    ],
    "featured": false,
    "createdAt": "2026-08-26 09:14:22",
    "updatedAt": "2026-08-26 09:14:22"
  }
}
```

**Errors**

Validation runs in a fixed order, and the first failure aborts the request:
`contents` → `navAnchorId` shape → `navAnchorId` uniqueness → `serviceType` → `publishedAt` →
`layoutType` → `galleryMedia[].type`.

| Status | `code` | When | `details` |
|--------|--------|------|-----------|
| `400` | `BAD_REQUEST` | Two `contents` entries share a `languageCode` | `{"message": "Duplicate language codes in contents list"}` |
| `400` | `BAD_REQUEST` | A `contents` entry has a null/blank `languageCode` | `{"field": "languageCode"}` |
| `400` | `BAD_REQUEST` | `languageCode` is neither `CKB` nor `KMR` | `{"languageCode": "EN", "allowed": ["CKB","KMR"]}` |
| `400` | `BAD_REQUEST` | A `contents` entry has a null/blank `title` | `{"languageCode": "KMR"}` |
| `400` | `BAD_REQUEST` | `navAnchorId` is not slug-shaped | `{"navAnchorId": "Archival Training", "expected": "slug-like, e.g. recording-studio"}` |
| `400` | `BAD_REQUEST` | `navAnchorId` already used by another service (case-insensitive) | `{"navAnchorId": "archival-training"}` |
| `400` | `BAD_REQUEST` | `serviceType` null or blank | `{"field": "serviceType"}` |
| `400` | `BAD_REQUEST` | `publishedAt` parses as neither pattern | `{"expected": "yyyy-MM-dd HH:mm:ss or ISO-8601", "got": "01/09/2026"}` |
| `400` | `BAD_REQUEST` | `layoutType` outside the allowed set | `{"layoutType": "SPLIT", "allowed": ["MEDIA_HERO","FEATURE_GRID","DEFAULT"]}` |
| `400` | `BAD_REQUEST` | `galleryMedia[].type` outside `IMAGE`/`VIDEO` | `{"type": "AUDIO", "allowed": ["IMAGE","VIDEO"]}` |
| `400` | `BAD_REQUEST` | Body missing, empty, or malformed JSON | `{"hint": "..."}` (`error.http.unreadable_body`) |
| `403` | — | No token, or a role below `ADMIN` | Denied in the security filter chain — Boot default error body, see [Authentication](#authentication) |
| `409` | `CONFLICT` | A database unique constraint fires anyway — e.g. a race on `uq_service_content_lang` | `{"hint": "..."}` |
| `500` | `INTERNAL_ERROR` | `Content-Type` is not `application/json`; see [Gotchas](#wrong-content-type-returns-500) |

Example `400`:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/services",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "BAD_REQUEST",
  "message": "Bad request",
  "messageEn": "Bad request",
  "messageKu": "داواکاری هەڵەیە",
  "details": {
    "layoutType": "SPLIT",
    "allowed": ["MEDIA_HERO", "FEATURE_GRID", "DEFAULT"]
  }
}
```

The human-readable reason lives in `details`, not in `message` — see
[Error messages are generic](#error-messages-are-generic).

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/services \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "serviceType": "Training",
    "location": "سلێمانی",
    "publishedAt": "2026-09-01 09:00:00",
    "sortOrder": 10,
    "layoutType": "MEDIA_HERO",
    "navAnchorId": "archival-training",
    "contents": [
      { "languageCode": "CKB", "title": "ڕاهێنان لەسەر ئەرشیفکردن", "description": "<p>خولێکی شەش هەفتەیی.</p>" },
      { "languageCode": "KMR", "title": "Perwerdehiya Arşîvkirinê", "description": "<p>Kursek şeş hefteyî.</p>" }
    ]
  }'
```

---

## 4. `PUT /api/v1/services/{id}` — Full replace

A complete replacement, not a patch. Every writable field is overwritten from the body; **anything you
omit is cleared**. `galleryMedia`, `featureImageUrls`, `thumbnailUrls`, `partnerIds` and `contents` are
each cleared and rebuilt from the request. Always send the full object — read it with
`GET /api/v1/services/{id}` first, mutate, then PUT it back.

Fields that are **not** touched by this endpoint and survive unchanged: `active`, `featured`,
`featuredOrder`, `featureImageUrl`, `createdAt`. Content-row ids are **not** preserved — the old
`service_contents` rows are deleted and new ones inserted, so `contents[].id` changes on every update.

Writes an `UPDATE` row to `service_audit_logs` and evicts the whole `services` cache.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | long | Yes | Primary key of the service to replace |

**Request body**

Identical to [`POST /api/v1/services`](#3-post-apiv1services--create-a-service) — same
`ServiceRequest` DTO, same constraints, same validation order. The `navAnchorId` uniqueness check
excludes the record being updated (`existsByNavAnchorIdIgnoreCaseAndIdNot`), so keeping your own
anchor is fine.

```json
{
  "serviceType": "Training",
  "location": "سلێمانی — بنکەی کەلەپووری کوردی",
  "publishedAt": "2026-09-01 09:00:00",
  "sortOrder": 5,
  "layoutType": "FEATURE_GRID",
  "navAnchorId": "archival-training",
  "galleryMedia": [
    {
      "type": "IMAGE",
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/archive-room-02.jpg",
      "alt": "ژووری ئەرشیف"
    }
  ],
  "featureImageUrls": [],
  "thumbnailUrls": [],
  "partnerIds": [3],
  "contents": [
    {
      "languageCode": "CKB",
      "title": "ڕاهێنان لەسەر ئەرشیفکردنی کەلەپووری کوردی",
      "description": "<p>خولێکی هەشت هەفتەیی، نوێکراوەتەوە بۆ ٢٠٢٦.</p>",
      "featureDescription": "خولێکی هەشت هەفتەیی لە ئەرشیفکردنی کەلەپووری کوردی."
    },
    {
      "languageCode": "KMR",
      "title": "Perwerdehiya Arşîvkirina Mîrateya Kurdî",
      "description": "<p>Kursek heşt hefteyî, ji bo 2026 hat nûkirin.</p>",
      "featureDescription": "Kursek heşt hefteyî li ser arşîvkirina mîrateya kurdî."
    }
  ]
}
```

A stray `"id"` field in the body is harmless — unknown JSON properties are ignored, see
[Unknown body fields are ignored](#unknown-body-fields-are-ignored).

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Service updated successfully",
  "data": {
    "id": 23,
    "serviceType": "Training",
    "location": "سلێمانی — بنکەی کەلەپووری کوردی",
    "active": true,
    "publishedAt": "2026-09-01 09:00:00",
    "sortOrder": 5,
    "layoutType": "FEATURE_GRID",
    "navAnchorId": "archival-training",
    "galleryMedia": [
      {
        "type": "IMAGE",
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/archive-room-02.jpg",
        "alt": "ژووری ئەرشیف"
      }
    ],
    "featureImageUrls": [],
    "thumbnailUrls": [],
    "partnerIds": [3],
    "contents": [
      {
        "id": 88,
        "languageCode": "CKB",
        "title": "ڕاهێنان لەسەر ئەرشیفکردنی کەلەپووری کوردی",
        "description": "<p>خولێکی هەشت هەفتەیی، نوێکراوەتەوە بۆ ٢٠٢٦.</p>",
        "featureDescription": "خولێکی هەشت هەفتەیی لە ئەرشیفکردنی کەلەپووری کوردی."
      },
      {
        "id": 89,
        "languageCode": "KMR",
        "title": "Perwerdehiya Arşîvkirina Mîrateya Kurdî",
        "description": "<p>Kursek heşt hefteyî, ji bo 2026 hat nûkirin.</p>",
        "featureDescription": "Kursek heşt hefteyî li ser arşîvkirina mîrateya kurdî."
      }
    ],
    "featured": false,
    "createdAt": "2026-08-26 09:14:22",
    "updatedAt": "2026-08-26 10:41:07"
  }
}
```

**Errors**

| Status | `code` | When | `details` |
|--------|--------|------|-----------|
| `404` | `NOT_FOUND` | No service with that id (checked before any validation) | `{"id": 999}` |
| `400` | `BAD_REQUEST` | Every validation failure listed under `POST` | as listed there |
| `403` | — | No token, or a role below `ADMIN` | Denied in the security filter chain — Boot default error body, see [Authentication](#authentication) |
| `409` | `CONFLICT` | Database unique-constraint violation | `{"hint": "..."}` |
| `500` | `INTERNAL_ERROR` | `{id}` not numeric, or wrong `Content-Type` | — |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/services/23 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @service-23.json
```

---

## 5. `PATCH /api/v1/services/{id}/active` — Soft show/hide

Flips the `active` flag. This is the soft-delete mechanism: an inactive service disappears from
`GET /api/v1/services`, `GET /api/v1/services/all` and `GET /api/v1/services/search`, but keeps its
row, its content, its media and its id. Writes a `TOGGLE_ACTIVE` audit row and evicts the `services`
cache. Nothing else on the record changes.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** not applicable — the state is a query parameter, not a body

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | long | Yes | Primary key of the service |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `value` | boolean | **Yes** | — | `true` to publish, `false` to hide. Accepts `true`/`false` (Spring also coerces `on`/`off`, `yes`/`no`, `1`/`0`) |

**Request body**

None. Sending one is ignored.

**Response `200 OK`**

The full updated `ServiceResponse`, with the message reflecting the direction —
`"Service activated"` or `"Service deactivated"`.

```json
{
  "success": true,
  "message": "Service deactivated",
  "data": {
    "id": 23,
    "serviceType": "Training",
    "location": "سلێمانی — بنکەی کەلەپووری کوردی",
    "active": false,
    "publishedAt": "2026-09-01 09:00:00",
    "sortOrder": 5,
    "layoutType": "FEATURE_GRID",
    "navAnchorId": "archival-training",
    "galleryMedia": [
      {
        "type": "IMAGE",
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/archive-room-02.jpg",
        "alt": "ژووری ئەرشیف"
      }
    ],
    "featureImageUrls": [],
    "thumbnailUrls": [],
    "partnerIds": [3],
    "contents": [
      {
        "id": 88,
        "languageCode": "CKB",
        "title": "ڕاهێنان لەسەر ئەرشیفکردنی کەلەپووری کوردی",
        "description": "<p>خولێکی هەشت هەفتەیی، نوێکراوەتەوە بۆ ٢٠٢٦.</p>",
        "featureDescription": "خولێکی هەشت هەفتەیی لە ئەرشیفکردنی کەلەپووری کوردی."
      },
      {
        "id": 89,
        "languageCode": "KMR",
        "title": "Perwerdehiya Arşîvkirina Mîrateya Kurdî",
        "description": "<p>Kursek heşt hefteyî, ji bo 2026 hat nûkirin.</p>",
        "featureDescription": "Kursek heşt hefteyî li ser arşîvkirina mîrateya kurdî."
      }
    ],
    "featured": false,
    "createdAt": "2026-08-26 09:14:22",
    "updatedAt": "2026-08-26 11:03:19"
  }
}
```

> **Note:** `setActive` loads the entity with `findById`, not `findByIdWithAll`, so `contents` is
> lazily initialised inside the transaction rather than join-fetched. The response content is correct;
> it just costs one extra query.

> **Note:** deactivating does **not** clear `featured`. A featured-but-inactive service still appears
> in the public `GET /api/v1/services/featured` list, and is still readable by id. Unfeature first if
> you want it gone from the public site entirely.

**Errors**

| Status | `code` | When | `details` |
|--------|--------|------|-----------|
| `400` | `MISSING_PARAMETER` | `value` absent from the URL | `{"missingParameter": "value", "expectedType": "boolean", "hint": "Append '?value=<value>' to your request URL."}` |
| `404` | `NOT_FOUND` | No service with that id | `{"id": 999}` |
| `403` | — | No token, or a role below `ADMIN` | Denied in the security filter chain — Boot default error body, see [Authentication](#authentication) |
| `500` | `INTERNAL_ERROR` | `{id}` not numeric, or `value` is not coercible to a boolean (e.g. `?value=maybe`) | — |

**Example**

```bash
curl -s -X PATCH "http://localhost:8080/api/v1/services/23/active?value=false" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 6. `PATCH /api/v1/services/{id}/featured` — Feature / unfeature

Puts the service into (or takes it out of) the highlight rail inside the public Services page hero.
This is **not** the homepage carousel: featured services take no share of
`SiteSettings.maxFeaturedSlides`, so any number may be featured at once. The write is delegated to
`SiteContentService.setServiceFeatured`, which also evicts the `services` cache so the cached public
lists pick the change up immediately.

This is the only handler in the controller carrying an explicit
`@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")`. It matches what `SecurityConfig` already
enforces for non-`GET` methods on `/api/v1/services/**`, so there is no widening or narrowing —
`SUPER_ADMIN` may write it, unlike the six homepage-carousel toggles which stay `ADMIN`-only.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | long | Yes | Primary key of the service |

**Request body** — `SiteContentDtos.FeaturedRequest`

Only three of the DTO's fields are read by `setServiceFeatured`. Send those; ignore the rest.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | boolean | No | — | `true` or omitted/`null` → feature it. `false` → unfeature it and null out `featuredOrder` |
| `featuredOrder` | int | No | — | Rail position, lower first, `null` sorts last. Only stored when featuring; forced to `null` when unfeaturing |
| `featureImageUrl` | string | No | Tri-state | Omit → leave the stored value alone. `""` → clear it and fall back to the gallery. A URL → trim and store |

> **Note:** `FeaturedRequest` declares `@NotBlank` on `type`, `slug`, `title`, `description` and
> `imageUrl`, but the handler parameter is a plain `@RequestBody` with no `@Valid`, so those
> constraints are never evaluated on this route and those fields are never read. Sending them changes
> nothing; omitting them causes no validation error. The DTO is shared with the homepage-featured
> endpoints in `FeaturedController`, which is where those fields matter.

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/archive-training-wide.jpg"
}
```

To unfeature:

```json
{ "featured": false }
```

**Response `204 No Content`**

Empty body. Re-read the record with `GET /api/v1/services/{id}` if you need the new state.

**Errors**

| Status | `code` | When | `details` |
|--------|--------|------|-----------|
| `404` | `NOT_FOUND` | No service with that id. Message is `"Service not found: 999"` — this path throws `EntityNotFoundException`, so the message is verbatim in all three message fields | `{"resource": "Service not found: 999"}` |
| `400` | `BAD_REQUEST` | Featuring a service that resolves to no rail image at all | `{"reason": "featureImageUrl is required to feature a service that has no gallery image."}` |
| `400` | `BAD_REQUEST` | Body missing or malformed JSON | `{"hint": "..."}` |
| `403` | — | No token, or a role below `ADMIN` | Denied in the security filter chain — Boot default error body, see [Authentication](#authentication) |
| `500` | `INTERNAL_ERROR` | `{id}` not numeric, or wrong `Content-Type` | — |

The rail image is resolved in this order, and the request is rejected only when all of them are
blank: `featureImageUrl` → the first gallery slot with a usable picture (a `VIDEO` slot contributes
its `posterUrl`, an `IMAGE` slot its `url`) → the first non-blank `featureImageUrls` entry →
`heroPosterUrl`. Unfeaturing never runs this check.

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/services/23/featured \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": true, "featuredOrder": 1}'

curl -s -X PATCH http://localhost:8080/api/v1/services/23/featured \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": false}'
```

---

## 7. `DELETE /api/v1/services/{id}` — Delete one service

A hard delete. The row disappears from `services`, and JPA cascades remove the child rows:
`service_contents` (`cascade = ALL`, `orphanRemoval = true`) and the element-collection tables
`service_gallery_media`, `service_feature_images`, `service_thumbnail_images`, `service_partners`.

A `DELETE` audit row is written **before** the delete, inside the same transaction, so the snapshot
survives the record. The `services` cache is fully evicted.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** not applicable

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | long | Yes | Primary key of the service |

**Request body**

None.

**Response `200 OK`**

`data` is null and therefore omitted:

```json
{
  "success": true,
  "message": "Service deleted successfully"
}
```

**Errors**

| Status | `code` | When | `details` |
|--------|--------|------|-----------|
| `404` | `NOT_FOUND` | No service with that id | `{"id": 999}` |
| `403` | — | No token, or a role below `ADMIN` | Denied in the security filter chain — Boot default error body, see [Authentication](#authentication) |
| `500` | `INTERNAL_ERROR` | `{id}` not numeric | — |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/services/23 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 8. `DELETE /api/v1/services/bulk` — Delete many services

Deletes every service in the id list, with the same cascade behaviour as endpoint 7. One `DELETE`
audit row is written per service (`details` reads `"Service bulk-deleted: <serviceType>"`), all saved
in one batch before the deletes. The `services` cache is fully evicted.

This is a `DELETE` **with a request body** — some HTTP clients and proxies strip those. If your
tooling drops the body, `@RequestBody` (required by default) rejects the request with
`400 BAD_REQUEST` and `details.hint` before `deleteBulk` ever runs.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

None.

**Request body** — a bare JSON array of ids (`List<Long>`), not an object.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| *(root)* | array of long | **Yes** | Must be non-null and non-empty | Primary keys to delete |

```json
[23, 24, 27]
```

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Services deleted successfully"
}
```

The response does not report how many rows were actually removed.

> **Note:** the id list is resolved with `findAllById`, and only the ids that exist are deleted. The
> request fails with `404` only when **none** of the ids exist. A mixed list such as
> `[23, 999999]` deletes service 23 and reports success, silently ignoring the missing id. Verify the
> result with `GET /api/v1/services/admin/all` if the dashboard needs an accurate count.

**Errors**

| Status | `code` | When | `details` |
|--------|--------|------|-----------|
| `400` | `BAD_REQUEST` | Body is an empty array `[]` | `{"field": "ids"}` |
| `400` | `BAD_REQUEST` | Body absent, empty, literal `null`, or not a JSON array of numbers | `{"hint": "..."}` (`error.http.unreadable_body`) |
| `404` | `NOT_FOUND` | Not one of the given ids exists | `{"ids": [999998, 999999]}` |
| `403` | — | No token, or a role below `ADMIN` | Denied in the security filter chain — Boot default error body, see [Authentication](#authentication) |
| `500` | `INTERNAL_ERROR` | Wrong `Content-Type` | — |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/services/bulk \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '[23, 24, 27]'
```

---

## Enums used by this API

None of these are Java enums — they are validated string sets in `ServiceService`. Values are
uppercased before the check, so `media_hero` is accepted and stored as `MEDIA_HERO`.

### `layoutType`

| Value | Meaning |
|-------|---------|
| `MEDIA_HERO` | Section leads with a full-bleed hero video or image |
| `FEATURE_GRID` | Section renders its gallery as a grid of feature cards |
| `DEFAULT` | Plain text-and-gallery section |

Omitting the field, or sending `""`, stores `null` — a valid state meaning "no layout hint".

### `galleryMedia[].type`

| Value | Meaning |
|-------|---------|
| `IMAGE` | Still picture — `url` is the image |
| `VIDEO` | Video file — `url` is the video, `posterUrl` is the poster frame |

Omit the field and the server auto-detects from the URL extension (`.mp4`, `.webm`, `.mov`, `.m4v`,
`.ogv`, `.ogg` → `VIDEO`, anything else → `IMAGE`), with the query string stripped before the test.
Any other explicit value is a `400`.

### `contents[].languageCode`

| Value | Meaning |
|-------|---------|
| `CKB` | Sorani (Central Kurdish) |
| `KMR` | Kurmanji (Northern Kurdish) |

Stored as `VARCHAR(10)` with an entity-level `^[A-Z]{2,5}$` pattern, so the schema is ready for more
languages — but `ServiceService.ALLOWED_LANG_CODES` accepts only these two today.
`UNIQUE(service_id, language_code)` means one row per language per service.

### `ServiceAuditLog.action`

Written by the server, never accepted from a client. Not exposed by any endpoint.

| Value | Written by |
|-------|-----------|
| `CREATE` | `POST /api/v1/services` |
| `UPDATE` | `PUT /api/v1/services/{id}` |
| `TOGGLE_ACTIVE` | `PATCH /api/v1/services/{id}/active` |
| `DELETE` | `DELETE /api/v1/services/{id}` and `DELETE /api/v1/services/bulk` |

`PATCH /{id}/featured` writes **no** audit row — it goes through `SiteContentService`, which does not
touch `service_audit_logs`.

---

## Service-type taxonomy

`serviceType` is deliberately free text (`VARCHAR(100)`, indexed as `idx_service_type`) so admins can
introduce a new type without a code change or migration. The values in use today — `Training`,
`Event`, `Program`, `Workshop`, `Studio` — are conventions, not constraints.

Consequences worth knowing:

- **Casing is not normalised on write.** `trimRequired` only trims. `"training"` and `"Training"`
  become two separate entries in `GET /api/v1/services/types`, even though the `?type=` filter matches
  both because it compares `lower(...) = lower(...)`. Pick a casing convention in the dashboard and
  enforce it there.
- **Renaming a type means editing every service that uses it.** There is no type table and no bulk
  rename endpoint.
- **Types are never garbage-collected.** `GET /api/v1/services/types` has no `active` filter, so a
  type that survives only on inactive services still shows up in the public filter list.
- **Deleting the last service of a type removes the type** from `/types` on the next cache miss.

---

## The soft-active flag

`services.active` is a `boolean` defaulting to `true`, indexed as `idx_service_active`. It is the only
visibility control in the domain — there is no draft/published state machine.

| Surface | Respects `active`? |
|---------|--------------------|
| `GET /api/v1/services` | Yes — active only |
| `GET /api/v1/services/all` | Yes — active only |
| `GET /api/v1/services/search` | Yes — active only |
| `GET /api/v1/services/admin/all` | No — returns everything |
| `GET /api/v1/services/search/admin` | No — searches everything |
| `GET /api/v1/services/{id}` | **No** — an inactive service is publicly readable by id |
| `GET /api/v1/services/featured` | **No** — a featured inactive service is publicly listed |

`publishedAt` is metadata, not a gate: a service with `publishedAt = null` is fully public as long as
`active` is `true`. If you need a scheduled publish, keep the record inactive and flip
`PATCH /{id}/active?value=true` at the right moment.

`POST` always creates with `active = true`; `PUT` never changes it. `PATCH /{id}/active` is the only
writer.

---

## The audit log

Every create, update, active-toggle and delete inserts a row into `service_audit_logs`
(`ServiceAuditLog`). The table stores `serviceId` and `serviceType` as **snapshots**, so rows survive
the hard delete of the service they describe.

| Column | Source |
|--------|--------|
| `service_id` | The service's id at the time of the action |
| `service_type` | Snapshot of `serviceType` |
| `action` | `CREATE` \| `UPDATE` \| `DELETE` \| `TOGGLE_ACTIVE` |
| `details` | Human-readable line, e.g. `"Service updated: Training"`, `"Service activated"` |
| `performed_by` | The literal string `"system"` |
| `request_id` | The MDC `traceId`, which is the same value as the `X-Trace-Id` response header |
| `timestamp` | Set in `@PrePersist` from `LocalDateTime.now()` if not already populated |

> **Note:** `performed_by` is hard-coded to `"system"` in `ServiceService.buildAuditLog` — the
> authenticated principal is never recorded, despite `ServiceAuditLog`'s own comment ("Who performed
> the action (future auth integration)"). The trail tells you *what* changed and *when*, not *who*.

> **Note:** no endpoint reads this table. `ServiceAuditLogRepository.findByServiceIdOrderByTimestampDesc`
> exists but has no caller. The audit trail is currently accessible only through direct database
> access.

Audit writes share the transaction with the operation they describe, so a rollback discards them too.
`request_id` gives you a join key between an audit row, the application log lines for that request,
and the `traceId` in any error the client saw.

---

## The bilingual content shape

Localised text lives in a separate `service_contents` table, one row per language, joined on
`service_id` with `UNIQUE(service_id, language_code)`. This differs from the About module, which uses
`@Embeddable` columns per language on a single row — the row-per-language design here means adding a
third language needs no migration.

Each row carries three text fields, each with a different rendering contract:

| Field | Contract |
|-------|----------|
| `title` | Plain text, `VARCHAR(300)`, required |
| `description` | Tiptap **HTML**, `TEXT`. All inline media for the service lives here as `<img>`, `<video>`, `<audio>` and `<a href>` tags pointing at S3 |
| `featureDescription` | **Plain text**, `VARCHAR(1000)`. Markup is stripped on save because the highlight rail cannot render HTML |

Responses always sort `contents[]` by `languageCode`, so `CKB` comes before `KMR`.

### How updates rewrite content rows

`ServiceService.update` clears `contents`, calls `saveAndFlush` to force the `DELETE` statements out
before the new `INSERT`s, then re-adds the rows from the request. This avoids a unique-constraint
violation on `uq_service_content_lang` when re-inserting the same language. The visible consequence
for a client is that **`contents[].id` is not stable across updates** — never use it as a React key
across a save, and never store it as a reference.

### Media workflow

1. `POST /api/v1/media/upload` (multipart, `ADMIN`/`SUPER_ADMIN`, part `file` plus optional `type`
   hint of `image` \| `audio` \| `video` \| `document` \| `gallery`) returns the S3 URL.
2. The editor bakes that URL into the Tiptap HTML, or into a `galleryMedia[].url`.
3. `POST` / `PUT` here sends only JSON — no file bytes.

As a safety net, `TiptapHtmlProcessor` scans every saved `description` for inline `data:` URIs in
`src` and `href` attributes, uploads the decoded bytes to S3 and rewrites the attribute. HTML with no
`data:` substring is returned unchanged, so the common path costs nothing. Multipart JSON parts
(`MultipartJsonConfig`) are irrelevant here — this domain has no multipart endpoint.

---

## Notes & gotchas

### Deletes leave S3 orphans

Neither `delete` nor `deleteBulk` touches S3. Gallery URLs, hero video and poster, the feature image
and every asset embedded in the Tiptap HTML stay in the bucket after the service row is gone. There is
no reconciliation job in this domain. `DELETE /api/v1/media?fileUrl=…` exists for manual cleanup.

### Caching and eviction

Reads are cached in Redis (prefix `khi:`, TTL 10 minutes, JDK serialization — which is why
`ServiceResponse`, `ServiceContentResponse` and `MediaItem` all implement `Serializable` and pin
`serialVersionUID = 1L`):

| Method | Cache key |
|--------|-----------|
| `getAllActive` | `khi:services::active:p{page}:s{size}` |
| `getAll` | `khi:services::all:p{page}:s{size}` |
| `getAllActiveByType` | `khi:services::type:{type lowercased}:p{page}:s{size}` |
| `globalSearch` | `khi:services::search:{q lowercased}:p{page}:s{size}` |
| `adminSearch` | `khi:services::adminSearch:{q lowercased}:p{page}:s{size}` |
| `getServiceTypes` | `khi:services::types` |

`getById` and `getFeatured` are not cached. All six write paths in this document carry
`@CacheEvict(value = "services", allEntries = true)` — including `setServiceFeatured` in
`SiteContentService` — so the dashboard never has to think about invalidation. Redis is on the request
path once caching is enabled: if `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` are wrong, cached reads
fail rather than degrade.

### Timestamps go in and come out as UTC

`publishedAt` is parsed by `ServiceService` into a `LocalDateTime` with no zone and stored via
Hibernate with `hibernate.jdbc.time_zone=UTC`. Responses format it back with the same
`yyyy-MM-dd HH:mm:ss` pattern inside the service layer, so Jackson's `Asia/Baghdad` time zone never
applies to these three fields. What you send is what you get back.

Two accepted input formats:

- `"2026-09-01 09:00:00"` — parsed literally as UTC wall-clock time.
- ISO-8601 offset date-time, e.g. `"2026-09-01T09:00:00Z"` — parsed with
  `OffsetDateTime.parse(...).toLocalDateTime()`.

> **Note:** the ISO branch **drops the offset without converting**. `"2026-09-01T09:00:00+03:00"` is
> stored as `09:00:00` UTC, not `06:00:00` — a three-hour drift for Baghdad-local timestamps. Send
> either the plain `yyyy-MM-dd HH:mm:ss` form already converted to UTC, or an ISO string with a `Z`
> offset.

### Unknown body fields are ignored

The JSON HTTP message converter runs on Jackson 3 (`tools.jackson:jackson-databind`), whose
`FAIL_ON_UNKNOWN_PROPERTIES` default is **off**. Extra fields in a `ServiceRequest` or
`FeaturedRequest` body — `id`, `active`, `featured`, a typo'd field name — are silently discarded, not
rejected. Do not rely on the API to catch a misspelled field; validate the payload shape in the
dashboard.

That also means `GlobalExceptionHandler`'s `UnrecognizedPropertyException` handler (which would return
`400` with `details.unknownField`) and `JacksonConfig`'s "tolerate a stray `id`" handler are inert for
these endpoints. Both are wired to the Jackson **2** `ObjectMapper` bean, which serves controllers
that deserialize a JSON part by hand — none of which are in this domain.

### Wrong Content-Type returns 500

`POST` and `PUT` declare `consumes = application/json`. A request with any other content type raises
`HttpMediaTypeNotSupportedException`, for which `GlobalExceptionHandler` has no `@ExceptionHandler`,
so it falls through to the catch-all `@ExceptionHandler(Exception.class)` and comes back as `500
INTERNAL_ERROR` instead of `415 Unsupported Media Type`. Always set the header.

### Non-numeric path and query values return 500

Same root cause: `MethodArgumentTypeMismatchException` has no handler and lands on the catch-all. A
non-numeric `{id}`, a non-integer `page`/`size`, or a `?value=` that is not boolean-coercible all
answer `500 INTERNAL_ERROR` rather than `400`. Coerce on the client.

### Error messages are generic

`ServiceService` throws with message keys — `service.not_found`, `service.field.required`,
`service.navAnchorId.duplicate`, `service.content.language.unsupported`, and so on — but **none of
those keys exist in `messages_ckb.properties` or `messages_kmr.properties`**. Every one falls back to
`GlobalExceptionHandler.fallbackByCode`, which yields `"Bad request"` / `"داواکاری هەڵەیە"` for `400`
and `"Resource not found"` / `"سەرچاوە نەدۆزرایەوە"` for `404`.

The machine-readable signal is `code` plus `details`; build the dashboard's toast text from
`details` (`details.field`, `details.allowed`, `details.navAnchorId`, `details.reason`, …), not from
`message`. `messageEn` is equally generic here — the English bundle is a file literally named
`" messages_en.properties"` with a leading space, so `ReloadableResourceBundleMessageSource` never
finds it.

### `partnerIds` is unchecked

`partnerIds` is an `@ElementCollection` of raw `Long` values with no foreign key and no server-side
existence check. You can attach an id that has no `partners` row, and nothing complains — the public
site simply fails to resolve it. Deleting a partner does not clean up references. Validate against
`GET /api/v1/about/partners` before saving.

### `navAnchorId` uniqueness is application-level

Uniqueness is enforced by an `existsBy...` query in `ServiceService`, not by a database unique index —
`nav_anchor_id` is a plain `VARCHAR(160)` column. Two concurrent creates racing on the same anchor can
both pass the check. Not a practical concern for a single-admin dashboard, but do not treat the
constraint as absolute.

### Stale Javadoc in `ServiceService.getFeatured`

The method comment describes featured services as "flagged for the homepage carousel … bounded by
`SiteSettings.maxFeaturedSlides`". Neither the repository query nor `setServiceFeatured` applies that
cap, and the controller and repository comments both say the opposite (Services-page highlights, no
cap). The code is the authority: **no cap**.

---

## Related documentation

- Counterpart (public reads): [`../external/SERVICE_API.md`](../external/SERVICE_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`. Note that
  `OpenApiConfig` puts the whole `/api/v1/services/**` prefix in the **`public`** group — the two
  admin reads and all six writes appear there despite requiring a JWT. Use the `all` group when you
  want a complete picture.
- Servers: `http://localhost:8080` locally; production is deployed on Railway
