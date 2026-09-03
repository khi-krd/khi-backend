# Featured on their own pages — Service & About

**Status: backend implemented, 2026-08-17.** 91 tests green.

Featuring a service or an About page now highlights it **on the Services page / the About page**.
It no longer enters the homepage hero carousel and no longer competes with the carousel for slots.

Read this alongside `FEATURED_ABOUT_SERVICE_DONATION.md`, which built the original behaviour.
This document changes **who consumes the flag**, not how it is written.

| | |
| --- | --- |
| Scope | `services` and `about_pages` only |
| Donation | **unchanged** — stays a hero slide (Q1) |
| The six publication types | **unchanged** — news, projects, writings, videos, sound-tracks, image-collections stay hero slides |
| DB migration | **none** — same columns, narrower meaning |
| New endpoints | 0 |
| Request bodies | unchanged — same `FeaturedRequest`, same partial-update semantics |

---

## 1 · What the flag means now, per source

| Source | `featured` means | In the hero? | Counts toward `maxFeaturedSlides`? |
| --- | --- | --- | --- |
| news, projects, writings, videos, sound-tracks, image-collections | homepage hero slide | ✅ | ✅ |
| donation | homepage hero slide | ✅ | ✅ (as 1) |
| **service** | **highlight on `/services`** | ❌ | ❌ |
| **about** | **leads `/about`** | ❌ | ❌ |

The columns were reused rather than replaced: the flag already carries an order
(`featuredOrder`), a picture (`featureImageUrl`) and, for services, a line of copy
(`featureDescription`) — exactly what a page-level highlight needs. "Featured service" reads
correctly as *featured on the services page*; only the consumer was ever carousel-specific.

---

## 2 · What changed in the backend

All in `SiteContentService` unless noted. No schema change.

| # | Change | Where |
| --- | --- | --- |
| 1 | `getFeatured()` no longer collects About or Service candidates | `SiteContentService.getFeatured()` |
| 2 | The slide mappers `aboutFeatured(...)` / `serviceFeatured(...)` are gone, with their dead helpers `serviceContentFor(...)` and `plainTextExcerpt(...)` | same file |
| 3 | `countAllFeatured()` dropped the About and Service terms — now six publication types + donation | same file |
| 4 | `setAboutFeatured(...)` / `setServiceFeatured(...)` lost the cap check | same file |
| 5 | Image guards kept, rewording only (§3) | same file |
| 6 | `serviceSlideImage(...)` kept — still resolves the highlight-card picture for the guard | same file |
| 7 | `ServiceRepository.findFeaturedWithContents()` kept — backs `GET /api/v1/services/featured` | `ServiceRepository` |
| 8 | `countByFeaturedTrue()` deleted from `AboutRepository` and `ServiceRepository` — it existed only to feed the cap | both repositories |
| 9 | The two toggles widened to `hasAnyRole('ADMIN','SUPER_ADMIN')` (Q4) | `AboutController`, `ServiceController` |

`AboutRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc()` is kept but currently
unused in `main` — it is the highlight-ordering query a dedicated `GET /api/v1/about/featured`
would need. Say the word and it is a three-line endpoint; until then the website filters the
paginated list.

---

## 3 · Errors these two endpoints can still return

The cap error is **gone** from both. The image guards remain, reworded:

| Endpoint | Rule | `details.reason` |
| --- | --- | --- |
| `PATCH /api/v1/about/{id}/featured` | `featureImageUrl` **required** while featured — it becomes the About page hero image | `featureImageUrl is required to feature an About page — it becomes the About page hero image.` |
| `PATCH /api/v1/services/{id}/featured` | required only when the service has no gallery image | `featureImageUrl is required to feature a service that has no gallery image.` |

Both are `400 BAD_REQUEST`. `404` still comes back for an unknown id. **`400` with
"Maximum of N featured slides allowed across all content" can no longer occur on these two
routes** — remove that UI path from the dashboard.

Unchanged: `featuredOrder` is cleared on unfeature, `featureImageUrl` survives it, an omitted
`featureImageUrl` leaves the stored value alone, `""` clears it, and
`@CacheEvict(value = "services", allEntries = true)` still fires on a service toggle.

---

## 4 · Read endpoints — none changed

| Endpoint | State |
| --- | --- |
| `GET /api/v1/services/featured` | every featured service, ordered by `featuredOrder` (nulls last, id desc), uncached, **no longer bounded by the slide cap** |
| `GET /api/v1/services/all` | already carries `featured`, `featuredOrder`, `featureImageUrl`, `contents[].featureDescription` — the whole Services page can be built from this one call |
| `GET /api/v1/about` | already carries `featured`, `featuredOrder`, `featureImageUrl` |
| `GET /featured`, `GET /api/v1/featured` | same shape, minus any `service` / `about` slides |

> ⚠️ **JSON omits nulls.** An unfeatured About record serializes as `{"featured": false}` with
> **no** `featuredOrder` and **no** `featureImageUrl` key at all. Treat *absent* as null. The
> website's zod schemas use `.nullish()`; the dashboard must do the same.

---

## 5 · Dashboard — presentational only

| Screen | New label | Helper text |
| --- | --- | --- |
| Service | **"Feature on the Services page"** | "Shown in the highlight band at the top of /services." |
| About | **"Feature on the About page"** | "This record leads /about, and its image becomes the About page hero." |

The six publication screens and the donation screen keep saying *homepage carousel* — for them
that is still exactly what it does.

**Slide counter** — drop two terms and hide it on those two screens entirely:

```diff
  used = news + projects + writings + videos + soundTracks + imageCollections
-      + aboutPages + services + (donationFeatured ? 1 : 0)
+      + (donationFeatured ? 1 : 0)
```

**`featureDescription` hint** — ~~"used on the homepage carousel"~~ → **"used on the Services
page highlight card"**. Worth promoting from optional to recommended-while-featured: it is the
one line the card shows.

**About image requirement** — keep disabling the switch until a picture is chosen, but say why
it is needed: it is the About page hero image, not a carousel crop. Wide, ~2560×1440.

---

## 6 · Website (khi-website)

Unchanged from the plan: parse the featured fields (`src/types/service.ts`,
`src/types/about.ts` currently drop them), render a highlight band above `ServicesShell`, pick
the lead About record by `featured` → lowest `featuredOrder` with today's rule as the fallback,
and pass `featureImageUrl` into `AboutHero` as the poster — the About hero has no picture at all
today.

Keep the `about` / `service` branches in `src/lib/content/href.ts`. They are unreachable against
this build, but they are the only defence if a stale backend is still emitting those slides.

---

## 7 · Decisions taken

| | Question | Answer |
| --- | --- | --- |
| **Q1** | Donation: hero or its own page? | **Hero, unchanged.** It promotes a call-to-action page with no list of its own to be highlighted in. |
| **Q2** | Cap on page highlights? | **None.** Any number of services may be featured; About is bounded by how many records exist. A soft warning in the dashboard beats a `400`. |
| **Q3** | About: one lead or several highlights? | **Lowest `featuredOrder` leads**, the rest render as highlighted sections below. Featuring exactly one behaves like "this is the About page"; several degrade gracefully. Backend needs nothing for this — the order is already in the payload. |
| **Q4** | SUPER_ADMIN? | **Widened these two endpoints** to `hasAnyRole('ADMIN','SUPER_ADMIN')`. The other seven carousel toggles stay `hasRole('ADMIN')` — no `RoleHierarchy` bean was added, so that asymmetry is deliberate and documented. |

---

## 8 · Verifying it

```bash
BASE=https://blissful-spontaneity-production.up.railway.app
TOKEN=<admin or super-admin jwt>

# 1 · the hero must no longer contain a service or an about slide
curl -s "$BASE/api/v1/featured?locale=ckb" | jq '[.[] | .source] | unique'
#   expect: no "service", no "about"   (donation may still be there)

# 2 · the featured service is still readable on its own route
curl -s "$BASE/api/v1/services/featured" | jq '.data.content[] | {id, navAnchorId, featuredOrder}'

# 3 · featuring a service must succeed even with the carousel full
curl -X PATCH "$BASE/api/v1/services/16/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":2}'
#   expect: 204, NOT 400 "Maximum of 7 featured slides"

# 4 · About still refuses to be featured without a picture
curl -X PATCH "$BASE/api/v1/about/6/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true}'
#   expect: 400, reason mentions the About page hero image

# 5 · featuring About with a picture succeeds
curl -X PATCH "$BASE/api/v1/about/5/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":1,"featureImageUrl":"https://…/about-hero.jpg"}'
curl -s "$BASE/api/v1/about?page=0&size=20" | jq '.data.content[] | select(.featured)'
```

Backend test checklist — all covered by `SiteContentServiceInstitutionalFeaturedTests`:

- [x] `getFeatured()` returns no `service` / `about` candidates even when both are featured with
      a resolvable image — and does not even query those repositories
- [x] `countAllFeatured()` ignores services and About pages
- [x] featuring a service while the carousel is full succeeds, with no cap lookup at all
- [x] featuring an About page with a blank `featureImageUrl` still `400`s, with the new wording
- [x] unfeaturing still nulls `featuredOrder` and keeps `featureImageUrl`
- [x] a gallery image alone satisfies the service picture requirement
- [x] donation is still collected into the hero

---

## 9 · Rollout

1. **Website first** — everything it needs is already in the API responses, so the highlight
   band and About hero can ship before the backend. During that window a featured service shows
   up in both places: visible, harmless, brief.
2. **Backend** (this change) — the service leaves the hero and returns its slot to the carousel.
3. **Dashboard last** — pure relabelling, so the labels never describe behaviour that is not
   live yet.

Rollback is symmetric: re-adding the two collection blocks in `getFeatured()` restores the old
behaviour, and no data changes in either direction.

> Note: `SiteSettings.getSiteSettings()` / `updateSiteSettings()` still have no controller, so
> `maxFeaturedSlides` can only be changed by editing the `site_settings` row. Unchanged by this
> work — but the default of 7 now governs seven sources instead of nine, so it goes further.
