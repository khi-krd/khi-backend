# About API — External (Public)

The About domain serves the institutional "About" pages of the KHI public website: the bilingual
narrative body, the founder block, the hero video, and the structured statistics strip. Every
endpoint on this page is reachable by an anonymous visitor — no token, no cookie, no role. The
admin dashboard writes these records through the separate
[internal About API](../internal/ABOUT_API.md); this document covers reads only.

| | |
|---|---|
| **Base path** | `/api/v1/about` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/about/AboutController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/about/AboutService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/about/AboutDTOs.java` |
| **Repository** | `src/main/java/ak/dev/khi_backend/khi_app/repository/about/AboutRepository.java` |
| **Entities** | `About`, `AboutContent` (embeddable), `StatItem` (JSONB element) |
| **Table** | `about_pages` |
| **Response envelope** | `ApiResponse<T>` — `{ "success", "message", "data" }` |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/about` | None | — | Paginated list of **active** About pages, ordered by `displayOrder` ascending |
| 2 | `GET` | `/api/v1/about/{identifier}` | None | — | Fetch one page by numeric id **or** by either localized slug |
| 3 | `GET` | `/api/v1/about/slug/{slug}` | None | — | Fetch one page by CKB slug **or** KMR slug |

All three are covered by `SecurityConfig` rule
`.requestMatchers(HttpMethod.GET, "/api/v1/about/**").permitAll()`. None of the three handler
methods carries a `@PreAuthorize` annotation, so nothing narrows that rule.

---

## The data model in one pass

Before the endpoint reference, here is what an About record actually is. Everything below is a
field of `AboutResponse`.

### Bilingual content — `ckbContent` / `kmrContent`

An About page carries two independent language versions. They are **not** keyed by a `language`
field in the JSON; they are two named objects:

| Object | Language | `Language` enum value | Script / direction |
|--------|----------|----------------------|--------------------|
| `ckbContent` | Central Kurdish (Sorani) | `CKB` | Arabic script, right-to-left |
| `kmrContent` | Northern Kurdish (Kurmanji) | `KMR` | Latin script, left-to-right |

Each object has the same four fields:

| Field | Type | DB column (CKB / KMR) | Max length | Description |
|-------|------|----------------------|------------|-------------|
| `title` | string | `title_ckb` / `title_kmr` | 300 | Page heading |
| `subtitle` | string | `subtitle_ckb` / `subtitle_kmr` | 500 | Sub-heading under the title |
| `metaDescription` | string | `meta_description_ckb` / `meta_description_kmr` | 2500 | SEO `<meta name="description">` text |
| `body` | string (HTML) | `body_ckb` / `body_kmr` | unlimited (`TEXT`) | Tiptap editor output — see below |

A record only needs **one** of the two titles to exist (the service enforces "at least one
localized About title is required"). So a CKB-only page is legal and its `kmrContent` will come
back empty or absent. Your renderer must fall back to the other language when the requested one
is missing.

> **Note:** a language version that was never filled in is returned inconsistently. The
> immediate `POST` / `PUT` response echoes an in-memory empty object (`"kmrContent": {}`),
> whereas a later `GET` reads the row back from Postgres, where Hibernate materialises an
> all-null embeddable as `null` — and because global Jackson config omits nulls, the
> `kmrContent` key is then absent entirely. Treat `{}`, `null` and "key missing" as the same
> thing: no content in that language.

### The Tiptap HTML body

`body` is a raw HTML string produced by the Tiptap rich-text editor in the admin dashboard.
About has **no** standalone hero-image, gallery, thumbnail or media-type field — every picture,
video, voice clip and downloadable document lives *inside* this HTML as an inline `<img>`,
`<video>`, `<audio>` or `<a href>` whose URL already points at S3.

On the write path, `TiptapHtmlProcessor` scans the incoming HTML for `src="data:...;base64,..."`
and `href="data:...;base64,..."`, uploads each payload to S3, and rewrites the attribute to the
resulting public URL. The database never stores base64. Consequently, on the read path:

- Every media URL in `body` is an absolute `https://s3-khiwebsite.s3.us-east-1.amazonaws.com/...`
  URL that a browser can load directly.
- The HTML is stored and returned **verbatim** — it is not sanitised server-side. Render it
  through your framework's HTML sanitiser before injecting it into the DOM.

### Statistics — `stats`

A JSONB array on the `about_pages.stats` column, order preserved exactly as the admin entered it.
Each element:

| Field | Type | Description |
|-------|------|-------------|
| `labelCkb` | string | Sorani label, e.g. `"کتێب"` |
| `labelKmr` | string | Kurmanji label, e.g. `"Pirtûk"` |
| `value` | string | Display value, kept as a string so `"12,400+"` and `"١٨"` are both valid |

`value` is **never** a number — do not `parseInt` it blindly, it is formatted for display.
An entry survives the write path if at least one of its three fields is non-blank, so a stat can
legitimately arrive with `labelKmr` missing. When a page has no stats the field is `[]`.

### Founder and hero fields

| Field | Type | DB column | Description |
|-------|------|-----------|-------------|
| `founderNameCkb` | string \| null | `founder_name_ckb` (300) | Founder name in Sorani |
| `founderNameKmr` | string \| null | `founder_name_kmr` (300) | Founder name in Kurmanji |
| `founderBioCkb` | string \| null | `founder_bio_ckb` (`TEXT`) | Founder biography in Sorani (plain text, not Tiptap) |
| `founderBioKmr` | string \| null | `founder_bio_kmr` (`TEXT`) | Founder biography in Kurmanji |
| `founderImageUrl` | string \| null | `founder_image_url` (`TEXT`) | S3 URL of the founder portrait |
| `heroVideoUrl` | string \| null | `hero_video_url` (`TEXT`) | S3 URL of the page-top video |
| `heroPosterUrl` | string \| null | `hero_poster_url` (`TEXT`) | S3 URL of the poster frame for `heroVideoUrl` |

All seven are stored trimmed, and a blank string is normalised to `null` on write. Because the
API omits nulls, expect these keys to be **absent** rather than `null` on a page that does not
use them.

### Featured, ordering and flags

| Field | Type | Description |
|-------|------|-------------|
| `id` | number (int64) | Primary key |
| `slugCkb` | string | Sorani route slug. Required, unique, max 200 chars |
| `slugKmr` | string \| null | Kurmanji route slug. Optional, unique when present, max 200 chars |
| `active` | boolean | Whether the page appears in the list endpoint |
| `displayOrder` | number (int32) | Ascending sort key for the list endpoint. Defaults to `0` |
| `featured` | boolean | `true` when this record **leads** the public About page |
| `featuredOrder` | number \| null | Lower value shows first among featured records; `null` when not featured |
| `featureImageUrl` | string \| null | S3 URL of the wide hero image used while `featured` is true |
| `createdAt` | string | `yyyy-MM-dd HH:mm:ss`, UTC |
| `updatedAt` | string | `yyyy-MM-dd HH:mm:ss`, UTC |

---

## What `featured` means here — and what it does **not**

This is the single most misread field in the domain, so it is worth stating plainly.

`featured` on an About record is a **page-level highlight**, not a homepage carousel slide.
`SiteContentService.getFeatured()` — the method behind `GET /api/v1/featured` and its legacy
alias `GET /featured` — pools flagged records from News, Projects, Writings, Videos, Sound
Tracks, Image Collections and the Donation settings row. **About is deliberately excluded from
that pool.** The source comment is explicit:

> Services and About pages are deliberately NOT collected here. Their `featured` flag highlights
> them on their own page (`/services` band, `/about` lead record).

Concrete consequences for a frontend developer:

1. A featured About page will **never** appear in the response of `GET /api/v1/featured`.
   Do not go looking for it there.
2. About takes **no share** of `SiteSettings.maxFeaturedSlides` (default 7). Any number of About
   records may be featured simultaneously — there is no cap check on this path.
3. To render `/about`, call `GET /api/v1/about`, then pick the record(s) with `featured: true`
   and sort them by `featuredOrder` ascending. The first one leads the page; any others render
   as highlighted sections below it.
4. `featureImageUrl` is **not** an override on About — About has no cover image to fall back on,
   so `featureImageUrl` is the only picture the highlight can use. The write path refuses to
   turn `featured` on while it is blank, which means a record you receive with
   `"featured": true` is guaranteed to also carry a non-empty `featureImageUrl`.

> **Note:** `AboutRepository` declares `findByFeaturedTrueOrderByFeaturedOrderAscIdDesc()`, and
> its own Javadoc says it "is the query a dedicated `GET /api/v1/about/featured` would use".
> That endpoint does not exist in this build — the method is currently unused. Filter the
> paginated list client-side instead.

---

## 1. `GET /api/v1/about` — List active About pages

Returns a Spring Data page of About records where `active = true`, sorted by `displayOrder`
ascending. Inactive records are excluded. Read-only, no side effects, no cache.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | None |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | int | No | `0` | Zero-based page index. Must be `>= 0` |
| `size` | int | No | `20` | Page size. Must be `>= 1` |

Both are declared as primitive `int` with `@RequestParam(defaultValue = ...)`. They are passed
straight to `PageRequest.of(page, size)`, which throws for a negative `page` or a `size` below 1
— that surfaces as `400 BAD_REQUEST`. A non-numeric value (`?page=abc`) cannot be bound at all
and is not covered by a dedicated exception handler; it falls through to the catch-all and
returns `500 INTERNAL_ERROR`.

**Request body**

None.

**Response `200 OK`**

The `data` field is a directly-serialised Spring Data `PageImpl`
(`spring.data.web.pageable.serialization-mode` is left at its `direct` default), so it carries
the full page metadata block:

```json
{
  "success": true,
  "message": "About pages fetched",
  "data": {
    "content": [
      {
        "id": 1,
        "slugCkb": "dezgay-kelepuri-kurdi",
        "slugKmr": "saziya-mirateya-kurdi",
        "ckbContent": {
          "title": "دەزگای کەلەپووری کوردی",
          "subtitle": "پاراستن و لێکۆڵینەوە لە کەلەپووری کوردی لە سلێمانی",
          "metaDescription": "دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزراوە بۆ کۆکردنەوە و پاراستنی کەلەپووری کوردی.",
          "body": "<h2>مێژووی دەزگا</h2><p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە شاری سلێمانی دامەزرا.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f3c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-archive-hall.jpg\" alt=\"هۆڵی ئەرشیف\"><p>ئێستا زیاتر لە ١٢ هەزار کتێب و دەستنووس دەپارێزێت.</p>"
        },
        "kmrContent": {
          "title": "Saziya Mîrateya Kurdî",
          "subtitle": "Parastin û lêkolîn li ser mîrateya kurdî li Silêmaniyê",
          "metaDescription": "Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin ji bo berhevkirin û parastina mîrateya kurdî.",
          "body": "<h2>Dîroka saziyê</h2><p>Saziya Mîrateya Kurdî di sala 2007an de li bajarê Silêmaniyê hat damezrandin.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9f3c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-archive-hall.jpg\" alt=\"Salona arşîvê\"><p>Îro zêdetir ji 12 hezar pirtûk û destnivîs diparêze.</p>"
        },
        "active": true,
        "stats": [
          { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,400+" },
          { "labelCkb": "دەستنووس", "labelKmr": "Destnivîs", "value": "3,150" },
          { "labelCkb": "ساڵ خزمەت", "labelKmr": "Sal xizmet", "value": "18" }
        ],
        "founderNameCkb": "د. ئاراس عەبدولڕەحمان",
        "founderNameKmr": "Dr. Aras Evdilrehman",
        "founderBioCkb": "توێژەری کەلەپووری کوردی و دامەزرێنەری دەزگا.",
        "founderBioKmr": "Lêkolînerê mîrateya kurdî û damezrînerê saziyê.",
        "founderImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/4a1d0c62-7b13-4e88-9a5f-2c6d8e0f1a37-founder.jpg",
        "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero.mp4",
        "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero-poster.jpg",
        "displayOrder": 0,
        "featured": true,
        "featuredOrder": 1,
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/b7e3f108-9c4a-42d6-8e15-3f7a6b2c9d40-about-feature.jpg",
        "createdAt": "2026-03-11 08:42:17",
        "updatedAt": "2026-08-20 14:05:03"
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
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "size": 20,
    "number": 0,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "numberOfElements": 2,
    "first": true,
    "empty": false
  }
}
```

> **Note:** `sort` reports `unsorted`. That is accurate about the `Pageable` that was built —
> `PageRequest.of(page, size)` carries no `Sort` — but it is **not** accurate about the rows.
> The ordering lives in the repository method name
> (`findAllByActiveTrueOrderByDisplayOrderAsc`), so results really are ordered by
> `displayOrder` ascending. Ignore the `sort` block; trust `displayOrder`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page` is negative, or `size` is `0` or negative |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer (`?page=abc`); unexpected server fault |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/about?page=0&size=20"
```

---

## 2. `GET /api/v1/about/{identifier}` — Fetch one page by id or slug

A backward-compatible detail lookup that accepts either form in a single route. The service does
exactly this:

1. Try `Long.valueOf(identifier)`. If it parses, look the record up **by primary key**.
2. If parsing throws `NumberFormatException`, fall back to a slug lookup that matches
   `slug_ckb = identifier OR slug_kmr = identifier`.

Read-only, no side effects, no cache.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `identifier` | string | Yes | Either a numeric primary key (`1`, `42`) or a slug in either language (`dezgay-kelepuri-kurdi`, `saziya-mirateya-kurdi`) |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "About page fetched",
  "data": {
    "id": 1,
    "slugCkb": "dezgay-kelepuri-kurdi",
    "slugKmr": "saziya-mirateya-kurdi",
    "ckbContent": {
      "title": "دەزگای کەلەپووری کوردی",
      "subtitle": "پاراستن و لێکۆڵینەوە لە کەلەپووری کوردی لە سلێمانی",
      "metaDescription": "دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە سلێمانی دامەزراوە.",
      "body": "<h2>مێژووی دەزگا</h2><p>دەزگای کەلەپووری کوردی لە ساڵی ٢٠٠٧ لە شاری سلێمانی دامەزرا.</p>"
    },
    "kmrContent": {
      "title": "Saziya Mîrateya Kurdî",
      "subtitle": "Parastin û lêkolîn li ser mîrateya kurdî li Silêmaniyê",
      "metaDescription": "Saziya Mîrateya Kurdî di sala 2007an de li Silêmaniyê hat damezrandin.",
      "body": "<h2>Dîroka saziyê</h2><p>Saziya Mîrateya Kurdî di sala 2007an de li bajarê Silêmaniyê hat damezrandin.</p>"
    },
    "active": true,
    "stats": [
      { "labelCkb": "کتێب", "labelKmr": "Pirtûk", "value": "12,400+" },
      { "labelCkb": "دەستنووس", "labelKmr": "Destnivîs", "value": "3,150" }
    ],
    "founderNameCkb": "د. ئاراس عەبدولڕەحمان",
    "founderNameKmr": "Dr. Aras Evdilrehman",
    "founderBioCkb": "توێژەری کەلەپووری کوردی و دامەزرێنەری دەزگا.",
    "founderBioKmr": "Lêkolînerê mîrateya kurdî û damezrînerê saziyê.",
    "founderImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/4a1d0c62-7b13-4e88-9a5f-2c6d8e0f1a37-founder.jpg",
    "heroVideoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero.mp4",
    "heroPosterUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2f9b41-3d57-4a0e-8f21-b9d4e7c05a68-about-hero-poster.jpg",
    "displayOrder": 0,
    "featured": true,
    "featuredOrder": 1,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/b7e3f108-9c4a-42d6-8e15-3f7a6b2c9d40-about-feature.jpg",
    "createdAt": "2026-03-11 08:42:17",
    "updatedAt": "2026-08-20 14:05:03"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No row with that id, and — for a non-numeric identifier — no row with that CKB or KMR slug. `details.resource` echoes `"About page not found: <identifier>"` |
| `500` | `INTERNAL_ERROR` | Two different rows collide on the slug (see the note below); unexpected server fault |

A `404` looks like this:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/about/mission",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NOT_FOUND",
  "message": "About page not found: mission",
  "messageEn": "About page not found: mission",
  "messageKu": "About page not found: mission",
  "details": { "resource": "About page not found: mission" }
}
```

> **Note:** `{identifier}` is greedy about numbers. If an About page is ever given a purely
> numeric slug — say `slugCkb: "2007"` — then `GET /api/v1/about/2007` resolves as
> *primary key 2007* and almost certainly answers `404`; it never reaches the slug branch,
> because `Long.valueOf("2007")` succeeds. Use endpoint 3 (`/api/v1/about/slug/{slug}`) for any
> slug that could be parsed as a number.

> **Note:** `team` and `partners` are reserved segments under this base path.
> `GET /api/v1/about/team` and `GET /api/v1/about/partners` are mapped by
> `PublicSiteController` and return team members / partner organisations, **not** an About page.
> Spring matches those literal paths ahead of `/{identifier}`, so an About record whose slug is
> `team` or `partners` is unreachable through this endpoint. Use endpoint 3 for it.

**Example**

```bash
# by primary key
curl -s http://localhost:8080/api/v1/about/1

# by Sorani slug
curl -s http://localhost:8080/api/v1/about/dezgay-kelepuri-kurdi

# by Kurmanji slug
curl -s http://localhost:8080/api/v1/about/saziya-mirateya-kurdi
```

---

## 3. `GET /api/v1/about/slug/{slug}` — Fetch one page by slug only

The unambiguous slug route. It never tries to interpret the value as an id, so it is the correct
endpoint for numeric-looking slugs and for the reserved words `team` / `partners`. The lookup
matches `slug_ckb = :slug OR slug_kmr = :slug`, so a caller can pass whichever language slug it
happens to hold and always land on the same record.

Read-only, no side effects, no cache.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `slug` | string | Yes | A CKB slug or a KMR slug. Matched exactly — case-sensitive, no trimming, no normalisation |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | None |

**Request body**

None.

**Response `200 OK`**

Identical body shape to endpoint 2 — a single `AboutResponse` wrapped in `ApiResponse`, with
`"message": "About page fetched"`.

```json
{
  "success": true,
  "message": "About page fetched",
  "data": {
    "id": 2,
    "slugCkb": "amancekanman",
    "slugKmr": "armancen-me",
    "ckbContent": {
      "title": "ئامانجەکانمان",
      "subtitle": "چ دەکەین و بۆچی",
      "body": "<p>پاراستنی دەستنووسە کوردییەکان و بڵاوکردنەوەی توێژینەوەی زمانەوانی.</p>"
    },
    "kmrContent": {
      "title": "Armancên me",
      "subtitle": "Em çi dikin û çima",
      "body": "<p>Parastina destnivîsên kurdî û weşandina lêkolînên zimannasî.</p>"
    },
    "active": true,
    "stats": [],
    "displayOrder": 1,
    "featured": false,
    "createdAt": "2026-04-02 11:20:44",
    "updatedAt": "2026-04-02 11:20:44"
  }
}
```

Note what is missing from that example: `slugKmr` is present but `founderNameCkb`,
`founderBioCkb`, `founderImageUrl`, `heroVideoUrl`, `heroPosterUrl`, `featuredOrder`,
`featureImageUrl` and `metaDescription` are all `null` in the database and therefore omitted from
the JSON entirely. This is the normal shape for a simple secondary page.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No row matches the slug in either language. `details.resource` echoes `"About page not found: <slug>"` |
| `500` | `INTERNAL_ERROR` | Two different rows match the slug (see the note below) |

> **Note:** the uniqueness rules on the write path are per-column — `slugCkb` is checked only
> against `slug_ckb`, `slugKmr` only against `slug_kmr`. Nothing prevents record A from having
> `slugCkb = "armancen-me"` while record B has `slugKmr = "armancen-me"`. When that happens,
> `findBySlugCkbOrSlugKmr` matches two rows, Spring Data cannot fit them into an `Optional`, and
> the request fails with `500 INTERNAL_ERROR` instead of a clean `409`. Both this endpoint and
> the slug branch of endpoint 2 are affected. This is a latent data-integrity bug, not a
> documented behaviour — treat cross-language slug collisions as forbidden in your admin tooling.

**Example**

```bash
curl -s http://localhost:8080/api/v1/about/slug/saziya-mirateya-kurdi
```

---

## Enums used by this API

The About request and response payloads contain **no enum-typed fields**. The `Language` enum
(`ak.dev.khi_backend.khi_app.enums.Language`) governs the domain conceptually, but it is never
serialised here — the language is encoded in the field name instead.

| `Language` value | Name | Where it shows up in About JSON |
|------------------|------|---------------------------------|
| `CKB` | Central Kurdish (Sorani) | `slugCkb`, `ckbContent`, `stats[].labelCkb`, `founderNameCkb`, `founderBioCkb` |
| `KMR` | Northern Kurdish (Kurmanji) | `slugKmr`, `kmrContent`, `stats[].labelKmr`, `founderNameKmr`, `founderBioKmr` |

Other content domains (News, Videos, Writings, …) *do* return a `contentLanguages: ["CKB","KMR"]`
array. About does not — derive availability by checking whether `ckbContent.title` /
`kmrContent.title` is present.

---

## Notes & gotchas

**Inactive pages are still publicly readable by id or slug.** `GET /api/v1/about` filters on
`active = true`, but `GET /api/v1/about/{identifier}` and `GET /api/v1/about/slug/{slug}` call
`findById` / `findBySlugCkbOrSlugKmr` with **no** active filter. An admin who unpublishes a page
removes it from the list but does not hide it — anyone holding the id or the slug can still fetch
it anonymously, and the response will carry `"active": false`. Check `data.active` before
rendering a deep-linked About page.

**Ties in `displayOrder` are non-deterministic.** The list query sorts only on `display_order`,
with no secondary key. Two records both sitting at `displayOrder: 0` can swap positions between
requests. Ask the admin to assign distinct orders, or apply a stable client-side tiebreak on `id`.

**No caching anywhere in this domain.** `AboutService` carries no `@Cacheable` or `@CacheEvict`,
and `about` is not among the cache names listed in `CacheConfig` (`news`, `projects`,
`soundTracks`, `imageCollections`, `services`). Every read here hits Postgres directly. A change
made in the dashboard is visible on the very next request — there is no 10-minute Redis TTL to
wait out, unlike News or Services.

**Timestamps are UTC wall-clock strings, not ISO-8601.** `createdAt` and `updatedAt` are
pre-formatted in `AboutService` with `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")` applied
to a `LocalDateTime` read from Postgres, which stores UTC
(`hibernate.jdbc.time_zone=UTC`). They arrive as plain strings with **no timezone suffix** and
are **not** converted to `Asia/Baghdad` by Jackson — the `spring.jackson.time-zone` setting only
touches real date types, and these are already strings by then. Parse them as UTC and convert
client-side if you display local time. (The `timestamp` field on error responses is a genuine
`Instant` and does carry a `Z`.)

**Nulls are omitted, not sent as `null`.** `spring.jackson.default-property-inclusion` is
`non_null` and `ApiResponse` additionally carries `@JsonInclude(NON_NULL)`. Any optional field
that is null in the database is simply absent from the JSON. Write your TypeScript types with
optional properties (`slugKmr?: string`) rather than nullable ones, or handle both.

**`stats` is `[]`, never absent.** `toStatsResponse` returns `List.of()` for a null or empty
list, and an empty array is not null, so the key is always present. This is the one collection
field you can index into without a guard.

**Media in the body outlives the page.** Deleting an About record removes only the Postgres row.
The S3 objects referenced from `body_ckb` / `body_kmr`, plus `founderImageUrl`, `heroVideoUrl`,
`heroPosterUrl` and `featureImageUrl`, are left in the bucket. If you cache media URLs on the
client, expect them to keep resolving after the page itself has disappeared from the API.

**`X-Trace-Id` round-trips.** `TraceIdFilter` reads an incoming `X-Trace-Id` header (or generates
one), echoes it back on every response, and stamps it into `ApiErrorResponse.traceId`. Send your
own correlation id and quote it when reporting a bug.

**Error messages are English in practice.** Every error body carries `messageEn` and `messageKu`.
For `NOT_FOUND` on this domain both are set to the raw exception text
(`"About page not found: mission"`), and `messageKu` resolves against locale `ku` for which no
bundle exists — so it falls back to the same English string. Sending `Accept-Language: ckb` or
`Accept-Language: kmr` does swap the top-level `message` to a Sorani/Kurmanji phrase for the
generic codes (`error.bad_request`, `error.validation`, …). Do not build UI copy from these
fields; key off `code` instead.

**Live spec.** Swagger UI is at `/swagger-ui.html`, the raw document at `/v3/api-docs`. About is
part of the `public` group (`/v3/api-docs/public`) and the `all` group, tagged **About**. Note
that the springdoc `public` group matches `/api/v1/about/**` wholesale, so the four admin-only
write endpoints show up there too — group membership is not an authorization statement. The
authorization rules in this document come from `SecurityConfig`, which is the only authority.

**Servers.** Local development runs on `http://localhost:8080`; production is deployed on
Railway. The second server entry in `OpenApiConfig` (`https://api.khi.local`) is an unresolved
placeholder — do not point a client at it.

---

## Related documentation

- Counterpart (admin writes): [`../internal/ABOUT_API.md`](../internal/ABOUT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
