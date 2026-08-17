# Featured — About, Service & Donation

The homepage carousel could only be fed by the six publication types (news, projects,
writings, videos, sound-tracks, image-collections). It can now also be fed by the three
**institutional** pages: **About**, **Service**, and the **Donation** page.

Read this alongside `IMAGES_MENU_AND_FEATURED.md` — everything in there about
`featureImageUrl`, the error envelope, and the `Accept-Language` gotcha still applies.

| | |
| --- | --- |
| New sources | `about`, `service`, `donation` |
| New slide `type` values | `"about"`, `"service"`, `"donation"` |
| New write endpoints | 3 (§2) |
| Slide shape | **unchanged** — no new fields on `FeaturedResponse` |
| DB migration | none needed — `ddl-auto: update` adds the columns (§7) |
| Backwards compatible | yes — every new column defaults to `false` / `NULL`, so `/featured` returns byte-identical JSON until an admin features one of the three |

---

## 1 · Why these three are not just three more of the same

The six publication types **derive** their slide from content they already have: title and
description come from `ckbContent` / `kmrContent`, and the image comes from their cover, with
`featureImageUrl` acting as an optional wide-crop override.

The three institutional pages have none of that:

| | The problem | The consequence |
| --- | --- | --- |
| **About** | No cover image of any kind — every picture lives inline inside the Tiptap `body`. Also has no `description` field, only `subtitle` and `metaDescription`. | `featureImageUrl` is **not** an override here, it is the *only* image the slide can have — so it is **required** while featured. |
| **Service** | Bilingual text is one row per language (`service_contents`), not embedded columns. Its `description` is **Tiptap HTML** — unusable as carousel copy. | New short plain-text field `featureDescription`, one per language row. |
| **Donation** | A **singleton settings row** — there is no "which record" to flag. | The toggle takes no `{id}`, and donation counts as **at most 1** slide. |

Everything else is identical to the six: the same `featured` / `featuredOrder` /
`featureImageUrl` fields, the same global cap, the same ordering, the same slide JSON.

---

## 2 · The new API surface

| Action | Method & path | Auth | Response |
| --- | --- | --- | --- |
| Feature / unfeature an About page | `PATCH /api/v1/about/{id}/featured` | `ADMIN` (§6.4) | `204 No Content`, empty body |
| Feature / unfeature a service | `PATCH /api/v1/services/{id}/featured` | `ADMIN` | `204 No Content`, empty body |
| Feature / unfeature the donation page | `PATCH /api/v1/donations/settings/featured` | `ADMIN` | `200` + `ApiResponse<DonationSettingsResponse>` |
| List featured services | `GET /api/v1/services/featured?page=0&size=20` | public | `ApiResponse<Page<ServiceResponse>>` |

Request body for all three PATCHes is the same `FeaturedRequest` the six already use:

```json
{ "featured": true, "featuredOrder": 1, "featureImageUrl": "https://…/hero-2560.jpg" }
```

Same partial-update semantics as before:

| Field | Omitted / `null` | Value |
| --- | --- | --- |
| `featured` | treated as `true` | `true` features, `false` unfeatures |
| `featuredOrder` | `null` → sorts last | lower shows first |
| `featureImageUrl` | stored value untouched | a URL is trimmed and stored; `""` clears it |

Unfeaturing nulls `featuredOrder` but **keeps** `featureImageUrl`, so re-featuring later
restores the same picture.

> ### ⚠️ `GET /api/v1/services/featured` changed behaviour
> It used to always return an **empty page** — it existed only so the dashboard's generic
> `/{resource}/featured` route would not collide with `/{id}`. It now returns the real
> featured services, ordered by `featuredOrder` (nulls last, then newest id first). If the
> dashboard relied on it being empty, that assumption is gone.

The donation PATCH returns the whole saved settings object (rather than `204`) because it is
a singleton — one call updates and re-reads the donation screen:

```json
{
  "success": true,
  "message": "Donation featured state updated",
  "data": {
    "id": 1,
    "titleCkb": "پاڵپشتیمان بکە",
    "heroImageUrl": "https://…/donation-hero.jpg",
    "featured": true,
    "featuredOrder": 2,
    "featureImageUrl": null,
    "…": "…"
  }
}
```

---

## 3 · What khi-website has to do

**The slide shape did not change.** `GET /featured` and `GET /api/v1/featured` return the
exact same object as before:

```json
{
  "id": "about-5",
  "source": "about",
  "entityId": 5,
  "type": "about",
  "slug": "derbare-me",
  "title": "دەربارەی ئێمە",
  "description": "ناوەندی مێژووی کورد.",
  "image": { "url": "https://…/about-hero.jpg", "alt": "دەربارەی ئێمە" },
  "locale": "ckb",
  "featured": true,
  "featuredOrder": 1,
  "displayOrder": 1,
  "active": true
}
```

So the carousel itself needs **no change**. The one thing that must be added is the
**click target**, because the website resolves the slide's link from `type` + `slug`, and
there are three new `type` values it does not know yet.

### 3.1 The three new types and their `slug`

| `type` | `source` | `entityId` | What `slug` holds | Suggested link |
| --- | --- | --- | --- | --- |
| `about` | `about` | About page id | the **localized** About slug (`slugKmr` when `?locale=kmr`, else `slugCkb`); falls back to the id as a string | your About detail route, e.g. `/about/{slug}` |
| `service` | `service` | Service id | the service's `navAnchorId` when set, otherwise the id as a string | the Services page **anchor**, e.g. `/services#{slug}` — `navAnchorId` exists precisely for `#anchor` scrolling |
| `donation` | `donation` | donation settings id | the constant string `"donation"` | your donation route, e.g. `/donation` |

For comparison, the existing six are unchanged: `article`, `archive`, `book`, `video`,
`audio`, `gallery`.

### 3.2 Add them to the type → href map

Wherever the website maps a slide to a URL, add the three cases. Prefer `source` over `type`
if you have it — `source` is the stable machine name — and keep a default branch so an
unknown future type renders as a non-clickable slide instead of a broken link:

```ts
// featured slide -> href
function slideHref(slide: FeaturedSlide): string | null {
  switch (slide.type) {
    case 'article':   return `/news/${slide.slug}`;
    case 'archive':   return `/projects/${slide.slug}`;
    case 'book':      return `/publications/writings/${slide.slug}`;
    case 'video':     return `/publications/videos/${slide.slug}`;
    case 'audio':     return `/publications/sounds/${slide.slug}`;
    case 'gallery':   return `/publications/images/${slide.slug}`;

    // NEW
    case 'about':     return `/about/${slide.slug}`;
    case 'service':   return `/services#${slide.slug}`;
    case 'donation':  return `/donation`;

    default:          return null;   // render the slide, do not link it
  }
}
```

Adjust the six existing paths to whatever your router actually uses — the point is only the
three new branches and the `default`.

### 3.3 Nothing else changes

- Same two read routes, same envelopes: `GET /featured` → `{ success, message, data: [...] }`,
  `GET /api/v1/featured` → **bare array**. Both public, both accept `?locale=ckb|kmr|ku`.
- `displayOrder` is still renumbered `1..N` across all sources combined.
- `description` may be `null` on any slide (it always could be) — keep the existing guard.
- An institutional slide with no resolvable image is **dropped server-side**, exactly like
  the six. The website never sees a slide with a blank `image.url`.

---

## 4 · Where each field comes from

First non-blank wins, per source:

| Source | `title` | `description` | `image.url` |
| --- | --- | --- | --- |
| `about` | localized `content.title` | localized `content.subtitle` → `content.metaDescription` | **`featureImageUrl` only** |
| `service` | localized `ServiceContent.title` | localized `ServiceContent.featureDescription` → tag-stripped excerpt of `description` (≤ 300 chars, cut on a word boundary, `…` appended) | `featureImageUrl` → first gallery slot (`galleryMedia`: image slot's `url`, video slot's `posterUrl`) → legacy `featureImageUrls[0]` → `heroPosterUrl` |
| `donation` | localized `titleCkb` / `titleKmr` | localized `descriptionCkb` / `descriptionKmr` | `featureImageUrl` → `heroImageUrl` |

"Localized" means: with `?locale=kmr` prefer KMR and fall back to CKB; otherwise prefer CKB
and fall back to KMR. For Service, whose text is one row per `languageCode`, the requested
language's row is used and the other language's row is the fallback.

`image.alt` is the resolved title, same as the six.

---

## 5 · What khi-dashboard has to build

Three screens change. The featured toggle widget you already have for news/projects can be
reused as-is — same request body, same semantics — with the per-screen notes below.

### 5.1 About screen

`AboutResponse` now returns three new **read-only** fields:

```jsonc
// GET /api/v1/about/5 — excerpt
{
  "id": 5,
  "slugCkb": "derbare-me",
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://…/about-hero.jpg",
  "…": "…"
}
```

> They are read-only there **on purpose**. `POST` / `PUT /api/v1/about` ignore them — those
> are full-replace saves, so honouring the fields would wipe the hero picture every time a
> content form saved without sending them. `PATCH /api/v1/about/{id}/featured` is the only
> writer. Same rule as the six.

**The one extra rule: the image is mandatory.** About has no cover to fall back on, so the
backend rejects featuring a page whose `featureImageUrl` is blank:

```
400 BAD_REQUEST
details.reason = "featureImageUrl is required to feature an About page — About has no cover image to fall back on."
```

So the UI should require the picture **before** enabling the "Feature" switch:

1. Upload via `POST /api/v1/media/upload` → take `data.fileUrl`.
2. Send it together with the toggle in one call:

```json
{ "featured": true, "featuredOrder": 1, "featureImageUrl": "https://…/about-hero.jpg" }
```

Disable the switch (with a hint) while no image is chosen and none is stored. Sending
`{"featured": true}` alone on a page that already has a stored `featureImageUrl` is fine.

### 5.2 Service screen

Two additions.

**(a) A new bilingual field in the content editor: `featureDescription`.**
It sits next to `title` and `description` inside each `contents[]` row and is saved through
the normal `POST` / `PUT /api/v1/services` body — it is **not** part of the featured PATCH:

```jsonc
// POST/PUT /api/v1/services — excerpt
{
  "serviceType": "Training",
  "contents": [
    {
      "languageCode": "CKB",
      "title": "ستودیوی تۆمارکردن",
      "description": "<p>…Tiptap HTML…</p>",
      "featureDescription": "ستودیویەکی تەواو بۆ تۆمارکردنی دەنگ."
    },
    { "languageCode": "KMR", "title": "…", "description": "…", "featureDescription": "…" }
  ]
}
```

| | |
| --- | --- |
| Purpose | the one line the homepage slide shows |
| Type | plain text, optional, max **1000** chars (longer input is truncated) |
| Input | a plain `<textarea>` / `<input>` — **not** the Tiptap editor |
| Sanitising | any HTML sent is stripped and whitespace collapsed on save |
| If left blank | the slide falls back to a tag-stripped 300-char excerpt of the Tiptap `description` — readable, but usually not what an editor wants |
| Read back | `contents[].featureDescription` on `ServiceResponse` |

Show the hint *"used on the homepage carousel"* next to it, and treat it as recommended
whenever the service is featured.

**(b) The featured toggle.** `ServiceResponse` now also returns `featured`, `featuredOrder`
and `featureImageUrl` (read-only, same rule as About — the service `PUT` ignores them).

The image is *not* mandatory here: a service with a gallery already has a usable picture. It
is only required when the service has no gallery image at all:

```
400 BAD_REQUEST
details.reason = "featureImageUrl is required to feature a service that has no gallery image."
```

A gallery picture is often not carousel-shaped, so offering an explicit wide hero image is
still the better default in the UI.

### 5.3 Donation screen

The donation page is a singleton, so there is **no id** and no list to pick from — it is one
switch on the donation settings screen. Two ways to write it:

**Preferred — the dedicated PATCH.** Returns the saved settings, so the screen can re-render
from the response:

```bash
PATCH /api/v1/donations/settings/featured
{ "featured": true, "featuredOrder": 2, "featureImageUrl": "https://…/donation-hero.jpg" }
```

**Or inline in the existing settings PUT.** `PUT /api/v1/donations/settings` now accepts
`featured`, `featuredOrder` and `featureImageUrl` too, and they are **null-tolerant**:
omitting them leaves the stored values untouched, so an older dashboard build that saves the
donation form cannot silently unfeature the page. `featuredOrder` is only applied while the
page is featured.

`DonationSettingsResponse` returns all three on every read, so the switch can be rendered
straight from `GET /api/v1/donations/settings`.

The image falls back to the existing `heroImageUrl`, so featuring works with no extra upload.
It only fails when both are blank:

```
400 BAD_REQUEST
details.reason = "featureImageUrl or heroImageUrl is required to feature the donation page."
```

And, since the row must exist before it can be flagged:

```
400 BAD_REQUEST
details.reason = "Donation settings have not been saved yet — save the donation page before featuring it."
```

### 5.4 The slide-count budget just got tighter

The cap is **global across all nine sources**, not per type — `SiteSettings.maxFeaturedSlides`,
default **7**. Featured About pages, featured services, and the donation page (counting as 1)
now consume the same budget as news and videos.

> **Note — the cap has no HTTP endpoint today.** `SiteContentService.getSiteSettings()` and
> `updateSiteSettings()` exist and work, but no controller exposes them, so the only way to
> change the limit right now is editing the `site_settings` row directly. Unrelated to this
> work (pre-existing), but worth knowing before the dashboard offers a "max slides" input —
> that needs two small endpoints added first.

If the dashboard shows a "N of M slides used" counter, its arithmetic must become:

```
used = news + projects + writings + videos + soundTracks + imageCollections
     + aboutPages + services + (donationFeatured ? 1 : 0)
```

Over the cap, every featured PATCH — old and new — answers:

```
400 BAD_REQUEST
details.reason = "Maximum of 7 featured slides allowed across all content. Unfeature one first."
```

Changing the `featuredOrder` of an **already featured** record never hits the cap; only
turning `featured` on does.

---

## 6 · Rules, errors and gotchas

### 6.1 Errors these endpoints can return

Same envelope as `IMAGES_MENU_AND_FEATURED.md` §5.1.

| Status | `code` | Cause |
| --- | --- | --- |
| `400` | `BAD_REQUEST` | global cap reached — `details.reason` explains |
| `400` | `BAD_REQUEST` | no resolvable slide image (About always, Service without a gallery, Donation without a hero) |
| `400` | `BAD_REQUEST` | donation settings row does not exist yet |
| `403` | — | missing token, or a non-`ADMIN` role. **Empty body** |
| `404` | `NOT_FOUND` | no About page / service with that id |

### 6.2 A blank image is a silent drop — that is why these validate

`getFeatured()` drops any candidate whose image is blank. For the six that is harmless (the
cover almost always exists), but an About page has nothing to fall back on, so featuring one
without a picture would produce a slide that never renders, with no error anywhere. The three
new toggles therefore fail fast instead. Do not work around it by writing the column directly.

### 6.3 Ordering is unchanged

All candidates from all nine sources are pooled, sorted by `featuredOrder` ascending (`null`
last), ties broken by **newest id first**, truncated to the cap, then `displayOrder` is
renumbered `1..N`. Because ties break on the raw id, ordering *between different sources* with
the same `featuredOrder` is arbitrary — set explicit `featuredOrder` values when the sequence
matters.

### 6.4 ⚠️ SUPER_ADMIN gets `403`

| Action | ADMIN | SUPER_ADMIN |
| --- | --- | --- |
| all nine featured PATCHes | ✅ | **❌ `403`** |

The featured endpoints are annotated `@PreAuthorize("hasRole('ADMIN')")`, there is no
`RoleHierarchy` bean, and `Role.getAuthorities()` grants exactly one `ROLE_<name>` — so a
SUPER_ADMIN account is rejected. Pre-existing across the six; the three new ones follow the
same pattern deliberately rather than diverging. **Use an ADMIN token.** (Widening it later is
a one-line change on all nine annotations.)

### 6.5 Service response cache

`ServiceService` caches its read paths in Redis under the `services` cache, and
`ServiceResponse` now carries the featured fields. `setServiceFeatured()` is annotated
`@CacheEvict(value = "services", allEntries = true)`, so a toggle invalidates those pages.
`GET /api/v1/services/featured` is deliberately **not** cached — it is bounded by the slide
cap and must reflect a toggle immediately.

---

## 7 · Schema changes

`ddl-auto: update` — Hibernate adds these on boot. No migration file, no manual SQL.

| Table | New columns |
| --- | --- |
| `about_pages` | `featured` (boolean), `featured_order` (int), `feature_image_url` (text) |
| `services` | `featured` (boolean), `featured_order` (int), `feature_image_url` (text) |
| `service_contents` | `feature_description` (varchar 1000) |
| `donation_settings` | `featured` (boolean), `featured_order` (int), `feature_image_url` (text) |

Every existing row gets `featured = false` / `NULL`, so the carousel output is unchanged until
an admin features something. Safe to deploy at any time; rolling back only loses the flags.

---

## 8 · Copy-paste recipes

```bash
BASE=https://blissful-spontaneity-production.up.railway.app
TOKEN=<admin jwt>          # ADMIN, not SUPER_ADMIN — §6.4

# 1 · upload the wide hero picture once (2560×1440, JPEG ~80, < 500 KB)
curl -X POST "$BASE/api/v1/media/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@about-hero.jpg" -F "type=image"
# -> data.fileUrl

# 2 · feature an About page — image REQUIRED
curl -X PATCH "$BASE/api/v1/about/5/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Accept-Language: ckb" \
  -d '{"featured":true,"featuredOrder":1,"featureImageUrl":"https://…/about-hero.jpg"}'

# 3 · feature a service — image optional when it has a gallery
curl -X PATCH "$BASE/api/v1/services/3/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":2,"featureImageUrl":"https://…/service-hero.jpg"}'

# 4 · give that service its carousel line (normal service save, per language)
curl -X PUT "$BASE/api/v1/services/3" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"serviceType":"Training","contents":[
        {"languageCode":"CKB","title":"ستودیوی تۆمارکردن","description":"<p>…</p>",
         "featureDescription":"ستودیویەکی تەواو بۆ تۆمارکردنی دەنگ."}]}'

# 5 · feature the donation page — no id, returns the saved settings
curl -X PATCH "$BASE/api/v1/donations/settings/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":3}'

# 6 · reorder without touching the picture
curl -X PATCH "$BASE/api/v1/about/5/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":4}'

# 7 · unfeature (keeps featureImageUrl for next time)
curl -X PATCH "$BASE/api/v1/services/3/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":false}'

# 8 · check the result
curl "$BASE/featured?locale=ckb"              # all slides, ApiResponse envelope
curl "$BASE/api/v1/featured?locale=kmr"       # all slides, bare array
curl "$BASE/api/v1/services/featured"         # featured services only
curl "$BASE/api/v1/donations/settings"        # donation featured state
```

---

## 9 · Status

| Piece | State |
| --- | --- |
| About — 3 columns, repo queries, mapper, PATCH, response fields | ✅ built |
| Service — 3 columns + `feature_description`, fetch-join query, mapper, PATCH, real `GET /featured` | ✅ built |
| Donation — 3 columns, mapper, PATCH, null-tolerant settings PUT, response fields | ✅ built |
| Global cap now counts all nine sources | ✅ built |
| Tests | ✅ 7 new (`SiteContentServiceInstitutionalFeaturedTests`), full suite **88 green**, `BUILD SUCCESS` |
| Dashboard UI (§5) | ⬜ not started — `khi-dashboard` |
| Website type → href map (§3.2) | ⬜ not started — `khi-website` |
| Deployed to Railway | ⬜ pending |
