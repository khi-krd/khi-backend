# Film Reklam Video API — External (Public)

**Base URL:** `/api/v1/videos/film-reklam-video`
**Platform:** Spring Boot 3 · No Auth Required · Not bilingual · Not paginated
**Note:** One read-only endpoint. The homepage **Film** section has a single site-wide background
video, served from S3/CDN. The exact counterpart of
[`SOUND_REKLAM_VIDEO_API.md`](./SOUND_REKLAM_VIDEO_API.md).

---

## Endpoint Summary

| Method | Path | Auth Required | Description |
|--------|------|--------------|-------------|
| `GET` | `/api/v1/videos/film-reklam-video` | No | Get the Film section background video |

---

## What This Is

A **single, site-wide background video** for the homepage Film section — the same treatment the
Sound section already has. It plays **muted and looping** behind the film cards.

It is **not** a `Video` publishment. It has no bilingual content, no topic, no tags, no featured
flag, and it never appears in:

- `GET /api/v1/videos` (the paginated list)
- any topic, tag, or keyword search
- the homepage carousel (`GET /api/v1/featured`)

Fetch it separately from its own endpoint.

---

## `GET /api/v1/videos/film-reklam-video` — Get the Background Video

**Auth:** None — public

Takes no parameters. There is only ever one video, so there is no ID, no pagination, no filter, and
no locale — the same video is served to CKB and KMR visitors alike.

**Response `200 OK`:**
```json
{
  "success": true,
  "message": "Film reklam video fetched successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/video/film-bg.mp4",
    "sizeBytes": 7199031,
    "mimeType": "video/mp4",
    "createdAt": "2026-08-18T10:00:00",
    "updatedAt": "2026-08-18T10:00:00"
  }
}
```

**Response Fields:**

| Field | Type | Notes for the website |
|-------|------|-----------------------|
| `videoUrl` | string | Feed straight to `<video src>`. Always present on a `200` |
| `sizeBytes` | long | File size. Useful to decide whether to preload or lazy-load |
| `mimeType` | string \| null | Use for `<source type>`; let the browser sniff when `null` |
| `updatedAt` | datetime | Changes when an editor replaces the file — good cache-busting key |
| `id`, `createdAt` | — | Not needed for rendering |

Render it exactly as the Sound section background: `muted`, `loop`, `playsinline`, `autoplay`.

---

## Handling `404` — The Empty State

**Response `404 Not Found`:** No background video has been uploaded.

```json
{
  "status": 404,
  "code": "NOT_FOUND",
  "message": "ڤیدیۆی رێکلامی فیلم نەدۆزرایەوە.",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "path": "/api/v1/videos/film-reklam-video"
}
```

**This is the expected empty state, not a failure.** The Film section shipped with no background at
all, and editors can remove it at any time.

- Hide the background and render the Film section on its plain dark ground.
- Do **not** show an error toast.
- Do **not** retry.
- Do **not** block page render on this request.

**Match on `status` and `code`, never on message text.** Only `message` — resolved from the
request's `Accept-Language` (`ckb`, `kmr`, or `en`) — carries the specific wording. `messageEn` and
`messageKu` currently fall back to generic strings for **every** endpoint in the API: the English
bundle is not on the classpath (its filename has a stray leading space) and no `messages_ku` bundle
exists. Treat those two fields as unreliable until it is fixed.

---

## Caching

`videoUrl` changes whenever an editor replaces the file, so the response is safe to cache for the
page's lifetime. If you cache longer than a session, key the cache on `updatedAt`.

---

## Error Responses

| Status | Reason |
|--------|--------|
| `404 Not Found` | No background video uploaded — expected empty state, see above |
| `500 Internal Server Error` | Unexpected server-side failure |

There are no `400` cases: the endpoint takes no input.
