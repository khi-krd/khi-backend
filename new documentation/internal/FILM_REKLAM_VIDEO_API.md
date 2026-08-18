# Film Reklam Video API — Internal (Admin)

**Base URL:** `/api/v1/videos/film-reklam-video`
**Platform:** Spring Boot 3 · JWT · Multipart · Not bilingual · Not paginated
**Note:** The background video for the homepage **Film** section. The exact counterpart of
[`SOUND_REKLAM_VIDEO_API.md`](./SOUND_REKLAM_VIDEO_API.md) — same shape, same rules, same flow, so
the dashboard can reuse one component for both. It is **not** a `Video`: no bilingual content, no
topic, no tags, no featured flag. One file plus metadata, in the `film_reklam_videos` table.

---

## Endpoint Summary

| Method | Path | Auth Required | Role | Description |
|--------|------|--------------|------|-------------|
| `POST` | `/api/v1/videos/film-reklam-video` | Yes | `EMPLOYEE` / `ADMIN` / `SUPER_ADMIN` | Upload the background video — first time only |
| `GET` | `/api/v1/videos/film-reklam-video` | No | Public | Read the background video |
| `PATCH` | `/api/v1/videos/film-reklam-video` | Yes | `EMPLOYEE` / `ADMIN` / `SUPER_ADMIN` | Replace the video file |
| `DELETE` | `/api/v1/videos/film-reklam-video` | Yes | `ADMIN` / `SUPER_ADMIN` | Remove the background video |

Every route is collection-level — there is no `{id}` anywhere.

---

## The Singleton Rule

**At most one row site-wide.** This shapes the whole screen:

- The **first** upload is a `POST`. Every upload after that is a `PATCH`.
- A second `POST` while a video exists fails with `400`. It does not create a second row and does
  not overwrite the first.
- When nothing has been uploaded, `GET` returns `404` — not an empty body.

**Dashboard flow**

1. `GET` when the Film settings screen opens.
2. On `200` — show the current video with **Replace** (`PATCH`) and **Remove** (`DELETE`).
3. On `404` — show an empty state with a single **Upload** button (`POST`).
4. Never show **Upload** and **Replace** at the same time.

If `POST` returns `video.reklamVideo.already_exists`, another editor uploaded one while the screen
was open. Re-fetch and switch to the replace flow rather than showing a raw error.

---

## What to Tell the Editor

Helper text for the upload screen:

- **MP4.** It plays **muted and looping** behind the film cards — no audio will ever be heard.
- **Keep it small.** The sound video is about 7 MB; treat that as the ceiling. The API does not
  enforce a size limit, so the dashboard should.
- **Keep the middle and bottom clear.** Cards and text sit over them.
- **Empty is fine.** With no video the section renders on a plain dark ground.

---

## Response Object

`VideoDTO.FilmReklamVideoResponse` — returned by `POST`, `GET`, and `PATCH`, wrapped in the
standard `ApiResponse` envelope.

| Field | Type | Description |
|-------|------|-------------|
| `id` | long | Row ID. Not needed by any endpoint |
| `videoUrl` | string | S3/CDN URL of the video file |
| `sizeBytes` | long | File size in bytes, as uploaded |
| `mimeType` | string \| null | Content type reported by the browser, e.g. `video/mp4` |
| `createdAt` | datetime | When the video was **first** uploaded |
| `updatedAt` | datetime | When the file was **last** replaced |

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

---

## `POST /api/v1/videos/film-reklam-video` — Upload the Background Video

**Auth:** JWT required · Role: `EMPLOYEE`, `ADMIN`, or `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Form Parts:**

| Part | Type | Required | Description |
|------|------|----------|-------------|
| `videoFile` | file | **Yes** | The video. Its `Content-Type` must start with `video/` |

No `data` JSON part — `videoFile` is the entire request. `sizeBytes` and `mimeType` are read off
the uploaded file.

**Response `201 Created`:** `FilmReklamVideoResponse`.

**Errors:**

| Status | Key | Cause |
|--------|-----|-------|
| `400` | `video.reklamVideo.already_exists` | A video is already stored — use `PATCH` |
| `400` | `error.validation` | `videoFile` missing or empty. `details.field` = `videoFile` |
| `400` | `error.validation` | Content type is not `video/*` |
| `400` | `media.upload.failed` | S3 rejected the file |

---

## `GET /api/v1/videos/film-reklam-video` — Read the Background Video

**Auth:** None — public

Takes no parameters.

**Response `200 OK`:** `FilmReklamVideoResponse`.

**Response `404 Not Found`:** Nothing uploaded — key `video.reklamVideo.not_found`. The normal
empty state, not a failure.

---

## `PATCH /api/v1/videos/film-reklam-video` — Replace the Video File

**Auth:** JWT required · Role: `EMPLOYEE`, `ADMIN`, or `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Form Parts:** identical to `POST` — a single required `videoFile` part.

Despite the `PATCH` verb this is a **full replacement**: there is no other field to patch. The row
keeps its `id` and `createdAt`; `videoUrl`, `sizeBytes`, `mimeType`, and `updatedAt` are rewritten.

**Behavior:** the new file goes to S3 first, then the row is saved, then the previous S3 object is
deleted. A failed upload leaves the old video untouched, so a failed replace never leaves the Film
section without a background.

**Response `200 OK`:** Updated `FilmReklamVideoResponse`.

**Fails with `404`** when no video exists yet — replace cannot create one. Fall back to `POST`.

---

## `DELETE /api/v1/videos/film-reklam-video` — Remove the Background Video

**Auth:** JWT required · Role: `ADMIN` or `SUPER_ADMIN`

Takes no parameters. Deletes the row, then removes the file from S3.

**Response `204 No Content`:** Empty body.

**Not idempotent** — deleting twice returns `404` the second time. Guard the button on the current
`GET` state, or treat a `404` on delete as "already gone" and refresh.

---

## Validation Rules

Enforced by `VideoService.validatePromoVideoFile`, shared by `POST` and `PATCH`:

| Rule | Failure |
|------|---------|
| `videoFile` present and non-empty | `400` · `error.validation` · `details.field` = `videoFile` |
| `Content-Type` starts with `video/` | `400` · `error.validation` · `details.field` = `videoFile` |

There is **no** size, duration, resolution, or codec check beyond the `video/*` prefix. The only
effective limit is the Spring multipart limit — enforce the ~7 MB ceiling client-side.

---

## Error Responses

| Status | Reason |
|--------|--------|
| `400 Bad Request` | Missing or non-video `videoFile`; video already exists; S3 upload failed |
| `401 Unauthorized` | JWT token is missing or invalid |
| `403 Forbidden` | Authenticated user lacks the required role |
| `404 Not Found` | No background video uploaded yet |
| `500 Internal Server Error` | Unexpected server-side failure |

**Match on the key, not the message text.** Keys for this resource:

| Key | Status | Meaning |
|-----|--------|---------|
| `video.reklamVideo.already_exists` | `400` | A video is already stored — use `PATCH` |
| `video.reklamVideo.not_found` | `404` | No video stored — use `POST` |
| `error.validation` | `400` | `videoFile` missing or not `video/*` |
| `media.upload.failed` | `400` | S3 storage failure |

> **Only `message` is trustworthy today.** It resolves from the request's `Accept-Language`
> (`ckb`, `kmr`, `en`). `messageEn` and `messageKu` fall back to generic per-code strings for
> **every** endpoint in the API: the English bundle is not on the classpath — its filename,
> `src/main/resources/i18n/ messages_en.properties`, has a stray leading space — and
> `GlobalExceptionHandler` resolves `messageKu` against locale `ku`, for which no bundle exists
> (the Sorani file is `messages_ckb`). Send `Accept-Language` and read `message`.

---

## Differences from the Sound Reklam Video

The two are deliberately near-identical. The only differences:

| | Sound | Film |
|---|---|---|
| Path | `/api/v1/sound-tracks/sound-reklam-video` | `/api/v1/videos/film-reklam-video` |
| Message keys | `sound.reklamVideo.*` | `video.reklamVideo.*` |
| S3 failure status | `502` · `sound.media_upload_failed` | `400` · `media.upload.failed` |
| `PATCH` auth | ⚠️ any signed-in user — see the sound doc | `EMPLOYEE` / `ADMIN` / `SUPER_ADMIN`, enforced |

The `PATCH` difference is not an oversight: the sound route falls through to
`anyRequest().authenticated()` because `SecurityConfig` has no `PATCH` matcher for
`/api/v1/sound-tracks/**`. The film route was given both a `PATCH` matcher and a `@PreAuthorize`
so the gap was not copied forward. Fixing sound to match is a one-line change.

---

## Source Reference

| Concern | Location |
|---------|----------|
| Endpoints | `VideoController` — the `FILM REKLAM VIDEO` section |
| Business logic | `VideoService` — `createFilmReklamVideo`, `getFilmReklamVideo`, `updateFilmReklamVideo`, `deleteFilmReklamVideo` |
| Validation | `VideoService.validatePromoVideoFile` |
| Entity / table | `FilmReklamVideo` → `film_reklam_videos` |
| Repository | `FilmReklamVideoRepository.findTopByOrderByIdAsc()` — enforces the singleton |
| Response DTO | `VideoDTO.FilmReklamVideoResponse` |
| Auth matchers | `user/configs/SecurityConfig` — POST / PATCH / DELETE on `/api/v1/videos/**` |
| Tests | `VideoServiceFilmReklamVideoTests` |
