# Images — Menu Background & Hero Feature Image

The two pictures an editor uploads from the dashboard, end to end: what each one
is, where it appears, the exact API for setting it, and every response the server
can give back.

Everything below was captured from the running code — not written from memory.

| | |
| --- | --- |
| Backend | `/Users/khi/Desktop/khi_backend` — Spring Boot 4, Java 21, PostgreSQL |
| Upload | `POST /api/v1/media/upload` — shared by both |
| Menu background | `imageUrl` on `/api/v1/nav-menu` |
| Hero feature image | `featureImageUrl` on `PATCH /api/v1/{resource}/{id}/featured` |
| Related docs | [`NAV_MENU_BACKEND.md`](NAV_MENU_BACKEND.md) · [`FEATURE_IMAGE_HERO.md`](FEATURE_IMAGE_HERO.md) |

---

## 1 · The two images at a glance

They are different pictures for different holes in the design. Do not reuse one for
the other — the shapes and the safe areas are not the same.

| | **Menu background** | **Hero feature image** |
| --- | --- | --- |
| Where | behind the hamburger menu, when hovering a menu item | the full-screen homepage carousel |
| JSON field | `imageUrl` | `featureImageUrl` |
| Column | `nav_menu_items.image_url` | `feature_image_url` on 6 content tables |
| How many | one per menu item (10 items) | one per featured record |
| Set by | `POST` / `PUT /api/v1/nav-menu` | `PATCH /api/v1/{resource}/{id}/featured` |
| Recommended size | **2000 px+ wide** | **2560 × 1440** (16:9) |
| If missing | menu shows no photo for that item | hero falls back to the item's cover |
| Who can set it | `ADMIN` **or** `SUPER_ADMIN` | `ADMIN` **only** — see §5.4 |
| Omitting the field on save | **clears it** (full replace) | **leaves it unchanged** |

> That last row is the one that bites. The nav-menu `PUT` is a full replace, so a
> body without `imageUrl` wipes the photo. The featured `PATCH` is a partial
> update, so a body without `featureImageUrl` keeps it. Details in §3.3 and §4.3.

---

## 2 · Uploading — the shared endpoint

Both features store **only a URL string**. The file itself goes to S3 through one
shared endpoint, and you send back the URL it returns. There is no image upload
built into either feature.

```http
POST /api/v1/media/upload
Authorization: Bearer <ADMIN or SUPER_ADMIN token>
Content-Type: multipart/form-data
```

| Part | Required | Notes |
| --- | --- | --- |
| `file` | **yes** | the binary. Empty file → `400` |
| `type` | no | folder hint: `image`, `gallery`, `video`, `audio`, `document`/`pdf`. Anything else, or omitted, behaves as `image` |

**`200 OK`** — note it is `200`, not `201`, and the envelope is the standard one:

```json
{
  "success": true,
  "message": "Media uploaded successfully",
  "data": {
    "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/3f2b9c14-6a7e-4d33-9c11-2b8e5a7d0e41-hero-2560.jpg",
    "fileName": "hero-2560.jpg",
    "fileSize": 412873,
    "contentType": "image/jpeg"
  }
}
```

Take **`data.fileUrl`** and send it as `imageUrl` (menu) or `featureImageUrl` (hero).
The URL is absolute and public.

Things worth knowing:

- **The key is randomised** — `khi-web-folders/images/<uuid>-<sanitised-name>.jpg` —
  so uploading two files with the same name never collides.
- **The whole `/api/v1/media/**` path is admin-only**, every method. A `GUEST` or
  `EMPLOYEE` token gets `403` with an empty body.
- **Server limit is 1 GB** (`spring.servlet.multipart.max-file-size`). Over it →
  `413` with `code: "PAYLOAD_TOO_LARGE"`. That ceiling is for video; for these two
  pictures keep to the sizes in §6.
- **Replacing a picture does not delete the old file from S3.** The old object is
  orphaned. If you care, delete it explicitly:
  `DELETE /api/v1/media?fileUrl=<the old url>` (admin-only).
- There is also `POST /api/v1/media/upload/multiple` taking a `files` part — not
  needed for either of these features, which take exactly one picture.

---

## 3 · Menu background image

### 3.1 What it is

Each of the 10 hamburger-menu items (news, projects, sound, video, gallery,
writings, services, about, contact, donate) carries one background photo, shown
full-screen behind the menu while that item is hovered.

It lives on the nav menu item itself — there is no separate image endpoint.

| | |
| --- | --- |
| Field | `imageUrl` |
| Type | `TEXT`, optional (nullable) |
| Column | `nav_menu_items.image_url` |
| Endpoints | `GET/POST/PUT/DELETE /api/v1/nav-menu` |
| Auth (writes) | `ADMIN` or `SUPER_ADMIN` |

### 3.2 Setting it

Send it as part of the item body. Full field reference is in
[`NAV_MENU_BACKEND.md` §3.1](NAV_MENU_BACKEND.md); the image-relevant part:

```json
{
  "itemKey": "news",
  "labelCkb": "هەواڵ",
  "href": "/news",
  "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/abc-news.jpg",
  "displayOrder": 1,
  "active": true
}
```

Response (`201` on create, `200` on update) echoes it back:

```json
{
  "success": true,
  "message": "Nav menu item created",
  "data": {
    "id": 1,
    "itemKey": "news",
    "labelCkb": "هەواڵ",
    "href": "/news",
    "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/abc-news.jpg",
    "displayOrder": 1,
    "active": true,
    "links": []
  }
}
```

### 3.3 ⚠️ Changing and clearing it

`PUT` is a **full replace**. The server rewrites every field from the body, so:

| Body contains | Result |
| --- | --- |
| `"imageUrl": "https://…"` | photo set to that URL |
| `"imageUrl": ""` or `"   "` | photo cleared → `null` |
| `"imageUrl": null` | photo cleared → `null` |
| **field omitted entirely** | **photo cleared → `null`** ← the trap |

So to change the label only, you must still send the current `imageUrl` back.
Load the item with `GET /api/v1/nav-menu/{id}` first, edit, send the whole object.

Also remember **null fields are omitted from responses**, so an item with no photo
comes back with no `imageUrl` key at all — not `"imageUrl": null`:

```json
{
  "success": true,
  "message": "Nav menu item created",
  "data": {
    "id": 4,
    "itemKey": "donate",
    "labelCkb": "بەخشین",
    "href": "/donate",
    "displayOrder": 0,
    "active": true,
    "links": []
  }
}
```

Fill your form defensively — `form.imageUrl = item.imageUrl ?? ''` — or the input
binds `undefined`.

### 3.4 Reading it

| Call | Returns |
| --- | --- |
| `GET /api/v1/nav-menu` | active items only, active links only — what the website uses |
| `GET /api/v1/nav-menu?includeInactive=true` | everything — what the dashboard list uses |
| `GET /api/v1/nav-menu/{id}` | one item, all its links — what the edit form uses |

All three are public; no token needed to read.

---

## 4 · Hero feature image

### 4.1 What it is

The homepage carousel is full-screen (`h-svh`, `object-cover`), so it needs a wide
picture. An item's cover usually is not wide — a book cover is portrait, a book scan
is a spread — and the hero was cropping them badly. `featureImageUrl` is an optional
picture made for that slot.

| | |
| --- | --- |
| Field | `featureImageUrl` |
| Type | `TEXT`, optional (nullable) |
| Column | `feature_image_url` on `news`, `projects`, `writings`, `videos`, `sound_tracks`, `image_collections` |
| Written by | `PATCH /api/v1/{resource}/{id}/featured` |
| Auth | `ADMIN` **only** (§5.4) |

### 4.2 How it reaches the website

The website is untouched — it already paints `image.url` full-screen. The mappers
just put a better URL in that field. First non-blank wins:

| Type | Resolution order for `image.url` |
| --- | --- |
| news | `featureImageUrl` → cover (`coverUrl` / `coverThumbnailUrl`, per `coverMediaType`) |
| project | `featureImageUrl` → cover (same rule) |
| writing, video, sound-track, image-collection | `featureImageUrl` → localized cover → `hoverCoverUrl` |

A blank or whitespace-only value is skipped, not used. If every candidate is blank,
that slide is dropped from the carousel entirely — which is exactly why the field
must stay optional.

One slide from `GET /api/v1/featured`:

```json
{
  "id": "news-42",
  "source": "news",
  "entityId": 42,
  "type": "article",
  "slug": "42",
  "title": "هەواڵی نوێ",
  "description": "کورتەیەک لەسەر هەواڵەکە.",
  "image": {
    "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/hero-2560.jpg",
    "alt": "هەواڵی نوێ"
  },
  "locale": "ckb",
  "featured": true,
  "featuredOrder": 1,
  "displayOrder": 1,
  "active": true
}
```

Nothing was added to the slide shape, so no client breaks. Two read routes exist and
their envelopes differ:

| Route | Envelope |
| --- | --- |
| `GET /featured` | `ApiResponse` — `{ success, message, data: [...] }` — used by the public website |
| `GET /api/v1/featured` | **bare JSON array** |

Both accept `?locale=ckb|kmr|ku` (`ku` is treated as `kmr`) and both are public.

### 4.3 Setting it

| Resource | Path |
| --- | --- |
| news | `PATCH /api/v1/news/{id}/featured` |
| projects | `PATCH /api/v1/projects/{id}/featured` |
| writings | `PATCH /api/v1/writings/{id}/featured` |
| videos | `PATCH /api/v1/videos/{id}/featured` |
| sound-tracks | `PATCH /api/v1/sound-tracks/{id}/featured` |
| image-collections | `PATCH /api/v1/image-collections/{id}/featured` |

Set the picture:

```json
{ "featured": true, "featuredOrder": 1, "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/hero-2560.jpg" }
```

Clear it, back to the cover:

```json
{ "featured": true, "featureImageUrl": "" }
```

Reorder or unfeature **without touching the picture** — just omit the field:

```json
{ "featured": true, "featuredOrder": 3 }
```

Unlike the nav menu, this is a partial update:

| `featureImageUrl` | Effect |
| --- | --- |
| omitted / `null` | stored value untouched — old feature/unfeature calls keep working |
| `""` or whitespace | cleared to `null`; hero falls back to the cover |
| a URL | trimmed and stored |

Every field is optional; `featured` omitted counts as `true`. Unfeaturing nulls
`featuredOrder` but **keeps** `featureImageUrl`, so re-featuring later restores the
same picture.

**Response: `204 No Content` with an empty body**, on all six. There is nothing to
read back — re-fetch, or update optimistically.

### 4.4 Reading it back

Each entity response carries the field, so the dashboard can show what is set
without going through `/featured`:

```jsonc
// GET /api/v1/news/42 — excerpt
{
  "id": 42,
  "coverUrl": "https://…/news-cover.jpg",
  "coverMediaType": "IMAGE",
  "featureImageUrl": "https://…/hero-2560.jpg",
  "…": "…"
}
```

Same field on `ProjectResponse`, `WritingDtos.Response`, `VideoDTO`,
`SoundTrackDtos.Response`, `ImageCollectionDTO.Response`.

> **It is read-only there.** The entity `POST`/`PUT` bodies ignore `featureImageUrl` —
> the create/update mappers never read it. That is deliberate: those are
> full-replace saves, so honouring it would make every content form that does not
> send the field wipe the hero picture on every save. The featured `PATCH` is the
> only writer.

---

## 5 · Errors

### 5.1 The envelope

Failures do **not** use `{ success, message, data }`. They use the error envelope:

```json
{
  "timestamp": "2026-08-15T19:43:04.472539Z",
  "status": 404,
  "path": "/api/v1/nav-menu/9999",
  "method": "GET",
  "traceId": "370012e4-ec65-42ff-915f-3ffa48f21280",
  "code": "NOT_FOUND",
  "message": "Resource not found",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "details": { "id": 9999 }
}
```

### 5.2 What each endpoint can return

| Status | `code` | Where | Cause |
| --- | --- | --- | --- |
| `400` | `VALIDATION_ERROR` | nav-menu write | missing `itemKey` / `labelCkb` / `href`, or a length cap exceeded |
| `400` | `BAD_REQUEST` | featured PATCH | global featured cap reached — `details.reason` explains |
| `400` | `BAD_REQUEST` | upload | empty `file` part |
| `403` | — | all writes | missing token, or role too low. **Empty body** |
| `404` | `NOT_FOUND` | all | no record with that id — *or* the endpoint is not in the deployed build (§7) |
| `409` | `CONFLICT` | nav-menu write | `itemKey` already used by another item |
| `413` | `PAYLOAD_TOO_LARGE` | upload | file over the 1 GB server limit |

### 5.3 Two client-side gotchas

- **`403` returns an empty body**, so `e?.response?.data?.message` is `undefined`.
  The toast must fall back to its own text.
- **Send `Accept-Language: ckb`** (or `kmr`) to get the Kurdish `message`. Without
  it you get the generic English fallback, because the request defaults to the
  English bundle — whose file is currently misnamed `" messages_en.properties"`
  (leading space) and never loads. `messageKu` is always populated regardless.

### 5.4 ⚠️ Who can actually set each image

They are **not** the same, and this is easy to trip over:

| Action | ADMIN | SUPER_ADMIN |
| --- | --- | --- |
| `POST /api/v1/media/upload` | ✅ | ✅ |
| nav-menu `POST` / `PUT` / `DELETE` | ✅ | ✅ |
| featured `PATCH` (hero image) | ✅ | **❌ `403`** |

The featured PATCH endpoints are annotated `@PreAuthorize("hasRole('ADMIN')")`,
there is no `RoleHierarchy` bean, and `Role.getAuthorities()` grants exactly one
`ROLE_<name>` — so a SUPER_ADMIN account is rejected. Pre-existing across all six
endpoints, not introduced by this work. **Test hero images with an ADMIN token.**

---

## 6 · For editors — what picture to give

### Menu background

| | |
| --- | --- |
| Size | **2000 px+ wide** |
| Format | JPEG, quality ~80 |
| Why | it fills the whole screen behind the menu; anything smaller looks soft on a large monitor |

### Hero feature image

| | |
| --- | --- |
| Size | **2560 × 1440** (16:9). Minimum 1920 × 1080 |
| Format | JPEG, quality ~80 |
| File size | keep under ~500 KB — the website serves the original file as-is |

**Where to keep the subject.** The slide's title and description sit at the
**bottom**, on the **right** in Kurdish, and dark gradients cover the bottom ~40%
and the right ~40% so the text stays readable. On phones the same picture is cropped
to a tall portrait.

So: put the subject **upper-middle, toward the left**, away from the outer edges —
those get cut off on narrow screens.

---

## 7 · Status

| Piece | State |
| --- | --- |
| Menu background — entities, endpoints, security, i18n | ✅ built, 8 integration tests |
| Hero feature image — 6 columns, 6 mappers, PATCH, 6 response DTOs | ✅ built, 7 tests |
| Unmapped-URL handling (`404` instead of `500`) | ✅ fixed, 3 tests |
| Full suite | ✅ 80 tests green, `BUILD SUCCESS` |
| Dashboard UI for both | ⬜ not started — `khi-dashboard` |
| Deployed to Railway | ⬜ pending |

Until the backend redeploys, `/api/v1/nav-menu` answers `500` on production simply
because that build has no such route. The hero field is safe to deploy any time:
every existing row is `NULL`, so the fallback runs and `/featured` returns
byte-identical JSON until the first picture is uploaded.

---

## 8 · Copy-paste recipes

```bash
BASE=https://blissful-spontaneity-production.up.railway.app
TOKEN=<admin jwt>

# 1 · upload once, reuse the URL for either feature
curl -X POST "$BASE/api/v1/media/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@hero-2560.jpg" -F "type=image"
# -> data.fileUrl

# 2a · menu background — send the WHOLE item back, or other fields reset
curl -X PUT "$BASE/api/v1/nav-menu/1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept-Language: ckb" \
  -d '{"itemKey":"news","labelCkb":"هەواڵ","href":"/news",
       "imageUrl":"https://…/abc-news.jpg","displayOrder":1,"active":true}'

# 2b · hero image — partial, only what you name changes
curl -X PATCH "$BASE/api/v1/news/42/featured" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":1,"featureImageUrl":"https://…/hero-2560.jpg"}'

# 3 · clear either one
curl -X PATCH "$BASE/api/v1/news/42/featured" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"featured":true,"featureImageUrl":""}'

# 4 · check the result
curl "$BASE/featured?locale=ckb"                    # hero slides
curl "$BASE/api/v1/nav-menu?includeInactive=true"   # menu items
```
