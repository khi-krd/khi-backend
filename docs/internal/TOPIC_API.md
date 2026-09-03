# Publishment Topics API — Internal (Authenticated)

Write access to `publishment_topics`, the one shared taxonomy table behind videos, sound tracks, image
collections and writings. These three endpoints let the admin dashboard add a subject, rename it in
either Kurdish dialect, and remove it. Everything here mutates a table that four content domains read
from, so a change is immediately visible across the whole public site.

Every endpoint on this page requires a JWT. Read the [Authorization](#authorization) section carefully
before you build against it — the effective rule is looser than the rest of the content API, and it is
almost certainly not what was intended. The public read endpoints are in
[`../external/TOPIC_API.md`](../external/TOPIC_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/topics` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/topic/PublishmentTopicController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/topic/PublishmentTopicService.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/publishment/topic/PublishmentTopicRepository.java` |
| **Entities** | `PublishmentTopic` (referenced by `Video`, `SoundTrack`, `ImageCollection`, `Writing`) |
| **Table** | `publishment_topics` |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either way — `JWTAuthenticationFilter` accepts both:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

or the HttpOnly cookie whose name comes from the `JWT_COOKIE_NAME` environment variable. Sessions are
stateless (`SessionCreationPolicy.STATELESS`); there is no server-side session to keep alive.

Granted authorities are `ROLE_<NAME>` plus the permission strings `user:create`, `user:read`,
`user:update`, `user:delete`. Roles, weakest to strongest: `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`.
`GUEST` is the default role assigned on self-registration.

---

## Authorization

`SecurityConfig` has **no rule that names `/api/v1/topics`**. Walk the ladder for a
`POST /api/v1/topics/VIDEO` and every matcher misses:

| Rule | Matches? |
|------|----------|
| `OPTIONS /**` | no — wrong method |
| Swagger / OpenAPI paths | no |
| `/api/auth/**`, `/api/user/**`, `/api/users/**` | no |
| `POST` allow-list (`/api/v1/contact/messages`, donations) | no |
| `GET` / `PATCH` admin allow-lists | no |
| `/api/v1/media/**` | no |
| `POST` admin allow-list (`/api/v1/featured/**`, `/about/team/**`, `/about/partners/**`, `/settings/social/**`, `/nav-menu/**`) | no |
| `PUT` / `DELETE` admin allow-lists | no |
| `/api/v1/about/**`, `/api/v1/contact/**`, `/api/v1/services/**` | no |
| `GET /api/v1/**` → `permitAll` | no — wrong method |
| `POST`/`PUT`/`DELETE` content allow-lists (`projects`, `news`, `videos`, `image-collections`, `sound-tracks`, `albums`, `writings`) | no — `topics` is not in the list |
| **`.anyRequest().authenticated()`** | **yes** |

There is no `@PreAuthorize` anywhere on `PublishmentTopicController` to narrow that, and
`@EnableMethodSecurity` therefore has nothing to enforce.

| Endpoint | SecurityConfig rule | `@PreAuthorize` | Effective |
|----------|--------------------|-----------------|-----------|
| `POST /api/v1/topics/{entityType}` | `anyRequest().authenticated()` | none | **Any authenticated user, `GUEST` included** |
| `PUT /api/v1/topics/{id}` | `anyRequest().authenticated()` | none | **Any authenticated user, `GUEST` included** |
| `DELETE /api/v1/topics/{id}` | `anyRequest().authenticated()` | none | **Any authenticated user, `GUEST` included** |

> **Note:** This is a real authorization gap, not a documentation simplification. Everywhere else in the
> platform, content writes are `EMPLOYEE`+ and deletes are `ADMIN`+ — the equivalent per-domain topic
> endpoints prove it: `POST /api/v1/videos/topics` requires `EMPLOYEE`, `ADMIN` or `SUPER_ADMIN`, and
> `DELETE /api/v1/videos/topics/{topicId}` requires `ADMIN` or `SUPER_ADMIN`, both inherited from the
> `/api/v1/videos/**` rules. The `/api/v1/topics` path is simply absent from every allow-list, so a
> freshly self-registered `GUEST` account holding a valid JWT can create, rename and delete rows in the
> taxonomy that the entire public site renders from. Treat these three endpoints as `ADMIN`-only in your
> dashboard's own UI gating, and do not rely on the server to reject a lower role. Fixing this means
> adding `/api/v1/topics/**` to the `POST`/`PUT` (`EMPLOYEE`+) and `DELETE` (`ADMIN`+) matchers in
> `SecurityConfig`, or putting `@PreAuthorize` on the three handlers.

### What an auth failure actually looks like

Auth failures on this path are produced by the security filter chain, **not** by
`GlobalExceptionHandler`, so they are not `ApiErrorResponse` bodies:

| Situation | Response |
|-----------|----------|
| Expired token | `401` with `JWTAuthenticationFilter`'s own body: `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}`. The auth cookie is cleared. |
| Malformed / unverifiable token | `403` with `{"error":"INVALID_TOKEN","message":"Invalid token"}`. The auth cookie is cleared. |
| Blacklisted token (logged out, session revoked) | `401` with `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}`. The auth cookie is cleared. |
| No token at all | Spring Security's default entry point answers with a bare `403` and an empty body. |

> **Note:** `user/exceptions/JwtAuthenticationEntryPoint` and `user/exceptions/JwtAccessDeniedHandler`
> exist as `@Component` beans but `SecurityConfig` never calls `.exceptionHandling(...)`, so neither is
> wired into the filter chain. Spring Security does not auto-detect them. The consequence is the last row
> above: a missing credential produces `403`, not the `401` you would expect. Branch on the presence of
> an `error` key, not on the status code alone.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/topics/{entityType}` | JWT | any authenticated (see above) | Create a topic under one publishment type |
| 2 | `PUT` | `/api/v1/topics/{id}` | JWT | any authenticated (see above) | Rename a topic in one or both dialects |
| 3 | `DELETE` | `/api/v1/topics/{id}` | JWT | any authenticated (see above) | Hard-delete a topic row |

All three return a **bare JSON body — no `ApiResponse` envelope**. `POST` and `PUT` serialise the
`PublishmentTopic` entity directly; `DELETE` returns no body at all.

---

## The topic object

There is no response DTO. The JPA entity is serialised as-is, so response field names are the entity's
field names.

| Field | Type | Nullable | Column | Notes |
|-------|------|----------|--------|-------|
| `id` | `long` | no | `id` (identity) | Assigned by PostgreSQL on insert. |
| `entityType` | `string` | no | `entity_type varchar(20)` | Set from the path segment on create, upper-cased. **Immutable afterwards** — no endpoint changes it. |
| `nameCkb` | `string \| null` | yes | `name_ckb varchar(300)` | Sorani (Central Kurdish) label. |
| `nameKmr` | `string \| null` | yes | `name_kmr varchar(300)` | Kurmanji (Northern Kurdish) label. |
| `createdAt` | `string` (ISO-8601 local date-time) | no | `created_at`, not updatable | Set by `@PrePersist`. |
| `updatedAt` | `string` (ISO-8601 local date-time) | no | `updated_at` | Refreshed by `@PreUpdate` on every successful `PUT`. |

Timestamps are `LocalDateTime` stored in UTC (`hibernate.jdbc.time_zone=UTC`) and serialised without an
offset — `"2026-04-03T15:11:38"`. `JacksonConfig` registers a plain `new ObjectMapper()` bean that
replaces Boot's auto-configured one, so `spring.jackson.*` settings in `application.yaml` do not apply
here; in particular `default-property-inclusion: non_null` is not in effect and a null `nameKmr` comes
back as `"nameKmr": null`.

---

## 1. `POST /api/v1/topics/{entityType}` — Create a topic

Inserts one row into `publishment_topics`. `entityType` comes from the path (upper-cased); the two
labels come from the JSON body. `@Transactional`.

The full service body is three statements — build, save, return. There is **no validation of any kind**:
no `@Valid` on the parameter, no constraints on the record, no allow-list on `entityType`, no blank
check, no duplicate check, no trimming. Everything below follows from that.

**Auth:** `Authorization: Bearer <token>` (or the JWT cookie). Effective roles: any authenticated user —
see [Authorization](#authorization).
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `entityType` | `string` | yes | `VIDEO`, `SOUND`, `IMAGE` or `WRITING`. Case-insensitive — `.toUpperCase()` is applied before persisting. **Not validated**: any string ≤ 20 characters is accepted and stored verbatim. |

**Query parameters**

None.

**Request body** — `PublishmentTopicController.TopicRequest`, a Java record:

```java
public record TopicRequest(String nameCkb, String nameKmr) {}
```

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `nameCkb` | `string` | no | none declared; DB column is `varchar(300)` | Sorani label. Stored exactly as sent — **not trimmed**. |
| `nameKmr` | `string` | no | none declared; DB column is `varchar(300)` | Kurmanji label. Stored exactly as sent — **not trimmed**. |

The body itself is required (`@RequestBody` defaults to `required = true`), but both of its fields may be
`null` or absent. `{}` is accepted and produces a topic with no label at all.

Unknown JSON properties are rejected. `JacksonConfig` installs a `DeserializationProblemHandler` that
silently swallows exactly one unknown key — `id`, so that a response-shaped payload can be replayed — and
lets every other unknown key raise `UnrecognizedPropertyException` → `400 BAD_REQUEST` with
`details.unknownField`.

```json
{
  "nameCkb": "بەڵگەنامەیی",
  "nameKmr": "Belgefîlm"
}
```

A single-dialect topic is legal:

```json
{
  "nameCkb": "چاوپێکەوتنی مێژوویی"
}
```

**Response `201 CREATED`** — the persisted entity, bare (no envelope).

```json
{
  "id": 27,
  "entityType": "VIDEO",
  "nameCkb": "بەڵگەنامەیی",
  "nameKmr": "Belgefîlm",
  "createdAt": "2026-08-26T09:14:22",
  "updatedAt": "2026-08-26T09:14:22"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Body missing, empty, or not parseable JSON (`HttpMessageNotReadableException`). |
| `400` | `BAD_REQUEST` | Unknown field in the body — any key other than `nameCkb`, `nameKmr`, `id`. `details.unknownField` names it. |
| `401` / `403` | — | Missing, expired, revoked or invalid token. Body is the filter's own JSON or empty — not `ApiErrorResponse`. See [above](#what-an-auth-failure-actually-looks-like). |
| `405` | `METHOD_NOT_ALLOWED` | Wrong verb, e.g. `POST /api/v1/topics/VIDEO/7`. `details` carries `usedMethod` and `supportedMethods`. |
| `409` | `CONFLICT` | Value too long for its column — `entityType` over 20 chars, either name over 300 chars. Surfaces as `DataIntegrityViolationException`. |
| `500` | `INTERNAL_ERROR` | `Content-Type` is not `application/json` (`HttpMediaTypeNotSupportedException` is not handled and falls through to the catch-all), or the database is unavailable. |

> **Note:** The `409` body is generic and its `details.hint` reads *"The username or email you provided is
> already in use."* — the `DataIntegrityViolationException` handler was written for the user-registration
> path and is reused verbatim for every DB constraint violation in the application. Ignore the hint text;
> use the status and `code`.

**Behaviour to plan around**

- **Duplicates are allowed.** No unique constraint exists on the table. The three indexes
  (`idx_topic_entity_type`, `idx_topic_name_ckb` on `entity_type, name_ckb`, `idx_topic_name_kmr` on
  `entity_type, name_kmr`) are all non-unique. Posting the same name twice creates two rows with
  different ids, and both appear in the public list. Check for an existing match client-side before
  creating.
- **Nothing is trimmed.** `"  بەڵگەنامەیی  "` is stored with its spaces and will not match a later
  lookup. Trim before you send. (Contrast: the per-domain creators call `trimOrNull`.)
- **A nameless topic is creatable.** `POST /api/v1/topics/VIDEO` with body `{}` succeeds and returns a
  row with both names `null`. Nothing in the system can render it. The per-domain creators reject this
  (`POST /api/v1/videos/topics` throws `BadRequestException` with message key
  `video.topic.names.required` when both names are blank); this endpoint does not.
- **A garbage `entityType` is creatable.** `POST /api/v1/topics/PODCAST` returns `201` and writes
  `entity_type = 'PODCAST'`. No content entity ever queries that value, so the row is orphaned from
  birth and is only reachable through `GET /api/v1/topics/PODCAST`.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/topics/VIDEO \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nameCkb":"بەڵگەنامەیی","nameKmr":"Belgefîlm"}'
```

```bash
# lower-case path segment, single dialect
curl -s -X POST http://localhost:8080/api/v1/topics/writing \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nameCkb":"مێژووی کوردستان"}'
```

---

## 2. `PUT /api/v1/topics/{id}` — Rename a topic

Partial update of the two labels on an existing row. `@Transactional`.

```java
if (nameCkb != null) topic.setNameCkb(nameCkb);
if (nameKmr != null) topic.setNameKmr(nameKmr);
```

Note the path shape: **there is no `{entityType}` segment on this route.** `PUT /api/v1/topics/VIDEO/7`
does not exist and returns `405`. The correct call is `PUT /api/v1/topics/7`.

**Auth:** `Authorization: Bearer <token>` (or the JWT cookie). Effective roles: any authenticated user.
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `long` | yes | Primary key of the topic. A non-numeric value produces `500`, not `400` — see Errors. |

**Query parameters**

None.

**Request body** — the same `TopicRequest` record as endpoint 1.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `nameCkb` | `string` | no | none declared; `varchar(300)` | New Sorani label. **Omit or send `null` to leave it unchanged.** Sending `""` sets it to an empty string. |
| `nameKmr` | `string` | no | none declared; `varchar(300)` | New Kurmanji label. Same null-means-keep semantics. |

```json
{
  "nameKmr": "Belgefîlm"
}
```

**Response `200 OK`** — the updated entity, bare. `updatedAt` has moved; `createdAt` and `entityType`
have not.

```json
{
  "id": 27,
  "entityType": "VIDEO",
  "nameCkb": "بەڵگەنامەیی",
  "nameKmr": "Belgefîlm",
  "createdAt": "2026-08-26T09:14:22",
  "updatedAt": "2026-08-26T11:47:05"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Body missing or unparseable; unknown field in the body. |
| `401` / `403` | — | Missing, expired, revoked or invalid token. |
| `405` | `METHOD_NOT_ALLOWED` | `PUT /api/v1/topics/{entityType}/{id}` — that two-segment route has no `PUT` mapping. |
| `409` | `CONFLICT` | A name longer than 300 characters. |
| `500` | `INTERNAL_ERROR` | **No topic with that id** — see the note below. |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long`, e.g. `PUT /api/v1/topics/VIDEO`. |

> **Note:** `PublishmentTopicService.update` throws
> `new RuntimeException("Topic not found: " + id)`. `GlobalExceptionHandler` has no handler for plain
> `RuntimeException`, so the request falls through to `@ExceptionHandler(Exception.class)` and the client
> gets `500 INTERNAL_ERROR` instead of `404 NOT_FOUND`. The same applies to
> `MethodArgumentTypeMismatchException` from a non-numeric `{id}` — also unhandled, also `500`. Every
> other publishment domain uses the `Errors.notFound(...)` factory and returns a proper `404` with a
> domain code. Do not build "topic was deleted by someone else" handling on a `404` here.

Missing-topic response body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 500,
  "path": "/api/v1/topics/9999",
  "method": "PUT",
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

**Behaviour to plan around**

- **You cannot clear a label.** `null` means "leave alone", so there is no way to set `nameKmr` back to
  `null` through this endpoint. The closest you can get is `""`, which is a different value and will
  render as an empty chip rather than falling back to the other dialect. Delete and recreate if you truly
  need a `null`.
- **`entityType` is immutable.** No endpoint re-types a topic. To move a topic from `VIDEO` to `SOUND`
  you must create a new row, repoint the content records, and delete the old one.
- **Renames are not trimmed** — same as create.
- **Renames propagate instantly to the DB but not to Redis.** See
  [Cache interaction](#cache-interaction).

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/topics/27 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nameKmr":"Belgefîlm"}'
```

```bash
# rename both dialects at once
curl -s -X PUT http://localhost:8080/api/v1/topics/27 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nameCkb":"فیلمی بەڵگەنامەیی","nameKmr":"Fîlmê Belgeyî"}'
```

---

## 3. `DELETE /api/v1/topics/{id}` — Delete a topic

Hard-deletes the row. `@Transactional`. The service checks `existsById` and then calls `deleteById` —
nothing else:

```java
if (!topicRepository.existsById(id)) {
    throw new RuntimeException("Topic not found: " + id);
}
topicRepository.deleteById(id);
```

**Auth:** `Authorization: Bearer <token>` (or the JWT cookie). Effective roles: any authenticated user.
**Content-Type:** — (no request body)

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `long` | yes | Primary key of the topic. |

**Query parameters**

None.

**Request body**

None.

**Response `204 NO CONTENT`** — empty body.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` / `403` | — | Missing, expired, revoked or invalid token. |
| `405` | `METHOD_NOT_ALLOWED` | `DELETE /api/v1/topics/{entityType}/{id}` — no `DELETE` mapping on the two-segment route. |
| `409` | `CONFLICT` | **The topic is still referenced by at least one video / track / collection / writing.** See the note below. |
| `500` | `INTERNAL_ERROR` | No topic with that id (bare `RuntimeException`, same bug as endpoint 2). |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long`. |

> **Note:** This endpoint does **not** un-link referencing content first. `Video`, `SoundTrack`,
> `ImageCollection` and `Writing` each hold a `topic_id` foreign key, and Hibernate's `ddl-auto: update`
> creates real FK constraints for them. Deleting a topic that is still in use is rejected by PostgreSQL,
> surfaces as `DataIntegrityViolationException`, and returns `409 CONFLICT` with the generic duplicate-data
> hint. Compare `DELETE /api/v1/videos/topics/{topicId}`, which loads every `Video` with that
> `topicId`, sets `topic = null`, saves them, and only then deletes — that one always succeeds. If you
> need the un-linking behaviour, call the per-domain endpoint instead of this one.

**Safe deletion flow**

1. Find what still points at the topic — `GET /api/v1/videos?topicId=27`, or the equivalent filtered
   list for the other domains.
2. Clear the relation on each one. Every content update DTO carries a `clearTopic` flag for exactly this
   (`VideoDTO.clearTopic`, `SoundTrackDtos.UpdateRequest.clearTopic`,
   `ImageCollectionDTO.UpdateRequest.clearTopic`, `WritingDtos.UpdateRequest.clearTopic`), or you can
   repoint them at a replacement `topicId`.
3. `DELETE /api/v1/topics/27`.

Or, when the topic belongs to `VIDEO`, skip all of that and call
`DELETE /api/v1/videos/topics/27`, which does steps 1–3 in one transaction. There is no equivalent
un-linking delete for `SOUND`, `IMAGE` or `WRITING`.

**Example**

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X DELETE http://localhost:8080/api/v1/topics/27 \
  -H "Authorization: Bearer $TOKEN"
```

---

## How this taxonomy is consumed

Each content entity holds a nullable `@ManyToOne` FK to `PublishmentTopic`:

| Entity | Table | Mapping | Type guard on write |
|--------|-------|---------|---------------------|
| `Video` | `videos` | `@ManyToOne(LAZY) @JoinColumn(name = "topic_id")` | `VideoService.findTopicOrThrow` rejects a topic whose `entityType != "VIDEO"` with a `BadRequestException` (message key `topic.type.mismatch`) |
| `SoundTrack` | `sound_tracks` | same | `SoundTrackService.findSoundTopicOrThrow` rejects a non-`SOUND` topic (`SOUND_VALIDATION`) |
| `ImageCollection` | `image_collections` | same | `ImageCollectionService.findImageTopicOrThrow` rejects a non-`IMAGE` topic (`IMAGE_VALIDATION`) |
| `Writing` | `writings` | same | **none** — see the note below |

> **Note:** `WritingService.resolveTopic` looks the topic up by id and throws `NOT_FOUND` if it is
> missing, but it never checks `entityType`. A writing can therefore be linked to a `VIDEO` or `SOUND`
> topic, and it will then be invisible in `GET /api/v1/writings/topics` (which filters on
> `entityType = "WRITING"`) while still rendering a topic name on the book itself. The other three
> domains all guard against this. Validate `entityType` client-side when assigning a topic to a writing.

### Two ways to create a topic

Editors rarely call `POST /api/v1/topics/{entityType}` directly. Every content create/update DTO accepts
an inline topic instead, so a new subject can be introduced in the same request that creates the content:

| Domain | Assign existing | Create inline | Clear on update |
|--------|-----------------|---------------|-----------------|
| Videos | `topicId` | `newTopic: { nameCkb, nameKmr }` (`VideoDTO.InlineTopicRequest`) | `clearTopic: true` |
| Sound tracks | `topicId` | `newTopic: { nameCkb, nameKmr }` (`SoundTrackDtos.InlineTopicRequest`) | `clearTopic: true` |
| Image collections | `topicId` | `newTopic: { nameCkb, nameKmr }` (`ImageCollectionDTO.InlineTopicRequest`) | `clearTopic: true` |
| Writings | `topicId` | `newTopic: { nameCkb, nameKmr }` (`WritingDtos.TopicPayload`) | `clearTopic: true` |

`topicId` wins when both are sent. The inline creators set `entityType` from their own service constant,
trim the names, and — except for writings — require at least one non-blank name. **None of them
de-duplicates**: an inline topic always inserts a new row even when an identical name already exists
under that type. If you care about a clean taxonomy, resolve against
`GET /api/v1/topics/{entityType}` first and send `topicId`.

> **Note:** `PublishmentTopicService.findOrCreate` exists precisely to solve that — it scans the existing
> rows for the same `entityType` plus a case-insensitive, trimmed name match and reuses the row before
> creating. Its javadoc says it is "used by SoundTrackService / VideoService". It is not called from
> anywhere in the codebase; `PublishmentTopicService` is injected only by `PublishmentTopicController`,
> which never calls it either. It is dead code, and the de-duplication it promises does not happen at
> runtime.

### Per-domain topic endpoints

| Endpoint | Auth | Notes |
|----------|------|-------|
| `GET /api/v1/videos/topics` | public | Bare array of `VideoDTO.TopicView` (`id`, `nameCkb`, `nameKmr`, `createdAt`), nulls omitted |
| `POST /api/v1/videos/topics?nameCkb=…&nameKmr=…` | `EMPLOYEE`+ | **Query params, not a JSON body.** Rejects both-blank. Returns `201` + `TopicView` |
| `DELETE /api/v1/videos/topics/{topicId}` | `ADMIN`+ | Un-links every referencing video first, then deletes. Returns `204` |
| `GET /api/v1/sound-tracks/topics` | public | `ApiResponse<List<Map>>`, nulls coerced to `""` |
| `GET /api/v1/image-collections/topics` | public | `ApiResponse<List<Map>>`, nulls coerced to `""` |
| `GET /api/v1/writings/topics` | public | `ApiResponse<List<Map>>`, nulls coerced to `""` |

Only videos have write endpoints of their own. For `SOUND`, `IMAGE` and `WRITING`, `/api/v1/topics` is
the only management surface.

---

## Cache interaction

`PublishmentTopicService` has **no** `@Cacheable` or `@CacheEvict` annotations — every call here is a
live database round-trip, and every read through `/api/v1/topics/{entityType}` is immediately consistent
with your write.

The content services are a different story. Redis caching is on (`spring.cache.type: redis`, key prefix
`khi:`, default TTL 600 000 ms = 10 minutes), and the cached payloads embed the topic label:

| Cache name | Populated by | Contains topic data? |
|------------|--------------|----------------------|
| `soundTracks` | `SoundTrackService` list / detail / featured / album reads | yes — `topicNameCkb`, `topicNameKmr` |
| `imageCollections` | `ImageCollectionService` list / detail / filtered reads | yes — `topicNameCkb`, `topicNameKmr` |

Those caches are evicted (`allEntries = true`) when a sound track or image collection is created,
updated or deleted — **not** when a topic is renamed here. So after `PUT /api/v1/topics/{id}`:

- `GET /api/v1/topics/{entityType}` and the per-domain `/topics` lists show the new label immediately
  (none of them is cached).
- `GET /api/v1/sound-tracks` and `GET /api/v1/image-collections` may keep serving the **old** label for
  up to 10 minutes.

If a rename must appear everywhere at once, either wait out the TTL, flush the `khi:soundTracks*` /
`khi:imageCollections*` keys out of band, or touch one record in each affected domain to trigger the
`allEntries` eviction.

---

## Enums used by this API

None. The discriminator is deliberately not an enum:

| Concept | Actual type | Values |
|---------|-------------|--------|
| `entityType` | `String` — `varchar(20)`, no `@Enumerated`, no bean validation | `VIDEO`, `SOUND`, `IMAGE`, `WRITING` by convention only; anything else is accepted and stored |

The bilingual split corresponds to the platform-wide `Language` enum (`CKB` = Sorani, `KMR` = Kurmanji),
but that enum never appears in a request or response on this API — the dialect is encoded in the field
name (`nameCkb` / `nameKmr`).

---

## Notes & gotchas

- **Two different path shapes.** Create takes `{entityType}`; update and delete take `{id}`. Mixing them
  up gives you `405` (`PUT /api/v1/topics/VIDEO/7`) or `500`
  (`PUT /api/v1/topics/VIDEO` → `MethodArgumentTypeMismatchException`). There is no route that takes both.
- **No envelope on writes.** `POST` and `PUT` return the raw entity. Clients that unconditionally unwrap
  `response.data` will get `undefined`.
- **Not-found is `500`, not `404`,** on both `PUT` and `DELETE`. This is the single most important quirk
  on this page for error handling.
- **No uniqueness, no trimming, no blank check.** All three are enforced on the per-domain creators and
  on none of these endpoints. `/api/v1/topics` is the raw table.
- **No audit trail.** `VideoLog`, `SoundTrackLog`, `ImageCollectionLog` and `WritingLog` record changes to
  their own entities; nothing logs topic mutations beyond the service's `log.info` lines. A deleted topic
  leaves no record of who removed it.
- **No S3 involvement.** Topics are text only; nothing here touches storage.
- **OpenAPI grouping is misleading.** `OpenApiConfig` puts `/api/v1/topics/**` in the `public` group, so
  these three authenticated endpoints show up under "Public API (no auth)" in Swagger UI. The grouping is
  by path prefix, not by required role.
- **Error bodies are bilingual** — every `ApiErrorResponse` carries `messageEn` and `messageKu`, with
  `message` resolved against `Accept-Language` (`en`, `ckb`, `kmr`; `?lang=` also works). Auth failures
  from the filter chain are the exception and use their own flat `{error, message}` shape.

---

## Related documentation

- Counterpart (public reads, full field reference): [`../external/TOPIC_API.md`](../external/TOPIC_API.md)
- Video topic writes and the un-linking delete: [`VIDEO_API.md`](VIDEO_API.md)
- Sound track topic assignment: [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md)
- Image collection topic assignment: [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md)
- Writing topic assignment: [`WRITING_API.md`](WRITING_API.md)
- Database schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups `public`, `internal`,
  `all`) — note the grouping caveat above.
- Servers: `http://localhost:8080` (local); production is deployed on Railway.
