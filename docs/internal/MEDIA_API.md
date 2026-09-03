# Media API — Internal (Authenticated)

The media endpoints are the shared upload pipeline behind every rich-text editor and asset picker in
the KHI admin dashboard. A file goes up as `multipart/form-data`, lands in the project S3 bucket, and
comes back as a permanent public URL that the dashboard then bakes into a Tiptap document, a cover
field, or a gallery item before saving the owning entity. News, Projects, About, Contact, Services,
Videos, Sound Tracks, Image Collections and Writings all use this one prefix instead of each
maintaining their own upload route.

| | |
|---|---|
| **Base path** | `/api/v1/media` |
| **Audience** | Admin dashboard (JWT required) — roles `ADMIN`, `SUPER_ADMIN` |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/media/MediaController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/media/MediaService.java` |
| **Supporting services** | `src/main/java/ak/dev/khi_backend/khi_app/service/S3Service.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/media/TiptapHtmlProcessor.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/MediaMetadataExtractor.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/media/MediaDtos.java` (`UploadResponse`, `UploadEnvelope`, `BulkUploadResponse`) |
| **Config** | `src/main/java/ak/dev/khi_backend/khi_app/config/S3Config.java`, `src/main/java/ak/dev/khi_backend/khi_app/config/MultipartJsonConfig.java` |
| **Entities** | `MediaItem` (JSONB gallery element on `News` and `Project`) — the upload endpoints themselves persist nothing to PostgreSQL |
| **Enums** | `MediaKind`, `ProjectMediaType` |
| **Verified against source** | 2026-08-26 |

## This prefix is never callable from the public site

`SecurityConfig` places a single rule ahead of every public-read rule:

```java
.requestMatchers("/api/v1/media/**")
.hasAnyRole("ADMIN", "SUPER_ADMIN")
```

It matches **all HTTP methods**, and it is registered *before* `GET /api/v1/**` → `permitAll()`, so
the usual "public reads, admin writes" split does not apply here — there is no anonymous read, no
anonymous `HEAD`, and no future media read endpoint can leak by accident. `EMPLOYEE` is not enough:
an employee who can create news through `POST /api/v1/news` cannot upload the images that go into
it. None of the three handlers carries a `@PreAuthorize`, so `SecurityConfig` is the only gate and
there is nothing narrowing it further. There is no external counterpart to this file.

**Authentication.** Send the JWT either as `Authorization: Bearer <token>` or as the HttpOnly cookie
whose name comes from `${JWT_COOKIE_NAME}` (the dashboard gets it automatically from
`POST /api/auth/login`). Sessions are stateless; the token is validated on every request and checked
against the revocation list.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/media/upload` | JWT | `ADMIN`, `SUPER_ADMIN` | Upload one file to S3, return its public URL |
| 2 | `POST` | `/api/v1/media/upload/multiple` | JWT | `ADMIN`, `SUPER_ADMIN` | Upload several files in one request |
| 3 | `DELETE` | `/api/v1/media` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete one previously uploaded object from S3 by its URL |

---

## 1. `POST /api/v1/media/upload` — Upload one file

Streams the uploaded part straight to S3 (the bytes are never fully buffered in the JVM heap) and
returns the resulting public URL together with the metadata the servlet container already knew. The
endpoint writes nothing to PostgreSQL, evicts no cache, and creates no `MediaItem` row — persisting
the URL is the caller's job, done later when the owning entity is saved.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Path parameters**

None.

**Query parameters**

None.

**Multipart parts**

| Part | Content type | Required | Description |
|------|--------------|----------|-------------|
| `file` | the file's own MIME type (`image/jpeg`, `audio/mpeg`, `video/mp4`, `application/pdf`, …) | Yes | The binary payload. Any MIME type is accepted — there is no allow-list, no extension check and no magic-byte validation. Must not be empty. |
| `type` | `text/plain` (curl sends it with no content type, which also works) | No | Folder hint. Accepted values are listed below. When the part is absent or blank the controller substitutes `"image"`. |

There is no JSON part on this endpoint. (`MultipartJsonConfig` documents that Spring Boot parses
`application/json` parts automatically — that matters for the content controllers such as
`POST /api/v1/sound-tracks`, which take a `data` JSON part next to their file parts, not here.)

**`type` values and where the file lands**

| `type` sent | `ProjectMediaType` resolved | S3 folder |
|-------------|-----------------------------|-----------|
| `image` (default), `gallery` | `IMAGE` | `khi-web-folders/images/` |
| `video` | `VIDEO` | `khi-web-folders/video/` |
| `audio` | `AUDIO` | `khi-web-folders/audio/` |
| `document`, `pdf` | `DOCUMENT` | `khi-web-folders/files/` |
| anything else (`other`, `file`, a typo…) | none — falls back to MIME sniffing | `images/` for `image/*`, `video/` for `video/*`, `audio/` for `audio/*`, `files/` for everything else |

Matching is case-insensitive and trimmed, so `Audio` and ` audio ` both resolve to `AUDIO`.

> **Note:** `MediaService`'s javadoc says the folder is inferred from the content type when `type` is
> null, but the controller never passes null — it defaults the hint to `"image"` first. The practical
> effect is that an MP3 uploaded **without** a `type` part is stored under `khi-web-folders/images/`.
> The object's `Content-Type` metadata is still the real MIME type and the URL still works, so this
> is cosmetic rather than breaking, but it makes bucket-prefix housekeeping unreliable. Always send
> `type` explicitly. Paradoxically, MIME sniffing only happens when you send an *unrecognised* hint.

**Response `200 OK`**

Envelope: `ApiResponse<UploadResponse>`.

```json
{
  "success": true,
  "message": "Media uploaded successfully",
  "data": {
    "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/7d3f5b12-9c44-4a81-b0e7-2f6a1c8d9e10-hewler-festival.jpg",
    "fileName": "hewler-festival.jpg",
    "fileSize": 482913,
    "contentType": "image/jpeg"
  }
}
```

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `fileUrl` | string | No | Permanent public URL of the stored object. This is the value you insert into editor HTML or persist on the entity. |
| `fileName` | string | Yes | The **original** filename exactly as the browser sent it, including non-ASCII characters. It is not the name used in the S3 key — see the key layout section. `null` when the client sent no filename. |
| `fileSize` | long | No | Size in bytes, as reported by the servlet container. |
| `contentType` | string | Yes | The MIME type the browser declared. `null` if the client sent none; in that case S3 stores the object as `application/octet-stream`. |

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | No token, or a valid token whose role is neither `ADMIN` nor `SUPER_ADMIN`. Rejected by Spring Security before the controller, so the body is **not** an `ApiErrorResponse`. |
| `401` | — | Token present but expired or revoked. The JWT filter writes its own body: `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}` (or `TOKEN_REVOKED`). An invalid/unparseable token yields `403` with `{"error":"INVALID_TOKEN", ...}`. |
| `400` | `BAD_REQUEST` | The `file` part is present but empty (`file.isEmpty()`), `details.reason` = `"File is required"`. |
| `400` | `BAD_REQUEST` | S3 rejected the `PutObject` call, or the temporary upload could not be read back. See the note about the message below. |
| `400` | `BAD_REQUEST` | The request is not valid `multipart/form-data` (`MultipartException`). `details.hint` explains the expected part names. |
| `413` | `PAYLOAD_TOO_LARGE` | The file exceeds the container limit of 1 GB. |
| `500` | `INTERNAL_ERROR` | The `file` part is missing entirely. See the note below. |

> **Note:** A missing required part raises `MissingServletRequestPartException`, which no handler in
> `GlobalExceptionHandler` covers, so the catch-all `@ExceptionHandler(Exception.class)` turns it
> into `500 INTERNAL_ERROR` instead of a `400`. Do not treat a 500 from this endpoint as
> necessarily a server fault — check that your form actually contains a part named `file`
> (or `files` on endpoint 2).

> **Note:** The `413` message is wrong about the limit. `GlobalExceptionHandler` hard-codes
> `MAX_UPLOAD_MB = 5`, so the body reads "The uploaded file exceeds the maximum allowed size of
> 5 MB" and `details.maxAllowedMB` is `5`, while the configured limit is 1 GB. `details.hint` also
> claims only JPEG/PNG/GIF/WebP are accepted, which is not true of this endpoint. Show your own
> copy to the operator rather than the server's.

> **Note:** S3 failures surface as `400 BAD_REQUEST`, not `500` and not the `STORAGE_ERROR` code that
> exists in the `ErrorCode` enum. `S3Service` throws `BadRequestException("s3.upload.failed", "…")`,
> and because `s3.upload.failed` is not a key in any `i18n/messages_*.properties` bundle the detailed
> reason is dropped: the client sees the generic `"Bad request"` / `"داواکاری هەڵەیە"`. The real
> cause (credentials, bucket policy, network) is only in the server log, keyed by `traceId`.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/media/upload \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F "file=@hewler-festival.jpg;type=image/jpeg" \
  -F "type=image"
```

Uploading an audio file for a sound-track page:

```bash
curl -s -X POST http://localhost:8080/api/v1/media/upload \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F "file=@hawara-kurdistane.mp3;type=audio/mpeg" \
  -F "type=audio"
```

```json
{
  "success": true,
  "message": "Media uploaded successfully",
  "data": {
    "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/audio/e07f9d2a-5c31-4b08-83f6-1a4c7e9b2d50-hawara-kurdistane.mp3",
    "fileName": "hawara-kurdistane.mp3",
    "fileSize": 8412677,
    "contentType": "audio/mpeg"
  }
}
```

Using the cookie instead of the header:

```bash
curl -s -X POST http://localhost:8080/api/v1/media/upload \
  --cookie "$JWT_COOKIE_NAME=$KHI_TOKEN" \
  -F "file=@brochure.pdf;type=application/pdf" \
  -F "type=document"
```

---

## 2. `POST /api/v1/media/upload/multiple` — Upload several files

Loops over the parts and performs the same single upload for each, in the order the parts arrive.
One `type` hint applies to the whole batch. Useful for the editor's "insert multiple images" flow and
for drag-and-dropping a folder into a gallery picker.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Multipart parts**

| Part | Content type | Required | Description |
|------|--------------|----------|-------------|
| `files` | each file's own MIME type | Yes | Repeat the part name once per file (`-F "files=@a.jpg" -F "files=@b.jpg"`). The combined request must stay under 1 GB. |
| `type` | `text/plain` | No | Folder hint applied to **every** file in the batch. Defaults to `"image"`. Mixed-media batches therefore all land in the same folder — upload audio and images in separate calls if the prefix matters to you. |

**Response `200 OK`**

Envelope: `ApiResponse<List<UploadResponse>>`. The array is in request order, one entry per file.

```json
{
  "success": true,
  "message": "Media uploaded successfully",
  "data": [
    {
      "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9b31c604-7f28-4d13-a9e5-6c0b2f8a41de-slemani-1948-01.jpg",
      "fileName": "slemani-1948-01.jpg",
      "fileSize": 731224,
      "contentType": "image/jpeg"
    },
    {
      "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1f4c7b28-3a56-49e0-8d21-b7e0c95a3f64-slemani-1948-02.jpg",
      "fileName": "slemani-1948-02.jpg",
      "fileSize": 688901,
      "contentType": "image/jpeg"
    }
  ]
}
```

An empty or absent list inside the parsed request produces `200 OK` with `"data": []` rather than an
error — but a completely missing `files` part is the `500` case described under endpoint 1.

**Errors**

Same table as endpoint 1, plus:

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Any single file fails to upload. The service wraps the failure in `RuntimeException("Upload failed: <filename>")` and aborts the batch. |

> **Note:** There is no transaction and no rollback across the batch. If file 4 of 6 fails, files 1–3
> are already in S3 and are **not** removed, while files 5–6 are never attempted — and the client
> receives a generic 500 with none of the successful URLs. The safest dashboard strategy is to call
> endpoint 1 once per file with client-side concurrency, so a single failure costs you one file and
> you keep the URLs of everything that succeeded.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/media/upload/multiple \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F "files=@slemani-1948-01.jpg;type=image/jpeg" \
  -F "files=@slemani-1948-02.jpg;type=image/jpeg" \
  -F "files=@slemani-1948-03.jpg;type=image/jpeg" \
  -F "type=gallery"
```

---

## 3. `DELETE /api/v1/media` — Delete one object from S3

Takes the **full public URL** as a query parameter — not an S3 key, not a JSON body, and not a path
variable. The service derives the key from the URL and issues a single `DeleteObject`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** not applicable (no request body)
**Produces:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `fileUrl` | string | Yes | — | The full URL previously returned as `data.fileUrl`. URL-encode it: it contains `:` and `/`. An empty value (`?fileUrl=`) is accepted and does nothing. |

**Request body**

None.

**How the key is derived**

`S3Service.extractKeyFromUrl` tries, in order:

1. Parse `fileUrl` as a URI and take its path minus the leading `/`. Virtual-hosted URLs
   (`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/x.jpg`) yield the key
   directly.
2. If that path starts with `s3-khiwebsite/`, strip it — this handles path-style URLs
   (`https://s3.us-east-1.amazonaws.com/s3-khiwebsite/khi-web-folders/images/x.jpg`).
3. If the URI cannot be parsed, fall back to the substring starting at `khi-web-folders`, with any
   `?query` suffix removed.
4. As a last resort, treat the segment after the final `/` as a filename and rebuild the key as
   `khi-web-folders/files/<filename>` — which will usually point at nothing.

**Response `200 OK`**

Envelope: `ApiResponse<Void>`. `data` is null and `ApiResponse` is annotated `@JsonInclude(NON_NULL)`,
so the key is omitted entirely.

```json
{
  "success": true,
  "message": "Media deleted successfully"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` / `401` | — | Same auth rules as endpoint 1. |
| `400` | `MISSING_PARAMETER` | `fileUrl` was not sent at all. `details.missingParameter` is `"fileUrl"`. |

There is no `404`. Deleting a URL that does not exist, a URL from someone else's bucket, or a
malformed URL all return `200 OK` — `S3Service.deleteFile` catches every exception, logs it, and
returns normally so that cascade deletes elsewhere in the codebase are never broken by a storage
error.

> **Note:** The response therefore tells you nothing about whether an object was actually removed.
> `S3Service.isOurS3Url(...)` exists but is not called on this path, so no ownership check is
> performed before the key is derived and the delete is attempted against the KHI bucket. Treat this
> endpoint as fire-and-forget cleanup, and never as confirmation.

**Example**

```bash
curl -s -X DELETE -G http://localhost:8080/api/v1/media \
  -H "Authorization: Bearer $KHI_TOKEN" \
  --data-urlencode "fileUrl=https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/7d3f5b12-9c44-4a81-b0e7-2f6a1c8d9e10-hewler-festival.jpg"
```

---

## S3 key layout and URL shape

Configured in `application.yaml` under `aws.s3` and read by `S3Service`:

| Setting | Value |
|---------|-------|
| Region | `us-east-1` |
| Bucket | `s3-khiwebsite` |
| Base folder | `khi-web-folders` |
| Credentials | `DefaultCredentialsProvider` (environment / instance profile) — see `S3Config` |

Every key produced by these endpoints has the shape:

```
khi-web-folders/<folder>/<uuid>-<sanitised-original-filename>
```

and the returned URL is always built as `https://<bucket>.s3.<region>.amazonaws.com/<key>`:

```
https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/7d3f5b12-9c44-4a81-b0e7-2f6a1c8d9e10-hewler-festival.jpg
```

| Folder | Written by |
|--------|-----------|
| `khi-web-folders/images/` | `type=image` (the default), `type=gallery`, or sniffed `image/*` |
| `khi-web-folders/video/` | `type=video` or sniffed `video/*` |
| `khi-web-folders/audio/` | `type=audio` or sniffed `audio/*` |
| `khi-web-folders/files/` | `type=document`, `type=pdf`, or any other MIME type |
| `khi-web-folders/albums/covers/ckb/`, `.../kmr/`, `khi-web-folders/albums/hover/` | Not reachable from this API — written by the album pipeline (`uploadAlbumCover`, `uploadAlbumHover`) |

**Filename sanitising.** `sanitizeFilename` replaces every character outside `[a-zA-Z0-9._-]` with an
underscore. A Kurdish filename such as `فێستیڤاڵ.jpg` becomes `________.jpg` in the key — the UUID
prefix keeps it unique, and the readable original is preserved in the `fileName` response field. A
`null` filename becomes the literal `file`.

**No overwrites.** Every upload gets a fresh `UUID.randomUUID()`, so uploading the same file twice
produces two distinct objects. Re-uploading a replacement image never invalidates the old URL; delete
the old one explicitly if you care about storage.

**No ACL is set on `PutObject`.** Objects are readable because of the bucket policy, not because the
upload asks for public-read. Nothing is presigned and nothing expires — URLs are stable forever and
safe to cache, hot-link and embed in stored HTML.

**No CDN rewrite.** The URL handed back is the raw S3 origin. If a CDN is put in front later, every
URL already stored inside Tiptap HTML and entity columns will still point at the origin.

## Size limits

| Limit | Value | Source |
|-------|-------|--------|
| Max single file | 1 GB | `spring.servlet.multipart.max-file-size` |
| Max total request | 1 GB | `spring.servlet.multipart.max-request-size` |
| In-memory threshold | 2 MB | `spring.servlet.multipart.file-size-threshold` — larger parts spill to a temp file on disk |
| Tomcat form post / swallow size | 1 GB | `server.tomcat.max-http-form-post-size`, `server.tomcat.max-swallow-size` |
| Max request parameters | 10000 | `server.tomcat.max-parameter-count` |

Uploads are streamed to S3 with `RequestBody.fromContentProvider(...)` and an explicit content
length, so a 1 GB video does not have to fit in the JVM heap. The AWS SDK may reopen the stream on
retry, which is why the service passes a provider rather than a single `InputStream`.

Practical advice for the dashboard: a 1 GB single-shot `PutObject` over a slow uplink will hold the
connection open for a long time and cannot report progress from the server side. Show client-side
upload progress from the `XMLHttpRequest`/`fetch` stream, set a generous client timeout, and prefer
one file per request over large batches.

## Extracted metadata (dimensions, duration, bitrate)

`MediaMetadataExtractor` is a pure-Java, `ffprobe`-free extractor built on
`com.drewnoakes:metadata-extractor:2.19.0`. Given raw bytes plus a MIME type it produces a
`MediaFileMeta` with `widthPx`, `heightPx`, `durationSeconds`, `fileFormat`, `codec` and
`bitrateKbps` — images (JPEG, PNG, WebP, GIF, BMP, TIFF, with an `ImageIO` fallback for dimensions),
video (MP4, MOV/QuickTime: dimensions, duration, normalised codec such as `H.264`), and audio (MP3
bitrate plus a duration derived from size × 8 ÷ bitrate; M4A/AAC duration from the MP4 container).
Unsupported containers (MKV, AVI, WEBM, FLAC, OGG) degrade to a MIME-derived `fileFormat` string with
the other fields left null.

> **Note:** Nothing calls it. `MediaMetadataExtractor` is a `@Component` with no injection point
> anywhere in `src/main/java` — not in `MediaService`, not in `TiptapHtmlProcessor`, not in any
> domain service. The upload endpoints therefore return **no** technical metadata: `UploadResponse`
> carries only `fileUrl`, `fileName`, `fileSize` and `contentType`. Do not expect width/height or
> duration back from an upload.

Where the domain models do store technical metadata — `SoundTrackFile.durationSeconds`, `bitRate`,
`sampleRate`, `fileFormat`; the video and image DTO equivalents — those values come from the JSON the
dashboard sends when saving the entity, not from server-side inspection. If the dashboard needs
accurate values it must read them in the browser (an `<audio>`/`<video>` element's `duration`, an
`Image`'s `naturalWidth`) and include them in the save payload. `sizeBytes` is the exception: the
content services take it from the uploaded part.

---

## Tiptap editor integration

Every bilingual `description` / `body` column on the platform stores Tiptap-authored HTML. Media
inside that HTML can reach S3 by two different routes, and the dashboard should prefer the first.

### Route A (recommended) — upload first, insert the URL

1. The operator drops an image, audio clip, video or PDF into the editor.
2. The editor's upload handler immediately `POST`s it to `/api/v1/media/upload` with the right `type`
   hint, showing a placeholder while the request is in flight.
3. On `200`, the handler inserts the returned `fileUrl` into the document — `src` for
   `<img>` / `<video>` / `<audio>` / `<source>`, `href` for a `<a>` download link.
4. The operator saves the entity. The HTML that reaches `POST`/`PUT /api/v1/news`,
   `/api/v1/projects`, `/api/v1/about`, `/api/v1/services`, `/api/v1/contact`, `/api/v1/videos`,
   `/api/v1/sound-tracks`, `/api/v1/image-collections` or `/api/v1/writings` already contains only
   permanent S3 URLs.

This keeps the save request small and fast, gives the operator per-asset progress and per-asset error
recovery, and means a failed save never costs an upload.

### Route B (fallback) — `TiptapHtmlProcessor` rewrites base64 on save

If HTML still arrives containing inline data URIs — a paste from another document, a plugin that
base64-encodes on drop, an offline draft — the owning service runs it through
`TiptapHtmlProcessor.process(html)` before persisting. That method:

- Returns immediately when the input is `null`, blank, or contains no `data:` substring, so it is
  idempotent and cheap on already-clean HTML.
- Matches `src="data:<mime>;base64,<payload>"` (single or double quotes, case-insensitive) on
  `<img>`, `<video>`, `<audio>` and `<source>` — images, video and voice recordings.
- Matches `href="data:<mime>;base64,<payload>"` on `<a>` — PDFs and any other downloadable file.
- Base64-decodes each payload, picks the folder from the MIME prefix (`image/*` → `images/`,
  `video/*` → `video/`, `audio/*` → `audio/`, everything else → `files/`), and uploads the bytes
  under the generated name `tiptap-<System.nanoTime()>.<ext>`, where the extension is mapped from a
  table of about 30 MIME types (`image/jpeg` → `jpg`, `image/svg+xml` → `svg`, `audio/mpeg` → `mp3`,
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document` → `docx`, …) and falls
  back to the MIME subtype, or `bin`.
- Rewrites the attribute in place so the persisted HTML holds the S3 URL. Raw binary never reaches
  PostgreSQL.
- Is resilient: a malformed base64 payload or a failed upload is logged and the **original attribute
  is left untouched**, and the rest of the document still saves.

Services that run it today: `AboutService`, `ContactService`, `ServiceService`, `NewsService`,
`ProjectService`, `VideoService` (article body plus every clip item's bilingual description),
`SoundTrackService`, `ImageCollectionService` and `WritingService`.

> **Note:** The resilience guarantee has a sharp edge. If an upload fails, the save still succeeds and
> the base64 blob is written into the TEXT column as-is. That row will be slow to read, will bloat
> every list response that includes the description, and will be served verbatim to the public site.
> After a save, the dashboard should re-check the returned HTML for `data:` and warn the operator if
> any survived.

### Why Route A matters

A 1 GB request cap applies to the **whole** save request. Base64 inflates payloads by roughly 33%, so
a handful of inline photos can push an otherwise small article past the limit and produce a `413`
whose message misreports the limit as 5 MB. Route A keeps each request to one asset.

### Deleting editor assets

Removing an `<img>` from a Tiptap document does **not** delete the S3 object — nothing tracks which
URLs a document references. `DELETE /api/v1/media?fileUrl=...` exists for that cleanup, and the
dashboard must call it deliberately (for example by diffing the URLs in the old and new HTML on
save). Orphaned objects are otherwise invisible and permanent.

---

## Enums used by this API

### `ProjectMediaType` — folder selector

Accepted indirectly, through the `type` part. Not present in any request or response body.

| Value | Folder | Reached by |
|-------|--------|-----------|
| `IMAGE` | `images/` | `type=image` (default), `type=gallery`, MIME `image/*` |
| `VIDEO` | `video/` | `type=video`, MIME `video/*` |
| `AUDIO` | `audio/` | `type=audio`, MIME `audio/*` |
| `DOCUMENT` | `files/` | `type=document`, `type=pdf`, any other MIME |
| `PDF` | `files/` | Defined on the enum but never resolved from a `type` hint — `pdf` maps to `DOCUMENT` |
| `TEXT` | `files/` | Defined on the enum but not reachable from this API |

### `MediaKind` — gallery item discriminator

Not accepted or returned by these three endpoints. It is the `kind` field of `MediaItem`, the JSONB
gallery element used by `News` and `Project`, and it drives which HTML element the public site
renders. You set it yourself when you assemble a gallery from uploaded URLs.

| Value | Rendered as | `thumbnailUrl` meaning |
|-------|-------------|------------------------|
| `IMAGE` | `<img>` | ignored |
| `VIDEO` | `<video>` | poster frame |
| `AUDIO` | `<audio>` | cover art |

### `MediaItem` — the gallery element you build from upload results

A plain serialisable POJO (no JPA annotations) stored as one element of a JSONB `media_gallery`
column, so order and shape survive without a join table.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | string | Yes | The `fileUrl` from an upload response. |
| `kind` | `MediaKind` | Yes | `IMAGE`, `VIDEO` or `AUDIO`. |
| `thumbnailUrl` | string | No | Poster (video) or cover art (audio); ignored for images. Upload it as a second file and paste its URL here. |
| `captionCkb` | string | No | Sorani caption. |
| `captionKmr` | string | No | Kurmanji caption. |
| `sortOrder` | integer | No | Ascending display order within the gallery. |

About, Contact and Service do **not** use `MediaItem` — all of their media lives inline in the Tiptap
HTML, where `TiptapHtmlProcessor` is the only media path.

```json
{
  "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9b31c604-7f28-4d13-a9e5-6c0b2f8a41de-slemani-1948-01.jpg",
  "kind": "IMAGE",
  "thumbnailUrl": null,
  "captionCkb": "بازاڕی سلێمانی، ١٩٤٨",
  "captionKmr": "Bazara Silêmaniyê, 1948",
  "sortOrder": 1
}
```

---

## Notes & gotchas

**No content validation whatsoever.** There is no MIME allow-list, no extension check, no magic-byte
sniffing, no image re-encoding, no EXIF stripping and no virus scan. Whatever bytes are sent are
stored and served from a public bucket under the KHI domain's S3 URL. The `ADMIN` / `SUPER_ADMIN`
gate is the only control — treat upload rights as equivalent to "can publish arbitrary files on the
KHI origin", and keep the role assignment tight. An SVG uploaded here is served as `image/svg+xml`
and can carry script.

**No persistence and no cache interaction.** These endpoints touch neither PostgreSQL nor Redis. No
`@Cacheable`, no `@CacheEvict`, no `khi:` cache key is created or invalidated. Content-domain caches
are only affected when you subsequently save an entity through its own controller.

**No audit trail.** Uploads and deletes are logged to the application log only (`⬆️ Streaming to S3`,
`🗑️ Deleted from S3`). No `createdBy` is recorded against the object, and nothing links an S3 key
back to the admin who uploaded it.

**Deletes are silent, uploads are not idempotent.** Retrying a failed upload duplicates the object
under a new UUID. Deleting the same URL twice returns `200` both times.

**Orphan accumulation is the norm.** Nothing sweeps the bucket. Assets stay after the owning entity
is deleted unless that entity's own delete path removes them; assets removed from a Tiptap document
are never removed from S3.

**The `type` part is a hint, not a declaration.** It never changes the object's stored `Content-Type`
— that always comes from the browser's part header — and it never validates that the file really is
what the hint claims.

**`UploadEnvelope` and `BulkUploadResponse` are unused.** Both are declared in `MediaDtos` but no
handler returns them; every response on this controller is the platform-standard `ApiResponse`.
Ignore them when generating a client from the source.

**Timestamps.** These endpoints return none. Elsewhere the database stores UTC
(`hibernate.jdbc.time_zone=UTC`) and `LocalDateTime` fields serialise as ISO-8601 without an offset.

**Live spec caveat.** `/api/v1/media/**` is the entire content of the springdoc `internal` group
alongside the auth and profile routes, so Swagger UI's "Internal API (JWT required)" group is the
quickest way to try these calls with a pasted token (`persist-authorization` is on).

---

## Related documentation

- Domains that consume the returned URLs: [`NEWS_API.md`](NEWS_API.md), [`PROJECT_API.md`](PROJECT_API.md),
  [`ABOUT_API.md`](ABOUT_API.md), [`CONTACT_API.md`](CONTACT_API.md), [`SERVICE_API.md`](SERVICE_API.md),
  [`VIDEO_API.md`](VIDEO_API.md), [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md),
  [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md)
- Public search, which surfaces the cover URLs produced here: [`../external/SEARCH_API.md`](../external/SEARCH_API.md)
- Obtaining a token: [`../external/AUTH_API.md`](../external/AUTH_API.md),
  [`AUTH_SESSIONS_API.md`](AUTH_SESSIONS_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`
  (groups: `public`, `internal`, `all`; Media appears under the `internal` group)
