# Site Configuration API — External (Public)

This domain is everything the public KHI website needs to render its *chrome* and its homepage
hero: the featured rail, the team and partner lists on the About page, the branding row (logo and
donate-band picture), the social links in the footer, the hamburger navigation menu, and the
sitemap. Every endpoint here is reachable by an anonymous visitor — no token, no cookie, no role.
The admin dashboard creates and edits these records through the separate
[internal Site Configuration API](../internal/SITE_SETTINGS_API.md); this document covers reads
only.

| | |
|---|---|
| **Base paths** | `/api/v1/featured`, `/api/v1/about/team`, `/api/v1/about/partners`, `/api/v1/site-settings`, `/api/v1/settings/social`, `/api/v1/nav-menu`, `/api/v1/sitemap`, plus the legacy `/featured` |
| **Audience** | Public website (no auth) |
| **Controllers** | `src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/api/site/LegacyFeaturedController.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/api/site/NavMenuController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/service/site/NavMenuService.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/service/site/SitemapService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/dto/site/NavMenuDtos.java` |
| **Repositories** | `src/main/java/ak/dev/khi_backend/khi_app/repository/site/` |
| **Entities** | `SiteSettings`, `TeamMember`, `Partner`, `SocialLink`, `NavMenuItem`, `NavMenuLink`, `DonationSettings` (read-through for the rail), `FeaturedItem` (persisted but unused — see gotchas) |
| **Tables** | `site_settings`, `team_members`, `partners`, `social_links`, `nav_menu_items`, `nav_menu_links`, `featured_items` |
| **Response envelope** | `ApiResponse<T>` on every endpoint **except** `GET /api/v1/featured`, which returns a bare JSON array |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/featured` | None | — | Homepage hero rail, aggregated across seven sources. **Bare array, no envelope.** |
| 2 | `GET` | `/featured` | None | — | Deprecated compatibility alias for #1, wrapped in `ApiResponse` |
| 3 | `GET` | `/api/v1/about/team` | None | — | Active team members, ordered by `displayOrder` |
| 4 | `GET` | `/api/v1/about/partners` | None | — | Active partners, ordered by `displayOrder` |
| 5 | `GET` | `/api/v1/site-settings` | None | — | Logo, donate-band image, hero slide cap |
| 6 | `GET` | `/api/v1/settings/social` | None | — | Social links for the footer |
| 7 | `GET` | `/api/v1/nav-menu` | None | — | Hamburger menu: items with their secondary links |
| 8 | `GET` | `/api/v1/nav-menu/{id}` | None | — | One menu item, including its inactive links |
| 9 | `GET` | `/api/v1/sitemap` | None | — | Flat list of site paths for one locale (**JSON, not XML**) |

### Where the authorization comes from

Endpoint #2 has its own explicit rule in `SecurityConfig`:

```java
.requestMatchers(HttpMethod.GET, "/featured").permitAll()
```

Endpoints #1 and #3–#9 are all covered by the catch-all public read rule, which is the last
`/api/v1/**` matcher before the content-write rules:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

`GET /api/v1/about/team` and `GET /api/v1/about/partners` are additionally matched by the earlier,
more specific `.requestMatchers(HttpMethod.GET, "/api/v1/about/**").permitAll()` — same outcome.

None of the nine handler methods carries a `@PreAuthorize` annotation, so nothing narrows those
rules. (`PUT /api/v1/site-settings` does carry one, but that is a write and lives in the
[internal doc](../internal/SITE_SETTINGS_API.md).)

---

## How the featured rail works

This is the most involved part of the domain, so it gets a full walkthrough before the endpoint
reference.

### There is no "featured item" record you can create

The database has a `featured_items` table and a `FeaturedItem` entity with its own repository —
but nothing in the running application reads or writes them. No service injects
`FeaturedItemRepository`, and there is no `FeaturedController`. The rail is assembled entirely
from `featured` boolean columns on the *content* entities themselves.

> **Note:** `SecurityConfig` still reserves `POST`, `PUT` and `DELETE` on `/api/v1/featured/**`
> for `ADMIN`/`SUPER_ADMIN`. No handler is mapped to those verbs, so they answer
> `405 METHOD_NOT_ALLOWED` (after the role check) rather than doing anything. Treat
> `/api/v1/featured` as read-only.

### How a record becomes featured

Each content domain owns a toggle endpoint. They are admin endpoints and are documented in their
own files; they are listed here only so you can see where the rail's contents come from.

| Source | Toggle endpoint | Documented in |
|--------|-----------------|---------------|
| News | `PATCH /api/v1/news/{id}/featured` | [`../internal/NEWS_API.md`](../internal/NEWS_API.md) |
| Projects | `PATCH /api/v1/projects/{id}/featured` | [`../internal/PROJECT_API.md`](../internal/PROJECT_API.md) |
| Writings | `PATCH /api/v1/writings/{id}/featured` | [`../internal/WRITING_API.md`](../internal/WRITING_API.md) |
| Videos | `PATCH /api/v1/videos/{id}/featured` | [`../internal/VIDEO_API.md`](../internal/VIDEO_API.md) |
| Sound tracks | `PATCH /api/v1/sound-tracks/{id}/featured` | [`../internal/SOUNDTRACK_API.md`](../internal/SOUNDTRACK_API.md) |
| Image collections | `PATCH /api/v1/image-collections/{id}/featured` | [`../internal/IMAGE_COLLECTION_API.md`](../internal/IMAGE_COLLECTION_API.md) |
| Donation page | `PATCH /api/v1/donations/settings/featured` (singleton — no id) | [`../internal/DONATION_API.md`](../internal/DONATION_API.md) |

Every toggle writes three columns on the target row: `featured` (boolean), `featured_order`
(nullable integer) and `feature_image_url` (nullable text).

Two other entities also have a `featured` flag but **never enter this rail**:

| Source | Toggle endpoint | What its flag does instead |
|--------|-----------------|----------------------------|
| About pages | `PATCH /api/v1/about/{id}/featured` | Highlights the record on the `/about` page itself |
| Services | `PATCH /api/v1/services/{id}/featured` | Highlights the service in the rail inside the `/services` hero |

`SiteContentService.getFeatured()` deliberately does not collect About or Service records, and
`countAllFeatured()` deliberately does not count them, so featuring nine services can never block
featuring an article. Read them through
[`GET /api/v1/services/featured`](../external/SERVICE_API.md) and the
[About list](../external/ABOUT_API.md) instead.

### How the rail is assembled

`GET /api/v1/featured` runs this pipeline on every call (there is no cache):

1. **Collect.** Seven repository queries, one per source:
   - `newsRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc()`
   - `projectRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc()`
   - `writingRepository.findFeaturedWithTopic()` — `… where w.featured = true order by w.featuredOrder asc, w.id desc`
   - `videoRepository.findFeaturedWithTopic()` — same shape, with a `left join fetch` on the topic
   - `soundTrackRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc()`
   - `imageCollectionRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc()`
   - the single `DonationSettings` row, if and only if its `featured` flag is on
2. **Map to a slide.** Title and description come from the record's bilingual content, picked by
   the requested locale with a fallback to the other language. The image is
   `featureImageUrl` first, then the record's cover.
3. **Drop unrenderable slides.** A candidate whose resolved image is null or blank is thrown away
   silently. See the gotcha on this below — it is the most common cause of "I featured it and it
   did not appear".
4. **Sort globally.** By `featuredOrder` ascending, with `null` treated as `Integer.MAX_VALUE`
   (so unnumbered slides sort last); ties broken by **id descending** (newest first).
5. **Truncate.** To `SiteSettings.maxFeaturedSlides` (default `7`).
6. **Renumber.** `displayOrder` is overwritten with `1, 2, 3, …` over the surviving slides. The
   stored `displayOrder` of the source record is never used here.

### The slide object — `FeaturedResponse`

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | string | no | Synthetic composite key, `"{source}-{entityId}"` — e.g. `"news-42"`. Stable across calls. Use it as the React `key`. |
| `source` | string | no | Which table the record came from. See the vocabulary table below. |
| `entityId` | number | no | Raw primary key of the record in its own table. |
| `type` | string | no | Presentation type the website switches on. **Not the same as `source`.** |
| `slug` | string | no | Route segment for the detail link. For every source except image collections and donations this is the numeric id rendered as a string. |
| `title` | string | no | Localized title. Falls back to `"{type} {entityId}"` (e.g. `"article 42"`) when both language titles are blank. |
| `description` | string | yes | Localized description. Omitted from the JSON when null. |
| `image` | object | no | `{ "url": string, "alt": string }`. `alt` is always a copy of the resolved `title`. |
| `locale` | string | no | The locale the slide was rendered in — `"ckb"` or `"kmr"`, echoing the normalized query parameter. |
| `featured` | boolean | no | Always `true` for a slide in this response. |
| `featuredOrder` | number | yes | The admin-set sort key. Omitted when null. |
| `displayOrder` | number | no | 1-based position in this response, assigned by the server. |
| `active` | boolean | no | Hard-coded `true` in `SiteContentService.featuredSlide()`. Carries no information. |

### `source` → `type` → `slug` → image

| `source` | `type` | `slug` is | Image resolution order |
|----------|--------|-----------|------------------------|
| `news` | `article` | `String.valueOf(id)` | `featureImageUrl` → (`coverUrl` → `coverThumbnailUrl`) when the cover is an image or its kind is unset, otherwise `coverThumbnailUrl` |
| `project` | `archive` | `String.valueOf(id)` | `featureImageUrl` → `coverUrl` when the cover is an image or unset, otherwise (`coverThumbnailUrl` → `coverUrl`) |
| `writing` | `book` | `String.valueOf(id)` | `featureImageUrl` → localized cover (`ckbCoverUrl` / `kmrCoverUrl`) → `hoverCoverUrl` |
| `video` | `video` | `String.valueOf(id)` | `featureImageUrl` → localized cover → `hoverCoverUrl` |
| `sound-track` | `audio` | `String.valueOf(id)` | `featureImageUrl` → localized cover → `hoverCoverUrl` |
| `image-collection` | `gallery` | localized slug (`slugCkb` / `slugKmr`), falling back to the id | `featureImageUrl` → localized cover → `hoverCoverUrl` |
| `donation` | `donation` | the literal string `"donation"` | `featureImageUrl` → `heroImageUrl` |

"Localized cover" means: with `locale=kmr`, the KMR URL if non-blank, else the CKB URL; with
`locale=ckb`, the reverse.

---

## 1. `GET /api/v1/featured` — Homepage hero rail

Returns the assembled rail described above. Read-only, no side effects, no cache.

**Auth:** None (public)
**Content-Type:** `application/json`
**Envelope:** none — this endpoint returns a **bare JSON array**, unlike every other endpoint in
this domain.

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | | | none |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `locale` | string | no | `ckb` | Which language to render titles and descriptions in. Normalized by `resolveFeaturedLocale()`: `"kmr"` and `"ku"` (any case, trimmed) both become `kmr`; **anything else, including `"en"` and a blank value, becomes `ckb`**. |

**Response `200 OK`**

```json
[
  {
    "id": "news-42",
    "source": "news",
    "entityId": 42,
    "type": "article",
    "slug": "42",
    "title": "کۆنگرەی نێودەوڵەتیی مێژووی کوردستان لە هەولێر دەستی پێکرد",
    "description": "کۆنگرەکە بۆ ماوەی سێ ڕۆژ بەردەوام دەبێت و زیاتر لە چل توێژەر بەشداری تێدا دەکەن.",
    "image": {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/news/42/hero-congress-2026.jpg",
      "alt": "کۆنگرەی نێودەوڵەتیی مێژووی کوردستان لە هەولێر دەستی پێکرد"
    },
    "locale": "ckb",
    "featured": true,
    "featuredOrder": 1,
    "displayOrder": 1,
    "active": true
  },
  {
    "id": "image-collection-9",
    "source": "image-collection",
    "entityId": 9,
    "type": "gallery",
    "slug": "hewler-1958-archive",
    "title": "ئەرشیفی وێنەی هەولێر ١٩٥٨",
    "description": "کۆمەڵێک وێنەی مێژوویی لە ئەرشیفی خێزانی مەلا مستەفا.",
    "image": {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/image-collections/9/cover.jpg",
      "alt": "ئەرشیفی وێنەی هەولێر ١٩٥٨"
    },
    "locale": "ckb",
    "featured": true,
    "featuredOrder": 2,
    "displayOrder": 2,
    "active": true
  },
  {
    "id": "donation-1",
    "source": "donation",
    "entityId": 1,
    "type": "donation",
    "slug": "donation",
    "title": "پشتگیری لە ئەرشیفی کوردستان بکە",
    "description": "بەخشینەکانت یارمەتی پاراستنی بەڵگەنامە مێژووییەکان دەدەن.",
    "image": {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/donate-band-2026.jpg",
      "alt": "پشتگیری لە ئەرشیفی کوردستان بکە"
    },
    "locale": "ckb",
    "featured": true,
    "featuredOrder": 5,
    "displayOrder": 3,
    "active": true
  }
]
```

An empty rail is `[]` with status `200`, never `404`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `405` | `METHOD_NOT_ALLOWED` | Any verb other than `GET` — but note that `POST`/`PUT`/`DELETE` on `/api/v1/featured/**` hit the `ADMIN`/`SUPER_ADMIN` rule in `SecurityConfig` first and are refused with `403` for an anonymous caller. |
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

There is no `400` for a bad `locale` — unknown values silently fall back to `ckb`.

**Example**

```bash
# Sorani (default)
curl -s http://localhost:8080/api/v1/featured

# Kurmanji
curl -s "http://localhost:8080/api/v1/featured?locale=kmr"
```

---

## 2. `GET /featured` — Deprecated alias for the hero rail

> **Deprecated.** Use [`GET /api/v1/featured`](#1-get-apiv1featured--homepage-hero-rail) instead.
> This route exists only for the currently deployed public-site client, whose source comment in
> `LegacyFeaturedController` reads *"Compatibility route used by the current public-site client."*

Same data, same service call (`siteContentService.getFeatured(locale)`), **different envelope**:
this one wraps the array in `ApiResponse`.

**Auth:** None (public) — explicit rule
`.requestMatchers(HttpMethod.GET, "/featured").permitAll()`
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `locale` | string | no | `ckb` | Identical normalization to endpoint #1. |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Featured items fetched",
  "data": [
    {
      "id": "writing-17",
      "source": "writing",
      "entityId": 17,
      "type": "book",
      "slug": "17",
      "title": "Dîroka rojnamegeriya kurdî",
      "description": "Lêkolînek li ser sed salên rojnamegeriya kurdî.",
      "image": {
        "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/writings/17/cover-kmr.jpg",
        "alt": "Dîroka rojnamegeriya kurdî"
      },
      "locale": "kmr",
      "featured": true,
      "featuredOrder": 3,
      "displayOrder": 1,
      "active": true
    }
  ]
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `405` | `METHOD_NOT_ALLOWED` | Any verb other than `GET`. `/featured` is not `/api/v1/**`, so non-`GET` verbs fall through to `anyRequest().authenticated()` and an anonymous caller is refused before the 405 is produced. |
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

**Example**

```bash
curl -s "http://localhost:8080/featured?locale=ckb"
```

> **Note:** `springdoc.paths-to-match` is `/api/**`, so this endpoint does **not** appear in
> Swagger UI or in `/v3/api-docs`. It exists only in the source and in this document.

---

## 3. `GET /api/v1/about/team` — Team members

Returns every **active** team member ordered by `displayOrder` ascending
(`teamRepository.findAllByActiveTrueOrderByDisplayOrderAsc()`). Rows with `active = false` are
invisible here; there is no query parameter to include them.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters** — none.

**Response `200 OK`**

`ApiResponse<List<TeamMemberResponse>>`.

| Field | Type | Nullable | DB column | Max length | Description |
|-------|------|----------|-----------|------------|-------------|
| `id` | number | no | `id` | — | Primary key |
| `nameCkb` | string | no | `name_ckb` | 300 | Full name, Sorani |
| `nameKmr` | string | yes | `name_kmr` | 300 | Full name, Kurmanji |
| `roleCkb` | string | no | `role_ckb` | 300 | Job title, Sorani |
| `roleKmr` | string | yes | `role_kmr` | 300 | Job title, Kurmanji |
| `bioCkb` | string | yes | `bio_ckb` | `TEXT` | Short biography, Sorani |
| `bioKmr` | string | yes | `bio_kmr` | `TEXT` | Short biography, Kurmanji |
| `office` | string | yes | `office` | 200 | Free text, e.g. the city or department |
| `imageUrl` | string | yes | `image_url` | `TEXT` | Portrait, normally an S3 URL |
| `displayOrder` | number | no | `display_order` | — | Sort key, ascending. Defaults to `0`. |
| `active` | boolean | no | `active` | — | Always `true` in this response |

```json
{
  "success": true,
  "message": "Team members fetched",
  "data": [
    {
      "id": 1,
      "nameCkb": "د. ئاراس مەحموود",
      "nameKmr": "Dr. Aras Mehmûd",
      "roleCkb": "بەڕێوەبەری گشتی",
      "roleKmr": "Rêveberê giştî",
      "bioCkb": "توێژەری مێژووی کوردستانی هاوچەرخ، دامەزرێنەری ئەرشیفی دەنگی هەولێر.",
      "bioKmr": "Lêkolînerê dîroka Kurdistana hemdem, damezrînerê arşîva dengî ya Hewlêrê.",
      "office": "هەولێر",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/team/aras-mahmud.jpg",
      "displayOrder": 1,
      "active": true
    },
    {
      "id": 4,
      "nameCkb": "شنە عەبدوڵا",
      "roleCkb": "بەڕێوەبەری ئەرشیف",
      "office": "سلێمانی",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/team/shne-abdulla.jpg",
      "displayOrder": 2,
      "active": true
    }
  ]
}
```

Note the second entry: `nameKmr`, `roleKmr`, `bioCkb` and `bioKmr` are null in the database and are
therefore **absent from the JSON entirely** — `spring.jackson.default-property-inclusion` is
`non_null` for the whole application. Never assume a key is present; check for `undefined`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

**Example**

```bash
curl -s http://localhost:8080/api/v1/about/team
```

---

## 4. `GET /api/v1/about/partners` — Partner organizations

Returns every **active** partner ordered by `displayOrder` ascending
(`partnerRepository.findAllByActiveTrueOrderByDisplayOrderAsc()`). Same shape of rule as the team
list: inactive rows cannot be requested.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters** — none.

**Response `200 OK`**

`ApiResponse<List<PartnerResponse>>`.

| Field | Type | Nullable | DB column | Max length | Description |
|-------|------|----------|-----------|------------|-------------|
| `id` | number | no | `id` | — | Primary key |
| `nameCkb` | string | no | `name_ckb` | 300 | Organization name, Sorani |
| `nameKmr` | string | yes | `name_kmr` | 300 | Organization name, Kurmanji |
| `descriptionCkb` | string | yes | `description_ckb` | `TEXT` | One-line description, Sorani |
| `descriptionKmr` | string | yes | `description_kmr` | `TEXT` | One-line description, Kurmanji |
| `logoUrl` | string | yes | `logo_url` | `TEXT` | Partner logo |
| `websiteUrl` | string | yes | `website_url` | `TEXT` | External link |
| `displayOrder` | number | no | `display_order` | — | Sort key, ascending. Defaults to `0`. |
| `active` | boolean | no | `active` | — | Always `true` in this response |

```json
{
  "success": true,
  "message": "Partners fetched",
  "data": [
    {
      "id": 2,
      "nameCkb": "زانکۆی سەڵاحەدین — هەولێر",
      "nameKmr": "Zanîngeha Selahedîn — Hewlêr",
      "descriptionCkb": "هاوبەشی توێژینەوەی مێژوویی و پارێزگاری لە بەڵگەنامەکان.",
      "descriptionKmr": "Hevkarê lêkolîna dîrokî û parastina belgeyan.",
      "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/partners/salahaddin-university.png",
      "websiteUrl": "https://su.edu.krd",
      "displayOrder": 1,
      "active": true
    }
  ]
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

**Example**

```bash
curl -s http://localhost:8080/api/v1/about/partners
```

---

## 5. `GET /api/v1/site-settings` — Branding and global settings

Returns the single `site_settings` row. **This endpoint never returns `404`.** When no row has
been saved yet, the service synthesizes a response carrying only the default slide cap, so a fresh
database still serves something usable and the website can fall back to its bundled logo.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters** — none.

**Response `200 OK`**

`ApiResponse<SiteSettingsResponse>`.

| Field | Type | Nullable | DB column | Max length | Description |
|-------|------|----------|-----------|------------|-------------|
| `id` | number | yes | `id` | — | Primary key. Absent on a database with no saved row. |
| `logoUrl` | string | yes | `logo_url` | 1200 | Header and footer logo. Should be a transparent PNG — it renders on cream in the header and near-black in the footer. When null, the website uses its bundled logo. |
| `donateImageUrl` | string | yes | `donate_image_url` | 1200 | Photograph for the donate band above the footer. Shown sharp inside the slanted panel and again blurred behind it. When null the band renders on a plain dark ground. |
| `maxFeaturedSlides` | number | no | `max_featured_slides` | — | Hard cap on the number of hero slides. `NOT NULL` in the database; `SiteSettings.DEFAULT_MAX_FEATURED_SLIDES` is `7`. |
| `updatedAt` | string | yes | `updated_at` | — | Last save, `yyyy-MM-dd HH:mm:ss`. Absent on a database with no saved row. |

Saved row:

```json
{
  "success": true,
  "message": "Site settings fetched",
  "data": {
    "id": 1,
    "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/branding/khi-logo-transparent.png",
    "donateImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/branding/donate-band.jpg",
    "maxFeaturedSlides": 7,
    "updatedAt": "2026-08-20 14:05:03"
  }
}
```

Fresh database, nothing saved:

```json
{
  "success": true,
  "message": "Site settings fetched",
  "data": {
    "maxFeaturedSlides": 7
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

**Example**

```bash
curl -s http://localhost:8080/api/v1/site-settings
```

---

## 6. `GET /api/v1/settings/social` — Social links

Returns the footer social links ordered by `displayOrder` ascending.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `includeInactive` | boolean | no | `false` | `false` → `findAllByActiveTrueOrderByDisplayOrderAsc()`. `true` → `findAllByOrderByDisplayOrderAsc()`, which also returns rows an admin has switched off. |

> **Note:** the source comment on this parameter says *"dashboard only — the website never sends
> it"*, but nothing enforces that. `GET /api/v1/settings/social?includeInactive=true` is
> `permitAll` under the `GET /api/v1/**` rule, so an anonymous visitor can read the links an
> administrator has hidden. Treat `active = false` as "hidden from the site", not "private".

**Response `200 OK`**

`ApiResponse<List<SocialLinkResponse>>`.

| Field | Type | Nullable | DB column | Max length | Description |
|-------|------|----------|-----------|------------|-------------|
| `id` | number | no | `id` | — | Primary key |
| `platform` | string | no | `platform` | 60 | Uppercased on write (`platform.trim().toUpperCase(Locale.ROOT)`) and **unique across the table** (`uk_social_platform`). Free text — the API defines no fixed list. |
| `url` | string | no | `url` | `TEXT` | Profile URL, stored verbatim after trimming. No format validation beyond `@NotBlank` and `@Size(max = 2000)` on the request. |
| `labelCkb` | string | yes | `label_ckb` | 200 | Display label, Sorani |
| `labelKmr` | string | yes | `label_kmr` | 200 | Display label, Kurmanji |
| `displayOrder` | number | no | `display_order` | — | Sort key, ascending. Defaults to `0`. |
| `active` | boolean | no | `active` | — | `false` rows are hidden unless `includeInactive=true` |

```json
{
  "success": true,
  "message": "Social links fetched",
  "data": [
    {
      "id": 1,
      "platform": "FACEBOOK",
      "url": "https://www.facebook.com/kurdistanheritageinstitute",
      "labelCkb": "فەیسبووک",
      "labelKmr": "Facebook",
      "displayOrder": 1,
      "active": true
    },
    {
      "id": 2,
      "platform": "YOUTUBE",
      "url": "https://www.youtube.com/@kurdistanheritage",
      "labelCkb": "یوتیوب",
      "labelKmr": "YouTube",
      "displayOrder": 2,
      "active": true
    },
    {
      "id": 3,
      "platform": "INSTAGRAM",
      "url": "https://www.instagram.com/khi.erbil",
      "displayOrder": 3,
      "active": true
    }
  ]
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

`includeInactive` is bound by Spring's `StringToBooleanConverter`, which accepts `true`/`on`/`yes`/`1`
and `false`/`off`/`no`/`0`. A value it cannot convert (e.g. `?includeInactive=maybe`) answers
`500 INTERNAL_ERROR` — see the note on type mismatches in the gotchas below.

**Example**

```bash
curl -s http://localhost:8080/api/v1/settings/social
```

---

## 7. `GET /api/v1/nav-menu` — Hamburger menu

Returns the website's top-level navigation items, each with its own background photo and its
secondary links. The tree is exactly two levels deep — an item has links, a link has nothing.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `includeInactive` | boolean | no | `false` | `false` → only items with `active = true`, **and** inactive links are filtered out of each item. `true` → every item, and every link including inactive ones. The one flag controls both levels. |

Ordering is `displayOrder ASC, id ASC` for items (`findAllByActiveTrueOrderByDisplayOrderAscIdAsc`
/ `findAllByOrderByDisplayOrderAscIdAsc`) and `displayOrder ASC, id ASC` for links (JPA `@OrderBy`
on the association).

> **Note:** the same caveat as the social links applies — `includeInactive=true` is not
> restricted to the dashboard and any anonymous caller may send it.

**Response `200 OK`**

`ApiResponse<List<NavMenuItemResponse>>`.

**`NavMenuItemResponse`**

| Field | Type | Nullable | DB column | Max length | Description |
|-------|------|----------|-----------|------------|-------------|
| `id` | number | no | `id` | — | Primary key |
| `itemKey` | string | no | `item_key` | 60 | Stable lowercase handle, unique across the table (`uk_nav_item_key`). The website keys its automatic sub-link generation off this value, so it must not change once the row exists. |
| `labelCkb` | string | no | `label_ckb` | 200 | Menu label, Sorani |
| `labelKmr` | string | yes | `label_kmr` | 200 | Menu label, Kurmanji |
| `descriptionCkb` | string | yes | `description_ckb` | `TEXT` | Sub-label under the item, Sorani |
| `descriptionKmr` | string | yes | `description_kmr` | `TEXT` | Sub-label under the item, Kurmanji |
| `href` | string | no | `href` | 300 | Target path or URL, stored verbatim after trimming |
| `imageUrl` | string | yes | `image_url` | `TEXT` | Background photo shown when the item is hovered |
| `displayOrder` | number | no | `display_order` | — | Sort key, ascending. Defaults to `0`. |
| `active` | boolean | no | `active` | — | Hidden from the default listing when `false` |
| `links` | array | no | — | — | Secondary links. Always present; `[]` when the item has none. |

**`NavMenuLinkResponse`** (each element of `links`)

| Field | Type | Nullable | DB column | Max length | Description |
|-------|------|----------|-----------|------------|-------------|
| `id` | number | no | `id` | — | Primary key in `nav_menu_links` |
| `labelCkb` | string | no | `label_ckb` | 200 | Link label, Sorani |
| `labelKmr` | string | yes | `label_kmr` | 200 | Link label, Kurmanji |
| `href` | string | no | `href` | 300 | Target path or URL |
| `displayOrder` | number | no | `display_order` | — | Sort key within the item |
| `active` | boolean | no | `active` | — | Filtered out of the default listing when `false` |

```json
{
  "success": true,
  "message": "Nav menu fetched",
  "data": [
    {
      "id": 1,
      "itemKey": "news",
      "labelCkb": "هەواڵەکان",
      "labelKmr": "Nûçe",
      "descriptionCkb": "دوایین هەواڵ و چالاکییەکانی دەزگا",
      "descriptionKmr": "Nûçe û çalakiyên dawî yên saziyê",
      "href": "/news",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/news.jpg",
      "displayOrder": 1,
      "active": true,
      "links": [
        {
          "id": 11,
          "labelCkb": "هەموو هەواڵەکان",
          "labelKmr": "Hemû nûçe",
          "href": "/news",
          "displayOrder": 1,
          "active": true
        },
        {
          "id": 12,
          "labelCkb": "ڕاگەیاندنەکان",
          "labelKmr": "Ragihandin",
          "href": "/news?type=announcement",
          "displayOrder": 2,
          "active": true
        }
      ]
    },
    {
      "id": 2,
      "itemKey": "gallery",
      "labelCkb": "گەلەری",
      "labelKmr": "Galerî",
      "href": "/gallery",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/gallery.jpg",
      "displayOrder": 2,
      "active": true,
      "links": []
    }
  ]
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

**Example**

```bash
curl -s http://localhost:8080/api/v1/nav-menu
```

---

## 8. `GET /api/v1/nav-menu/{id}` — One menu item

Fetches a single item by primary key. This handler exists to feed the dashboard's edit form, so it
behaves differently from the list endpoint in two ways:

- it returns the item **regardless of its `active` flag**;
- it returns **all** links, including inactive ones (`toResponse(item, true)` is hard-coded).

There is no query parameter to change that.

> **Note:** the route is nevertheless `permitAll` under the `GET /api/v1/**` rule, so an anonymous
> caller who guesses an id can read a menu item and links that the list endpoint hides. Nothing
> secret lives in this table, but do not use `active = false` as an access control.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key of the `nav_menu_items` row |

**Query parameters** — none.

**Response `200 OK`**

`ApiResponse<NavMenuItemResponse>` — same object as an element of endpoint #7's array.

```json
{
  "success": true,
  "message": "Nav menu item fetched",
  "data": {
    "id": 3,
    "itemKey": "sound",
    "labelCkb": "دەنگ",
    "labelKmr": "Deng",
    "descriptionCkb": "ئەرشیفی دەنگی و تۆمارە مێژووییەکان",
    "href": "/audio",
    "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/sound.jpg",
    "displayOrder": 3,
    "active": true,
    "links": [
      {
        "id": 31,
        "labelCkb": "هەموو تۆمارەکان",
        "href": "/audio",
        "displayOrder": 1,
        "active": true
      },
      {
        "id": 32,
        "labelCkb": "بەرنامەی ڕادیۆ",
        "href": "/audio?type=radio",
        "displayOrder": 2,
        "active": false
      }
    ]
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No `nav_menu_items` row with that id. Message key `navMenu.not_found`, `details` carries `{ "id": <requested id> }`. |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`** such as `/api/v1/nav-menu/abc`, see the gotchas below. |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/nav-menu/99",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NOT_FOUND",
  "message": "بڕگەی مێنیو نەدۆزرایەوە.",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "details": {
    "id": 99
  }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/nav-menu/3
```

---

## 9. `GET /api/v1/sitemap` — Site paths for one locale

Generates a flat, de-duplicated list of website paths. **The output is JSON, not `sitemap.xml`.**
There is no XML representation anywhere in the codebase; if you need a `sitemap.xml`, your
front-end has to render this list into one.

`SitemapService.generate()` builds the list in this order:

1. Eleven static paths for the locale:
   `/{locale}`, `/{locale}/about`, `/{locale}/contact`, `/{locale}/services`, `/{locale}/donate`,
   `/{locale}/news`, `/{locale}/projects`, `/{locale}/writings`, `/{locale}/audio`,
   `/{locale}/videos`, `/{locale}/gallery`
2. `/{locale}/about/{slug}` for every **active** About page (KMR slug when the locale is `ku` and
   that slug is non-null, otherwise the CKB slug; blank slugs are skipped)
3. `/{locale}/contact/{slug}` for every **active** Contact page, same slug rule
4. `/{locale}/news/{id}` for **every** news row
5. `/{locale}/projects/{id}` for **every** project row
6. `/{locale}/writings/{id}` for **every** writing row
7. `/{locale}/audio/{id}` for **every** sound track row
8. `/{locale}/videos/{id}` for **every** video row
9. `/{locale}/gallery/{id}` for **every** image collection row

The result is passed through `.distinct()`, so duplicates collapse but the order above is
preserved.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `locale` | string | no | `ckb` | Normalized by `SitemapService.normalizeLocale()`: `"kmr"` and `"ku"` both become **`ku`**; everything else, including blank, becomes **`ckb`**. |

> **Note:** the sitemap normalizes to `ckb` / **`ku`**, while `GET /api/v1/featured` normalizes the
> same parameter to `ckb` / **`kmr`**. Sending `?locale=kmr` therefore yields paths prefixed
> `/ku/…` from this endpoint and `"locale": "kmr"` from the featured endpoint. The two vocabularies
> are genuinely different in the source; do not normalize them yourself before calling.

**Response `200 OK`**

`ApiResponse<SitemapResponse>` where `SitemapResponse` is `{ locale: string, paths: string[] }`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `locale` | string | no | The normalized locale, `"ckb"` or `"ku"` |
| `paths` | array of string | no | Absolute site paths, already de-duplicated |

```json
{
  "success": true,
  "message": "Sitemap generated",
  "data": {
    "locale": "ckb",
    "paths": [
      "/ckb",
      "/ckb/about",
      "/ckb/contact",
      "/ckb/services",
      "/ckb/donate",
      "/ckb/news",
      "/ckb/projects",
      "/ckb/writings",
      "/ckb/audio",
      "/ckb/videos",
      "/ckb/gallery",
      "/ckb/about/dameza-rani",
      "/ckb/contact/hewler-office",
      "/ckb/news/42",
      "/ckb/news/41",
      "/ckb/projects/8",
      "/ckb/writings/17",
      "/ckb/audio/23",
      "/ckb/videos/12",
      "/ckb/gallery/9"
    ]
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sitemap?locale=ku"
```

---

## Enums and controlled vocabularies used by this API

None of these is a Java `enum` — they are string constants produced by `SiteContentService` and
`SitemapService`. They are listed here because a front-end has to switch on them.

### `FeaturedResponse.source`

| Value | Origin table |
|-------|--------------|
| `news` | `news` |
| `project` | `projects` |
| `writing` | `writings` |
| `video` | `videos` |
| `sound-track` | `sound_tracks` |
| `image-collection` | `image_collections` |
| `donation` | `donation_settings` (singleton row) |

### `FeaturedResponse.type`

| Value | Meaning | Produced by |
|-------|---------|-------------|
| `article` | News article | `source = news` |
| `archive` | Project / archive entry | `source = project` |
| `book` | Written work | `source = writing` |
| `video` | Video | `source = video` |
| `audio` | Sound track | `source = sound-track` |
| `gallery` | Image collection | `source = image-collection` |
| `donation` | The donation call-to-action page | `source = donation` |

### `locale` values accepted on input

| Endpoint | Accepted input (case-insensitive, trimmed) | Normalized output |
|----------|--------------------------------------------|-------------------|
| `GET /api/v1/featured`, `GET /featured` | `kmr`, `ku` | `kmr` |
| `GET /api/v1/featured`, `GET /featured` | anything else, blank, or omitted | `ckb` |
| `GET /api/v1/sitemap` | `kmr`, `ku` | `ku` |
| `GET /api/v1/sitemap` | anything else, blank, or omitted | `ckb` |

The platform-wide `Language` enum (`CKB` = Sorani, `KMR` = Kurmanji) is what the *content* modules
are keyed on. This domain never exposes that enum directly; it exposes the lowercase locale strings
above and the `…Ckb` / `…Kmr` field-name suffixes.

### `SocialLink.platform`

Free text, `@Size(max = 60)`, uppercased and unique. The codebase defines no allowed list. Values
in production use are `FACEBOOK`, `INSTAGRAM`, `YOUTUBE`, `X`, `TELEGRAM`, but a new one can be
created at any time from the dashboard — render an unknown platform with a generic icon rather
than dropping it.

### `NavMenuItem.itemKey`

Free text, `@Size(max = 60)`, lowercased and unique. Not an enum. The website uses it to attach
automatically generated secondary links to the six CMS-backed sections, so the values in use track
the content modules: `news`, `projects`, `writings`, `videos`, `sound`, `gallery`, plus editorial
entries such as `about`, `services`, `donate`, `contact`.

---

## Notes & gotchas

**A featured record with no picture silently vanishes.** `SiteContentService.featuredSlide()`
returns `null` when the resolved image URL is null or blank, and null candidates are dropped before
sorting. For the six publication types the toggle endpoints do **not** require a picture, so it is
entirely possible to feature a news item that has no cover and no `featureImageUrl` and see nothing
change on the homepage. (About, Service and Donation toggles *do* enforce an image, which is why
they never hit this.) If a slide is missing, check the record's cover first.

**The cap counts rows, not slides.** `countAllFeatured()` sums `featured = true` rows across the
six publication tables plus the donation singleton. Slides dropped for want of an image are still
counted. So the admin dashboard can hit *"Maximum of 7 featured slides allowed"* while the public
rail shows five.

**`displayOrder` in the rail is not the record's `displayOrder`.** It is recomputed `1..N` on every
request from the sort position. The stored `featuredOrder` is what an admin controls; `null` sorts
last, and ties are broken newest-id-first.

**Two `/featured` routes with two different envelopes.** `GET /api/v1/featured` returns a bare
array (`ResponseEntity<List<FeaturedResponse>>`); `GET /featured` returns
`ApiResponse<List<FeaturedResponse>>`. Same data, same service call. If you switch from the legacy
route to the v1 route you must unwrap one level less.

**Nulls are omitted everywhere.** `spring.jackson.default-property-inclusion: non_null` is set
globally, and `ApiResponse` additionally carries `@JsonInclude(NON_NULL)`. Any nullable field in
the tables above may simply not appear in the JSON. There is no `"field": null` in this API.
Responses are also pretty-printed (`spring.jackson.serialization.indent_output: true`), so expect
whitespace on the wire.

**A non-numeric `{id}` answers `500`, not `400`.** `GlobalExceptionHandler` is a plain
`@RestControllerAdvice` carrying an `@ExceptionHandler(Exception.class)` catch-all, and
`ExceptionHandlerExceptionResolver` runs before Spring's `DefaultHandlerExceptionResolver`. The
catch-all therefore intercepts `MethodArgumentTypeMismatchException` — which Spring would normally
render as `400` — and answers `500 INTERNAL_ERROR` with `"An unexpected error occurred. Please try
again later."`. This affects `GET /api/v1/nav-menu/{id}` and any query parameter that fails
conversion. Wrong verbs (`405`), unmapped paths (`404`) and unreadable bodies (`400`) are handled
explicitly and behave normally.

**Nothing in this domain is cached.** `SiteContentService` and `NavMenuService` carry no
`@Cacheable`. The only cache annotation anywhere near this code is `@CacheEvict(value = "services")`
on `setServiceFeatured()`, which clears the *Services* cache after an admin toggle. Redis (key
prefix `khi:`, default TTL 10 minutes) is used by other domains, not by these reads. Every call
here reaches PostgreSQL.

**The sitemap is the heaviest endpoint here.** It calls `findAll()` — unpaged, unfiltered — on
eight repositories on every request, then materializes one string per row. There is no cache and no
`active` / published filter on the six content tables, so **unpublished and inactive content
appears in the sitemap**. Only About and Contact pages are filtered by `isActive()`. Service detail
pages are not included at all, only the `/{locale}/services` index.

**`GET /api/v1/nav-menu` avoids the N+1.** `NavMenuItemRepository` annotates all three read methods
with `@EntityGraph(attributePaths = "links")` and `spring.jpa.open-in-view` is `false`, so the links
come back in the same query and a lazy list is never touched after the transaction closes.

**`updatedAt` on site settings is a server-clock stamp.** `SiteSettings` uses a `@PrePersist` /
`@PreUpdate` hook calling `LocalDateTime.now()` rather than Hibernate's `@CreationTimestamp` /
`@UpdateTimestamp`. It is the only timestamp this domain returns.

**`X-Trace-Id` round-trips.** `TraceIdFilter` reads an incoming `X-Trace-Id` header (or generates
one), puts it in the log MDC, and stamps it into `ApiErrorResponse.traceId`. Send your own
correlation id and quote it when reporting a bug.

**Error messages are English in practice.** Every `ApiErrorResponse` carries `message`,
`messageEn` and `messageKu`. `messageEn` resolves against `Locale.ENGLISH` and `messageKu` against
locale `ku`; neither bundle is loadable at runtime (`i18n/messages_ckb.properties` and
`i18n/messages_kmr.properties` are the only two files the `classpath:i18n/messages` basename
picks up), so both fall back to the hard-coded strings in `GlobalExceptionHandler.fallbackByCode()`.
Sending `Accept-Language: ckb` or `Accept-Language: kmr` *does* swap the top-level `message` to a
Sorani or Kurmanji phrase. Do not build UI copy from these fields — key off `code`.

**403, not 401, for a rejected anonymous write.** `SecurityConfig` configures no
`AuthenticationEntryPoint`, so Spring Security's default applies and an anonymous request to a
protected route is refused with `403`. The refusal happens in the filter chain, before the
dispatcher, so the body is Spring Boot's default error JSON — **not** an `ApiErrorResponse`.
(`JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` exist in
`ak.dev.khi_backend.user.exceptions` but are never wired into the filter chain.)

**Live spec.** Swagger UI is at `/swagger-ui.html`, the raw document at `/v3/api-docs`. `springdoc`
matches `/api/**` only, so endpoints #1 and #3–#9 appear in the `all` group (`/v3/api-docs/all`)
tagged **Public Site** and **Nav Menu**, while the legacy `GET /featured` appears in no group at
all. The `public` group's path list does not include `/api/v1/featured`, `/api/v1/site-settings`,
`/api/v1/settings/**`, `/api/v1/nav-menu/**` or `/api/v1/sitemap`, so those are missing from
`/v3/api-docs/public` even though they are public. Group membership is not an authorization
statement — `SecurityConfig` is the only authority.

**Servers.** Local development runs on `http://localhost:8080`; production is deployed on Railway.

---

## Related documentation

- Counterpart (admin writes): [`../internal/SITE_SETTINGS_API.md`](../internal/SITE_SETTINGS_API.md)
- About pages: [`./ABOUT_API.md`](./ABOUT_API.md)
- Services: [`./SERVICE_API.md`](./SERVICE_API.md)
- Contact: [`./CONTACT_API.md`](./CONTACT_API.md)
- Content that feeds the rail: [`./NEWS_API.md`](./NEWS_API.md),
  [`./PROJECT_API.md`](./PROJECT_API.md), [`./WRITING_API.md`](./WRITING_API.md),
  [`./VIDEO_API.md`](./VIDEO_API.md), [`./SOUNDTRACK_API.md`](./SOUNDTRACK_API.md),
  [`./IMAGE_COLLECTION_API.md`](./IMAGE_COLLECTION_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
