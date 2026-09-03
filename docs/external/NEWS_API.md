# News API — External (Public)

The News domain holds bilingual (Sorani / Kurmanji) news articles for the KHI public website. Each
article carries a cover asset, an optional mixed-media gallery, a two-level category taxonomy, per
language tags and keywords, and a Tiptap-authored HTML body per language. Every endpoint on this
page is reachable by an anonymous visitor with no token — they back the public news index, the
homepage carousel, article detail pages, and the site search box.

| | |
|---|---|
| **Base path** | `/api/v1/news` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/news/NewsController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/news/NewsService.java` |
| **DTO** | `src/main/java/ak/dev/khi_backend/khi_app/dto/news/NewsDto.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/news/NewsRepository.java` |
| **Entities** | `News`, `NewsContent`, `NewsCategory`, `NewsSubCategory`, `NewsAuditLog`, `MediaItem` |
| **Verified against source** | 2026-08-26 |

Why these are public: `SecurityConfig` matches `GET /api/v1/**` with `permitAll()` before any of the
role-gated content rules. Every handler on this page is a `GET`, so none of them require a token.
Writes on the same controller (`POST`, `PUT`, `PATCH`, `DELETE`) are role-gated and documented in
[`../internal/NEWS_API.md`](../internal/NEWS_API.md).

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/news` (aliases `/api/v1/news/` and `/api/v1/news/all`) | None | — | Paginated list of every article, newest first |
| 2 | `GET` | `/api/v1/news/featured` | None | — | Paginated list of articles flagged for the homepage carousel |
| 3 | `GET` | `/api/v1/news/{id}` | None | — | One article by numeric id |
| 4 | `GET` | `/api/v1/news/search` | None | — | Global free-text search across titles, bodies, tags, keywords |
| 5 | `GET` | `/api/v1/news/search/keyword` | None | — | Search the keyword sets only, optionally one language |
| 6 | `GET` | `/api/v1/news/search/tag` | None | — | Search the tag sets only, optionally one language |
| 7 | `GET` | `/api/v1/news/search/category` | None | — | Filter by category name (either language) |
| 8 | `GET` | `/api/v1/news/search/subcategory` | None | — | Filter by subcategory name (either language) |

---

## Shared response shapes

### The `ApiResponse<T>` envelope

Every endpoint on this page returns the `ApiResponse<T>` envelope:

```json
{
  "success": true,
  "message": "News fetched successfully",
  "data": {}
}
```

`ApiResponse` is annotated `@JsonInclude(NON_NULL)`, so a null `data` is dropped from the payload.
The `NewsDto` inside `data` is **not** annotated that way — fields with no value are serialised as
explicit `null` (see [Notes & gotchas](#notes--gotchas)).

### The page envelope

Seven of the eight endpoints put a Spring Data `Page<NewsDto>` in `data`. It is serialised with
Spring Data's default `PageImpl` shape:

```json
{
  "content": [],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 57,
  "totalPages": 3,
  "last": false,
  "first": true,
  "size": 20,
  "number": 0,
  "sort": { "empty": true, "sorted": false, "unsorted": true },
  "numberOfElements": 20,
  "empty": false
}
```

`sort` reports `sorted: false` on every endpoint except `/featured`, because the list and search
queries carry their `ORDER BY` inside JPQL rather than in the `Pageable`. `/featured` builds a real
`Sort` (`featuredOrder` ascending, then `id` descending) and therefore reports `sorted: true`.

### The `NewsDto` object

This is the single article shape returned everywhere in this domain — as `data` on
`GET /{id}` and as each element of `content` on the paginated endpoints.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key |
| `coverUrl` | string | no | S3 URL of the card cover asset. Always populated — creation rejects a blank cover |
| `coverMediaType` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO`. Tells the frontend whether to render `<img>`, `<video>` or `<audio>`. Never null on read — the mapper substitutes `IMAGE` |
| `coverThumbnailUrl` | string | yes | Poster frame for a `VIDEO` cover, cover art for an `AUDIO` cover. Ignored when `coverMediaType` is `IMAGE` |
| `featureImageUrl` | string | yes | Wide hero picture for the homepage carousel. Written only by the admin `PATCH /{id}/featured` call; falls back to `coverUrl` when null |
| `mediaGallery` | array of `MediaItem` | no (may be `[]`) | Mixed image/video/audio gallery rendered beside the cover, already sorted by `sortOrder` ascending |
| `datePublished` | string `yyyy-MM-dd` | no | Editorial publication date. Defaults to the creation date when the writer omits it |
| `createdAt` | string ISO-8601 local date-time | no | Row insert time, e.g. `2026-08-24T11:42:07.512345` |
| `updatedAt` | string ISO-8601 local date-time | no | Last write time |
| `contentLanguages` | array of enum `Language` | no | Which language blocks exist: any of `CKB`, `KMR` |
| `category` | object | yes in theory, always present in practice | `{ "ckbName": …, "kmrName": … }`; the column is `NOT NULL` |
| `subCategory` | object | yes in theory, always present in practice | `{ "ckbName": …, "kmrName": … }`; the column is `NOT NULL` |
| `ckbContent` | object | yes | `{ "title": …, "description": … }` — present only when `contentLanguages` contains `CKB` |
| `kmrContent` | object | yes | Same shape — present only when `contentLanguages` contains `KMR` |
| `tags` | object | no | `{ "ckb": [ … ], "kmr": [ … ] }` — always emitted, either side may be `[]` |
| `keywords` | object | no | Same shape as `tags` |

`MediaItem` (element of `mediaGallery`):

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `url` | string | no | S3 URL of the asset |
| `kind` | enum `MediaKind` | no | `IMAGE` \| `VIDEO` \| `AUDIO` |
| `thumbnailUrl` | string | yes | Poster (`VIDEO`) or cover art (`AUDIO`) |
| `captionCkb` | string | yes | Sorani caption |
| `captionKmr` | string | yes | Kurmanji caption |
| `sortOrder` | integer | no | Display order, ascending |

`ckbContent.description` and `kmrContent.description` are **Tiptap HTML**, not plain text or
Markdown. See [Tiptap HTML bodies](#tiptap-html-bodies).

---

## 1. `GET /api/v1/news` — List every article

Returns one page of articles ordered by `datePublished` descending, then `createdAt` descending
(newest first). This is the endpoint behind the public `/news` index.

> **Note:** three paths map to this one handler — `GET /api/v1/news`, `GET /api/v1/news/`
> and `GET /api/v1/news/all`. They are the same `@GetMapping(value = {"", "/", "/all"})`
> method with identical behaviour, parameters and response. Pick one and stay with it;
> `/api/v1/news` is the canonical form. This alias set is documented once here and not
> repeated below.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | integer | no | `0` | Zero-based page index |
| `size` | integer | no | `20` | Page size. Not clamped on this endpoint — a very large value produces a very large response |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "News fetched successfully",
  "data": {
    "content": [
      {
        "id": 128,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f2a1c60-7d34-49b2-b1c8-0e5d9a3f2b71-festival-hero-wide.jpg",
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
        "updatedAt": "2026-08-25T09:03:44.118220",
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
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "empty": true, "sorted": false, "unsorted": true },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 57,
    "totalPages": 3,
    "last": false,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 20,
    "empty": false
  }
}
```

An out-of-range `page` is not an error — it returns `content: []`, `empty: true` and the real
`totalElements`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer (e.g. `?page=first`) |
| 500 | `INTERNAL_ERROR` | `size` is `0` or negative — `PageRequest.of` rejects it and the catch-all handler answers 500 |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/news?page=0&size=12"
```

---

## 2. `GET /api/v1/news/featured` — Homepage carousel articles

Returns only articles whose `featured` flag is on, ordered by `featuredOrder` ascending and then
`id` descending, so an article with no explicit order sorts last. The flag and the order are set by
the admin dashboard through
[`PATCH /api/v1/news/{id}/featured`](../internal/NEWS_API.md#4-patch-apiv1newsidfeatured--flag-an-article-for-the-homepage-carousel).

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | integer | no | `0` | Zero-based page index. Negative values are clamped to `0` |
| `size` | integer | no | `20` | Page size. Clamped into the range `1`–`100` |

Unlike every other endpoint in this domain, `/featured` sanitises its paging arguments:
`page` is raised to `0` if negative and `size` is clamped to `[1, 100]`. `size=0` and `size=-5`
therefore behave as `size=1`, and `size=5000` behaves as `size=100`.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Featured news fetched successfully",
  "data": {
    "content": [
      {
        "id": 128,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f2a1c60-7d34-49b2-b1c8-0e5d9a3f2b71-festival-hero-wide.jpg",
        "mediaGallery": [],
        "datePublished": "2026-08-24",
        "createdAt": "2026-08-24T11:42:07.512345",
        "updatedAt": "2026-08-25T09:03:44.118220",
        "contentLanguages": ["CKB", "KMR"],
        "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
        "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
        "ckbContent": {
          "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
          "description": "<p>فێستیڤاڵی ساڵانەی مۆسیقای کوردی ئێوارەی دوێنێ لە قەڵای هەولێر دەستیپێکرد.</p>"
        },
        "kmrContent": {
          "title": "Festîvala muzîka kurdî li Hewlêrê dest pê kir",
          "description": "<p>Festîvala salane ya muzîka kurdî êvara duh li Kelehê Hewlêrê dest pê kir.</p>"
        },
        "tags": { "ckb": ["هەولێر"], "kmr": ["Hewlêr"] },
        "keywords": { "ckb": ["مۆسیقای کوردی"], "kmr": ["muzîka kurdî"] }
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "empty": false, "sorted": true, "unsorted": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 3,
    "totalPages": 1,
    "last": true,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": false, "sorted": true, "unsorted": false },
    "numberOfElements": 3,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/news/featured?page=0&size=7"
```

> **Note:** the total number of featured slides across *all* content types (news, projects,
> writings, videos, sound tracks, image collections, plus the donation band) is capped by the
> admin-configurable `maxFeaturedSlides` site setting, default `7`. This cap is enforced when
> an admin turns the flag on, not when the public site reads it — so this endpoint can legally
> return fewer than 7 items, and never more than the number of news articles currently flagged.

---

## 3. `GET /api/v1/news/{id}` — One article

Fetches a single article by primary key. The repository loads it with an `@EntityGraph` covering
content languages, all four tag/keyword collections, the category and the subcategory, so the whole
DTO comes back in one round trip.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Article primary key |

**Query parameters**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "News fetched successfully",
  "data": {
    "id": 128,
    "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
    "coverMediaType": "IMAGE",
    "coverThumbnailUrl": null,
    "featureImageUrl": null,
    "mediaGallery": [],
    "datePublished": "2026-08-24",
    "createdAt": "2026-08-24T11:42:07.512345",
    "updatedAt": "2026-08-25T09:03:44.118220",
    "contentLanguages": ["CKB"],
    "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
    "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
    "ckbContent": {
      "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
      "description": "<p>فێستیڤاڵی ساڵانەی مۆسیقای کوردی ئێوارەی دوێنێ لە قەڵای هەولێر دەستیپێکرد.</p>"
    },
    "kmrContent": null,
    "tags": { "ckb": ["هەولێر", "فێستیڤاڵ"], "kmr": [] },
    "keywords": { "ckb": ["مۆسیقای کوردی"], "kmr": [] }
  }
}
```

This example is a CKB-only article: `contentLanguages` holds a single value, `kmrContent` is `null`
and `tags.kmr` / `keywords.kmr` are empty arrays. Render whichever language block is present and
fall back to the other one rather than assuming both exist.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 404 | `NEWS_NOT_FOUND` | No article with that id. `details` carries `{ "id": "999" }` |
| 500 | `INTERNAL_ERROR` | `id` is not a number (e.g. `/api/v1/news/latest`) — see the note below |

Sample 404 body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/news/999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NEWS_NOT_FOUND",
  "message": "News not found.",
  "messageEn": "News not found.",
  "messageKu": "هەواڵ نەدۆزرایەوە",
  "fieldErrors": null,
  "details": { "id": "999" }
}
```

> **Note:** a non-numeric `{id}` does not produce a 400. Spring fails to bind the path variable to
> `Long`, and `GlobalExceptionHandler` has no handler for `MethodArgumentTypeMismatchException`, so
> the catch-all `Exception` handler answers `500 INTERNAL_ERROR`. Validate that the id is numeric
> on the client before calling.

**Example**

```bash
curl -s http://localhost:8080/api/v1/news/128
```

---

## The five search axes

The domain exposes five separate search endpoints rather than one endpoint with a mode switch.
They differ in *which columns* they match and *whether they can be restricted to one language*:

| Endpoint | Matches against | Language filter | Query param carrying the term |
|----------|-----------------|-----------------|-------------------------------|
| `/search` | CKB + KMR titles, CKB + KMR Tiptap bodies, all four tag/keyword sets | no | `keyword` or `q` |
| `/search/keyword` | `keywordsCkb` and/or `keywordsKmr` only | yes (`language`) | `keyword` |
| `/search/tag` | `tagsCkb` and/or `tagsKmr` only | yes (`language`) | `tag` |
| `/search/category` | `category.nameCkb` **or** `category.nameKmr` | no (always both) | `name` |
| `/search/subcategory` | `subCategory.nameCkb` **or** `subCategory.nameKmr` | no (always both) | `name` |

All five share the same semantics:

- **Substring, case-insensitive.** The JPQL is `lower(col) LIKE lower('%' || :term || '%')`. There
  is no prefix anchoring, no stemming, no relevance ranking.
- **Trimmed.** Leading and trailing whitespace on the term is stripped before matching.
- **Same ordering as the list endpoint** — `datePublished DESC, createdAt DESC`.
- **Same page envelope**, same `page` / `size` defaults (`0` / `20`), no clamping.
- **A blank term is rejected**, not treated as "match everything".
- `%` and `_` inside the term are **not escaped**, so they act as SQL `LIKE` wildcards. A user
  typing `100%` searches for "100" followed by anything.

Because `LIKE '%term%'` cannot use a B-tree index, these queries do a scan. Keep `size` modest and
rely on the Redis cache (below) for repeated terms.

---

## 4. `GET /api/v1/news/search` — Global free-text search

One search box that covers everything: both titles, both Tiptap bodies, all four tag and keyword
collections. This is the endpoint behind the site-wide news search field.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `keyword` | string | no | — | The search term. Takes precedence when both are sent |
| `q` | string | no | — | Alias for `keyword`, used when `keyword` is absent or blank |
| `page` | integer | no | `0` | Zero-based page index |
| `size` | integer | no | `20` | Page size |

Both term parameters are declared `required = false`, but the handler resolves them to
`keyword` if non-blank, else `q`, else the empty string — and the service rejects an empty term.
In practice **one of `keyword` or `q` must be supplied and non-blank**.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Global search completed",
  "data": {
    "content": [
      {
        "id": 128,
        "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b1f8c2e-4a17-4a55-9f0d-2c3b7e5a1d90-festival-hewler.jpg",
        "coverMediaType": "IMAGE",
        "coverThumbnailUrl": null,
        "featureImageUrl": null,
        "mediaGallery": [],
        "datePublished": "2026-08-24",
        "createdAt": "2026-08-24T11:42:07.512345",
        "updatedAt": "2026-08-25T09:03:44.118220",
        "contentLanguages": ["CKB", "KMR"],
        "category": { "ckbName": "کولتوور", "kmrName": "Çand" },
        "subCategory": { "ckbName": "مۆسیقا", "kmrName": "Muzîk" },
        "ckbContent": {
          "title": "فێستیڤاڵی مۆسیقای کوردی لە هەولێر دەستیپێکرد",
          "description": "<p>فێستیڤاڵی ساڵانەی مۆسیقای کوردی ئێوارەی دوێنێ لە قەڵای هەولێر دەستیپێکرد.</p>"
        },
        "kmrContent": {
          "title": "Festîvala muzîka kurdî li Hewlêrê dest pê kir",
          "description": "<p>Festîvala salane ya muzîka kurdî êvara duh li Kelehê Hewlêrê dest pê kir.</p>"
        },
        "tags": { "ckb": ["هەولێر"], "kmr": ["Hewlêr"] },
        "keywords": { "ckb": ["مۆسیقای کوردی"], "kmr": ["muzîka kurdî"] }
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
    "totalElements": 1,
    "totalPages": 1,
    "last": true,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 1,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | Neither `keyword` nor `q` supplied, or both blank. `details` carries `{ "message": "Search keyword is required" }` |
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer |

Sample 400 body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/news/search",
  "method": "GET",
  "traceId": "8c21e470-53b6-4f8d-9a1c-6d0e4b2f7a19",
  "code": "BAD_REQUEST",
  "message": "Bad request",
  "messageEn": "Bad request",
  "messageKu": "داواکاری هەڵەیە",
  "fieldErrors": null,
  "details": { "message": "Search keyword is required" }
}
```

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/news/search \
  --data-urlencode "q=هەولێر" \
  --data-urlencode "page=0" \
  --data-urlencode "size=10"
```

---

## 5. `GET /api/v1/news/search/keyword` — Search the keyword sets

Matches only the SEO keyword collections (`news_keywords_ckb`, `news_keywords_kmr`). Titles, bodies
and tags are not consulted.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `keyword` | string | **yes** | — | Search term. Must be present in the URL |
| `language` | string | no | `both` | `ckb` searches `keywordsCkb` only, `kmr` searches `keywordsKmr` only. Any other value — including the default `both` — searches both sets. Matched case-insensitively |
| `page` | integer | no | `0` | Zero-based page index |
| `size` | integer | no | `20` | Page size |

The `language` comparison is `"ckb".equalsIgnoreCase(language)` / `"kmr".equalsIgnoreCase(language)`.
It is a plain string, not the `Language` enum, so `CKB`, `Ckb` and `ckb` all work, while an
unrecognised value such as `en` silently falls through to the both-languages query instead of
erroring.

**Response `200 OK`**

Same page envelope as [`/search`](#4-get-apiv1newssearch--global-free-text-search); only the
envelope `message` differs:

```json
{
  "success": true,
  "message": "Search by keyword completed",
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
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 0,
    "empty": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `keyword` is absent from the URL. `details` carries `missingParameter`, `expectedType`, `hint` |
| 400 | `BAD_REQUEST` | `keyword` is present but blank or whitespace (`?keyword=`). `details` carries `{ "message": "Search keyword is required" }` |
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/news/search/keyword \
  --data-urlencode "keyword=muzîka kurdî" \
  --data-urlencode "language=kmr"
```

---

## 6. `GET /api/v1/news/search/tag` — Search the tag sets

Matches only the editorial tag collections (`news_tags_ckb`, `news_tags_kmr`). This is the endpoint
behind clicking a tag chip on an article page.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `tag` | string | **yes** | — | Search term. Must be present in the URL |
| `language` | string | no | `both` | `ckb` searches `tagsCkb` only, `kmr` searches `tagsKmr` only, anything else searches both |
| `page` | integer | no | `0` | Zero-based page index |
| `size` | integer | no | `20` | Page size |

**Response `200 OK`**

Same page envelope; envelope `message` is `"Search by tag completed"`.

```json
{
  "success": true,
  "message": "Search by tag completed",
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
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 0,
    "empty": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `tag` is absent from the URL |
| 400 | `BAD_REQUEST` | `tag` is present but blank. `details` carries `{ "message": "Search tag is required" }` |
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/news/search/tag \
  --data-urlencode "tag=هەولێر" \
  --data-urlencode "language=ckb"
```

---

## 7. `GET /api/v1/news/search/category` — Filter by category

Returns every article whose category name contains the given term in **either** language. There is
no `language` parameter here — the JPQL matches `nameCkb OR nameKmr` unconditionally, so
`?name=Çand` and `?name=کولتوور` both return the Culture articles.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `name` | string | **yes** | — | Category name or fragment of it, in CKB or KMR |
| `page` | integer | no | `0` | Zero-based page index |
| `size` | integer | no | `20` | Page size |

**Response `200 OK`**

Same page envelope; envelope `message` is `"Search by category completed"`.

```json
{
  "success": true,
  "message": "Search by category completed",
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
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 0,
    "empty": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `name` is absent from the URL |
| 400 | `BAD_REQUEST` | `name` is present but blank. `details` carries `{ "field": "category" }` |
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/news/search/category \
  --data-urlencode "name=Çand"
```

---

## 8. `GET /api/v1/news/search/subcategory` — Filter by subcategory

Same as the category filter, one level down. Matches `subCategory.nameCkb OR subCategory.nameKmr`.

> **Note:** subcategory names are unique only *within* a category (`UNIQUE (category_id, name_ckb)`).
> Two different categories may each own a subcategory called `مۆسیقا` / `Muzîk`, and this endpoint
> matches on the name alone, so the result can span several categories. Read `category` on each
> returned article if you need to disambiguate.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `name` | string | **yes** | — | Subcategory name or fragment of it, in CKB or KMR |
| `page` | integer | no | `0` | Zero-based page index |
| `size` | integer | no | `20` | Page size |

**Response `200 OK`**

Same page envelope; envelope `message` is `"Search by subcategory completed"`.

```json
{
  "success": true,
  "message": "Search by subcategory completed",
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
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 0,
    "empty": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `name` is absent from the URL |
| 400 | `BAD_REQUEST` | `name` is present but blank. `details` carries `{ "field": "subCategory" }` |
| 400 | `BAD_REQUEST` | `page` or `size` is not an integer |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/news/search/subcategory \
  --data-urlencode "name=Muzîk"
```

---

## The category / subcategory taxonomy

News uses a strict two-level taxonomy. Both levels are dedicated entities, not free strings on the
article row, and both are **bilingual by construction**:

```
NewsCategory                       news_categories
  id
  nameCkb   NOT NULL, UNIQUE       name_ckb  varchar(120)  ← the identity of a category
  nameKmr   NOT NULL               name_kmr  varchar(120)
  subCategories  1..n

NewsSubCategory                    news_sub_categories
  id
  nameCkb   NOT NULL               name_ckb  varchar(120)
  nameKmr   NOT NULL               name_kmr  varchar(120)
  category  NOT NULL               UNIQUE (category_id, name_ckb)
```

For a public consumer the important consequences are:

1. **Every article always has both.** `category_id` and `sub_category_id` are `NOT NULL` on the
   `news` table, and the writer API refuses a create or update that omits either.
2. **The DTO only exposes the names, never the ids.** `NewsDto.CategoryDto` and
   `NewsDto.SubCategoryDto` carry `ckbName` and `kmrName` and nothing else. Build your filter links
   from the name, which is exactly what
   [`/search/category`](#7-get-apiv1newssearchcategory--filter-by-category) and
   [`/search/subcategory`](#8-get-apiv1newssearchsubcategory--filter-by-subcategory) accept.
3. **`nameCkb` is the key.** Categories are looked up and de-duplicated by their Sorani name, which
   is globally unique. A subcategory's Sorani name is unique only inside its parent category.
4. **There is no "list all categories" endpoint on this controller.** To build a category
   navigation, page through `/api/v1/news` and collect the distinct `category` / `subCategory`
   objects, or use the site navigation menu API.

---

## Tiptap HTML bodies

`ckbContent.description` and `kmrContent.description` hold HTML produced by the Tiptap editor in
the admin dashboard. Treat them as trusted server-side HTML and render them with your framework's
raw-HTML escape hatch (`dangerouslySetInnerHTML`, `v-html`, `{@html}`).

What the stored HTML contains:

- Standard Tiptap block and inline markup: `<p>`, `<h2>`, `<h3>`, `<ul>`, `<ol>`, `<li>`,
  `<blockquote>`, `<strong>`, `<em>`, `<a>`, `<figure>`, `<figcaption>`.
- `<img>`, `<video>`, `<audio>` and `<source>` elements whose `src` is **always an S3 URL** under
  `https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/…`.
- `<a>` download links to documents (PDF, DOCX, XLSX …), also S3 URLs.

What it never contains:

- `data:` URIs. On the way in, `TiptapHtmlProcessor` finds every inline
  `src="data:<mime>;base64,…"` and `href="data:<mime>;base64,…"`, decodes it, uploads the bytes to
  S3 under `images/`, `video/`, `audio/` or `files/` depending on the MIME type, and rewrites the
  attribute to the resulting public URL. The database never stores base64 payloads.

Two caveats worth building for:

- The processor is best-effort. If a single inline asset fails to decode or fails to upload, that
  one attribute is left exactly as the editor sent it and the save still succeeds. A malformed
  `data:` URI can therefore survive into stored HTML in rare cases — guard your renderer.
- The HTML is **not sanitised**. There is no allow-list filter; whatever the dashboard editor
  produced is what you receive. Apply your own client-side sanitiser if untrusted editors have
  dashboard access.

Direction is not encoded in the HTML. Set `dir="rtl"` for `ckbContent` and `dir="ltr"` for
`kmrContent` in your own wrapper element.

---

## Enums used by this API

### `Language`

Values appear in the `contentLanguages` array.

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish (Sorani), right-to-left, Arabic script |
| `KMR` | Northern Kurdish (Kurmanji), left-to-right, Latin script |

Deserialisation is case-insensitive and whitespace-trimmed (`Language.from`), but responses always
emit the upper-case name.

### `MediaKind`

Values appear in `coverMediaType` and in each `mediaGallery[].kind`.

| Value | Meaning |
|-------|---------|
| `IMAGE` | Render with `<img src=url>`. `thumbnailUrl` is ignored |
| `VIDEO` | Render with `<video src=url poster=thumbnailUrl>` |
| `AUDIO` | Render with `<audio src=url>`, using `thumbnailUrl` as cover art |

---

## Error responses

Every error on this page uses the shared `ApiErrorResponse` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/news/999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NEWS_NOT_FOUND",
  "message": "News not found.",
  "messageEn": "News not found.",
  "messageKu": "هەواڵ نەدۆزرایەوە",
  "fieldErrors": null,
  "details": { "id": "999" }
}
```

- `code` is the `ErrorCode` enum. The News domain can produce `NEWS_NOT_FOUND`, `BAD_REQUEST`,
  `MISSING_PARAMETER`, `METHOD_NOT_ALLOWED` and `INTERNAL_ERROR` on the read side.
- `traceId` mirrors the `X-Trace-Id` response header, which `TraceIdFilter` sets on every response.
  Send your own `X-Trace-Id` request header to correlate a client-side log line with a server log.
- `message` is localised from `Accept-Language`. The locale resolver accepts `en`, `ckb` and `kmr`
  and defaults to `en`. A `?lang=ckb` query parameter also switches the locale
  (`LocaleChangeInterceptor`).
- `fieldErrors` is only populated by bean-validation failures, which none of these read endpoints
  trigger. It is `null` here.

> **Note:** `messageKu` is resolved against `Locale.forLanguageTag("ku")`, but the bundle files are
> `messages_en.properties`, `messages_ckb.properties` and `messages_kmr.properties` — there is no
> `messages_ku.properties` and no default `messages.properties`. Every `messageKu` therefore falls
> back to the hard-coded Sorani string in `GlobalExceptionHandler.fallbackByCode(...)` rather than
> reading the Kurmanji bundle. Do not expect Kurmanji error text in `messageKu`; use `code` to key
> your own translations.

---

## Notes & gotchas

**Nulls are serialised, not omitted.** `application.yaml` sets
`spring.jackson.default-property-inclusion: non_null`, but `JacksonConfig` defines its own
`ObjectMapper` bean, which replaces Spring Boot's auto-configured one and drops those YAML
properties. `NewsDto` carries no `@JsonInclude`, so unset fields arrive as explicit `null`
(`"kmrContent": null`, `"coverThumbnailUrl": null`). `ApiResponse` itself is annotated
`@JsonInclude(NON_NULL)`, so a null `data` *is* omitted from the envelope.

**Redis caching, 10-minute TTL.** Six of these eight endpoints are `@Cacheable(value = "news")`:
the list endpoint and all five searches. `GET /{id}` and `GET /featured` are **not** cached and
always hit the database.

| Endpoint | Redis key |
|----------|-----------|
| `GET /api/v1/news` | `khi:news::all:p{page}:s{size}` |
| `GET /api/v1/news/search` | `khi:news::search:{term-lowercased}:p{page}:s{size}` |
| `GET /api/v1/news/search/keyword` | `khi:news::kw:{term-lowercased}:lang:{language}:p{page}:s{size}` |
| `GET /api/v1/news/search/tag` | `khi:news::tag:{term-lowercased}:lang:{language}:p{page}:s{size}` |
| `GET /api/v1/news/search/category` | `khi:news::cat:{name-lowercased}:p{page}:s{size}` |
| `GET /api/v1/news/search/subcategory` | `khi:news::subcat:{name-lowercased}:p{page}:s{size}` |

Default TTL is 600 000 ms (10 minutes), key prefix `khi:`, null values are not cached. Every admin
create, bulk create, update, delete and bulk delete calls `@CacheEvict(value = "news",
allEntries = true)`, so an editorial change is visible immediately.

**Featuring an article does not evict the cache.** `PATCH /{id}/featured` writes through
`SiteContentService`, which carries no `@CacheEvict`. `/featured` is uncached and updates instantly,
but a cached `GET /api/v1/news` page can keep serving a stale `featureImageUrl` for up to ten
minutes after an admin sets it. If your homepage reads the hero image from the list endpoint rather
than from `/featured`, expect that lag.

**Paging is unclamped everywhere except `/featured`.** The list and the five searches pass `page`
and `size` straight into `PageRequest.of`, which throws `IllegalArgumentException` on a negative
page or a size below 1 — surfacing as `500 INTERNAL_ERROR`, not `400`. Validate on the client.
`/featured` is the exception and clamps into `page >= 0`, `1 <= size <= 100`.

**Ordering is by `datePublished`, not by `createdAt`.** `datePublished` is editor-controlled and
defaults to the creation date only when omitted. A back-dated article will not appear at the top of
the list even if it was published to the site a minute ago.

**Search reads are efficient despite the collections.** Each read runs in two phases: one
index-friendly, paginated ID-only query, then a batch hydration of the full rows. `@BatchSize(50)`
on the four tag/keyword collections, on `contentLanguages`, and on the `NewsCategory` /
`NewsSubCategory` classes means a page of 20 articles costs a fixed handful of `IN` queries rather
than N+1. Requesting a very large `size` breaks that assumption.

**`mediaGallery` is a JSONB column, already sorted.** It is stored as a single `jsonb` array on the
`news` row, so it needs no join and never triggers an extra query. The service sorts it by
`sortOrder` ascending before persisting, so you can render it in array order.

**`featureImageUrl` versus `coverUrl`.** `coverUrl` is the card image and is always present.
`featureImageUrl` is the wide homepage hero and is often `null` — fall back to `coverUrl` when it is.

**Media URLs are public S3 objects.** They follow
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/{images|video|audio|files}/{uuid}-{sanitised-filename}`.
There are no signed URLs and no expiry; you can cache them indefinitely and hot-link them directly.

**No `DELETE`-style side effects on read.** Nothing here mutates state; every handler is a plain
`GET`. There is no view counter and no read audit log — `NewsAuditLog` records only `CREATE`,
`UPDATE` and `DELETE`.

**No multipart on this controller.** News migrated to plain `application/json`. Inline media and
cover images are uploaded separately through `POST /api/v1/media/upload`, which is admin-only. See
[`../internal/NEWS_API.md`](../internal/NEWS_API.md).

---

## Related documentation

- Counterpart (admin writes): [`../internal/NEWS_API.md`](../internal/NEWS_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`
  (groups: `public`, `internal`, `all`; News appears under the `public` group)
