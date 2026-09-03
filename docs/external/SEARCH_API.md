# Global Search API — External (Public)

The global search endpoint is the single entry point behind the site-wide search box on the KHI
public website. One request fans out across all six content models — projects, news, videos,
writings, sound tracks and image collections — and returns a separate paginated section for each,
in a shape lightweight enough to render a results list without any follow-up calls. It is
reachable by an anonymous visitor with no token.

| | |
|---|---|
| **Base path** | `/api/v1/search` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/search/GlobalSearchController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/search/GlobalSearchService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/search/GlobalSearchResponse.java`, `src/main/java/ak/dev/khi_backend/khi_app/dto/search/SearchItem.java` |
| **Repositories** | `ProjectRepository`, `NewsRepository`, `VideoRepository`, `WritingRepository`, `SoundTrackRepository`, `ImageCollectionRepository` (each `findIdsByGlobalSearch` + `findAllByIds`) |
| **Entities** | `Project`, `ProjectContentBlock`, `News`, `NewsContent`, `Video`, `VideoContent`, `Writing`, `WritingContent`, `SoundTrack`, `SoundTrackContent`, `ImageCollection`, `ImageContent`, `PublishmentTopic` |
| **Verified against source** | 2026-08-26 |

Why this is public: `SecurityConfig` matches `GET /api/v1/**` with `permitAll()` and no earlier rule
covers `/api/v1/search`. The handler carries no `@PreAuthorize`. There is no admin counterpart —
this domain is read-only and has no internal file.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/search` | None | — | Free-text search across all six content types at once, one paginated section per type |

---

## 1. `GET /api/v1/search` — Search everything

Runs a case-insensitive substring (`LIKE '%q%'`) search over the titles, descriptions, tags and
keywords of every content model, plus a few model-specific fields (director, writer, album name,
topic names, collector, location). Results are grouped into one section per content type; each
section is paginated independently using the same `page` / `size` values.

The endpoint is a pure read — no counters, no audit rows, no cache writes.

**Auth:** None (public)
**Content-Type:** not applicable (no request body)
**Produces:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `q` | string | Yes | — | The search term. Must be **present**, but may be empty (`?q=`). Leading/trailing whitespace is trimmed server-side before matching. Matching is case-insensitive substring matching — no stemming, no fuzzy matching, no wildcards to escape. |
| `type` | string | No | `ALL` | Restricts the search to one content type. Accepted values: `ALL`, `PROJECT`, `NEWS`, `VIDEO`, `WRITING`, `SOUNDTRACK`, `IMAGE`. Trimmed and upper-cased server-side, so `news` and `News` both work. An unrecognised value is not rejected — see [Notes & gotchas](#notes--gotchas). |
| `page` | int | No | `0` | 0-based page index. Applied to **every** section in the same request. Must be `>= 0`. |
| `size` | int | No | `10` | Items per page **per section** — not per response. With `type=ALL` and `size=10` a single response can carry up to 60 items. Must be `>= 1`. There is no server-side upper bound. |
| `locale` | string | No | — | Accepted by the controller and then discarded — it is never passed to the service and has no effect on the response. See the note under [Notes & gotchas](#notes--gotchas). |
| `lang` | string | No | — | Not specific to this endpoint. A platform-wide `LocaleChangeInterceptor` reads `lang` (`en`, `ckb`, `kmr`) and sets the request locale, which only affects the `message` field of error bodies. It does not filter or translate search results. |

**Request body**

None.

**Response `200 OK`**

Envelope: `ApiResponse<GlobalSearchResponse>` — `{ "success": true, "message": "Search completed", "data": { ... } }`.

```json
{
  "success": true,
  "message": "Search completed",
  "data": {
    "query": "کوردستان",
    "page": 0,
    "size": 10,
    "type": "ALL",
    "projects": {
      "items": [
        {
          "id": 31,
          "type": "PROJECT",
          "titleCkb": "پرۆژەی پاراستنی کەلەپووری کوردستان",
          "titleKmr": "Projeya parastina mîrateya Kurdistanê",
          "descriptionCkb": "پرۆژەیەکی سێ ساڵەیە بۆ تۆمارکردن و پاراستنی کەلەپووری زارەکی لە هەرێمی کوردستان.",
          "descriptionKmr": "Projeyeke sê salî ji bo tomarkirin û parastina mîrateya devkî li Herêma Kurdistanê.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/7d3f5b12-9c44-4a81-b0e7-2f6a1c8d9e10-heritage-project.jpg",
          "createdAt": "2026-05-14T11:02:38"
        }
      ],
      "totalElements": 12,
      "totalPages": 2,
      "currentPage": 0,
      "size": 10
    },
    "news": {
      "items": [
        {
          "id": 208,
          "type": "NEWS",
          "titleCkb": "کۆنفرانسی نێودەوڵەتی زمانی کوردی لە هەولێر دەستیپێکرد",
          "titleKmr": "Konferansa navneteweyî ya zimanê kurdî li Hewlêrê dest pê kir",
          "descriptionCkb": "زیاتر لە ٤٠ توێژەر لە شەش وڵاتەوە بەشداری کۆنفرانسەکە دەکەن کە سێ ڕۆژ بەردەوام دەبێت.",
          "descriptionKmr": "Zêdetirî 40 lêkolîner ji şeş welatan beşdarî konferansê dibin ku sê rojan berdewam dike.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a41c88f0-6b2d-4e19-9a53-0c7e5d114b83-conference-hewler.jpg",
          "createdAt": "2026-08-11T08:15:44"
        }
      ],
      "totalElements": 45,
      "totalPages": 5,
      "currentPage": 0,
      "size": 10
    },
    "videos": {
      "items": [
        {
          "id": 77,
          "type": "VIDEO",
          "titleCkb": "بەڵگەنامەی ڕێگای ئاوریشم لە کوردستان",
          "titleKmr": "Belgefîlma Riya Hevrîşimê li Kurdistanê",
          "descriptionCkb": "بەڵگەنامەیەکی ٥٢ خولەکی دەربارەی ڕێگا بازرگانییە مێژووییەکان.",
          "descriptionKmr": "Belgefîlmeke 52 deqeyî li ser riyên bazirganiyê yên dîrokî.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2c9d4a61-13f8-4f57-8f10-6b9e0a2d7f45-silk-road-cover.jpg",
          "createdAt": "2026-04-02T13:47:19"
        }
      ],
      "totalElements": 5,
      "totalPages": 1,
      "currentPage": 0,
      "size": 10
    },
    "writings": {
      "items": [
        {
          "id": 19,
          "type": "WRITING",
          "titleCkb": "مێژووی ڕۆژنامەگەری لە کوردستان",
          "titleKmr": "Dîroka rojnamegeriyê li Kurdistanê",
          "descriptionCkb": "توێژینەوەیەک لەسەر یەکەم ڕۆژنامەکانی کوردی و کاریگەرییان.",
          "descriptionKmr": "Lêkolînek li ser rojnameyên kurdî yên yekem û bandora wan.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/58e2b7c3-4d90-41aa-9c62-3f1d8e5a06b7-press-history.jpg",
          "createdAt": "2026-03-21T16:30:05"
        }
      ],
      "totalElements": 8,
      "totalPages": 1,
      "currentPage": 0,
      "size": 10
    },
    "soundTracks": {
      "items": [
        {
          "id": 142,
          "type": "SOUNDTRACK",
          "titleCkb": "هاواری کوردستان",
          "titleKmr": "Hawara Kurdistanê",
          "descriptionCkb": "تۆمارێکی مێژوویی لە ساڵی ١٩٧٨ لە دهۆک تۆمارکراوە.",
          "descriptionKmr": "Tomareke dîrokî ya sala 1978'an li Dihokê hatiye tomarkirin.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/e07f9d2a-5c31-4b08-83f6-1a4c7e9b2d50-hawar-cover.jpg",
          "createdAt": "2026-01-09T10:12:51"
        }
      ],
      "totalElements": 3,
      "totalPages": 1,
      "currentPage": 0,
      "size": 10
    },
    "imageCollections": {
      "items": [
        {
          "id": 64,
          "type": "IMAGE",
          "titleCkb": "وێنەکانی کۆنی شاری سلێمانی",
          "titleKmr": "Wêneyên kevn ên bajarê Silêmaniyê",
          "descriptionCkb": "٨٤ وێنەی مێژوویی لە نێوان ١٩٢٠ و ١٩٦٠.",
          "descriptionKmr": "84 wêneyên dîrokî di navbera 1920 û 1960'î de.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9b31c604-7f28-4d13-a9e5-6c0b2f8a41de-slemani-archive.jpg",
          "createdAt": "2026-02-27T09:05:12"
        }
      ],
      "totalElements": 7,
      "totalPages": 1,
      "currentPage": 0,
      "size": 10
    }
  }
}
```

### `data` — `GlobalSearchResponse`

| Field | Type | Description |
|-------|------|-------------|
| `query` | string | The trimmed search term the server actually used. Echo it back into the UI rather than the raw input. |
| `page` | int | The 0-based page index that was requested. |
| `size` | int | The per-section page size that was requested. |
| `type` | string | The scope, trimmed and upper-cased: `ALL`, `PROJECT`, `NEWS`, `VIDEO`, `WRITING`, `SOUNDTRACK`, `IMAGE` — or whatever unrecognised value the client sent, echoed verbatim in upper case. |
| `projects` | `SearchSection` | Project hits. `null` when `type` is not `ALL` or `PROJECT`. |
| `news` | `SearchSection` | News hits. `null` when `type` is not `ALL` or `NEWS`. |
| `videos` | `SearchSection` | Video hits. `null` when `type` is not `ALL` or `VIDEO`. |
| `writings` | `SearchSection` | Writing hits. `null` when `type` is not `ALL` or `WRITING`. |
| `soundTracks` | `SearchSection` | Sound-track hits. `null` when `type` is not `ALL` or `SOUNDTRACK`. |
| `imageCollections` | `SearchSection` | Image-collection hits. `null` when `type` is not `ALL` or `IMAGE`. |

Treat a `null` section and an absent section identically: check the section object exists before
reading `.items`.

### `SearchSection`

| Field | Type | Description |
|-------|------|-------------|
| `items` | `SearchItem[]` | The hits on this page for this content type. Never `null` — an empty array when there are none. |
| `totalElements` | long | Total matching records of this type for this query, across all pages. |
| `totalPages` | int | `ceil(totalElements / size)`. |
| `currentPage` | int | Echo of the requested `page`. |
| `size` | int | Echo of the requested `size`. |

### `SearchItem`

Deliberately flat and small — everything a result card needs and nothing more. There are no tags,
no gallery, no body HTML; fetch the detail endpoint when the visitor clicks through.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | long | No | Primary key of the underlying entity. Combine with `type` to build the detail link. |
| `type` | string | No | Discriminator: `PROJECT`, `NEWS`, `VIDEO`, `WRITING`, `SOUNDTRACK`, `IMAGE`. Note `IMAGE` (not `IMAGE_COLLECTION`) and `SOUNDTRACK` (not `SOUND_TRACK`). |
| `titleCkb` | string | No | Sorani title. Empty string `""` when the entity has no CKB content — never `null`. |
| `titleKmr` | string | No | Kurmanji title. Empty string `""` when absent. |
| `descriptionCkb` | string | No | Sorani snippet: the Tiptap HTML description with all tags stripped, whitespace collapsed, truncated to 200 characters plus a `…` ellipsis. Empty string when absent or when the HTML contained no text. |
| `descriptionKmr` | string | No | Kurmanji snippet, same rules. |
| `coverUrl` | string | Yes | Cover image URL, or `null` when the entity has no cover. Projects and news use their single `coverUrl`; videos, writings, sound tracks and image collections prefer `ckbCoverUrl` and fall back to `kmrCoverUrl`. The `hoverCoverUrl` variants are never used here. |
| `createdAt` | date-time | Yes | Entity creation timestamp, ISO-8601 local date-time with no offset, e.g. `2026-08-11T08:15:44`. Stored in the database as UTC. Useful for a "published X days ago" label. It is **not** the publication date — see [Notes & gotchas](#notes--gotchas). |

---

## What each content type searches

Every clause is `lower(column) LIKE lower('%q%')`, OR-ed together, with `DISTINCT`/`GROUP BY` so an
entity that matches through several tags appears once.

| Section | Entity | Fields matched |
|---------|--------|----------------|
| `projects` | `Project` | `ckbContent.title`, `kmrContent.title`, `ckbContent.description`, `kmrContent.description`, tag names (CKB + KMR), keyword names (CKB + KMR) |
| `news` | `News` | `ckbContent.title`, `kmrContent.title`, `ckbContent.description`, `kmrContent.description`, tags (CKB + KMR), keywords (CKB + KMR) |
| `videos` | `Video` | `ckbContent.title`, `kmrContent.title`, `ckbContent.description`, `kmrContent.description`, `ckbContent.director`, `kmrContent.director`, tags (CKB + KMR), keywords (CKB + KMR) |
| `writings` | `Writing` | `ckbContent.title`, `kmrContent.title`, `ckbContent.description`, `kmrContent.description`, `ckbContent.writer`, `kmrContent.writer`, tags (CKB + KMR), keywords (CKB + KMR) |
| `soundTracks` | `SoundTrack` | `ckbContent.title`, `kmrContent.title`, `ckbContent.description`, `kmrContent.description`, `albumName`, `terms`, tags (CKB + KMR), keywords (CKB + KMR), `topic.nameCkb`, `topic.nameKmr` |
| `imageCollections` | `ImageCollection` | `ckbContent.title`, `kmrContent.title`, `ckbContent.description`, `kmrContent.description`, `ckbContent.collectedBy`, `kmrContent.collectedBy`, `ckbContent.location`, `kmrContent.location`, tags (CKB + KMR), keywords (CKB + KMR), `topic.nameCkb`, `topic.nameKmr` |

Not searched anywhere: slugs, categories/subcategories (news), album entities, contributors,
readers, file names, and any field on About, Contact, Service, Team, Partner or Donation. Descriptions
are matched as raw HTML, so a term that happens to sit inside a tag name or an attribute — `img`,
`href`, `amazonaws` — will produce spurious hits.

## Ranking and ordering

There is no relevance ranking. Nothing is scored, and a title hit does not outrank a tag hit.
Each section is independently ordered by its own recency rule:

| Section | Order |
|---------|-------|
| `projects` | `id` descending (newest-created first, by insertion order) |
| `news` | `datePublished` descending, then `createdAt` descending |
| `videos` | `id` descending |
| `writings` | `id` descending |
| `soundTracks` | `createdAt` descending |
| `imageCollections` | `publishmentDate` descending, then `createdAt` descending |

Sections always appear in the same order in the JSON object (`projects`, `news`, `videos`,
`writings`, `soundTracks`, `imageCollections`); that order carries no meaning. If you want one
merged list in the UI, concatenate the sections yourself and sort client-side — `createdAt` is on
every item for exactly that purpose.

## Pagination

`page` and `size` apply to each section separately, which has two consequences worth designing for:

- **A single page index drives six paginators.** Asking for `page=1` moves every section forward
  one page at once. Sections with only one page of results return an empty `items` array at
  `page=1` while other sections still have data. For a per-section "load more" control, send a
  separate request with `type=<that type>` and its own `page`.
- **`size` multiplies.** `size=50` with `type=ALL` can return 300 items and six count queries over
  six join graphs. Keep `size` small for the "all types" preview and raise it only on a
  type-scoped request.

There is no cap on `size` and no cap on `page`. `size=0` or a negative `page` is rejected with
`400 BAD_REQUEST` (`PageRequest` refuses them before any query runs).

---

## Errors

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `q` was not sent at all. `details.missingParameter` is `"q"`. Sending `q=` (empty) is legal and matches everything. |
| `400` | `BAD_REQUEST` | `page` is negative, or `size` is `0` or negative — `PageRequest.of` throws `IllegalArgumentException`. `details.reason` carries the reason, e.g. `Page index must not be less than zero`. |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `GET` on `/api/v1/search`. Note that a non-`GET` request from an anonymous client is rejected by `SecurityConfig` first (`anyRequest().authenticated()`) and never reaches the 405. |
| `500` | `INTERNAL_ERROR` | Database failure. Quote `traceId` when reporting it. |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer (`?size=abc`). See the note below. |

> **Note:** A non-numeric `page` / `size` raises `MethodArgumentTypeMismatchException`, which no
> handler in `GlobalExceptionHandler` covers, so the catch-all `@ExceptionHandler(Exception.class)`
> converts Spring MVC's normal `400` into a `500 INTERNAL_ERROR`. Coerce both values to integers
> client-side rather than relying on the server to reject bad input cleanly.

Error bodies are the standard `ApiErrorResponse`:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/search",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "MISSING_PARAMETER",
  "message": "Required parameter 'q' is missing.",
  "messageEn": "Required parameter 'q' is missing.",
  "messageKu": "پارامیتەری پێویستی 'q' نەگەیشتووە.",
  "fieldErrors": null,
  "details": {
    "missingParameter": "q",
    "expectedType": "String",
    "hint": "Append '?q=<value>' to your request URL."
  }
}
```

---

## Worked examples

### Example 1 — Search everything (the site-wide search box)

Default scope, default paging: 10 hits per content type, up to 60 items.

```bash
curl -s -G http://localhost:8080/api/v1/search \
  --data-urlencode "q=کوردستان"
```

Equivalent to `?q=کوردستان&type=ALL&page=0&size=10`. Response: the full six-section body shown
above.

### Example 2 — Second page of news only

Scope the query to one type so its paginator is independent of the others.

```bash
curl -s -G http://localhost:8080/api/v1/search \
  --data-urlencode "q=کۆنفرانس" \
  --data-urlencode "type=NEWS" \
  --data-urlencode "page=1" \
  --data-urlencode "size=10"
```

```json
{
  "success": true,
  "message": "Search completed",
  "data": {
    "query": "کۆنفرانس",
    "page": 1,
    "size": 10,
    "type": "NEWS",
    "projects": null,
    "news": {
      "items": [
        {
          "id": 191,
          "type": "NEWS",
          "titleCkb": "کۆنفرانسی ساڵانەی مێژووی کوردی لە دهۆک",
          "titleKmr": "Konferansa salane ya dîroka kurdî li Dihokê",
          "descriptionCkb": "بەشداربووان باس لە سەرچاوە نوێیەکانی مێژووی سەدەی بیستەم دەکەن.",
          "descriptionKmr": "Beşdar li ser çavkaniyên nû yên dîroka sedsala bîstan diaxivin.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6f2a90d5-8e14-4c77-b3a1-5d0e9c7b2846-dihok-conference.jpg",
          "createdAt": "2026-06-30T14:22:09"
        }
      ],
      "totalElements": 14,
      "totalPages": 2,
      "currentPage": 1,
      "size": 10
    },
    "videos": null,
    "writings": null,
    "soundTracks": null,
    "imageCollections": null
  }
}
```

### Example 3 — Sound tracks, larger page

Matches the archive's audio catalogue on title, description, album name, `terms`, tags, keywords
and topic name.

```bash
curl -s -G http://localhost:8080/api/v1/search \
  --data-urlencode "q=هاوار" \
  --data-urlencode "type=SOUNDTRACK" \
  --data-urlencode "page=0" \
  --data-urlencode "size=20"
```

```json
{
  "success": true,
  "message": "Search completed",
  "data": {
    "query": "هاوار",
    "page": 0,
    "size": 20,
    "type": "SOUNDTRACK",
    "projects": null,
    "news": null,
    "videos": null,
    "writings": null,
    "soundTracks": {
      "items": [
        {
          "id": 142,
          "type": "SOUNDTRACK",
          "titleCkb": "هاواری کوردستان",
          "titleKmr": "Hawara Kurdistanê",
          "descriptionCkb": "تۆمارێکی مێژوویی لە ساڵی ١٩٧٨ لە دهۆک تۆمارکراوە.",
          "descriptionKmr": "Tomareke dîrokî ya sala 1978'an li Dihokê hatiye tomarkirin.",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/e07f9d2a-5c31-4b08-83f6-1a4c7e9b2d50-hawar-cover.jpg",
          "createdAt": "2026-01-09T10:12:51"
        },
        {
          "id": 138,
          "type": "SOUNDTRACK",
          "titleCkb": "گۆرانی هاوار — تۆماری ژیانی",
          "titleKmr": "Stranên Hawar — Tomara jiyanî",
          "descriptionCkb": "",
          "descriptionKmr": "",
          "coverUrl": null,
          "createdAt": "2025-12-18T17:40:33"
        }
      ],
      "totalElements": 2,
      "totalPages": 1,
      "currentPage": 0,
      "size": 20
    },
    "imageCollections": null
  }
}
```

Note the second item: an entity with no cover and no descriptions still returns — `coverUrl` is
`null`, the description fields are empty strings, and the title fields are never `null`.

### Example 4 — Browse everything (empty query)

`q` must be present but may be empty. An empty term becomes `LIKE '%%'`, which matches every row
that has at least one non-null searched column, so this behaves as "list the newest of everything".

```bash
curl -s "http://localhost:8080/api/v1/search?q=&type=IMAGE&page=0&size=12"
```

Handy for a first-open state on the search page, but the per-domain list endpoints
(`GET /api/v1/image-collections?page=0&size=12`) are cheaper and return the full DTO.

---

## Building links from results

`SearchItem` carries `id` and `type` and nothing route-shaped — no slug, no path. Map the
discriminator to a detail endpoint yourself:

| `type` | Detail endpoint | Public docs |
|--------|-----------------|-------------|
| `PROJECT` | `GET /api/v1/projects/{id}` | [`PROJECT_API.md`](PROJECT_API.md) |
| `NEWS` | `GET /api/v1/news/{id}` | [`NEWS_API.md`](NEWS_API.md) |
| `VIDEO` | `GET /api/v1/videos/{id}` | [`VIDEO_API.md`](VIDEO_API.md) |
| `WRITING` | `GET /api/v1/writings/{id}` | [`WRITING_API.md`](WRITING_API.md) |
| `SOUNDTRACK` | `GET /api/v1/sound-tracks/{id}` | [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md) |
| `IMAGE` | `GET /api/v1/image-collections/{id}` — a slug route also exists: `GET /api/v1/image-collections/slug/{slug}` | [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md) |

Image collections are the only type whose slug is exposed as a route, and the search response does
not carry it, so link by `id` and let the detail response supply `slugCkb` / `slugKmr` if you want
to rewrite the URL afterwards.

---

## Global search versus per-domain search

Use `/api/v1/search` for the header search box, a search-results page that spans the whole site, or
any "did you mean" preview. Use a per-domain endpoint when the visitor is already inside one section
and you need that domain's full DTO, its own filters, or a paginator that only that section drives.

| Domain | Per-domain endpoints | What global search cannot do |
|--------|----------------------|------------------------------|
| News | `GET /api/v1/news/search?keyword=&q=&page=&size=`, `/search/keyword`, `/search/tag`, `/search/category`, `/search/subcategory` | Filter by category or subcategory, or restrict a tag/keyword search to one language (`language=ckb|kmr|both`). Per-domain search returns the full `NewsDto` including gallery, categories and body HTML. |
| Projects | `GET /api/v1/projects/search/tag?tag=`, `/search/keyword?keyword=` | Match tags or keywords in isolation. Per-domain returns full `ProjectResponse`. |
| Videos | `GET /api/v1/videos/search/tag?value=`, `/search/keyword?value=` | Return the full `VideoDTO` (sources, clips, durations). Note these use the parameter name `value`, not `tag`/`keyword`, and are **not** wrapped in `ApiResponse` — they return a bare Spring `Page`. |
| Writings | `GET /api/v1/writings/search/writer?name=`, `/search/tag?tag=`, `/search/keyword?keyword=` | Search by writer name alone, or restrict to one language. |
| Sound tracks | `GET /api/v1/sound-tracks/search?q=`, `/search/tag?tag=`, `/search/keyword?keyword=` | Return the full track DTO with audio files, readers and directors. The per-domain `/search` covers the same columns as the global one but returns `Page<Response>`. |
| Image collections | none | Global search is the **only** search path for image collections — the controller exposes no `/search*` route. |

Two shape differences to plan for: the per-domain endpoints return a Spring `Page` (`content`,
`totalElements`, `number`, `numberOfElements`, `first`, `last`, `pageable`, `sort`), while global
search returns the leaner `SearchSection` (`items`, `totalElements`, `totalPages`, `currentPage`,
`size`). And most per-domain search endpoints default to `size=20`, while global search defaults to
`size=10`.

---

## Performance characteristics

The service uses a deliberate two-phase strategy per content type, so cost does not grow with how
many tags or keywords an item carries:

1. **ID query.** `findIdsByGlobalSearch` selects `DISTINCT <entity>.id` with `LEFT JOIN`s onto the
   tag and keyword collection tables, paginated. No entities are hydrated, and the join fan-out is
   collapsed by `DISTINCT` (or `GROUP BY` for sound tracks). Spring Data issues a matching count
   query for `totalElements`.
2. **Batch hydration.** `findAllByIds(ids)` loads the bare scalar columns for that page with a
   single `IN` query. The service reads only `id`, titles, descriptions, cover URLs and
   `createdAt`, so the entities' lazy collections are never touched and no `@BatchSize` follow-up
   queries fire.

Order is preserved by re-mapping the hydrated entities back onto the phase-1 ID list, so the SQL
`IN` ordering does not matter.

Cost per request: roughly 2 queries plus 1 count query per searched type — about 18 statements for
`type=ALL`, 3 for a single type, independent of `size` and of tag cardinality.

What makes it slow: `LIKE '%term%'` cannot use a b-tree index, so every section runs a sequential
scan on the searched columns. The existing indexes (`idx_video_title_ckb`, `idx_video_title_kmr`,
`idx_writer_ckb`, `idx_writer_kmr`) only help prefix matches, not the leading-wildcard pattern used
here. Descriptions are TEXT columns holding full Tiptap HTML, so they are the expensive part of the
scan. Practical guidance for the frontend:

- Debounce the search box (300 ms or more) and cancel in-flight requests.
- Do not fire a global search on every keystroke below 2–3 characters.
- Prefer `type=<one type>` when the visitor is browsing a single section.
- Keep `size` at or below 20 for `type=ALL`.

There is **no caching** on this path. `GlobalSearchService` is annotated `@Transactional(readOnly = true)`
only — no `@Cacheable`, no Redis entry, no HTTP cache headers. Every request hits PostgreSQL.

---

## Enums used by this API

`type` and `SearchItem.type` are plain strings in the code, not Java enums, but only these values
are meaningful:

| Value | Section populated | Backing entity |
|-------|-------------------|----------------|
| `ALL` | all six | — |
| `PROJECT` | `projects` | `Project` |
| `NEWS` | `news` | `News` |
| `VIDEO` | `videos` | `Video` |
| `WRITING` | `writings` | `Writing` |
| `SOUNDTRACK` | `soundTracks` | `SoundTrack` |
| `IMAGE` | `imageCollections` | `ImageCollection` |

The platform `Language` enum (`CKB` = Sorani, `KMR` = Kurmanji) is not accepted as a parameter here.
Both languages are always searched and both are always returned; pick the one to display from the
visitor's active locale.

---

## Notes & gotchas

**`locale` is accepted and ignored.** The controller declares `@RequestParam(required = false) String locale`
but never passes it to the service. Sending `?locale=ckb` changes nothing — not the fields searched,
not the fields returned, not the ordering.

> **Note:** This is a real mismatch between the controller signature and its behaviour. Treat
> `locale` as dead; do not build UI on it.

**An unrecognised `type` returns an empty response, not an error.** `searchesType` compares the
upper-cased value against each known type, so `type=ARTICLE` or a typo like `type=SOUND` matches
nothing: all six sections stay `null` and the endpoint answers `200 OK` with only `query`, `page`,
`size` and `type` populated. Validate the value client-side before sending it.

**Paging past the end zeroes the totals.** When a section's ID page comes back empty — which happens
whenever `page` exceeds that section's last page — the service returns `SearchSection.empty(page, size)`,
which reports `totalElements: 0` and `totalPages: 0` even though matches exist on earlier pages.

> **Note:** A paginator that reads `totalPages` from the response of the page it just loaded will
> collapse to zero as soon as the visitor steps past the last page of any one section. Cache the
> totals from `page=0` (or from the first non-empty response) and drive the paginator from those.

**`createdAt` is not the publication date.** Sections are ordered by `datePublished` (news) or
`publishmentDate` (image collections), but the item payload only ever carries `createdAt` from the
audit columns. For news and image collections the visible order can therefore look inconsistent
with the dates you render. If you need the publication date, fetch the detail endpoint.

**Snippets are stripped HTML, and stripping happens after truncation-free cleanup.** The service
removes every `<...>` sequence, collapses whitespace, then cuts at 200 characters and appends `…`.
The 200-character budget is counted on the plain text, so snippets are consistent across items —
but any HTML entity (`&nbsp;`, `&amp;`) survives unescaped. Render snippets as **text**, not HTML.

**Titles are never null, covers often are.** `titleCkb` / `titleKmr` / `descriptionCkb` /
`descriptionKmr` fall back to `""`. `coverUrl` is genuinely `null` when no cover exists — plan a
placeholder image.

**Null fields are serialised, not omitted, inside `data`.** The application registers its own
`ObjectMapper` bean, which does not apply `spring.jackson.default-property-inclusion`. Only
`ApiResponse` itself carries `@JsonInclude(NON_NULL)`. In practice: expect `"projects": null` and
`"coverUrl": null` to be present as explicit nulls in the body.

**Cover URLs are public S3 objects.** They follow
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/{images|video|audio|files}/{uuid}-{sanitised-filename}`.
No signing, no expiry — safe to cache and hot-link. They are produced by the admin-only upload
pipeline documented in [`../internal/MEDIA_API.md`](../internal/MEDIA_API.md).

**Draft/unpublished content is not filtered out.** None of the six queries filters on a published
flag, a `featured` flag, or a date window. Anything that exists in the table is searchable by an
anonymous visitor as soon as it is saved.

**`DISTINCT` combined with a non-selected `ORDER BY`.** The news and image-collection ID queries
select `DISTINCT <alias>.id` while ordering by `datePublished` / `publishmentDate` / `createdAt`,
which are not in the select list.

> **Note:** PostgreSQL rejects `SELECT DISTINCT ... ORDER BY <column not in select list>` with
> `for SELECT DISTINCT, ORDER BY expressions must appear in select list`. If the `news` or
> `imageCollections` section ever returns `500 INTERNAL_ERROR` while the other four sections work,
> this is the reason — the other sections order by `id`, which is selected. There is no test
> covering these two queries against PostgreSQL.

---

## Related documentation

- Per-domain public APIs: [`NEWS_API.md`](NEWS_API.md), [`PROJECT_API.md`](PROJECT_API.md),
  [`VIDEO_API.md`](VIDEO_API.md), [`WRITING_API.md`](WRITING_API.md),
  [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md), [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md)
- Admin upload pipeline that produces the `coverUrl` values: [`../internal/MEDIA_API.md`](../internal/MEDIA_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`
  (groups: `public`, `internal`, `all`; Global Search appears under the `public` group)
