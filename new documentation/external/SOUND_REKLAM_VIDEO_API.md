# Sound Reklam Video API — External (Public)

**Base URL:** `/api/v1/sound-tracks/sound-reklam-video`
**Platform:** Spring Boot 3 · No Auth Required · Not bilingual · Not paginated
**Note:** One read-only endpoint. The Sound section has a single site-wide promo video, served
from S3/CDN.

---

## Endpoint Summary

| Method | Path | Auth Required | Description |
|--------|------|--------------|-------------|
| `GET` | `/api/v1/sound-tracks/sound-reklam-video` | No | Get the Sound section promo video |

---

## What This Is

A **single, site-wide promo video** for the Sound section — one video file the website plays
wherever the Sound promo slot lives.

It is **not** a soundtrack. It has no bilingual content, no topic, no tags, and no featured flag.
It never appears in:

- `GET /api/v1/sound-tracks` (the paginated list)
- any `by-state`, `by-sound-type`, or `by-topic` filter
- any `search` result
- the homepage carousel (`GET /api/v1/featured`)

Fetch it separately from its own endpoint. Nothing else on the site references it.

---

## `GET /api/v1/sound-tracks/sound-reklam-video` — Get the Promo Video

**Auth:** None — public

Takes no parameters. There is only ever one video, so there is no ID, no pagination, no filter,
and no locale — the same video is served to CKB and KMR visitors alike.

**Response `200 OK`:**
```json
{
  "success": true,
  "message": "Sound reklam video fetched successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://cdn.khi.org/sound/reklam-2026.mp4",
    "sizeBytes": 18874368,
    "mimeType": "video/mp4",
    "createdAt": "2026-08-01T09:14:00",
    "updatedAt": "2026-08-14T16:02:00"
  }
}
```

**Response Fields:**

| Field | Type | Notes for the website |
|-------|------|-----------------------|
| `videoUrl` | string | Feed straight to `<video src>`. Always present on a `200` |
| `sizeBytes` | long | File size in bytes. Useful to decide whether to preload or lazy-load |
| `mimeType` | string \| null | Use for `<source type>`; let the browser sniff when `null` |
| `updatedAt` | datetime | Changes when an editor replaces the file — good cache-busting key |
| `id` | long | Not needed for rendering |
| `createdAt` | datetime | Not needed for rendering |

---

## Handling `404` — The Empty State

**Response `404 Not Found`:** No promo video has been uploaded.

```json
{
  "status": 404,
  "code": "NOT_FOUND",
  "message": "ڤیدیۆی رێکلامی ساوند نەدۆزرایەوە.",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "path": "/api/v1/sound-tracks/sound-reklam-video"
}
```

**This is the expected empty state, not a failure.** The site launched without a promo video and
editors can remove it at any time, so `404` is a normal, permanent-until-changed condition.

- Hide the promo slot entirely and render the rest of the Sound page as normal.
- Do **not** show an error toast.
- Do **not** retry.
- Do **not** block page render on this request — load it alongside the rest of the page, not before.

**Match on `status` and `code`, never on message text.** Only `message` — resolved from the
request's `Accept-Language` (`ckb`, `kmr`, or `en`) — carries the specific wording. `messageEn`
and `messageKu` currently fall back to generic strings for **every** endpoint in the API, not just
this one: the English bundle is not on the classpath (its filename has a stray leading space) and
no `messages_ku` bundle exists at all. Treat those two fields as unreliable until it is fixed.

---

## Caching

`videoUrl` changes whenever an editor replaces the file, so the response is safe to cache for the
page's lifetime. If you cache longer than a session, key the cache on `updatedAt`.

---

## Error Responses

| Status | Reason |
|--------|--------|
| `404 Not Found` | No promo video uploaded — expected empty state, see above |
| `500 Internal Server Error` | Unexpected server-side failure |

There are no `400` cases: the endpoint takes no input.
