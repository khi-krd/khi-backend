# Writings API — External (Public)

The Writings domain holds the institute's books and long-form publications. Every record is
bilingual: it carries an independent Sorani (`CKB`) and Kurmanji (`KMR`) block, each with its own
title, synopsis, author name, downloadable file and page count. A book can carry several genres at
once (a historical novel is `HISTORY` + `NOVEL`), and books can be chained into a *series* so the
public site can render "Volume 1 / 2 / 3" navigation.

Every endpoint on this page is reachable by an anonymous visitor — SecurityConfig lets every
`GET /api/v1/**` through with `permitAll()`. Creating, editing, deleting, series-linking and
featuring a book all require a JWT and are documented in
[`../internal/WRITING_API.md`](../internal/WRITING_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/writings` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/writing/WritingController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/writing/WritingService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/writing/WritingDtos.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/publishment/writing/WritingRepository.java` |
| **Entities** | `Writing`, `WritingContent` (embeddable), `WritingLog`, `PublishmentTopic`, `BookGenre` (editor-managed genre rows) |
| **Enums** | `Language`, `WritingFileFormat` (plus the legacy `BookGenre` request shim) |
| **Verified against source** | 2026-09-03 |

> **Update 2026-09-03 — genres are now editor-managed rows.** The fixed `BookGenre` enum has been
> replaced by database rows (`book_genres`) with public reads and admin CRUD at
> `/api/v1/book-genres` — see [`../BOOK_GENRES.md`](../BOOK_GENRES.md). Book responses keep the
> `bookGenres` string array unchanged (now the linked rows' slugs — identical values for the 22
> seeded genres) and additionally carry a `genres` array with the full row objects (id, slug,
> bilingual names). Nothing on this page breaks; the enum table further down now documents the
> seeded rows rather than compiled code.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/writings` | None | — | Paged list of every book, newest first |
| 2 | `GET` | `/api/v1/writings/featured` | None | — | Paged list of books flagged for the homepage carousel |
| 3 | `GET` | `/api/v1/writings/{id}` | None | — | One book, fully hydrated |
| 4 | `GET` | `/api/v1/writings/series/parents` | None | — | Paged list of series root books |
| 5 | `GET` | `/api/v1/writings/series/{seriesId}` | None | — | Every volume of one series, ordered |
| 6 | `GET` | `/api/v1/writings/search/writer` | None | — | Search by author name |
| 7 | `GET` | `/api/v1/writings/search/tag` | None | — | Search by tag |
| 8 | `GET` | `/api/v1/writings/search/keyword` | None | — | Search by keyword |
| 9 | `GET` | `/api/v1/writings/topics` | None | — | The `WRITING` topic registry (for filter chips) |
| 10 | `GET` | `/api/v1/book-genres` | None | — | The editor-managed genre rows (for the genre chips) — full contract in [`../BOOK_GENRES.md`](../BOOK_GENRES.md) |

There is no authentication header on any of these. Sending one is harmless — the JWT filter simply
populates the security context and the `permitAll()` rule still applies.

---

## Shared response shapes

Read this section once; the per-endpoint sections below then only show what is specific to them.

The per-endpoint JSON samples are abridged for readability — optional fields that would be present
in a real response are sometimes left out. The one complete, non-abridged example is
[Full example of one book](#full-example-of-one-book). In particular, `seriesInfo` / `series` are
present on essentially every record in practice, because the entity auto-assigns a `seriesId` on
insert.

### The `ApiResponse<T>` envelope

Every endpoint in this domain wraps its payload in `ApiResponse<T>`:

```json
{
  "success": true,
  "message": "Writings fetched successfully",
  "data": { }
}
```

`ApiResponse` is annotated `@JsonInclude(NON_NULL)`, and the application also sets
`spring.jackson.default-property-inclusion: non_null` globally. **Null fields are omitted from the
JSON entirely, not emitted as `null`.** A frontend must treat "key absent" and "value null" as the
same thing throughout this API.

### The `Page<T>` envelope

Endpoints 1, 2, 4, 6, 7 and 8 put a Spring Data `Page` in `data`. `spring.data.web.pageable
.serialization-mode` is left at its default (`direct`), so the raw `PageImpl` shape is serialised:

```json
{
  "content": [],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalPages": 3,
  "totalElements": 57,
  "last": false,
  "sort": { "sorted": true, "unsorted": false, "empty": false },
  "first": true,
  "numberOfElements": 20,
  "size": 20,
  "number": 0,
  "empty": false
}
```

`sort.sorted` is `true` on every paged writings endpoint, because each one builds a real `Sort`
object rather than relying on an `ORDER BY` buried in JPQL.

Requesting a page past the end is not an error: you get `content: []`, `empty: true` and the real
`totalElements`.

### The writing object

This is the `WritingDtos.Response` class. It is the `data` of endpoint 3 and every element of
`content` on endpoints 1, 2, 4, 6, 7 and 8.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key |
| `contentLanguages` | array of `Language` | no | Which language blocks this book declares. Empty array for legacy rows. |
| `ckbCoverUrl` | string | yes | Sorani cover image URL |
| `kmrCoverUrl` | string | yes | Kurmanji cover image URL |
| `hoverCoverUrl` | string | yes | Overlay image shown on card hover |
| `featureImageUrl` | string | yes | Wide hero picture for the homepage carousel. Written only by the admin featured PATCH; falls back to a cover when absent. |
| `ckbContent` | `LanguageContentDto` | yes | Sorani text + file block |
| `kmrContent` | `LanguageContentDto` | yes | Kurmanji text + file block |
| `topic` | `TopicInfo` | yes | The `PublishmentTopic` this book is filed under |
| `topicId` | integer (int64) | yes | Flattened alias for `topic.id` |
| `topicNameCkb` | string | yes | Flattened alias for `topic.nameCkb` |
| `topicNameKmr` | string | yes | Flattened alias for `topic.nameKmr` |
| `bookGenres` | array of string | no | The linked genre rows' slugs (`"POETRY"`, `"HISTORY"`…) — backward-compatible with the old enum array. Always present; may be `[]` for legacy rows. Ordered by the genres' `displayOrder`. |
| `genres` | array of `GenreInfo` | no | The full linked genre rows: `{ id, slug, nameCkb, nameKmr }`. Same order as `bookGenres`. Always present; may be `[]`. |
| `publishedByInstitute` | boolean | no | `true` when KHI itself published the book (as opposed to merely archiving it) |
| `tags` | `BilingualSet` | no | Short display labels, per language |
| `keywords` | `BilingualSet` | no | Search terms, per language |
| `seriesInfo` | `SeriesInfoDto` | yes | Series membership. Absent only for rows created before the series columns existed. |
| `series` | `SeriesInfoDto` | yes | Byte-for-byte alias of `seriesInfo`, kept for the public website |
| `createdAt` | string date-time | no | `2026-03-14T09:14:22` |
| `updatedAt` | string date-time | no | `2026-03-14T09:14:22` |

Timestamps are `LocalDateTime` and serialise as ISO-8601 **without** an offset. They are stored in
UTC in PostgreSQL (`hibernate.jdbc.time_zone=UTC`); `spring.jackson.time-zone: Asia/Baghdad` only
affects zoned types, so these values read back exactly as stored.

> **Note:** `seriesInfo` and `series` are two serialisations of the same object. Pick one and stay
> consistent — they can never disagree. Likewise `topic` and the flat `topicId` /
> `topicNameCkb` / `topicNameKmr` triple: when `topic` is null all four are omitted.

#### `LanguageContentDto`

One per language. Every field is independently nullable.

| Field | Type | Description |
|-------|------|-------------|
| `title` | string (≤300) | Book title in this language |
| `description` | string (≤10000) | Synopsis. Stored as Tiptap-produced HTML; any inline base64 media has already been pushed to S3 and rewritten to a public URL before persistence. |
| `writer` | string (≤200) | Author name in this language |
| `fileUrl` | string (≤1000) | Downloadable book file — an S3 URL when uploaded through the dashboard, or an external URL |
| `fileFormat` | `WritingFileFormat` | `PDF`, `EPUB`, … — see the enum table |
| `fileSizeBytes` | integer (int64) | Size of `fileUrl` in bytes, as supplied by the editor |
| `pageCount` | integer | Page count for this language edition |
| `genre` | string (≤150) | Free-text genre label in this language, e.g. `"ڕۆمانی مێژوویی"`. **Display only** — unrelated to the machine-readable `bookGenres` set. |

> **Note:** `fileSizeBytes` and `fileFormat` are metadata typed in by the editor. The backend does
> not derive them from the uploaded file and does not verify them. Treat them as hints, not
> guarantees — always fall back to the extension of `fileUrl` when they disagree.

#### `SeriesInfoDto`

| Field | Type | Description |
|-------|------|-------------|
| `seriesId` | string (≤100) | The series key. Auto-generated as `series-<epochMillis>` when a book is created without one. |
| `seriesName` | string (≤300) | Display name of the series |
| `seriesOrder` | number (double) | Position within the series. Defaults to `1.0`. Fractional values are allowed so a volume can be slotted between two others. |
| `parentBookId` | integer (int64) | Id of the root book. Absent on the root itself. |
| `totalBooks` | integer | Denormalised count of books sharing this `seriesId` |
| `parent` | boolean | `true` when this row is the series root |

> **Note:** the JSON key is **`parent`**, not `isParent`. The DTO field is declared
> `private boolean isParent`, Lombok generates `isParent()`, and Jackson strips the `is` prefix.
> Any older documentation or client code reading `series.isParent` is reading `undefined`.

#### `BilingualSet`

```json
{ "ckb": ["مێژوو", "کوردستان"], "kmr": ["dîrok", "Kurdistan"] }
```

Both sides are always present on responses (an empty array when nothing is stored). Ordering is the
insertion order of the underlying `LinkedHashSet` and is stable across reads, but is not a sort.

#### `TopicInfo`

```json
{ "id": 7, "nameCkb": "مێژووی هاوچەرخ", "nameKmr": "Dîroka hevçerx" }
```

### Full example of one book

```json
{
  "success": true,
  "message": "Writing fetched successfully",
  "data": {
    "id": 41,
    "contentLanguages": ["CKB", "KMR"],
    "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b2f0e51-9c3a-4a1d-bd77-0f5a1c2e8b44-berg_ckb.jpg",
    "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a41d7c92-5f60-4d8e-8b21-3ce9042f77aa-berg_kmr.jpg",
    "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/d0c3b118-77ea-4c55-9f30-51b8a2d6e4c7-hover.jpg",
    "ckbContent": {
      "title": "مێژووی کوردستان — بەرگی یەکەم",
      "description": "<p>لێکۆڵینەوەیەکی فراوان لەسەر گۆڕانکارییە سیاسییەکانی کوردستان لە نێوان ١٩٢٠ و ١٩٤٦.</p>",
      "writer": "د. عەبدوڵا حەسەن",
      "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/1c9d5a70-3e8b-42f1-9a6c-77b0d4e2f915-mejuy_kurdistan_1.pdf",
      "fileFormat": "PDF",
      "fileSizeBytes": 18734221,
      "pageCount": 312,
      "genre": "لێکۆڵینەوەی مێژوویی"
    },
    "kmrContent": {
      "title": "Dîroka Kurdistanê — Berga Yekem",
      "description": "<p>Lêkolînek berfireh li ser guhertinên siyasî yên Kurdistanê di navbera 1920 û 1946 de.</p>",
      "writer": "Dr. Ebdulla Hesen",
      "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/7fa2c341-90d6-4b18-a55e-2d8e6b0c19f3-diroka_kurdistane_1.pdf",
      "fileFormat": "PDF",
      "fileSizeBytes": 17980114,
      "pageCount": 298,
      "genre": "Lêkolîna dîrokî"
    },
    "topic": { "id": 7, "nameCkb": "مێژووی هاوچەرخ", "nameKmr": "Dîroka hevçerx" },
    "bookGenres": ["HISTORY", "POLITICS"],
    "genres": [
      { "id": 5,  "slug": "HISTORY",  "nameCkb": "مێژوو",  "nameKmr": "Dîrok" },
      { "id": 10, "slug": "POLITICS", "nameCkb": "سیاسەت", "nameKmr": "Siyaset" }
    ],
    "publishedByInstitute": true,
    "tags": {
      "ckb": ["مێژوو", "کوردستان"],
      "kmr": ["dîrok", "Kurdistan"]
    },
    "keywords": {
      "ckb": ["سەدەی بیستەم", "کۆماری مەهاباد"],
      "kmr": ["sedsala bîstan", "Komara Mehabadê"]
    },
    "seriesInfo": {
      "seriesId": "series-1758042911233",
      "seriesName": "مێژووی کوردستان",
      "seriesOrder": 1.0,
      "totalBooks": 3,
      "parent": true
    },
    "createdAt": "2026-03-14T09:14:22",
    "updatedAt": "2026-04-02T11:40:07",
    "series": {
      "seriesId": "series-1758042911233",
      "seriesName": "مێژووی کوردستان",
      "seriesOrder": 1.0,
      "totalBooks": 3,
      "parent": true
    },
    "topicId": 7,
    "topicNameCkb": "مێژووی هاوچەرخ",
    "topicNameKmr": "Dîroka hevçerx"
  }
}
```

---

## The series model

Series membership is what lets the site render "Volume 2 of 3" and a sibling strip. Three columns on
the `writings` table carry it, plus one self-referencing foreign key:

| Column | Meaning |
|--------|---------|
| `series_id` | The grouping key. **Every** book has one. |
| `parent_book_id` | Self-FK to the root volume. `NULL` on the root. |
| `series_order` | Position, a `double` so volumes can be inserted between existing ones. |
| `series_total_books` | Denormalised `COUNT(*)` of rows with the same `series_id`. |

The key thing to understand: **a standalone book is a series of one.** The `Writing` entity's
`@PrePersist` hook fills `seriesId` with `series-<System.currentTimeMillis()>` and `seriesOrder`
with `1.0` whenever they were not supplied. So there is no such thing as a book without a series.
The distinction the site cares about is whether the series has more than one member.

Two derived booleans on the entity implement that:

```
isPartOfSeries() = seriesId != null && (seriesTotalBooks == null || seriesTotalBooks > 1)
isSeriesParent() = parentBook == null && isPartOfSeries()
```

That is what `seriesInfo.parent` reports. A lone book has `totalBooks: 1`, so `isPartOfSeries()` is
false and `parent` is **`false`** — correct, because there is no series to be the parent of.

### Worked example

An editor publishes volume one of a three-volume history.

**Step 1 — the root is created.** No `seriesId`, no `parentBookId` in the request, but a
`seriesName` is given.

```
POST /api/v1/writings   →  id 41
  seriesId          = "series-1758042911233"   (auto-generated by @PrePersist)
  seriesName        = "مێژووی کوردستان"
  seriesOrder       = 1.0                      (auto-defaulted)
  parentBookId      = null
  seriesTotalBooks  = 1
```

Reading it back gives `"parent": false` and `"totalBooks": 1`. It is a standalone book so far.

**Step 2 — volume two is created with `parentBookId: 41`.** The service looks the parent up, copies
its `seriesId` onto the child, and — because `seriesOrder` was omitted — computes
`MAX(seriesOrder) + 1` across the series.

```
POST /api/v1/writings   →  id 42
  parentBookId      = 41
  seriesId          = "series-1758042911233"   (copied from the parent, NOT from the request)
  seriesOrder       = 2.0                      (max 1.0 + 1.0)
```

The service then recounts the series and stamps `seriesTotalBooks = 2` onto **both** rows. Book 41
now reports `"parent": true, "totalBooks": 2`; book 42 reports
`"parent": false, "parentBookId": 41, "seriesOrder": 2.0, "totalBooks": 2`.

**Step 3 — an already-published book (id 57) is retro-fitted into the series** with
`POST /api/v1/writings/series/link`, `{ "bookId": 57, "parentBookId": 41, "seriesOrder": 3 }`.
Same effect: parent set, `seriesId` copied from the parent, count re-stamped to 3 on all three rows.

**Reading the series back.** The public site asks for
`GET /api/v1/writings/series/series-1758042911233` and gets all three volumes ordered by
`seriesOrder ASC`, with the series name resolved from the first row.

**Inserting between volumes.** Because `seriesOrder` is a `double`, slotting a companion volume
between 1 and 2 is `seriesOrder: 1.5`. No renumbering is needed.

> **Note:** `seriesId` in a create request is silently discarded whenever `parentBookId` is also
> present — the parent's key always wins. Supplying your own `seriesId` is only meaningful when
> creating a root.

---

## 1. `GET /api/v1/writings` — List every book

Returns one page of books, newest first. The topic is join-fetched, so rendering
`topicNameCkb` on a list of cards costs no extra queries.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `20` | Page size. Not clamped — see errors below. |

Sorting is fixed: `createdAt DESC`. There is no `sort` parameter.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Writings fetched successfully",
  "data": {
    "content": [
      {
        "id": 41,
        "contentLanguages": ["CKB", "KMR"],
        "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b2f0e51-9c3a-4a1d-bd77-0f5a1c2e8b44-berg_ckb.jpg",
        "ckbContent": {
          "title": "مێژووی کوردستان — بەرگی یەکەم",
          "writer": "د. عەبدوڵا حەسەن",
          "fileFormat": "PDF",
          "pageCount": 312
        },
        "kmrContent": {
          "title": "Dîroka Kurdistanê — Berga Yekem",
          "writer": "Dr. Ebdulla Hesen",
          "fileFormat": "PDF",
          "pageCount": 298
        },
        "topic": { "id": 7, "nameCkb": "مێژووی هاوچەرخ", "nameKmr": "Dîroka hevçerx" },
        "bookGenres": ["HISTORY", "POLITICS"],
        "publishedByInstitute": true,
        "tags": { "ckb": ["مێژوو"], "kmr": ["dîrok"] },
        "keywords": { "ckb": ["سەدەی بیستەم"], "kmr": ["sedsala bîstan"] },
        "seriesInfo": {
          "seriesId": "series-1758042911233",
          "seriesName": "مێژووی کوردستان",
          "seriesOrder": 1.0,
          "totalBooks": 3,
          "parent": true
        },
        "createdAt": "2026-03-14T09:14:22",
        "updatedAt": "2026-04-02T11:40:07",
        "series": {
          "seriesId": "series-1758042911233",
          "seriesName": "مێژووی کوردستان",
          "seriesOrder": 1.0,
          "totalBooks": 3,
          "parent": true
        },
        "topicId": 7,
        "topicNameCkb": "مێژووی هاوچەرخ",
        "topicNameKmr": "Dîroka hevçerx"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalPages": 3,
    "totalElements": 57,
    "last": false,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "first": true,
    "numberOfElements": 20,
    "size": 20,
    "number": 0,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | `size < 1` or `page < 0` — `PageRequest.of` rejects it and `details.reason` carries the JDK message, e.g. `"Page size must not be less than one"` |
| 500 | `INTERNAL_ERROR` | `page` or `size` is not an integer (`?page=abc`) — see [Notes & gotchas](#notes--gotchas) |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/writings?page=0&size=12"
```

---

## 2. `GET /api/v1/writings/featured` — Books on the homepage carousel

Returns only books whose `featured` flag is set from the admin dashboard, ordered by
`featuredOrder` ascending then `id` descending. A book with no `featuredOrder` sorts last
(PostgreSQL puts `NULL` last on an ascending sort).

This endpoint is separate from the site-wide legacy alias `GET /featured`, which mixes writings with
news, projects, videos, sound tracks and image collections into one carousel payload.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | no | `0` | Zero-based page index. Values below `0` are clamped to `0`. |
| `size` | int | no | `20` | Page size, clamped into `1..100`. |

> **Note:** this is the only paged endpoint in the domain that clamps its inputs instead of
> rejecting them. `?size=0` returns a page of 1; `?size=5000` returns a page of 100; `?page=-4`
> returns page 0. None of those is an error here, while the same values are `400` on endpoints
> 1, 4, 6, 7 and 8.

**Response `200 OK`**

Identical envelope to endpoint 1, with `message` = `"Featured writings fetched successfully"` and
`pageable.sort.sorted` = `true`. Featured books typically carry a `featureImageUrl`:

```json
{
  "success": true,
  "message": "Featured writings fetched successfully",
  "data": {
    "content": [
      {
        "id": 41,
        "contentLanguages": ["CKB", "KMR"],
        "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b2f0e51-9c3a-4a1d-bd77-0f5a1c2e8b44-berg_ckb.jpg",
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8e4f21b0-c7d3-4a09-b8f6-19e5a03c62d1-hero_mejuy_kurdistan.jpg",
        "ckbContent": { "title": "مێژووی کوردستان — بەرگی یەکەم", "writer": "د. عەبدوڵا حەسەن" },
        "kmrContent": { "title": "Dîroka Kurdistanê — Berga Yekem", "writer": "Dr. Ebdulla Hesen" },
        "bookGenres": ["HISTORY", "POLITICS"],
        "publishedByInstitute": true,
        "tags": { "ckb": ["مێژوو"], "kmr": ["dîrok"] },
        "keywords": { "ckb": [], "kmr": [] },
        "createdAt": "2026-03-14T09:14:22",
        "updatedAt": "2026-04-02T11:40:07"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalPages": 1,
    "totalElements": 2,
    "last": true,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "first": true,
    "numberOfElements": 2,
    "size": 20,
    "number": 0,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 500 | `INTERNAL_ERROR` | `page` or `size` is not an integer |

Out-of-range numeric values do not error — they are clamped.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/writings/featured?size=6"
```

---

## 3. `GET /api/v1/writings/{id}` — One book

Loads a single book with its topic, parent book and child volumes join-fetched via an
`@EntityGraph`, and its six element collections (genres, languages, two tag sets, two keyword sets)
batch-loaded 25 at a time. This is the detail-page endpoint.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the book |

**Response `200 OK`**

The full writing object shown in
[Full example of one book](#full-example-of-one-book), with
`message` = `"Writing fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 404 | `WRITING_NOT_FOUND` | No row with that id. `details` = `{ "id": "9999" }`. |
| 500 | `INTERNAL_ERROR` | `{id}` is not a number, e.g. `/api/v1/writings/latest` |

A 404 body looks like this. Note the message text — see the callout under
[Notes & gotchas](#notes--gotchas):

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/writings/9999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "WRITING_NOT_FOUND",
  "message": "Internal error",
  "messageEn": "Internal error",
  "messageKu": "هەڵەی ناوخۆیی",
  "details": { "id": "9999" }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/writings/41
```

---

## 4. `GET /api/v1/writings/series/parents` — Series root books

Returns every book that has a `seriesId` and no `parentBookId` — i.e. the root of each chain. Since
`@PrePersist` guarantees a `seriesId` on every row, this is effectively "every book that is not a
child volume", which includes standalone books.

The public site uses this to build a "Book series" index page; the dashboard uses it to populate the
"link to parent" picker, which is why the default page size is `100` rather than `20`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `100` | Page size. Not clamped. |

Sorted `createdAt DESC`.

**Response `200 OK`**

Same page envelope as endpoint 1, with `message` = `"Series parents fetched"`. Entries whose
`seriesInfo.totalBooks` is greater than 1 are real multi-volume roots:

```json
{
  "success": true,
  "message": "Series parents fetched",
  "data": {
    "content": [
      {
        "id": 41,
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": { "title": "مێژووی کوردستان — بەرگی یەکەم" },
        "kmrContent": { "title": "Dîroka Kurdistanê — Berga Yekem" },
        "bookGenres": ["HISTORY", "POLITICS"],
        "publishedByInstitute": true,
        "tags": { "ckb": ["مێژوو"], "kmr": ["dîrok"] },
        "keywords": { "ckb": [], "kmr": [] },
        "seriesInfo": {
          "seriesId": "series-1758042911233",
          "seriesName": "مێژووی کوردستان",
          "seriesOrder": 1.0,
          "totalBooks": 3,
          "parent": true
        },
        "createdAt": "2026-03-14T09:14:22",
        "updatedAt": "2026-04-02T11:40:07",
        "series": {
          "seriesId": "series-1758042911233",
          "seriesName": "مێژووی کوردستان",
          "seriesOrder": 1.0,
          "totalBooks": 3,
          "parent": true
        }
      },
      {
        "id": 58,
        "contentLanguages": ["CKB"],
        "ckbContent": { "title": "دیوانی نالی" },
        "bookGenres": ["POETRY"],
        "publishedByInstitute": false,
        "tags": { "ckb": ["شیعر"], "kmr": [] },
        "keywords": { "ckb": [], "kmr": [] },
        "seriesInfo": {
          "seriesId": "series-1761330008412",
          "seriesOrder": 1.0,
          "totalBooks": 1,
          "parent": false
        },
        "createdAt": "2026-05-02T08:03:51",
        "updatedAt": "2026-05-02T08:03:51",
        "series": {
          "seriesId": "series-1761330008412",
          "seriesOrder": 1.0,
          "totalBooks": 1,
          "parent": false
        }
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 100,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalPages": 1,
    "totalElements": 2,
    "last": true,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "first": true,
    "numberOfElements": 2,
    "size": 100,
    "number": 0,
    "empty": false
  }
}
```

> **Note:** filter client-side on `seriesInfo.totalBooks > 1` if you only want genuine multi-volume
> series. The endpoint does not do that for you.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | `size < 1` or `page < 0` |
| 500 | `INTERNAL_ERROR` | `page` or `size` is not an integer |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/writings/series/parents?page=0&size=50"
```

---

## 5. `GET /api/v1/writings/series/{seriesId}` — All volumes of one series

Returns every book sharing a `seriesId`, ordered by `seriesOrder` ascending, as a compact summary
list. This is not paginated — a series is expected to be small.

The response `seriesName` is resolved from the **first** book in the ordered list using
`Writing.getEffectiveSeriesName()`: its `seriesName` if set, otherwise its Sorani title, otherwise
its Kurmanji title, otherwise the literal string `"Unknown Series"`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `seriesId` | string | yes | The series key, e.g. `series-1758042911233` |

**Response `200 OK`** — the `data` is a `SeriesResponse`, not a page and not a writing object:

| Field | Type | Description |
|-------|------|-------------|
| `seriesId` | string | Echo of the path variable |
| `seriesName` | string | Resolved as described above |
| `totalBooks` | integer | Number of entries in `books` |
| `books` | array of `SeriesBookSummary` | Ordered by `seriesOrder` ascending |

`SeriesBookSummary`:

| Field | Type | Description |
|-------|------|-------------|
| `id` | integer (int64) | Book id — use it against endpoint 3 for the full record |
| `titleCkb` | string | Sorani title, or absent when the book has no CKB block |
| `titleKmr` | string | Kurmanji title, or absent when the book has no KMR block |
| `seriesOrder` | number (double) | Position in the series |
| `createdAt` | string date-time | When the book was created |

```json
{
  "success": true,
  "message": "Series books fetched",
  "data": {
    "seriesId": "series-1758042911233",
    "seriesName": "مێژووی کوردستان",
    "totalBooks": 3,
    "books": [
      {
        "id": 41,
        "titleCkb": "مێژووی کوردستان — بەرگی یەکەم",
        "titleKmr": "Dîroka Kurdistanê — Berga Yekem",
        "seriesOrder": 1.0,
        "createdAt": "2026-03-14T09:14:22"
      },
      {
        "id": 42,
        "titleCkb": "مێژووی کوردستان — بەرگی دووەم",
        "titleKmr": "Dîroka Kurdistanê — Berga Duyem",
        "seriesOrder": 2.0,
        "createdAt": "2026-03-21T14:02:10"
      },
      {
        "id": 57,
        "titleCkb": "مێژووی کوردستان — بەرگی سێیەم",
        "titleKmr": "Dîroka Kurdistanê — Berga Sêyem",
        "seriesOrder": 3.0,
        "createdAt": "2026-06-09T16:55:44"
      }
    ]
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 404 | `NOT_FOUND` | No book carries that `seriesId`. `details` = `{ "seriesId": "series-000" }`. |

Note the code here is the generic `NOT_FOUND`, not `WRITING_NOT_FOUND`.

**Example**

```bash
curl -s http://localhost:8080/api/v1/writings/series/series-1758042911233
```

---

## 6. `GET /api/v1/writings/search/writer` — Search by author

Case-insensitive substring match on the `writer` column of the chosen language block(s). Backed by
the `idx_writer_ckb` / `idx_writer_kmr` indexes; the both-languages variant is a simple `OR` of two
indexed column checks, with no collection join and no `DISTINCT`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `name` | string | **yes** | — | Author name fragment. Trimmed before matching. |
| `language` | string | no | `both` | `ckb` searches `ckbContent.writer`; `kmr` searches `kmrContent.writer`; **any other value**, including the default `both`, searches both. Case-insensitive. |
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `20` | Page size. Not clamped. |

Sorted `createdAt DESC`.

> **Note:** `language` is not validated. `language=english`, `language=xx` and `language=both` all
> behave identically — they fall through to the both-languages query. Only the literal strings
> `ckb` and `kmr` (any casing) narrow the search.

**Response `200 OK`**

The standard page envelope from endpoint 1, with `message` = `"Search completed"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `name` omitted entirely. `details` = `{ "missingParameter": "name", "expectedType": "String", "hint": "..." }`. |
| 400 | `BAD_REQUEST` | `name` present but blank (`?name=` or `?name=%20`). `details` = `{ "field": "writerName" }`. |
| 400 | `BAD_REQUEST` | `size < 1` or `page < 0` |
| 500 | `INTERNAL_ERROR` | `page` or `size` is not an integer |

A zero-result search is `200` with `content: []`, not a 404.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/writings/search/writer?name=%D8%AD%DB%95%D8%B3%DB%95%D9%86&language=ckb"
curl -s "http://localhost:8080/api/v1/writings/search/writer?name=Hesen&language=kmr&size=10"
```

---

## 7. `GET /api/v1/writings/search/tag` — Search by tag

Case-insensitive substring match against the `writing_tags_ckb` and/or `writing_tags_kmr`
collection tables. The query uses `EXISTS` sub-queries rather than a `LEFT JOIN … DISTINCT`, so
searching both languages does not produce a cross-product.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `tag` | string | **yes** | — | Tag fragment. Trimmed before matching. |
| `language` | string | no | `both` | `ckb` → CKB tags only; `kmr` → KMR tags only; anything else → both |
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `20` | Page size. Not clamped. |

Sorted `createdAt DESC`. Matching is substring, not exact: `tag=مێژ` matches the tag `مێژوو`.

**Response `200 OK`**

The standard page envelope, `message` = `"Search completed"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `tag` omitted |
| 400 | `BAD_REQUEST` | `tag` present but blank. `details` = `{ "field": "tag" }`. |
| 400 | `BAD_REQUEST` | `size < 1` or `page < 0` |
| 500 | `INTERNAL_ERROR` | `page` or `size` is not an integer |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/writings/search/tag?tag=dîrok&language=kmr"
```

---

## 8. `GET /api/v1/writings/search/keyword` — Search by keyword

Identical mechanics to endpoint 7, against `writing_keywords_ckb` / `writing_keywords_kmr`.
Keywords are the SEO-oriented set; tags are the display-oriented set. They are stored and searched
independently — a term in one is not found by a search of the other.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `keyword` | string | **yes** | — | Keyword fragment. Trimmed before matching. |
| `language` | string | no | `both` | `ckb` → CKB keywords only; `kmr` → KMR keywords only; anything else → both |
| `page` | int | no | `0` | Zero-based page index |
| `size` | int | no | `20` | Page size. Not clamped. |

Sorted `createdAt DESC`.

**Response `200 OK`**

The standard page envelope, `message` = `"Search completed"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| 400 | `MISSING_PARAMETER` | `keyword` omitted |
| 400 | `BAD_REQUEST` | `keyword` present but blank. `details` = `{ "field": "keyword" }`. |
| 400 | `BAD_REQUEST` | `size < 1` or `page < 0` |
| 500 | `INTERNAL_ERROR` | `page` or `size` is not an integer |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/writings/search/keyword?keyword=%D8%B4%DB%86%DA%95%D8%B4"
```

---

## 9. `GET /api/v1/writings/topics` — The writing topic registry

Returns every `PublishmentTopic` row whose `entityType` is `"WRITING"`. Topics are a dynamic,
editor-managed taxonomy stored in the shared `publishment_topics` table — each publication module
(`VIDEO`, `SOUND`, `IMAGE`, `WRITING`) has its own set and they never mix. Use this to build the
topic filter chips on the books index page, then filter results client-side on `topicId`.

**Auth:** None (public)
**Content-Type:** `application/json`

No parameters. Not paginated, not sorted — rows come back in whatever order PostgreSQL returns them.

**Response `200 OK`** — `data` is a flat array of objects with exactly three keys:

| Field | Type | Description |
|-------|------|-------------|
| `id` | integer (int64) | Topic id, matching `topic.id` / `topicId` on a writing |
| `nameCkb` | string | Sorani name. Empty string `""` when the topic has no Sorani name — never null. |
| `nameKmr` | string | Kurmanji name. Empty string `""` when unset — never null. |

```json
{
  "success": true,
  "message": "WRITING topics fetched",
  "data": [
    { "id": 7, "nameCkb": "مێژووی هاوچەرخ", "nameKmr": "Dîroka hevçerx" },
    { "id": 9, "nameCkb": "ئەدەبی کوردی", "nameKmr": "Wêjeya kurdî" },
    { "id": 14, "nameCkb": "زمانناسی", "nameKmr": "" }
  ]
}
```

> **Note:** this response deliberately uses `""` where the writing object's `TopicInfo` would omit
> the key. The controller substitutes an empty string because `Map.of(...)` cannot hold nulls. Do
> not treat `""` here and a missing `nameKmr` on a writing as different states.

**Errors**

This endpoint has no failure path of its own. An empty registry returns `data: []`.

**Example**

```bash
curl -s http://localhost:8080/api/v1/writings/topics
```

---

## Enums used by this API

### `Language`

Used in `contentLanguages`.

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish / Sorani |
| `KMR` | Northern Kurdish / Kurmanji |

### Book genres (`bookGenres` values)

> **No longer an enum in responses.** Since 2026-09-03 these are editor-managed rows read from
> `GET /api/v1/book-genres` ([`../BOOK_GENRES.md`](../BOOK_GENRES.md)); editors can add, rename,
> re-order and hide them. The table below is the seeded starting set — its slugs equal the old
> enum codes, so existing clients see identical values until an editor changes something.

The machine-readable, multi-valued classification returned in `bookGenres`. A book carries a set,
not a single value — a historical novel is `["HISTORY", "NOVEL"]`.

| Value | Sorani | Meaning |
|-------|--------|---------|
| `POETRY` | شیعر | Poetry collections |
| `NOVEL` | ڕۆمان | Novels and long-form fiction |
| `SHORT_STORY` | چیرۆکی کورت | Short stories and novellas |
| `DRAMA` | شانۆ | Plays and dramatic works |
| `HISTORY` | مێژوو | Historical works |
| `BIOGRAPHY` | ژیاننامە | Biographies and memoirs |
| `PHILOSOPHY` | فەلسەفە | Philosophy |
| `RELIGION` | ئایین | Religious and theological texts |
| `FOLKLORE` | زارگوتن | Folklore, oral tradition, mythology |
| `POLITICS` | سیاسەت | Political science and theory |
| `SOCIOLOGY` | کۆمەڵناسی | Sociology and social studies |
| `ECONOMICS` | ئابووری | Economics and finance |
| `LAW` | یاسا | Law and legal studies |
| `LINGUISTICS` | زمانناسی | Linguistics and language studies |
| `ARTS` | هونەر | Visual arts, music, crafts |
| `CULTURAL` | کولتووری | Cultural studies and heritage |
| `SCIENCE` | زانست | Natural and applied sciences |
| `MEDICINE` | پزیشکی | Medical and health sciences |
| `EDUCATIONAL` | پەروەردەیی | Textbooks and academic works |
| `CHILDREN` | منداڵان | Children's books |
| `TRAVEL` | گەشتوگوزار | Travel and geography |
| `OTHER` | یتر | Uncategorised |

The legacy Java enum's alias constants — `ESSAY`, `POLITICAL` and `ACADEMIC` — were folded into
the canonical rows by the one-time migration (`POLITICAL → POLITICS`, `ACADEMIC → EDUCATIONAL`,
`ESSAY → OTHER`), so **they can never appear in a response**. A client only ever needs to handle
the slugs the genre endpoint returns — which start as the 22 values in the first table.

> **Note:** do not confuse `bookGenres` (machine-readable slugs, on the book) with
> `ckbContent.genre` / `kmrContent.genre` (free text, per language, editor-typed, display only).
> They are unrelated fields and are not kept in sync.

### `WritingFileFormat`

The format of the downloadable file in `ckbContent.fileFormat` / `kmrContent.fileFormat`.

| Value | Meaning |
|-------|---------|
| `PDF` | PDF document |
| `DOCX` | Microsoft Word, modern |
| `DOC` | Microsoft Word, legacy |
| `TXT` | Plain text |
| `EPUB` | E-book |
| `ODT` | OpenDocument Text |
| `RTF` | Rich Text Format |
| `HTML` | HTML document |
| `OTHER` | Anything else |

---

## Notes & gotchas

**Downloading a book file.** `fileUrl` points straight at the S3 object
(`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/<uuid>-<filename>`). There
is no download proxy, no signed URL and no download counter on this API — link to it directly. The
file name in the URL is the editor's original file name with every character outside
`[A-Za-z0-9._-]` replaced by `_`, prefixed with a UUID.

**Which cover to show.** There are three cover slots plus a hero. The entity's
`getAnyCoverUrl()` helper falls back `ckbCoverUrl → kmrCoverUrl → hoverCoverUrl`, but that helper is
*not* exposed on the API. Implement the same fallback client-side, and prefer the cover matching the
user's active locale.

**`404` bodies carry an unhelpful message.** `WRITING_NOT_FOUND` has no entry in
`messages_en.properties` and no case in the exception handler's `fallbackByCode` switch, so the
`message` / `messageEn` fields read `"Internal error"` and `messageKu` reads `"هەڵەی ناوخۆیی"` even
though the HTTP status is genuinely `404`. **Branch on `status` and `code`, never on `message`.**
The same applies to `series.not_found`, which returns `"Resource not found"` because the generic
`NOT_FOUND` case *does* exist in the switch.

**A non-numeric `{id}` is a 500, not a 404.** `GET /api/v1/writings/latest` binds `latest` to a
`Long` path variable, Spring raises `MethodArgumentTypeMismatchException`, and the project's
`@RestControllerAdvice` does not extend `ResponseEntityExceptionHandler` — so it falls through to
the catch-all `Exception` handler and returns `500 INTERNAL_ERROR`. Validate ids client-side. The
same applies to non-numeric `page` / `size`.

**No caching.** `WritingService` carries no `@Cacheable` or `@CacheEvict` annotations. Redis is
configured for the application (`khi:` key prefix, 10-minute default TTL) but this domain does not
use it — every request hits PostgreSQL. Cache on the CDN or in the browser if you need to.

**Ordering inside `tags` / `keywords` / `contentLanguages`.** These are backed by
`LinkedHashSet`, so ordering is stable per record but arbitrary in meaning. `bookGenres` and
`genres` are the exception since the genre migration: both follow the genres' `displayOrder`
(lowest first). Do not read editorial meaning into `bookGenres[0]` — it is the earliest-ordered
chip, not "the primary genre".

**`totalBooks` can lag.** `seriesTotalBooks` is denormalised. It is re-stamped on every book in a
series on create, update, delete and series-link, but the series-link path only recounts the
*destination* series — moving a book out of series A into series B leaves A's `totalBooks` one too
high until something else touches A. Prefer `SeriesResponse.totalBooks` from endpoint 5, which is a
live `books.size()`, when the number has to be right.

**Deleting a parent orphans its children in place.** Child volumes keep their `seriesId` and
`seriesOrder` but have `parentBookId` cleared, which makes every one of them satisfy
`isSeriesParent()`. A series can therefore report several roots. Endpoint 5 is unaffected — it keys
purely on `seriesId`.

**Live spec.** Swagger UI is at `/swagger-ui.html`; the raw OpenAPI JSON is at `/v3/api-docs`.
The `public` group includes `/api/v1/writings/**`; the `internal` and `all` groups cover the
authenticated side as well.

**Servers.** `http://localhost:8080` for local development; production runs on Railway.

---

## Related documentation

- Counterpart: [`../internal/WRITING_API.md`](../internal/WRITING_API.md) — create, update, delete,
  series linking and the featured toggle
- Sibling publication domains: [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md), [`VIDEO_API.md`](VIDEO_API.md),
  [`NEWS_API.md`](NEWS_API.md), [`PROJECT_API.md`](PROJECT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
