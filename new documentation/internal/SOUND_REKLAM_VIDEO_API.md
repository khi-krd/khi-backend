# Sound Reklam Video API — Internal (Admin)

**Base URL:** `/api/v1/sound-tracks/sound-reklam-video`
**Platform:** Spring Boot 3 · JWT · Multipart · Not bilingual · Not paginated
**Note:** A single, site-wide promo video for the Sound section. It is **not** a soundtrack: no
bilingual content, no topic, no tags, no featured flag. One video file plus its metadata, stored
in the `sound_reklam_videos` table. Lives on the SoundTrack controller but is an independent
resource — it never appears in the soundtrack list, filters, search, or the homepage carousel.

---

## Endpoint Summary

| Method | Path | Auth Required | Role | Description |
|--------|------|--------------|------|-------------|
| `POST` | `/api/v1/sound-tracks/sound-reklam-video` | Yes | `EMPLOYEE` / `ADMIN` / `SUPER_ADMIN` | Upload the promo video — first time only |
| `GET` | `/api/v1/sound-tracks/sound-reklam-video` | No | Public | Read the promo video |
| `PATCH` | `/api/v1/sound-tracks/sound-reklam-video` | Yes | Any signed-in user ⚠️ | Replace the promo video file |
| `DELETE` | `/api/v1/sound-tracks/sound-reklam-video` | Yes | `ADMIN` / `SUPER_ADMIN` | Remove the promo video |

Every route is collection-level. There is no `{id}` path anywhere — see below.

---

## The Singleton Rule

**The backend keeps at most one row.** This is the single most important thing about this
resource, and it shapes the whole dashboard screen:

- The **first** upload is a `POST`. Every upload after that is a `PATCH`.
- A second `POST` while a video already exists fails with `400`. It does **not** create a second
  row and does **not** overwrite the first.
- When no video has been uploaded yet, `GET` returns `404` — not an empty body, not `null`.
- The row's `id` is returned for completeness but is never needed: no endpoint takes it.

**Recommended dashboard flow**

1. Call `GET` when the Sound settings screen opens.
2. On `200` — render the current video with **Replace** (`PATCH`) and **Remove** (`DELETE`).
3. On `404` — render an empty state with a single **Upload** button (`POST`).
4. Never show **Upload** and **Replace** at the same time.

If a `POST` comes back with `sound.reklamVideo.already_exists`, another editor uploaded one while
the screen was open. Re-fetch with `GET` and switch the screen to the replace flow rather than
showing a raw error.

---

## Response Object

`SoundReklamVideoResponse` — returned by `POST`, `GET`, and `PATCH`, wrapped in the standard
`ApiResponse` envelope.

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

---

## `POST /api/v1/sound-tracks/sound-reklam-video` — Upload the Promo Video

**Auth:** JWT required · Role: `EMPLOYEE`, `ADMIN`, or `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Form Parts:**

| Part | Type | Required | Description |
|------|------|----------|-------------|
| `videoFile` | file | **Yes** | The video. Its `Content-Type` must start with `video/` |

There is no `data` JSON part — `videoFile` is the entire request. `sizeBytes` and `mimeType` are
read off the uploaded file; the dashboard does not send them.

**Response `201 Created`:** `SoundReklamVideoResponse` (shape above).

**Errors:**

| Status | Key | Cause |
|--------|-----|-------|
| `400` | `sound.reklamVideo.already_exists` | A video is already stored — use `PATCH` |
| `400` | `error.validation` | `videoFile` missing or empty. `details.field` = `videoFile` |
| `400` | `error.validation` | Content type is not `video/*` |
| `502` | `sound.media_upload_failed` | S3 rejected the file. `details.reason` carries the cause |

---

## `GET /api/v1/sound-tracks/sound-reklam-video` — Read the Promo Video

**Auth:** None — public

Takes no parameters. There is only ever one video, so there is no ID, no pagination, no filter,
and no locale.

**Response `200 OK`:** `SoundReklamVideoResponse` (shape above).

**Response `404 Not Found`:** No video has been uploaded — key `sound.reklamVideo.not_found`.
This is the normal empty state, **not** a failure. Render the upload prompt; do not show an error
toast.

---

## `PATCH /api/v1/sound-tracks/sound-reklam-video` — Replace the Video File

**Auth:** JWT required · ⚠️ see the security note below
**Content-Type:** `multipart/form-data`

**Form Parts:** identical to `POST` — a single required `videoFile` part.

Despite the `PATCH` verb this is a **full replacement**, not a partial update: there is no other
field to patch. The row keeps its `id` and `createdAt`; `videoUrl`, `sizeBytes`, `mimeType`, and
`updatedAt` are all rewritten.

**Behavior:** the new file is uploaded to S3 first, then the row is saved, then the previous S3
object is deleted. If the upload fails the old video is left untouched — a failed replace never
leaves the site with no promo video.

**Response `200 OK`:** Updated `SoundReklamVideoResponse`.

**Errors:**

| Status | Key | Cause |
|--------|-----|-------|
| `404` | `sound.reklamVideo.not_found` | No video exists yet — replace cannot create one. Fall back to `POST` |
| `400` | `error.validation` | `videoFile` missing, or content type is not `video/*` |
| `502` | `sound.media_upload_failed` | S3 rejected the file |

> **⚠️ Security note.** This route is currently reachable by **any signed-in account**, including
> a plain `USER`. `SecurityConfig` gates `/api/v1/sound-tracks/**` for `POST`, `PUT`, and
> `DELETE`, but has no `PATCH` matcher for it, and the handler carries no `@PreAuthorize` — so it
> falls through to `anyRequest().authenticated()`. Create and delete are correctly restricted;
> only replace is open. The dashboard should still hide the control from non-editors, but that is
> UI-side only and is **not** enforced by the API today.

---

## `DELETE /api/v1/sound-tracks/sound-reklam-video` — Remove the Promo Video

**Auth:** JWT required · Role: `ADMIN` or `SUPER_ADMIN`

Takes no parameters. Deletes the row, then removes the file from S3.

**Response `204 No Content`:** Empty body.

**Errors:**

| Status | Key | Cause |
|--------|-----|-------|
| `404` | `sound.reklamVideo.not_found` | No video exists |

**Not idempotent.** Unlike `DELETE /api/v1/sound-tracks/{id}`, which succeeds even when the ID is
already gone, deleting the promo video twice returns `404` the second time. Guard the button on
the current `GET` state, or treat a `404` on delete as "already gone" and refresh.

After a successful delete the Sound section renders with no promo video until a new one is
`POST`ed.

---

## Validation Rules

Enforced by `SoundTrackService.validatePromoVideoFile`, shared by `POST` and `PATCH`:

| Rule | Failure |
|------|---------|
| `videoFile` must be present and non-empty | `400` · `error.validation` · `details.field` = `videoFile` |
| `Content-Type` must start with `video/` | `400` · `error.validation` · `details.field` = `videoFile` |

There is **no** size cap, duration cap, resolution check, or format allow-list beyond the
`video/*` prefix. The only effective size limit is the Spring multipart limit. If the dashboard
needs a ceiling, enforce it client-side before uploading.

---

## Error Responses

| Status | Reason |
|--------|--------|
| `400 Bad Request` | Missing or non-video `videoFile`; promo video already exists |
| `401 Unauthorized` | JWT token is missing or invalid |
| `403 Forbidden` | Authenticated user lacks the required role |
| `404 Not Found` | No promo video uploaded yet |
| `502 Bad Gateway` | S3 upload or delete failed |
| `500 Internal Server Error` | Unexpected server-side failure |

Error bodies carry `status`, `path`, `method`, `traceId`, `code`, a localised `message`, and
`messageEn` / `messageKu`.

**Match on the key, not the message text** — the text is translated. Keys for this resource:

| Key | Status | Meaning |
|-----|--------|---------|
| `sound.reklamVideo.already_exists` | `400` | A video is already stored — use `PATCH` |
| `sound.reklamVideo.not_found` | `404` | No video stored — use `POST` |
| `error.validation` | `400` | `videoFile` missing or not `video/*` |
| `sound.media_upload_failed` | `502` | S3 storage failure; see `details.reason` |

> **Only `message` is trustworthy today.** It is resolved from the request's `Accept-Language`
> (`ckb`, `kmr`, `en`). `messageEn` and `messageKu` fall back to generic per-code strings
> ("Resource not found", "سەرچاوە نەدۆزرایەوە") for **every** endpoint in the API, not just this
> one: the English bundle is not on the classpath — its filename,
> `src/main/resources/i18n/ messages_en.properties`, has a stray leading space — and
> `GlobalExceptionHandler` resolves `messageKu` against locale `ku`, for which no bundle exists
> (the Sorani file is `messages_ckb`). Send `Accept-Language` and read `message`.

---

## Source Reference

| Concern | Location |
|---------|----------|
| Endpoints | `SoundTrackController:202–242` |
| Business logic | `SoundTrackService` — `createSoundReklamVideo`, `getSoundReklamVideo`, `updateSoundReklamVideo`, `deleteSoundReklamVideo` |
| Validation | `SoundTrackService.validatePromoVideoFile` |
| Entity / table | `SoundReklamVideo` → `sound_reklam_videos` |
| Repository | `SoundReklamVideoRepository.findTopByOrderByIdAsc()` — enforces the singleton |
| Response DTO | `SoundTrackDtos.SoundReklamVideoResponse` |
| Auth matchers | `user/configs/SecurityConfig:150–167` |
| Message keys | `src/main/resources/i18n/messages_ckb.properties`, `messages_kmr.properties` |
