# Image Collections API — External (Public)

Image collections are the photo-publishment type of the KHI archive: a single photograph, a gallery,
or a sequential photo story, each described in Sorani and/or Kurmanji, optionally filed under a
topic from the shared publishment-topic registry. The endpoints on this page are the read side —
they are what the public website calls to render the photo archive, the collection detail page, and
the homepage hero carousel. None of them require a token.

Every write operation (create, update, delete, feature toggle) lives in the
[internal counterpart](../internal/IMAGE_COLLECTION_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/image-collections` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/image/ImageCollectionController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/image/ImageCollectionService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/image/ImageCollectionDTO.java` |
| **Entities** | `ImageCollection`, `ImageAlbumItem`, `ImageContent` (embeddable), `ImageCollectionLog`, `PublishmentTopic` |
| **Response envelope** | `ApiResponse<T>` — `{ "success", "message", "data" }` |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/image-collections` | None | — | Paginated list, optionally filtered by collection type or topic |
| 2 | `GET` | `/api/v1/image-collections/featured` | None | — | Paginated list of collections flagged for the homepage carousel |
| 3 | `GET` | `/api/v1/image-collections/{id}` | None | — | One collection by numeric id, fully hydrated |
| 4 | `GET` | `/api/v1/image-collections/slug/{slug}` | None | — | One collection by either its Sorani or its Kurmanji slug |
| 5 | `GET` | `/api/v1/image-collections/topics` | None | — | The `IMAGE` topic taxonomy, for filter chips and autocomplete |

All five are public because `SecurityConfig` ends the public-read block with
`.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()`. No handler in this controller carries a
`@PreAuthorize` on a `GET`.

---

## The `ApiResponse` envelope

Every endpoint on this page returns:

```json
{
  "success": true,
  "message": "Image collections fetched successfully",
  "data": {}
}
```

`success` is always `true` on a 2xx. Errors do **not** use this envelope — they use `ApiErrorResponse`
(see [Errors](#errors-shared-by-every-endpoint-on-this-page)).

Global Jackson configuration (`spring.jackson.default-property-inclusion: non_null`) means **null
fields are omitted from responses entirely**. A collection with no Kurmanji content simply has no
`kmrContent` key — do not treat a missing key as different from `null`.
Responses are pretty-printed (`spring.jackson.serialization.indent_output: true`).

---

## The `Response` object

This is the `data` payload of endpoints 3 and 4, and each element of `data.content` for
endpoints 1 and 2. Source: `ImageCollectionDTO.Response`.

| Field | Type | Notes |
|-------|------|-------|
| `id` | integer (int64) | Primary key |
| `slugCkb` | string | Sorani slug, unique across the table, max 240 chars |
| `slugKmr` | string | Kurmanji slug, unique across the table, max 240 chars |
| `collectionType` | enum | `SINGLE` \| `GALLERY` \| `PHOTO_STORY` |
| `ckbCoverUrl` | string (URL) | Sorani cover image |
| `kmrCoverUrl` | string (URL) | Kurmanji cover image |
| `hoverCoverUrl` | string (URL) | Overlay shown on card hover in list views |
| `featureImageUrl` | string (URL) | Wide hero picture for the homepage carousel. Written only by the internal featured PATCH; when absent, fall back to the cover |
| `topicId` | integer (int64) | `PublishmentTopic.id`, or absent when the collection has no topic |
| `topicNameCkb` | string | Sorani topic name |
| `topicNameKmr` | string | Kurmanji topic name |
| `publishmentDate` | string `yyyy-MM-dd` | Editorial publication date. Primary sort key on list endpoints |
| `contentLanguages` | array of enum | Subset of `["CKB", "KMR"]`, insertion-ordered. Tells the site which language tabs to render |
| `ckbContent` | object | See [`LanguageContentDto`](#languagecontentdto) — present only when `CKB` is in `contentLanguages` **and** at least one of its fields was filled in |
| `kmrContent` | object | Same, for Kurmanji |
| `tags` | object | `{ "ckb": [...], "kmr": [...] }`. Both keys are always present, possibly as empty arrays |
| `keywords` | object | Same shape as `tags` |
| `imageAlbum` | array | The photographs. See [`ImageItemDto`](#imageitemdto). Always sorted by `sortOrder` ascending |
| `createdAt` | string ISO date-time | e.g. `2026-06-12T10:04:31` |
| `updatedAt` | string ISO date-time | Bumped by a JPA `@PreUpdate` hook on every save |

> **Note:** `Response` deliberately does **not** expose the persisted `featured` flag or
> `featuredOrder`. The `ImageCollection` entity has both columns, but they are not mapped into the
> DTO. A client cannot tell from a single object whether it is featured — it can only infer it from
> the fact that the object came back from `GET /featured`.

### `LanguageContentDto`

| Field | Type | Notes |
|-------|------|-------|
| `title` | string | Max 300 chars |
| `description` | string (HTML) | Tiptap rich-text HTML. Any inline base64 media was already rewritten to S3 URLs before storage |
| `location` | string | Max 250 chars, e.g. `هەولێر، هەرێمی کوردستان` |
| `collectedBy` | string | Max 250 chars — the photographer or archivist credit |

> **Note:** `LanguageContentDto` also declares a `topic` field, but `ImageCollectionService` never
> reads or writes it (`buildContent()` copies only the four fields above, and the `ImageContent`
> embeddable has no topic column). It is always absent from responses. Use the top-level
> `topicId` / `topicNameCkb` / `topicNameKmr` instead.

### `ImageItemDto`

One entry per photograph in the album. Exactly one of `imageUrl`, `externalUrl` or `embedUrl` is
normally populated; when the item was uploaded as a file, it is always `imageUrl`.

| Field | Type | Notes |
|-------|------|-------|
| `id` | integer (int64) | `ImageAlbumItem.id` — stable across updates, needed by the dashboard |
| `imageUrl` | string (URL) | Direct S3 (or CDN) URL of the picture |
| `externalUrl` | string (URL) | External page link (Flickr, Unsplash, an institutional archive…) |
| `embedUrl` | string (URL) | Iframe-ready embed URL |
| `captionCkb` | string | Short Sorani caption, max 500 chars |
| `captionKmr` | string | Short Kurmanji caption, max 500 chars |
| `descriptionCkb` | string (HTML) | Longer Sorani description, Tiptap HTML |
| `descriptionKmr` | string (HTML) | Longer Kurmanji description, Tiptap HTML |
| `sortOrder` | integer | 0-based display order |
| `fileSizeBytes` | integer (int64) | Auto-extracted at upload time. Absent for URL-sourced items |
| `widthPx` | integer | Auto-extracted with `ImageIO`. Absent for URL-sourced items, and for formats `ImageIO` cannot decode |
| `heightPx` | integer | Same |
| `mimeType` | string | The uploaded part's `Content-Type`, e.g. `image/jpeg` |
| `aspectRatio` | number | Computed server-side as `widthPx / heightPx`. Use it to reserve layout space before the image loads |
| `humanReadableSize` | string | Computed server-side, e.g. `"2.4 MB"`, `"850 KB"` |

`aspectRatio` and `humanReadableSize` are `@Transient` getters on `ImageAlbumItem` — they are derived,
never stored, and are absent whenever `widthPx`/`heightPx`/`fileSizeBytes` are absent.

---

## The pagination wrapper

Endpoints 1 and 2 put a Spring Data `Page` inside `data`. Page serialization mode is the framework
default (`DIRECT`), so the full `PageImpl` shape is emitted:

```json
{
  "success": true,
  "message": "Image collections fetched successfully",
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
    "totalElements": 42,
    "totalPages": 3,
    "last": false,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 20,
    "first": true,
    "empty": false
  }
}
```

Read `content`, `totalElements`, `totalPages`, `number` and `size`. Treat `pageable` and `sort` as
framework noise that may change with a Spring Data upgrade.

---

## 1. `GET /api/v1/image-collections` — List collections

Returns a page of collections, newest first. The `type` and `topicId` parameters select which of
three service methods runs; they are **not** combined.

**Auth:** None (public)
**Content-Type:** — (no request body)
**Produces:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `type` | enum `ImageCollectionType` | No | — | `SINGLE` \| `GALLERY` \| `PHOTO_STORY`. When present, filters by collection type |
| `topicId` | integer (int64) | No | — | Filter by `PublishmentTopic.id`. **Ignored when `type` is also supplied** |
| `page` | integer | No | `0` | 0-based page index |
| `size` | integer | No | `20` | Page size. Not clamped on this endpoint — see the note below |

**Filter precedence** (straight from the controller's nested ternary):

1. `type` present → `getByType(type, page, size)`
2. else `topicId` present → `getByTopic(topicId, page, size)`
3. else → `getAll(page, size)`

> **Note:** Sending `?type=GALLERY&topicId=7` silently drops `topicId`. There is no combined filter
> and no error. If you need both, filter by topic and narrow client-side, or use the admin search
> endpoints.

**Ordering:** all three branches order by `publishmentDate DESC, createdAt DESC`. Rows with a null
`publishmentDate` sort last under PostgreSQL's default `DESC` → `NULLS LAST` behaviour.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Image collections fetched successfully",
  "data": {
    "content": [
      {
        "id": 42,
        "slugCkb": "qelay-hewler-1932",
        "slugKmr": "keleha-hewler-1932",
        "collectionType": "GALLERY",
        "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f1c2d43-6b9a-4d21-9c0e-1a2b3c4d5e6f-qelay-hewler-cover-ckb.jpg",
        "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/c71e9a04-2f55-43b8-8a17-0d9e7c6b5a44-keleha-hewler-cover-kmr.jpg",
        "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2d4f6a81-9c3b-4e70-b5d2-77aa1c8e3f90-qelay-hewler-hover.jpg",
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b0c5e19-4a72-4f3d-8e91-c2d3b4a5f607-qelay-hewler-hero.jpg",
        "topicId": 7,
        "topicNameCkb": "کەلەپوور",
        "topicNameKmr": "Kelepûr",
        "publishmentDate": "2026-06-12",
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": {
          "title": "قەڵای هەولێر لە ساڵی ١٩٣٢",
          "description": "<p>کۆمەڵەیەک وێنەی مێژوویی لە قەڵای هەولێر، کۆکراوەتەوە لە ئەرشیفی خێزانی.</p>",
          "location": "هەولێر، هەرێمی کوردستان",
          "collectedBy": "ئارام محەمەد"
        },
        "kmrContent": {
          "title": "Keleha Hewlêrê di sala 1932'an de",
          "description": "<p>Komek wêneyên dîrokî ji Keleha Hewlêrê, ji arşîva malbatî hatine berhevkirin.</p>",
          "location": "Hewlêr, Herêma Kurdistanê",
          "collectedBy": "Aram Mihemed"
        },
        "tags": {
          "ckb": ["قەڵا", "مێژوو", "هەولێر"],
          "kmr": ["Kele", "Dîrok", "Hewlêr"]
        },
        "keywords": {
          "ckb": ["ئەرشیفی وێنە", "کوردستان ١٩٣٢"],
          "kmr": ["arşîva wêneyan", "Kurdistan 1932"]
        },
        "imageAlbum": [
          {
            "id": 118,
            "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a3c8d5f2-7e14-4b96-92a0-5f6e7d8c9b01-qelay-hewler-01.jpg",
            "captionCkb": "دەروازەی سەرەکی قەڵا",
            "captionKmr": "Deriyê sereke yê keleyê",
            "descriptionCkb": "<p>دەروازەی باشووری قەڵا، وێنەگیراوە لە بەهاری ١٩٣٢.</p>",
            "descriptionKmr": "<p>Deriyê başûrî yê keleyê, di bihara 1932'an de hatiye wênekirin.</p>",
            "sortOrder": 0,
            "fileSizeBytes": 2517891,
            "widthPx": 3000,
            "heightPx": 2000,
            "mimeType": "image/jpeg",
            "aspectRatio": 1.5,
            "humanReadableSize": "2.4 MB"
          },
          {
            "id": 119,
            "externalUrl": "https://www.flickr.com/photos/kurdistan-archive/51234567890",
            "captionCkb": "دیمەنی گشتی شار لە قەڵاوە",
            "captionKmr": "Dîmena giştî ya bajêr ji keleyê",
            "sortOrder": 1
          }
        ],
        "createdAt": "2026-06-12T10:04:31",
        "updatedAt": "2026-06-18T09:12:07"
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
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 1,
    "first": true,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page` is negative or `size` is less than 1. `PageRequest.of` throws `IllegalArgumentException`; `details.reason` carries the framework message (`"Page index must not be less than zero"` / `"Page size must not be less than one"`) |
| `500` | `INTERNAL_ERROR` | `type` is not one of the three enum values, or `page`/`size` is not numeric — see the type-coercion note below |

> **Note:** `?type=BOGUS`, `?page=abc` and `?size=abc` all raise
> `MethodArgumentTypeMismatchException`, for which `GlobalExceptionHandler` has no `@ExceptionHandler`.
> The catch-all `@ExceptionHandler(Exception.class)` therefore answers **500 `INTERNAL_ERROR`** where a
> 400 would be correct. Validate enum and numeric query parameters client-side.

**Example**

```bash
# Everything, first page
curl -s http://localhost:8080/api/v1/image-collections

# Photo stories only, 12 per page
curl -s "http://localhost:8080/api/v1/image-collections?type=PHOTO_STORY&page=0&size=12"

# Everything filed under the "heritage" topic
curl -s "http://localhost:8080/api/v1/image-collections?topicId=7"
```

---

## 2. `GET /api/v1/image-collections/featured` — Homepage carousel slides

Returns only collections whose `featured` column is `true`, ordered by `featuredOrder` ascending then
`id` descending. The homepage hero carousel is built from this list (plus the equivalent lists from
news, projects, writings, videos and sound tracks).

**Auth:** None (public)
**Produces:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | integer | No | `0` | 0-based page index. Values below 0 are clamped to 0 |
| `size` | integer | No | `20` | Page size. Clamped into the range 1–100 |

Unlike endpoint 1, this endpoint clamps rather than rejects: `featuredPageable()` applies
`Math.max(page, 0)` and `Math.min(Math.max(size, 1), 100)`. `?page=-5&size=9999` returns page 0 with
100 items instead of a 400.

**Ordering:** `featuredOrder ASC, id DESC`. `featuredOrder` is nullable and PostgreSQL sorts nulls
last on an ascending sort, so unordered featured collections appear after the explicitly ordered ones.

**Response `200 OK`**

Identical shape to endpoint 1, with the message `"Featured image collections fetched successfully"`:

```json
{
  "success": true,
  "message": "Featured image collections fetched successfully",
  "data": {
    "content": [
      {
        "id": 42,
        "slugCkb": "qelay-hewler-1932",
        "slugKmr": "keleha-hewler-1932",
        "collectionType": "GALLERY",
        "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f1c2d43-6b9a-4d21-9c0e-1a2b3c4d5e6f-qelay-hewler-cover-ckb.jpg",
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b0c5e19-4a72-4f3d-8e91-c2d3b4a5f607-qelay-hewler-hero.jpg",
        "publishmentDate": "2026-06-12",
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": {
          "title": "قەڵای هەولێر لە ساڵی ١٩٣٢",
          "location": "هەولێر، هەرێمی کوردستان"
        },
        "kmrContent": {
          "title": "Keleha Hewlêrê di sala 1932'an de",
          "location": "Hewlêr, Herêma Kurdistanê"
        },
        "tags": { "ckb": ["قەڵا", "مێژوو"], "kmr": ["Kele", "Dîrok"] },
        "keywords": { "ckb": [], "kmr": [] },
        "imageAlbum": [],
        "createdAt": "2026-06-12T10:04:31",
        "updatedAt": "2026-06-18T09:12:07"
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
    "totalElements": 1,
    "totalPages": 1,
    "last": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": false, "sorted": true, "unsorted": false },
    "numberOfElements": 1,
    "first": true,
    "empty": false
  }
}
```

Render `featureImageUrl` when it is present and fall back to `ckbCoverUrl` / `kmrCoverUrl` when it is
not — that is exactly the contract the dashboard's featured screen was built against.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | `page` or `size` is non-numeric (same type-coercion gap as endpoint 1) |

Out-of-range pages are not an error: page 99 of a 1-page result returns an empty `content` array
with `"empty": true`.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/image-collections/featured?size=10"
```

---

## 3. `GET /api/v1/image-collections/{id}` — One collection by id

Loads a single collection with all of its collections eagerly fetched through a JPA
`@EntityGraph` (`contentLanguages`, `tagsCkb`, `tagsKmr`, `keywordsCkb`, `keywordsKmr`, `imageAlbum`,
`topic`), so the album and taxonomy always come back fully populated.

**Auth:** None (public)
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | Yes | `ImageCollection.id` |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Image collection fetched successfully",
  "data": {
    "id": 42,
    "slugCkb": "qelay-hewler-1932",
    "slugKmr": "keleha-hewler-1932",
    "collectionType": "GALLERY",
    "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f1c2d43-6b9a-4d21-9c0e-1a2b3c4d5e6f-qelay-hewler-cover-ckb.jpg",
    "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/c71e9a04-2f55-43b8-8a17-0d9e7c6b5a44-keleha-hewler-cover-kmr.jpg",
    "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2d4f6a81-9c3b-4e70-b5d2-77aa1c8e3f90-qelay-hewler-hover.jpg",
    "topicId": 7,
    "topicNameCkb": "کەلەپوور",
    "topicNameKmr": "Kelepûr",
    "publishmentDate": "2026-06-12",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "قەڵای هەولێر لە ساڵی ١٩٣٢",
      "description": "<p>کۆمەڵەیەک وێنەی مێژوویی لە قەڵای هەولێر.</p>",
      "location": "هەولێر، هەرێمی کوردستان",
      "collectedBy": "ئارام محەمەد"
    },
    "kmrContent": {
      "title": "Keleha Hewlêrê di sala 1932'an de",
      "description": "<p>Komek wêneyên dîrokî ji Keleha Hewlêrê.</p>",
      "location": "Hewlêr, Herêma Kurdistanê",
      "collectedBy": "Aram Mihemed"
    },
    "tags": { "ckb": ["قەڵا", "مێژوو", "هەولێر"], "kmr": ["Kele", "Dîrok", "Hewlêr"] },
    "keywords": { "ckb": ["ئەرشیفی وێنە"], "kmr": ["arşîva wêneyan"] },
    "imageAlbum": [
      {
        "id": 118,
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a3c8d5f2-7e14-4b96-92a0-5f6e7d8c9b01-qelay-hewler-01.jpg",
        "captionCkb": "دەروازەی سەرەکی قەڵا",
        "captionKmr": "Deriyê sereke yê keleyê",
        "sortOrder": 0,
        "fileSizeBytes": 2517891,
        "widthPx": 3000,
        "heightPx": 2000,
        "mimeType": "image/jpeg",
        "aspectRatio": 1.5,
        "humanReadableSize": "2.4 MB"
      }
    ],
    "createdAt": "2026-06-12T10:04:31",
    "updatedAt": "2026-06-18T09:12:07"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `IMAGE_NOT_FOUND` | No row with that id. `details` is `{ "id": "999" }` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number, e.g. `/api/v1/image-collections/abc` (unhandled `MethodArgumentTypeMismatchException`) |

404 body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/image-collections/999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "IMAGE_NOT_FOUND",
  "message": "Image collection not found",
  "messageEn": "Image collection not found",
  "messageKu": "کۆمەڵەی وێنە نەدۆزرایەوە",
  "details": { "id": "999" }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/image-collections/42
```

---

## 4. `GET /api/v1/image-collections/slug/{slug}` — One collection by slug

Looks the collection up with `findBySlugCkbOrSlugKmr(slug, slug)` — one slug value is matched against
**both** slug columns, so the Sorani and the Kurmanji URL both resolve to the same record. Same
`@EntityGraph` hydration as endpoint 3.

**Auth:** None (public)
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `slug` | string | Yes | Either `slugCkb` or `slugKmr`. Matching is exact and case-sensitive — the query is a plain equality check, not a `lower()` comparison |

**Response `200 OK`**

Identical body to endpoint 3, with the same message `"Image collection fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No row whose `slugCkb` or `slugKmr` equals the value. `details` is `{ "slug": "..." }` |

> **Note:** This endpoint answers `NOT_FOUND`, while the by-id lookup answers `IMAGE_NOT_FOUND`.
> The service uses the generic `Errors.notFound(...)` factory here and the domain-specific
> `Errors.imageNotFound(id)` there. Handle both codes if you branch on `code`.

404 body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/image-collections/slug/qelay-hewler-1933",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NOT_FOUND",
  "message": "Resource not found",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "details": { "slug": "qelay-hewler-1933" }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/image-collections/slug/qelay-hewler-1932

# The Kurmanji slug resolves to the same record
curl -s http://localhost:8080/api/v1/image-collections/slug/keleha-hewler-1932
```

---

## 5. `GET /api/v1/image-collections/topics` — The `IMAGE` topic taxonomy

Returns every `PublishmentTopic` whose `entityType` is `"IMAGE"`. Topics are a shared registry across
publishment types (`VIDEO`, `SOUND`, `IMAGE`, `WRITING`); this endpoint returns only the image slice,
so a video topic can never appear in an image filter. The equivalent endpoint exists on the sound
track controller.

The result is a plain list, not a page — there is no pagination and no filtering.

**Auth:** None (public)
**Produces:** `application/json`

**Response `200 OK`**

Each element has exactly three keys, built inline by the controller with `Map.of(...)`. `nameCkb` and
`nameKmr` are coerced to `""` when the underlying column is null, so they are never absent.

```json
{
  "success": true,
  "message": "IMAGE topics fetched successfully",
  "data": [
    { "id": 7,  "nameCkb": "کەلەپوور",        "nameKmr": "Kelepûr" },
    { "id": 12, "nameCkb": "ژیانی ڕۆژانە",     "nameKmr": "Jiyana rojane" },
    { "id": 18, "nameCkb": "کشتوکاڵ و لادێ",   "nameKmr": "Çandinî û gund" },
    { "id": 23, "nameCkb": "جل و بەرگی کوردی", "nameKmr": "Cil û bergên kurdî" }
  ]
}
```

An empty registry returns `"data": []`, not `null`.

Feed `id` back into `GET /api/v1/image-collections?topicId=<id>` to filter the archive.

> **Note:** The map keys are ordered by `Map.of(...)`, which does not preserve insertion order.
> Do not depend on key order in the JSON — parse by key name.

**Example**

```bash
curl -s http://localhost:8080/api/v1/image-collections/topics
```

---

## Enums used by this API

### `ImageCollectionType`

`ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType`

| Value | Meaning | Album size enforced on write |
|-------|---------|------------------------------|
| `SINGLE` | One photograph presented with its own metadata | Exactly 1 |
| `GALLERY` | A photo album — many pictures with equal weight | At least 1 |
| `PHOTO_STORY` | Sequential pictures telling a story or documenting a process step by step | At least 2 |

The count rules are enforced by the internal write endpoints, not by the read endpoints. A read
client should still be defensive: a legacy row could in principle violate them.

### `Language`

`ak.dev.khi_backend.khi_app.enums.Language`

| Value | Meaning |
|-------|---------|
| `CKB` | Kurdish Central — Sorani |
| `KMR` | Kurdish Kurmanji |

Appears in `contentLanguages`. Deserialization is case-insensitive and trimmed (`@JsonCreator`),
serialization is always the upper-case name (`@JsonValue`).

---

## Errors shared by every endpoint on this page

All errors use `ApiErrorResponse`, never the `ApiResponse` envelope:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/image-collections/999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "IMAGE_NOT_FOUND",
  "message": "Image collection not found",
  "messageEn": "Image collection not found",
  "messageKu": "کۆمەڵەی وێنە نەدۆزرایەوە",
  "details": { "id": "999" }
}
```

* `message` follows the request's `Accept-Language`; `messageEn` and `messageKu` are always present.
* `traceId` comes from `TraceIdFilter` via SLF4J MDC — quote it in bug reports.
* `fieldErrors` and `details` are omitted when null (global `non_null` inclusion).
* The image domain has no entries in `src/main/resources/i18n/*.properties`, so its messages come
  from `GlobalExceptionHandler.fallbackByCode(...)`: `IMAGE_NOT_FOUND` → `"Image collection not found"`
  / `"کۆمەڵەی وێنە نەدۆزرایەوە"`, `IMAGE_VALIDATION` → `"Image validation error"` /
  `"هەڵەی پشکنینەوە لە داتای وێنەدا"`.

> **Note:** Two independent naming faults mean `messageEn` and `messageKu` are effectively always the
> handler's hard-coded literals, never bundle translations. The English bundle is on disk as
> `src/main/resources/i18n/ messages_en.properties` — with a **leading space in the filename** — while
> the `MessageSource` basename is `classpath:i18n/messages`, so `i18n/messages_en.properties` is never
> found. And `messageKu` is resolved with `Locale.forLanguageTag("ku")` while the Kurdish bundles are
> named `messages_ckb.properties` / `messages_kmr.properties`, with no `messages_ku.properties` and no
> default `messages.properties` to fall back to. Only `message` can pick up a real translation, and
> only when the client sends `Accept-Language: ckb` or `kmr` for a key that exists in that bundle.

| Status | `code` | Applies to | When |
|--------|--------|-----------|------|
| `400` | `BAD_REQUEST` | 1 | `page < 0` or `size < 1` |
| `404` | `IMAGE_NOT_FOUND` | 3 | Unknown id |
| `404` | `NOT_FOUND` | 4 | Unknown slug |
| `404` | `NOT_FOUND` | all | Path does not exist at all (`NoResourceFoundException`) — `details` carries `path`, `method`, `hint` |
| `405` | `METHOD_NOT_ALLOWED` | all | Wrong verb, e.g. `POST /api/v1/image-collections/topics`. `details` carries `usedMethod` and `supportedMethods` |
| `500` | `INTERNAL_ERROR` | 1, 2, 3 | Unparseable enum or numeric path/query value; also any unexpected server fault. `details` carries `traceId` and a support hint |

---

## Notes & gotchas

**Caching.** `getAll`, `getByType` and `getByTopic` are `@Cacheable(value = "imageCollections", ...)`
against Redis (key prefix `khi:`, TTL 10 minutes, JDK serialization). The cache keys are
`all:p{page}:s{size}`, `type:{TYPE}:p{page}:s{size}` and `topic:{id}:p{page}:s{size}`. Writes on the
internal endpoints evict the whole `imageCollections` cache (`allEntries = true`), so a create,
update or delete is visible immediately.

`getFeatured`, `getById` and `getBySlug` are **not** cached and always hit PostgreSQL.

> **Note:** The internal `PATCH /{id}/featured` runs through `SiteContentService`, which carries no
> `@CacheEvict` for `imageCollections`. Toggling a collection's featured state therefore leaves the
> cached list pages stale for up to 10 minutes — `GET /featured` reflects the change instantly, but
> `featureImageUrl` inside a cached `GET /api/v1/image-collections` page may lag.

**Album ordering.** `imageAlbum` is sorted twice: by `@OrderBy("sortOrder ASC")` at the JPA level and
again in `toResponse` with a null-safe comparator that treats a null `sortOrder` as `0`. Two items
sharing a `sortOrder` keep a stable but unspecified relative order — nothing in the schema enforces
uniqueness of `sortOrder` within a collection.

**Cover image selection.** There are four picture slots and no server-side fallback logic in the DTO
layer. Pick client-side:

* `ckbCoverUrl` for the Sorani site, `kmrCoverUrl` for the Kurmanji site;
* `hoverCoverUrl` for the card mouse-over state;
* `featureImageUrl` for the homepage hero, falling back to the cover when absent.

The entity does expose a `getAnyCoverUrl()` helper (ckb → kmr → hover), but it is used internally for
logs and thumbnails and is not mapped into the response.

**Slug uniqueness and shape.** `slug_ckb` and `slug_kmr` each carry a database `UNIQUE` constraint
(240 chars). Nothing in the service generates, transliterates, normalizes or lower-cases a slug — the
dashboard supplies them verbatim, and both may be omitted entirely, in which case the collection is
reachable only by id. Both columns are nullable, and PostgreSQL allows any number of NULLs under a
unique constraint.

**S3 URL shape.** Uploaded pictures live under
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/<uuid>-<sanitized-filename>`.
The uuid prefix means the same original filename can be uploaded repeatedly without collision. Do not
parse meaning out of the key; treat the URL as opaque. `externalUrl` and `embedUrl` items point at
third-party hosts and are outside KHI's control — expect broken links and set an `onerror` fallback.

**Query performance.** The list endpoints use a deliberate two-phase strategy: phase 1 fetches only
ids (`findAllIds` / `findIdsByType` / `findIdsByTopic`, no joins, index-backed), phase 2 hydrates them
with `findAllByIds` and lets `@BatchSize(50)` load languages, tags, keywords, album items and topics
in eight `IN` queries regardless of page size. This avoids the Cartesian blow-up an eager join would
cause. `GET /featured` does not use this path — it hydrates lazily row by row, still batched.

**Timestamps.** `createdAt` / `updatedAt` are `LocalDateTime` written by JPA `@PrePersist` /
`@PreUpdate` hooks using the server clock, stored in UTC (`hibernate.jdbc.time_zone=UTC`), and
serialized as ISO-8601 without a zone offset (`2026-06-12T10:04:31`). `publishmentDate` is a plain
`LocalDate` (`2026-06-12`) and is editorial metadata — it is not derived from `createdAt` and may be
null.

**Rich text.** `description` on both `LanguageContentDto` and `ImageItemDto` is trusted HTML produced
by the dashboard's Tiptap editor. `TiptapHtmlProcessor` rewrites inline `data:` URIs to S3 URLs at
write time, but it does **not** sanitize markup. Render it through your own sanitizer if the site is
exposed to untrusted editors.

---

## Related documentation

- Counterpart: [`../internal/IMAGE_COLLECTION_API.md`](../internal/IMAGE_COLLECTION_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (group `public`)
