# Search & Filter — Complete Reference

**Scope:** every search and filter endpoint in the KHI backend, in one independent file.
**Platform:** Spring Boot 3 · JPA/Hibernate · Bilingual (CKB + KMR) · All results paginated
**Audience:** frontend (Vue) and backend — this file is deliberately *not* split into
external/internal. Sections 1–6 are the API contract; sections 7–10 are the engine, the
performance model, and the known gaps.

---

## Table of Contents

1. [The Map — what exists](#1-the-map--what-exists)
2. [Envelopes — what every response looks like](#2-envelopes--what-every-response-looks-like)
3. [Global Search — `GET /api/v1/search`](#3-global-search--get-apiv1search)
4. [Per-module Search Endpoints](#4-per-module-search-endpoints)
5. [Per-module Filter Endpoints](#5-per-module-filter-endpoints)
6. [Auth & Errors](#6-auth--errors)
7. [The Engine — two-phase ID + hydrate](#7-the-engine--two-phase-id--hydrate)
8. [Field Coverage Matrix — what each search actually looks inside](#8-field-coverage-matrix--what-each-search-actually-looks-inside)
9. [Sort Order & Pagination Semantics](#9-sort-order--pagination-semantics)
10. [Caveats, Gaps & Gotchas](#10-caveats-gaps--gotchas)
11. [Frontend Recipes](#11-frontend-recipes)

---

## 1. The Map — what exists

There are **three distinct layers**, and they do not share code paths:

| Layer | Entry point | What it does |
|-------|------------|--------------|
| **Global search** | `GET /api/v1/search` | One `q` searched across **6 content types at once**, each returned in its own paginated section |
| **Module search** | `GET /api/v1/{module}/search…` | Free-text / tag / keyword / writer / category search scoped to **one** content type |
| **Module filter** | `GET /api/v1/{module}?…` | Exact-match narrowing by enum, FK, or boolean flag — **no text matching** |

**Quick index of every endpoint covered here:**

| Method | Path | Kind | Auth |
|--------|------|------|------|
| `GET` | `/api/v1/search` | Global search (all 6 types) | Public |
| `GET` | `/api/v1/news/search` | Module search (free text) | Public |
| `GET` | `/api/v1/news/search/keyword` | Module search (keyword) | Public |
| `GET` | `/api/v1/news/search/tag` | Module search (tag) | Public |
| `GET` | `/api/v1/news/search/category` | Module search (category name) | Public |
| `GET` | `/api/v1/news/search/subcategory` | Module search (subcategory name) | Public |
| `GET` | `/api/v1/videos/search/tag` | Module search (tag) | Public |
| `GET` | `/api/v1/videos/search/keyword` | Module search (keyword) | Public |
| `GET` | `/api/v1/videos` | Filter (`videoType`, `memories`, `topicId`) | Public |
| `GET` | `/api/v1/writings/search/writer` | Module search (writer name) | Public |
| `GET` | `/api/v1/writings/search/tag` | Module search (tag) | Public |
| `GET` | `/api/v1/writings/search/keyword` | Module search (keyword) | Public |
| `GET` | `/api/v1/sound-tracks/search` | Module search (free text) | Public |
| `GET` | `/api/v1/sound-tracks/search/tag` | Module search (tag) | Public |
| `GET` | `/api/v1/sound-tracks/search/keyword` | Module search (keyword) | Public |
| `GET` | `/api/v1/sound-tracks/by-state` | Filter (`state`) | Public |
| `GET` | `/api/v1/sound-tracks/by-sound-type` | Filter (`soundType`) | Public |
| `GET` | `/api/v1/sound-tracks/by-topic` | Filter (`topicId`) | Public |
| `GET` | `/api/v1/sound-tracks/album-of-memories` | Filter (flag) | Public |
| `GET` | `/api/v1/projects/search/tag` | Module search (tag) | Public |
| `GET` | `/api/v1/projects/search/keyword` | Module search (keyword) | Public |
| `GET` | `/api/v1/image-collections` | Filter (`type`, `topicId`) | Public |
| `GET` | `/api/v1/services` | Filter (`type`) | Public |
| `GET` | `/api/v1/services/search` | Module search (free text, active only) | Public |
| `GET` | `/api/v1/services/search/admin` | Module search (includes inactive) | **ADMIN / SUPER_ADMIN** |

> **Image collections have no text-search endpoint of their own.** They are searchable **only**
> through `GET /api/v1/search`. See [§10](#10-caveats-gaps--gotchas).

---

## 2. Envelopes — what every response looks like

### 2.1 The success envelope

Every endpoint on this page except `GET /api/v1/videos` and `GET /api/v1/videos/search/*`
wraps its payload in `ApiResponse<T>`:

```json
{
  "success": true,
  "message": "Search completed",
  "data": { }
}
```

`ApiResponse` itself carries `@JsonInclude(NON_NULL)`, so its own `data` / `message` keys drop
when null.

> **Don't assume nested DTOs are null-stripped too.** `application.yaml` sets
> `spring.jackson.default-property-inclusion: non_null`, but `JacksonConfig` registers a
> hand-built `new ObjectMapper()` bean, which causes Spring Boot's Jackson auto-configuration
> to back off — so that property may never reach the mapper the HTTP converters use. Nested
> DTOs like `SearchItem` have no class-level `@JsonInclude`, so a null field may serialise as
> `"titleKmr": null` rather than being omitted. **Write frontend code that tolerates both:**
> use a truthiness check (`item.titleKmr || item.titleCkb`), never `'titleKmr' in item`.

**The video endpoints are the exception** — they return the `Page` object **raw**, with no
`success`/`message`/`data` wrapper:

```js
// every other module
const items = res.data.data.content

// videos only
const items = res.data.content
```

### 2.2 The page shape

All module search and filter endpoints return a standard Spring `Page`:

```json
{
  "content": [ /* module DTOs */ ],
  "pageable": { "pageNumber": 0, "pageSize": 20, "offset": 0 },
  "totalElements": 137,
  "totalPages": 7,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false,
  "numberOfElements": 20,
  "empty": false
}
```

Global search uses its **own** section shape instead — see [§3.3](#33-response-shape).

### 2.3 The error envelope

```json
{
  "timestamp": "2026-08-19T10:22:41.317Z",
  "status": 400,
  "path": "/api/v1/news/search",
  "method": "GET",
  "traceId": "b41f9c2e-77a0-4f2c-b0f1-9e2f0a5b1d33",
  "code": "BAD_REQUEST",
  "message": "Search keyword is required",
  "messageEn": "Search keyword is required",
  "messageKu": "کلیلەووشەی گەڕان پێویستە",
  "fieldErrors": null,
  "details": { "field": "q" }
}
```

`message` is resolved from the request's `Accept-Language`; `messageEn` and `messageKu` are
always both present, so the UI can pick without a second round trip.

---

## 3. Global Search — `GET /api/v1/search`

One request, six content types, six independently paginated sections.

### 3.1 Query parameters

| Param | Required | Default | Notes |
|-------|----------|---------|-------|
| `q` | **Yes** | — | The search term. Missing → `400`. Empty string (`?q=`) is legal and matches **everything** |
| `type` | No | `ALL` | `ALL` · `PROJECT` · `NEWS` · `VIDEO` · `WRITING` · `SOUNDTRACK` · `IMAGE`. Case-insensitive, trimmed |
| `page` | No | `0` | 0-based |
| `size` | No | `10` | Items **per section**, not per response. `type=ALL&size=10` → up to **60** items |
| `locale` | No | — | **Accepted but ignored** — see [§10](#10-caveats-gaps--gotchas) |

An unrecognised `type` value (e.g. `type=AUDIO`) is **not** an error — it simply matches no
section, and every section comes back `null`.

### 3.2 Examples

```http
GET /api/v1/search?q=کوردستان
GET /api/v1/search?q=کوردستان&page=1&size=10
GET /api/v1/search?q=کوردستان&type=NEWS
GET /api/v1/search?q=هاوار&type=SOUNDTRACK&page=0&size=20
```

### 3.3 Response shape

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
          "id": 41,
          "type": "PROJECT",
          "titleCkb": "پرۆژەی کوردستان",
          "titleKmr": "Projeya Kurdistanê",
          "descriptionCkb": "کورتەیەک لە پرۆژەکە …",
          "descriptionKmr": "Kurteyek ji projeyê …",
          "coverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/covers/p41.jpg",
          "createdAt": "2026-04-02T18:31:07"
        }
      ],
      "totalElements": 12,
      "totalPages": 2,
      "currentPage": 0,
      "size": 10
    },

    "news":             { "items": [], "totalElements": 0, "totalPages": 0, "currentPage": 0, "size": 10 },
    "videos":           { "items": [], "totalElements": 0, "totalPages": 0, "currentPage": 0, "size": 10 },
    "writings":         { "items": [], "totalElements": 0, "totalPages": 0, "currentPage": 0, "size": 10 },
    "soundTracks":      { "items": [], "totalElements": 0, "totalPages": 0, "currentPage": 0, "size": 10 },
    "imageCollections": { "items": [], "totalElements": 0, "totalPages": 0, "currentPage": 0, "size": 10 }
  }
}
```

**Two different "nothing here" states — do not confuse them:**

| State | Meaning | How to render |
|-------|---------|---------------|
| Section is **`null`** — either `"news": null` or the key absent ([§2.1](#21-the-success-envelope)) | That type was **not searched** — the `type` filter excluded it | Don't render the section at all |
| Section is **an object with `items: []`** | That type **was** searched and found nothing | Render the section header with an empty state, or hide it — your choice |

Test with `if (data.news)`, which handles both the `null` and the absent-key form.

### 3.4 `SearchItem` — the unified result card

Every section returns the same flat item, so one Vue component renders all six:

| Field | Type | Notes |
|-------|------|-------|
| `id` | number | Primary key of the source entity |
| `type` | string | `PROJECT` · `NEWS` · `VIDEO` · `WRITING` · `SOUNDTRACK` · `IMAGE` — use it to pick the detail route |
| `titleCkb` | string | `""` when absent (never `null`) |
| `titleKmr` | string | `""` when absent, for all six types ([§10.2](#102-image-items-return-titlekmr-null-instead-of----fixed)) |
| `descriptionCkb` | string | HTML-stripped snippet, max 200 chars + `…` |
| `descriptionKmr` | string | Same treatment |
| `coverUrl` | string \| null | First non-blank of CKB → KMR cover |
| `createdAt` | ISO-8601 | For "published X days ago" labels |

**Snippets are pre-cleaned.** Descriptions are Tiptap HTML in storage; the service strips all
tags, collapses whitespace, truncates at 200 chars, and appends `…`. Render as plain text —
`v-html` is neither needed nor safe here.

**Cover fallback is CKB-first.** Types with split covers (`VIDEO`, `WRITING`, `SOUNDTRACK`,
`IMAGE`) return `ckbCoverUrl` when set, otherwise `kmrCoverUrl`, otherwise `null`.
`PROJECT` and `NEWS` have one shared `coverUrl`.

---

## 4. Per-module Search Endpoints

All of these are text searches. All are case-insensitive substring matches (`LIKE %term%`).
All trim leading/trailing whitespace. All reject blank input with `400`.

### 4.1 News — `/api/v1/news`

| Endpoint | Params | Searches |
|----------|--------|----------|
| `GET /search` | `keyword` **or** `q`, `page=0`, `size=20` | Title, description, tags, keywords — CKB + KMR |
| `GET /search/keyword` | `keyword`, `language=both`, `page=0`, `size=20` | Keyword collections |
| `GET /search/tag` | `tag`, `language=both`, `page=0`, `size=20` | Tag collections |
| `GET /search/category` | `name`, `page=0`, `size=20` | Category name (CKB + KMR) |
| `GET /search/subcategory` | `name`, `page=0`, `size=20` | Subcategory name (CKB + KMR) |

`GET /search` accepts **either** param name. `keyword` wins when both are sent; if neither is
sent the query resolves to `""` and the service rejects it with `400 keyword.required`.

The `language` param on `/search/keyword` and `/search/tag`:

| Value | Behaviour |
|-------|-----------|
| `ckb` | CKB collection only |
| `kmr` | KMR collection only |
| anything else (incl. the default `both`) | Both collections, OR'd |

```http
GET /api/v1/news/search?q=هەڵبژاردن&page=0&size=20
GET /api/v1/news/search/tag?tag=سیاسەت&language=ckb
GET /api/v1/news/search/category?name=Culture
```

### 4.2 Videos — `/api/v1/videos`

| Endpoint | Params | Searches |
|----------|--------|----------|
| `GET /search/tag` | `value`, `page=0`, `size=10` | `tagsCkb` + `tagsKmr` |
| `GET /search/keyword` | `value`, `page=0`, `size=10` | Titles, descriptions, **director names**, keyword collections — CKB + KMR |

⚠️ **The param is `value`, not `tag` / `keyword`** — videos are the odd one out here.
And remember these two return a **raw `Page`**, not the `ApiResponse` envelope.

```http
GET /api/v1/videos/search/keyword?value=هاوار&page=0&size=12
GET /api/v1/videos/search/tag?value=دۆکیۆمێنتاری
```

### 4.3 Writings — `/api/v1/writings`

| Endpoint | Params | Searches |
|----------|--------|----------|
| `GET /search/writer` | `name`, `language=both`, `page=0`, `size=20` | `writerCkb` / `writerKmr` |
| `GET /search/tag` | `tag`, `language=both`, `page=0`, `size=20` | Tag collections |
| `GET /search/keyword` | `keyword`, `language=both`, `page=0`, `size=20` | Keyword collections |

Same `ckb` / `kmr` / *both* semantics as News. Results are sorted `createdAt DESC` —
the sort is applied in the controller's `Pageable`, not the query.

Writings have **no** `/search` free-text endpoint at module level; use global search
with `type=WRITING`.

### 4.4 Sound tracks — `/api/v1/sound-tracks`

| Endpoint | Params | Searches |
|----------|--------|----------|
| `GET /search` | `q`, `page=0`, `size=20` | Titles, descriptions, album name, terms, tags, keywords, **topic names** — CKB + KMR |
| `GET /search/tag` | `tag` **or** `value`, `page=0`, `size=20` | Tag collections |
| `GET /search/keyword` | `keyword` **or** `value`, `page=0`, `size=20` | Keyword collections |

`/search/tag` and `/search/keyword` accept both the canonical name and the `value` alias
(the video-style name), so a shared frontend helper can send either.

### 4.5 Projects — `/api/v1/projects`

| Endpoint | Params | Searches |
|----------|--------|----------|
| `GET /search/tag` | `tag`, `page=0`, `size=20` | `tagsCkb.name` + `tagsKmr.name` |
| `GET /search/keyword` | `keyword`, `page=0`, `size=20` | `keywordsCkb.name` + `keywordsKmr.name` |

No `language` param — both languages are always searched.
Project tags/keywords are **entities with a `name` field**, not plain strings like the other
modules. This is invisible over HTTP but matters if you touch the JPQL.

### 4.6 Services — `/api/v1/services`

| Endpoint | Params | Searches | Auth |
|----------|--------|----------|------|
| `GET /search` | `q`, `page=0`, `size=20` | Service type, location, content title, content description — **`active = true` only** | Public |
| `GET /search/admin` | `q`, `page=0`, `size=20` | Same fields, **including inactive services** | ADMIN / SUPER_ADMIN |

The only difference between the two is the `active = true` predicate. Never call
`/search/admin` from the public site — it leaks unpublished services and will `403` without
a valid admin token.

---

## 5. Per-module Filter Endpoints

Filters are **exact matches** on enums, foreign keys, and boolean flags. No `LIKE`, no
partial matching, no language variants.

### 5.1 Videos — `GET /api/v1/videos`

| Param | Type | Notes |
|-------|------|-------|
| `videoType` | `FILM` \| `VIDEO_CLIP` | Optional |
| `memories` | boolean | Optional. **Only honoured when `videoType=VIDEO_CLIP`** |
| `topicId` | number | Optional |
| `page` / `size` | int | Default `0` / `10`. **`size` is clamped to 100**, `page` floored at 0 |

**The params are not combinable — they are a priority chain.** The service checks them in a
fixed order and returns on the first match:

```
1. topicId present            → all videos in that topic        (videoType and memories IGNORED)
2. videoType=VIDEO_CLIP
   AND memories is non-null   → clips filtered by album flag
3. videoType present          → all videos of that type
4. nothing present            → all videos
```

So `?topicId=5&videoType=FILM` returns **every** video in topic 5, films and clips alike —
`videoType` is silently dropped. Send one filter at a time.

Sorted `createdAt DESC`.

```http
GET /api/v1/videos?videoType=FILM&page=0&size=12
GET /api/v1/videos?videoType=VIDEO_CLIP&memories=true
GET /api/v1/videos?topicId=5
```

### 5.2 Sound tracks — `/api/v1/sound-tracks`

Each filter is its own endpoint, so there is no precedence to reason about:

| Endpoint | Param | Required | Notes |
|----------|-------|----------|-------|
| `GET /by-state` | `state` | Yes | `SINGLE` \| `MULTI`. Invalid value → `500`, see [§6.2](#62-error-responses) |
| `GET /by-sound-type` | `soundType` **or** `type` | Yes | Free-form string, case-insensitive exact match (e.g. `poem`) |
| `GET /by-topic` | `topicId` | Yes | FK |
| `GET /album-of-memories` | — | — | The memorial album flag |

All take `page=0`, `size=20`.

```http
GET /api/v1/sound-tracks/by-state?state=MULTI
GET /api/v1/sound-tracks/by-sound-type?soundType=poem
GET /api/v1/sound-tracks/by-topic?topicId=3
GET /api/v1/sound-tracks/album-of-memories?page=0&size=20
```

### 5.3 Image collections — `GET /api/v1/image-collections`

| Param | Type | Notes |
|-------|------|-------|
| `type` | `SINGLE` \| `GALLERY` \| `PHOTO_STORY` | Optional |
| `topicId` | number | Optional |
| `page` / `size` | int | Default `0` / `20` |

**Also a priority chain, not a combination:**

```
1. type present     → filter by collection type   (topicId IGNORED)
2. topicId present  → filter by topic
3. neither          → all collections
```

`?type=GALLERY&topicId=4` returns **all** galleries, ignoring the topic.

### 5.4 Services — `GET /api/v1/services`

| Param | Type | Notes |
|-------|------|-------|
| `type` | string | Optional. Case-insensitive exact match on `serviceType` |
| `page` / `size` | int | Default `0` / `20` |

Returns **active services only**, sorted `sortOrder ASC NULLS LAST, publishedAt DESC,
createdAt DESC`. `GET /api/v1/services/types` returns the list of valid `type` values for
populating a filter dropdown.

Admins wanting inactive rows use `GET /api/v1/services/admin/all` (no type filter there).

---

## 6. Auth & Errors

### 6.1 Auth

`SecurityConfig` grants `permitAll()` to `GET /api/v1/**`. **Every search and filter endpoint
on this page is public and needs no token** — with exactly one exception:

| Endpoint | Requirement |
|----------|-------------|
| `GET /api/v1/services/search/admin` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` (JWT bearer) |

### 6.2 Error responses

| Status | When | `code` | Typical `details` |
|--------|------|--------|-------------------|
| `400` | Required param present but blank/whitespace | `BAD_REQUEST` | `{"field": "q"}` |
| `400` | Required param missing entirely (`q`, `state`, `topicId`, `name`, `tag`…) | `MISSING_PARAMETER` | `{"missingParameter": "q", "expectedType": "String", "hint": "…"}` |
| `400` | `page` is negative (`PageRequest` rejects it) | `BAD_REQUEST` | `{"reason": "Page index must not be less than zero"}` |
| `400` | Sound-track filter validation | `SOUND_VALIDATION` | `{"field": "state"}` |
| `400` | Image-collection filter validation | `IMAGE_VALIDATION` | `{"field": "type"}` |
| `403` | `/services/search/admin` without an admin role | `FORBIDDEN` | `{"path": "…", "method": "GET", "hint": "…"}` |
| **`500`** | **Enum param can't bind** (`state=LOUD`, `videoType=MOVIE`, `type=ALBUM`) | `INTERNAL_ERROR` | `{"traceId": "…"}` |

⚠️ **An invalid enum value returns `500`, not `400`.** `GlobalExceptionHandler` is a plain
`@RestControllerAdvice` — it does not extend `ResponseEntityExceptionHandler` and has no
handler for `MethodArgumentTypeMismatchException`, so a bad enum falls through to the
catch-all `Exception` handler. **Validate enum params client-side**; never let a user type
free text into `state`, `videoType`, or `type`. Adding a
`@ExceptionHandler(MethodArgumentTypeMismatchException.class)` returning `400` would fix
this for every endpoint at once.

**Message keys you will see from the search layer:**
`keyword.required` · `tag.required` · `search.keyword.required` · `search.tag.required` ·
`search.writer.required` · `news.category.required` · `service.search.required` ·
`soundTrack.state.required` · `soundTrack.soundType.required` · `imageCollection.type.required`

**A search that matches nothing is a `200`, never a `404`.** Empty results come back as an
empty `content` / `items` array with `totalElements: 0`. Only a missing *entity by ID* 404s.

**Negative `page` and oversized `size`:** only the video endpoints clamp them
(`page ≥ 0`, `1 ≤ size ≤ 100`). Everywhere else the value is handed to `PageRequest.of()`
untouched — `page=-1` throws and surfaces as `400`, and `size=100000` will genuinely try to
build that page. Validate on the client.

---

## 7. The Engine — two-phase ID + hydrate

Every search in this codebase — global and per-module — runs the same two-phase pattern.
Understanding it explains both the speed and the quirks.

### Phase 1 — ID-only query

```java
Page<Long> idPage = repo.findIdsByGlobalSearch(q, pageable);
```

Selects `DISTINCT id` with `LEFT JOIN`s onto the collection tables (tags, keywords). No
entities are constructed. Pagination and `COUNT` happen here, on a narrow index-friendly
result set.

**Why `DISTINCT` matters:** joining 4 element-collection tables multiplies rows — an item with
3 CKB tags × 2 KMR tags × 4 CKB keywords × 2 KMR keywords is 48 duplicate rows for one entity.
Paginating *that* would produce pages containing 2 real items. `DISTINCT` on the ID collapses
it, and because only the ID is selected there is no Cartesian blow-up in memory.

> **Sound tracks use `GROUP BY s.id` + `ORDER BY max(s.createdAt) DESC` instead of `DISTINCT`.**
> Same effect — the aggregate is required because the sort column isn't in the select list.

### Phase 2 — batch hydration

```java
List<Entity> rows = repo.findAllByIds(idPage.getContent());
```

One `WHERE id IN (...)` for the whole page. No `@EntityGraph` — collections stay lazy.

- **Global search** never touches the lazy collections (a `SearchItem` needs only scalars),
  so hydration is exactly **2 queries per content type**: 12 total for `type=ALL`,
  regardless of how many tags each item carries.
- **Module searches** map to full module DTOs, which *do* read the collections. `@BatchSize`
  on each entity then fires one `IN` query per collection: 9 for News, 12 for SoundTrack,
  8 for ImageCollection, 2 for Service. Still constant per page — not N+1.

### Phase 3 — re-ordering in Java

`findAllByIds` mostly has no `ORDER BY`, so the DB may return rows in any order. Every caller
re-indexes into a `LinkedHashMap` and walks the original ID list to restore Phase-1 order.
Rows deleted between the two queries are skipped silently — which is why `items.length` can
occasionally be less than `size` on a busy page while `totalElements` still reports the
Phase-1 count.

### Single-entity reads are different

`GET /{id}` uses `findByIdWithGraph()` with a full `@EntityGraph`. A bounded Cartesian product
on one row is fine; the same graph across a page is what the two-phase pattern exists to avoid.

---

## 8. Field Coverage Matrix — what each search actually looks inside

### 8.1 Global search (`/api/v1/search`) per type

| Type | Title | Description | Tags | Keywords | Extra fields |
|------|:-----:|:-----------:|:----:|:--------:|--------------|
| `PROJECT` | ✅ | ✅ | ✅ | ✅ | — |
| `NEWS` | ✅ | ✅ | ✅ | ✅ | — |
| `VIDEO` | ✅ | ✅ | ✅ | ✅ | **director** (CKB + KMR) |
| `WRITING` | ✅ | ✅ | ✅ | ✅ | **writer** (CKB + KMR) |
| `SOUNDTRACK` | ✅ | ✅ | ✅ | ✅ | **albumName**, **terms**, **topic name** (CKB + KMR) |
| `IMAGE` | ✅ | ✅ | ✅ | ✅ | **collectedBy**, **location**, **topic name** (CKB + KMR) |

Every column above is matched in **both dialects** and case-insensitively.

**Not searched anywhere:** category / subcategory names (News), service type & location
(Services — those live on the module endpoint only), file names, attachment metadata, clip
titles, album item captions.

### 8.2 Module search coverage

| Module endpoint | Title | Desc | Tags | Keywords | Other |
|-----------------|:-----:|:----:|:----:|:--------:|-------|
| `news/search` | ✅ | ✅ | ✅ | ✅ | — |
| `news/search/tag` | — | — | ✅ | — | `language` scoped |
| `news/search/keyword` | — | — | — | ✅ | `language` scoped |
| `news/search/category` | — | — | — | — | category name |
| `news/search/subcategory` | — | — | — | — | subcategory name |
| `videos/search/keyword` | ✅ | ✅ | — | ✅ | director |
| `videos/search/tag` | — | — | ✅ | — | — |
| `writings/search/writer` | — | — | — | — | writer, `language` scoped |
| `writings/search/tag` | — | — | ✅ | — | `language` scoped |
| `writings/search/keyword` | — | — | — | ✅ | `language` scoped |
| `sound-tracks/search` | ✅ | ✅ | ✅ | ✅ | albumName, terms, topic |
| `sound-tracks/search/tag` | — | — | ✅ | — | — |
| `sound-tracks/search/keyword` | — | — | — | ✅ | — |
| `projects/search/tag` | — | — | ✅ | — | — |
| `projects/search/keyword` | — | — | — | ✅ | — |
| `services/search` | ✅ | ✅ | — | — | serviceType, location |

Note the asymmetry: `videos/search/keyword` is effectively a **full-text** search (it covers
titles and descriptions too), while `projects/search/keyword` searches the keyword collection
**only**. They are not the same operation despite the matching path.

---

## 9. Sort Order & Pagination Semantics

### 9.1 Sort order per type

Sorting is defined in the JPQL, not by a client param. **There is no `sort` query parameter
anywhere in the search layer** — sending one has no effect.

| Type | Global-search order | Meaning |
|------|--------------------|---------|
| `PROJECT` | `id DESC` | Insertion order, newest first |
| `NEWS` | `datePublished DESC, createdAt DESC` | Editorial date first |
| `VIDEO` | `id DESC` | Insertion order |
| `WRITING` | `id DESC` | Insertion order |
| `SOUNDTRACK` | `max(createdAt) DESC` | Creation date |
| `IMAGE` | `publishmentDate DESC, createdAt DESC` | Publication date |
| `SERVICE` (module only) | `publishedAt DESC, createdAt DESC` | Publication date |

`id DESC` is a **proxy** for recency, not the real thing. If a Project or Video was
backdated, or imported out of order, global search will still place it by insert order while
that same item sorts by date elsewhere in the app. Don't build "latest" UI on it.

Module filter endpoints differ again: videos sort `createdAt DESC`, writings `createdAt DESC`,
active services `sortOrder ASC (nulls last), publishedAt DESC, createdAt DESC`.

### 9.2 Paging global search

`size` is **per section**. With `type=ALL&size=10` you receive up to 60 items and six
independent `totalPages` counts.

`page` applies to **all sections at once** — there is no way to advance only the news section.
Two workable UI patterns:

- **Tabbed:** call with `type=NEWS&page=n` per tab. One section, one paginator, correct counts.
- **Mixed preview:** call once with `type=ALL&size=5`, show 5 per section with a "see all"
  link that switches to the tabbed call.

Paging a single section inside an `ALL` response re-queries all six types and wastes
5/6 of the work.

### 9.3 Empty `q`

`?q=` (empty) resolves to `LIKE '%%'`, which matches every **non-null** value. In practice
`/api/v1/search?q=` returns a browsable page of all content. Module endpoints **reject** blank
input with `400`, so this behaviour is global-search-only. Rows whose every searched column is
`NULL` are excluded even by the empty query — SQL `NULL LIKE '%'` is `NULL`, not true.

---

## 10. Caveats, Gaps & Gotchas

These are real, verified behaviours in the current code. Read before building against them.

### 10.1 `locale` on global search does nothing

`GlobalSearchController.search()` declares `@RequestParam(required = false) String locale` and
never passes it on. Results are always bilingual. Harmless to send, but it will not scope
results to a dialect — filter client-side on `titleCkb` / `titleKmr` instead.

### 10.2 ~~`IMAGE` items return `titleKmr: null` instead of `""`~~ — ✅ FIXED

**Was:** five of the six branches in `GlobalSearchService` wrapped the KMR title in a
null-safe helper yielding `""`; the `IMAGE` branch omitted that wrapper, so image collections
with no KMR content returned `titleKmr: null` while the other five returned `""`.

**Now:** the `IMAGE` branch calls the same `title(...)` helper. **All six types return `""`
consistently** for a missing KMR title.

Keep writing `item.titleKmr || item.titleCkb` anyway — it costs nothing and stays correct
whichever way the field is absent.

### 10.3 Services are not in global search

`GET /api/v1/search` covers 6 types; `SERVICE` is not one of them, and `type=SERVICE` returns
all-null sections. `ServiceRepository` *does* have `findIdsByGlobalSearch` — it powers
`GET /api/v1/services/search` only. If the UI needs services in unified results, call the
service endpoint in parallel and merge client-side.

### 10.4 Unreachable service methods

Written, tested-by-nothing, wired to no endpoint:

| Method | Status |
|--------|--------|
| `ImageCollectionService.globalSearch(q, page, size)` | No controller mapping |
| `ImageCollectionService.searchByTag(...)` | No controller mapping |
| `ImageCollectionService.searchByKeyword(...)` | No controller mapping |
| `ProjectService.globalSearch(q, page, size)` | No controller mapping |

Image collections and projects are therefore text-searchable **only** via `/api/v1/search`.
Adding `GET /api/v1/image-collections/search?q=` and `GET /api/v1/projects/search?q=` is a
two-line controller change each — the service and repository layers are already done.

### 10.5 ~~`@Cacheable` is currently inert~~ — ✅ ENABLED

**Was:** the `@Cacheable` annotations across News, Projects, Sound tracks, Image collections,
and Services had no effect, because no `@EnableCaching` existed anywhere — Spring never
created the caching proxies, so every annotated method ran its query on every call.

**Now:** `khi_app/config/CacheConfig.java` carries `@EnableCaching`. Redis caching is live for
all five cache names, with the TTL and key prefix still coming from `application.yaml`
(10 minutes, `khi:`). Every cache has `@CacheEvict(allEntries = true)` on its create / update /
delete paths, so writes don't leave stale reads behind.

**Two consequences worth planning for:**

1. **Redis is now on the request path.** Before, the app served traffic whether or not Redis
   was reachable — Lettuce connects lazily and nothing ever asked it for a value. Now an
   unreachable Redis surfaces on every cached read. Confirm `REDIS_HOST` / `REDIS_PORT` /
   `REDIS_PASSWORD` are set in every environment.
2. **Search keys are user-supplied.** Keys like `'search:' + #q.toLowerCase() + ':p0:s20'`
   mean cache cardinality scales with distinct search terms, not with content volume. The
   10-minute TTL bounds it, but watch Redis memory once real traffic lands, and consider a
   `maxmemory-policy` of `allkeys-lru` on the Redis side.

**The serialization contract this depends on:** Boot's Redis cache uses JDK serialization, so
every type reachable from a cached return value must implement `Serializable`. All five cached
DTO graphs now do, each pinning `serialVersionUID = 1L` so that merely adding a field doesn't
invalidate in-flight cache entries with `InvalidClassException`.
`CacheSerializationTests` round-trips each cached page shape through `ObjectOutputStream` and
fails the build if a non-Serializable field creeps in — **add a case there whenever you add a
`@Cacheable` returning a new type.**

### 10.6 `LIKE '%term%'` cannot use a standard index

Every text search is a leading-wildcard match. B-tree indexes on `lower(title)` do **not**
help a leading `%`; the planner falls back to a sequential scan on the joined set. It is fine
at the current corpus size and will degrade linearly. The repository javadocs recommend
functional indexes on `lower(tag)` / `lower(keyword)` — those help the equality-ish cases but
not the leading wildcard. The real fix when it matters is Postgres full-text search
(`tsvector` + GIN) or an external index.

### 10.7 Video endpoints break the envelope convention

`GET /api/v1/videos`, `/videos/search/tag`, and `/videos/search/keyword` return a bare `Page`.
Everything else returns `ApiResponse<Page<…>>`. A shared API client must special-case videos
or it will read `undefined` from `res.data.data`.

### 10.8 Param naming is not uniform

| Concept | Names in use |
|---------|--------------|
| Free text | `q` (global, sound-tracks, services), `keyword` **or** `q` (news) |
| Tag | `tag` (news, writings, projects, sound-tracks), `value` (videos, sound-tracks alias) |
| Keyword | `keyword` (news, writings, projects, sound-tracks), `value` (videos, sound-tracks alias) |
| Name | `name` (news category/subcategory, writings writer) |

Sound tracks accept both spellings; videos accept **only** `value`. Centralise this in one
frontend helper.

### 10.9 Filter params don't combine

Videos and image collections resolve filters as a **priority chain**, silently ignoring
lower-priority params ([§5.1](#51-videos--get-apiv1videos), [§5.3](#53-image-collections--get-apiv1image-collections)).
A UI offering "type **and** topic" checkboxes at the same time will show results that
contradict the checked boxes. Make those controls mutually exclusive, or filter the second
dimension client-side.

### 10.10 Stale comment in `VideoRepository`

`findAllByIds` is documented as *"ORDER BY FIELD preserves the Phase-1 sort order"*, but the
query has no `ORDER BY`. Order is in fact preserved — by the Java re-indexing step in
`GlobalSearchService` ([§7](#7-the-engine--two-phase-id--hydrate)). Behaviour is correct;
only the comment is wrong. Don't rely on the query returning ordered rows if you reuse it.

---

## 11. Frontend Recipes

### 11.1 Unified search page

```js
async function globalSearch(q, { type = 'ALL', page = 0, size = 10 } = {}) {
  const { data } = await api.get('/api/v1/search', { params: { q, type, page, size } })
  return data.data
}

const r = await globalSearch('کوردستان')

// null section = not searched; empty items = searched, nothing found
const sections = [
  ['projects',         'پرۆژەکان'],
  ['news',             'هەواڵەکان'],
  ['videos',           'ڤیدیۆکان'],
  ['writings',         'نووسینەکان'],
  ['soundTracks',      'دەنگەکان'],
  ['imageCollections', 'وێنەکان'],
]
  .filter(([key]) => r[key] !== null && r[key] !== undefined)
  .map(([key, label]) => ({ key, label, ...r[key] }))
```

### 11.2 Routing a `SearchItem` to its detail page

```js
const ROUTES = {
  PROJECT:    id => `/projects/${id}`,
  NEWS:       id => `/news/${id}`,
  VIDEO:      id => `/videos/${id}`,
  WRITING:    id => `/writings/${id}`,
  SOUNDTRACK: id => `/sound-tracks/${id}`,
  IMAGE:      id => `/image-collections/${id}`,
}

const href = ROUTES[item.type](item.id)
```

### 11.3 Bilingual title with a safe fallback

All six types now return `""` for a missing title ([§10.2](#102-image-items-return-titlekmr-null-instead-of----fixed)),
but a truthiness check costs nothing and covers `""`, `null`, and an absent key alike:

```js
const title = (item, lang) =>
  (lang === 'kmr' ? item.titleKmr : item.titleCkb) ||
  (lang === 'kmr' ? item.titleCkb : item.titleKmr) ||
  '—'
```

### 11.4 One helper for the inconsistent envelopes

```js
// videos return a bare Page; everything else wraps it in { success, message, data }
const unwrapPage = res => res.data.content ? res.data : res.data.data

const videos = unwrapPage(await api.get('/api/v1/videos', { params: { videoType: 'FILM' } }))
const news   = unwrapPage(await api.get('/api/v1/news/search', { params: { q: 'هەواڵ' } }))

videos.content // ✅
news.content   // ✅
```

### 11.5 Tabbed search — page one type at a time

```js
const TABS = ['ALL', 'PROJECT', 'NEWS', 'VIDEO', 'WRITING', 'SOUNDTRACK', 'IMAGE']
const SECTION_OF = {
  PROJECT: 'projects',    NEWS: 'news',              VIDEO: 'videos',
  WRITING: 'writings',    SOUNDTRACK: 'soundTracks', IMAGE: 'imageCollections',
}

async function tabPage(q, type, page) {
  const r = await globalSearch(q, { type, page, size: 20 })
  if (type === 'ALL') return r                 // six sections, one shared page index
  return r[SECTION_OF[type]]                   // one section with its own totalPages
}
```

Prefer this over paging a section inside an `ALL` response — that re-queries all six types
and discards five sixths of the result.

### 11.6 Debounce, and don't fire on empty

```js
import { debounce } from 'lodash-es'

const run = debounce(async q => {
  const term = q.trim()
  if (!term) { results.value = null; return }   // module endpoints 400 on blank
  results.value = await globalSearch(term)
}, 300)
```

Every keystroke is a `LIKE '%…%'` scan across six tables
([§10.6](#106-like-term-cannot-use-a-standard-index)). Redis now absorbs repeats within its
10-minute window ([§10.5](#105-cacheable-is-currently-inert--enabled)), but a debounce still
matters: every distinct prefix a user types on the way to their real query is a **cache miss
and a new cache key**. 300 ms is a reasonable floor.

---

## Appendix — Source Files

| Layer | File |
|-------|------|
| Global search controller | `khi_app/api/search/GlobalSearchController.java` |
| Global search service | `khi_app/service/search/GlobalSearchService.java` |
| Global search DTOs | `khi_app/dto/search/GlobalSearchResponse.java`, `SearchItem.java` |
| Success envelope | `khi_app/dto/project/ApiResponse.java` *(package `khi_app.dto`)* |
| Error envelope | `khi_app/exceptions/ApiErrorResponse.java`, `GlobalExceptionHandler.java` |
| Auth rules | `user/configs/SecurityConfig.java` |
| Cache enablement + serialization contract | `khi_app/config/CacheConfig.java` |
| Cache config (TTL, key prefix) | `src/main/resources/application.yaml` (`spring.cache`) |
| Cache serialization guard test | `src/test/java/.../khi_app/config/CacheSerializationTests.java` |
| Phase-1 / Phase-2 queries | `khi_app/repository/**/{Project,News,Video,Writing,SoundTrack,ImageCollection,Service}Repository.java` |
| Module search/filter logic | `khi_app/service/**/{Project,News,Video,Writing,SoundTrack,ImageCollection,Service}Service.java` |
| Module endpoints | `khi_app/api/**/*Controller.java` |
