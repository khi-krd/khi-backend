# Video API — Internal (Authenticated)

Write operations for the Video domain: creating and updating film / clip publishments through multipart
upload, deleting them, managing the shared video topic registry, toggling homepage featuring, and
managing the single background video behind the homepage Film section.

Every endpoint here requires a JWT. The exact role varies per endpoint — the featured toggle is stricter
than the SecurityConfig rule implies, and deletes are stricter than creates. Read the per-endpoint
**Auth** line, not just the table. Public read endpoints are in
[`../external/VIDEO_API.md`](../external/VIDEO_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/videos` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/video/VideoController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/video/VideoService.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **Mapper** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/video/VideoMapper.java` |
| **Entities** | `Video`, `VideoContent`, `VideoClipItem`, `VideoSourceFile`, `VideoCastMember`, `VideoHighlightClip`, `VideoLog`, `FilmReklamVideo`, `PublishmentTopic` |
| **Enums** | `VideoType`, `Language` |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either way — `JWTAuthenticationFilter` accepts both:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

or the HttpOnly cookie whose name comes from the `JWT_COOKIE_NAME` environment variable (default
`khi_auth_token`). Sessions are stateless (`SessionCreationPolicy.STATELESS`); there is no server-side
session to keep alive.

Granted authorities are `ROLE_<NAME>` plus the permission strings `user:create`, `user:read`,
`user:update`, `user:delete`. Roles, weakest to strongest: `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`.
`GUEST` is the default for self-registration and can do nothing in this domain.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/videos` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a video (multipart) |
| 2 | `PUT` | `/api/v1/videos/{id}` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Update a video (multipart, partial) |
| 3 | `DELETE` | `/api/v1/videos/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a video |
| 4 | `PATCH` | `/api/v1/videos/{id}/featured` | JWT | **`ADMIN` only** | Feature / unfeature on the homepage |
| 5 | `POST` | `/api/v1/videos/topics` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a video topic |
| 6 | `DELETE` | `/api/v1/videos/topics/{topicId}` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete a topic (un-links videos first) |
| 7 | `POST` | `/api/v1/videos/film-reklam-video` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | First upload of the Film background video |
| 8 | `PATCH` | `/api/v1/videos/film-reklam-video` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Replace the Film background video |
| 9 | `DELETE` | `/api/v1/videos/film-reklam-video` | JWT | `ADMIN`, `SUPER_ADMIN` | Remove the Film background video |

### How each role is derived

| Endpoint | SecurityConfig rule | `@PreAuthorize` on the handler | Effective |
|----------|--------------------|--------------------------------|-----------|
| 1 `POST /api/v1/videos` | `POST /api/v1/videos/**` → `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | none | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| 2 `PUT /{id}` | `PUT /api/v1/videos/**` → `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | none | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| 3 `DELETE /{id}` | `DELETE /api/v1/videos/**` → `ADMIN`, `SUPER_ADMIN` | none | `ADMIN`, `SUPER_ADMIN` |
| 4 `PATCH /{id}/featured` | `PATCH /api/v1/videos/**` → `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | `hasRole('ADMIN')` | **`ADMIN` only** |
| 5 `POST /topics` | `POST /api/v1/videos/**` → `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | none | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| 6 `DELETE /topics/{topicId}` | `DELETE /api/v1/videos/**` → `ADMIN`, `SUPER_ADMIN` | none | `ADMIN`, `SUPER_ADMIN` |
| 7 `POST /film-reklam-video` | `POST /api/v1/videos/**` → `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | `hasAnyRole('EMPLOYEE','ADMIN','SUPER_ADMIN')` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| 8 `PATCH /film-reklam-video` | `PATCH /api/v1/videos/**` → `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | `hasAnyRole('EMPLOYEE','ADMIN','SUPER_ADMIN')` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| 9 `DELETE /film-reklam-video` | `DELETE /api/v1/videos/**` → `ADMIN`, `SUPER_ADMIN` | `hasAnyRole('ADMIN','SUPER_ADMIN')` | `ADMIN`, `SUPER_ADMIN` |

> **Note:** Endpoint 4 is the one real mismatch. `@PreAuthorize("hasRole('ADMIN')")` narrows the
> SecurityConfig rule to `ROLE_ADMIN` *exactly* — it does not include `SUPER_ADMIN`, and Spring Security
> has no role hierarchy configured in this application. **A `SUPER_ADMIN` receives `403 FORBIDDEN` when
> trying to feature or unfeature a video.** Every other `setXFeatured` toggle in the dashboard should be
> checked for the same pattern. Documented here as observed behaviour, not fixed in code.

---

## Multipart conventions

Endpoints 1 and 2 consume `multipart/form-data`. Endpoints 7 and 8 consume `multipart/form-data` with a
single file part. Everything else is JSON or query parameters.

| Setting | Value |
|---------|-------|
| Max single file | **1 GB** (`spring.servlet.multipart.max-file-size`) |
| Max whole request | **1 GB** (`spring.servlet.multipart.max-request-size`) |
| Disk spill threshold | 2 MB |
| Storage backend | AWS S3, region `us-east-1`, bucket `s3-khiwebsite`, base folder `khi-web-folders` |

The `data` part is a **JSON blob sent as a form field**. The controller declares it as
`@RequestPart("data") String dtoJson` and parses it with an injected Jackson `ObjectMapper`
(`khi_app/config/JacksonConfig.java`). `MultipartJsonConfig` documents that Spring Boot needs no extra
converter registration for this. Setting the part's `Content-Type` to `application/json` works and is
recommended; sending it as a plain text field also works.

> **Note:** That injected `ObjectMapper` is a bare `new ObjectMapper()`, so `FAIL_ON_UNKNOWN_PROPERTIES`
> is **enabled**. Any property in `data` that is not a `VideoDTO` field is rejected with `400 BAD_REQUEST`
> / `code: BAD_REQUEST` and `details.unknownField`. The single exception is a stray `id`, which a custom
> `DeserializationProblemHandler` skips so a response-shaped payload can be echoed back on update.
> In particular, `featured`, `featuredOrder` and `topicNameCkb` are **not** accepted inside `data`.

Uploaded S3 objects come back as
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/<folder>/<uuid>-<sanitised-filename>`,
where `<folder>` is `video` for `video/*` content types, `images` for `image/*`, `audio` for `audio/*`
and `files` otherwise. Non-alphanumeric characters in the filename become `_`.

---

## The `data` payload (`VideoDTO` as input)

Same class as the response DTO; these are the fields the service actually reads on write.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `videoType` | `FILM` \| `VIDEO_CLIP` | **Yes — on create and on update** | Enum. Rejected with `video.type.required` when absent | On update, echo the current value to keep the type; send the other value to switch and wipe the outgoing type's data |
| `ckbCoverUrl` | `string` | No | — | Cover URL for CKB. Ignored when the `ckbCoverImage` file part is present |
| `kmrCoverUrl` | `string` | No | — | Cover URL for KMR. Ignored when the `kmrCoverImage` file part is present |
| `hoverCoverUrl` | `string` | No | — | Hover/thumbnail URL. Ignored when the `hoverImage` file part is present |
| `albumOfMemories` | `boolean` | No | — | Only honoured for `VIDEO_CLIP`; forced to `false` for `FILM` |
| `topicId` | `long` | No | Must exist and have `entityType = "VIDEO"` | Assign an existing topic. Wins over `newTopic` |
| `newTopic` | `{ nameCkb, nameKmr }` | No | At least one of the two names non-blank | Create and assign a topic in one call |
| `clearTopic` | `boolean` | No | Default `false` | Update only. `true` detaches the topic and suppresses `topicId` / `newTopic` |
| `contentLanguages` | `["CKB"\|"KMR"]` | No | Enum set | Replaces the stored set when non-null |
| `ckbContent` | `VideoContentDTO` | No | — | Replaced wholesale when non-null. All-blank collapses to `null` |
| `kmrContent` | `VideoContentDTO` | No | — | Replaced wholesale when non-null. All-blank collapses to `null` |
| `videoSources` | `VideoSourceDTO[]` | No | `FILM` only | Ordered source list. Uploaded files overwrite by index |
| `sourceUrl` | `string` | No | `FILM` only | Legacy single-source shorthand; used only when `videoSources` is absent |
| `sourceExternalUrl` | `string` | No | `FILM` only | Legacy shorthand |
| `sourceEmbedUrl` | `string` | No | `FILM` only | Legacy shorthand |
| `videoClipItems` | `VideoClipItemDTO[]` | No | `VIDEO_CLIP` only | Each clip needs a source; on update each existing clip must carry its `id` |
| `castMembers` | `CastMemberDTO[]` | No | — | Replaces the stored list when non-null |
| `highlightClips` | `HighlightClipDTO[]` | No | — | Replaces the stored list when non-null |
| `fileFormat` | `string` | No | 20 chars | e.g. `"mp4"` |
| `durationSeconds` | `int` | No | — | Total runtime |
| `publishmentDate` | `string` | No | `yyyy-MM-dd` | Publication date |
| `resolution` | `string` | No | 20 chars | e.g. `"1080p"` |
| `fileSizeMb` | `double` | No | — | Size in megabytes |
| `tagsCkb` / `tagsKmr` | `string[]` | No | 100 chars per tag | Replaced wholesale when non-null |
| `keywordsCkb` / `keywordsKmr` | `string[]` | No | 150 chars per keyword | Replaced wholesale when non-null |

Fields the server **ignores on input**: `id` (skipped by the custom Jackson handler), `featureImageUrl`,
`topicNameCkb`, `topicNameKmr`, `createdAt`, `updatedAt`. `featureImageUrl` is writable only through
endpoint 4.

> **Note:** There is no `jakarta.validation` on any of this. `VideoDTO` carries zero constraint
> annotations, and the handler parameter is a `String`, so `@Valid` could not run anyway. Every rule below
> is enforced imperatively inside `VideoService` and surfaces as a `BAD_REQUEST` `AppException`, never as
> a `VALIDATION_ERROR` with `fieldErrors`. Column length limits (`title` 300, `location`/`director`/
> `producer` 250, `fileFormat`/`resolution` 20, tags 100, keywords 150) are **not** checked before the
> insert — exceeding them surfaces as `409 CONFLICT` / `DB_ERROR` from PostgreSQL, not as a clean 400.

### Nested input objects

**`VideoContentDTO`** — `title`, `description`, `location`, `director`, `producer`. All optional
strings; all trimmed, and blanks become `null`. If all five are blank the whole block is stored as
`null`. `description` is Tiptap HTML: any inline `data:<mime>;base64,…` payload on `<img>`, `<video>`,
`<audio>`, `<source>` `src` or `<a>` `href` is uploaded to S3 and the attribute rewritten before the row
is saved. The processing is idempotent and failure-tolerant — a bad payload is logged and left in place
rather than failing the save.

**`VideoSourceDTO`** — `url`, `externalUrl`, `embedUrl`, `main`, `label`, `durationSeconds`. A source
with all three URL fields blank is dropped. Exactly one surviving source ends up `main: true`: the first
one you flag, otherwise index 0.

**`VideoClipItemDTO`** — `id`, `url`, `externalUrl`, `embedUrl`, `clipNumber`, `durationSeconds`,
`resolution`, `fileFormat`, `fileSizeMb`, `titleCkb`, `titleKmr`, `descriptionCkb`, `descriptionKmr`.
At least one of `url` / `externalUrl` / `embedUrl` must resolve — from the JSON, from a matching
`videoFiles` part, or (on update only) from what is already stored.

**`CastMemberDTO`** — `nameCkb`, `nameKmr`, `roleCkb`, `roleKmr`, `imageUrl`. All optional strings,
trimmed. `imageUrl` must already be a URL; there is no cast-photo file part.

**`HighlightClipDTO`** — `titleCkb`, `titleKmr`, `url`, `embedUrl`, `durationSeconds`. No file part
either; supply URLs.

---

## File-part routing

This is the part most integrations get wrong. The rules differ by `videoType`.

### `FILM`

`videoFiles` is a repeatable part. **Every** uploaded file is kept as an entry in `videoSources[]`.

1. The list is seeded from `data.videoSources[]`, or from the legacy `sourceUrl` /
   `sourceExternalUrl` / `sourceEmbedUrl` triple when `videoSources` is absent.
2. `videoFiles[i]` overwrites `videoSources[i].url` and clears that entry's `externalUrl` and `embedUrl`.
   Extra files beyond the seeded list are **appended** as new sources with `main: false`.
3. Entries left with no `url`, no `externalUrl` and no `embedUrl` are dropped.
4. Exactly one surviving entry is flagged `main` — the first one you marked, otherwise index 0.
5. The `main` entry is mirrored onto the `sourceUrl` / `sourceExternalUrl` / `sourceEmbedUrl` columns.

Uploaded files always beat URLs supplied in the JSON for the same index.

### `VIDEO_CLIP`

`videoFiles[i]` maps to `data.videoClipItems[i]` **by array position**. A file at index `i` sets that
clip's `url` and clears its `externalUrl` and `embedUrl`. A clip with no file at its index keeps the URLs
from the JSON (create) or its stored source (update).

To upload a file for clip 3 but not clips 1 and 2, you must still send three `videoFiles` parts — send
empty ones for 1 and 2. An empty part is treated as "no file" and the slot falls through to the JSON /
stored value.

### Cross-type switching

Changing `videoType` on update wipes the other type's data: switching to `FILM` clears
`videoClipItems`; switching to `VIDEO_CLIP` clears `videoSources` and the three legacy source columns,
and forces `albumOfMemories` handling back to the clip rules.

---

## 1. `POST /api/v1/videos` — Create a video

Creates a `FILM` or `VIDEO_CLIP` publishment, uploading any supplied covers and video files to S3, then
writes a `CREATED` row to the `video_logs` audit table.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Request parts**

| Part | Content type | Required | Repeatable | Description |
|------|--------------|----------|------------|-------------|
| `data` | `application/json` (JSON blob as a form field) | **Yes** | No | Serialised `VideoDTO`. See [The `data` payload](#the-data-payload-videodto-as-input) |
| `ckbCoverImage` | `image/*` | No | No | Cover image for the CKB site. Overrides `data.ckbCoverUrl` |
| `kmrCoverImage` | `image/*` | No | No | Cover image for the KMR site. Overrides `data.kmrCoverUrl` |
| `hoverImage` | `image/*` | No | No | Hover / thumbnail image. Overrides `data.hoverCoverUrl` |
| `videoFiles` | `video/*` | No | **Yes** | Video files. Routed by `videoType` — see [File-part routing](#file-part-routing) |

There is no server-side content-type check on `ckbCoverImage`, `kmrCoverImage`, `hoverImage` or
`videoFiles`. The S3 folder is chosen from the declared content type, so a video sent with an
`image/jpeg` content type lands in `images/`. Set part content types correctly. (The `film-reklam-video`
endpoints, by contrast, *do* enforce `video/*`.)

**`data` example — `FILM` with two uploaded parts**

```json
{
  "videoType": "FILM",
  "topicId": 7,
  "contentLanguages": ["CKB", "KMR"],
  "ckbContent": {
    "title": "کۆچی ئەنفال",
    "description": "<p>بەڵگەنامەیەک لەسەر کۆچی دانیشتوانی گوندەکانی گەرمیان لە ساڵی ١٩٨٨.</p>",
    "location": "گەرمیان، هەرێمی کوردستان",
    "director": "ئارام قادر",
    "producer": "دەزگای کەلەپووری کوردی"
  },
  "kmrContent": {
    "title": "Koça Enfalê",
    "description": "<p>Belgefîlmek li ser koça gundiyên Germiyanê di sala 1988an de.</p>",
    "location": "Germiyan, Herêma Kurdistanê",
    "director": "Aram Qadir",
    "producer": "Saziya Kelepûra Kurdî"
  },
  "videoSources": [
    { "main": true, "label": "بەشی یەکەم", "durationSeconds": 2760 },
    { "label": "بەشی دووەم", "durationSeconds": 2540 }
  ],
  "castMembers": [
    {
      "nameCkb": "ئارام قادر",
      "nameKmr": "Aram Qadir",
      "roleCkb": "دەرهێنەر",
      "roleKmr": "Derhêner",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/70d2c58a-91b4-4c3e-8f57-6e1a93d4c027-aram.jpg"
    }
  ],
  "highlightClips": [
    {
      "titleCkb": "کورتەی فیلم",
      "titleKmr": "Kurteya fîlmê",
      "embedUrl": "https://www.youtube.com/embed/nQ2sK7pR1Ac",
      "durationSeconds": 74
    }
  ],
  "fileFormat": "mp4",
  "durationSeconds": 5300,
  "publishmentDate": "2026-03-14",
  "resolution": "1080p",
  "fileSizeMb": 1840.5,
  "tagsCkb": ["ئەنفال", "مێژوو"],
  "tagsKmr": ["Enfal", "Dîrok"],
  "keywordsCkb": ["ئەنفال", "گەرمیان", "١٩٨٨"],
  "keywordsKmr": ["Enfal", "Germiyan", "1988"]
}
```

The two `videoSources` entries carry no `url` — the two `videoFiles` parts fill them by index. Entry 0
is flagged `main`, so its uploaded URL is also mirrored onto `sourceUrl`.

**`data` example — `VIDEO_CLIP` album of memories, creating its topic inline**

```json
{
  "videoType": "VIDEO_CLIP",
  "albumOfMemories": true,
  "newTopic": { "nameCkb": "بیرەوەری", "nameKmr": "Bîranîn" },
  "contentLanguages": ["CKB", "KMR"],
  "ckbContent": {
    "title": "ئەلبوومی بیرەوەری هەڵەبجە",
    "description": "<p>کۆمەڵێک کلیپی کورت لە ئەرشیڤی هەڵەبجە.</p>",
    "location": "هەڵەبجە"
  },
  "kmrContent": {
    "title": "Albûma Bîranîna Helebceyê",
    "description": "<p>Komek klîpên kurt ji arşîva Helebceyê.</p>",
    "location": "Helebce"
  },
  "videoClipItems": [
    {
      "clipNumber": 1,
      "durationSeconds": 96,
      "resolution": "720p",
      "fileFormat": "mp4",
      "titleCkb": "بەیانی ١٦ی ئازار",
      "titleKmr": "Sibeha 16ê Adarê"
    },
    {
      "clipNumber": 2,
      "durationSeconds": 143,
      "externalUrl": "https://www.youtube.com/watch?v=nQ2sK7pR1Ac",
      "embedUrl": "https://www.youtube.com/embed/nQ2sK7pR1Ac",
      "titleCkb": "لێدوانی شایەتحاڵان",
      "titleKmr": "Şahidiya şahidan"
    }
  ],
  "publishmentDate": "2026-03-16",
  "tagsCkb": ["هەڵەبجە", "بیرەوەری"],
  "tagsKmr": ["Helebce", "Bîranîn"]
}
```

Clip 1 has no URL fields and expects `videoFiles[0]`. Clip 2 supplies its own external and embed URLs,
so no file is needed — but if you send `videoFiles` at all, clip 2 corresponds to index 1, and sending
a real file there would clear the YouTube URLs.

**Response `201 Created`**

A bare `VideoDTO` — no `ApiResponse` envelope, no `Location` header.

```json
{
  "id": 42,
  "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-anfal-cover-ckb.jpg",
  "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b2d8a10-77f4-49c1-b0a3-1d5e9c2f7a48-anfal-cover-kmr.jpg",
  "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/c41e7f92-0d63-4a58-9e77-2b8c6f014d5a-anfal-hover.jpg",
  "videoType": "FILM",
  "albumOfMemories": false,
  "clearTopic": false,
  "topicId": 7,
  "topicNameCkb": "بەڵگەنامەیی",
  "topicNameKmr": "Belgefîlm",
  "contentLanguages": ["CKB", "KMR"],
  "ckbContent": {
    "title": "کۆچی ئەنفال",
    "description": "<p>بەڵگەنامەیەک لەسەر کۆچی دانیشتوانی گوندەکانی گەرمیان لە ساڵی ١٩٨٨.</p>",
    "location": "گەرمیان، هەرێمی کوردستان",
    "director": "ئارام قادر",
    "producer": "دەزگای کەلەپووری کوردی"
  },
  "kmrContent": {
    "title": "Koça Enfalê",
    "description": "<p>Belgefîlmek li ser koça gundiyên Germiyanê di sala 1988an de.</p>",
    "location": "Germiyan, Herêma Kurdistanê",
    "director": "Aram Qadir",
    "producer": "Saziya Kelepûra Kurdî"
  },
  "videoSources": [
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/8d1a4b60-53e7-41f9-9c25-0af6b3d81e77-anfal-part-1.mp4",
      "main": true,
      "label": "بەشی یەکەم",
      "durationSeconds": 2760
    },
    {
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/2e77c9f4-a015-4d38-b6c1-9f4e2a7d5033-anfal-part-2.mp4",
      "main": false,
      "label": "بەشی دووەم",
      "durationSeconds": 2540
    }
  ],
  "sourceUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/8d1a4b60-53e7-41f9-9c25-0af6b3d81e77-anfal-part-1.mp4",
  "castMembers": [
    {
      "nameCkb": "ئارام قادر",
      "nameKmr": "Aram Qadir",
      "roleCkb": "دەرهێنەر",
      "roleKmr": "Derhêner",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/70d2c58a-91b4-4c3e-8f57-6e1a93d4c027-aram.jpg"
    }
  ],
  "highlightClips": [
    {
      "titleCkb": "کورتەی فیلم",
      "titleKmr": "Kurteya fîlmê",
      "embedUrl": "https://www.youtube.com/embed/nQ2sK7pR1Ac",
      "durationSeconds": 74
    }
  ],
  "fileFormat": "mp4",
  "durationSeconds": 5300,
  "publishmentDate": "2026-03-14",
  "resolution": "1080p",
  "fileSizeMb": 1840.5,
  "tagsCkb": ["ئەنفال", "مێژوو"],
  "tagsKmr": ["Enfal", "Dîrok"],
  "keywordsCkb": ["ئەنفال", "گەرمیان", "١٩٨٨"],
  "keywordsKmr": ["Enfal", "Germiyan", "1988"],
  "createdAt": "2026-03-14T09:22:41",
  "updatedAt": "2026-03-14T09:22:41"
}
```

**Errors**

| Status | `code` | `messageKey` in `details` context | When |
|--------|--------|-----------------------------------|------|
| `400` | `BAD_REQUEST` | `video.dto.required` | `data` deserialised to `null` (e.g. the literal `null`) |
| `400` | `BAD_REQUEST` | `video.type.required` | `videoType` missing from `data`. `details.field = "videoType"` |
| `400` | `BAD_REQUEST` | `video.clip.source.required` | A `VIDEO_CLIP` clip has no file, no `url`, no `externalUrl` and no `embedUrl`. `details.field = "videoClipItems[i]"` |
| `400` | `BAD_REQUEST` | `video.topic.names.required` | `newTopic` supplied with both names blank |
| `400` | `BAD_REQUEST` | `topic.type.mismatch` | `topicId` points at a topic whose `entityType` is not `"VIDEO"` |
| `400` | `BAD_REQUEST` | `media.upload.failed` | Reading the uploaded bytes failed. `details.filename`, `details.error` |
| `400` | `BAD_REQUEST` | `s3.upload.failed` | S3 rejected the `PutObject` |
| `400` | `BAD_REQUEST` | `error.http.unknown_field` | `data` contains a property that is not a `VideoDTO` field. `details.unknownField` |
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Authenticated as `GUEST` |
| `404` | `NOT_FOUND` | `topic.not_found` | `topicId` does not exist. `details.id` |
| `409` | `CONFLICT` | — | A column-length or constraint violation reached PostgreSQL |
| `413` | `PAYLOAD_TOO_LARGE` | `error.http.payload_too_large` | Request exceeds the 1 GB multipart limit |
| `500` | `INTERNAL_ERROR` | — | Malformed JSON syntax in `data`, an unknown enum value such as `"videoType": "MOVIE"`, or the `data` part missing entirely |

> **Note:** The `413` body says *"The uploaded file exceeds the maximum allowed size of 5 MB"* and lists
> "Accepted formats: JPEG, PNG, GIF, WebP". `GlobalExceptionHandler.MAX_UPLOAD_MB` is hard-coded to `5`
> while `spring.servlet.multipart.max-file-size` is `1GB`. The status and `code` are right; the message
> and `details.maxAllowedMB` are wrong. Do not surface that text to users of this endpoint.

> **Note:** The three `500` cases above are unhandled exception types.
> `MissingServletRequestPartException` (missing `data`), Jackson's `JsonParseException` (malformed JSON)
> and `InvalidFormatException` (bad enum value) are none of them covered by a specific
> `@ExceptionHandler`, so the catch-all `@ExceptionHandler(Exception.class)` answers `500 INTERNAL_ERROR`
> where a `400` would be correct. Validate the JSON client-side before posting.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/videos \
  -H "Authorization: Bearer $TOKEN" \
  -F "data=@create-film.json;type=application/json" \
  -F "ckbCoverImage=@anfal-cover-ckb.jpg;type=image/jpeg" \
  -F "kmrCoverImage=@anfal-cover-kmr.jpg;type=image/jpeg" \
  -F "hoverImage=@anfal-hover.jpg;type=image/jpeg" \
  -F "videoFiles=@anfal-part-1.mp4;type=video/mp4" \
  -F "videoFiles=@anfal-part-2.mp4;type=video/mp4"
```

Uploading two 900 MB parts in one request exceeds `max-request-size`. Split large films across several
`PUT` calls, appending one source at a time.

---

## 2. `PUT /api/v1/videos/{id}` — Update a video

Partial update. A `null` field in `data` means "leave it alone"; a non-null field replaces the stored
value outright (collections are replaced, not merged). Writes an `UPDATED` row to `video_logs`.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `long` | Yes | Video primary key |

**Request parts** — identical to endpoint 1 (`data`, `ckbCoverImage`, `kmrCoverImage`, `hoverImage`,
`videoFiles`). `data` is still required.

**Update semantics**

| Concern | Behaviour |
|---------|-----------|
| Scalars & collections | Non-null in `data` → replaced. Null / absent → untouched. There is no way to clear a scalar back to `null` through this endpoint |
| `videoType` | Mandatory (`requireDto()` runs first and rejects a null). Echo the current type to keep it; send the other value to switch, which wipes the outgoing type's data |
| Covers | A file part replaces the stored URL. No file but a non-blank URL in `data` replaces it. Neither → untouched. Sending `""` does **not** clear a cover |
| `FILM` sources | Rebuilt **only** when the request signals intent: at least one non-empty `videoFiles` part, or `videoSources` present in `data`, or any of the three legacy `source*Url` fields present. Otherwise the stored sources and the mirror are left completely untouched. When rebuilt, the whole list is replaced |
| `VIDEO_CLIP` clips | Rebuilt only when `videoClipItems` is present in `data`. The array you send becomes the complete new clip list — **any stored clip you omit is deleted** (`orphanRemoval = true`). Include the persisted `id` on every clip you want to keep |
| Clip sources on update | Priority: uploaded `videoFiles[i]` → URL fields in the clip JSON → the clip's stored source. A clip that resolves to none of these is rejected |
| Topic | Precedence is `clearTopic` → `topicId` → `newTopic`. `clearTopic: true` detaches and ignores the other two |
| `albumOfMemories` | Applied only for `VIDEO_CLIP` and only when present in `data`. Forced to `false` whenever the resulting type is `FILM` |
| Old S3 objects | **Never deleted.** Replacing a cover or a source leaves the previous object in the bucket |

**`data` example — replace clip 2's file, keep clip 1, drop the topic**

```json
{
  "videoType": "VIDEO_CLIP",
  "clearTopic": true,
  "videoClipItems": [
    { "id": 301, "clipNumber": 1 },
    { "id": 302, "clipNumber": 2, "titleCkb": "لێدوانی شایەتحاڵان (نوێکراوە)" }
  ]
}
```

Paired with two `videoFiles` parts — an empty one for index 0 so clip 301 keeps its stored URL, and the
real replacement file at index 1 for clip 302 (which also clears clip 302's `externalUrl` and
`embedUrl`).

**`data` example — add a third part to a film without touching anything else**

```json
{
  "videoType": "FILM",
  "videoSources": [
    { "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/8d1a4b60-53e7-41f9-9c25-0af6b3d81e77-anfal-part-1.mp4", "main": true, "label": "بەشی یەکەم" },
    { "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/2e77c9f4-a015-4d38-b6c1-9f4e2a7d5033-anfal-part-2.mp4", "label": "بەشی دووەم" },
    { "label": "بەشی سێیەم" }
  ]
}
```

Send exactly three `videoFiles` parts — two empty, one real at index 2 — or send `videoSources` with the
first two URLs echoed back as above plus one real `videoFiles` part, which will be consumed at index 0.
The first form is safer: the file-to-index mapping counts **all** `videoFiles` parts, empty ones
included, so a single trailing file part lands at index 0, not at the end.

**Response `200 OK`** — a bare `VideoDTO`, same shape as endpoint 1's response, with a refreshed
`updatedAt`.

**Errors**

| Status | `code` | `messageKey` | When |
|--------|--------|--------------|------|
| `400` | `BAD_REQUEST` | `video.dto.required` | `data` deserialised to `null` |
| `400` | `BAD_REQUEST` | `video.type.required` | `videoType` missing from `data` — **required on update too** |
| `400` | `BAD_REQUEST` | `video.id.required` | `id` resolved to `null` |
| `400` | `BAD_REQUEST` | `video.clip.id.invalid` | A clip carries an `id` that does not belong to this video. `details.field = "videoClipItems[i].id"`, `details.id` |
| `400` | `BAD_REQUEST` | `video.clip.id.duplicate` | The same clip `id` appears twice in `videoClipItems` |
| `400` | `BAD_REQUEST` | `video.clip.source.required` | A clip ends up with no source at all |
| `400` | `BAD_REQUEST` | `video.topic.names.required` | `newTopic` with both names blank |
| `400` | `BAD_REQUEST` | `topic.type.mismatch` | `topicId` belongs to a non-`VIDEO` topic |
| `400` | `BAD_REQUEST` | `media.upload.failed` / `s3.upload.failed` | Upload failure |
| `400` | `BAD_REQUEST` | `error.http.unknown_field` | Unknown property inside `data` |
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Insufficient role |
| `404` | `VIDEO_NOT_FOUND` | `video.not_found` | No video with that id. `details.id` |
| `404` | `NOT_FOUND` | `topic.not_found` | `topicId` does not exist |
| `409` | `CONFLICT` | — | Column-length or constraint violation |
| `413` | `PAYLOAD_TOO_LARGE` | — | Request exceeds 1 GB |
| `500` | `INTERNAL_ERROR` | — | `{id}` not numeric, malformed `data` JSON, bad enum value, or missing `data` part |

> **Note:** `videoType` is mandatory on update even though the surrounding contract is partial —
> `requireDto()` runs before the existing entity is consulted. Always echo the current `videoType` back.

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/videos/88 \
  -H "Authorization: Bearer $TOKEN" \
  -F "data=@update-clips.json;type=application/json" \
  -F "videoFiles=;type=video/mp4" \
  -F "videoFiles=@halabja-clip-2-remaster.mp4;type=video/mp4"
```

---

## 3. `DELETE /api/v1/videos/{id}` — Delete a video

Hard-deletes the row. Cascades to `video_clip_items` (`cascade = ALL`, `orphanRemoval = true`) and to the
`video_source_files`, `video_cast_members`, `video_highlight_clips`, `video_tags_ckb`, `video_tags_kmr`,
`video_keywords_ckb`, `video_keywords_kmr` and `video_content_languages` collection tables. The topic row
itself is left alone. A `DELETED` row is written to `video_logs`, preserving the id and title snapshot.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** none (no body)

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `long` | Yes | Video primary key |

**Response `204 No Content`** — empty body.

**Idempotent.** Deleting an id that does not exist is a successful no-op: still `204`, and no audit row
is written. Do not rely on a `404` to tell you a video is already gone.

> **Note:** S3 objects are **not** cleaned up. Cover images, hover images, film sources, clip files and
> any media extracted from the Tiptap descriptions stay in `s3-khiwebsite` forever. (The
> `film-reklam-video` delete at endpoint 9 *does* remove its S3 object — this domain is inconsistent.)

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | `UNAUTHORIZED` | No or invalid JWT |
| `403` | `FORBIDDEN` | Authenticated as `EMPLOYEE` or `GUEST` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/videos/42 \
  -H "Authorization: Bearer $TOKEN" -i
```

---

## 4. `PATCH /api/v1/videos/{id}/featured` — Feature / unfeature

Flags a video for the homepage carousel. Delegates to `SiteContentService.setVideoFeatured()`, which is
shared with news, projects, writings, sound tracks, image collections and the donation page — the slide
cap is global across all of them.

**Auth:** `Authorization: Bearer <token>`, role **`ADMIN` only** (`@PreAuthorize("hasRole('ADMIN')")`)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `long` | Yes | Video primary key |

**Request body** — `SiteContentDtos.FeaturedRequest`. Only three of its fields are read by this code path:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | `boolean` | No | — | `true` or omitted/`null` → feature. `false` → unfeature |
| `featuredOrder` | `int` | No | — | Sort key, ascending; lower shows first. Nulls sort last. **Forced to `null` when unfeaturing** |
| `featureImageUrl` | `string` | No | — | Wide hero picture. Omit to leave the stored value alone; send `""` to clear it and fall back to the cover. Any other value is trimmed and stored |

```json
{
  "featured": true,
  "featuredOrder": 2,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/aa71c3d5-9f20-4c8e-8b16-77d9e4a3c012-anfal-hero.jpg"
}
```

To unfeature:

```json
{ "featured": false }
```

> **Note:** `FeaturedRequest` declares `@NotBlank` on `type`, `slug`, `title`, `description` and
> `imageUrl`, but the handler parameter is a plain `@RequestBody` with **no `@Valid`**, so none of those
> constraints run and none of those five fields are read by `setVideoFeatured()`. The minimal body above
> is accepted. An empty body `{}` is also accepted and means "feature with no explicit order".

**Response `204 No Content`** — empty body. Re-read the video with
`GET /api/v1/videos/{id}` if you need the updated state, though note that `VideoDTO` exposes
`featureImageUrl` but never `featured` or `featuredOrder`. To confirm the flag took effect, call
`GET /api/v1/videos/featured` and look for the id.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Turning featuring **on** would exceed `SiteSettings.maxFeaturedSlides` (default `7`) counted across news + projects + writings + videos + sound tracks + image collections + the donation page. Message: `"Maximum of N featured slides allowed across all content. Unfeature one first."` |
| `400` | `BAD_REQUEST` | Malformed JSON body |
| `401` | `UNAUTHORIZED` | No or invalid JWT |
| `403` | `FORBIDDEN` | Any role other than `ADMIN` — **including `SUPER_ADMIN`** |
| `404` | `NOT_FOUND` | No video with that id. Message: `"Video not found: 42"` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number |

The cap is only checked when turning featuring **on** for a video that is not already featured. Changing
the `featuredOrder` of an already-featured video is never blocked, and unfeaturing is never blocked.

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/videos/42/featured \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": true, "featuredOrder": 2}' -i
```

---

## 5. `POST /api/v1/videos/topics` — Create a video topic

Creates a `PublishmentTopic` with `entityType = "VIDEO"`. Topics are shared across videos: create once,
then reference by `topicId` from any number of videos.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** none — the names are **query parameters**, not a JSON body.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `nameCkb` | `string` | No\* | — | Sorani name. Trimmed; blank becomes `null` |
| `nameKmr` | `string` | No\* | — | Kurmanji name. Trimmed; blank becomes `null` |

\* Both are declared `required = false`, but **at least one must be non-blank** or the service rejects the
call. Sending both is normal.

**Response `201 Created`** — a bare `TopicView`.

```json
{
  "id": 21,
  "nameCkb": "چاوپێکەوتن",
  "nameKmr": "Hevpeyvîn",
  "createdAt": "2026-08-26T09:14:22"
}
```

**No duplicate check.** Posting the same names twice creates two topics with different ids. The
dashboard should offer the existing list (`GET /api/v1/videos/topics`) as autocomplete before creating.

**Errors**

| Status | `code` | `messageKey` | When |
|--------|--------|--------------|------|
| `400` | `BAD_REQUEST` | `video.topic.names.required` | Both names absent or blank. `details.message` explains in Sorani |
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Authenticated as `GUEST` |

**Example**

```bash
# Let curl encode the Kurdish names, but keep the method as POST with an empty body
curl -s -X POST -G --data-urlencode "nameCkb=چاوپێکەوتن" \
                   --data-urlencode "nameKmr=Hevpeyvîn" \
  "http://localhost:8080/api/v1/videos/topics" \
  -H "Authorization: Bearer $TOKEN"
```

Or with the query string already percent-encoded:

```bash
curl -s -X POST "http://localhost:8080/api/v1/videos/topics?nameCkb=%DA%86%D8%A7%D9%88%D9%BE%DB%8E%DA%A9%DB%95%D9%88%D8%AA%D9%86&nameKmr=Hevpeyv%C3%AEn" \
  -H "Authorization: Bearer $TOKEN"
```

There is no update endpoint for topics — to rename one, delete it and create a replacement, then
re-assign the affected videos.

---

## 6. `DELETE /api/v1/videos/topics/{topicId}` — Delete a video topic

Detaches the topic from every video that references it (setting each `topic_id` to `NULL` and saving the
batch), then deletes the topic row. Videos are **not** deleted.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** none (no body)

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `topicId` | `long` | Yes | Topic primary key |

**Response `204 No Content`** — empty body.

Unlike the video delete, this one is **not idempotent**: deleting the same topic twice returns `404` the
second time.

**Errors**

| Status | `code` | `messageKey` | When |
|--------|--------|--------------|------|
| `400` | `BAD_REQUEST` | `topic.type.mismatch` | The id exists but belongs to a `SOUND` / `IMAGE` / `WRITING` topic. `details.message` names the actual `entityType` |
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Authenticated as `EMPLOYEE` or `GUEST` |
| `404` | `NOT_FOUND` | `topic.not_found` | No topic with that id. `details.id` |
| `500` | `INTERNAL_ERROR` | — | `{topicId}` is not a number |

The `topic.type.mismatch` guard means this endpoint cannot be used to delete another domain's topics
even though they share one table.

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/videos/topics/21 \
  -H "Authorization: Bearer $TOKEN" -i
```

---

## Film Reklam Video (endpoints 7–9)

The homepage Film section has one site-wide background video that plays muted and looping behind the
film cards. It is a singleton — `film_reklam_videos` holds at most one row — so none of these endpoints
takes an id. It is **not** a `Video` row: no bilingual content, no topic, no tags, no featured flag, no
audit log. It mirrors `SoundReklamVideo`, which does the same job for the Sound section.

**Dashboard flow.** On screen open, call `GET /api/v1/videos/film-reklam-video`
([external doc](../external/VIDEO_API.md#7-get-apiv1videosfilm-reklam-video--film-section-background-video)).
A `200` means show "replace" and "remove"; a `404` means show "upload". The first upload is `POST`;
every one after that is `PATCH`.

Both write endpoints validate the file the same way, in `VideoService.validatePromoVideoFile()`:

- the `videoFile` part must be present and non-empty, and
- its `Content-Type` must start with `video/` (case-insensitive).

There is no size or duration cap beyond the 1 GB Spring multipart limit — enforce any tighter ceiling in
the dashboard before uploading.

---

## 7. `POST /api/v1/videos/film-reklam-video` — First upload

Creates the singleton. **Fails when one already exists** — use `PATCH` to replace.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Request parts**

| Part | Content type | Required | Repeatable | Description |
|------|--------------|----------|------------|-------------|
| `videoFile` | `video/*` | **Yes** | No | The background video. Content type must start with `video/` |

**Response `201 Created`** — wrapped in `ApiResponse<FilmReklamVideoResponse>`.

```json
{
  "success": true,
  "message": "Film reklam video created successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/9a3f27e6-c841-4b05-97de-6f2c30d15a84-film-section-loop.mp4",
    "sizeBytes": 48219043,
    "mimeType": "video/mp4",
    "createdAt": "2026-08-26T09:14:22",
    "updatedAt": "2026-08-26T09:14:22"
  }
}
```

**Errors**

| Status | `code` | `messageKey` | When |
|--------|--------|--------------|------|
| `400` | `VIDEO_MEDIA_INVALID` | `error.validation` | `videoFile` empty. `details.field = "videoFile"` |
| `400` | `VIDEO_MEDIA_INVALID` | `error.validation` | Content type does not start with `video/` |
| `400` | `VIDEO_MEDIA_INVALID` | `video.reklamVideo.already_exists` | A background video already exists — use `PATCH` |
| `400` | `BAD_REQUEST` | `media.upload.failed` / `s3.upload.failed` | Upload failure |
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Authenticated as `GUEST` |
| `413` | `PAYLOAD_TOO_LARGE` | — | Larger than 1 GB |
| `500` | `INTERNAL_ERROR` | — | `videoFile` part missing entirely |

`video.reklamVideo.already_exists` is one of the few video message keys that **does** exist in
`messages_ckb.properties` and `messages_kmr.properties`, so the `Accept-Language`-driven `message` field
reads correctly for Kurdish clients.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/videos/film-reklam-video \
  -H "Authorization: Bearer $TOKEN" \
  -F "videoFile=@film-section-loop.mp4;type=video/mp4"
```

---

## 8. `PATCH /api/v1/videos/film-reklam-video` — Replace

A full replacement despite the `PATCH` verb — there is no other field to patch. The new file is uploaded
and the row saved **before** the old S3 object is deleted, so a failed upload leaves the existing video
intact. Fails with `404` when nothing has been uploaded yet; fall back to `POST`.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Request parts**

| Part | Content type | Required | Repeatable | Description |
|------|--------------|----------|------------|-------------|
| `videoFile` | `video/*` | **Yes** | No | The replacement video. Content type must start with `video/` |

**Response `200 OK`** — `ApiResponse<FilmReklamVideoResponse>`. The `id` and `createdAt` are unchanged;
`videoUrl`, `sizeBytes`, `mimeType` and `updatedAt` all change.

```json
{
  "success": true,
  "message": "Film reklam video updated successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/41c8d20b-7ef5-4a93-b158-6d0e93f7a25c-film-section-loop-v2.mp4",
    "sizeBytes": 61044887,
    "mimeType": "video/mp4",
    "createdAt": "2026-05-04T12:30:19",
    "updatedAt": "2026-08-26T09:14:22"
  }
}
```

**Errors**

| Status | `code` | `messageKey` | When |
|--------|--------|--------------|------|
| `400` | `VIDEO_MEDIA_INVALID` | `error.validation` | `videoFile` empty, or content type not `video/*` |
| `400` | `BAD_REQUEST` | `media.upload.failed` / `s3.upload.failed` | Upload failure |
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Authenticated as `GUEST` |
| `404` | `NOT_FOUND` | `video.reklamVideo.not_found` | Nothing uploaded yet — use `POST` |
| `413` | `PAYLOAD_TOO_LARGE` | — | Larger than 1 GB |
| `500` | `INTERNAL_ERROR` | — | `videoFile` part missing entirely |

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/videos/film-reklam-video \
  -H "Authorization: Bearer $TOKEN" \
  -F "videoFile=@film-section-loop-v2.mp4;type=video/mp4"
```

---

## 9. `DELETE /api/v1/videos/film-reklam-video` — Remove

Deletes the row and then removes the S3 object. The Film section falls back to its plain background on
the public site.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** none (no body)

**Response `204 No Content`** — empty body.

**Not idempotent** — a second call returns `404`.

S3 deletion is best-effort: `S3Service.deleteFile()` swallows its own failures and logs them, so a `204`
does not guarantee the object is gone from the bucket. The database row is gone either way.

**Errors**

| Status | `code` | `messageKey` | When |
|--------|--------|--------------|------|
| `401` | `UNAUTHORIZED` | — | No or invalid JWT |
| `403` | `FORBIDDEN` | — | Authenticated as `EMPLOYEE` or `GUEST` |
| `404` | `NOT_FOUND` | `video.reklamVideo.not_found` | Nothing uploaded, or already deleted |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/videos/film-reklam-video \
  -H "Authorization: Bearer $ADMIN_TOKEN" -i
```

---

## Enums used by this API

### `VideoType`

`src/main/java/ak/dev/khi_backend/khi_app/model/publishment/video/VideoType.java`. Required in every
create and update `data` payload.

| Value | Meaning |
|-------|---------|
| `FILM` | Film or documentary. Sources go in `videoSources[]`; `videoClipItems` is force-cleared and `albumOfMemories` is forced to `false` |
| `VIDEO_CLIP` | Collection of short clips in `videoClipItems[]`; `videoSources` and the three legacy `source*Url` columns are force-cleared. May be flagged `albumOfMemories` |

### `Language`

`src/main/java/ak/dev/khi_backend/khi_app/enums/Language.java`. Used in `contentLanguages`.

| Value | Meaning |
|-------|---------|
| `CKB` | Sorani / Central Kurdish |
| `KMR` | Kurmanji / Northern Kurdish |

Sending anything else — `"EN"`, `"FILM"`, a lowercase `"ckb"` — is an `InvalidFormatException` inside the
`data` parse, which currently surfaces as `500 INTERNAL_ERROR` rather than `400`.

### `FilmType` — not used

`src/main/java/ak/dev/khi_backend/khi_app/enums/publishment/FilmType.java` defines `DOCUMENTARY`,
`EVIDENCE`, `SHORT_FILM`, `FEATURE_FILM`, `INTERVIEW`, `ARCHIVAL_FOOTAGE`, `EDUCATIONAL`, `OTHER`.

> **Note:** Nothing references this enum. It is not a field on `Video`, `VideoDTO` or any other class in
> the codebase — a grep across `src/main/java` finds only its own declaration. Do not send a `filmType`
> field; it would be rejected as an unknown property. Use the topic registry (endpoints 5–6) to
> categorise films.

---

## The `VideoLog` audit trail

Every create, update and delete writes one row to `video_logs` via `VideoService.logAction()`.

| Column | Type | Set by | Notes |
|--------|------|--------|-------|
| `id` | `bigserial` | DB | |
| `video_id` | `bigint` | service | Snapshot. Survives the video's deletion, so it may point at a row that no longer exists |
| `video_title` | `varchar(300)` | service | Snapshot of `ckbContent.title`, falling back to `kmrContent.title`, falling back to the literal `"ڤیدیۆی بێ ناو"` ("untitled video") |
| `action` | `varchar(30)` | service | `"CREATED"`, `"UPDATED"` or `"DELETED"` |
| `details` | `text` | service | Kurdish free text. Create records the type and album flag; update and delete record a fixed phrase |
| `performed_by` | `varchar(150)` | — | **Always `null`** |
| `timestamp` | `timestamp` | service / `@PrePersist` | UTC |

`VideoLogRepository` exposes `findByVideoIdOrderByTimestampDesc` and `findAllByOrderByTimestampDesc`, and
`VideoMapper.toLogDTO()` maps to `VideoLogDTO` (`id`, `videoId`, `videoTitle`, `action`, `details`,
`performedBy`, `timestamp`).

> **Note:** No controller exposes any of this. There is **no HTTP endpoint** to read the video audit log
> in this build, and `performedBy` is never populated — the `VideoLog` javadoc calls it "future auth
> integration". The table records *what* changed, never *who* changed it.

---

## Notes & gotchas

**No caching, no cache eviction.** `VideoService` has no `@Cacheable` / `@CacheEvict`. Redis is
configured application-wide (`khi:` prefix, 10-minute default TTL) but this domain does not use it, so
writes are visible to public reads immediately. No eviction call is needed after a write.

**Transactions.** `addVideo`, `updateVideo`, `deleteVideo`, `createTopic`, `deleteTopic` and all three
`film-reklam-video` writes are `@Transactional`. S3 uploads happen **inside** those transactions but are
not transactional themselves: if the DB save fails after a successful upload, the object stays in the
bucket, orphaned. Orphans accumulate; there is no reaper.

**S3 objects are never garbage-collected on video writes.** Replacing a cover, replacing a film source,
or deleting a whole video leaves every previous object in `s3-khiwebsite`. Only endpoints 8 and 9 clean
up after themselves.

**Tiptap media extraction.** Before every save, `TiptapHtmlProcessor` scans `ckbContent.description`,
`kmrContent.description` and every clip's `descriptionCkb` / `descriptionKmr` for inline
`data:<mime>;base64,…` payloads on `<img>`, `<video>`, `<audio>`, `<source>` `src` attributes and `<a>`
`href` attributes, uploads each to S3, and rewrites the attribute. This is idempotent — HTML that already
contains only S3 URLs is returned unchanged. A malformed payload or a failed upload is logged and left in
place; the save still succeeds. Sending a 900 MB base64 image inside `description` will count against
`max-request-size`, so keep large media in the dedicated file parts.

**Empty multipart parts are meaningful.** They count as index slots for `videoFiles` routing while being
treated as "no file uploaded". This is how you target clip `i` or source `i` without disturbing the
earlier ones.

**No `@Valid` anywhere in this controller.** Neither the `data` blob (it is a `String`) nor the
`FeaturedRequest` body is validated by jakarta.validation. You will never see `VALIDATION_ERROR` with a
populated `fieldErrors` array from these endpoints — every rejection is a `BAD_REQUEST` `AppException`
with a `details` map instead.

**Error message text is unreliable; branch on `code` and `status`.** None of the `video.*`, `topic.*`,
`search.*` or `media.*` message keys used by `VideoService` exist in any
`src/main/resources/i18n/messages_*.properties` bundle, and `GlobalExceptionHandler.fallbackByCode()`
has no `VIDEO_*` branch, so its `default` arm produces "Internal error" / "هەڵەی ناوخۆیی" for a correctly
coded `404 VIDEO_NOT_FOUND`.

> **Note:** Two separate i18n defects compound this. First, the English bundle file is named
> `" messages_en.properties"` with a leading space, so `classpath:i18n/messages_en.properties` never
> resolves and `messageEn` is always the hard-coded fallback. Second, the handler resolves `messageKu`
> with `Locale.forLanguageTag("ku")` while the bundles are `_ckb` and `_kmr`, so no file matches there
> either. Only the `Accept-Language`-driven `message` field reaches the real translations, and only for
> keys that actually exist — `video.reklamVideo.not_found` and `video.reklamVideo.already_exists` are the
> only two in this domain.

**Do not send `?lang=`.** `I18nConfig` registers a `LocaleChangeInterceptor` on the `lang` parameter
alongside an `AcceptHeaderLocaleResolver`, whose `setLocale()` throws `UnsupportedOperationException`.
Any request carrying `?lang=…` returns `500 INTERNAL_ERROR`. Use the `Accept-Language` header.

**Path routing.** `/film-reklam-video`, `/topics`, `/featured` and `/search/*` are literal segments and
outrank the `/{id}` template in Spring's `PathPattern` comparator, so there is no ambiguity with a
numeric id. A non-numeric `{id}` or `{topicId}` produces a `500`, not a `404`.

**Dead code worth knowing about.** `VideoRepository.findByIdWithDetails()` carries an `@EntityGraph` that
join-fetches `videoClipItems` and `topic`, but `VideoService.findOrThrow()` calls the plain
`findById()` — so the documented N+1 fix for the detail endpoint is not actually in effect.
`VideoService.getAllVideos(int, int)` is likewise never called; the controller routes through
`getVideoListing()`.

---

## Related documentation

- Counterpart (public reads, response schemas in full): [`../external/VIDEO_API.md`](../external/VIDEO_API.md)
- Database schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups `public`, `internal`, `all`).
  Note that `OpenApiConfig` groups by path prefix, so all of `/api/v1/videos/**` — these authenticated
  writes included — is listed under the `public` group.
- Servers: `http://localhost:8080` (local); production is deployed on Railway.
