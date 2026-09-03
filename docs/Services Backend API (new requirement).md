# Services Backend API

# Services Backend API

Spec for **khi-backend** / CMS so **`/[locale]/services`** works in **khi-website**.

## Overview

- The Services page is **dynamic**: the site fetches active records and renders **one scroll section per record**.
- There is **no fixed section list** on the frontend — not 8, not any preset names. The API may return **2 sections or 30**; the page renders whatever comes back (in response order).
- Page chrome (hero eyebrow/title/intro/CTA) uses i18n today. Section **titles and bodies** come from the API.

**Public fetch**

```
GET /api/v1/services/all?page=0&size={bulkSize}
```

Only records with `active: true` should be returned publicly. Records **without a localized title** for the current locale (after fallback) are skipped.

**Locales**

| URL | `contents[].languageCode` |
| --- | --- |
| `/ckb/services` | `CKB` (preferred) |
| `/ku/services` | `KMR` (preferred) |

Fallback: other language → first `contents` entry.

---

## Endpoints

| Method | Path | Use |
| --- | --- | --- |
| `GET` | `/api/v1/services/all` | Public site (bulk list) |
| `GET` | `/api/v1/services` | Admin list (optional `type`, pagination) |
| `POST` | `/api/v1/services` | Create |
| `PUT` | `/api/v1/services/{id}` | Update |

Response (paginated):

```json
{
  "content": [ /* Service[] — order = display order on site */ ],
  "totalElements": 12,
  "totalPages": 1
}
```

---

## Service record (= one page section)

### Fields

| Field | Type | Notes |
| --- | --- | --- |
| `id` | number | Required |
| `active` | boolean | Required; inactive = hidden |
| `contents[]` | array | Required; see below |
| `navAnchorId` | string | Optional slug for `#anchor` links; unique if set. Falls back to `id` |
| `serviceType` | string | Optional label/category |
| `location` | string | Optional |
| `layoutType` | enum | Optional: `MEDIA_HERO` | `FEATURE_GRID` | `DEFAULT` |
| `galleryMedia[]` | array | **Recommended** — ordered gallery slots |
| `featureImageUrls` | string[] | Legacy gallery fallback |
| `thumbnailUrls` | string[] | Legacy gallery fallback |
| `heroVideoUrl` | string | Legacy; page hero + gallery fallback |
| `heroPosterUrl` | string | Legacy poster / page hero background |
| `partnerIds` | number[] | Optional; About partner IDs for bottom cards |
| `publishedAt` | datetime | Optional |
| `sortOrder` / `displayOrder` | number | Optional but recommended — control nav/scroll order |

### Localized content (`contents[]`)

Use **`contents[]` + `languageCode`**. Not `ckbContent` / `kmrContent`.

```json
"contents": [
  {
    "languageCode": "CKB",
    "title": "ناونیشانی بەش",
    "description": "Markdown body…"
  },
  {
    "languageCode": "KMR",
    "title": "Sernavê beşê",
    "description": "Markdown body…"
  }
]
```

| Field | Required | Notes |
| --- | --- | --- |
| `languageCode` | ✅ | `CKB` or `KMR` |
| `title` | ✅ | Nav label + section heading. No title → section omitted |
| `description` | Optional | Markdown (GFM); rendered as rich prose |

### Gallery (`galleryMedia[]`)

Ordered list; **each slot is `IMAGE` or `VIDEO`**. Any position can be video; multiple videos allowed.

```json
"galleryMedia": [
  { "type": "IMAGE", "url": "https://…/1.jpg", "alt": "…" },
  { "type": "VIDEO", "url": "https://…/clip.mp4", "posterUrl": "https://…/poster.jpg" },
  { "type": "IMAGE", "url": "https://…/2.jpg" }
]
```

| Field | Notes |
| --- | --- |
| `type` | `IMAGE` | `VIDEO` |
| `url` | Image URL, or video file URL |
| `posterUrl` | Recommended for `VIDEO` |
| `alt` | Optional |

**Public UI:** full-width active item on top; ordered filmstrip below. Empty/duplicate URLs skipped.

**Legacy fallback** (when `galleryMedia` is empty): `featureImageUrls[0]`, then `thumbnailUrls[]` in order. Video file extensions (`.mp4`, `.webm`, etc.) auto-detected as video.

---

## How the site uses the response

1. Fetch all active services from `/api/v1/services/all`.
2. Render **one nav item + one scroll section** per record, in **`content[]` order**.
3. Section `id` / hash target: `navAnchorId` if set, otherwise string `id`.
4. Title/body from `contents[]` for the current locale.
5. Page hero background: first service with `heroPosterUrl` (or mock fallback if none).
6. Bottom cards: union of all `partnerIds` → About partners API; static fallback if empty.

**Dev fallback:** if the API is empty/unavailable, the site shows mock sections + i18n copy for local development only.

---

## Example payload

```json
{
  "active": true,
  "navAnchorId": "recording-studio",
  "layoutType": "MEDIA_HERO",
  "sortOrder": 2,
  "galleryMedia": [
    { "type": "IMAGE", "url": "<https://cdn.example.com/a.jpg>" },
    { "type": "VIDEO", "url": "<https://cdn.example.com/tour.mp4>", "posterUrl": "<https://cdn.example.com/tour.jpg>" }
  ],
  "partnerIds": [1],
  "contents": [
    { "languageCode": "CKB", "title": "ستۆدیۆ", "description": "…" },
    { "languageCode": "KMR", "title": "Stûdyo", "description": "…" }
  ]
}
```

---

## Validation (backend)

- At least one `contents[].title` per record (both locales preferred).
- `navAnchorId`: slug-like, unique when provided.
- `galleryMedia[].url` required when slot present.
- `active: false` excludes record from public list.

---

## CMS checklist

- [ ]  CRUD + reorder (sort field)
- [ ]  CKB / KMR tabs on `contents[]`
- [ ]  Markdown editor for `description`
- [ ]  Ordered `galleryMedia[]` editor (`IMAGE` / `VIDEO` per slot)
- [ ]  Media upload → URLs in `galleryMedia`
- [ ]  Optional `navAnchorId`, `layoutType`, `partnerIds`, `active`

---

## Code references (khi-website)

| What | Path |
| --- | --- |
| Schema | `src/types/service.ts` |
| Fetch | `src/lib/api/services.ts` |
| Resolve | `src/lib/services/resolve.ts` |
| Page | `src/app/[locale]/services/page.tsx` |