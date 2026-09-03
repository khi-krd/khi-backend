# Projects API — External (Public)

Projects are KHI's bilingual portfolio records: a cover asset, an optional mixed-media gallery, a Sorani (CKB) and/or Kurmanji (KMR) content block whose `description` holds Tiptap-authored HTML, plus two independent taxonomies (tags and keywords) used for browsing and search. Everything documented on this page is readable by an anonymous visitor with no token — it is what the public website calls to render the projects index, the homepage carousel slot, a project detail page, and the tag/keyword result pages.

Writes (create, update, delete, feature toggle) are **not** here. See [`../internal/PROJECT_API.md`](../internal/PROJECT_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/projects` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/project/ProjectController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/project/ProjectService.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/project/ProjectRepository.java` |
| **Entities** | `Project`, `ProjectContentBlock` (embeddable), `ProjectTag`, `ProjectKeyword`, `MediaItem` (JSONB) |
| **Response DTO** | `ProjectResponse` (+ nested `ProjectResponse.ProjectContentBlockDto`) |
| **Envelope** | `ApiResponse<T>` — every endpoint on this page is wrapped |
| **Verified against source** | 2026-08-26 |

---

## Authorization

`SecurityConfig` reaches these endpoints through the public-read catch-all:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

No rule above it matches `/api/v1/projects/**`, and none of the five handlers below carries `@PreAuthorize`. All five are therefore anonymous-callable. Sending an `Authorization` header is harmless but changes nothing about the response.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/projects/getAll` | None | — | Paged list of every project, newest id first |
| 2 | `GET` | `/api/v1/projects/featured` | None | — | Paged list of projects flagged for the homepage carousel |
| 3 | `GET` | `/api/v1/projects/{id}` | None | — | One project by numeric id |
| 4 | `GET` | `/api/v1/projects/search/tag` | None | — | Substring search across CKB **and** KMR tag names |
| 5 | `GET` | `/api/v1/projects/search/keyword` | None | — | Substring search across CKB **and** KMR keyword names |

> **Note:** `ProjectService` also exposes a `globalSearch(q, page, size)` method that searches titles, descriptions, tags and keywords in one pass. `ProjectController` does not map it, so there is no `GET /api/v1/projects/search` route. The cross-domain `/api/v1/search/**` endpoints are the supported way to reach it; that is a separate domain and is documented elsewhere.

---

## The response envelope

Every endpoint on this page returns `ak.dev.khi_backend.khi_app.dto.ApiResponse<T>`:

```json
{
  "success": true,
  "message": "Projects fetched successfully",
  "data": { }
}
```

| Field | Type | Notes |
|-------|------|-------|
| `success` | boolean | Always `true` on a 2xx from these handlers |
| `message` | string | Fixed English string, different per endpoint (listed below). Not localised — do not display it to visitors |
| `data` | object | The `ProjectResponse` or the page object |

`ApiResponse` is annotated `@JsonInclude(NON_NULL)`, so `data` disappears from the body when it is null. Error responses never use this envelope — see [Errors](#errors).

---

## The page object

The four list endpoints put a Spring Data `Page<ProjectResponse>` in `data`. Read these keys:

| Field | Type | Meaning |
|-------|------|---------|
| `content` | array | The `ProjectResponse` objects on this page |
| `number` | int | Zero-based index of the page you got back |
| `size` | int | Page size that was applied |
| `totalElements` | long | Total matching projects across all pages |
| `totalPages` | int | Total page count |
| `numberOfElements` | int | `content.length` |
| `first` / `last` | boolean | Page position flags |
| `empty` | boolean | `true` when `content` is empty |
| `pageable` | object | Echo of the request page/size/offset |
| `sort` | object | Sort descriptor |

> **Note:** the app registers a hand-built `com.fasterxml.jackson.databind.ObjectMapper` in `JacksonConfig`, which replaces the Spring-Boot-built mapper and does not carry Spring Data's page serialization module. The `content`, `number`, `size`, `totalElements`, `totalPages`, `first`, `last`, `numberOfElements` and `empty` keys are stable; the exact rendering of `sort` and `pageable` is an implementation detail of whichever mapper is active. Bind your client to the eight stable keys, not to `sort`.

---

## The `ProjectResponse` object

Produced by `ProjectService.toResponse(Project)`. This is the complete field list — nothing else is returned.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | number (int64) | no | Primary key |
| `coverUrl` | string | yes | S3 URL of the card cover asset. Max 1024 chars in the DB |
| `coverMediaType` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO`. Defaults to `IMAGE` when the row stored null — tells you which element to render |
| `coverThumbnailUrl` | string | yes | Poster for a `VIDEO` cover, cover art for an `AUDIO` cover. Ignored for `IMAGE` |
| `featureImageUrl` | string | yes | Wide hero picture used by the homepage carousel. Written only by the admin feature toggle. Fall back to `coverUrl` when null |
| `mediaGallery` | array of `MediaItem` | no (may be `[]`) | Mixed image/video/audio gallery shown beside the cover, pre-sorted ascending by `sortOrder` |
| `projectTypeCkb` | string | yes | Free-text Sorani project type, e.g. `"پاراستنی کەلەپوور"`. Max 128 chars |
| `projectTypeKmr` | string | yes | Free-text Kurmanji project type. Max 128 chars |
| `status` | enum `ProjectStatus` | no | `ACTIVE` \| `ONGOING` \| `COMPLETED` \| `ARCHIVED` |
| `projectDate` | string `yyyy-MM-dd` | yes | The date the project is filed under (not a created/updated timestamp) |
| `contentLanguages` | array of enum `Language` | no (may be `[]`) | Which of `CKB` / `KMR` this project actually has content for |
| `ckbContent` | object | yes | Present only when `contentLanguages` contains `CKB` |
| `kmrContent` | object | yes | Present only when `contentLanguages` contains `KMR` |
| `tagsCkb` | array of string | no (may be `[]`) | Sorani-side tag names |
| `tagsKmr` | array of string | no (may be `[]`) | Kurmanji-side tag names |
| `keywordsCkb` | array of string | no (may be `[]`) | Sorani-side keyword names |
| `keywordsKmr` | array of string | no (may be `[]`) | Kurmanji-side keyword names |
| `createdAt` | string, ISO-8601 instant | yes | Row creation time |
| `updatedAt` | string, ISO-8601 instant | **always absent/null** | Declared on the DTO but never populated — see the note below |
| `createdBy` | string | **always absent/null** | Declared on the DTO but never populated |
| `updatedBy` | string | **always absent/null** | Declared on the DTO but never populated |

`ckbContent` / `kmrContent` (`ProjectResponse.ProjectContentBlockDto`):

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `title` | string | no in practice | Project title in that language. Max 255 chars |
| `description` | string | yes | Tiptap HTML. Inline `<img>` / `<video>` / `<audio>` / `<a href>` assets already point at S3 URLs |
| `location` | string | yes | Human-readable place, e.g. `"هەولێر، هەرێمی کوردستان"`. Max 255 chars |

`mediaGallery[]` (`MediaItem`):

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `url` | string | no | S3 URL of the asset |
| `kind` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO`; defaults to `IMAGE` |
| `thumbnailUrl` | string | yes | Poster (`VIDEO`) or cover art (`AUDIO`) |
| `captionCkb` | string | yes | Sorani caption |
| `captionKmr` | string | yes | Kurmanji caption |
| `sortOrder` | number (int32) | no | Ascending display order; the server backfills it from the array index when it was omitted at write time |

> **Note:** `ProjectService.toResponse()` sets `createdAt` and stops there — `updatedAt`, `createdBy` and `updatedBy` are never assigned even though the `Project` entity extends `AuditableEntity` and stores all four columns. Treat those three response fields as permanently unavailable on the public API; do not build "last updated" UI on them.

> **Note:** `description` is raw HTML that an admin authored in Tiptap. It is stored verbatim (the server only rewrites inline `data:` URIs into S3 URLs on write) and is **not** sanitised on read. Render it through your own sanitiser.

### Full example object

```json
{
  "id": 42,
  "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
  "coverMediaType": "IMAGE",
  "coverThumbnailUrl": null,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6e0d19-77af-4c31-9a02-4d8e1c5f6b90-hewler-citadel-wide.jpg",
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
    "description": "<h2>دەربارەی پرۆژەکە</h2><p>ئەم پرۆژەیە بە هاوکاری شارەوانی هەولێر کاردەکات لەسەر ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵاکە.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c1d9e33-5a80-4f27-bb43-1e9d0c4a7f68-wall-survey.jpg\" alt=\"پێوانەی دیوار\">",
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
```

---

## 1. `GET /api/v1/projects/getAll` — Paged list of all projects

Returns every project, newest first. There is no visibility filter: a project with `status = ARCHIVED` is returned exactly like an `ACTIVE` one. Filter on `status` client-side if you need to hide archived work.

Ordering is `ORDER BY p.id DESC` — insertion order reversed, **not** `projectDate` and not `createdAt`.

**Auth:** None (public)
**Content-Type:** `application/json`
**Envelope message:** `"Projects fetched successfully"`
**Cached:** yes — Redis key `khi:projects::all:p{page}:s{size}`, 10-minute TTL

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | None |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | no | `0` | Zero-based page index. Must be `>= 0` |
| `size` | int | no | `20` | Rows per page. Must be `>= 1`. **Not** clamped at the top end — `size=5000` is accepted and will try to hydrate 5000 rows |

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Projects fetched successfully",
  "data": {
    "content": [
      {
        "id": 42,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": null,
        "mediaGallery": [],
        "projectTypeCkb": "پاراستنی کەلەپوور",
        "projectTypeKmr": "Parastina Mîrasê",
        "status": "ONGOING",
        "projectDate": "2025-04-12",
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": {
          "title": "نۆژەنکردنەوەی قەڵای هەولێر",
          "description": "<p>ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵای هەولێر.</p>",
          "location": "هەولێر، هەرێمی کوردستان"
        },
        "kmrContent": {
          "title": "Nûvekirina Keleha Hewlêrê",
          "description": "<p>Nûvekirina dîwarên rojavayî yên Keleha Hewlêrê.</p>",
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
      },
      {
        "id": 41,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1a4f7b90-2c63-4d85-9e07-3b8c5a1d2f44-slemani-archive.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": null,
        "mediaGallery": [],
        "projectTypeCkb": "ئەرشیفکردن",
        "projectTypeKmr": "Arşîvkirin",
        "status": "COMPLETED",
        "projectDate": "2024-11-03",
        "contentLanguages": ["CKB"],
        "ckbContent": {
          "title": "ئەرشیفی دەنگی سلێمانی",
          "description": "<p>کۆکردنەوە و دیجیتاڵکردنی تۆمارە دەنگییە کۆنەکانی سلێمانی.</p>",
          "location": "سلێمانی، هەرێمی کوردستان"
        },
        "kmrContent": null,
        "tagsCkb": ["ئەرشیف"],
        "tagsKmr": [],
        "keywordsCkb": ["تۆماری دەنگی"],
        "keywordsKmr": [],
        "createdAt": "2026-02-02T11:40:05Z",
        "updatedAt": null,
        "createdBy": null,
        "updatedBy": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 42,
    "totalPages": 3,
    "size": 20,
    "number": 0,
    "numberOfElements": 2,
    "first": true,
    "last": false,
    "empty": false
  }
}
```

An empty database still answers `200` with `"content": []`, `"totalElements": 0`, `"empty": true`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `size` is `0` or negative, or `page` is negative — `PageRequest.of` throws `IllegalArgumentException` (`"Page size must not be less than one"` / `"Page index must not be less than zero"`), surfaced verbatim in `details.reason` |
| `400` | `BAD_REQUEST` | `page` or `size` is not an integer |
| `500` | `INTERNAL_ERROR` | Redis is unreachable — caching is on the request path for this endpoint |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/projects/getAll?page=0&size=12"
```

---

## 2. `GET /api/v1/projects/featured` — Projects flagged for the homepage carousel

Returns only projects whose `featured` column is `true`. The flag itself is not part of `ProjectResponse` — membership in this list *is* the signal. Sorted by `featuredOrder` ascending, then `id` descending, so a project with no explicit order sorts by whatever PostgreSQL does with nulls in that column and generally lands after the explicitly ordered ones.

Render each slide with `featureImageUrl` when present, falling back to `coverUrl`.

**Auth:** None (public)
**Content-Type:** `application/json`
**Envelope message:** `"Featured projects fetched successfully"`
**Cached:** no — this endpoint reads straight from PostgreSQL on every call

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | no | `0` | Zero-based page index. Negative values are clamped to `0` |
| `size` | int | no | `20` | Rows per page. Clamped into `1..100` (`Math.min(Math.max(size, 1), 100)`) — unlike `/getAll`, bad values never produce a 400 here |

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Featured projects fetched successfully",
  "data": {
    "content": [
      {
        "id": 42,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6e0d19-77af-4c31-9a02-4d8e1c5f6b90-hewler-citadel-wide.jpg",
        "mediaGallery": [],
        "projectTypeCkb": "پاراستنی کەلەپوور",
        "projectTypeKmr": "Parastina Mîrasê",
        "status": "ONGOING",
        "projectDate": "2025-04-12",
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": {
          "title": "نۆژەنکردنەوەی قەڵای هەولێر",
          "description": "<p>ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵای هەولێر.</p>",
          "location": "هەولێر، هەرێمی کوردستان"
        },
        "kmrContent": {
          "title": "Nûvekirina Keleha Hewlêrê",
          "description": "<p>Nûvekirina dîwarên rojavayî yên Keleha Hewlêrê.</p>",
          "location": "Hewlêr, Herêma Kurdistanê"
        },
        "tagsCkb": ["کەلەپوور"],
        "tagsKmr": ["mîras"],
        "keywordsCkb": ["قەڵای هەولێر"],
        "keywordsKmr": ["Keleha Hewlêrê"],
        "createdAt": "2026-03-14T09:14:22Z",
        "updatedAt": null,
        "createdBy": null,
        "updatedBy": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 1,
    "totalPages": 1,
    "size": 20,
    "number": 0,
    "numberOfElements": 1,
    "first": true,
    "last": true,
    "empty": false
  }
}
```

> **Note:** `/api/v1/projects/featured` is projects-only. The homepage carousel that mixes news, projects, writings, videos, sound tracks, image collections and the donation band is served by the separate legacy alias `GET /featured` (also `permitAll`), which is capped at the admin-configured `maxFeaturedSlides` (default `7`). Use `/featured` for the carousel and this endpoint only when you need a projects-scoped list.

> **Note:** the literal `featured` segment and the `{id}` route in item 3 share the same prefix. Spring resolves the literal path first, so `/api/v1/projects/featured` never falls through to `/api/v1/projects/{id}` and never produces a "cannot convert `featured` to Long" error. This is covered by `PublicApiContractIntegrationTests`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page` or `size` is not an integer |

`page` and `size` are clamped, so out-of-range numbers do not error.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/projects/featured?page=0&size=6"
```

---

## 3. `GET /api/v1/projects/{id}` — One project by id

Loads a single project with its tags, keywords and content languages fetched in one query (`@EntityGraph`), so no lazy-loading round trips.

**Auth:** None (public)
**Content-Type:** `application/json`
**Envelope message:** `"Project fetched successfully"`
**Cached:** no

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (int64) | yes | Project primary key. Must parse as a `Long` |

**Query parameters**

None.

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Project fetched successfully",
  "data": {
    "id": 42,
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
    "coverMediaType": "IMAGE",
    "coverThumbnailUrl": null,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6e0d19-77af-4c31-9a02-4d8e1c5f6b90-hewler-citadel-wide.jpg",
    "mediaGallery": [
      {
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/5c8a1f22-91d4-4b7e-a3c6-77e2b0d4419a-workshop-day-1.jpg",
        "kind": "IMAGE",
        "thumbnailUrl": null,
        "captionCkb": "یەکەم ڕۆژی ۆرکشۆپ",
        "captionKmr": "Roja yekem a atolyeyê",
        "sortOrder": 0
      }
    ],
    "projectTypeCkb": "پاراستنی کەلەپوور",
    "projectTypeKmr": "Parastina Mîrasê",
    "status": "ONGOING",
    "projectDate": "2025-04-12",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "نۆژەنکردنەوەی قەڵای هەولێر",
      "description": "<h2>دەربارەی پرۆژەکە</h2><p>ئەم پرۆژەیە بە هاوکاری شارەوانی هەولێر کاردەکات.</p>",
      "location": "هەولێر، هەرێمی کوردستان"
    },
    "kmrContent": {
      "title": "Nûvekirina Keleha Hewlêrê",
      "description": "<h2>Der barê projeyê de</h2><p>Ev proje bi hevkariya Şaredariya Hewlêrê dixebite.</p>",
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

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `PROJECT_NOT_FOUND` | No row with that id. `details.projectId` echoes the id you asked for |
| `400` | `BAD_REQUEST` | `{id}` is not a number, e.g. `/api/v1/projects/abc` |

`404` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/projects/999999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "PROJECT_NOT_FOUND",
  "message": "Project not found",
  "messageEn": "Project not found",
  "messageKu": "پرۆژە نەدۆزرایەوە",
  "fieldErrors": null,
  "details": {
    "projectId": "999999"
  }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/projects/42
```

---

## 4. `GET /api/v1/projects/search/tag` — Search by tag

Case-insensitive **substring** match against tag names, checked against the Sorani link table and the Kurmanji link table at once. A project matches if either side has a tag whose name contains the query. `mîras` matches a tag named `Mîrasa Çandî`; `کەلە` matches `کەلەپوور`.

The query string is `trim()`-ed before matching. Results are distinct project ids ordered `id DESC`.

**Auth:** None (public)
**Content-Type:** `application/json`
**Envelope message:** `"Search by tag completed"`
**Cached:** yes — Redis key `khi:projects::tag:{lowercased tag}:p{page}:s{size}`, 10-minute TTL

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `tag` | string | **yes** | — | Substring to look for in tag names. Matched case-insensitively against both `tagsCkb` and `tagsKmr` |
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `20` | Rows per page, `>= 1`, not clamped at the top end |

**Request body**

None.

**Response `200 OK`**

Same page shape as `/getAll`, with `"message": "Search by tag completed"`.

```json
{
  "success": true,
  "message": "Search by tag completed",
  "data": {
    "content": [
      {
        "id": 42,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f1c7a41-3e2b-4d6a-8c55-1b0d9e77aa21-hewler-citadel.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": null,
        "mediaGallery": [],
        "projectTypeCkb": "پاراستنی کەلەپوور",
        "projectTypeKmr": "Parastina Mîrasê",
        "status": "ONGOING",
        "projectDate": "2025-04-12",
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": {
          "title": "نۆژەنکردنەوەی قەڵای هەولێر",
          "description": "<p>ڕێکخستنەوەی دیوارە خۆرئاواییەکانی قەڵای هەولێر.</p>",
          "location": "هەولێر، هەرێمی کوردستان"
        },
        "kmrContent": {
          "title": "Nûvekirina Keleha Hewlêrê",
          "description": "<p>Nûvekirina dîwarên rojavayî yên Keleha Hewlêrê.</p>",
          "location": "Hewlêr, Herêma Kurdistanê"
        },
        "tagsCkb": ["کەلەپوور", "نۆژەنکردنەوە"],
        "tagsKmr": ["mîras", "nûvekirin"],
        "keywordsCkb": ["قەڵای هەولێر"],
        "keywordsKmr": ["Keleha Hewlêrê"],
        "createdAt": "2026-03-14T09:14:22Z",
        "updatedAt": null,
        "createdBy": null,
        "updatedBy": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 1,
    "totalPages": 1,
    "size": 20,
    "number": 0,
    "numberOfElements": 1,
    "first": true,
    "last": true,
    "empty": false
  }
}
```

No match is a `200` with an empty `content` array, not a `404`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `tag` was omitted entirely. `details.missingParameter` is `"tag"` |
| `400` | `BAD_REQUEST` | `tag` was supplied but blank (`?tag=` or only whitespace). Message key `tag.required` |
| `400` | `BAD_REQUEST` | `size` is `0` or negative, or `page` is negative |
| `500` | `INTERNAL_ERROR` | Redis unreachable |

Blank-`tag` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/projects/search/tag",
  "method": "GET",
  "traceId": "5a2f8c31-7b90-4e12-a3d6-9c4e0b1f7d28",
  "code": "BAD_REQUEST",
  "message": "Bad request",
  "messageEn": "Bad request",
  "messageKu": "داواکاری هەڵەیە",
  "fieldErrors": null,
  "details": {}
}
```

> **Note:** the codebase ships `ProjectSearchValidationException.tagRequired()` / `.keywordRequired()`, which would have produced `code: PROJECT_VALIDATION` and a helpful `details.hint`. `ProjectService` does not use them — it throws a plain `BadRequestException("tag.required", Map.of())` instead, so the real response carries `code: BAD_REQUEST` and an empty `details` object. Match on `BAD_REQUEST`, not on `PROJECT_VALIDATION`.

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/projects/search/tag \
  --data-urlencode "tag=mîras" \
  --data-urlencode "page=0" \
  --data-urlencode "size=20"
```

---

## 5. `GET /api/v1/projects/search/keyword` — Search by keyword

Identical mechanics to the tag search, against the keyword taxonomy instead. Case-insensitive substring match over the Sorani and Kurmanji keyword link tables, `trim()`-ed input, distinct ids ordered `id DESC`.

Tags and keywords are two separate tables (`project_tags`, `project_keywords`) with independent name uniqueness. A term in one is not visible to the other endpoint.

**Auth:** None (public)
**Content-Type:** `application/json`
**Envelope message:** `"Search by keyword completed"`
**Cached:** yes — Redis key `khi:projects::kw:{lowercased keyword}:p{page}:s{size}`, 10-minute TTL

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `keyword` | string | **yes** | — | Substring to look for in keyword names, matched against both `keywordsCkb` and `keywordsKmr` |
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `20` | Rows per page, `>= 1`, not clamped at the top end |

**Request body**

None.

**Response `200 OK`**

Same page shape as `/getAll`, with `"message": "Search by keyword completed"`.

```json
{
  "success": true,
  "message": "Search by keyword completed",
  "data": {
    "content": [],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 0,
    "totalPages": 0,
    "size": 20,
    "number": 0,
    "numberOfElements": 0,
    "first": true,
    "last": true,
    "empty": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `keyword` was omitted. `details.missingParameter` is `"keyword"` |
| `400` | `BAD_REQUEST` | `keyword` supplied but blank. Message key `keyword.required` |
| `400` | `BAD_REQUEST` | `size` is `0` or negative, or `page` is negative |
| `500` | `INTERNAL_ERROR` | Redis unreachable |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/projects/search/keyword \
  --data-urlencode "keyword=UNESCO"
```

---

## Errors

Errors never use the `ApiResponse` envelope. Every failure is serialised as `ApiErrorResponse`:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/projects/999999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "PROJECT_NOT_FOUND",
  "message": "Project not found",
  "messageEn": "Project not found",
  "messageKu": "پرۆژە نەدۆزرایەوە",
  "fieldErrors": null,
  "details": {
    "projectId": "999999"
  }
}
```

| Field | Meaning |
|-------|---------|
| `code` | The `ErrorCode` enum value. This is the stable thing to branch on |
| `message` | Localised per the request's `Accept-Language` |
| `messageEn` / `messageKu` | Always both present, regardless of `Accept-Language` |
| `traceId` | Correlates with the server log line; quote it in bug reports |
| `fieldErrors` | Populated only for bean-validation failures. Always `null` on the read endpoints |
| `details` | Safe extra context; shape varies by error |

### Error codes reachable from this page

| `code` | Status | Raised by |
|--------|--------|-----------|
| `PROJECT_NOT_FOUND` | 404 | `GET /{id}` with an unknown id |
| `MISSING_PARAMETER` | 400 | `tag` / `keyword` omitted on the search endpoints |
| `BAD_REQUEST` | 400 | Blank `tag` / `keyword`; bad `page` / `size`; non-numeric `{id}` |
| `NOT_FOUND` | 404 | Path is not mapped at all (typo in the URL) |
| `METHOD_NOT_ALLOWED` | 405 | Wrong verb, e.g. `POST /api/v1/projects/getAll` |
| `INTERNAL_ERROR` | 500 | Unexpected server fault, including an unreachable Redis |

### Localisation of error text

Send `Accept-Language: ckb` for Sorani or `Accept-Language: kmr` for Kurmanji; anything else resolves to English. `messageEn` and `messageKu` are always populated in addition, so a bilingual UI can render both without a second request.

> **Note:** `I18nConfig` registers a `LocaleChangeInterceptor` on the `lang` query parameter (`?lang=ckb`) on top of an `AcceptHeaderLocaleResolver`. `AcceptHeaderLocaleResolver.setLocale()` throws `UnsupportedOperationException` by contract, so appending `?lang=` to **any** request on this API produces a `500 INTERNAL_ERROR` rather than switching language. Use the `Accept-Language` header; do not use `?lang=`.

> **Note:** the English bundle file is named `src/main/resources/i18n/ messages_en.properties` — with a leading space — while `I18nConfig` looks for basename `classpath:i18n/messages`. The English properties therefore never load and English text comes from the hard-coded fallbacks inside `GlobalExceptionHandler`. The Sorani and Kurmanji bundles load correctly. This only affects wording, not `code` or `status`.

---

## Enums used by this API

### `ProjectStatus`

`ak.dev.khi_backend.khi_app.enums.project.ProjectStatus`

| Value | Meaning |
|-------|---------|
| `ACTIVE` | Project is live and being promoted |
| `ONGOING` | Work is in progress (`بەردەوام`). This is the server-side default when a project is saved without a status |
| `COMPLETED` | Work is finished (`تەواو`) |
| `ARCHIVED` | Retired. **Still returned by every endpoint on this page** — filter client-side if you want it hidden |

### `MediaKind`

`ak.dev.khi_backend.khi_app.enums.MediaKind` — used by `coverMediaType` and by `mediaGallery[].kind`.

| Value | Meaning |
|-------|---------|
| `IMAGE` | Render with `<img>`. `thumbnailUrl` is ignored |
| `VIDEO` | Render with `<video>`; use `thumbnailUrl` / `coverThumbnailUrl` as the poster |
| `AUDIO` | Render with `<audio>`; use `thumbnailUrl` / `coverThumbnailUrl` as cover art |

### `Language`

`ak.dev.khi_backend.khi_app.enums.Language` — the values inside `contentLanguages`.

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish / Sorani. Pairs with `ckbContent`, `projectTypeCkb`, `tagsCkb`, `keywordsCkb` |
| `KMR` | Northern Kurdish / Kurmanji. Pairs with `kmrContent`, `projectTypeKmr`, `tagsKmr`, `keywordsKmr` |

Deserialisation is case-insensitive and whitespace-tolerant (`@JsonCreator Language.from`), but serialisation is always the upper-case name.

---

## Notes & gotchas

**A missing language block means missing content, not an empty string.** When `contentLanguages` is `["CKB"]`, `kmrContent` is `null` and `projectTypeKmr` / `tagsKmr` / `keywordsKmr` are `null` or `[]`. Always branch on `contentLanguages` before reaching into a block, and have a fallback language in the UI.

**`projectDate` is editorial, not technical.** It is a plain `LocalDate` the admin picked and can sit in the past or the future. Never sort a public list by it and expect it to match the API's own ordering, which is always `id DESC`.

**Timestamps.** `createdAt` comes back as an ISO-8601 instant. The database stores timestamps in UTC (`hibernate.jdbc.time_zone=UTC`) and `application.yaml` declares a Jackson time zone of `Asia/Baghdad`; because `JacksonConfig` installs its own `ObjectMapper`, do not assume the offset — parse the value as an instant and format it in the viewer's zone yourself.

**Caching.** `/getAll`, `/search/tag` and `/search/keyword` are `@Cacheable` under the Redis cache named `projects` (key prefix `khi:`, TTL 10 minutes). `/featured` and `/{id}` are not cached. Admin create/update/delete evict the whole `projects` cache immediately, so new and edited projects appear at once — but the admin **feature toggle does not evict it**, so a change to `featureImageUrl` can take up to 10 minutes to show up in `/getAll` and in the search results. `/featured` itself is always fresh.

**Redis is on the request path.** With `CacheConfig` active, an unreachable Redis turns the three cached endpoints into `500`s rather than degrading to a database read.

**Tag and keyword names are shared rows.** `project_tags.name` and `project_keywords.name` are globally unique (case-insensitive on write). The same name used on both the CKB and KMR side of a project points at one row, so it will appear in both `tagsCkb` and `tagsKmr`. Do not treat the two arrays as disjoint sets.

**Search is substring, not exact and not tokenised.** `?tag=ar` matches `Arşîv`, `Parastin` and `Arkeolojî`. There is no relevance ranking — results are ordered `id DESC`. Do not build an autocomplete on top of it without debouncing.

**No `slug` field exists on projects.** Deep links must use the numeric id.

**`mediaGallery` is a JSONB column**, not a join table. It is returned exactly as stored, pre-sorted by `sortOrder`; entries with a blank `url` were dropped at write time, so every entry you receive has a usable URL.

**Read-side N+1 is mitigated, not eliminated.** The list endpoints run an id-only query, then hydrate in one `IN (:ids)` batch and lazy-load the tag/keyword/language collections under `@BatchSize(50)`. Large `size` values multiply those batches; keep page sizes reasonable.

**Live spec.** Swagger UI is at `/swagger-ui.html`, raw JSON at `/v3/api-docs`. The `public` group already matches `/api/v1/projects/**` — note that this group is a path filter, not an auth filter, so it also lists the admin write endpoints.

**Servers.** `http://localhost:8080` locally; production runs on Railway.

---

## Related documentation

- Counterpart (admin writes): [`../internal/PROJECT_API.md`](../internal/PROJECT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
