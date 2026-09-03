# Services API — External (Public)

The services catalogue holds every service the Kurdish Heritage Institute offers — training courses,
events, programmes, workshops, studio facilities. Each record is one scroll section on the public
`/[locale]/services` page: a bilingual title and rich-text body, an ordered image/video gallery, an
optional hero video, and an anchor id for in-page navigation. These endpoints are read-only and need
no authentication; the public website calls them directly from the browser. Everything that writes to
the catalogue lives in the [internal counterpart](../internal/SERVICE_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/services` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/service/ServiceController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/service/ServiceService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/service/ServiceDTOs.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/service/ServiceRepository.java` |
| **Entities** | `Service`, `ServiceContent`, `ServiceMedia`, `ServiceAuditLog` |
| **Authorization** | `SecurityConfig`: `GET /api/v1/services/**` → `permitAll()` |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/services` | None | — | Active services, paginated, optional `?type=` filter |
| 2 | `GET` | `/api/v1/services/all` | None | — | Active services, paginated (no type filter) |
| 3 | `GET` | `/api/v1/services/featured` | None | — | Services flagged for the Services-page highlight rail |
| 4 | `GET` | `/api/v1/services/{id}` | None | — | One service with full detail |
| 5 | `GET` | `/api/v1/services/types` | None | — | Distinct service-type labels in use |
| 6 | `GET` | `/api/v1/services/search` | None | — | Full-text search across active services |

Two further `GET` routes under the same prefix are **not** public and are documented in the
[internal reference](../internal/SERVICE_API.md): `GET /api/v1/services/admin/all` and
`GET /api/v1/services/search/admin`. `SecurityConfig` lists both under an
`ADMIN`/`SUPER_ADMIN` matcher that is registered *before* the public
`GET /api/v1/services/**` rule, so the earlier, narrower rule wins.

---

## Response envelope

Every endpoint on this page returns the `ApiResponse<T>` envelope:

```json
{
  "success": true,
  "message": "Services fetched successfully",
  "data": {}
}
```

| Field | Type | Description |
|-------|------|-------------|
| `success` | boolean | Always `true` on a 2xx response |
| `message` | string | Fixed English operator string; not localised, not meant for display |
| `data` | object | The payload — a Spring `Page`, a `ServiceResponse`, or a string array |

`spring.jackson.default-property-inclusion` is `non_null`, so **any field whose value is `null` is
omitted from the JSON entirely** — it is not emitted as `"field": null`. Treat a missing key and a
null value as the same thing. Empty lists are still emitted as `[]`.

### Page shape

List endpoints put a Spring Data `Page` under `data`. The project runs Spring Data's default
`DIRECT` page serialization (`spring.data.web.pageable.serialization-mode` is not overridden), so the
whole `PageImpl` bean is serialized:

```json
{
  "success": true,
  "message": "Services fetched successfully",
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
    "totalElements": 7,
    "totalPages": 1,
    "last": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 7,
    "first": true,
    "empty": false
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `content` | array | The `ServiceResponse` objects for this page |
| `totalElements` | long | Total matching services across all pages |
| `totalPages` | int | Total page count |
| `number` | int | Zero-based index of this page |
| `size` | int | Page size that was applied |
| `numberOfElements` | int | Items in `content` |
| `first` / `last` / `empty` | boolean | Convenience flags |
| `pageable`, `sort` | object | Spring Data internals — do not build the UI on these |

The repository never applies a `Sort`, so `sort.unsorted` is always `true` even though the results
*are* ordered (the ordering is baked into the JPQL — see [Ordering](#ordering)).

---

## 1. `GET /api/v1/services` — List active services

Returns active services only, one page at a time, ordered for the public page. Passing `?type=` narrows
the result to a single service type (case-insensitive exact match on `serviceType`). Read-only; no side
effects. Results are cached in Redis for 10 minutes.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | None |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `type` | string | No | — | Service type label, matched case-insensitively against `serviceType` (`lower(s.serviceType) = lower(:type)`). Blank or absent means no filter. Values come from [`/api/v1/services/types`](#5-get-apiv1servicestypes--list-service-type-labels). |
| `page` | int | No | `0` | Zero-based page index. Must be `>= 0`. |
| `size` | int | No | `20` | Page size. Must be `>= 1`. No upper bound is enforced on this endpoint. |

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Services fetched successfully",
  "data": {
    "content": [
      {
        "id": 12,
        "serviceType": "Studio",
        "location": "سلێمانی — بنکەی کەلەپووری کوردی",
        "active": true,
        "publishedAt": "2026-05-14 09:30:00",
        "sortOrder": 1,
        "layoutType": "MEDIA_HERO",
        "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/studio-tour.mp4",
        "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-tour-poster.jpg",
        "navAnchorId": "recording-studio",
        "galleryMedia": [
          {
            "type": "IMAGE",
            "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/studio-hall-01.jpg",
            "alt": "هۆڵی تۆمارکردن"
          },
          {
            "type": "VIDEO",
            "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/studio-session.mp4",
            "posterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-session-poster.jpg",
            "alt": "دانیشتنی تۆمارکردن"
          }
        ],
        "featureImageUrls": [],
        "thumbnailUrls": [],
        "partnerIds": [3],
        "contents": [
          {
            "id": 41,
            "languageCode": "CKB",
            "title": "ستۆدیۆی تۆمارکردنی دەنگ",
            "description": "<p>ستۆدیۆیەکی تەواو بۆ تۆمارکردنی گۆرانی و چیرۆکی زارەکی کوردی.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-desk.jpg\" alt=\"مێزی تۆمار\">",
            "featureDescription": "ستۆدیۆیەکی پیشەیی بۆ تۆمارکردنی کەلەپووری دەنگی کوردی."
          },
          {
            "id": 42,
            "languageCode": "KMR",
            "title": "Stûdyoya Tomarkirina Deng",
            "description": "<p>Stûdyoyeke temam ji bo tomarkirina stran û çîrokên devkî yên kurdî.</p>",
            "featureDescription": "Stûdyoyeke pîşeyî ji bo tomarkirina mîrateya dengî ya kurdî."
          }
        ],
        "featured": true,
        "featuredOrder": 2,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-wide.jpg",
        "createdAt": "2026-05-14 09:30:00",
        "updatedAt": "2026-06-02 11:12:45"
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
    "totalElements": 7,
    "totalPages": 1,
    "last": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 7,
    "first": true,
    "empty": false
  }
}
```

An unknown `type` value is not an error — it simply produces an empty page.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` (`PageRequest.of` rejects it; `details.reason` carries the Spring message) |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer — see the [type-coercion note](#non-numeric-path-and-query-values-return-500) |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/services?page=0&size=20"
curl -s "http://localhost:8080/api/v1/services?type=Training&page=0&size=10"
```

---

## 2. `GET /api/v1/services/all` — List active services (no filter)

Returns exactly the same data as endpoint 1 called without `?type=`: **active services only**. The
handler (`ServiceController.getAllPublic`) calls `ServiceService.getAllActive(page, size)`, the same
method endpoint 1 uses, and shares the same Redis cache entries.

> **Note:** the class-level Javadoc on `ServiceController` describes this route as
> `admin: all incl. inactive (paginated)`. That comment is stale — the handler never calls the
> admin method and the route is `permitAll()` in `SecurityConfig`. Inactive services are only
> reachable through `GET /api/v1/services/admin/all`, which requires `ADMIN` or `SUPER_ADMIN`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | No | `0` | Zero-based page index. Must be `>= 0`. |
| `size` | int | No | `20` | Page size. Must be `>= 1`. |

**Response `200 OK`**

Identical shape to endpoint 1 — a `Page<ServiceResponse>` under `data`, with the message
`"Services fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/services/all?page=0&size=50"
```

### Which list endpoint should I call?

| Endpoint | Rows returned | Type filter | Pagination | Cache key | Auth |
|----------|---------------|-------------|------------|-----------|------|
| `GET /api/v1/services` | `active = true` only | Yes, `?type=` | `PageRequest.of(page, size)`, validated | `khi:services::active:p{page}:s{size}` or `khi:services::type:{type}:p{page}:s{size}` | Public |
| `GET /api/v1/services/all` | `active = true` only | No | `PageRequest.of(page, size)`, validated | `khi:services::active:p{page}:s{size}` (shared with the row above) | Public |
| `GET /api/v1/services/admin/all` | **All rows, including `active = false`** | No | `PageRequest.of(page, size)`, validated | `khi:services::all:p{page}:s{size}` | `ADMIN`, `SUPER_ADMIN` |

The public website should use `/api/v1/services/all` (or `/api/v1/services`) as its bulk fetch and
render one nav item plus one scroll section per record, in the order returned.

---

## 3. `GET /api/v1/services/featured` — Featured services

Returns the services whose `featured` flag is set. These drive the highlight rail inside the Services
page hero. They are page-level highlights, **not** homepage carousel slides, and are therefore not
bounded by `SiteSettings.maxFeaturedSlides` — any number of services can be featured at once.

This endpoint is deliberately **not cached**: the flag is written from `SiteContentService`, and the
list is small.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | No | `0` | Zero-based page index. **Clamped**, not validated: values below `0` become `0`. |
| `size` | int | No | `20` | Page size. **Clamped** into `1..100`: `0` becomes `1`, `500` becomes `100`. |

This is the only list endpoint in the domain that clamps instead of throwing — `getFeatured` applies
`Math.max(page, 0)` and `Math.min(Math.max(size, 1), 100)` before slicing an in-memory list.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Featured services fetched successfully",
  "data": {
    "content": [
      {
        "id": 12,
        "serviceType": "Studio",
        "location": "سلێمانی — بنکەی کەلەپووری کوردی",
        "active": true,
        "publishedAt": "2026-05-14 09:30:00",
        "sortOrder": 1,
        "layoutType": "MEDIA_HERO",
        "navAnchorId": "recording-studio",
        "galleryMedia": [
          {
            "type": "IMAGE",
            "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/studio-hall-01.jpg",
            "alt": "هۆڵی تۆمارکردن"
          }
        ],
        "featureImageUrls": [],
        "thumbnailUrls": [],
        "partnerIds": [3],
        "contents": [
          {
            "id": 41,
            "languageCode": "CKB",
            "title": "ستۆدیۆی تۆمارکردنی دەنگ",
            "description": "<p>ستۆدیۆیەکی تەواو بۆ تۆمارکردنی گۆرانی و چیرۆکی زارەکی کوردی.</p>",
            "featureDescription": "ستۆدیۆیەکی پیشەیی بۆ تۆمارکردنی کەلەپووری دەنگی کوردی."
          },
          {
            "id": 42,
            "languageCode": "KMR",
            "title": "Stûdyoya Tomarkirina Deng",
            "description": "<p>Stûdyoyeke temam ji bo tomarkirina stran û çîrokên devkî yên kurdî.</p>",
            "featureDescription": "Stûdyoyeke pîşeyî ji bo tomarkirina mîrateya dengî ya kurdî."
          }
        ],
        "featured": true,
        "featuredOrder": 2,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-wide.jpg",
        "createdAt": "2026-05-14 09:30:00",
        "updatedAt": "2026-06-02 11:12:45"
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

**Ordering:** `COALESCE(featuredOrder, 2147483647) ASC, id DESC` — an explicit `featuredOrder` wins,
services without one sort last, newest id first inside a tie.

> **Note:** `ServiceRepository.findFeaturedWithContents()` filters on `featured = true` only. A service
> that is featured but has been deactivated (`active = false`) is still returned here, even though it
> is absent from every other public list. Deactivating a service does **not** clear its `featured`
> flag. Guard against this in the UI, or unfeature before deactivating.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer |

Out-of-range `page`/`size` values cannot fail here — they are clamped.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/services/featured?page=0&size=20"
```

---

## 4. `GET /api/v1/services/{id}` — Get one service

Fetches a single service with its bilingual contents in one query
(`findByIdWithAll` uses `LEFT JOIN FETCH s.contents`). Not cached — the read goes to PostgreSQL every
time.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | long | Yes | Primary key of the service |

**Query parameters**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Service fetched successfully",
  "data": {
    "id": 12,
    "serviceType": "Studio",
    "location": "سلێمانی — بنکەی کەلەپووری کوردی",
    "active": true,
    "publishedAt": "2026-05-14 09:30:00",
    "sortOrder": 1,
    "layoutType": "MEDIA_HERO",
    "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/studio-tour.mp4",
    "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-tour-poster.jpg",
    "navAnchorId": "recording-studio",
    "galleryMedia": [
      {
        "type": "IMAGE",
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/gallery/studio-hall-01.jpg",
        "alt": "هۆڵی تۆمارکردن"
      }
    ],
    "featureImageUrls": [],
    "thumbnailUrls": [],
    "partnerIds": [3],
    "contents": [
      {
        "id": 41,
        "languageCode": "CKB",
        "title": "ستۆدیۆی تۆمارکردنی دەنگ",
        "description": "<p>ستۆدیۆیەکی تەواو بۆ تۆمارکردنی گۆرانی و چیرۆکی زارەکی کوردی.</p>",
        "featureDescription": "ستۆدیۆیەکی پیشەیی بۆ تۆمارکردنی کەلەپووری دەنگی کوردی."
      },
      {
        "id": 42,
        "languageCode": "KMR",
        "title": "Stûdyoya Tomarkirina Deng",
        "description": "<p>Stûdyoyeke temam ji bo tomarkirina stran û çîrokên devkî yên kurdî.</p>",
        "featureDescription": "Stûdyoyeke pîşeyî ji bo tomarkirina mîrateya dengî ya kurdî."
      }
    ],
    "featured": true,
    "featuredOrder": 2,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image/studio-wide.jpg",
    "createdAt": "2026-05-14 09:30:00",
    "updatedAt": "2026-06-02 11:12:45"
  }
}
```

> **Note:** `getById` does **not** filter on `active`. An inactive service is fully readable by an
> anonymous caller who knows or guesses its id, even though it never appears in any public list.
> Deactivating a service hides it from listings, it does not make it private.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No service with that id. `messageEn` is `"Resource not found"`, `details` is `{ "id": 12 }` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number, e.g. `/api/v1/services/studio` — see the [note below](#non-numeric-path-and-query-values-return-500) |

`404` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/services/999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NOT_FOUND",
  "message": "Resource not found",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "details": { "id": 999 }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/services/12
```

---

## 5. `GET /api/v1/services/types` — List service-type labels

Returns every distinct `serviceType` string currently stored, sorted alphabetically by PostgreSQL
(`SELECT DISTINCT s.serviceType FROM Service s ORDER BY s.serviceType`). Use it to build the type
filter chips on the Services page.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Service types fetched",
  "data": ["Event", "Program", "Studio", "Training", "Workshop"]
}
```

> **Note:** the query has no `WHERE s.active = true` clause, so this list includes types that only
> exist on inactive services. Filtering by such a type through
> `GET /api/v1/services?type=...` correctly returns an empty page, which reads as a dead filter chip
> in the UI. Cross-check the returned types against the types present in your list payload if that
> matters.

Values are free text entered by admins — the taxonomy is open, not an enum. Matching in the `?type=`
filter is case-insensitive, but the strings returned here preserve the stored casing.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| — | — | No client-triggerable errors |

**Example**

```bash
curl -s http://localhost:8080/api/v1/services/types
```

---

## 6. `GET /api/v1/services/search` — Search active services

One search box covering four columns at once, across both languages:

- `services.service_type`
- `services.location`
- `service_contents.title` (every `CKB` and `KMR` row)
- `service_contents.description` (the raw Tiptap HTML, so a hit may be inside markup)

The match is a case-insensitive `LIKE '%q%'` — no stemming, no relevance ranking, no tokenisation.
Only `active = true` services are searched. Results are cached in Redis for 10 minutes under
`khi:services::search:{q lowercased}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `q` | string | **Yes** | — | Search term. Trimmed before use. Must not be blank. |
| `page` | int | No | `0` | Zero-based page index. Must be `>= 0`. |
| `size` | int | No | `20` | Page size. Must be `>= 1`. |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Search results fetched",
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
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 0,
    "first": true,
    "empty": true
  }
}
```

Non-empty results carry the same `ServiceResponse` objects shown in endpoint 1.

**Ordering:** search results are ordered `publishedAt DESC, createdAt DESC` only — `sortOrder` is
**not** applied here, unlike the list endpoints.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `q` is absent from the URL. `details.missingParameter` is `"q"` |
| `400` | `BAD_REQUEST` | `q` is present but blank (`?q=` or `?q=%20`); `details.field` is `"q"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer |

`400` body for a missing `q`:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/services/search",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "MISSING_PARAMETER",
  "message": "Required parameter 'q' is missing.",
  "messageEn": "Required parameter 'q' is missing.",
  "messageKu": "پارامیتەری پێویستی 'q' نەگەیشتووە.",
  "details": {
    "missingParameter": "q",
    "expectedType": "String",
    "hint": "Append '?q=<value>' to your request URL."
  }
}
```

> **Note:** the underlying JPQL is
> `SELECT DISTINCT s.id FROM Service s LEFT JOIN s.contents c WHERE … ORDER BY s.publishedAt DESC, s.createdAt DESC`.
> PostgreSQL rejects a `SELECT DISTINCT` whose `ORDER BY` references columns that are not in the
> select list (`ERROR 42P10: for SELECT DISTINCT, ORDER BY expressions must appear in select list`) —
> the same rule `ServiceRepository` documents in the comment above `findFeaturedWithContents()`, where
> `DISTINCT` was deliberately dropped for exactly this reason. If this endpoint answers `500`
> `INTERNAL_ERROR` with an otherwise valid `q`, that is the cause. Verify against your deployed
> database before shipping a search UI on top of it.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/services/search?q=%D8%AA%DB%86%D9%85%D8%A7%D8%B1&page=0&size=20"
curl -s "http://localhost:8080/api/v1/services/search?q=studio"
```

---

## Object reference

### `ServiceResponse`

Source: `ServiceDTOs.ServiceResponse`. Remember that `null` fields are omitted from the JSON.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | long | No | Primary key. Fallback anchor target when `navAnchorId` is absent. |
| `serviceType` | string | No | Free-text type label, max 100 chars. See [Service-type taxonomy](#service-type-taxonomy). |
| `location` | string | Yes | Physical or virtual location, max 200 chars. |
| `active` | boolean | No | Soft-visibility flag. Always `true` in public lists; can be `false` in `/featured` and `/{id}`. |
| `publishedAt` | string | Yes | `yyyy-MM-dd HH:mm:ss`, **UTC**. `null` means never formally published — it does not hide the record. |
| `sortOrder` | int | Yes | Explicit display order, lower first. `null` sorts last. |
| `layoutType` | string | Yes | `MEDIA_HERO` \| `FEATURE_GRID` \| `DEFAULT`. Rendering hint only. |
| `heroVideoUrl` | string | Yes | Full-bleed hero video (S3 URL). |
| `heroPosterUrl` | string | Yes | Poster frame for `heroVideoUrl`. |
| `navAnchorId` | string | Yes | Slug for `#anchor` links, max 160 chars, globally unique (case-insensitive). |
| `galleryMedia` | array of [`MediaItem`](#mediaitem) | No (may be `[]`) | Ordered gallery slots. The recommended gallery model. |
| `featureImageUrls` | array of string | No (may be `[]`) | Legacy gallery fallback. Only meaningful when `galleryMedia` is empty. |
| `thumbnailUrls` | array of string | No (may be `[]`) | Legacy thumbnail fallback. Only meaningful when `galleryMedia` is empty. |
| `partnerIds` | array of long | No (may be `[]`) | Ids of `Partner` rows. Resolve them against `GET /api/v1/about/partners`. |
| `contents` | array of [`ServiceContentResponse`](#servicecontentresponse) | No (may be `[]`) | Bilingual rows, sorted by `languageCode` — `CKB` before `KMR`. |
| `featured` | boolean | No | Whether the service shows in the Services-page highlight rail. |
| `featuredOrder` | int | Yes | Highlight-rail order, lower first, `null` last. |
| `featureImageUrl` | string | Yes | Wide picture for the highlight rail. When absent, fall back to the first usable gallery picture (a `VIDEO` slot contributes its `posterUrl`), then `featureImageUrls[0]`, then `heroPosterUrl`. |
| `createdAt` | string | Yes | `yyyy-MM-dd HH:mm:ss`, **UTC**. |
| `updatedAt` | string | Yes | `yyyy-MM-dd HH:mm:ss`, **UTC**. |

### `MediaItem`

Source: `ServiceDTOs.MediaItem`, persisted as the `ServiceMedia` embeddable in
`service_gallery_media`. Array order is display order and is preserved exactly as the CMS stored it
(`@OrderColumn(name = "display_order")`).

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `type` | string | Yes in theory | `IMAGE` or `VIDEO`. Always populated on responses — the write path normalises or auto-detects it. |
| `url` | string | No | S3 URL of the image or video file. |
| `posterUrl` | string | Yes | Poster frame. Present on `VIDEO` slots when the admin supplied one. |
| `alt` | string | Yes | Accessibility text, max 500 chars. |

### `ServiceContentResponse`

Source: `ServiceDTOs.ServiceContentResponse`, persisted as the `ServiceContent` entity.
One row per language, `UNIQUE(service_id, language_code)`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | long | No | `service_contents.id`. |
| `languageCode` | string | No | `CKB` or `KMR`. |
| `title` | string | No | Localised title, max 300 chars. |
| `description` | string | Yes | Tiptap rich-text **HTML**. All media for this service is embedded here as `<img>`, `<video>`, `<audio>` and `<a href>` pointing at S3. Render as HTML, sanitising on the client. |
| `featureDescription` | string | Yes | Short **plain-text** line for the highlight rail / carousel, max 1000 chars. Any markup was stripped on save. |

---

## Enums used by this API

None of these are Java enums — they are validated string sets in `ServiceService`. Only the values
listed below can ever be returned.

### `layoutType`

| Value | Meaning |
|-------|---------|
| `MEDIA_HERO` | Section leads with a full-bleed hero video or image |
| `FEATURE_GRID` | Section renders its gallery as a grid of feature cards |
| `DEFAULT` | Plain text-and-gallery section |

Absent (`null`) is also valid and means "no hint — use the site default".

### `galleryMedia[].type`

| Value | Meaning |
|-------|---------|
| `IMAGE` | Slot is a still picture; render `url` in an `<img>` |
| `VIDEO` | Slot is a video file; render `url` in a `<video>` with `posterUrl` as the poster |

### `contents[].languageCode`

| Value | Meaning |
|-------|---------|
| `CKB` | Sorani (Central Kurdish) |
| `KMR` | Kurmanji (Northern Kurdish) |

`ServiceContent` stores the language as a plain `VARCHAR(10)` validated against
`^[A-Z]{2,5}$` at the entity level, and against the set `{CKB, KMR}` in `ServiceService`. It is not
the shared `Language` enum, so extra languages can be added later without a schema change — but today
only `CKB` and `KMR` are accepted on write, and therefore only those two are ever returned.

---

## Notes & gotchas

### Service-type taxonomy

`serviceType` is free text (`VARCHAR(100)`), not an enum — admins can introduce a new type without a
code change or a migration. The values in use today (`Training`, `Event`, `Program`, `Workshop`,
`Studio`) are conventions, not constraints, and `GET /api/v1/services/types` is the only authoritative
list. Two consequences for the public site:

- **Casing is not normalised on write.** `"training"` and `"Training"` appear as two separate entries
  in `/types`, even though `?type=` matches both — the filter compares `lower(...) = lower(...)`.
  De-duplicate case-insensitively before rendering filter chips.
- **`/types` is not filtered by `active`.** A type that survives only on inactive services still shows
  up, and filtering by it returns an empty page.

### Ordering

The list endpoints run a two-phase query: phase 1 selects only ids (paginated and ordered), phase 2
hydrates those ids in a batch and the service re-applies the phase-1 order in memory. The order you
receive is always the phase-1 order:

| Endpoint | `ORDER BY` |
|----------|-----------|
| `GET /api/v1/services`, `GET /api/v1/services/all` | `COALESCE(sortOrder, 2147483647) ASC, publishedAt DESC, createdAt DESC` |
| `GET /api/v1/services?type=…` | same as above |
| `GET /api/v1/services/search` | `publishedAt DESC, createdAt DESC` (no `sortOrder`) |
| `GET /api/v1/services/featured` | `COALESCE(featuredOrder, 2147483647) ASC, id DESC` |

PostgreSQL sorts `NULL` **first** on a `DESC` column, so inside one `sortOrder` bucket a service with
`publishedAt = null` comes before services that have a publish timestamp. If you want unpublished
drafts at the bottom of the page, set an explicit `sortOrder`.

### Timestamps are UTC, not Asia/Baghdad

`publishedAt`, `createdAt` and `updatedAt` are formatted to `yyyy-MM-dd HH:mm:ss` **inside
`ServiceService`**, not by Jackson. Hibernate stores these `LocalDateTime` columns with
`hibernate.jdbc.time_zone=UTC`, so the strings you receive are UTC wall-clock times. The global
`spring.jackson.time-zone: Asia/Baghdad` setting does not touch them because they are already strings
by the time Jackson sees them. Add the +03:00 offset yourself when displaying local time.

### Media lives inside the HTML

A service has no standalone media collection. Everything an editor uploads is embedded in the Tiptap
`contents[].description` HTML with an S3 `src`/`href`, on top of the structured `galleryMedia`,
`heroVideoUrl`/`heroPosterUrl` and `featureImageUrl` fields. Expect `<img>`, `<video>`, `<audio>` and
`<a>` tags in `description`, all pointing at
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/…`. Sanitise before injecting into
the DOM.

### Gallery: new model vs legacy fallback

`galleryMedia` is the current model — ordered slots, each independently `IMAGE` or `VIDEO`.
`featureImageUrls` and `thumbnailUrls` are plain string lists kept only for records created before
`galleryMedia` existed. Render `galleryMedia` when it is non-empty and fall back to the legacy lists
otherwise.

### Caching

Read paths are cached in Redis (key prefix `khi:`, TTL 10 minutes, JDK serialization):

| Method | Cache key |
|--------|-----------|
| `getAllActive(page, size)` | `khi:services::active:p{page}:s{size}` |
| `getAll(page, size)` (admin) | `khi:services::all:p{page}:s{size}` |
| `getAllActiveByType(type, page, size)` | `khi:services::type:{type lowercased}:p{page}:s{size}` |
| `globalSearch(q, page, size)` | `khi:services::search:{q lowercased}:p{page}:s{size}` |
| `adminSearch(q, page, size)` (admin) | `khi:services::adminSearch:{q lowercased}:p{page}:s{size}` |
| `getServiceTypes()` | `khi:services::types` |

`GET /api/v1/services/{id}` and `GET /api/v1/services/featured` are **not** cached. Every write in the
[internal API](../internal/SERVICE_API.md) — including the featured toggle, which is written from
`SiteContentService` — evicts the whole `services` cache (`allEntries = true`), so a public list is at
most one write behind, never more than 10 minutes stale otherwise.

### Non-numeric path and query values return 500

`GlobalExceptionHandler` has no handler for `MethodArgumentTypeMismatchException`, so a value Spring
cannot coerce falls through to the catch-all `@ExceptionHandler(Exception.class)` and comes back as
`500 INTERNAL_ERROR` instead of `400 BAD_REQUEST`. This affects `/api/v1/services/{id}` with a
non-numeric id and `?page=`/`?size=` with a non-integer value. Validate on the client.

Note that `/api/v1/services/all`, `/api/v1/services/types`, `/api/v1/services/featured` and
`/api/v1/services/search` are literal mappings and are matched before `/{id}`, so they never hit this.

### Error messages are generic

`ServiceService` throws with i18n message keys such as `service.not_found`,
`service.search.required` and `service.content.title.required`, but **none of those keys exist in
`messages_ckb.properties` / `messages_kmr.properties`**. Every service error therefore falls back to
the generic per-code text in `GlobalExceptionHandler.fallbackByCode` — `"Resource not found"` /
`"سەرچاوە نەدۆزرایەوە"` for `404`, `"Bad request"` / `"داواکاری هەڵەیە"` for `400`. The machine-readable
signal is the `code` field plus `details`; do not show `message` to end users. (`messageEn` is affected
by the same gap for an unrelated reason: the English bundle file is literally named
`" messages_en.properties"`, with a leading space, so it never loads.)

### Localisation of error responses

`Accept-Language` (values `en`, `ckb`, `kmr`) selects which language lands in `message`; `messageEn`
and `messageKu` are always present. A `?lang=ckb` query parameter does the same thing via
`LocaleChangeInterceptor`. Content localisation is unrelated — pick the `contents[]` row whose
`languageCode` matches the locale you are rendering.

### Trace ids

Every response carries an `X-Trace-Id` header. Send your own `X-Trace-Id` on the request to have it
echoed back and used as the `traceId` inside any error body — useful when reporting an issue.

---

## Related documentation

- Counterpart (writes and admin reads): [`../internal/SERVICE_API.md`](../internal/SERVICE_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs` (groups `public`,
  `internal`, `all` — `/api/v1/services/**` sits in the `public` group, including the two admin-only
  routes)
- Servers: `http://localhost:8080` locally; production is deployed on Railway
