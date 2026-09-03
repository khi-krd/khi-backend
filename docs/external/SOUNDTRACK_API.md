# Sound Tracks API — External (Public)

The sound-track module is the audio archive of the Kurdish Heritage Institute: recorded `lawik`,
`heyran`, oral poetry, interviews and field recordings, published either as a **SINGLE** track or as a
**MULTI**-track album (an album can additionally be flagged as an *Album of Memories*). Every record is
bilingual — Sorani (`CKB`) and Kurmanji (`KMR`) — and carries an ordered list of audio files, each with
its own technical metadata and brochure images, plus optional album-level attachments (PDF booklets,
promo clips, lyric sheets).

This file documents only the endpoints an anonymous visitor may call. Everything that creates, updates
or deletes a sound track lives in [`../internal/SOUNDTRACK_API.md`](../internal/SOUNDTRACK_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/sound-tracks` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/sound/SoundTrackController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/sound/SoundTrackService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/sound/SoundTrackDtos.java` |
| **Entities** | `SoundTrack`, `SoundTrackContent`, `SoundTrackFile`, `SoundTrackBrochure`, `SoundTrackAttachment`, `SoundReklamVideo`, `SoundTrackLog`, `PublishmentTopic` |
| **Envelope** | `ApiResponse<T>` — `{ "success", "message", "data" }` |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/sound-tracks` | None | — | Paged list, newest first |
| 2 | `GET` | `/api/v1/sound-tracks/featured` | None | — | Homepage carousel slides |
| 3 | `GET` | `/api/v1/sound-tracks/{id}` | None | — | One full sound track |
| 4 | `GET` | `/api/v1/sound-tracks/by-state` | None | — | Filter by `SINGLE` / `MULTI` |
| 5 | `GET` | `/api/v1/sound-tracks/by-sound-type` | None | — | Filter by free-text sound type |
| 6 | `GET` | `/api/v1/sound-tracks/by-topic` | None | — | Filter by topic id |
| 7 | `GET` | `/api/v1/sound-tracks/album-of-memories` | None | — | Only Album-of-Memories records |
| 8 | `GET` | `/api/v1/sound-tracks/search` | None | — | Global search across titles, tags, keywords, topic |
| 9 | `GET` | `/api/v1/sound-tracks/search/tag` | None | — | Tag-only search |
| 10 | `GET` | `/api/v1/sound-tracks/search/keyword` | None | — | Keyword-only search |
| 11 | `GET` | `/api/v1/sound-tracks/topics` | None | — | `SOUND` topic list for autocomplete |
| 12 | `GET` | `/api/v1/sound-tracks/sound-reklam-video` | None | — | The single sound-page banner video |

All twelve are public because `SecurityConfig` ends the public block with
`.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()` and no narrower rule and no
`@PreAuthorize` covers any of these handlers.

---

## Shared response shapes

### The envelope

Every endpoint on this page returns `ApiResponse<T>`:

```json
{
  "success": true,
  "message": "SoundTracks fetched successfully",
  "data": { }
}
```

`ApiResponse` is annotated `@JsonInclude(NON_NULL)` and the application sets
`spring.jackson.default-property-inclusion: non_null`, so **null fields are omitted** from responses.
A field documented below as nullable simply will not appear when it is null.

### The page wrapper

Endpoints 1, 2 and 4–10 put a Spring Data `Page<Response>` in `data`:

```json
{
  "success": true,
  "message": "SoundTracks fetched successfully",
  "data": {
    "content": [],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": false, "unsorted": true, "empty": true },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 0,
    "totalPages": 0,
    "last": true,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "sorted": false, "unsorted": true, "empty": true },
    "numberOfElements": 0,
    "empty": true
  }
}
```

Ordering is applied inside the JPQL query (`ORDER BY s.createdAt DESC`), not through `Pageable`, so
`sort.sorted` is `false` on every list endpoint **except** `/featured`, which sorts by
`featuredOrder ASC, id DESC` through `Pageable` and therefore reports `sorted: true`.

### `SoundTrackDtos.Response` — the sound-track object

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `number` (int64) | no | Primary key |
| `ckbCoverUrl` | `string` | yes | Sorani cover image URL |
| `kmrCoverUrl` | `string` | yes | Kurmanji cover image URL |
| `hoverCoverUrl` | `string` | yes | Cover shown on hover |
| `featureImageUrl` | `string` | yes | Wide hero picture for the homepage carousel. Written only by the internal featured PATCH; falls back to the cover when absent |
| `soundType` | `string` | no | Free-text type label, e.g. `lawk`, `heyran`, `poem`. **Not** an enum |
| `trackState` | `enum` | no | `SINGLE` or `MULTI` |
| `albumOfMemories` | `boolean` | no | `true` when the record belongs to the Album of Memories collection |
| `topicId` | `number` (int64) | yes | `PublishmentTopic.id` (entity type `SOUND`) |
| `topicNameCkb` | `string` | yes | Topic name, Sorani |
| `topicNameKmr` | `string` | yes | Topic name, Kurmanji |
| `contentLanguages` | `array<enum>` | no | Subset of `["CKB","KMR"]`. Decides which content blocks exist |
| `ckbContent` | `object` | yes | `{ "title", "description" }`, Sorani. Absent when `CKB` is not in `contentLanguages` or when both sub-fields were blank |
| `kmrContent` | `object` | yes | `{ "title", "description" }`, Kurmanji |
| `locations` | `array<string>` | no | Recording / origin places. Empty array when unset |
| `reader` | `string` | yes | Single reader / performer name |
| `directors` | `array<string>` | no | Director names |
| `terms` | `string` | yes | Dialect / terminology note |
| `thisProjectOfInstitute` | `boolean` | no | `true` when the recording is an Institute production |
| `tags` | `object` | no | `{ "ckb": [...], "kmr": [...] }` |
| `keywords` | `object` | no | `{ "ckb": [...], "kmr": [...] }` |
| `files` | `array<FileResponse>` | no | Audio files, ordered by `id ASC`. Empty array when none |
| `totalDurationSeconds` | `number` (int64) | no | Sum of `files[].durationSeconds`, computed on read |
| `totalSizeBytes` | `number` (int64) | no | Sum of `files[].sizeBytes`, computed on read |
| `albumName` | `string` | yes | Album title — meaningful for `MULTI` |
| `publishmentYear` | `number` (int32) | yes | Album-level publication year |
| `cdNumber` | `number` (int32) | yes | Disc number inside a multi-disc release |
| `totalTracks` | `number` (int32) | yes | Declared track count of the album |
| `attachments` | `array<AttachmentResponse>` | no | Album-level extras, ordered by `id ASC`. Available for `SINGLE` **and** `MULTI` |
| `createdAt` | `string` (date-time) | no | ISO-8601 local date-time, e.g. `2026-08-26T09:14:22` |
| `updatedAt` | `string` (date-time) | no | ISO-8601 local date-time |

`description` inside `ckbContent` / `kmrContent` is Tiptap-produced HTML. Any inline `data:` URI that the
editor submitted was uploaded to S3 and rewritten to a public URL before storage, so the string you
receive contains ordinary `https://` sources only.

### `FileResponse` — one audio file

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `number` (int64) | no | Primary key of `sound_track_files` |
| `fileUrl` | `string` | yes | Directly hosted audio (S3 / CDN) |
| `externalUrl` | `string` | yes | External page link (YouTube watch page, SoundCloud page …) |
| `embedUrl` | `string` | yes | Iframe / embed URL |
| `title` | `string` | yes | Title of this individual track |
| `fileType` | `enum` | no | `AUDIO`, `VIDEO`, `MP3`, `WAV`, `OGG`, `AAC`, `FLAC`, `OTHER` |
| `publishmentYear` | `number` (int32) | yes | Year this file was published |
| `sizeBytes` | `number` (int64) | no | `0` when unknown |
| `durationSeconds` | `number` (int64) | no | `0` when unknown |
| `durationMinutes` | `number` (double) | no | Derived: `durationSeconds / 60.0` |
| `bitRate` | `string` | yes | Free-text label, e.g. `320 kbps`, `24-bit` |
| `sampleRate` | `string` | yes | Free-text label, e.g. `44100 Hz` |
| `audioChannel` | `enum` | yes | `MONO` or `STEREO` |
| `form` | `string` | yes | Musical / linguistic form, e.g. `کێشدار`, `بێکێش` |
| `genre` | `string` | yes | Genre label |
| `recordingVenue` | `string` | yes | Where the sound was recorded |
| `brochures` | `array<BrochureResponse>` | no | Booklet / cover scans for this file, ordered by `id ASC` |

At least one of `fileUrl`, `externalUrl`, `embedUrl` is always populated — the write path rejects a file
that has none of the three.

> **Note:** `SoundTrackFile` also persists a `fileFormat` column (`"MP3"`, `"FLAC"` …), but neither
> `FileCreateRequest` nor `FileResponse` exposes it, so it is always `NULL` in practice and never
> reaches the API. Use `fileType` instead.

### `BrochureResponse`

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `number` (int64) | no | Primary key |
| `imageUrl` | `string` | no | Brochure image URL |
| `caption` | `string` | yes | e.g. `بەرگی پێشەوە` / "Front cover" |
| `brochureOrder` | `number` (int32) | yes | Zero-based position as submitted |

### `AttachmentResponse`

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `number` (int64) | no | Primary key |
| `fileUrl` | `string` | no | Attachment URL |
| `title` | `string` | yes | e.g. `دەفتەرچەی ئەلبوم` |
| `attachmentType` | `enum` | no | `PDF`, `VIDEO`, `IMAGE`, `AUDIO`, `OTHER` |
| `sizeBytes` | `number` (int64) | no | `0` when unknown |
| `mimeType` | `string` | yes | e.g. `application/pdf` |
| `attachmentOrder` | `number` (int32) | yes | Zero-based position as submitted |

---

## 1. `GET /api/v1/sound-tracks` — List everything

Returns a page of sound tracks ordered newest-first by `createdAt`. Read only; no side effects.
The result is cached in Redis under `khi:soundTracks::all:p{page}:s{size}` for 10 minutes and is evicted
whenever any sound track or the banner video is written.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

None.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size. Not clamped — see the errors table |

**Request body**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "SoundTracks fetched successfully",
  "data": {
    "content": [
      {
        "id": 41,
        "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-lawk_ckb_cover.jpg",
        "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8d2a4f10-6c31-4b7e-9a05-1c8f2b3d7e44-lawk_kmr_cover.jpg",
        "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/b71c9e04-5a2d-4f68-8c13-9e07d2a4b5f1-lawk_hover.jpg",
        "soundType": "lawk",
        "trackState": "SINGLE",
        "albumOfMemories": false,
        "topicId": 5,
        "topicNameCkb": "لاوک و حەیران",
        "topicNameKmr": "Lawik û Heyran",
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": {
          "title": "لاوکی شێخ عەبدولکەریم",
          "description": "<p>تۆمارێکی مەیدانی لە گوندی بێباوی، پارێزگای دهۆک، ساڵی ١٩٧٨.</p>"
        },
        "kmrContent": {
          "title": "Lawikê Şêx Evdilkerîm",
          "description": "<p>Tomarek meydanî ji gundê Bêbawî, parêzgeha Dihokê, sala 1978an.</p>"
        },
        "locations": ["دهۆک", "زاخۆ"],
        "reader": "کاروان کامیل",
        "directors": ["ئارام سەعید"],
        "terms": "کورمانجیی سەروو",
        "thisProjectOfInstitute": true,
        "tags": {
          "ckb": ["کلاسیک", "فۆلکلۆر"],
          "kmr": ["klasîk", "folklor"]
        },
        "keywords": {
          "ckb": ["شیعر", "دەنگی مەیدانی"],
          "kmr": ["helbest", "dengê meydanî"]
        },
        "files": [
          {
            "id": 88,
            "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/audio/c4f6a812-7d93-4e51-9b2a-0f5c8d1e3a67-lawke_sexe_evdilkerim.mp3",
            "title": "لاوکی شێخ عەبدولکەریم",
            "fileType": "MP3",
            "publishmentYear": 1978,
            "sizeBytes": 9840512,
            "durationSeconds": 246,
            "durationMinutes": 4.1,
            "bitRate": "320 kbps",
            "sampleRate": "44100 Hz",
            "audioChannel": "STEREO",
            "form": "کێشدار",
            "genre": "Folk",
            "recordingVenue": "ستودیۆی ئەنستیتۆ، سلێمانی",
            "brochures": [
              {
                "id": 130,
                "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/5e91c2b7-4a08-4d6f-b3c9-72e1a4f80d55-brochure_front.jpg",
                "caption": "بەرگی پێشەوە",
                "brochureOrder": 0
              }
            ]
          }
        ],
        "totalDurationSeconds": 246,
        "totalSizeBytes": 9840512,
        "attachments": [],
        "createdAt": "2026-08-20T11:42:07",
        "updatedAt": "2026-08-24T09:03:15"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": false, "unsorted": true, "empty": true },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 1,
    "totalPages": 1,
    "last": true,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "sorted": false, "unsorted": true, "empty": true },
    "numberOfElements": 1,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` — `PageRequest.of` throws `IllegalArgumentException` |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer (see [Notes & gotchas](#notes--gotchas)) |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sound-tracks?page=0&size=20"
```

---

## 2. `GET /api/v1/sound-tracks/featured` — Homepage carousel slides

Returns the sound tracks whose `featured` flag is on, ordered by `featuredOrder ASC` then `id DESC`
(records with a null `featuredOrder` sort last under PostgreSQL's default `NULLS LAST` for ascending
order). The flag itself is set from the admin dashboard via
[`PATCH /api/v1/sound-tracks/{id}/featured`](../internal/SOUNDTRACK_API.md#4-patch-apiv1sound-tracksidfeatured--feature--unfeature).
This endpoint is **not** cached.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | `int` | no | `0` | Clamped to `max(page, 0)` |
| `size` | `int` | no | `20` | Clamped to `min(max(size, 1), 100)` |

**Response `200 OK`**

Same `Page<Response>` shape as endpoint 1, with `message` = `"Featured sound tracks fetched successfully"`
and a populated `sort` block:

```json
{
  "success": true,
  "message": "Featured sound tracks fetched successfully",
  "data": {
    "content": [
      {
        "id": 41,
        "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-lawk_ckb_cover.jpg",
        "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9a3f7c21-8e04-4b5d-a1c7-63f0d9e2b478-lawk_hero_wide.jpg",
        "soundType": "lawk",
        "trackState": "SINGLE",
        "albumOfMemories": false,
        "contentLanguages": ["CKB", "KMR"],
        "ckbContent": { "title": "لاوکی شێخ عەبدولکەریم" },
        "kmrContent": { "title": "Lawikê Şêx Evdilkerîm" },
        "locations": [],
        "directors": [],
        "thisProjectOfInstitute": true,
        "tags": { "ckb": ["کلاسیک"], "kmr": ["klasîk"] },
        "keywords": { "ckb": [], "kmr": [] },
        "files": [],
        "totalDurationSeconds": 0,
        "totalSizeBytes": 0,
        "attachments": [],
        "createdAt": "2026-08-20T11:42:07",
        "updatedAt": "2026-08-24T09:03:15"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 1,
    "totalPages": 1,
    "last": true,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "numberOfElements": 1,
    "empty": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer |

Out-of-range `page` / `size` values cannot fail here — they are clamped.

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sound-tracks/featured?page=0&size=6"
```

---

## 3. `GET /api/v1/sound-tracks/{id}` — One sound track

Loads a single record with an entity graph covering languages, locations, directors, tags, keywords,
files and attachments, so the response is complete in one round trip. Not cached.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `int64` | yes | Sound-track primary key |

**Response `200 OK`**

`data` is a single `Response` object (the same shape shown in the `content[0]` of endpoint 1). A `MULTI`
album with attachments looks like this:

```json
{
  "success": true,
  "message": "SoundTrack fetched successfully",
  "data": {
    "id": 57,
    "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2b6d1f83-90ac-4e77-8d21-45c0f7a3b9e6-album_ckb.jpg",
    "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/47e0c9a5-31bd-4f2c-9807-e6b1d5834a2f-album_kmr.jpg",
    "soundType": "heyran",
    "trackState": "MULTI",
    "albumOfMemories": true,
    "topicId": 7,
    "topicNameCkb": "ئەلبومی یادەوەری",
    "topicNameKmr": "Albûma Bîranînan",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "حەیرانی هەورامان",
      "description": "<p>کۆمەڵێک تۆماری مەیدانی لە هەورامان، ١٩٦٩–١٩٧٤.</p>"
    },
    "kmrContent": {
      "title": "Heyranên Hewramanê",
      "description": "<p>Komek tomarên meydanî ji Hewramanê, 1969–1974.</p>"
    },
    "locations": ["هەڵەبجە", "هەورامان"],
    "reader": "شەهرام ناسری",
    "directors": ["سۆران محەمەد", "دلێر ئەحمەد"],
    "terms": "هەورامی",
    "thisProjectOfInstitute": true,
    "tags": { "ckb": ["حەیران", "هەورامی"], "kmr": ["heyran", "hewramî"] },
    "keywords": { "ckb": ["تۆماری مەیدانی"], "kmr": ["tomara meydanî"] },
    "files": [
      {
        "id": 101,
        "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/audio/f1d8b7c3-0a25-46e9-b4d7-8c61e903f2a5-heyran_01.mp3",
        "title": "حەیران — بەشی یەکەم",
        "fileType": "MP3",
        "publishmentYear": 1969,
        "sizeBytes": 12058624,
        "durationSeconds": 302,
        "durationMinutes": 5.033333333333333,
        "bitRate": "320 kbps",
        "sampleRate": "44100 Hz",
        "audioChannel": "MONO",
        "form": "بێکێش",
        "genre": "Classical",
        "recordingVenue": "هەورامان تەخت",
        "brochures": []
      },
      {
        "id": 102,
        "externalUrl": "https://www.youtube.com/watch?v=aBcD1234efg",
        "embedUrl": "https://www.youtube.com/embed/aBcD1234efg",
        "title": "حەیران — بەشی دووەم",
        "fileType": "VIDEO",
        "publishmentYear": 1974,
        "sizeBytes": 0,
        "durationSeconds": 415,
        "durationMinutes": 6.916666666666667,
        "audioChannel": "STEREO",
        "genre": "Classical",
        "brochures": [
          {
            "id": 144,
            "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2e8b40-19fa-4d35-8e71-b0c94a2f6d18-booklet_p2.jpg",
            "caption": "لاپەڕەی دووەم",
            "brochureOrder": 0
          }
        ]
      }
    ],
    "totalDurationSeconds": 717,
    "totalSizeBytes": 12058624,
    "albumName": "حەیرانی هەورامان",
    "publishmentYear": 1974,
    "cdNumber": 1,
    "totalTracks": 12,
    "attachments": [
      {
        "id": 63,
        "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/ae40c1d7-53b8-4f96-9a20-7d1e6c8b3f04-album_booklet.pdf",
        "title": "دەفتەرچەی ئەلبوم",
        "attachmentType": "PDF",
        "sizeBytes": 2417664,
        "mimeType": "application/pdf",
        "attachmentOrder": 0
      }
    ],
    "createdAt": "2026-07-02T14:20:51",
    "updatedAt": "2026-08-19T16:35:44"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `SOUND_NOT_FOUND` | No sound track with that id. `details.id` echoes the requested id |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `int64` |

`404` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/sound-tracks/9999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "SOUND_NOT_FOUND",
  "message": "SoundTrack not found",
  "messageEn": "SoundTrack not found",
  "messageKu": "سەدا نەدۆزرایەوە",
  "fieldErrors": null,
  "details": { "id": "9999" }
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/sound-tracks/57
```

---

## 4. `GET /api/v1/sound-tracks/by-state` — Filter by SINGLE / MULTI

Filters on the `track_state` column (index `idx_soundtrack_state`), newest first.
Cached under `khi:soundTracks::state:{STATE}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `state` | `enum` | **yes** | — | `SINGLE` or `MULTI`. Case-sensitive |
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"SoundTracks by state fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `state` omitted. `details.missingParameter = "state"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |
| `500` | `INTERNAL_ERROR` | `state` is present but not `SINGLE`/`MULTI` (see [Notes & gotchas](#notes--gotchas)) |

`400 MISSING_PARAMETER` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/sound-tracks/by-state",
  "method": "GET",
  "traceId": "b71c9e04-5a2d-4f68-8c13-9e07d2a4b5f1",
  "code": "MISSING_PARAMETER",
  "message": "Required parameter 'state' is missing.",
  "messageEn": "Required parameter 'state' is missing.",
  "messageKu": "پارامیتەری پێویستی 'state' نەگەیشتووە.",
  "fieldErrors": null,
  "details": {
    "missingParameter": "state",
    "expectedType": "TrackState",
    "hint": "Append '?state=<value>' to your request URL."
  }
}
```

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sound-tracks/by-state?state=MULTI&page=0&size=12"
```

---

## 5. `GET /api/v1/sound-tracks/by-sound-type` — Filter by sound type

`soundType` is a **free-text** column, not an enum. Matching is exact but case-insensitive
(`lower(s.soundType) = lower(:soundType)`) after trimming — `Lawk`, `lawk` and `LAWK` all match, but
`law` does not. Cached under `khi:soundTracks::soundType:{lowercased}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `soundType` | `string` | conditional | — | The sound type to match |
| `type` | `string` | conditional | — | Alias for `soundType`, used only when `soundType` is absent or blank |
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

Exactly one of `soundType` / `type` must be supplied and non-blank. `soundType` wins when both are given.

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"SoundTracks by sound type fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `SOUND_VALIDATION` | Both `soundType` and `type` missing or blank. `details.field = "soundType"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |

`400 SOUND_VALIDATION` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/sound-tracks/by-sound-type",
  "method": "GET",
  "traceId": "8d2a4f10-6c31-4b7e-9a05-1c8f2b3d7e44",
  "code": "SOUND_VALIDATION",
  "message": "Sound validation error",
  "messageEn": "Sound validation error",
  "messageKu": "هەڵەی پشکنینەوە لە داتای سەدا",
  "fieldErrors": null,
  "details": { "field": "soundType" }
}
```

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sound-tracks/by-sound-type?soundType=lawk"
curl -s "http://localhost:8080/api/v1/sound-tracks/by-sound-type?type=heyran&size=50"
```

---

## 6. `GET /api/v1/sound-tracks/by-topic` — Filter by topic

Filters on `topic_id` (index `idx_soundtrack_topic`), newest first. Use
[`/topics`](#11-get-apiv1sound-trackstopics--sound-topic-list) to discover valid ids.
Cached under `khi:soundTracks::topic:{topicId}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `topicId` | `int64` | **yes** | — | `PublishmentTopic.id` with `entityType = "SOUND"` |
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"SoundTracks by topic fetched successfully"`.
An unknown or non-`SOUND` `topicId` is **not** an error — it returns an empty page.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `topicId` omitted |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |
| `500` | `INTERNAL_ERROR` | `topicId` is not a valid `int64` |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sound-tracks/by-topic?topicId=5"
```

---

## 7. `GET /api/v1/sound-tracks/album-of-memories` — Album of Memories only

Returns every record whose `is_album_of_memories` flag is `true` (index `idx_soundtrack_album`),
newest first. The flag is independent of `trackState` — a `SINGLE` track can carry it too, although the
entity's `isMultiAlbumOfMemories()` helper treats `MULTI + albumOfMemories` as the canonical case.
Cached under `khi:soundTracks::album:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"Album of memories fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not an integer |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/sound-tracks/album-of-memories?page=0&size=20"
```

---

## 8. `GET /api/v1/sound-tracks/search` — Global search

One search box across twelve columns, all with case-insensitive `LIKE %q%` matching:

- `ckbContent.title`, `kmrContent.title`
- `ckbContent.description`, `kmrContent.description` (the raw Tiptap HTML, so a query can accidentally
  match markup such as `p` or `href`)
- `albumName`
- `terms`
- tags — `tagsCkb`, `tagsKmr`
- keywords — `keywordsCkb`, `keywordsKmr`
- topic names — `topic.nameCkb`, `topic.nameKmr`

Results are grouped by id and ordered `max(createdAt) DESC`.
Cached under `khi:soundTracks::search:{lowercased q}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `q` | `string` | **yes** | — | Search term. Trimmed before matching. No minimum length |
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"SoundTracks global search fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `q` omitted entirely |
| `400` | `BAD_REQUEST` | `q` present but blank (`?q=` or `?q=%20%20`). `details.field = "q"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/sound-tracks/search --data-urlencode "q=هاوار"
```

---

## 9. `GET /api/v1/sound-tracks/search/tag` — Tag search

Partial, case-insensitive match against `tagsCkb` **or** `tagsKmr`
(`lower(tag) LIKE lower('%…%')`). Grouped by id, ordered `max(createdAt) DESC`.
Cached under `khi:soundTracks::tag:{lowercased}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `tag` | `string` | conditional | — | Tag fragment to search for |
| `value` | `string` | conditional | — | Alias for `tag`, used only when `tag` is absent or blank |
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"SoundTracks by tag fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Both `tag` and `value` missing or blank. `details.field = "tag"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/sound-tracks/search/tag --data-urlencode "tag=کلاسیک"
```

---

## 10. `GET /api/v1/sound-tracks/search/keyword` — Keyword search

Same mechanics as the tag search but against `keywordsCkb` / `keywordsKmr`.
Cached under `khi:soundTracks::kw:{lowercased}:p{page}:s{size}`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `keyword` | `string` | conditional | — | Keyword fragment to search for |
| `value` | `string` | conditional | — | Alias for `keyword`, used only when `keyword` is absent or blank |
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size |

**Response `200 OK`**

`Page<Response>` as in endpoint 1, with `message` = `"SoundTracks by keyword fetched successfully"`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Both `keyword` and `value` missing or blank. `details.field = "keyword"` |
| `400` | `BAD_REQUEST` | `page < 0` or `size < 1` |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/sound-tracks/search/keyword --data-urlencode "keyword=شیعر"
```

---

## 11. `GET /api/v1/sound-tracks/topics` — SOUND topic list

Returns every `PublishmentTopic` whose `entityType` is `"SOUND"`, intended for the topic filter and the
admin autocomplete. Unpaged, unsorted (natural table order), not cached. `nameCkb` / `nameKmr` are
normalised to `""` rather than `null`, because the handler builds an immutable `Map.of(...)`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "SOUND topics fetched successfully",
  "data": [
    { "id": 5, "nameCkb": "لاوک و حەیران", "nameKmr": "Lawik û Heyran" },
    { "id": 7, "nameCkb": "ئەلبومی یادەوەری", "nameKmr": "Albûma Bîranînan" },
    { "id": 9, "nameCkb": "چیرۆکی زارەکی", "nameKmr": "" }
  ]
}
```

`data` is an empty array when no `SOUND` topics exist.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | Database failure |

**Example**

```bash
curl -s http://localhost:8080/api/v1/sound-tracks/topics
```

---

## 12. `GET /api/v1/sound-tracks/sound-reklam-video` — Sound page banner video

The sound section of the site shows a single promotional ("reklam") video above the archive. There is at
most **one** such record in the whole system: the service always reads
`findTopByOrderByIdAsc()`. Managed from the dashboard through the
[internal sound-reklam-video endpoints](../internal/SOUNDTRACK_API.md#5-post-apiv1sound-trackssound-reklam-video--create-the-banner-video).

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

None.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Sound reklam video fetched successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/d5e1a736-42c9-4b08-91f7-6a3c0d84e2b5-sound_reklam.mp4",
    "sizeBytes": 48234496,
    "mimeType": "video/mp4",
    "createdAt": "2026-05-11T10:05:33",
    "updatedAt": "2026-08-01T13:47:19"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No banner video has been uploaded yet |

`404` body — this key has real translations in all three bundles:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/sound-tracks/sound-reklam-video",
  "method": "GET",
  "traceId": "c4f6a812-7d93-4e51-9b2a-0f5c8d1e3a67",
  "code": "NOT_FOUND",
  "message": "Sound reklam video was not found.",
  "messageEn": "Sound reklam video was not found.",
  "messageKu": "ڤیدیۆی رێکلامی ساوند نەدۆزرایەوە.",
  "fieldErrors": null,
  "details": {}
}
```

Treat the `404` as "no banner configured" and hide the banner — it is the normal empty state, not a
failure.

**Example**

```bash
curl -s http://localhost:8080/api/v1/sound-tracks/sound-reklam-video
```

---

## Enums used by this API

### `TrackState` — `SoundTrack.trackState`

| Value | Meaning |
|-------|---------|
| `SINGLE` | One standalone recording. `albumName` / `cdNumber` / `totalTracks` are normally empty |
| `MULTI` | A multi-track album; `files` holds the track list |

### `Language` — `SoundTrack.contentLanguages`

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish (Sorani) — populates `ckbContent` |
| `KMR` | Northern Kurdish (Kurmanji) — populates `kmrContent` |

Deserialisation is case-insensitive and trims whitespace (`@JsonCreator Language.from`); serialisation
always emits the upper-case name.

### `FileType` — `files[].fileType`

| Value | Meaning |
|-------|---------|
| `AUDIO` | Generic audio; the value the public website contract uses when the container is unimportant |
| `VIDEO` | Generic video, kept for externally hosted mixed-media entries |
| `MP3` | MPEG-1 Audio Layer III |
| `WAV` | Waveform Audio |
| `OGG` | Ogg Vorbis |
| `AAC` | Advanced Audio Coding |
| `FLAC` | Free Lossless Audio Codec |
| `OTHER` | Anything else |

### `AudioChannel` — `files[].audioChannel`

| Value | Meaning |
|-------|---------|
| `MONO` | Single channel |
| `STEREO` | Two channels (left + right) |

### `AttachmentType` — `attachments[].attachmentType`

| Value | Meaning |
|-------|---------|
| `PDF` | Booklet, lyric sheet, liner notes |
| `VIDEO` | Promotional or documentary video |
| `IMAGE` | Standalone image (poster, artwork) |
| `AUDIO` | Extra audio clip (intro, interview) |
| `OTHER` | Anything else — also the default the server applies when the type is omitted on write |

> **Note:** `enums/publishment/SoundType.java` (`LAWK`, `HAIRAN`) and `enums/publishment/AlbumType.java`
> (`AUDIO`, `VIDEO`) exist in the codebase but no sound-track entity, DTO, repository or controller
> references either of them. `soundType` on the wire is an unconstrained string; do **not** validate it
> against the `SoundType` enum on the client.

---

## Notes & gotchas

**Redis caching.** All list and filter endpoints (1, 4–10) are `@Cacheable(value = "soundTracks")`
with a 10-minute TTL and the global key prefix `khi:`. `GET /{id}`, `/featured`, `/topics` and
`/sound-reklam-video` are **not** cached. Every write in the internal API — create, update, delete, and
all three banner-video mutations — runs `@CacheEvict(value = "soundTracks", allEntries = true)`, so the
public list endpoints refresh immediately after an admin edit. A change made straight in the database
will not be visible until the TTL expires.

**Two-phase read, no N+1.** List endpoints first fetch a page of bare ids (`Page<Long>`), then hydrate
them with `findAllByIds` and let `@BatchSize(50)` load every child collection in a handful of `IN`
queries. This is why `Pageable.sort` is empty: ordering is inside the JPQL. The service then re-sorts
the hydrated rows back into the id order the first query produced, so the page order is stable.

**Enum and numeric path/query parameters are unforgiving.** `GlobalExceptionHandler` registers a
catch-all `@ExceptionHandler(Exception.class)` but no handler for
`MethodArgumentTypeMismatchException`. A bad enum (`?state=SINGEL`), a non-numeric `{id}`, or a
non-numeric `page`/`size` therefore returns **`500 INTERNAL_ERROR`**, not `400`. Validate these on the
client before calling.

**`totalDurationSeconds` / `totalSizeBytes` are derived per request.** They are summed from the loaded
`files` array, not stored. A file whose duration was never supplied contributes `0`, so the album total
can understate the real running time.

**`durationMinutes` is a raw double.** `246 / 60.0` serialises as `4.1`, but `302 / 60.0` serialises as
`5.033333333333333`. Format it on the client; do not print it directly.

**Duration, bitrate and sample rate are author-supplied, not probed.** `MediaMetadataExtractor` exists in
the codebase and can read MP3/M4A duration and bitrate, but `SoundTrackService` never calls it. Only
`sizeBytes` is filled in automatically (from the uploaded part). `durationSeconds`, `bitRate`,
`sampleRate` and `audioChannel` are exactly what the dashboard typed in — expect them to be `0` / absent
on older records.

**Cover images can be missing.** `ckbCoverUrl`, `kmrCoverUrl` and `hoverCoverUrl` are each independently
nullable. Plan a placeholder, and remember `featureImageUrl` is only set for records that have been
featured at least once.

**Search matches raw HTML.** `descriptionCkb` / `descriptionKmr` are stored as Tiptap HTML and the global
search runs `LIKE` over them, so short Latin queries can produce false positives from tag names and S3
URLs. Prefer `/search/tag` or `/search/keyword` for precise filtering.

**Bilingual content follows `contentLanguages`.** If a record declares only `["CKB"]`, `kmrContent` is
`null` and omitted from the JSON — always fall back to the other language rather than assuming both
blocks exist.

**Error localisation.** All error bodies carry `messageEn` and `messageKu`; `message` follows
`Accept-Language` (`en`, `ckb`, `kmr`; default `en`). Message keys that are not present in the bundles
fall back to a per-`ErrorCode` phrase — for sound errors that is "SoundTrack not found",
"Sound validation error", "Invalid sound media data" and "SoundTrack data conflict".

> **Note:** the English bundle file is committed as `src/main/resources/i18n/ messages_en.properties`
> with a leading space in the filename, so the configured basename `classpath:i18n/messages` never loads
> it. English error text therefore comes from the hard-coded per-`ErrorCode` fallbacks in
> `GlobalExceptionHandler`, not from the properties file. This affects every domain, not just sound.

---

## Related documentation

- Counterpart (write operations): [`../internal/SOUNDTRACK_API.md`](../internal/SOUNDTRACK_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups `public`, `internal`, `all`)
