# Publishment Topics API — External (Public)

`publishment_topics` is one shared lookup table that holds the subject taxonomy for every publishment
type in the system: videos, sound tracks, image collections and writings. Instead of a fixed Java enum,
topics are rows, so editors can add "Documentary", "Folk song" or "Linguistics" without a redeploy. Each
row carries a bilingual label — `nameCkb` (Sorani) and `nameKmr` (Kurmanji) — and an `entityType` string
that keeps the four taxonomies from mixing.

The two endpoints documented here are the public read side: the website uses them to render filter chips,
topic dropdowns and the human-readable topic label next to a video, track, gallery or book. Creating,
renaming and deleting topics requires a JWT and lives in
[`../internal/TOPIC_API.md`](../internal/TOPIC_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/topics` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/topic/PublishmentTopicController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/topic/PublishmentTopicService.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/publishment/topic/PublishmentTopicRepository.java` |
| **Entities** | `PublishmentTopic` (referenced by `Video`, `SoundTrack`, `ImageCollection`, `Writing`) |
| **Table** | `publishment_topics` |
| **Verified against source** | 2026-08-26 |

---

## Authorization

`SecurityConfig` contains no rule naming `/api/v1/topics`. Both endpoints below are covered by the
catch-all public read rule:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

There is no `@PreAuthorize` anywhere on `PublishmentTopicController`, so nothing narrows that. Anonymous
`GET` is fully allowed — no `Authorization` header, no cookie, no CORS credentials needed.

Sending a valid JWT anyway is harmless; it changes nothing about the response.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/topics/{entityType}` | None | — | Every topic registered for one publishment type |
| 2 | `GET` | `/api/v1/topics/{entityType}/{id}` | None | — | One topic by primary key |

Both return a **bare JSON body — no `ApiResponse` envelope.** Endpoint 1 returns a JSON array, endpoint 2
returns a single JSON object. This differs from the per-domain convenience endpoints
(`/api/v1/videos/topics`, `/api/v1/sound-tracks/topics`, …), which *are* wrapped in
`{ "success": …, "message": …, "data": … }`. See
[Per-domain convenience endpoints](#per-domain-convenience-endpoints) before you pick one.

---

## The topic object

Both endpoints serialise the JPA entity `PublishmentTopic` directly — there is no response DTO and no
mapper, so the JSON field names are exactly the entity's field names.

| Field | Type | Nullable | Column | Notes |
|-------|------|----------|--------|-------|
| `id` | `long` | no | `id` (identity) | Primary key. This is the value content records store in `topic_id`. |
| `entityType` | `string` | no | `entity_type varchar(20)` | `VIDEO`, `SOUND`, `IMAGE` or `WRITING`. Always stored upper-case. |
| `nameCkb` | `string \| null` | yes | `name_ckb varchar(300)` | Sorani (Central Kurdish) label. |
| `nameKmr` | `string \| null` | yes | `name_kmr varchar(300)` | Kurmanji (Northern Kurdish) label. |
| `createdAt` | `string` (ISO-8601 local date-time) | no | `created_at` | Set by `@PrePersist`. |
| `updatedAt` | `string` (ISO-8601 local date-time) | no | `updated_at` | Refreshed by `@PreUpdate` on every rename. |

Both name columns are nullable and **neither is individually required**. The write endpoints in
`/api/v1/topics` do not validate them at all, and the per-domain creators require only *one* of the two.
A topic with a Sorani label but no Kurmanji label is normal and appears in production data. Render with a
fallback:

```js
const label = topic.nameCkb || topic.nameKmr || `#${topic.id}`;
```

> **Note:** `PublishmentTopic` carries no `@JsonInclude`, and `JacksonConfig` registers a plain
> `new ObjectMapper()` bean that replaces Spring Boot's auto-configured one — so
> `spring.jackson.default-property-inclusion: non_null` in `application.yaml` does not reach this
> serialiser. Expect a missing Kurmanji label to come back as `"nameKmr": null` rather than being
> omitted. Write your client to tolerate both shapes; do not treat the key's presence as meaning it has
> a value.

### Timestamps

`createdAt` / `updatedAt` are `LocalDateTime`. Hibernate writes them to PostgreSQL in UTC
(`hibernate.jdbc.time_zone=UTC`), and the same custom `ObjectMapper` above serialises them with
`WRITE_DATES_AS_TIMESTAMPS` disabled, i.e. ISO-8601 without an offset — `"2026-04-03T15:11:38"`. There is
no `Z` suffix and no zone information in the string; do not feed it straight into a formatter that
assumes local browser time without deciding on a zone first.

---

## How `{entityType}` is resolved

`{entityType}` is a plain `String` path variable, not an enum. `PublishmentTopicService.getAllByType`
does exactly one thing with it:

```java
return topicRepository.findByEntityType(entityType.toUpperCase());
```

Consequences you can rely on:

| Request | Behaviour |
|---------|-----------|
| `/api/v1/topics/VIDEO` | Matches. |
| `/api/v1/topics/video` | Matches — the value is upper-cased before the query. |
| `/api/v1/topics/Video` | Matches, same reason. |
| `/api/v1/topics/vIdEo` | Matches. |
| `/api/v1/topics/MUSIC` | **`200 OK` with `[]`.** Unknown types are not rejected. |
| `/api/v1/topics/SOUNDS` | `200 OK` with `[]` — the match is exact after upper-casing, not a prefix or plural match. |
| `/api/v1/topics` | `404 NOT_FOUND` — there is no mapping for the bare base path. |

> **Note:** There is no allow-list check on `{entityType}` anywhere in the controller, the service or the
> entity. An unrecognised type is indistinguishable from a recognised type that happens to have no rows —
> both are `200` + `[]`. Hard-code the four valid values in your client rather than probing the API.

The four canonical values, and the constants that produce them on the write side:

| `entityType` | Content entity | Table | Constant in code |
|--------------|----------------|-------|------------------|
| `VIDEO` | `Video` | `videos` | `VideoService.TOPIC_ENTITY_TYPE` |
| `SOUND` | `SoundTrack` | `sound_tracks` | `SoundTrackService.TOPIC_ENTITY_TYPE` |
| `IMAGE` | `ImageCollection` | `image_collections` | `ImageCollectionService.TOPIC_ENTITY_TYPE` |
| `WRITING` | `Writing` | `writings` | `WritingService.ENTITY_TYPE` |

---

## 1. `GET /api/v1/topics/{entityType}` — Topics for one publishment type

Returns every topic row whose `entity_type` equals the upper-cased path segment. No pagination, no
filtering, no search, no sorting — the whole taxonomy for that type in one array, in whatever order
PostgreSQL returns it. Read-only; `@Transactional(readOnly = true)`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `entityType` | `string` | yes | `VIDEO`, `SOUND`, `IMAGE` or `WRITING`. Case-insensitive — upper-cased server-side. Any other value returns an empty array. |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None. `page`, `size`, `q` and `lang` are all ignored (`lang` is consumed by the global `LocaleChangeInterceptor` and only affects the localized `message` field of error bodies). |

**Request body**

None.

**Response `200 OK`** — a bare JSON array of topic objects.

```json
[
  {
    "id": 7,
    "entityType": "SOUND",
    "nameCkb": "گۆرانی فۆلکلۆری",
    "nameKmr": "Stranên Gelêrî",
    "createdAt": "2026-01-08T13:20:44",
    "updatedAt": "2026-01-08T13:20:44"
  },
  {
    "id": 12,
    "entityType": "SOUND",
    "nameCkb": "شیعری کوردی",
    "nameKmr": "Helbesta Kurdî",
    "createdAt": "2026-02-19T09:57:02",
    "updatedAt": "2026-03-04T11:02:15"
  },
  {
    "id": 19,
    "entityType": "SOUND",
    "nameCkb": "مۆسیقای کلاسیک",
    "nameKmr": null,
    "createdAt": "2026-04-03T15:11:38",
    "updatedAt": "2026-04-03T15:11:38"
  }
]
```

An empty taxonomy — or an unknown `entityType` — returns `[]` with status `200`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | `GET /api/v1/topics` with no type segment — no handler is mapped for the bare base path. |
| `405` | `METHOD_NOT_ALLOWED` | Wrong verb against this exact path, e.g. `PATCH /api/v1/topics/VIDEO`. `details` carries `usedMethod` and `supportedMethods`. |
| `500` | `INTERNAL_ERROR` | Database unavailable. `details` carries `traceId` and a support hint. |

There is no `400` for a bad `entityType` — see the note above.

**Example**

```bash
curl -s http://localhost:8080/api/v1/topics/SOUND
```

```bash
# lower-case works identically
curl -s http://localhost:8080/api/v1/topics/writing
```

---

## 2. `GET /api/v1/topics/{entityType}/{id}` — One topic by id

Fetches a single topic row by primary key. Read-only.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `entityType` | `string` | yes | Bound by Spring, then **discarded** — see the note below. Any non-empty segment satisfies the route. |
| `id` | `long` | yes | Primary key of the topic. |

> **Note:** The handler signature accepts `entityType`, but the body is
> `return ResponseEntity.ok(topicService.getById(id));` — the type segment is never compared against the
> row that comes back. `GET /api/v1/topics/VIDEO/7` happily returns topic 7 even when topic 7 is a
> `SOUND` topic, and `GET /api/v1/topics/anything/7` returns the same row. Always check the
> `entityType` field on the response yourself if the distinction matters to your UI. The per-domain
> lookups inside `VideoService`, `SoundTrackService` and `ImageCollectionService` *do* enforce the match
> and reject a cross-type id — this endpoint does not.

**Query parameters**

None.

**Request body**

None.

**Response `200 OK`** — a bare topic object (no array, no envelope).

```json
{
  "id": 7,
  "entityType": "VIDEO",
  "nameCkb": "بەڵگەنامەیی",
  "nameKmr": "Belgefîlm",
  "createdAt": "2026-01-08T13:20:44",
  "updatedAt": "2026-01-08T13:20:44"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | **No topic with that id.** See the note below — this is *not* a `404`. |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long`, e.g. `/api/v1/topics/VIDEO/abc`. |
| `405` | `METHOD_NOT_ALLOWED` | Wrong verb against this path. |

> **Note:** `PublishmentTopicService.getById` throws a bare
> `new RuntimeException("Topic not found: " + id)`. `GlobalExceptionHandler` has no handler for plain
> `RuntimeException`, so it falls through to `@ExceptionHandler(Exception.class)` and the client receives
> **`500 INTERNAL_ERROR`**, not `404 NOT_FOUND`. The same applies to a non-numeric `{id}`:
> `MethodArgumentTypeMismatchException` is likewise unhandled and produces `500`. Every other publishment
> domain in this codebase returns a proper `404` with a domain `code` for a missing id — this controller
> is the exception. Do not branch on `404` here; treat `500` from this endpoint as "possibly not found"
> and prefer resolving topics from the list endpoint.

Missing-topic response body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 500,
  "path": "/api/v1/topics/VIDEO/9999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "INTERNAL_ERROR",
  "message": "An unexpected error occurred. Please try again later.",
  "messageEn": "An unexpected error occurred. Please try again later.",
  "messageKu": "هەڵەیەکی چاونەچاواو ڕوویدا. تکایە دواتر هەوڵبدەرەوە.",
  "fieldErrors": null,
  "details": {
    "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
    "hint": "If this error persists, please contact support and provide the traceId above."
  }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/topics/VIDEO/7
```

---

## How content references a topic

A topic is never embedded as a nested object inside a content row's own table. Each of the four content
entities holds a nullable `@ManyToOne` FK:

| Entity | File | Mapping |
|--------|------|---------|
| `Video` | `model/publishment/video/Video.java` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "topic_id")` |
| `SoundTrack` | `model/publishment/sound/SoundTrack.java` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "topic_id")` |
| `ImageCollection` | `model/publishment/image/ImageCollection.java` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "topic_id")` |
| `Writing` | `model/publishment/writing/Writing.java` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "topic_id")` |

The relation is **nullable everywhere** — a video, track, gallery or book may have no topic at all. Build
your UI so an absent topic is a normal state, not an error.

### What the content APIs actually return

The content read APIs flatten the relation rather than nesting the entity, so you usually do not need to
call `/api/v1/topics` at all to render a label:

| Domain | Fields on the response DTO |
|--------|---------------------------|
| Videos | `topicId`, `topicNameCkb`, `topicNameKmr` |
| Sound tracks | `topicId`, `topicNameCkb`, `topicNameKmr` |
| Image collections | `topicId`, `topicNameCkb`, `topicNameKmr` |
| Writings | nested `topic` object (`TopicInfo`), plus flattened `topicId`, `topicNameCkb`, `topicNameKmr` aliases exposed through `@JsonProperty` getters for public-website compatibility |

You need this API — or a per-domain `/topics` endpoint — for the *other* direction: listing the whole
taxonomy so a visitor can filter by topic. Videos accept `?topicId=` as a filter on
`GET /api/v1/videos`; see [`VIDEO_API.md`](VIDEO_API.md).

Because `PublishmentTopic` is annotated `@BatchSize(size = 50)` at class level, loading a page of N
content rows costs one extra `IN (...)` query for all their topics instead of N lazy loads. You do not
have to request topics separately to avoid an N+1 on the server.

---

## Per-domain convenience endpoints

Four controllers expose their own read-only topic list. They hit the same table and the same
`findByEntityType` query, but the shape differs, so pick deliberately:

| Endpoint | Auth | Envelope | Item fields | Source |
|----------|------|----------|-------------|--------|
| `GET /api/v1/topics/{entityType}` | None | **none** (bare array) | `id`, `entityType`, `nameCkb`, `nameKmr`, `createdAt`, `updatedAt` | `PublishmentTopicController` → `PublishmentTopicService` |
| `GET /api/v1/videos/topics` | None | none (bare array of `VideoDTO.TopicView`) | `id`, `nameCkb`, `nameKmr`, `createdAt` — nulls omitted (`@JsonInclude(NON_NULL)`) | `VideoController` → `VideoService.getTopics()` |
| `GET /api/v1/sound-tracks/topics` | None | `ApiResponse<List<Map>>` | `id`, `nameCkb`, `nameKmr` — nulls coerced to `""` | `SoundTrackController` → `PublishmentTopicRepository` directly |
| `GET /api/v1/image-collections/topics` | None | `ApiResponse<List<Map>>` | `id`, `nameCkb`, `nameKmr` — nulls coerced to `""` | `ImageCollectionController` → `PublishmentTopicRepository` directly |
| `GET /api/v1/writings/topics` | None | `ApiResponse<List<Map>>` | `id`, `nameCkb`, `nameKmr` — nulls coerced to `""` | `WritingController` → `PublishmentTopicRepository` directly |

Practical guidance:

- For a **filter chip row or a topic dropdown**, the per-domain endpoint is the friendlier payload: the
  three `ApiResponse`-wrapped ones already turn a missing label into `""`, so you never render `null`.
- For anything that needs `entityType` or `updatedAt` — an admin picker, a cache-busting check, a
  cross-type view — use `/api/v1/topics/{entityType}`, which is the only one that returns them.
- The three `Map`-based endpoints are built with `Map.of(...)`, whose iteration order is unspecified;
  JSON object key order from them is not stable. Read by key, never by position.

> **Note:** The `PublishmentTopicController` class javadoc states that `SoundTrackController` and
> `VideoController` "both delegate to this service so there is one source of truth." They do not.
> `SoundTrackController`, `ImageCollectionController` and `WritingController` inject
> `PublishmentTopicRepository` and query it inline; `VideoController` calls `VideoService.getTopics()`.
> `PublishmentTopicService` is injected by `PublishmentTopicController` and nothing else. The data is
> still one table, so the *rows* agree — but the response shapes do not, and no single service method is
> shared.

---

## Enums used by this API

None. This API's discriminator is deliberately *not* an enum:

| Concept | Actual type | Values |
|---------|-------------|--------|
| `entityType` | `String` — `varchar(20)`, no `@Enumerated`, no validation | `VIDEO`, `SOUND`, `IMAGE`, `WRITING` by convention only |

The bilingual split maps onto the platform-wide `Language` enum, but that enum is not part of any request
or response on this API:

| `Language` | Field on `PublishmentTopic` | Script / dialect |
|------------|-----------------------------|------------------|
| `CKB` | `nameCkb` | Sorani (Central Kurdish), Arabic script |
| `KMR` | `nameKmr` | Kurmanji (Northern Kurdish), Latin script |

There is a legacy `WritingTopic` enum at
`src/main/java/ak/dev/khi_backend/khi_app/enums/publishment/WritingTopic.java`. It is unrelated to this
table and unused — `BookGenre` replaced it, and books now carry both a `bookGenres` set and a
`PublishmentTopic` FK.

---

## Notes & gotchas

- **No envelope.** Endpoints 1 and 2 return raw JSON. Code that unconditionally reads `response.data`
  will read `undefined` here. Only the per-domain `/topics` endpoints are wrapped.
- **No ordering.** `findByEntityType` has no `ORDER BY`. Row order is whatever PostgreSQL returns and can
  change between calls. Sort client-side — by `nameCkb` with a locale-aware comparator, or by `id` for
  stability.
- **No pagination.** The full taxonomy comes back in one response. That is fine at the current scale
  (tens of rows per type), but do not assume a `page`/`size` contract will appear silently.
- **Duplicates are possible.** `publishment_topics` has three indexes — `idx_topic_entity_type`,
  `idx_topic_name_ckb` (`entity_type, name_ckb`), `idx_topic_name_kmr` (`entity_type, name_kmr`) — and
  **none of them is unique**. Two rows named "بەڵگەنامەیی" under `VIDEO` can and do coexist if created
  through different paths. De-duplicate by trimmed, case-folded name in the UI if a clean chip row
  matters.
- **Not cached.** `PublishmentTopicService` carries no `@Cacheable` / `@CacheEvict`; every call is a live
  query. The surrounding content services *are* cached in Redis (caches `soundTracks`,
  `imageCollections`, …, key prefix `khi:`, 10-minute default TTL) and their cached payloads embed
  `topicNameCkb` / `topicNameKmr`. Renaming a topic through `PUT /api/v1/topics/{id}` does **not** evict
  those caches, so a content list can serve the old label for up to 10 minutes while
  `/api/v1/topics/{entityType}` already shows the new one. Details in
  [`../internal/TOPIC_API.md`](../internal/TOPIC_API.md).
- **Repository javadoc oversells it.** `PublishmentTopicRepository`'s comment describes
  `searchByNameCkb` / `searchByNameKmr` autocomplete methods. Neither exists — the interface declares
  exactly one method, `findByEntityType`. There is no server-side topic search; fetch the list and filter
  in the client.
- **`404` vs `500`.** Only endpoint 1's missing base path yields a real `404`. A missing *topic* yields
  `500` (endpoint 2). This is a bug in the service, documented above, not a convention to imitate.
- **Error bodies are bilingual.** Every error is an `ApiErrorResponse` carrying both `messageEn` and
  `messageKu`; `message` is resolved against `Accept-Language` (`en`, `ckb`, `kmr` are the supported
  tags, `?lang=` also works via `LocaleChangeInterceptor`).

---

## Related documentation

- Counterpart (create / rename / delete topics): [`../internal/TOPIC_API.md`](../internal/TOPIC_API.md)
- Video topics and the `topicId` filter: [`VIDEO_API.md`](VIDEO_API.md) —
  [`GET /api/v1/videos/topics`](VIDEO_API.md#6-get-apiv1videostopics--list-video-topics)
- Sound topics: [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md) —
  [`GET /api/v1/sound-tracks/topics`](SOUNDTRACK_API.md#11-get-apiv1sound-trackstopics--sound-topic-list)
- Image topics: [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md) —
  [`GET /api/v1/image-collections/topics`](IMAGE_COLLECTION_API.md#5-get-apiv1image-collectionstopics--the-image-topic-taxonomy)
- Writing topics: [`WRITING_API.md`](WRITING_API.md)
- Database schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups `public`, `internal`,
  `all`). `OpenApiConfig` lists `/api/v1/topics/**` under the `public` group — including the
  authenticated write endpoints, because the grouping is by path prefix, not by required role.
- Servers: `http://localhost:8080` (local); production is deployed on Railway.
