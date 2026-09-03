# Sound Tracks API — Internal (Authenticated)

Everything the admin dashboard needs to publish and curate the audio archive: creating a `SINGLE`
recording or a `MULTI`-track album, replacing its audio files, brochures and attachments, deleting a
record, promoting one to the homepage carousel, and managing the single promotional banner video that
sits above the sound section.

All write endpoints are `multipart/form-data` except the featured toggle, which is plain JSON. The audio
payload is described by a **JSON blob in a form part named `data`** plus index-matched binary parts —
see [The multipart contract](#the-multipart-contract) before writing any client code.

Public read endpoints live in [`../external/SOUNDTRACK_API.md`](../external/SOUNDTRACK_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/sound-tracks` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/sound/SoundTrackController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/sound/SoundTrackService.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` (featured toggle) |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/sound/SoundTrackDtos.java`, `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java` |
| **Entities** | `SoundTrack`, `SoundTrackContent`, `SoundTrackFile`, `SoundTrackBrochure`, `SoundTrackAttachment`, `SoundReklamVideo`, `SoundTrackLog`, `PublishmentTopic` |
| **Envelope** | `ApiResponse<T>` on 200/201; empty body on 204 |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either as a header or as the HttpOnly cookie named by `${JWT_COOKIE_NAME}`:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

Sessions are stateless (`SessionCreationPolicy.STATELESS`). Granted authorities are `ROLE_<NAME>` plus
the permissions `user:create`, `user:read`, `user:update`, `user:delete`. Roles in ascending order:
`GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`. Roles are **not** hierarchical — `hasRole('ADMIN')` does not
match a `SUPER_ADMIN` principal.

Missing or invalid credentials produce `401 UNAUTHORIZED`; an authenticated principal without the
required role produces `403 FORBIDDEN` (`ErrorCode.FORBIDDEN`).

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/sound-tracks` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a sound track |
| 2 | `PUT` | `/api/v1/sound-tracks/{id}` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Partial update / replace media |
| 3 | `DELETE` | `/api/v1/sound-tracks/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete a sound track and all its children |
| 4 | `PATCH` | `/api/v1/sound-tracks/{id}/featured` | JWT | `ADMIN` only | Feature / unfeature on the homepage |
| 5 | `POST` | `/api/v1/sound-tracks/sound-reklam-video` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create the banner video (once) |
| 6 | `PATCH` | `/api/v1/sound-tracks/sound-reklam-video` | JWT | **any authenticated user** | Replace the banner video |
| 7 | `DELETE` | `/api/v1/sound-tracks/sound-reklam-video` | JWT | `ADMIN`, `SUPER_ADMIN` | Remove the banner video |

### How these roles are derived

`SecurityConfig` grants, in order of evaluation:

- `POST /api/v1/sound-tracks/**` → `hasAnyRole("EMPLOYEE","ADMIN","SUPER_ADMIN")` — covers 1 and 5.
- `PUT /api/v1/sound-tracks/**` → `hasAnyRole("EMPLOYEE","ADMIN","SUPER_ADMIN")` — covers 2.
- `DELETE /api/v1/sound-tracks/**` → `hasAnyRole("ADMIN","SUPER_ADMIN")` — covers 3 and 7.
- Nothing else matches `PATCH /api/v1/sound-tracks/**`, so 4 and 6 fall through to
  `.anyRequest().authenticated()`.

> **Note (discrepancy):** `SecurityConfig` has a `PATCH` rule for `/api/v1/videos/**` but **no** `PATCH`
> rule for `/api/v1/sound-tracks/**`. Two consequences, both real behaviour today:
>
> 1. `PATCH /api/v1/sound-tracks/{id}/featured` is protected only by the method-level
>    `@PreAuthorize("hasRole('ADMIN')")`, which requires the `ROLE_ADMIN` authority exactly. A
>    `SUPER_ADMIN` calling it gets `403 FORBIDDEN`, even though `SUPER_ADMIN` may delete the same record.
> 2. `PATCH /api/v1/sound-tracks/sound-reklam-video` carries no `@PreAuthorize` at all, so **any**
>    authenticated principal — including a self-registered `GUEST` — can replace the site-wide banner
>    video, while creating it needs `EMPLOYEE`+ and deleting it needs `ADMIN`+.

---

## The multipart contract

Endpoints 1 and 2 accept `multipart/form-data` with these parts:

| Part | Content type | Cardinality | Required | Description |
|------|--------------|-------------|----------|-------------|
| `data` | `application/json` (or `text/plain`) | 1 | **yes** | The `CreateRequest` / `UpdateRequest` JSON document |
| `ckbCoverImage` | `image/*` | 0–1 | no | Sorani cover image binary |
| `kmrCoverImage` | `image/*` | 0–1 | no | Kurmanji cover image binary |
| `hoverCoverImage` | `image/*` | 0–1 | no | Hover-state cover image binary |
| `audioFiles` | `audio/*`, `video/*`, any | 0–n | no | Audio binaries, consumed in order against `data.files[]` |
| `brochureFiles` | `image/*` | 0–n | no | Brochure images, consumed as one flat list across every `data.files[].brochures[]` |
| `attachmentFiles` | any | 0–n | no | Attachment binaries, consumed in order against `data.attachments[]` |

Repeat the part name once per file for the list parts (`-F "audioFiles=@a.mp3" -F "audioFiles=@b.mp3"`).

### How `data` is parsed

The handler receives `data` as a raw `String` and parses it itself with the application's
`ObjectMapper` bean (`khi_app/config/JacksonConfig.java`), **not** through Spring's message converters.
That mapper:

- rejects unknown JSON properties with `UnrecognizedPropertyException` → `400 BAD_REQUEST`,
  `details.unknownField` naming the offender;
- makes one exception: a property literally named `id` at a level that has no `id` field is silently
  skipped, so a response-shaped payload can be echoed back for an update;
- has `JavaTimeModule` registered and `WRITE_DATES_AS_TIMESTAMPS` disabled.

`MultipartJsonConfig` is intentionally empty — Spring Boot needs no converter registration for JSON
parts; this endpoint does the parsing by hand anyway.

> **Note (discrepancy):** the controller declares `throws Exception` and does not catch Jackson errors.
> An unknown property is handled (`400`), but **malformed JSON** in `data` — a stray comma, an unclosed
> brace, a wrong scalar type — throws a plain `JsonProcessingException`, which only the catch-all
> `@ExceptionHandler(Exception.class)` sees, so the client gets `500 INTERNAL_ERROR` instead of `400`.
> Likewise an entirely **missing `data` part** raises `MissingServletRequestPartException`, which is not
> `MultipartException` and has no handler, so that is also `500`.

### Index matching between `data` and binaries

```
audioFiles[0]      ↔  data.files[0]        an uploaded binary always wins over data.files[0].fileUrl
audioFiles[1]      ↔  data.files[1]
brochureFiles      one flat queue consumed left-to-right across every files[i].brochures[j]
attachmentFiles[0] ↔  data.attachments[0]
```

Empty parts are skipped rather than counted: the service walks the list with `nextNonEmpty` /
`advanceIndex`, so a zero-byte part never consumes a slot. The loop length is
`max(dtoCount, nonEmptyUploadCount)` — supplying more binaries than DTO entries creates extra records
with no metadata (see the gotchas), and supplying more DTO entries than binaries is normal when those
entries carry `fileUrl` / `externalUrl` / `embedUrl` instead.

### Upload limits and storage

`max-file-size` and `max-request-size` are both **1GB**; Tomcat's `max-http-form-post-size` and
`max-swallow-size` match, and `max-parameter-count` is 10000. Files above the limit produce
`413 PAYLOAD_TOO_LARGE`.

Every binary goes to AWS S3 (region `us-east-1`, bucket `s3-khiwebsite`, base folder `khi-web-folders`).
The subfolder is chosen from the part's content type: `image/*` → `images/`, `video/*` → `video/`,
`audio/*` → `audio/`, anything else → `files/`. The stored key is
`khi-web-folders/<folder>/<uuid>-<sanitised-filename>` and the persisted URL is
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/<key>`. Filenames are sanitised to
`[a-zA-Z0-9._-]`, so Kurdish filenames become underscores — the URL is not a display name.

---

## Request DTO reference

### `CreateRequest` / `UpdateRequest` (the `data` part)

| Field | Type | Create | Update | Constraints¹ | Description |
|-------|------|--------|--------|--------------|-------------|
| `soundType` | `string` | **required** | optional | `@Size(max=100)`; DB `varchar(100) NOT NULL` | Free-text type label (`lawk`, `heyran`, `poem`…). Trimmed. On update a blank value is ignored |
| `trackState` | `enum` | **required** | optional | `SINGLE` \| `MULTI`; DB `NOT NULL` | Track shape |
| `albumOfMemories` | `boolean` | optional | optional | — | `null` on create stores `false`; `null` on update leaves it alone |
| `ckbCoverUrl` | `string` | optional | optional | `@Size(max=1200)`; DB `varchar(1000)` | Used only when no `ckbCoverImage` part is sent |
| `kmrCoverUrl` | `string` | optional | optional | `@Size(max=1200)`; DB `varchar(1000)` | Used only when no `kmrCoverImage` part is sent |
| `hoverCoverUrl` | `string` | optional | optional | `@Size(max=1200)`; DB `varchar(1000)` | Used only when no `hoverCoverImage` part is sent |
| `topicId` | `int64` | optional | optional | must exist with `entityType = "SOUND"` | Attach an existing topic |
| `newTopic` | `object` | optional | optional | `{ "nameCkb", "nameKmr" }`, at least one non-blank | Creates a `SOUND` topic inline. Ignored when `topicId` is present |
| `clearTopic` | `boolean` | — | optional | default `false` | `true` detaches the topic and suppresses `topicId` / `newTopic` |
| `contentLanguages` | `array<enum>` | **required, non-empty** | optional | subset of `["CKB","KMR"]` | Decides which content blocks are kept |
| `ckbContent` | `object` | optional | optional | `title` `@Size(max=200)` (DB 200), `description` `TEXT` | `{ "title", "description" }` |
| `kmrContent` | `object` | optional | optional | same | `{ "title", "description" }` |
| `locations` | `array<string>` | optional | optional | DB `varchar(255)` per row | Full replacement on update. Blank entries dropped, order preserved, duplicates collapsed |
| `reader` | `string` | optional | optional | `@Size(max=255)` | Single performer. On update: `null` = unchanged, `""` = clear |
| `directors` | `array<string>` | optional | optional | DB `varchar(255)` per row | Full replacement on update |
| `terms` | `string` | optional | optional | `@Size(max=200)` | Dialect / terminology note |
| `thisProjectOfInstitute` | `boolean` | optional | optional | `@JsonProperty` — the wire name is exactly this | Create reads a primitive (`null` → `false`); update reads a `Boolean` (`null` → unchanged) |
| `tags` | `object` | optional | optional | `{ "ckb": [...], "kmr": [...] }`, DB `varchar(60)` per row | Each side replaced independently, and only when that side is non-null |
| `keywords` | `object` | optional | optional | `{ "ckb": [...], "kmr": [...] }`, DB `varchar(100)` per row | Same rule as `tags` |
| `files` | `array<FileCreateRequest>` | optional | optional | see below | On update this is an **authoritative replacement list** |
| `albumName` | `string` | optional | optional | `@Size(max=300)` | Album title |
| `publishmentYear` | `int32` | optional | optional | — | Album-level year |
| `cdNumber` | `int32` | optional | optional | — | Disc number |
| `totalTracks` | `int32` | optional | optional | — | Declared track count |
| `attachments` | `array<AttachmentRequest>` | optional | optional | see below | Available for `SINGLE` and `MULTI` |

¹ **The `jakarta.validation` annotations on these DTOs are never executed.** `data` arrives as a `String`
and is parsed manually, and no handler parameter carries `@Valid` / `@Validated`. Only the checks written
by hand in `SoundTrackService` run. Anything longer than the DB column will therefore fail at the
database with `409 CONFLICT` rather than `400`. Treat the `@Size` values above as the DB limits you must
enforce client-side.

### `FileCreateRequest` — an entry in `files[]`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `int64` | update only | Identifies an **existing** file of this sound track to keep and edit. Omit for a new file |
| `fileUrl` | `string` | conditional | Direct hosted URL. Ignored when a matching `audioFiles` binary is present |
| `externalUrl` | `string` | conditional | External page link (YouTube watch page…) |
| `embedUrl` | `string` | conditional | Iframe / embed URL |
| `title` | `string` | no | DB `varchar(300)` |
| `fileType` | `enum` | **effectively yes** | `AUDIO`, `VIDEO`, `MP3`, `WAV`, `OGG`, `AAC`, `FLAC`, `OTHER`. Column is `NOT NULL` — omitting it on a new file causes a `409 CONFLICT` from the database |
| `publishmentYear` | `int32` | no | Year for this specific file |
| `sizeBytes` | `int64` | no | Overwritten by the real size when a binary is uploaded |
| `durationSeconds` | `int64` | no | Author-supplied; never probed from the audio |
| `bitRate` | `string` | no | Free text, `varchar(50)`, e.g. `320 kbps` |
| `sampleRate` | `string` | no | Free text, `varchar(50)`, e.g. `44100 Hz` |
| `audioChannel` | `enum` | no | `MONO` \| `STEREO` |
| `form` | `string` | no | `varchar(150)`, e.g. `کێشدار` |
| `genre` | `string` | no | `varchar(100)` |
| `recordingVenue` | `string` | no | `varchar(500)` |
| `brochures` | `array<BrochureRequest>` | no | Booklet scans for this file |

At least one of `fileUrl`, `externalUrl`, `embedUrl` — or a matching `audioFiles` binary — must resolve,
otherwise the request fails with `SOUND_VALIDATION`.
`durationMinutes` is accepted-and-ignored (`@JsonIgnoreProperties`), so a `FileResponse` can be sent back
verbatim.

### `BrochureRequest` — an entry in `files[].brochures[]`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `int64` | update only | Existing brochure of **this file**; must not repeat within the request |
| `imageUrl` | `string` | conditional | Used when no `brochureFiles` binary is available for this slot |
| `caption` | `string` | no | `varchar(300)` |

`brochureOrder` is accepted-and-ignored; the server always assigns the zero-based array index.
An entry that resolves to neither an upload nor a non-blank `imageUrl` is **silently skipped**.

### `AttachmentRequest` — an entry in `attachments[]`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `int64` | update only | Existing attachment of this sound track; must not repeat |
| `fileUrl` | `string` | conditional | Used when no `attachmentFiles` binary is available for this slot |
| `title` | `string` | no | `varchar(300)` |
| `attachmentType` | `enum` | no | `PDF`, `VIDEO`, `IMAGE`, `AUDIO`, `OTHER`. Defaults to `OTHER` when omitted on a new attachment |
| `sizeBytes` | `int64` | no | Overwritten by the real size when a binary is uploaded |
| `mimeType` | `string` | no | `varchar(100)` |

`attachmentOrder` is accepted-and-ignored; the server assigns the zero-based array index.
An entry that resolves to no URL at all is **silently skipped**.

The response shape (`Response`, `FileResponse`, `BrochureResponse`, `AttachmentResponse`) is documented
field by field in [the external doc](../external/SOUNDTRACK_API.md#shared-response-shapes); all five write
endpoints below return exactly those objects.

---

## 1. `POST /api/v1/sound-tracks` — Create a sound track

Creates one `SoundTrack` with its files, per-file brochures and album attachments in a single
transaction. Every supplied binary is uploaded to S3 first; inline `data:` URIs inside
`ckbContent.description` / `kmrContent.description` are extracted by `TiptapHtmlProcessor`, uploaded and
rewritten to public URLs before the HTML is stored. A `CREATED` row is written to `sound_track_logs`
(actor is hard-coded to `"system"`), and the whole `soundTracks` Redis cache region is evicted.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Path parameters**

None.

**Query parameters**

None.

**Form parts**

| Part | Content type | Required | Description |
|------|--------------|----------|-------------|
| `data` | `application/json` | **yes** | `CreateRequest` JSON |
| `ckbCoverImage` | `image/*` | no | Sorani cover; wins over `data.ckbCoverUrl` |
| `kmrCoverImage` | `image/*` | no | Kurmanji cover; wins over `data.kmrCoverUrl` |
| `hoverCoverImage` | `image/*` | no | Hover cover; wins over `data.hoverCoverUrl` |
| `audioFiles` | any | no | Repeatable; index-matched to `data.files[]` |
| `brochureFiles` | `image/*` | no | Repeatable; one flat queue across all `data.files[].brochures[]` |
| `attachmentFiles` | any | no | Repeatable; index-matched to `data.attachments[]` |

**Request body — the `data` part**

Server-side validation order:

1. `soundType` blank → `400 SOUND_VALIDATION`, `details.field = "soundType"`.
2. `trackState` null → `400 SOUND_VALIDATION`, `details.field = "trackState"`.
3. `contentLanguages` null/empty → `400 SOUND_VALIDATION` (`soundTrack.languages.required`),
   `details.field = "contentLanguages"`.
4. Topic resolution (`topicId` → 404 / type mismatch; `newTopic` → both names blank is an error).
5. Per file: no resolvable source → `400 SOUND_VALIDATION`, `details.index = i`.

A `MULTI` album with two audio uploads, one brochure and one PDF attachment:

```json
{
  "soundType": "heyran",
  "trackState": "MULTI",
  "albumOfMemories": true,
  "topicId": 7,
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
  "tags": {
    "ckb": ["حەیران", "هەورامی"],
    "kmr": ["heyran", "hewramî"]
  },
  "keywords": {
    "ckb": ["تۆماری مەیدانی"],
    "kmr": ["tomara meydanî"]
  },
  "albumName": "حەیرانی هەورامان",
  "publishmentYear": 1974,
  "cdNumber": 1,
  "totalTracks": 12,
  "files": [
    {
      "title": "حەیران — بەشی یەکەم",
      "fileType": "MP3",
      "publishmentYear": 1969,
      "durationSeconds": 302,
      "bitRate": "320 kbps",
      "sampleRate": "44100 Hz",
      "audioChannel": "MONO",
      "form": "بێکێش",
      "genre": "Classical",
      "recordingVenue": "هەورامان تەخت",
      "brochures": [
        { "caption": "بەرگی پێشەوە" }
      ]
    },
    {
      "title": "حەیران — بەشی دووەم",
      "fileType": "MP3",
      "publishmentYear": 1974,
      "durationSeconds": 415,
      "bitRate": "320 kbps",
      "sampleRate": "44100 Hz",
      "audioChannel": "STEREO",
      "genre": "Classical",
      "recordingVenue": "هەورامان تەخت"
    }
  ],
  "attachments": [
    {
      "title": "دەفتەرچەی ئەلبوم",
      "attachmentType": "PDF",
      "mimeType": "application/pdf"
    }
  ]
}
```

A `SINGLE` track that links out instead of hosting audio needs no binaries at all:

```json
{
  "soundType": "lawk",
  "trackState": "SINGLE",
  "contentLanguages": ["CKB"],
  "ckbContent": { "title": "لاوکی شێخ عەبدولکەریم" },
  "newTopic": { "nameCkb": "لاوک و حەیران", "nameKmr": "Lawik û Heyran" },
  "files": [
    {
      "title": "لاوکی شێخ عەبدولکەریم",
      "fileType": "VIDEO",
      "externalUrl": "https://www.youtube.com/watch?v=aBcD1234efg",
      "embedUrl": "https://www.youtube.com/embed/aBcD1234efg",
      "durationSeconds": 246
    }
  ]
}
```

**Response `201 Created`**

```json
{
  "success": true,
  "message": "SoundTrack created successfully",
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
        "brochures": [
          {
            "id": 144,
            "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2e8b40-19fa-4d35-8e71-b0c94a2f6d18-booklet_p1.jpg",
            "caption": "بەرگی پێشەوە",
            "brochureOrder": 0
          }
        ]
      },
      {
        "id": 102,
        "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/audio/a90b4c17-6f28-4d3a-85e1-2c7f0b9d6e34-heyran_02.mp3",
        "title": "حەیران — بەشی دووەم",
        "fileType": "MP3",
        "publishmentYear": 1974,
        "sizeBytes": 16613376,
        "durationSeconds": 415,
        "durationMinutes": 6.916666666666667,
        "bitRate": "320 kbps",
        "sampleRate": "44100 Hz",
        "audioChannel": "STEREO",
        "genre": "Classical",
        "recordingVenue": "هەورامان تەخت",
        "brochures": []
      }
    ],
    "totalDurationSeconds": 717,
    "totalSizeBytes": 28672000,
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
    "createdAt": "2026-08-26T09:14:22",
    "updatedAt": "2026-08-26T09:14:22"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `SOUND_VALIDATION` | `soundType` blank, `trackState` null, `contentLanguages` empty, `newTopic` with both names blank, topic id belongs to another `entityType`, or a `files[i]` with no resolvable source |
| `400` | `BAD_REQUEST` | Unknown property in `data` (`details.unknownField`), or a malformed multipart envelope |
| `401` | `UNAUTHORIZED` | No / invalid token |
| `403` | `FORBIDDEN` | Role below `EMPLOYEE` |
| `404` | `NOT_FOUND` | `topicId` does not exist (`topic.not_found`, `details.id`) |
| `409` | `CONFLICT` | Database constraint hit — most often `fileType` omitted on a new file, or a string longer than its column |
| `413` | `PAYLOAD_TOO_LARGE` | A part or the whole request exceeds 1GB |
| `500` | `INTERNAL_ERROR` | Malformed JSON in `data`, missing `data` part, or an unexpected failure |
| `502` | `STORAGE_ERROR` | Reading a part failed (`IOException`) — message key `sound.media_upload_failed`, `details.reason` |

`400 SOUND_VALIDATION` for a file with no source:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/sound-tracks",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "SOUND_VALIDATION",
  "message": "Sound validation error",
  "messageEn": "Sound validation error",
  "messageKu": "هەڵەی پشکنینەوە لە داتای سەدا",
  "fieldErrors": null,
  "details": {
    "index": 1,
    "message": "هەر فایلێک پێویستی بە لانیکەم fileUrl، externalUrl، یان embedUrl هەیە"
  }
}
```

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/sound-tracks \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F 'data=@create-heyran.json;type=application/json' \
  -F "ckbCoverImage=@album_ckb.jpg;type=image/jpeg" \
  -F "kmrCoverImage=@album_kmr.jpg;type=image/jpeg" \
  -F "audioFiles=@heyran_01.mp3;type=audio/mpeg" \
  -F "audioFiles=@heyran_02.mp3;type=audio/mpeg" \
  -F "brochureFiles=@booklet_p1.jpg;type=image/jpeg" \
  -F "attachmentFiles=@album_booklet.pdf;type=application/pdf"
```

---

## 2. `PUT /api/v1/sound-tracks/{id}` — Update a sound track

A partial update: only what appears in `data` changes — with the important exceptions listed under
[Update semantics](#update-semantics). Writes an `UPDATED` log row and evicts the whole `soundTracks`
cache region.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `int64` | yes | Sound-track primary key |

**Query parameters**

None.

**Form parts**

Identical to [endpoint 1](#1-post-apiv1sound-tracks--create-a-sound-track).

### Update semantics

**Scalars.** `soundType` (non-blank only), `trackState`, `albumOfMemories`, `terms`,
`thisProjectOfInstitute`, `albumName`, `publishmentYear`, `cdNumber`, `totalTracks` are written only
when the JSON property is present and non-null. There is no way to null them back out.

**`reader`.** Three-state: omit to leave unchanged, send `""` to clear, send a name to set it.

**Covers.** A cover is rewritten when either the binary part is present **or** the matching
`*CoverUrl` property is present. Sending `"ckbCoverUrl": ""` clears the cover to `null`; the binary always
wins when both are supplied.

**Topic.** `clearTopic: true` detaches the topic and suppresses `topicId` / `newTopic` entirely.
Otherwise `topicId` (or `newTopic`) replaces it; omitting all three leaves the existing topic in place.

**Languages and content — destructive.** If `contentLanguages` is present it replaces the stored set.
Then, for each of `CKB` / `KMR`: a language **absent** from the (possibly new) set has its content block
**set to `null` and deleted**, whether or not you sent that block; a language present in the set is
merged field-by-field only when its DTO block is non-null. Sending `"contentLanguages": ["CKB"]` on a
bilingual record therefore permanently drops the Kurmanji title and description.

**Collections.** `locations` and `directors` are cleared and refilled when present. `tags` and
`keywords` replace `ckb` and `kmr` independently — sending `{"tags": {"ckb": []}}` empties the Sorani tags
and leaves the Kurmanji ones untouched.

**`files` — an authoritative replacement list.** The merge runs when `data.files` is present **or** any
non-empty `audioFiles` part exists. For each index `i` of
`max(files.length, nonEmptyAudioFileCount)`:

- `files[i].id` present → must name a file that already belongs to **this** sound track and must not
  repeat within the request, else `400 SOUND_VALIDATION` with `details.field = "files[i].id"`.
- `files[i].id` absent → a brand-new file row.
- A matching `audioFiles` binary replaces `fileUrl` and **nulls `externalUrl` and `embedUrl`**, and sets
  the real `sizeBytes`.
- No binary but the DTO carries any of the three URLs → all three are set from the DTO (so omitting
  `embedUrl` while sending `fileUrl` clears the embed).
- Neither → the existing file keeps its source; a *new* file in that position fails with
  `soundTrack.file.source.required`.
- Metadata is applied per property when non-null. `sizeBytes` is taken from the DTO only when there is no
  upload and the file is new or the value is non-zero; `durationSeconds` likewise (new file or non-zero).
- `files[i].brochures` is merged only when the property is present, with the same id rules scoped to that
  file; `brochureOrder` is reassigned from the array index.

**Any existing file whose `id` you do not resend is removed** (`orphanRemoval = true`), together with its
brochures. Send the full list every time, including the `id` of every file you want to keep.

**`attachments`** follow the same id-aware merge and the same orphan-removal rule.

**Request body — the `data` part**

Keep two files (editing one), add a third from a new upload, and refresh the Kurmanji title:

```json
{
  "soundType": "heyran",
  "trackState": "MULTI",
  "totalTracks": 13,
  "contentLanguages": ["CKB", "KMR"],
  "kmrContent": { "title": "Heyranên Hewramanê (çapa nû)" },
  "files": [
    { "id": 101 },
    {
      "id": 102,
      "title": "حەیران — بەشی دووەم (چاککراو)",
      "bitRate": "256 kbps",
      "brochures": [
        { "id": 144, "caption": "بەرگی پێشەوە — چاککراو" }
      ]
    },
    {
      "title": "حەیران — بەشی سێیەم",
      "fileType": "MP3",
      "publishmentYear": 1974,
      "durationSeconds": 388,
      "audioChannel": "STEREO"
    }
  ],
  "attachments": [
    { "id": 63 }
  ]
}
```

Clear the reader and detach the topic without touching anything else:

```json
{
  "reader": "",
  "clearTopic": true
}
```

**Response `200 OK`**

```json
{
  "success": true,
  "message": "SoundTrack updated successfully",
  "data": {
    "id": 57,
    "soundType": "heyran",
    "trackState": "MULTI",
    "albumOfMemories": true,
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": { "title": "حەیرانی هەورامان" },
    "kmrContent": { "title": "Heyranên Hewramanê (çapa nû)" },
    "locations": ["هەڵەبجە", "هەورامان"],
    "directors": ["سۆران محەمەد", "دلێر ئەحمەد"],
    "thisProjectOfInstitute": true,
    "tags": { "ckb": ["حەیران"], "kmr": ["heyran"] },
    "keywords": { "ckb": [], "kmr": [] },
    "files": [
      { "id": 101, "fileType": "MP3", "sizeBytes": 12058624, "durationSeconds": 302, "durationMinutes": 5.033333333333333, "brochures": [] },
      { "id": 102, "fileType": "MP3", "title": "حەیران — بەشی دووەم (چاککراو)", "bitRate": "256 kbps", "sizeBytes": 16613376, "durationSeconds": 415, "durationMinutes": 6.916666666666667, "brochures": [{ "id": 144, "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6c2e8b40-19fa-4d35-8e71-b0c94a2f6d18-booklet_p1.jpg", "caption": "بەرگی پێشەوە — چاککراو", "brochureOrder": 0 }] },
      { "id": 118, "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/audio/7b0e5d29-c48f-4a13-96d2-3e8a1f0c7b56-heyran_03.mp3", "title": "حەیران — بەشی سێیەم", "fileType": "MP3", "publishmentYear": 1974, "sizeBytes": 15532032, "durationSeconds": 388, "durationMinutes": 6.466666666666667, "audioChannel": "STEREO", "brochures": [] }
    ],
    "totalDurationSeconds": 1105,
    "totalSizeBytes": 44204032,
    "albumName": "حەیرانی هەورامان",
    "publishmentYear": 1974,
    "cdNumber": 1,
    "totalTracks": 13,
    "attachments": [
      { "id": 63, "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/ae40c1d7-53b8-4f96-9a20-7d1e6c8b3f04-album_booklet.pdf", "title": "دەفتەرچەی ئەلبوم", "attachmentType": "PDF", "sizeBytes": 2417664, "mimeType": "application/pdf", "attachmentOrder": 0 }
    ],
    "createdAt": "2026-08-26T09:14:22",
    "updatedAt": "2026-08-26T11:02:48"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `SOUND_VALIDATION` | Unknown/duplicate `files[i].id`, `files[i].brochures[j].id` or `attachments[i].id`; a new file with no source; `newTopic` with both names blank; topic `entityType` mismatch |
| `400` | `BAD_REQUEST` | Unknown property in `data` |
| `401` | `UNAUTHORIZED` | No / invalid token |
| `403` | `FORBIDDEN` | Role below `EMPLOYEE` |
| `404` | `SOUND_NOT_FOUND` | No sound track with that id |
| `404` | `NOT_FOUND` | `topicId` does not exist |
| `409` | `CONFLICT` | Database constraint hit (missing `fileType` on a new file, over-length string) |
| `413` | `PAYLOAD_TOO_LARGE` | Request or part above 1GB |
| `500` | `INTERNAL_ERROR` | Malformed JSON in `data`, missing `data` part, or non-numeric `{id}` |
| `502` | `STORAGE_ERROR` | `IOException` while reading a part |

`400` for a stale file id:

```json
{
  "timestamp": "2026-08-26T11:02:48Z",
  "status": 400,
  "path": "/api/v1/sound-tracks/57",
  "method": "PUT",
  "traceId": "8d2a4f10-6c31-4b7e-9a05-1c8f2b3d7e44",
  "code": "SOUND_VALIDATION",
  "message": "Sound validation error",
  "messageEn": "Sound validation error",
  "messageKu": "هەڵەی پشکنینەوە لە داتای سەدا",
  "fieldErrors": null,
  "details": {
    "field": "files[0].id",
    "id": 999,
    "message": "ئایدی فایل لەم سەدايەدا نەدۆزرایەوە یان دووبارەیە"
  }
}
```

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/sound-tracks/57 \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F 'data=@update-heyran.json;type=application/json' \
  -F "audioFiles=@heyran_03.mp3;type=audio/mpeg"
```

---

## 3. `DELETE /api/v1/sound-tracks/{id}` — Delete a sound track

Removes the sound track and, by JPA cascade with `orphanRemoval`, every `SoundTrackFile`,
`SoundTrackBrochure` and `SoundTrackAttachment` under it, plus the element-collection rows for
languages, locations, directors, tags and keywords. A `DELETED` row is written to `sound_track_logs`
first, holding a snapshot of the id and title so the audit trail survives the hard delete. Evicts the
whole `soundTracks` cache region.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Produces:** `application/json` (declared), but the body is always empty

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `int64` | yes | Sound-track primary key |

**Query parameters**

None.

**Request body**

None.

**Response `204 No Content`**

Empty body.

> **Note:** the delete is deliberately idempotent — `SoundTrackService.delete` returns quietly when the
> id is `null` or the row does not exist. Deleting id `999999` returns `204`, not `404`, and no log row is
> written. Do not treat `204` as proof that something was removed.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | `UNAUTHORIZED` | No / invalid token |
| `403` | `FORBIDDEN` | Role below `ADMIN` (an `EMPLOYEE` may create and update but not delete) |
| `409` | `CONFLICT` | A foreign key still references the row. `SoundTrackLog` has a nullable `sound_track_id` association alongside its snapshot columns; new logs never populate it, but a legacy row that does would block the delete — and `SoundTrackLogRepository.findBySoundTrackId`, whose comment says it exists "for detaching before hard delete", is never called |
| `500` | `INTERNAL_ERROR` | Non-numeric `{id}` |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/sound-tracks/57 \
  -H "Authorization: Bearer $KHI_TOKEN" -o /dev/null -w '%{http_code}\n'
```

---

## 4. `PATCH /api/v1/sound-tracks/{id}/featured` — Feature / unfeature

Toggles the record's presence in the homepage carousel and optionally sets its wide hero picture.
Delegates to `SiteContentService.setSoundTrackFeatured`, which enforces a **global** slide budget shared
by news, projects, writings, videos, sound tracks, image collections and the donation banner. The limit
comes from `site_settings.max_featured_slides` and defaults to **7**.

This endpoint does **not** touch the `soundTracks` Redis region, so the public
`GET /api/v1/sound-tracks/featured` (uncached) reflects the change immediately while the cached list
endpoints keep their old `featureImageUrl` until the TTL expires.

**Auth:** `Authorization: Bearer <token>`, role `ADMIN` only — see the discrepancy note in
[Endpoints at a glance](#how-these-roles-are-derived)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `int64` | yes | Sound-track primary key |

**Query parameters**

None.

**Request body — `SiteContentDtos.FeaturedRequest`**

Only three properties are read by this handler. The DTO also declares `type`, `slug`, `title`,
`description`, `imageUrl`, `imageAlt`, `locale`, `displayOrder` and `active` with `@NotBlank`
annotations, but the parameter is **not** annotated `@Valid`, so those are neither validated nor used —
send them or omit them, the result is the same.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | `boolean` | no | — | `null` or omitted is treated as `true`. `false` unfeatures and also nulls `featuredOrder` |
| `featuredOrder` | `int32` | no | — | Lower sorts first; `null` sorts last. Ignored (forced to `null`) when unfeaturing |
| `featureImageUrl` | `string` | no | — | Tri-state: omit to leave stored value alone, `""` to clear it (the site then falls back to the cover), a URL to set it |

Feature with an explicit position and hero image:

```json
{
  "featured": true,
  "featuredOrder": 2,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9a3f7c21-8e04-4b5d-a1c7-63f0d9e2b478-heyran_hero_wide.jpg"
}
```

Unfeature:

```json
{ "featured": false }
```

**Response `204 No Content`**

Empty body. Read the record back with
[`GET /api/v1/sound-tracks/{id}`](../external/SOUNDTRACK_API.md#3-get-apiv1sound-tracksid--one-sound-track)
to see `featureImageUrl`, or with
[`GET /api/v1/sound-tracks/featured`](../external/SOUNDTRACK_API.md#2-get-apiv1sound-tracksfeatured--homepage-carousel-slides)
to see the new ordering.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Turning `featured` on when the global slide count already equals the limit. `details.reason` = `"Maximum of 7 featured slides allowed across all content. Unfeature one first."` |
| `400` | `BAD_REQUEST` | Body absent or not valid JSON (`error.http.unreadable_body`). The body is required — send at least `{}` |
| `400` | `BAD_REQUEST` | Unknown property in the body, e.g. `soundTrack`. `details.unknownField` names it |
| `401` | `UNAUTHORIZED` | No / invalid token |
| `403` | `FORBIDDEN` | Any role other than `ADMIN`, including `SUPER_ADMIN` |
| `404` | `NOT_FOUND` | No sound track with that id. `message` = `"Sound track not found: 57"`, `details.resource` repeats it |
| `500` | `INTERNAL_ERROR` | Non-numeric `{id}` |

`400` when the carousel is full:

```json
{
  "timestamp": "2026-08-26T11:20:04Z",
  "status": 400,
  "path": "/api/v1/sound-tracks/57/featured",
  "method": "PATCH",
  "traceId": "b71c9e04-5a2d-4f68-8c13-9e07d2a4b5f1",
  "code": "BAD_REQUEST",
  "message": "داواکاری هەڵەیە.",
  "messageEn": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "messageKu": "داواکاری هەڵەیە.",
  "fieldErrors": null,
  "details": {
    "reason": "Maximum of 7 featured slides allowed across all content. Unfeature one first."
  }
}
```

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/sound-tracks/57/featured \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured": true, "featuredOrder": 2}' \
  -o /dev/null -w '%{http_code}\n'
```

---

## 5. `POST /api/v1/sound-tracks/sound-reklam-video` — Create the banner video

Uploads the single promotional video shown above the sound archive. The system stores **at most one**
`SoundReklamVideo` row; creating a second one is rejected. Evicts the `soundTracks` cache region.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Path parameters**

None.

**Query parameters**

None.

**Form parts**

| Part | Content type | Required | Description |
|------|--------------|----------|-------------|
| `videoFile` | `video/*` | **yes** | The video binary. The declared content type must start with `video/`; the file must be non-empty |

There is no `data` part — `sizeBytes` and `mimeType` are taken from the uploaded part.

**Response `201 Created`**

```json
{
  "success": true,
  "message": "Sound reklam video created successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/d5e1a736-42c9-4b08-91f7-6a3c0d84e2b5-sound_reklam.mp4",
    "sizeBytes": 48234496,
    "mimeType": "video/mp4",
    "createdAt": "2026-08-26T09:14:22",
    "updatedAt": "2026-08-26T09:14:22"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `SOUND_VALIDATION` | `videoFile` empty (`details.field = "videoFile"`), content type not `video/*`, or a video already exists (`sound.reklamVideo.already_exists`, empty `details`) |
| `401` | `UNAUTHORIZED` | No / invalid token |
| `403` | `FORBIDDEN` | Role below `EMPLOYEE` |
| `413` | `PAYLOAD_TOO_LARGE` | Above 1GB |
| `500` | `INTERNAL_ERROR` | `videoFile` part missing entirely |
| `502` | `STORAGE_ERROR` | `IOException` while reading the part |

`400` when one already exists — this key has real translations in all three bundles:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/sound-tracks/sound-reklam-video",
  "method": "POST",
  "traceId": "c4f6a812-7d93-4e51-9b2a-0f5c8d1e3a67",
  "code": "SOUND_VALIDATION",
  "message": "Sound reklam video already exists.",
  "messageEn": "Sound reklam video already exists.",
  "messageKu": "ڤیدیۆی رێکلامی ساوند پێشتر هەیە.",
  "fieldErrors": null,
  "details": {}
}
```

> **Note:** "already exists" is reported as `400 SOUND_VALIDATION`, not `409 CONFLICT`, even though a
> `SoundTrackConflictException` / `SOUND_CONFLICT` code exists in the codebase. Branch on the message key
> or on the endpoint, not on the status alone. Use `PATCH` to replace an existing banner.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/sound-tracks/sound-reklam-video \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F "videoFile=@sound_reklam.mp4;type=video/mp4"
```

---

## 6. `PATCH /api/v1/sound-tracks/sound-reklam-video` — Replace the banner video

Uploads a new video, points the existing row at it, and then **deletes the previous object from S3**.
The row id, and therefore any client-side reference to it, stays the same. Evicts the `soundTracks`
cache region.

**Auth:** `Authorization: Bearer <token>` — **any authenticated principal**, no role check
(see the discrepancy note in [Endpoints at a glance](#how-these-roles-are-derived))
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Path parameters**

None.

**Query parameters**

None.

**Form parts**

| Part | Content type | Required | Description |
|------|--------------|----------|-------------|
| `videoFile` | `video/*` | **yes** | The replacement binary. Same non-empty + `video/*` validation as `POST` |

There is no partial mode: `PATCH` always replaces the whole video.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Sound reklam video updated successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/1f7a3c96-08d5-4e2b-b743-5c90e6a1d82f-sound_reklam_2026.mp4",
    "sizeBytes": 51380224,
    "mimeType": "video/mp4",
    "createdAt": "2026-05-11T10:05:33",
    "updatedAt": "2026-08-26T11:47:09"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `SOUND_VALIDATION` | `videoFile` empty or not `video/*` |
| `401` | `UNAUTHORIZED` | No / invalid token |
| `404` | `NOT_FOUND` | No banner video exists yet (`sound.reklamVideo.not_found`) — create it with `POST` first |
| `413` | `PAYLOAD_TOO_LARGE` | Above 1GB |
| `500` | `INTERNAL_ERROR` | `videoFile` part missing entirely |
| `502` | `STORAGE_ERROR` | `IOException` while reading the part |

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/sound-tracks/sound-reklam-video \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F "videoFile=@sound_reklam_2026.mp4;type=video/mp4"
```

---

## 7. `DELETE /api/v1/sound-tracks/sound-reklam-video` — Remove the banner video

Deletes the row and then removes the object from S3. After this the public
`GET /api/v1/sound-tracks/sound-reklam-video` answers `404` until a new video is created with `POST`.
Evicts the `soundTracks` cache region.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Produces:** `application/json` (declared), but the body is always empty

**Path parameters**

None.

**Query parameters**

None.

**Request body**

None.

**Response `204 No Content`**

Empty body.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | `UNAUTHORIZED` | No / invalid token |
| `403` | `FORBIDDEN` | Role below `ADMIN` |
| `404` | `NOT_FOUND` | No banner video exists — unlike the sound-track delete, this one is **not** idempotent |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/sound-tracks/sound-reklam-video \
  -H "Authorization: Bearer $KHI_TOKEN" -o /dev/null -w '%{http_code}\n'
```

---

## Enums used by this API

### `TrackState` — `trackState`

| Value | Meaning |
|-------|---------|
| `SINGLE` | One standalone recording |
| `MULTI` | A multi-track album; `albumName`, `cdNumber`, `totalTracks` become meaningful |

Attachments are accepted for both. `SoundTrack.isMultiAlbumOfMemories()` treats
`MULTI` + `albumOfMemories` as the canonical Album-of-Memories case, but nothing rejects
`SINGLE` + `albumOfMemories`.

### `Language` — `contentLanguages`

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish (Sorani) — keeps `ckbContent` |
| `KMR` | Northern Kurdish (Kurmanji) — keeps `kmrContent` |

Accepted case-insensitively on input (`@JsonCreator` trims and upper-cases); always emitted upper-case.

### `FileType` — `files[].fileType`

| Value | Meaning |
|-------|---------|
| `AUDIO` | Generic audio — the value the public website contract uses |
| `VIDEO` | Generic video, for externally hosted mixed-media entries |
| `MP3` | MPEG-1 Audio Layer III |
| `WAV` | Waveform Audio |
| `OGG` | Ogg Vorbis |
| `AAC` | Advanced Audio Coding |
| `FLAC` | Free Lossless Audio Codec |
| `OTHER` | Anything else |

The column is `NOT NULL` and the service never defaults it — always send it for a new file.

### `AudioChannel` — `files[].audioChannel`

| Value | Meaning |
|-------|---------|
| `MONO` | Single channel |
| `STEREO` | Two channels (left + right) |

Nullable; no default.

### `AttachmentType` — `attachments[].attachmentType`

| Value | Meaning |
|-------|---------|
| `PDF` | Booklet, lyric sheet, liner notes |
| `VIDEO` | Promotional or documentary video |
| `IMAGE` | Poster, artwork |
| `AUDIO` | Extra audio clip (intro, interview) |
| `OTHER` | Anything else — **and the server default** when the field is omitted on a new attachment |

> **Note:** `enums/publishment/SoundType.java` (`LAWK`, `HAIRAN`) and `enums/publishment/AlbumType.java`
> (`AUDIO`, `VIDEO`) are dead code — no sound entity, DTO, repository or controller references either.
> `soundType` is an unconstrained `varchar(100)`; the dashboard is the only thing keeping its values tidy.
> Normalise casing there, because `GET /by-sound-type` matches on `lower(...) = lower(...)` exactly.

---

## Notes & gotchas

**Bean validation on the write DTOs never runs.** `CreateRequest`, `UpdateRequest`,
`FileCreateRequest`, `BrochureRequest` and `AttachmentRequest` carry `@NotBlank`, `@NotNull` and
`@Size`, but `data` is a `String` parsed by hand and no parameter is annotated `@Valid`. The same is true
of the `FeaturedRequest` body on endpoint 4. Only `SoundTrackService`'s hand-written checks and the
database constraints protect the data — so an over-length title surfaces as `409 CONFLICT` from
`DataIntegrityViolationException`, not as a field-level `400`. Enforce lengths in the dashboard.

**`fileType` is the most common `409`.** `SoundTrackFile.file_type` is `NOT NULL` and neither the create
nor the update path defaults it. Two ways to hit this: omitting `fileType` on a new `files[]` entry, and
sending more `audioFiles` binaries than `data.files` entries — the surplus binaries become files with no
DTO at all, so every metadata field including `fileType` is null.

**Deleting a sound track does not delete its S3 objects.** `SoundTrackService.delete` removes rows only;
the audio, covers, brochures and attachments stay in `s3-khiwebsite` forever. The same applies to files
dropped by an update's orphan removal, and to covers replaced by a new upload. Only the banner-video
`PATCH` and `DELETE` clean up after themselves (`s3Service.deleteFile`). Budget for orphaned objects, or
run a reconciliation job.

**Update is destructive for `files`, `attachments` and `contentLanguages`.** Sending `"files": []`
deletes every audio file. Sending `"contentLanguages": ["CKB"]` deletes the Kurmanji title and
description. Always read the record first, then send back the complete list with ids.

**A response cannot be round-tripped as a request.** `Response` carries `topicNameCkb`, `topicNameKmr`,
`featureImageUrl`, `totalDurationSeconds`, `totalSizeBytes`, `createdAt` and `updatedAt`, none of which
exist on `UpdateRequest`; the mapper rejects unknown properties with `400 BAD_REQUEST`. Only three names
are tolerated by design: a stray top-level `id` (skipped by a custom `DeserializationProblemHandler`),
`durationMinutes` inside `files[]`, `brochureOrder` inside `brochures[]` and `attachmentOrder` inside
`attachments[]` (all `@JsonIgnoreProperties`). Strip the rest before PUTting.

**Media metadata is not extracted.** `MediaMetadataExtractor` can read duration, bitrate and codec from
MP3/M4A/MP4 with no external tooling, but `SoundTrackService` does not inject or call it. Only
`sizeBytes` is derived automatically, from `MultipartFile.getSize()`. `durationSeconds`, `bitRate`,
`sampleRate` and `audioChannel` are whatever the dashboard sends, and `totalDurationSeconds` in the
response is only as accurate as those inputs.

**`SoundTrackFile.fileFormat` is unreachable.** The column exists (`varchar(50)`, meant for `"MP3"`,
`"FLAC"` …) but no DTO reads or writes it, so it is always `NULL`.

**Tiptap descriptions upload their own media.** `TiptapHtmlProcessor` scans
`ckbContent.description` / `kmrContent.description` for `src="data:…;base64,…"` on `<img>`, `<video>`,
`<audio>`, `<source>` and `href="data:…"` on `<a>`, uploads each payload to S3 and rewrites the
attribute. It is idempotent and fails soft — a broken payload leaves that one attribute untouched and
the save still succeeds.

**Inline topic creation is unguarded.** `newTopic` creates a `PublishmentTopic` row with
`entityType = "SOUND"` on every request that carries it; there is no name de-duplication. Prefer
`topicId` from [`GET /api/v1/sound-tracks/topics`](../external/SOUNDTRACK_API.md#11-get-apiv1sound-trackstopics--sound-topic-list)
and reserve `newTopic` for genuinely new topics. A `topicId` pointing at a `VIDEO` / `IMAGE` / `WRITING`
topic is rejected with `400 SOUND_VALIDATION` (`topic.type.mismatch`).

**Cache invalidation is region-wide.** Create, update, delete and all three banner-video mutations call
`@CacheEvict(value = "soundTracks", allEntries = true)`, wiping every cached list, filter and search page
under the `khi:soundTracks::` prefix. The featured toggle (endpoint 4) does not — it goes through
`SiteContentService` — so cached list pages keep a stale `featureImageUrl` for up to 10 minutes.

**Audit log writes never fail the request.** `createLog` is wrapped in a `try/catch` that only logs a
warning, so a `sound_track_logs` failure will not roll back the sound track. `actorName` is hard-coded to
`"system"` — the authenticated user is not recorded, and `actorId`, `requestId` and `meta` are left null.

**`@EntityGraph` lists a non-association.** `SoundTrackRepository.findByIdWithGraph` includes `"reader"`
in its `attributePaths`, but `reader` has been a plain `varchar(255)` column (`reader_name`) since the
readers collection was flattened. The repository's phase-2 comment also still mentions a
`sound_track_readers` table that no longer exists. Harmless, but do not expect `reader` to behave like a
collection.

**Non-numeric path variables and bad enum values return 500.** `GlobalExceptionHandler` has no
`MethodArgumentTypeMismatchException` handler, so `/api/v1/sound-tracks/abc` and `?state=SINGEL` fall
through to the catch-all and answer `500 INTERNAL_ERROR`.

**English error text comes from code, not the bundle.** The English properties file is committed as
`src/main/resources/i18n/ messages_en.properties` — with a leading space — so the configured basename
`classpath:i18n/messages` never resolves it. `messageEn` falls back to the hard-coded per-`ErrorCode`
strings in `GlobalExceptionHandler` ("SoundTrack not found", "Sound validation error", …) or to the raw
exception message. `messageKu` and Sorani/Kurmanji `message` values do load correctly. This affects every
domain.

---

## Related documentation

- Counterpart (public reads): [`../external/SOUNDTRACK_API.md`](../external/SOUNDTRACK_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups `public`, `internal`, `all`)
