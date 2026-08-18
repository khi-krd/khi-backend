# Site Settings API — Internal (Admin)

**Base URL:** `/api/v1/site-settings`
**Platform:** Spring Boot 3 · JWT · JSON · Not bilingual · Not paginated
**Note:** Branding and global site configuration — the institute **logo**, the **donate band
picture**, and the homepage carousel slide cap. A singleton row (`site_settings`). Until now the
entity had no controller at all and could only be changed by editing the database row.

---

## Endpoint Summary

| Method | Path | Auth Required | Role | Description |
|--------|------|--------------|------|-------------|
| `GET` | `/api/v1/site-settings` | No | Public | Read branding and global settings |
| `PUT` | `/api/v1/site-settings` | Yes | `ADMIN` / `SUPER_ADMIN` | Save branding and global settings |

---

## The Rule That Matters

**Nothing here is required, and no picker may block a save.** Every field is tri-state — the same
convention as the `featureImageUrl` fields you already use:

| Sent | Effect |
|------|--------|
| field **omitted** | leave the stored value alone |
| `""` | clear it (stored as `null`) |
| a value | trim and store |

So `PUT {"logoUrl": "https://…"}` changes the logo and touches nothing else. An empty body
`PUT {}` is a legal no-op that returns the current settings.

`maxFeaturedSlides` follows the same omitted-means-leave-alone rule, but has no clear form — omit
it to keep the current cap. On the very first save, when no row exists yet, it is filled in with
the default of `7` so a logo-only save can create the row.

---

## Response Object

`SiteContentDtos.SiteSettingsResponse`, wrapped in the standard `ApiResponse` envelope.

| Field | Type | Description |
|-------|------|-------------|
| `id` | long \| null | Row ID. `null` when no row has been saved yet |
| `logoUrl` | string \| null | Header and footer logo. `null` → website uses its bundled logo |
| `donateImageUrl` | string \| null | Donate band photograph. `null` → band renders on plain dark ground |
| `maxFeaturedSlides` | integer | Homepage carousel cap. Defaults to `7` |
| `updatedAt` | datetime \| null | Last save |

```json
{
  "success": true,
  "message": "Site settings fetched",
  "data": {
    "id": 1,
    "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/branding/khi-logo.png",
    "donateImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/branding/archive.jpg",
    "maxFeaturedSlides": 7,
    "updatedAt": "2026-08-18T10:04:11"
  }
}
```

---

## `GET /api/v1/site-settings` — Read Settings

**Auth:** None — public. The website needs the logo on first paint.

Takes no parameters.

**Response `200 OK`:** `SiteSettingsResponse`.

**Never 404s.** With no row stored yet it answers with the defaults — `logoUrl` and
`donateImageUrl` `null`, `maxFeaturedSlides` `7` — so a fresh database still serves a usable
response and the dashboard screen always has something to render.

---

## `PUT /api/v1/site-settings` — Save Settings

**Auth:** JWT required · Role: `ADMIN` or `SUPER_ADMIN`
**Content-Type:** `application/json`

**Request Body** — every field optional:

| Field | Type | Validation | Description |
|-------|------|-----------|-------------|
| `logoUrl` | string | — | Absolute `https://` URL, or `""` to clear |
| `donateImageUrl` | string | — | Absolute `https://` URL, or `""` to clear |
| `maxFeaturedSlides` | integer | `1`–`20` when present | Homepage carousel cap |

```json
{
  "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/branding/khi-logo.png",
  "donateImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/branding/archive.jpg",
  "maxFeaturedSlides": 7
}
```

**Response `200 OK`:** the saved `SiteSettingsResponse`.

**Errors:**

| Status | Cause |
|--------|-------|
| `400` | `maxFeaturedSlides` present and outside `1`–`20` |
| `401` | JWT missing or invalid |
| `403` | Not `ADMIN` or `SUPER_ADMIN` |

There is **no URL validation** — the API stores whatever string it is given. The dashboard must
enforce the `https://` rule; see below.

---

## Dashboard — Settings → Branding

One new screen holding both pickers plus the slide cap.

### Logo picker

| | |
| --- | --- |
| Field | `logoUrl` |
| Rendered at | 64 × 64 px (52 × 52 on phones) |
| Upload | **512 × 512 PNG with a transparent background** |

**The one thing the helper text must say:** the logo appears on a **cream background in the header**
and a **near-black background in the footer**. A logo with a white box baked into it looks correct
at the top of the page and wrong at the bottom. **Transparent PNG — not JPG.**

### Donate band picker

| | |
| --- | --- |
| Field | `donateImageUrl` |
| Upload | **2000 × 1500 JPG** (minimum 1600 × 1200) |

Two things for the helper text:

1. **One upload, two treatments.** The same picture is shown sharp inside the slanted panel and
   again, blurred, behind it. The editor uploads one file.
2. **The panel is a slanted crop.** Keep the subject centred — the left and right edges are cut at
   some screen widths.

This is the best place on the site for a real archive photograph. It currently shows a stock
picture of a notebook and a map loaded from Unsplash — the most-seen placeholder on the site.

### Slide cap

`maxFeaturedSlides` has never had a UI. Range `1`–`20`, default `7`. It caps the homepage carousel
across all seven featured content types.

### Rules the dashboard must enforce

The API stores what it is given, so these are client-side:

1. **Absolute `https://` URLs only.** The site upgrades insecure requests, so `http://` is blocked,
   and a relative path will not resolve — the website and the API are on different hosts.
2. **Upload to the existing S3 bucket** (`s3-khiwebsite.s3.us-east-1.amazonaws.com`). A different
   host needs a website deploy before anything appears.
3. **Empty is a normal state.** Both images have a working fallback. Saving with both cleared is
   legal and must not be blocked.

---

## Error Responses

| Status | Reason |
|--------|--------|
| `400 Bad Request` | `maxFeaturedSlides` outside `1`–`20` |
| `401 Unauthorized` | JWT token is missing or invalid |
| `403 Forbidden` | Authenticated user is not `ADMIN` or `SUPER_ADMIN` |
| `500 Internal Server Error` | Unexpected server-side failure |

`GET` has no error cases — it always returns `200`.

---

## Source Reference

| Concern | Location |
|---------|----------|
| Endpoints | `PublicSiteController` — the branding section |
| Business logic | `SiteContentService.getSiteSettings`, `SiteContentService.updateSiteSettings` |
| Entity / table | `SiteSettings` → `site_settings` (`logo_url`, `donate_image_url`, `updated_at`) |
| Repository | `SiteSettingsRepository.findFirstByOrderByIdAsc()` — enforces the singleton |
| DTOs | `SiteContentDtos.SiteSettingsRequest` / `SiteSettingsResponse` |
| Auth matcher | `user/configs/SecurityConfig` — `PUT /api/v1/site-settings` |
| Tests | `SiteContentServiceSiteSettingsTests` |
