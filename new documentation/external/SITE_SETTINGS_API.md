# Site Settings API — External (Public)

**Base URL:** `/api/v1/site-settings`
**Platform:** Spring Boot 3 · No Auth Required · Not bilingual · Not paginated
**Note:** One read-only endpoint carrying site-wide branding: the institute **logo** and the
**donate band picture**. Both were hardcoded in the website until now.

---

## Endpoint Summary

| Method | Path | Auth Required | Description |
|--------|------|--------------|-------------|
| `GET` | `/api/v1/site-settings` | No | Read site branding and global settings |

---

## `GET /api/v1/site-settings` — Read Settings

**Auth:** None — public

Takes no parameters. There is one settings row site-wide, and the same values are served to CKB and
KMR visitors alike.

**Response `200 OK`:**
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

**Response Fields:**

| Field | Type | Where it renders | Fallback when `null` |
|-------|------|------------------|----------------------|
| `logoUrl` | string \| null | Header and footer of every page, 64 × 64 (52 × 52 on phones) | the website's bundled `/logo.png` |
| `donateImageUrl` | string \| null | The green donate band above the footer — sharp inside the slanted panel, blurred behind it | a plain dark ground |
| `maxFeaturedSlides` | integer | Homepage carousel cap. Informational — `/api/v1/featured` already applies it | `7` |
| `id`, `updatedAt` | — | Not needed for rendering; `updatedAt` is a useful cache key |

---

## Both Images Are Nullable — And That Is Normal

**`GET` never 404s.** On a fresh database it returns `logoUrl: null`, `donateImageUrl: null`, and
`maxFeaturedSlides: 7`, so there is always a `200` to render from.

`null` is the expected value until an editor uploads something, and an editor can clear either
picture at any time. Both have a working fallback, so:

- **`logoUrl` null** → keep rendering the bundled logo. The site works exactly as it does today.
- **`donateImageUrl` null** → render the donate band on a plain dark ground. The heading and the
  button are unaffected.

Never treat `null` as an error and never block render on this request.

### The donate band uses one file twice

`donateImageUrl` is a single upload with two treatments in the band: shown **sharp** inside the
slanted panel, and again **blurred** behind it. Do not expect a second field for the blurred copy.

---

## Caching

`logoUrl` and `donateImageUrl` change only when an editor saves the Branding screen, so this
response is safe to cache aggressively. Key the cache on `updatedAt` if you cache across sessions.

Both URLs point at `s3-khiwebsite.s3.us-east-1.amazonaws.com` and are absolute `https://` — the
website and the API are on different hosts, so relative paths would not resolve.

---

## Error Responses

| Status | Reason |
|--------|--------|
| `500 Internal Server Error` | Unexpected server-side failure |

There are no `400` or `404` cases: the endpoint takes no input and always has a row or a default to
return.
