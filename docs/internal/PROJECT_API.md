# Projects API — Internal (Authenticated)

The write side of the projects domain: the admin dashboard uses these four endpoints to create, replace, delete and feature a project. Every call needs a JWT. Create and update are plain `application/json` — binary assets are uploaded first through the shared media pipeline and only their S3 URLs travel in the project payload.

Public reads live in [`../external/PROJECT_API.md`](../external/PROJECT_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/projects` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/project/ProjectController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/project/ProjectService.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` (feature toggle), `src/main/java/ak/dev/khi_backend/khi_app/service/media/TiptapHtmlProcessor.java` |
| **Request DTO** | `ProjectCreateRequest` (used by both create and update) |
| **Response DTO** | `ProjectResponse` |
| **Entities** | `Project`, `ProjectContentBlock` (embeddable), `ProjectTag`, `ProjectKeyword`, `ProjectLog`, `MediaItem` (JSONB), `SiteSettings` (featured limit) |
| **Verified against source** | 2026-08-26 |

---

> **Note — the brief for this domain says create and update are multipart. They are not.** `ProjectController.create` and `ProjectController.update` are annotated `@PostMapping` / `@PutMapping` with `@Valid @RequestBody ProjectCreateRequest` and no `consumes` for multipart. The class Javadoc records the migration: *"All endpoints are now plain `application/json`. The frontend uploads the cover image and any inline media first via `POST /api/v1/media/upload`, then sends URLs in the JSON body."* The old `media[]` array, the `contentsCkb` / `contentsKmr` string lists and the `project_media` table are gone. Sending `multipart/form-data` to `/create` or `/update/{id}` returns `415 Unsupported Media Type`. Everything below documents the JSON contract that the code actually implements.

---

## Authentication

Send the token as either:

```
Authorization: Bearer <jwt>
```

or the HttpOnly cookie whose name comes from the `JWT_COOKIE_NAME` environment variable. `JWTAuthenticationFilter` checks the header first (`resolveToken`) and falls back to the cookie. Sessions are stateless (`SessionCreationPolicy.STATELESS`) — there is no server-side session to keep alive.

Token failures are answered by the filter, before Spring Security's authorization rules run:

| Situation | Status | Body |
|-----------|--------|------|
| Expired token | `401` | `{"error":"TOKEN_EXPIRED", ...}` and the auth cookie is cleared |
| Malformed / unverifiable token | `403` | `{"error":"INVALID_TOKEN", ...}` and the auth cookie is cleared |
| Token blacklisted by logout | `401` | Auth cookie cleared |
| No token at all | `401`/`403` | Falls through to Spring Security, which rejects the request |

Roles are `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`. Each grants the authority `ROLE_<NAME>` plus permission authorities from `user:create`, `user:read`, `user:update`, `user:delete`. `GUEST` is the default for self-registration and can call **none** of the endpoints on this page.

---

## Authorization, endpoint by endpoint

Two independent mechanisms decide access, and both must pass:

1. `SecurityConfig` path/method rules.
2. `@PreAuthorize` on the handler, which narrows further.

For `/api/v1/projects/**` the relevant `SecurityConfig` rules are:

```java
.requestMatchers(HttpMethod.POST,   "/api/v1/projects/**", ...).hasAnyRole("EMPLOYEE", "ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.PUT,    "/api/v1/projects/**", ...).hasAnyRole("EMPLOYEE", "ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.DELETE, "/api/v1/projects/**", ...).hasAnyRole("ADMIN", "SUPER_ADMIN")
.anyRequest().authenticated()
```

There is **no** `PATCH` rule for `/api/v1/projects/**` — the only PATCH rule in the file targets `/api/v1/videos/**`. `PATCH /api/v1/projects/{id}/featured` therefore falls through to `anyRequest().authenticated()` and is narrowed by its own `@PreAuthorize("hasRole('ADMIN')")`.

| Endpoint | `SecurityConfig` | `@PreAuthorize` | Effective roles |
|----------|------------------|-----------------|-----------------|
| `POST /create` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | none | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `PUT /update/{id}` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | none | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `DELETE /delete/{id}` | `ADMIN`, `SUPER_ADMIN` | none | `ADMIN`, `SUPER_ADMIN` |
| `PATCH /{id}/featured` | authenticated (fall-through) | `hasRole('ADMIN')` | **`ADMIN` only** |

> **Note — `SUPER_ADMIN` cannot feature a project.** `hasRole('ADMIN')` is an exact authority check for `ROLE_ADMIN`; there is no role hierarchy bean in this application, so `ROLE_SUPER_ADMIN` does not imply `ROLE_ADMIN`. A `SUPER_ADMIN` who can delete any project gets `403 FORBIDDEN` on `PATCH /{id}/featured`. Every other feature toggle in the codebase that sits behind `SecurityConfig` uses `hasAnyRole("ADMIN", "SUPER_ADMIN")`. This is a real inconsistency in the source, documented here as-is.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/projects/create` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a project from a JSON body |
| 2 | `PUT` | `/api/v1/projects/update/{id}` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Full replace of an existing project |
| 3 | `DELETE` | `/api/v1/projects/delete/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete a project and its audit log |
| 4 | `PATCH` | `/api/v1/projects/{id}/featured` | JWT | `ADMIN` only | Toggle homepage-carousel membership |

Response shapes differ across the four — check each section:

| Endpoint | Success status | Body |
|----------|----------------|------|
| `POST /create` | `201 Created` | `ApiResponse<ProjectResponse>` |
| `PUT /update/{id}` | `200 OK` | `ApiResponse<ProjectResponse>` |
| `DELETE /delete/{id}` | `204 No Content` | **empty — no envelope** |
| `PATCH /{id}/featured` | `204 No Content` | **empty — no envelope** |

---

## Prerequisite: upload assets first

`ProjectService` no longer touches S3 for cover or gallery assets. Before you can create or update a project you must have S3 URLs:

```
POST /api/v1/media/upload        (multipart/form-data)
```

| Part | Content type | Required | Description |
|------|--------------|----------|-------------|
| `file` | any (`image/*`, `video/*`, `audio/*`, `application/pdf`, …) | yes | The binary to store |
| `type` | `text/plain` | no | Folder hint: `image` \| `audio` \| `video` \| `document` \| `gallery`. Defaults to `image` |

`POST /api/v1/media/upload` is itself restricted — `SecurityConfig` maps all of `/api/v1/media/**` to `hasAnyRole("ADMIN", "SUPER_ADMIN")`.

> **Note — an `EMPLOYEE` can create a project but cannot upload its cover.** `POST /api/v1/projects/create` allows `EMPLOYEE`, while the media pipeline it depends on does not. An `EMPLOYEE` can only publish a project by reusing an S3 URL that somebody else uploaded. Inline base64 media inside `description` is the one exception, because that is uploaded server-side (see below) and never passes through `/api/v1/media/**`.

```bash
curl -s -X POST http://localhost:8080/api/v1/media/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@hewler-citadel.jpg;type=image/jpeg" \
  -F "type=image"
```

```json
{
  "success": true,
  "message": "Media uploaded successfully",
  "data": {
    "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg"
  }
}
```

Take `data.fileUrl` and put it in `coverUrl`, in a `mediaGallery[].url`, or inside the Tiptap HTML.

**Upload limits:** `spring.servlet.multipart.max-file-size` and `max-request-size` are both `1GB`; files land in bucket `s3-khiwebsite`, region `us-east-1`, under base folder `khi-web-folders`. `S3Service` routes by MIME into `khi-web-folders/images/`, `/video/`, `/audio/` or `/files/`, and the object key is `<base>/<folder>/<uuid>-<sanitised filename>`.

> **Note:** the `413` handler in `GlobalExceptionHandler` hard-codes `MAX_UPLOAD_MB = 5` in its message text, so an oversized upload says *"exceeds the maximum allowed size of 5 MB"* while the configured limit is 1 GB. The status (`413`) and code (`PAYLOAD_TOO_LARGE`) are correct; only the wording is wrong.

---

## The `ProjectCreateRequest` body

Both `/create` and `/update/{id}` take the same DTO. Nothing is merged — see [Update semantics](#update-semantics).

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `coverUrl` | string | **yes** | `@Size(max = 1024)`; rejected when blank by service validation | S3 URL of the card cover asset |
| `coverMediaType` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO` | How to render `coverUrl`. **Null becomes `IMAGE`** |
| `coverThumbnailUrl` | string | no | `@Size(max = 1024)` | Poster for a `VIDEO` cover, cover art for an `AUDIO` cover. Blank is stored as `null` |
| `mediaGallery` | array of `MediaItem` | no | — | Mixed gallery beside the cover. Omitting it stores an empty list |
| `projectTypeCkb` | string | conditional | `@Size(max = 128)` | **Required when `contentLanguages` contains `CKB`** |
| `projectTypeKmr` | string | conditional | `@Size(max = 128)` | **Required when `contentLanguages` contains `KMR`** |
| `status` | enum `ProjectStatus` | no | `ACTIVE` \| `ONGOING` \| `COMPLETED` \| `ARCHIVED` | **Null becomes `ONGOING`** |
| `contentLanguages` | array of enum `Language` | **yes** | `@NotEmpty` | Which language blocks this project carries. Duplicates collapse (it is a `Set`) |
| `projectDate` | string `yyyy-MM-dd` | no | — | Editorial date. Nullable |
| `ckbContent` | object | conditional | — | Sorani block. **Required with a non-blank `title` when `contentLanguages` contains `CKB`** |
| `kmrContent` | object | conditional | — | Kurmanji block. **Required with a non-blank `title` when `contentLanguages` contains `KMR`** |
| `tagsCkb` | array of string | no | — | Sorani-side tag names |
| `tagsKmr` | array of string | no | — | Kurmanji-side tag names |
| `keywordsCkb` | array of string | no | — | Sorani-side keyword names |
| `keywordsKmr` | array of string | no | — | Kurmanji-side keyword names |

`ckbContent` / `kmrContent` — three fields, nothing else:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `title` | string | yes when the block is present | DB column is 255 chars | Project title in that language |
| `description` | string | no | DB column is `TEXT` | Tiptap HTML. Passed through `TiptapHtmlProcessor` on save |
| `location` | string | no | DB column is 255 chars | Human-readable place |

> **Note:** `ProjectCreateRequest` types these two fields as the JPA `@Embeddable` `ProjectContentBlock` rather than a request-side DTO. Functionally it is the same three fields, but it means the request schema is coupled to the persistence model. Send exactly `title`, `description`, `location` — an extra key inside the block is an unknown property and is rejected.

`mediaGallery[]` — `MediaItem`:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | string | **yes** | S3 URL. An entry with a null or blank `url` is silently dropped |
| `kind` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO`. **Null becomes `IMAGE`** |
| `thumbnailUrl` | string | no | Poster (`VIDEO`) / cover art (`AUDIO`). Blank stored as `null` |
| `captionCkb` | string | no | Sorani caption. Blank stored as `null` |
| `captionKmr` | string | no | Kurmanji caption. Blank stored as `null` |
| `sortOrder` | number (int32) | no | Ascending display order. **Null is backfilled with the item's index among accepted entries**, then the list is sorted ascending before it is persisted |

### Unknown fields are rejected

`JacksonConfig` installs a `DeserializationProblemHandler` that tolerates exactly one unknown property — a top-level `id`, so you can `PUT` a response-shaped object straight back. Every other unknown key produces:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/projects/create",
  "method": "POST",
  "traceId": "7c1e4b90-3f28-4a65-b0d1-2e9f6c8a4b37",
  "code": "BAD_REQUEST",
  "message": "Unknown field in request body: featured",
  "messageEn": "Unknown field in request body: featured",
  "messageKu": "Unknown field in request body: featured",
  "fieldErrors": null,
  "details": {
    "unknownField": "featured",
    "hint": "Remove this field or check its spelling against the API request schema."
  }
}
```

In particular `featured`, `featuredOrder` and `featureImageUrl` are **not** accepted here — they belong to endpoint 4.

### Inline media inside `description`

`ProjectService` runs both `description` strings through `TiptapHtmlProcessor.process()` before persisting. That processor:

- Fast-exits when the HTML contains no `data:` substring, so HTML that already holds S3 URLs is returned untouched (idempotent).
- Finds `src="data:<mime>;base64,…"` on `<img>`, `<video>`, `<audio>` and `<source>`, and `href="data:<mime>;base64,…"` on `<a>`.
- Base64-decodes each payload, uploads the bytes to S3 as `tiptap-<nanoTime>.<ext>`, and rewrites the attribute to the resulting public URL.
- Routes by MIME through `ProjectMediaType` into the S3 folder (`image/*` → `images/`, `video/*` → `video/`, `audio/*` → `audio/`, anything else → `files/`).
- Never fails the save: a malformed base64 payload or a failed S3 upload is logged and the original attribute is left in place.

So you may either pre-upload via `/api/v1/media/upload` and embed the returned URLs, or paste base64 data URIs and let the server hoist them. Both work; pre-uploading is far cheaper on request size.

> **Note:** a failed inline upload leaves the `data:` URI in the stored HTML and still returns `201`/`200`. The response body shows the *processed* HTML, so compare what you sent with `data.ckbContent.description` if you need certainty that every asset was hoisted.

### Validation order

Bean validation runs first (`@Valid`), then `ProjectService.validate(dto, requireCoverUrl = true)` — identical for create and update:

| # | Check | Failure |
|---|-------|---------|
| 0 | `@NotEmpty contentLanguages`, `@Size` on `coverUrl` / `coverThumbnailUrl` / `projectTypeCkb` / `projectTypeKmr` | `400` `VALIDATION_ERROR` with `fieldErrors` |
| 1 | Body is null | `400` `PROJECT_VALIDATION`, key `project.request_required` |
| 2 | `CKB` selected and `projectTypeCkb` blank | `400` `PROJECT_VALIDATION`, key `project.ckb_type_required` |
| 3 | `KMR` selected and `projectTypeKmr` blank | `400` `PROJECT_VALIDATION`, key `project.kmr_type_required` |
| 4 | `contentLanguages` null or empty | `400` `PROJECT_VALIDATION`, key `project.languages_required` |
| 5 | `coverUrl` blank | `400` `PROJECT_VALIDATION`, key `project.cover_required` |
| 6 | `CKB` selected and `ckbContent` null or `ckbContent.title` blank | `400` `PROJECT_VALIDATION`, key `project.ckb_title_required` |
| 7 | `KMR` selected and `kmrContent` null or `kmrContent.title` blank | `400` `PROJECT_VALIDATION`, key `project.kmr_title_required` |

Each service-level failure carries a `details.hint` naming the exact field. Example for step 6:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/projects/create",
  "method": "POST",
  "traceId": "b48c2d71-6e05-4f93-8a12-77c3e0d5b6a9",
  "code": "PROJECT_VALIDATION",
  "message": "Project validation error",
  "messageEn": "Project validation error",
  "messageKu": "هەڵەی پشکنینەوە لە داتای پرۆژەدا",
  "fieldErrors": null,
  "details": {
    "hint": "ckbContent.title is required when contentLanguages includes CKB."
  }
}
```

> **Note:** check 4 is unreachable over HTTP. `@NotEmpty` on `contentLanguages` fires first and returns `VALIDATION_ERROR` with a `fieldErrors` entry, so `project.languages_required` / `PROJECT_VALIDATION` never reaches a client. Handle the `VALIDATION_ERROR` shape for the missing-languages case.

---

## 1. `POST /api/v1/projects/create` — Create a project

Validates, opens a transaction, builds the `Project`, resolves tags and keywords (creating rows that do not exist yet), saves, and writes one `ProjectLog` audit row. Evicts the entire `projects` Redis cache so the public list endpoints show the new project immediately.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | None |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

The full [`ProjectCreateRequest`](#the-projectcreaterequest-body). Note that `featured`, `featuredOrder` and `featureImageUrl` cannot be set here — a new project is always unfeatured with a null `featureImageUrl`.

```json
{
  "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
  "coverMediaType": "IMAGE",
  "coverThumbnailUrl": null,
  "mediaGallery": [
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/5c8a1f22-91d4-4b7e-a3c6-77e2b0d4419a-workshop-day-1.jpg",
      "kind": "IMAGE",
      "thumbnailUrl": null,
      "captionCkb": "یەکەم ڕۆژی ۆرکشۆپ لە قەڵای هەولێر",
      "captionKmr": "Roja yekem a atolyeyê li Keleha Hewlêrê",
      "sortOrder": 0
    },
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/a71b3c04-6e28-42d9-8f15-c9d0a7b3e552-restoration-timelapse.mp4",
      "kind": "VIDEO",
      "thumbnailUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/d4f8c210-3b77-4e6a-9c81-0af2e5b7d913-timelapse-poster.jpg",
      "captionCkb": "تایم‌لاپسی کاری نۆژەنکردنەوە",
      "captionKmr": "Timelapse a xebata nûvekirinê",
      "sortOrder": 1
    }
  ],
  "projectTypeCkb": "پاراستنی کەلەپوور",
  "projectTypeKmr": "Parastina Mîrasê",
  "status": "ONGOING",
  "contentLanguages": ["CKB", "KMR"],
  "projectDate": "2025-04-12",
  "ckbContent": {
    "title": "نۆژەنکردنەوەی قەڵای هەولێر",
    "description": "<h2>دەربارەی پرۆژەکە</h2><p>ئەم پرۆژەیە بە هاوکاری شارەوانی هەولێر کاردەکات لەسەر ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵاکە.</p>",
    "location": "هەولێر، هەرێمی کوردستان"
  },
  "kmrContent": {
    "title": "Nûvekirina Keleha Hewlêrê",
    "description": "<h2>Der barê projeyê de</h2><p>Ev proje bi hevkariya Şaredariya Hewlêrê li ser nûvekirina dîwarên rojavayî yên kelehê dixebite.</p>",
    "location": "Hewlêr, Herêma Kurdistanê"
  },
  "tagsCkb": ["کەلەپوور", "نۆژەنکردنەوە"],
  "tagsKmr": ["mîras", "nûvekirin"],
  "keywordsCkb": ["قەڵای هەولێر", "یونسکۆ"],
  "keywordsKmr": ["Keleha Hewlêrê", "UNESCO"]
}
```

A minimal single-language body:

```json
{
  "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1a4f7b90-2c63-4d85-9e07-3b8c5a1d2f44-slemani-archive.jpg",
  "projectTypeCkb": "ئەرشیفکردن",
  "contentLanguages": ["CKB"],
  "ckbContent": {
    "title": "ئەرشیفی دەنگی سلێمانی"
  }
}
```

**Response `201 Created`**

```json
{
  "success": true,
  "message": "Project created successfully",
  "data": {
    "id": 42,
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
    "coverMediaType": "IMAGE",
    "coverThumbnailUrl": null,
    "featureImageUrl": null,
    "mediaGallery": [
      {
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/5c8a1f22-91d4-4b7e-a3c6-77e2b0d4419a-workshop-day-1.jpg",
        "kind": "IMAGE",
        "thumbnailUrl": null,
        "captionCkb": "یەکەم ڕۆژی ۆرکشۆپ لە قەڵای هەولێر",
        "captionKmr": "Roja yekem a atolyeyê li Keleha Hewlêrê",
        "sortOrder": 0
      },
      {
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/a71b3c04-6e28-42d9-8f15-c9d0a7b3e552-restoration-timelapse.mp4",
        "kind": "VIDEO",
        "thumbnailUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/d4f8c210-3b77-4e6a-9c81-0af2e5b7d913-timelapse-poster.jpg",
        "captionCkb": "تایم‌لاپسی کاری نۆژەنکردنەوە",
        "captionKmr": "Timelapse a xebata nûvekirinê",
        "sortOrder": 1
      }
    ],
    "projectTypeCkb": "پاراستنی کەلەپوور",
    "projectTypeKmr": "Parastina Mîrasê",
    "status": "ONGOING",
    "projectDate": "2025-04-12",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "نۆژەنکردنەوەی قەڵای هەولێر",
      "description": "<h2>دەربارەی پرۆژەکە</h2><p>ئەم پرۆژەیە بە هاوکاری شارەوانی هەولێر کاردەکات لەسەر ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵاکە.</p>",
      "location": "هەولێر، هەرێمی کوردستان"
    },
    "kmrContent": {
      "title": "Nûvekirina Keleha Hewlêrê",
      "description": "<h2>Der barê projeyê de</h2><p>Ev proje bi hevkariya Şaredariya Hewlêrê li ser nûvekirina dîwarên rojavayî yên kelehê dixebite.</p>",
      "location": "Hewlêr, Herêma Kurdistanê"
    },
    "tagsCkb": ["کەلەپوور", "نۆژەنکردنەوە"],
    "tagsKmr": ["mîras", "nûvekirin"],
    "keywordsCkb": ["قەڵای هەولێر", "یونسکۆ"],
    "keywordsKmr": ["Keleha Hewlêrê", "UNESCO"],
    "createdAt": "2026-03-14T09:14:22Z",
    "updatedAt": null,
    "createdBy": null,
    "updatedBy": null
  }
}
```

> **Note:** `updatedAt`, `createdBy` and `updatedBy` are declared on `ProjectResponse` but `ProjectService.toResponse()` never assigns them, so they are always `null` even though the `projects` table does store `created_by` / `updated_by` (populated by `AuditorAwareImpl` from the JWT subject, or `"SYSTEM"` for anonymous). To see who last edited a project you must read the database or the `project_log` table directly — no endpoint exposes either.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `contentLanguages` empty/missing, or a `@Size` limit exceeded. `fieldErrors[]` names each field |
| `400` | `PROJECT_VALIDATION` | Any service-level check in the [validation order](#validation-order) table |
| `400` | `BAD_REQUEST` | Unknown field in the body, or unparseable JSON |
| `401` | `UNAUTHORIZED` | No token, expired token, or blacklisted token |
| `403` | `FORBIDDEN` | Authenticated as `GUEST`; or an invalid token |
| `409` | `PROJECT_CONFLICT` | `DataIntegrityViolationException` during save — in practice a race creating the same tag/keyword name twice. `details.traceId` is set |
| `415` | — | `Content-Type` is not `application/json` (including any `multipart/form-data` attempt) |
| `500` | `INTERNAL_ERROR` | Anything else. `details` carries `operation: "create"` and `traceId` |

`409` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 409,
  "path": "/api/v1/projects/create",
  "method": "POST",
  "traceId": "e2a7f14c-8b39-4d02-9c65-0f1b3e8d7a44",
  "code": "PROJECT_CONFLICT",
  "message": "Project data conflict",
  "messageEn": "Project data conflict",
  "messageKu": "کێشەی تێکچوون لە پرۆژەدا",
  "fieldErrors": null,
  "details": {
    "traceId": "e2a7f14c-8b39-4d02-9c65-0f1b3e8d7a44"
  }
}
```

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/projects/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept-Language: ckb" \
  -d '{
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
    "coverMediaType": "IMAGE",
    "projectTypeCkb": "پاراستنی کەلەپوور",
    "projectTypeKmr": "Parastina Mîrasê",
    "status": "ONGOING",
    "contentLanguages": ["CKB", "KMR"],
    "projectDate": "2025-04-12",
    "ckbContent": {
      "title": "نۆژەنکردنەوەی قەڵای هەولێر",
      "description": "<p>ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵاکە.</p>",
      "location": "هەولێر، هەرێمی کوردستان"
    },
    "kmrContent": {
      "title": "Nûvekirina Keleha Hewlêrê",
      "description": "<p>Nûvekirina dîwarên rojavayî yên kelehê.</p>",
      "location": "Hewlêr, Herêma Kurdistanê"
    },
    "tagsCkb": ["کەلەپوور"],
    "tagsKmr": ["mîras"],
    "keywordsCkb": ["قەڵای هەولێر"],
    "keywordsKmr": ["Keleha Hewlêrê"]
  }'
```

---

## 2. `PUT /api/v1/projects/update/{id}` — Full replace

Loads the project, overwrites every field the DTO covers, re-resolves tags and keywords, saves and writes an `UPDATE` audit row. Evicts the whole `projects` cache.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (int64) | yes | Project primary key. Must parse as a `Long` |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

Same `ProjectCreateRequest` as `/create`, with the same validation and the same `coverUrl`-is-required rule. A top-level `id` key is tolerated and ignored; the URL wins.

### Update semantics

This is a **replace, not a patch**. `ProjectService.applyUpdate` writes every field unconditionally:

| Field | Behaviour when omitted from the body |
|-------|--------------------------------------|
| `coverUrl` | Cannot be omitted — validation rejects a blank cover |
| `coverMediaType` | Reset to `IMAGE` |
| `coverThumbnailUrl` | Set to `null` |
| `mediaGallery` | Replaced with an empty list — **the whole gallery is wiped** |
| `projectDate` | Set to `null` |
| `projectTypeCkb` / `projectTypeKmr` | Set to `null` (subject to the conditional validation above) |
| `status` | Reset to `ONGOING` |
| `contentLanguages` | Cannot be omitted — `@NotEmpty` |
| `ckbContent` / `kmrContent` | Cleared to `null` if the matching language is not in `contentLanguages`, even if the block itself was sent |
| `tagsCkb` / `tagsKmr` / `keywordsCkb` / `keywordsKmr` | All four collections are cleared first, then re-attached from the body. Omitting one clears it |

Fields **not** touched by update, because no request field maps to them:

- `featured`
- `featuredOrder`
- `featureImageUrl`
- `createdAt` / `createdBy` (immutable columns)

Practical consequence: always send the full current object back. Read it with `GET /api/v1/projects/{id}`, mutate the fields you care about, and `PUT` the whole thing.

Dropping a language is destructive — `PUT` with `"contentLanguages": ["CKB"]` on a project that had both blocks permanently deletes `title_kmr`, `description_kmr` and `location_kmr`.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Project updated successfully",
  "data": {
    "id": 42,
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
    "coverMediaType": "IMAGE",
    "coverThumbnailUrl": null,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6e0d19-77af-4c31-9a02-4d8e1c5f6b90-hewler-citadel-wide.jpg",
    "mediaGallery": [],
    "projectTypeCkb": "پاراستنی کەلەپوور",
    "projectTypeKmr": "Parastina Mîrasê",
    "status": "COMPLETED",
    "projectDate": "2026-01-30",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "نۆژەنکردنەوەی قەڵای هەولێر",
      "description": "<h2>ئەنجامەکان</h2><p>کارەکە لە ٣٠ی کانوونی دووەمی ٢٠٢٦ تەواو بوو.</p>",
      "location": "هەولێر، هەرێمی کوردستان"
    },
    "kmrContent": {
      "title": "Nûvekirina Keleha Hewlêrê",
      "description": "<h2>Encam</h2><p>Xebat di 30ê Rêbendana 2026an de qediya.</p>",
      "location": "Hewlêr, Herêma Kurdistanê"
    },
    "tagsCkb": ["کەلەپوور", "نۆژەنکردنەوە"],
    "tagsKmr": ["mîras", "nûvekirin"],
    "keywordsCkb": ["قەڵای هەولێر", "یونسکۆ"],
    "keywordsKmr": ["Keleha Hewlêrê", "UNESCO"],
    "createdAt": "2026-03-14T09:14:22Z",
    "updatedAt": null,
    "createdBy": null,
    "updatedBy": null
  }
}
```

Note `featureImageUrl` survived the update, and `mediaGallery` came back empty because the request omitted it.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | Bean-validation failure; `fieldErrors[]` populated |
| `400` | `PROJECT_VALIDATION` | Any service-level check in the [validation order](#validation-order) table |
| `400` | `BAD_REQUEST` | Unknown field (other than `id`), unparseable JSON, or a non-numeric `{id}` |
| `401` | `UNAUTHORIZED` | Missing / expired / blacklisted token |
| `403` | `FORBIDDEN` | Role below `EMPLOYEE`; or an invalid token |
| `404` | `PROJECT_NOT_FOUND` | No project with that id. `details.projectId` echoes it |
| `409` | `PROJECT_CONFLICT` | `DataIntegrityViolationException` during save |
| `415` | — | `Content-Type` is not `application/json` |
| `500` | `INTERNAL_ERROR` | Anything else. `details` carries `operation: "update"`, `projectId` and `traceId` |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/projects/update/42 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
    "coverMediaType": "IMAGE",
    "projectTypeCkb": "پاراستنی کەلەپوور",
    "projectTypeKmr": "Parastina Mîrasê",
    "status": "COMPLETED",
    "contentLanguages": ["CKB", "KMR"],
    "projectDate": "2026-01-30",
    "ckbContent": {
      "title": "نۆژەنکردنەوەی قەڵای هەولێر",
      "description": "<h2>ئەنجامەکان</h2><p>کارەکە تەواو بوو.</p>",
      "location": "هەولێر، هەرێمی کوردستان"
    },
    "kmrContent": {
      "title": "Nûvekirina Keleha Hewlêrê",
      "description": "<h2>Encam</h2><p>Xebat qediya.</p>",
      "location": "Hewlêr, Herêma Kurdistanê"
    },
    "tagsCkb": ["کەلەپوور", "نۆژەنکردنەوە"],
    "tagsKmr": ["mîras", "nûvekirin"],
    "keywordsCkb": ["قەڵای هەولێر", "یونسکۆ"],
    "keywordsKmr": ["Keleha Hewlêrê", "UNESCO"]
  }'
```

---

## 3. `DELETE /api/v1/projects/delete/{id}` — Delete a project

Bulk-deletes the project's `project_log` rows with a single JPQL `DELETE`, then removes the project row. Join-table rows in `project_tag_map_ckb`, `project_tag_map_kmr`, `project_keyword_map_ckb`, `project_keyword_map_kmr` and `project_content_languages` go with it. Evicts the whole `projects` cache.

**This endpoint is idempotent and never returns `404`.** `ProjectService.delete` returns silently when `id` is null and, when the row does not exist, logs `"Project delete ignored"` and returns. The controller answers `204` either way.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** — (no body)
**Produces:** empty body

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (int64) | yes | Project primary key. Must parse as a `Long` |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

None.

**Response `204 No Content`**

Empty body. No `ApiResponse` envelope — do not try to parse JSON from a successful delete.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `{id}` is not a number |
| `401` | `UNAUTHORIZED` | Missing / expired / blacklisted token |
| `403` | `FORBIDDEN` | Role is `EMPLOYEE` or `GUEST` — note that `EMPLOYEE` can create and update but not delete |
| `500` | `INTERNAL_ERROR` | Delete failed. `details` carries `operation: "delete"`, `projectId` and `traceId` |

**Example**

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X DELETE http://localhost:8080/api/v1/projects/delete/42 \
  -H "Authorization: Bearer $TOKEN"
# 204
```

> **Note — S3 objects are not deleted.** Nothing in the delete path calls `S3Service.delete`. The cover, every `mediaGallery` asset and every image, video, audio file or document embedded in the Tiptap HTML stay in `s3-khiwebsite` forever. `DELETE /api/v1/media?fileUrl=...` exists for manual cleanup but is not wired into the project lifecycle. Budget for orphaned objects.

> **Note — tags and keywords are not deleted.** `project_tags` and `project_keywords` are a shared, global taxonomy. Deleting the last project that used a tag leaves the tag row behind, where it stays available to `ensureTags` for the next project. There is no endpoint that lists or prunes them.

---

## 4. `PATCH /api/v1/projects/{id}/featured` — Feature / unfeature

Toggles whether a project appears in the homepage carousel and optionally sets its hero picture. Handled by `SiteContentService.setProjectFeatured`, not `ProjectService`.

**Auth:** `Authorization: Bearer <token>`, role **`ADMIN` only** (see the [authorization note](#authorization-endpoint-by-endpoint) — `SUPER_ADMIN` is rejected)
**Content-Type:** `application/json`
**Produces:** empty body

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (int64) | yes | Project primary key |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

The DTO is `SiteContentDtos.FeaturedRequest`, shared across every content type. `setProjectFeatured` reads only three of its fields:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | boolean | no | — | `true` or omitted/`null` → feature it. `false` → unfeature it |
| `featuredOrder` | number (int32) | no | — | Carousel position, ascending; lower shows first. Set when featuring, **forced to `null` when unfeaturing** |
| `featureImageUrl` | string | no | — | Wide hero picture. Omitted/`null` → leave the stored value alone. `""` → clear it (falls back to `coverUrl`). A URL → set it |

The DTO also declares `type`, `slug`, `title`, `description`, `imageUrl`, `imageAlt`, `locale`, `displayOrder` and `active`. `setProjectFeatured` ignores all of them.

> **Note:** the handler signature is `@RequestBody SiteContentDtos.FeaturedRequest request` — **without** `@Valid`. The `@NotBlank` constraints that `FeaturedRequest` declares on `type`, `slug`, `title`, `description` and `imageUrl` are therefore never enforced on this route, which is why a three-field body works. Do not rely on that if the annotation is ever added; but equally, do not send the five `@NotBlank` fields hoping they do something here — they are ignored.

Feature a project at carousel position 1 and give it a hero image:

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6e0d19-77af-4c31-9a02-4d8e1c5f6b90-hewler-citadel-wide.jpg"
}
```

Reorder an already-featured project without touching its hero image:

```json
{
  "featured": true,
  "featuredOrder": 3
}
```

Unfeature it (`featuredOrder` is nulled server-side; `featureImageUrl` is kept):

```json
{
  "featured": false
}
```

Clear the hero image so the carousel falls back to `coverUrl`:

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": ""
}
```

An empty body `{}` is valid and features the project with a null order.

**Response `204 No Content`**

Empty body. No `ApiResponse` envelope, and no representation of the new state — re-read `GET /api/v1/projects/featured` if you need to confirm.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | The global featured-slide limit is already reached. `details.reason` is `"Maximum of 7 featured slides allowed across all content. Unfeature one first."` |
| `400` | `BAD_REQUEST` | `{id}` is not a number, unknown field in the body, or unparseable JSON |
| `401` | `UNAUTHORIZED` | Missing / expired / blacklisted token |
| `403` | `FORBIDDEN` | Any role other than `ADMIN`, **including `SUPER_ADMIN`** |
| `404` | `NOT_FOUND` | No project with that id |
| `500` | `INTERNAL_ERROR` | Unexpected fault |

Limit-reached body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/projects/42/featured",
  "method": "PATCH",
  "traceId": "9d3b1f56-4c7a-4e08-b219-6a0f8c2d5e71",
  "code": "BAD_REQUEST",
  "message": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "messageEn": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "messageKu": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "fieldErrors": null,
  "details": {
    "reason": "Maximum of 7 featured slides allowed across all content. Unfeature one first."
  }
}
```

`404` body — this path raises a `jakarta.persistence.EntityNotFoundException`, so the code is the generic `NOT_FOUND`, **not** `PROJECT_NOT_FOUND`, and the message is the same English string in all three message fields:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/projects/999999/featured",
  "method": "PATCH",
  "traceId": "c07e2a4b-8f31-4d95-a6c2-1b5e9d0f3847",
  "code": "NOT_FOUND",
  "message": "Project not found: 999999",
  "messageEn": "Project not found: 999999",
  "messageKu": "Project not found: 999999",
  "fieldErrors": null,
  "details": {
    "resource": "Project not found: 999999"
  }
}
```

### The featured-slide budget

The limit is global across the whole site, not per content type. `countAllFeatured()` sums featured **news + projects + writings + videos + sound tracks + image collections**, plus `1` if the donation settings row is currently flagged featured. The ceiling comes from `site_settings.max_featured_slides`, default `7` (`SiteSettings.DEFAULT_MAX_FEATURED_SLIDES`), editable through `PUT /api/v1/site-settings`.

The check only fires when you are turning featuring **on** for a project that is not already featured. Reordering an already-featured project (`featured: true` + a new `featuredOrder`) skips the count check, because that project is already inside the total.

**Example**

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X PATCH http://localhost:8080/api/v1/projects/42/featured \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": true, "featuredOrder": 1, "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6e0d19-77af-4c31-9a02-4d8e1c5f6b90-hewler-citadel-wide.jpg"}'
# 204
```

---

## Tag and keyword handling

Both taxonomies behave identically (`attachTags` / `ensureTags` and `attachKeywords` / `ensureKeywords`), differing only in the table they write to.

1. Each incoming name is `trim()`-ed. Empty results are skipped.
2. The name is looked up with `findByNameIgnoreCase`. If a row exists it is reused; otherwise a new row is inserted **with the casing you sent**.
3. Names are keyed internally by `name.trim().toLowerCase()`, so `"Mîras"`, `"mîras"` and `"  MÎRAS  "` all resolve to the same row.
4. Within one array, duplicates that resolve to the same row are attached once (guarded by a `Set<Long> seen`).
5. `tagsCkb` links into `project_tag_map_ckb`; `tagsKmr` links into `project_tag_map_kmr`. Same for keywords.

Consequences worth designing around:

- **The two tables are global, not per project.** `project_tags.name` and `project_keywords.name` each carry a unique constraint. Whoever inserts a name first fixes its casing for the whole site.
- **CKB and KMR share the same rows.** Putting `"UNESCO"` in both `keywordsCkb` and `keywordsKmr` links one keyword row into both join tables — you will get `"UNESCO"` back in both response arrays.
- **Tags and keywords are separate tables.** A name in one is invisible to the other's search endpoint.
- **Nothing is ever cleaned up.** Removing the last reference to a tag leaves the row in place.
- **Concurrent creates can collide.** Two simultaneous requests inserting the same new tag name can trip the unique constraint; that surfaces as `409 PROJECT_CONFLICT`. Retry the request.

---

## The `ProjectLog` audit trail

`ProjectService.auditLog` writes one row to `project_log` on create and on update. Delete does not write a row — it deletes the existing rows for that project before removing it.

| Column | Value written |
|--------|---------------|
| `project_id` | FK to `projects`, `NOT NULL`, `ON DELETE CASCADE` at the database level |
| `action` | `"CREATE"` or `"UPDATE"` |
| `field_name` | Always `"SUMMARY"` |
| `old_value` | Always `null` |
| `new_value` | `"Project created: <title>"` or `"Project updated: <title>"` |
| `created_at` | `LocalDateTime.now()` |

`<title>` comes from `safeTitle()`: the CKB title if non-blank, else the KMR title, else the literal `"project#<id>"`.

Behaviour to know:

- **The write is best-effort.** `auditLog` swallows every exception and logs a warning; a failed audit write never fails the API call and never rolls the save back. A missing log row is not proof that nothing happened.
- **No field-level diffing.** `field_name` is always `SUMMARY` and `old_value` is always `null`, so the table records *that* an edit happened, never *what* changed. The columns exist for a diffing implementation that was never written.
- **Nothing is deleted on `DELETE`** in the "kept for forensics" sense — `deleteByProject` wipes the whole history for that project first, and the FK also cascades at the PostgreSQL level as a safety net. Once a project is gone, its trail is gone.
- **`created_at` is a `LocalDateTime`, not an instant.** Everything else in the schema is written under `hibernate.jdbc.time_zone=UTC`; this column stores the application server's wall-clock time with no zone attached. Do not compare it directly against `createdAt` from `ProjectResponse`.
- **No HTTP endpoint exposes it.** `ProjectLogRepository.findByProjectOrderByCreatedAtDesc` exists but no controller calls it. Reading the audit trail today means querying `project_log` directly.

---

## Enums

### `ProjectStatus`

`ak.dev.khi_backend.khi_app.enums.project.ProjectStatus` — accepted in the request `status` field, returned in the response.

| Value | Meaning |
|-------|---------|
| `ACTIVE` | Live and promoted |
| `ONGOING` | In progress (`بەردەوام`). **The default** — used whenever `status` is null on create or update |
| `COMPLETED` | Finished (`تەواو`) |
| `ARCHIVED` | Retired. Note this is **not** a visibility flag: archived projects are still returned by every public read endpoint |

### `MediaKind`

`ak.dev.khi_backend.khi_app.enums.MediaKind` — accepted in `coverMediaType` and `mediaGallery[].kind`.

| Value | Meaning |
|-------|---------|
| `IMAGE` | `<img>`. **The default** when the field is null |
| `VIDEO` | `<video>`; `coverThumbnailUrl` / `thumbnailUrl` is the poster |
| `AUDIO` | `<audio>`; `coverThumbnailUrl` / `thumbnailUrl` is the cover art |

### `Language`

`ak.dev.khi_backend.khi_app.enums.Language` — the values inside `contentLanguages`.

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish / Sorani. Gates `ckbContent` and requires `projectTypeCkb` |
| `KMR` | Northern Kurdish / Kurmanji. Gates `kmrContent` and requires `projectTypeKmr` |

Deserialisation trims and upper-cases (`@JsonCreator Language.from`), so `"ckb"` and `" Ckb "` both parse. An unrecognised value is a `400 BAD_REQUEST`.

### `ProjectMediaType`

`ak.dev.khi_backend.khi_app.enums.project.ProjectMediaType` — **never appears in any request or response body.** It is internal to the storage layer: `TiptapHtmlProcessor` derives it from an inline asset's MIME type and `S3Service.getFolderForMediaType` maps it to an S3 folder. Listed here only because the enum is part of the project package.

| Value | Derived from | S3 folder |
|-------|--------------|-----------|
| `IMAGE` | `image/*` | `khi-web-folders/images/` |
| `VIDEO` | `video/*` | `khi-web-folders/video/` |
| `AUDIO` | `audio/*` | `khi-web-folders/audio/` |
| `DOCUMENT` | Everything else, including a null MIME | `khi-web-folders/files/` |
| `PDF` | Declared but never produced by `TiptapHtmlProcessor` (a PDF arrives as `DOCUMENT`) | `khi-web-folders/files/` |
| `TEXT` | Declared but never produced | `khi-web-folders/files/` |

---

## Notes & gotchas

**Caching.** `create`, `update` and `delete` each carry `@CacheEvict(value = "projects", allEntries = true)`, so every cached public read (`/getAll`, `/search/tag`, `/search/keyword` — Redis, prefix `khi:`, 10-minute TTL) is dropped on any write. `PATCH /{id}/featured` does **not** evict anything: `SiteContentService.setProjectFeatured` has no cache annotation, unlike its sibling `setServiceFeatured` which does carry `@CacheEvict(value = "services")`. A `featureImageUrl` change can stay invisible to `/getAll` and to the search endpoints for up to 10 minutes. `/api/v1/projects/featured` is uncached and reflects the change instantly.

**Redis must be reachable.** With `CacheConfig` enabled, an unreachable Redis breaks the cached read endpoints outright rather than degrading gracefully. Set `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` in every environment.

**Cache serialisation contract.** The `projects` cache stores `PageImpl<ProjectResponse>` through JDK serialisation. `ProjectResponse`, its nested `ProjectContentBlockDto` and `MediaItem` all implement `Serializable` and pin `serialVersionUID = 1L`. If you add a field to `ProjectResponse`, keep that constant — `CacheSerializationTests` enforces the round trip.

**Transaction shape.** `ProjectService` opens its own `TransactionTemplate` inside each method rather than relying on `@Transactional` on the public method. Create and update run their whole body — build, tag resolution, save, audit write — inside one transaction, so a validation failure or a constraint violation rolls back the tag/keyword rows created in the same call.

**Validation runs before persistence, uploads run during it.** `TiptapHtmlProcessor` performs S3 uploads while the transaction is open. A slow inline-media payload holds a database transaction for the duration; pre-upload via `/api/v1/media/upload` to avoid that.

**Nothing enforces uniqueness on projects themselves.** The `projects` table has no unique constraint on title, type or date. `PROJECT_CONFLICT` in practice only comes from a tag/keyword name race. You can create the same project twice — the API will not stop you.

**Ordering is by id.** `/getAll` and both search endpoints order `id DESC`. There is no way to influence public ordering from a write; `projectDate` and `status` are display data, not sort keys. The only ordering you control is the carousel, via `featuredOrder`.

**`Accept-Language` on writes.** Error text honours `Accept-Language: ckb` / `kmr`; `messageEn` and `messageKu` are always both present. Do **not** use the `?lang=` query parameter — `I18nConfig` registers a `LocaleChangeInterceptor` over an `AcceptHeaderLocaleResolver`, whose `setLocale()` throws `UnsupportedOperationException`, so `?lang=` turns any request into a `500`.

**English error text comes from fallbacks, not the bundle.** The file is checked in as `src/main/resources/i18n/ messages_en.properties` with a leading space in the name, while `I18nConfig` uses basename `classpath:i18n/messages`. The English bundle never loads, so `messageEn` is whatever `GlobalExceptionHandler.fallbackByCode` hard-codes. The `ckb` and `kmr` bundles load fine. `code` and `status` are unaffected.

**Swagger.** UI at `/swagger-ui.html`, JSON at `/v3/api-docs`, groups `public` / `internal` / `all`. The groups are **path** filters, not auth filters: `/api/v1/projects/**` sits in the `public` group, so all four write endpoints on this page are listed there rather than under `internal`. Use `?group=all` and read the authorization tables above rather than trusting the group name.

**Servers.** `http://localhost:8080` locally; production runs on Railway.

---

## Related documentation

- Counterpart (public reads): [`../external/PROJECT_API.md`](../external/PROJECT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
