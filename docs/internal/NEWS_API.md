# News API — Internal (Authenticated)

The write side of the News domain: create, bulk-create, update, delete, bulk-delete, and the
homepage-carousel toggle. These endpoints back the admin dashboard's news editor. Every one of them
requires a JWT and a content role — anonymous visitors only ever see the read endpoints documented
in [`../external/NEWS_API.md`](../external/NEWS_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/news` |
| **Audience** | Admin dashboard (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/news/NewsController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/news/NewsService.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` (featured toggle) |
| **DTOs** | `NewsDto`, `SiteContentDtos.FeaturedRequest` |
| **Entities** | `News`, `NewsContent`, `NewsCategory`, `NewsSubCategory`, `NewsAuditLog`, `MediaItem` |
| **Verified against source** | 2026-08-26 |

---

## Authentication and authorization

Send the JWT either as a header or as the HttpOnly cookie — `JWTAuthenticationFilter` prefers the
header and falls back to the cookie:

```
Authorization: Bearer <jwt>
```

or the cookie named by `${JWT_COOKIE_NAME}` (registered in the OpenAPI spec as `auth_token`), which
`POST /api/auth/login` sets automatically. Sessions are stateless
(`SessionCreationPolicy.STATELESS`); there is nothing server-side to keep alive.

Granted authorities are `ROLE_<NAME>` plus the permission strings `user:create`, `user:read`,
`user:update`, `user:delete`. The four roles are `GUEST` (the default for self-registration),
`EMPLOYEE`, `ADMIN` and `SUPER_ADMIN`.

**There is no role hierarchy bean.** `SUPER_ADMIN` does not automatically satisfy
`hasRole('ADMIN')`; each rule lists its roles explicitly. This matters for the featured toggle
below.

Where each rule comes from:

| Endpoint | Rule source | Roles allowed |
|----------|-------------|---------------|
| `POST /api/v1/news`, `POST /api/v1/news/bulk` | `SecurityConfig` — `POST /api/v1/news/**` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `PUT /api/v1/news/{id}` | `SecurityConfig` — `PUT /api/v1/news/**` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `DELETE /api/v1/news/{id}`, `/delete/{id}`, `/bulk` | `SecurityConfig` — `DELETE /api/v1/news/**` | `ADMIN`, `SUPER_ADMIN` |
| `PATCH /api/v1/news/{id}/featured` | `@PreAuthorize("hasRole('ADMIN')")` on the handler, over `anyRequest().authenticated()` | `ADMIN` only |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/news` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create one article |
| 2 | `POST` | `/api/v1/news/bulk` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create many articles in one transaction |
| 3 | `PUT` | `/api/v1/news/{id}` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Replace an existing article |
| 4 | `PATCH` | `/api/v1/news/{id}/featured` | JWT | `ADMIN` only | Flag / unflag for the homepage carousel |
| 5 | `DELETE` | `/api/v1/news/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete one article |
| 6 | `DELETE` | `/api/v1/news/delete/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Duplicate route for #5 — same handler behaviour |
| 7 | `DELETE` | `/api/v1/news/bulk` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete many articles by id |

---

## Before you write: upload media first

This controller is **plain `application/json` only**. There are no multipart endpoints on
`/api/v1/news` — the earlier multipart upload handlers were removed during the Tiptap migration,
along with the `news_media` table.

The write flow is two-step:

1. `POST /api/v1/media/upload` (`multipart/form-data`, parts `file` and optional `type`) returns an
   `ApiResponse<UploadResponse>` whose `fileUrl` is a public S3 URL. Repeat for the cover, the
   cover thumbnail, and every gallery asset.
2. Send those URLs as strings in the `NewsDto` JSON body here.

Inline body media has a second, server-side path: any `src="data:<mime>;base64,…"` or
`href="data:<mime>;base64,…"` left inside `ckbContent.description` / `kmrContent.description` is
decoded, uploaded to S3 and rewritten to a public URL by `TiptapHtmlProcessor` during the save.
Multipart limits still apply to the media endpoint: max file 1 GB, max request 1 GB.

> **Note:** an `EMPLOYEE` can create and update news but **cannot** call
> `POST /api/v1/media/upload` — `SecurityConfig` gates all of `/api/v1/media/**` to
> `ADMIN`/`SUPER_ADMIN` for every method. An `EMPLOYEE` therefore cannot obtain a `coverUrl` for a
> new article through the supported flow, even though `coverUrl` is mandatory on create. They can
> only reuse an already-uploaded URL, or smuggle inline media through as base64 inside the Tiptap
> HTML, which the processor uploads on their behalf. Treat news authoring as an
> `ADMIN`/`SUPER_ADMIN` workflow in practice.

---

## The `NewsDto` request body

The same DTO is used for create, bulk create and update. There are **no `jakarta.validation`
annotations on `NewsDto`, and no `@Valid` on any handler** — every rule below is hand-written in
`NewsService.validate(...)` and throws `NEWS_VALIDATION` (HTTP 400), never a `fieldErrors` array.

| Field | Type | Required (create) | Required (update) | Constraints | Description |
|-------|------|-------------------|-------------------|-------------|-------------|
| `id` | integer | ignored | ignored | — | Accepted and discarded; the id comes from the URL. Jackson is explicitly configured to tolerate a stray `id` anywhere in the body |
| `coverUrl` | string | **yes** | no (keeps stored value) | non-blank, trimmed, DB `varchar(1024)` | S3 URL of the card cover asset |
| `coverMediaType` | enum `MediaKind` | no | no | `IMAGE` \| `VIDEO` \| `AUDIO` | Defaults to `IMAGE` on create. On update, only applied when non-null |
| `coverThumbnailUrl` | string | no | no | trimmed, `""` → `null`, DB `varchar(1024)` | Poster for a `VIDEO` cover, cover art for an `AUDIO` cover |
| `featureImageUrl` | string | ignored | ignored | — | Read-only here. Written only by `PATCH /{id}/featured` so saving an article never disturbs the hero picture |
| `mediaGallery` | array of `MediaItem` | no | no | see below | Full replace when present. Items with a null/blank `url` are dropped |
| `datePublished` | string `yyyy-MM-dd` | no | no | — | Defaults to today on create (`@PrePersist`). On update, only applied when non-null |
| `createdAt` / `updatedAt` | date-time | ignored | ignored | — | Server-managed by `@PrePersist` / `@PreUpdate` |
| `contentLanguages` | array of enum `Language` | **yes** | **yes** | non-empty; values `CKB`, `KMR` | Drives which content blocks are stored. Always fully replaced on update |
| `category` | object | **yes** | no (keeps stored value) | both names non-blank when present, DB `varchar(120)` | `{ "ckbName": …, "kmrName": … }` |
| `subCategory` | object | **yes** | no (keeps stored value) | both names non-blank when present, DB `varchar(120)` | `{ "ckbName": …, "kmrName": … }` |
| `ckbContent` | object | required when `contentLanguages` contains `CKB` | same | `title` non-blank, DB `varchar(250)`; `description` is `TEXT` | `{ "title": …, "description": … }` |
| `kmrContent` | object | required when `contentLanguages` contains `KMR` | same | same | Same shape |
| `tags` | object | no | no | each entry DB `varchar(80)` | `{ "ckb": [ … ], "kmr": [ … ] }` |
| `keywords` | object | no | no | each entry DB `varchar(120)` | Same shape |

`MediaItem` (each element of `mediaGallery`):

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `url` | string | **yes** | non-blank, trimmed | S3 URL. An item with a blank `url` is silently dropped from the gallery |
| `kind` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO` | Defaults to `IMAGE` |
| `thumbnailUrl` | string | no | trimmed, `""` → `null` | Poster (`VIDEO`) or cover art (`AUDIO`) |
| `captionCkb` | string | no | trimmed, `""` → `null` | Sorani caption |
| `captionKmr` | string | no | trimmed, `""` → `null` | Kurmanji caption |
| `sortOrder` | integer | no | — | Defaults to the item's index among the surviving entries. The list is re-sorted ascending before persisting; a null after defaulting sorts last |

**String hygiene applied to every tag and keyword:** null and blank entries are dropped, the rest
are `trim()`-ed, and the set is a `LinkedHashSet`, so duplicates collapse and insertion order is
preserved.

**Unknown fields.** `NewsDto` carries `@JsonIgnoreProperties(ignoreUnknown = true)`, so unknown
*top-level* fields are silently discarded. The nested types (`CategoryDto`, `SubCategoryDto`,
`LanguageContentDto`, `BilingualSet`, `MediaItem`) do **not**, so an unknown field inside
`category`, `ckbContent`, `tags` or a gallery item raises `400 BAD_REQUEST` with
`details.unknownField`. The single exception is a field literally named `id`, which
`JacksonConfig`'s `DeserializationProblemHandler` tolerates anywhere so that response-shaped
payloads can be sent straight back as requests.

---

## 1. `POST /api/v1/news` — Create an article

Creates one article, resolving (or creating) its category and subcategory on the way, and writes a
`CREATE` row to `news_audit_logs`.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json` (declared `consumes` — a different type gives 415)

**Path parameters**

None.

**Query parameters**

None.

**Request body**

`NewsDto` — see [the table above](#the-newsdto-request-body). Validation runs in this order and
stops at the first failure:

| # | Rule | `code` | `details` |
|---|------|--------|-----------|
| 1 | body present | `NEWS_VALIDATION` | `{ "field": "body", "message": "Request body is required" }` |
| 2 | `contentLanguages` non-empty | `NEWS_VALIDATION` | `{ "field": "contentLanguages" }` |
| 3 | `coverUrl` non-blank | `NEWS_VALIDATION` | `{ "field": "coverUrl" }` |
| 4 | `category.ckbName` and `category.kmrName` non-blank | `NEWS_VALIDATION` | `{ "field": "category" }` |
| 5 | `subCategory.ckbName` and `subCategory.kmrName` non-blank | `NEWS_VALIDATION` | `{ "field": "subCategory" }` |
| 6 | `ckbContent.title` non-blank when `CKB` is listed | `NEWS_VALIDATION` | `{ "field": "ckbContent.title" }` |
| 7 | `kmrContent.title` non-blank when `KMR` is listed | `NEWS_VALIDATION` | `{ "field": "kmrContent.title" }` |

```json
{
  "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
  "coverMediaType": "IMAGE",
  "coverThumbnailUrl": null,
  "mediaGallery": [
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1d4c7b90-2f56-4e18-a0b3-7c9e2d6f8a45-stage-night.jpg",
      "kind": "IMAGE",
      "captionCkb": "شەوی کۆتایی فێستیڤاڵ لە قەڵای هەولێر",
      "captionKmr": "Şeva dawî ya festîvalê li Kelehê Hewlêrê",
      "sortOrder": 0
    },
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/2e5f8a11-6c07-4b93-8d21-4f6a0c1e7b32-opening-night.mp4",
      "kind": "VIDEO",
      "thumbnailUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/93b0d7c4-1a28-4f60-9e7d-5b2c8f4a6d10-opening-poster.jpg",
      "captionCkb": "ڤیدیۆی کردنەوەی فێستیڤاڵ",
      "captionKmr": "Vîdyoya vekirina festîvalê",
      "sortOrder": 1
    }
  ],
  "datePublished": "2026-08-24",
  "contentLanguages": ["CKB", "KMR"],
  "category": {
    "ckbName": "کولتوور",
    "kmrName": "Çand"
  },
  "subCategory": {
    "ckbName": "مۆسیقا",
    "kmrName": "Muzîk"
  },
  "ckbContent": {
    "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
    "description": "<p>فێستیڤاڵی ساڵانەی مۆسیقای کوردی ئێوارەی دوێنێ لە قەڵای هەولێر دەستیپێکرد.</p><figure><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/c7e4b210-9a63-4f57-8b0d-1e2f5c8a7d34-crowd.jpg\"><figcaption>ئامادەبووان لە شەوی یەکەم</figcaption></figure>"
  },
  "kmrContent": {
    "title": "Festîvala muzîka kurdî li Hewlêrê dest pê kir",
    "description": "<p>Festîvala salane ya muzîka kurdî êvara duh li Kelehê Hewlêrê dest pê kir.</p>"
  },
  "tags": {
    "ckb": ["هەولێر", "فێستیڤاڵ"],
    "kmr": ["Hewlêr", "Festîval"]
  },
  "keywords": {
    "ckb": ["مۆسیقای کوردی", "کەلتوور"],
    "kmr": ["muzîka kurdî", "çand"]
  }
}
```

**Response `201 Created`**

The full persisted article, including the server-assigned `id` and timestamps, and with any inline
base64 media in the Tiptap HTML already rewritten to S3 URLs.

```json
{
  "success": true,
  "message": "News created successfully",
  "data": {
    "id": 128,
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
    "coverMediaType": "IMAGE",
    "coverThumbnailUrl": null,
    "featureImageUrl": null,
    "mediaGallery": [
      {
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1d4c7b90-2f56-4e18-a0b3-7c9e2d6f8a45-stage-night.jpg",
        "kind": "IMAGE",
        "thumbnailUrl": null,
        "captionCkb": "شەوی کۆتایی فێستیڤاڵ لە قەڵای هەولێر",
        "captionKmr": "Şeva dawî ya festîvalê li Kelehê Hewlêrê",
        "sortOrder": 0
      },
      {
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/2e5f8a11-6c07-4b93-8d21-4f6a0c1e7b32-opening-night.mp4",
        "kind": "VIDEO",
        "thumbnailUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/93b0d7c4-1a28-4f60-9e7d-5b2c8f4a6d10-opening-poster.jpg",
        "captionCkb": "ڤیدیۆی کردنەوەی فێستیڤاڵ",
        "captionKmr": "Vîdyoya vekirina festîvalê",
        "sortOrder": 1
      }
    ],
    "datePublished": "2026-08-24",
    "createdAt": "2026-08-24T11:42:07.512345",
    "updatedAt": "2026-08-24T11:42:07.512345",
    "contentLanguages": ["CKB", "KMR"],
    "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
    "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
    "ckbContent": {
      "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
      "description": "<p>فێستیڤاڵی ساڵانەی مۆسیقای کوردی ئێوارەی دوێنێ لە قەڵای هەولێر دەستیپێکرد.</p><figure><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/c7e4b210-9a63-4f57-8b0d-1e2f5c8a7d34-crowd.jpg\"><figcaption>ئامادەبووان لە شەوی یەکەم</figcaption></figure>"
    },
    "kmrContent": {
      "title": "Festîvala muzîka kurdî li Hewlêrê dest pê kir",
      "description": "<p>Festîvala salane ya muzîka kurdî êvara duh li Kelehê Hewlêrê dest pê kir.</p>"
    },
    "tags": {
      "ckb": ["هەولێر", "فێستیڤاڵ"],
      "kmr": ["Hewlêr", "Festîval"]
    },
    "keywords": {
      "ckb": ["مۆسیقای کوردی", "کەلتوور"],
      "kmr": ["muzîka kurdî", "çand"]
    }
  }
}
```

**Side effects**

- `NewsCategory` is looked up by `nameCkb` (globally unique). If it exists and the request's
  `kmrName` differs, **the stored `nameKmr` is overwritten** — a global rename affecting every
  article in that category. If it does not exist, it is created.
- `NewsSubCategory` is looked up by `(category, nameCkb)`, with the same rename-on-mismatch
  behaviour, and created when missing.
- Both `ckbContent.description` and `kmrContent.description` pass through `TiptapHtmlProcessor`,
  which uploads any inline base64 asset to S3 and rewrites the attribute.
- Content blocks for languages **not** listed in `contentLanguages` are nulled, and that language's
  tags and keywords are cleared.
- `createdAt`, `updatedAt` and (when omitted) `datePublished` are set by `@PrePersist`.
- A `NewsAuditLog` row is written with `action = "CREATE"`, `note = "News created"`,
  `performedBy = "system"`.
- `@CacheEvict(value = "news", allEntries = true)` clears the entire Redis `news` cache.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `NEWS_VALIDATION` | Any of the seven validation rules above. `details.field` identifies which |
| 400 | `BAD_REQUEST` | Body missing or not valid JSON; unknown field inside a nested object; `contentLanguages` or `coverMediaType` holds an unrecognised enum value |
| 401 | — | No token, or an expired/invalid one (handled by the JWT filter, plain `{"error":…,"message":…}` body) |
| 403 | `FORBIDDEN` | Authenticated as `GUEST` |
| 409 | `CONFLICT` | A value overflows its column — title over 250 chars, tag over 80, keyword over 120, category name over 120, cover URL over 1024 |
| 415 | — | `Content-Type` is not `application/json` |
| 502 | `STORAGE_ERROR` | Reserved by the `Errors.newsStorageFailed(...)` factory; not reachable from the current service code |
| 500 | `INTERNAL_ERROR` | Unexpected failure |

Sample `NEWS_VALIDATION` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/news",
  "method": "POST",
  "traceId": "5a91c7f2-0d3e-4b16-8c47-9e2b1d6f3a84",
  "code": "NEWS_VALIDATION",
  "message": "News validation error",
  "messageEn": "News validation error",
  "messageKu": "هەڵەی پشکنینەوە لە داتای هەواڵدا",
  "fieldErrors": null,
  "details": { "field": "ckbContent.title" }
}
```

> **Note:** the localised text is generic. `NewsService` throws message keys such as
> `news.cover.required` and `news.ckb.title.required` (dot-separated), while the i18n bundles define
> `news.cover_required` and `news.ckb_title_required` (underscore-separated). No key matches, so
> `messageEn` / `messageKu` always fall back to the generic per-code strings from
> `GlobalExceptionHandler.fallbackByCode(...)`. **Drive your dashboard's field highlighting from
> `details.field`, not from the message.**

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/news \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d @article.json
```

---

## 2. `POST /api/v1/news/bulk` — Create many articles

Creates a list of articles in a single database transaction. Used by the dashboard's import screen
and by seeding scripts.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

None.

**Request body**

A JSON array of `NewsDto` objects. Same per-item rules as
[`POST /api/v1/news`](#1-post-apiv1news--create-an-article), plus:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| *(root)* | array of `NewsDto` | **yes** | non-null, non-empty | An empty array is rejected with `NEWS_VALIDATION` and `details = { "field": "list", "message": "News list is empty" }` |

**Every item is validated before anything is written.** The loop runs `validate(dto, true)` over
the whole list first; only then does the transaction open. A single bad item aborts the entire
batch and nothing is persisted.

```json
[
  {
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
    "coverMediaType": "IMAGE",
    "datePublished": "2026-08-24",
    "contentLanguages": ["CKB", "KMR"],
    "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
    "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
    "ckbContent": {
      "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
      "description": "<p>ئێوارەی دوێنێ لە قەڵای هەولێر.</p>"
    },
    "kmrContent": {
      "title": "Festîvala muzîka kurdî li Hewlêrê dest pê kir",
      "description": "<p>Êvara duh li Kelehê Hewlêrê.</p>"
    },
    "tags": { "ckb": ["هەولێر"], "kmr": ["Hewlêr"] },
    "keywords": { "ckb": ["مۆسیقای کوردی"], "kmr": ["muzîka kurdî"] }
  },
  {
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a4d81f30-5c72-4e69-bb15-3f8a0e2c9d47-dukan-lake.jpg",
    "coverMediaType": "IMAGE",
    "datePublished": "2026-08-25",
    "contentLanguages": ["CKB"],
    "category": { "ckbName": "ژینگە", "kmrName": "Jîngeh" },
    "subCategory": { "ckbName": "ئاو", "kmrName": "Av" },
    "ckbContent": {
      "title": "ئاستی ئاوی دوکان سێ مەتر بەرزبووەتەوە",
      "description": "<p>بەرپرسانی ئاودێری سلێمانی ڕایانگەیاند.</p>"
    },
    "tags": { "ckb": ["دوکان", "سلێمانی"], "kmr": [] },
    "keywords": { "ckb": ["ئاستی ئاو"], "kmr": [] }
  }
]
```

**Response `201 Created`**

`data` is an array of persisted `NewsDto` objects in the same order as the request.

```json
{
  "success": true,
  "message": "News created successfully (bulk)",
  "data": [
    {
      "id": 128,
      "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
      "coverMediaType": "IMAGE",
      "coverThumbnailUrl": null,
      "featureImageUrl": null,
      "mediaGallery": [],
      "datePublished": "2026-08-24",
      "createdAt": "2026-08-26T09:14:22.004118",
      "updatedAt": "2026-08-26T09:14:22.004118",
      "contentLanguages": ["CKB", "KMR"],
      "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
      "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
      "ckbContent": {
        "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
        "description": "<p>ئێوارەی دوێنێ لە قەڵای هەولێر.</p>"
      },
      "kmrContent": {
        "title": "Festîvala muzîka kurdî li Hewlêrê dest pê kir",
        "description": "<p>Êvara duh li Kelehê Hewlêrê.</p>"
      },
      "tags": { "ckb": ["هەولێر"], "kmr": ["Hewlêr"] },
      "keywords": { "ckb": ["مۆسیقای کوردی"], "kmr": ["muzîka kurdî"] }
    },
    {
      "id": 129,
      "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a4d81f30-5c72-4e69-bb15-3f8a0e2c9d47-dukan-lake.jpg",
      "coverMediaType": "IMAGE",
      "coverThumbnailUrl": null,
      "featureImageUrl": null,
      "mediaGallery": [],
      "datePublished": "2026-08-25",
      "createdAt": "2026-08-26T09:14:22.004118",
      "updatedAt": "2026-08-26T09:14:22.004118",
      "contentLanguages": ["CKB"],
      "category": { "ckbName": "ژینگە", "kmrName": "Jîngeh" },
      "subCategory": { "ckbName": "ئاو", "kmrName": "Av" },
      "ckbContent": {
        "title": "ئاستی ئاوی دوکان سێ مەتر بەرزبووەتەوە",
        "description": "<p>بەرپرسانی ئاودێری سلێمانی ڕایانگەیاند.</p>"
      },
      "kmrContent": null,
      "tags": { "ckb": ["دوکان", "سلێمانی"], "kmr": [] },
      "keywords": { "ckb": ["ئاستی ئاو"], "kmr": [] }
    }
  ]
}
```

**Side effects**

Same as the single create, applied per item, plus:

- Categories and subcategories are resolved inside the loop, so several items sharing a taxonomy
  reuse the row created by the first item in the same transaction.
- Audit logs are written with `saveAll`, `action = "CREATE"`, `note = "News bulk created"`.
- One `@CacheEvict(value = "news", allEntries = true)` for the whole batch.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `NEWS_VALIDATION` | Empty array (`details.field = "list"`), or any item failing a validation rule. `details.field` names the failing *field*, not the failing *index* |
| 400 | `BAD_REQUEST` | Body is not a JSON array, or an unknown field inside a nested object |
| 401 / 403 | — / `FORBIDDEN` | Missing token / insufficient role |
| 409 | `CONFLICT` | Any item overflows a column length |
| 415 | — | `Content-Type` is not `application/json` |

> **Note:** on a validation failure the response identifies the field but **not which array
> element** failed. Validate client-side before submitting a large batch, or submit in small
> chunks, otherwise a 50-item import that fails tells you only that some item is missing, for
> example, `subCategory`.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/news/bulk \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d @articles.json
```

---

## 3. `PUT /api/v1/news/{id}` — Update an article

Loads the article, applies the body, and writes an `UPDATE` audit row. This is **not** a JSON Merge
Patch: some fields merge (omit to keep the stored value) while others are replaced wholesale. Read
the per-field semantics below carefully.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Article primary key |

**Query parameters**

None.

**Request body**

`NewsDto`. Validation runs first with `createRequiresCover = false`, so rules 1, 2, 4, 5, 6 and 7
from the create table apply, but the `coverUrl` rule is relaxed: a blank `coverUrl` is fine as long
as the *stored* article already has one.

Per-field update semantics:

| Field | Behaviour when omitted / null | Behaviour when present |
|-------|------------------------------|------------------------|
| `coverUrl` | keeps the stored value | replaced (trimmed) |
| `coverMediaType` | keeps the stored value | replaced |
| `coverThumbnailUrl` | keeps the stored value | replaced; send `""` to clear it to `null` |
| `mediaGallery` | keeps the stored gallery | **full replace**; send `[]` to empty it |
| `datePublished` | keeps the stored date | replaced |
| `category` | keeps the stored category | resolved / created, with the same rename-on-mismatch behaviour as create |
| `subCategory` | keeps the stored subcategory | resolved / created **under the article's current category** |
| `contentLanguages` | **not optional** — validation rejects an empty or missing set | always fully replaced |
| `ckbContent` / `kmrContent` | **full replace**, always | the whole block is rebuilt from the body |
| `tags.ckb`, `tags.kmr`, `keywords.ckb`, `keywords.kmr` | each side keeps its stored set when that array is null | that side is cleared and refilled |

```json
{
  "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/f1a93b57-84c0-4d2e-9b16-7a5e3c0d8f42-festival-day-two.jpg",
  "coverMediaType": "IMAGE",
  "coverThumbnailUrl": "",
  "mediaGallery": [],
  "datePublished": "2026-08-24",
  "contentLanguages": ["CKB", "KMR"],
  "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
  "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
  "ckbContent": {
    "title": "فێستیڤاڵی مۆسیقای کوردی: ڕۆژی دووەم",
    "description": "<p>ڕۆژی دووەمی فێستیڤاڵ بە بەشداریی ژمارەیەکی زۆر لە هونەرمەندان بەڕێوەچوو.</p>"
  },
  "kmrContent": {
    "title": "Festîvala muzîka kurdî: roja duyemîn",
    "description": "<p>Roja duyemîn a festîvalê bi beşdariya gelek hunermendan pêk hat.</p>"
  },
  "tags": {
    "ckb": ["هەولێر", "فێستیڤاڵ", "مۆسیقا"],
    "kmr": ["Hewlêr", "Festîval", "Muzîk"]
  },
  "keywords": {
    "ckb": ["مۆسیقای کوردی"],
    "kmr": ["muzîka kurdî"]
  }
}
```

**Response `200 OK`**

```json
{
  "success": true,
  "message": "News updated successfully",
  "data": {
    "id": 128,
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/f1a93b57-84c0-4d2e-9b16-7a5e3c0d8f42-festival-day-two.jpg",
    "coverMediaType": "IMAGE",
    "coverThumbnailUrl": null,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f2a1c60-7d34-49b2-b1c8-0e5d9a3f2b71-festival-hero-wide.jpg",
    "mediaGallery": [],
    "datePublished": "2026-08-24",
    "createdAt": "2026-08-24T11:42:07.512345",
    "updatedAt": "2026-08-26T09:14:22.331907",
    "contentLanguages": ["CKB", "KMR"],
    "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
    "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
    "ckbContent": {
      "title": "فێستیڤاڵی مۆسیقای کوردی: ڕۆژی دووەم",
      "description": "<p>ڕۆژی دووەمی فێستیڤاڵ بە بەشداریی ژمارەیەکی زۆر لە هونەرمەندان بەڕێوەچوو.</p>"
    },
    "kmrContent": {
      "title": "Festîvala muzîka kurdî: roja duyemîn",
      "description": "<p>Roja duyemîn a festîvalê bi beşdariya gelek hunermendan pêk hat.</p>"
    },
    "tags": {
      "ckb": ["هەولێر", "فێستیڤاڵ", "مۆسیقا"],
      "kmr": ["Hewlêr", "Festîval", "Muzîk"]
    },
    "keywords": {
      "ckb": ["مۆسیقای کوردی"],
      "kmr": ["muzîka kurdî"]
    }
  }
}
```

Note that `featureImageUrl` came back unchanged even though the body never mentioned it — the
update path never reads that field.

**Side effects**

- Both descriptions run through `TiptapHtmlProcessor` again. Already-uploaded S3 URLs are left
  alone (the processor short-circuits when the HTML contains no `data:`), so a re-save does not
  duplicate assets.
- `updatedAt` is refreshed by `@PreUpdate`.
- A `NewsAuditLog` row with `action = "UPDATE"`, `note = "News updated"`, `performedBy = "system"`.
- `@CacheEvict(value = "news", allEntries = true)`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `NEWS_VALIDATION` | `contentLanguages` empty, category/subcategory names blank, a listed language's title blank, or `coverUrl` blank in both the body and the stored row |
| 400 | `BAD_REQUEST` | Malformed JSON, unknown field inside a nested object, unrecognised enum value |
| 401 / 403 | — / `FORBIDDEN` | Missing token / insufficient role |
| 404 | `NEWS_NOT_FOUND` | No article with that id. `details` carries `{ "id": "999" }` |
| 409 | `CONFLICT` | A value overflows its column length |
| 415 | — | `Content-Type` is not `application/json` |
| 500 | `INTERNAL_ERROR` | `{id}` is not numeric — the path variable fails to bind and the catch-all handler answers 500 |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/news/128 \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d @article-update.json
```

> **Note — three update behaviours that will surprise you:**
>
> 1. **Content blocks are always rebuilt from the body.** Sending `ckbContent` with a `title` but
>    no `description` wipes the stored Sorani body. Always send both fields.
> 2. **Dropping a language does not clear its tags and keywords, unlike create.** `updateNews`
>    calls `applyContentByLanguages` (which clears the dropped language's tag and keyword sets) and
>    *then* `replaceBilingualSets` (which refills them from the body). Removing `KMR` from
>    `contentLanguages` while still sending `tags.kmr` leaves `kmrContent = null` but `tagsKmr`
>    populated. `addNews` runs the same two steps in the opposite order and does clear them. Send
>    `"kmr": []` explicitly when you drop a language.
> 3. **Changing `category` without also sending `subCategory`** leaves the article pointing at a
>    subcategory that belongs to the *previous* category — the service only re-resolves the
>    subcategory when the body supplies one. Always send both together.

---

## 4. `PATCH /api/v1/news/{id}/featured` — Flag an article for the homepage carousel

Turns the `featured` flag on or off, sets the carousel position, and optionally sets the wide hero
picture. Handled by `SiteContentService.setNewsFeatured`, not by `NewsService`.

**Auth:** `Authorization: Bearer <token>`, role `ADMIN` **only**
**Content-Type:** `application/json`

> **Note — authorization discrepancy.** `SecurityConfig` has no `PATCH` rule for
> `/api/v1/news/**` (its only `PATCH` content rule covers `/api/v1/videos/**`), so this path falls
> through to `anyRequest().authenticated()`. The handler's `@PreAuthorize("hasRole('ADMIN')")` then
> narrows it, and because no `RoleHierarchy` bean is registered, **`SUPER_ADMIN` is rejected with
> 403** even though it can create, update and delete the same article. Every other featured toggle
> in this codebase is reachable by `ADMIN` and `SUPER_ADMIN`. Use an `ADMIN` account for this call.

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Article primary key |

**Query parameters**

None.

**Request body**

`SiteContentDtos.FeaturedRequest`. The class declares `@NotBlank` on `type`, `slug`, `title`,
`description` and `imageUrl`, but the handler parameter is a bare `@RequestBody` with **no
`@Valid`**, so those constraints are never evaluated. Only three fields are actually read:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | boolean | no | — | `true` or omitted/`null` turns the flag **on**; `false` turns it off |
| `featuredOrder` | integer | no | — | Carousel position, ascending. `null` sorts the article last. Forced to `null` whenever `featured` is `false` |
| `featureImageUrl` | string | no | — | Wide hero picture. `null`/omitted leaves the stored value alone; `""` clears it to `null` so the carousel falls back to `coverUrl` |
| `type`, `slug`, `title`, `description`, `imageUrl`, `imageAlt`, `locale`, `displayOrder`, `active` | — | no | not validated | Accepted and ignored by `setNewsFeatured` |

`FeaturedRequest` has no `@JsonIgnoreProperties`, so a field outside that list raises
`400 BAD_REQUEST` with `details.unknownField` — the sole exception being `id`.

Turn on and position first, with a hero image:

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f2a1c60-7d34-49b2-b1c8-0e5d9a3f2b71-festival-hero-wide.jpg"
}
```

Turn off:

```json
{
  "featured": false
}
```

Clear the hero image but stay featured:

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": ""
}
```

**Response `204 No Content`**

Empty body — no `ApiResponse` envelope. Re-read the article via
[`GET /api/v1/news/{id}`](../external/NEWS_API.md#3-get-apiv1newsid--one-article) or
[`GET /api/v1/news/featured`](../external/NEWS_API.md#2-get-apiv1newsfeatured--homepage-carousel-articles)
to confirm the new state. `NewsDto` exposes no `featured` / `featuredOrder` field, so the only way
to read back the flag is membership in the `/featured` list.

**Side effects**

- `news.featured`, `news.featured_order` and possibly `news.feature_image_url` are updated.
- **No audit log row** is written — `NewsAuditLog` only covers `CREATE`, `UPDATE` and `DELETE` from
  `NewsService`.
- **No cache eviction.** `SiteContentService` carries no `@CacheEvict`, so cached
  `GET /api/v1/news` pages keep the previous `featureImageUrl` until the 10-minute TTL expires.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | The global featured-slide cap is already reached and you are turning the flag on for an article that is not already featured. `details.reason` reads `"Maximum of 7 featured slides allowed across all content. Unfeature one first."` |
| 400 | `BAD_REQUEST` | Malformed JSON or an unknown field in the body |
| 401 / 403 | — / `FORBIDDEN` | Missing token, or any role other than `ADMIN` — including `SUPER_ADMIN` |
| 404 | `NOT_FOUND` | No article with that id. Note the code is the **generic** `NOT_FOUND`, not `NEWS_NOT_FOUND`, because this path throws `EntityNotFoundException` rather than the News-specific exception |
| 500 | `INTERNAL_ERROR` | `{id}` is not numeric |

Sample cap-reached body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/news/128/featured",
  "method": "PATCH",
  "traceId": "b7f30c81-9d24-4e57-a013-6c8b2f5e9d41",
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

Sample 404 body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/news/999/featured",
  "method": "PATCH",
  "traceId": "c2d84a19-7f60-4b35-8e1a-0d9c5b3f6e27",
  "code": "NOT_FOUND",
  "message": "News not found: 999",
  "messageEn": "News not found: 999",
  "messageKu": "News not found: 999",
  "fieldErrors": null,
  "details": { "resource": "News not found: 999" }
}
```

**The slide cap in detail.** `countAllFeatured()` sums featured rows across news, projects,
writings, videos, sound tracks, image collections, plus 1 when the donation settings row is
featured. The limit is `SiteSettings.maxFeaturedSlides`, default
`SiteSettings.DEFAULT_MAX_FEATURED_SLIDES = 7`, adjustable through `PUT /api/v1/site-settings`.
Services and About pages are deliberately excluded from the count. The check is skipped when the
article is already featured (so re-ordering an existing slide always works) and when you are
turning the flag off.

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/news/128/featured \
  -H "Authorization: Bearer $KHI_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": true, "featuredOrder": 1}'
```

---

## 5. `DELETE /api/v1/news/{id}` — Delete an article

Deletes the article and its owned collection rows, after writing a `DELETE` audit row.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** none (no request body)

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Article primary key |

**Query parameters**

None.

**Request body**

None.

**Response `204 No Content`**

Empty body, no envelope.

**This endpoint is idempotent and never returns 404.** The service loads the article, and when it
does not exist it logs a debug line and returns normally — the response is still `204`. You cannot
distinguish "deleted it" from "it was never there" by status code.

**Side effects**

- A `NewsAuditLog` row with `action = "DELETE"`, `note = "News deleted"`,
  `performedBy = "system"` — written **before** the delete, and it survives the delete because
  `NewsAuditLog.newsId` is a plain `Long` column with no foreign key.
- The row's `@ElementCollection` tables are cleared by Hibernate: `news_content_languages`,
  `news_tags_ckb`, `news_tags_kmr`, `news_keywords_ckb`, `news_keywords_kmr`. `media_gallery` is a
  JSONB column on the row itself and goes with it.
- `NewsCategory` and `NewsSubCategory` rows are **not** deleted, even when the article was the last
  one using them. Empty taxonomy entries accumulate and there is no cleanup endpoint.
- **S3 objects are not deleted.** The cover, the gallery assets and every inline Tiptap asset stay
  in `s3-khiwebsite`. There is no orphan sweep; `DELETE /api/v1/media?fileUrl=…` exists but is
  never called from this path.
- `@CacheEvict(value = "news", allEntries = true)`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 401 / 403 | — / `FORBIDDEN` | Missing token, or role is `GUEST` or `EMPLOYEE` |
| 500 | `INTERNAL_ERROR` | `{id}` is not numeric |

**Example**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  http://localhost:8080/api/v1/news/128 \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 6. `DELETE /api/v1/news/delete/{id}` — Delete an article (duplicate route)

Identical to [#5](#5-delete-apiv1newsid--delete-an-article) in every respect: same path variable,
same `newsService.deleteNews(id)` call, same `204` response, same side effects, same errors, same
`ADMIN`/`SUPER_ADMIN` rule from the `DELETE /api/v1/news/**` matcher. The only difference is the
log line the handler emits.

> **Note — duplicate route.** `NewsController` declares two mappings,
> `@DeleteMapping("/{id}")` → `deleteNews` and `@DeleteMapping("/delete/{id}")` → `deleteNewsAlt`,
> whose bodies are the same single service call. This is redundant surface area, presumably kept
> for an older dashboard build. **Use `DELETE /api/v1/news/{id}`.** Treat `/delete/{id}` as
> deprecated: it is not wrong, but it doubles the paths that must stay in sync and it is the one
> more likely to be removed.

**Example**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  http://localhost:8080/api/v1/news/delete/128 \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 7. `DELETE /api/v1/news/bulk` — Delete many articles

Deletes every article in the supplied id list, in one transaction.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json` (declared `consumes` — see the note below, this one bites)

**Path parameters**

None.

**Query parameters**

None.

**Request body**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| *(root)* | array of integer (int64) | **yes** | — | Article ids. `null` or `[]` is a no-op that still answers `204` |

```json
[128, 129, 131]
```

**Response `204 No Content`**

Empty body, no envelope.

**Partial matches are silently tolerated.** The service runs `findAllById(newsIds)` and deletes
whatever it finds. Ids that do not exist are ignored; the response is `204` either way, and there
is no report of how many rows were actually removed. If the list matches nothing at all, the
transaction returns early and not even audit rows are written.

**Side effects**

- One `NewsAuditLog` row per *found* article, `action = "DELETE"`, `note = "News bulk deleted"`,
  `performedBy = "system"`, written before the delete.
- Same cascade, taxonomy and S3 behaviour as the single delete — orphaned categories and orphaned
  S3 objects remain.
- `@CacheEvict(value = "news", allEntries = true)`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | Body missing, empty, or not a JSON array of numbers |
| 401 / 403 | — / `FORBIDDEN` | Missing token, or role is `GUEST` or `EMPLOYEE` |
| 500 | `INTERNAL_ERROR` | `Content-Type` is not `application/json` — see the note below |

> **Note — route collision with `DELETE /{id}`.** `/api/v1/news/bulk` is declared with
> `consumes = application/json`. When the header is missing or different, that mapping's consumes
> condition fails and the request is matched by the sibling template `DELETE /api/v1/news/{id}`
> instead, with `id = "bulk"`. Binding `"bulk"` to `Long` throws
> `MethodArgumentTypeMismatchException`, for which `GlobalExceptionHandler` has no dedicated
> handler, so the catch-all answers `500 INTERNAL_ERROR` rather than `415` or `400`. **Always send
> `Content-Type: application/json` on this call.**

**Example**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  http://localhost:8080/api/v1/news/bulk \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d '[128, 129, 131]'
```

---

## The category / subcategory taxonomy

News uses a strict two-level taxonomy, stored as real entities rather than free strings, and
**maintained implicitly by the write endpoints**. There is no category CRUD API on this controller —
categories come into existence as a side effect of saving an article.

```
NewsCategory                       news_categories
  id
  nameCkb   NOT NULL, UNIQUE       name_ckb  varchar(120)   ← the lookup key
  nameKmr   NOT NULL               name_kmr  varchar(120)
  subCategories  1..n              CascadeType.ALL + orphanRemoval

NewsSubCategory                    news_sub_categories
  id
  nameCkb   NOT NULL               name_ckb  varchar(120)
  nameKmr   NOT NULL               name_kmr  varchar(120)
  category  NOT NULL               UNIQUE (category_id, name_ckb)
```

Rules the write path enforces:

1. **Both levels are mandatory on create**, in both languages. `news.category_id` and
   `news.sub_category_id` are `NOT NULL`.
2. **`nameCkb` is the identity.** `getOrCreateCategory` looks up by `nameCkb` only.
   `getOrCreateSubCategory` looks up by `(category, nameCkb)`.
3. **`nameKmr` is overwritten on mismatch.** If a category already exists under a Sorani name and
   your request sends a different Kurmanji name, the stored `nameKmr` is silently updated for
   *every* article in that category. Use this deliberately to rename a category; guard against it
   in the dashboard by pre-filling both names from an existing article rather than letting an
   editor retype them.
4. **Typos create new categories.** There is no fuzzy match, no normalisation beyond `trim()`, and
   no uniqueness check on `nameKmr`. `کولتوور ` and `کولتوور` collapse (trim), but `کەلتوور` becomes
   a second category. Drive the dashboard's category picker from the distinct values already in
   use, and only allow free text when creating a genuinely new one.
5. **Nothing removes an unused category.** Deleting the last article in a category leaves the
   category and its subcategories in place.
6. **Renaming a subcategory's Sorani name creates a new subcategory** rather than renaming the old
   one, because the lookup key changed. The old row stays, now orphaned of articles.

---

## Tiptap HTML bodies

`ckbContent.description` and `kmrContent.description` carry Tiptap editor HTML. On every create and
update they pass through `TiptapHtmlProcessor.process(...)`, which is the single media entry point
for the whole platform.

What the processor does:

- Scans for `src="data:<mime>;base64,<payload>"` on `<img>`, `<video>`, `<audio>` and `<source>`,
  and `href="data:<mime>;base64,<payload>"` on `<a>` (PDFs and other downloads).
- Base64-decodes each payload, derives an extension from the MIME type, uploads the bytes to S3
  under `khi-web-folders/images/`, `/video/`, `/audio/` or `/files/` depending on the MIME family,
  and rewrites the attribute to the returned public URL.
- Filenames are generated as `tiptap-<nanoTime>.<ext>`, then keyed as
  `khi-web-folders/<folder>/<uuid>-tiptap-<nanoTime>.<ext>`.

Behaviour guarantees that matter to a client:

- **Idempotent.** HTML with no `data:` substring is returned untouched, so re-saving an article
  never re-uploads its assets or duplicates S3 objects.
- **Null- and blank-safe.** A null or empty description passes straight through.
- **Best-effort, never fatal.** A malformed base64 payload or a failed S3 upload is logged and that
  one attribute is left exactly as sent; the rest of the document still uploads and the save still
  succeeds with `201`/`200`. **A `2xx` is not proof that every inline asset made it to S3.** If
  that matters, upload through `POST /api/v1/media/upload` and embed the returned URLs yourself
  rather than relying on inline base64.
- **No sanitisation.** There is no allow-list, no script stripping, no attribute filtering.
  Whatever HTML you send is what the public site will render.

A `buildContent` shortcut worth knowing: if both `title` and `description` are blank for a language,
the whole `NewsContent` block is stored as `null`. Combined with the title-required validation rule,
this only bites for a language that is *not* in `contentLanguages`.

---

## Enums used by this API

### `Language`

Accepted in `contentLanguages`.

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish (Sorani) — enables `ckbContent`, `tags.ckb`, `keywords.ckb` |
| `KMR` | Northern Kurdish (Kurmanji) — enables `kmrContent`, `tags.kmr`, `keywords.kmr` |

`Language.from` is `@JsonCreator`-annotated: input is trimmed and upper-cased, so `"ckb"`, `" CKB "`
and `"Ckb"` all deserialise. An unrecognised value raises `IllegalArgumentException` inside Jackson
and surfaces as `400 BAD_REQUEST`.

### `MediaKind`

Accepted in `coverMediaType` and in each `mediaGallery[].kind`.

| Value | Meaning |
|-------|---------|
| `IMAGE` | Rendered with `<img>`; `thumbnailUrl` ignored |
| `VIDEO` | Rendered with `<video>`, `thumbnailUrl` used as the poster |
| `AUDIO` | Rendered with `<audio>`, `thumbnailUrl` used as cover art |

Standard enum deserialisation — the value must match a constant name exactly (case-sensitive).
`null` falls back to `IMAGE`.

---

## Error responses

All errors use `ApiErrorResponse`:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/news/999",
  "method": "PUT",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NEWS_NOT_FOUND",
  "message": "News not found.",
  "messageEn": "News not found.",
  "messageKu": "هەواڵ نەدۆزرایەوە",
  "fieldErrors": null,
  "details": { "id": "999" }
}
```

Codes this domain can emit on the write side:

| `code` | Status | Source |
|--------|--------|--------|
| `NEWS_VALIDATION` | 400 | `NewsService.validate(...)` and the bulk empty-list check |
| `NEWS_NOT_FOUND` | 404 | `PUT /{id}` on a missing article |
| `BAD_REQUEST` | 400 | Malformed JSON, unknown nested field, bad enum value, featured-slide cap reached, blank search term |
| `NOT_FOUND` | 404 | `PATCH /{id}/featured` on a missing article (generic, not News-specific) |
| `FORBIDDEN` | 403 | Role check failed |
| `CONFLICT` | 409 | Column-length overflow surfacing as a `DataIntegrityViolationException` |
| `METHOD_NOT_ALLOWED` | 405 | Wrong verb for the path |
| `INTERNAL_ERROR` | 500 | Non-numeric path variable, or any uncaught failure |

Notes on the error body:

- `fieldErrors` is always `null` here. It is only populated by `MethodArgumentNotValidException` and
  `ConstraintViolationException`, and no News handler is annotated `@Valid` or `@Validated`. Use
  `details.field` instead.
- `traceId` mirrors the `X-Trace-Id` response header set by `TraceIdFilter`. Send your own
  `X-Trace-Id` request header to correlate dashboard logs with server logs.
- `message` honours `Accept-Language` (`en`, `ckb`, `kmr`; default `en`), and `?lang=ckb` also
  switches the locale.

> **Note:** `messageKu` is resolved against `Locale.forLanguageTag("ku")`, but the bundles are
> `messages_en.properties`, `messages_ckb.properties` and `messages_kmr.properties` — no
> `messages_ku.properties` and no default `messages.properties` exist. Every `messageKu` therefore
> falls back to the hard-coded Sorani string in `GlobalExceptionHandler.fallbackByCode(...)` and
> never reads the Kurmanji bundle.

Two error factories exist for this domain but are **never called**: `Errors.newsConflict(...)`
(`NEWS_CONFLICT`, 409) and `Errors.newsStorageFailed(...)` (`STORAGE_ERROR`, 502), along with
`Errors.newsInternal(...)`. Do not write client branches for `NEWS_CONFLICT` — a duplicate-data
conflict surfaces as the generic `CONFLICT` from the `DataIntegrityViolationException` handler.

---

## Notes & gotchas

**No `@Valid`, no bean validation, anywhere in this controller.** `NewsDto` has zero
`jakarta.validation` annotations, and `FeaturedRequest`'s five `@NotBlank`s are inert because the
handler omits `@Valid`. All enforcement is the hand-written `NewsService.validate(...)`. Build your
dashboard's client-side validation to mirror the seven rules listed under
[create](#1-post-apiv1news--create-an-article) — the server will not catch anything else.

**Field length limits are enforced by PostgreSQL, not by the service.** Overflowing a column
produces a `DataIntegrityViolationException` that the global handler reports as `409 CONFLICT` with
the generic message "A record with this data already exists" and a hint about usernames and emails
— misleading text for a too-long title. Enforce these client-side:

| Field | Limit |
|-------|-------|
| `ckbContent.title`, `kmrContent.title` | 250 chars |
| `category.ckbName`, `category.kmrName`, `subCategory.ckbName`, `subCategory.kmrName` | 120 chars |
| each `tags.ckb` / `tags.kmr` entry | 80 chars |
| each `keywords.ckb` / `keywords.kmr` entry | 120 chars |
| `coverUrl`, `coverThumbnailUrl` | 1024 chars |
| descriptions, `featureImageUrl` | `TEXT`, effectively unbounded |

**Every write evicts the entire `news` cache.** Create, bulk create, update, delete and bulk delete
all carry `@CacheEvict(value = "news", allEntries = true)`, so the six cached public read endpoints
go stale-free immediately. The one exception is `PATCH /{id}/featured`, which evicts nothing — see
[#4](#4-patch-apiv1newsidfeatured--flag-an-article-for-the-homepage-carousel).

**Cached values are JDK-serialised into Redis.** `NewsDto` and all four nested DTOs implement
`Serializable` with a pinned `serialVersionUID = 1L`. If you add a field to `NewsDto`, keep the
`serialVersionUID` and keep the new type `Serializable`, otherwise the first cache write after
deploy throws `SerializationFailedException` — or, worse, previously written entries fail to read
back with `InvalidClassException` until the 10-minute TTL flushes them. `CacheSerializationTests`
guards this contract.

**Audit logs record who as `"system"`, always.** `NewsService.buildAuditLog` hard-codes
`performedBy = "system"` — it never reads `SecurityContextHolder`. `news_audit_logs` tells you what
changed and when, but not which admin did it. There is also no read endpoint for the audit table.

**Audit rows outlive their articles.** `NewsAuditLog.newsId` is a plain `Long` column with no
`@ManyToOne` and no foreign key, so `DELETE` rows survive the article's removal and the id can be
reused by a future `IDENTITY` value only if the sequence is reset. Read the audit table by
`newsId` with a date filter, not by joining to `news`.

**Transactions are explicit, not annotation-driven.** `addNews`, `addNewsBulk`, `updateNews`,
`deleteNews` and `deleteNewsBulk` use a `TransactionTemplate` rather than `@Transactional`. The
practical consequence: **the Tiptap S3 uploads for a create happen inside the transaction**, so a
slow upload holds a database connection open, and a rollback after the upload leaves the uploaded
objects orphaned in S3.

**Reads that follow a write may miss the write.** The cached public read endpoints are evicted, so
they are fine. But `PATCH /{id}/featured` writes without eviction — after flagging an article, read
back from `/api/v1/news/featured` (uncached) rather than from `/api/v1/news` (cached 10 minutes).

**`featureImageUrl` has exactly one writer.** Neither create nor update reads it from the body;
only `PATCH /{id}/featured` sets it. This is deliberate — it means an editor saving an article can
never clobber the hero picture the carousel is using.

**Paging arguments on the public read endpoints are unclamped.** Not a write concern, but the
dashboard's list view calls the same public `GET /api/v1/news`: `size=0` or a negative `page`
produces `500 INTERNAL_ERROR`, not `400`. Clamp in the dashboard.

**Three GET aliases exist for the list endpoint** — `""`, `"/"`, `"/all"` all map to the same
handler. Documented once in
[`../external/NEWS_API.md`](../external/NEWS_API.md#1-get-apiv1news--list-every-article).

---

## Related documentation

- Counterpart (public reads): [`../external/NEWS_API.md`](../external/NEWS_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`
  (groups: `public`, `internal`, `all`)

> **Note:** the springdoc `internal` group matches only `/api/auth/logout*`,
> `/api/auth/sessions/**`, `/api/user/**` and `/api/v1/media/**`. Every endpoint on this page lives
> under `/api/v1/news/**`, which the `public` group claims, so the write endpoints documented here
> appear in the **`public`** Swagger group despite requiring a JWT. The group split in Swagger is
> path-based and does not reflect the actual authorization rules — this document does.
