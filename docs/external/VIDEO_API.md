# Video API — External (Public)

The Video domain holds every moving-image publishment on the KHI site: full-length films and
documentaries (`FILM`) and curated collections of short clips (`VIDEO_CLIP`, optionally flagged as an
"album of memories"). It also owns the shared topic registry used to categorise videos, and the single
site-wide background video that plays behind the homepage Film section.

Everything on this page is readable by an anonymous visitor. `SecurityConfig` makes every `GET` under
`/api/v1/**` `permitAll()`, and none of these handlers carry a narrowing `@PreAuthorize`. Creating,
updating, deleting, featuring, and managing topics all require a JWT and live in
[`../internal/VIDEO_API.md`](../internal/VIDEO_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/videos` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/video/VideoController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/video/VideoService.java` |
| **Mapper** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/video/VideoMapper.java` |
| **Entities** | `Video`, `VideoContent`, `VideoClipItem`, `VideoSourceFile`, `VideoCastMember`, `VideoHighlightClip`, `FilmReklamVideo`, `PublishmentTopic`, `VideoLog` |
| **Enums** | `VideoType`, `Language` |
| **Verified against source** | 2026-08-26 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/videos` | None | — | List and filter videos (paged) |
| 2 | `GET` | `/api/v1/videos/featured` | None | — | Videos flagged for the homepage carousel (paged) |
| 3 | `GET` | `/api/v1/videos/{id}` | None | — | One video, full detail |
| 4 | `GET` | `/api/v1/videos/search/tag` | None | — | Partial tag search across CKB + KMR tags (paged) |
| 5 | `GET` | `/api/v1/videos/search/keyword` | None | — | Partial search across titles, descriptions, directors, keywords (paged) |
| 6 | `GET` | `/api/v1/videos/topics` | None | — | Every video topic (flat list) |
| 7 | `GET` | `/api/v1/videos/film-reklam-video` | None | — | The homepage Film section background video |

---

## Response envelopes

Two different shapes are returned by this controller. Which one you get is per-endpoint, not per-domain.

**A. Bare DTO / bare page** — endpoints 1–6 return the object directly, with no wrapper:

```json
{ "id": 42, "videoType": "FILM" }
```

**B. `ApiResponse<T>`** — endpoint 7 (`film-reklam-video`) wraps its payload:

```json
{
  "success": true,
  "message": "Film reklam video fetched successfully",
  "data": { "id": 1 }
}
```

Null properties are omitted from responses (`spring.jackson.default-property-inclusion: non_null`,
reinforced by `@JsonInclude(NON_NULL)` on `VideoDTO`, `ApiResponse` and their nested classes).
JSON is pretty-printed (`spring.jackson.serialization.indent_output: true`).

### Page envelope

Endpoints 1, 2, 4 and 5 return a Spring Data `Page<VideoDTO>` serialised directly:

```json
{
  "content": [],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 137,
  "totalPages": 14,
  "last": false,
  "first": true,
  "size": 10,
  "number": 0,
  "sort": { "sorted": true, "unsorted": false, "empty": false },
  "numberOfElements": 10,
  "empty": false
}
```

Integrate against `content`, `totalElements`, `totalPages`, `number` and `size`. The remaining keys are
Spring Data internals and may shift between framework upgrades.

**Paging is clamped server-side** in `VideoService.buildPageable()`:

| Input | Effective value |
|-------|-----------------|
| `page` < 0 | `0` |
| `size` < 1 | `1` |
| `size` > 100 | `100` |

---

## Response objects

### `VideoDTO`

Returned by endpoints 1–5. Source: `dto/publishment/video/VideoDTO.java`, populated by `VideoMapper.toDTO()`.

| Field | Type | Always present | Description |
|-------|------|----------------|-------------|
| `id` | `long` | yes | Primary key |
| `ckbCoverUrl` | `string` | no | Cover image shown on the Sorani (CKB) site |
| `kmrCoverUrl` | `string` | no | Cover image shown on the Kurmanji (KMR) site |
| `hoverCoverUrl` | `string` | no | Image swapped in on card hover (thumbnail) |
| `featureImageUrl` | `string` | no | Wide hero picture for the homepage carousel. Written only through the admin featured toggle; falls back to the cover when absent |
| `videoType` | `string` enum | yes | `FILM` or `VIDEO_CLIP` |
| `albumOfMemories` | `boolean` | yes | Memorial / retrospective clip set. Always `false` for `FILM` |
| `clearTopic` | `boolean` | yes | Request-only flag. Always serialised as `false` on responses — ignore it |
| `topicId` | `long` | no | FK into `publishment_topics` |
| `topicNameCkb` | `string` | no | Sorani topic name |
| `topicNameKmr` | `string` | no | Kurmanji topic name |
| `contentLanguages` | `string[]` | yes | Subset of `["CKB", "KMR"]`. May be `[]` |
| `ckbContent` | `VideoContentDTO` | no | Sorani text block; omitted when every field is blank |
| `kmrContent` | `VideoContentDTO` | no | Kurmanji text block; omitted when every field is blank |
| `videoSources` | `VideoSourceDTO[]` | no | `FILM` only. Ordered list of every source file for the film |
| `sourceUrl` | `string` | no | Mirror of the `main` entry of `videoSources` (hosted file) |
| `sourceExternalUrl` | `string` | no | Mirror of the `main` entry (external watch page) |
| `sourceEmbedUrl` | `string` | no | Mirror of the `main` entry (iframe embed) |
| `videoClipItems` | `VideoClipItemDTO[]` | no | `VIDEO_CLIP` only. Omitted entirely when empty |
| `castMembers` | `CastMemberDTO[]` | yes | May be `[]` |
| `highlightClips` | `HighlightClipDTO[]` | yes | May be `[]` |
| `fileFormat` | `string` | no | e.g. `"mp4"` |
| `durationSeconds` | `int` | no | Total runtime in seconds |
| `publishmentDate` | `string` date | no | `yyyy-MM-dd` |
| `resolution` | `string` | no | e.g. `"1080p"` |
| `fileSizeMb` | `double` | no | Size in megabytes |
| `tagsCkb` | `string[]` | yes | May be `[]` |
| `tagsKmr` | `string[]` | yes | May be `[]` |
| `keywordsCkb` | `string[]` | yes | May be `[]` |
| `keywordsKmr` | `string[]` | yes | May be `[]` |
| `createdAt` | `string` date-time | yes | ISO local date-time, e.g. `2026-03-14T09:22:41` |
| `updatedAt` | `string` date-time | yes | ISO local date-time |

> **Note:** `VideoDTO` has no `featured` or `featuredOrder` field. Even the response of
> `GET /api/v1/videos/featured` carries no flag saying the video is featured — membership in that
> result set is the only signal. The `Video` entity does persist `featured` and `featuredOrder`;
> `VideoMapper.toDTO()` simply never copies them.

### `VideoContentDTO`

| Field | Type | Description |
|-------|------|-------------|
| `title` | `string` | Title in this language (max 300 chars in the DB) |
| `description` | `string` | Tiptap HTML body. Inline base64 media has already been extracted to S3 by the server |
| `location` | `string` | Where it was shot |
| `director` | `string` | Director name |
| `producer` | `string` | Producer / production house |

### `VideoSourceDTO` (`FILM` only)

Backed by the `VideoSourceFile` embeddable, stored in `video_source_files` with an explicit
`display_order` column, so array order is the intended display order.

| Field | Type | Description |
|-------|------|-------------|
| `url` | `string` | Direct hosted file (S3 / CDN) |
| `externalUrl` | `string` | External watch page (YouTube watch, Vimeo …) |
| `embedUrl` | `string` | Embeddable iframe URL |
| `main` | `boolean` | Exactly one source per film is `true` — the one mirrored onto `sourceUrl` |
| `label` | `string` | Optional friendly label, e.g. `"Beş 1"`, `"1080p"`, `"Trailer"` |
| `durationSeconds` | `int` | Optional per-source runtime |

Legacy films saved before `videoSources` existed still return a one-element array synthesised from the
`sourceUrl` / `sourceExternalUrl` / `sourceEmbedUrl` columns, with `main: true`.

### `VideoClipItemDTO` (`VIDEO_CLIP` only)

Its own table (`video_clip_items`), ordered by `clipNumber ASC`.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `long` | Clip primary key. Needed by the admin update flow |
| `url` | `string` | Direct hosted file |
| `externalUrl` | `string` | External watch page |
| `embedUrl` | `string` | Embeddable iframe URL |
| `clipNumber` | `int` | Sort key within the collection |
| `durationSeconds` | `int` | Clip runtime |
| `resolution` | `string` | e.g. `"720p"` |
| `fileFormat` | `string` | e.g. `"mp4"` |
| `fileSizeMb` | `double` | Size in megabytes |
| `titleCkb` | `string` | Sorani clip title |
| `titleKmr` | `string` | Kurmanji clip title |
| `descriptionCkb` | `string` | Sorani clip description (Tiptap HTML) |
| `descriptionKmr` | `string` | Kurmanji clip description (Tiptap HTML) |

At least one of `url`, `externalUrl`, `embedUrl` is always populated — the server refuses to save a
sourceless clip.

### `CastMemberDTO`

| Field | Type | Description |
|-------|------|-------------|
| `nameCkb` | `string` | Sorani name |
| `nameKmr` | `string` | Kurmanji name |
| `roleCkb` | `string` | Sorani role / credit |
| `roleKmr` | `string` | Kurmanji role / credit |
| `imageUrl` | `string` | Portrait image URL |

### `HighlightClipDTO`

| Field | Type | Description |
|-------|------|-------------|
| `titleCkb` | `string` | Sorani title |
| `titleKmr` | `string` | Kurmanji title |
| `url` | `string` | Direct hosted file (column `clip_url`) |
| `embedUrl` | `string` | Embeddable iframe URL |
| `durationSeconds` | `int` | Runtime in seconds |

### `TopicView`

| Field | Type | Description |
|-------|------|-------------|
| `id` | `long` | Topic primary key — pass as `topicId` when filtering |
| `nameCkb` | `string` | Sorani name |
| `nameKmr` | `string` | Kurmanji name |
| `createdAt` | `string` date-time | When the topic was created |

### `FilmReklamVideoResponse`

| Field | Type | Description |
|-------|------|-------------|
| `id` | `long` | Row id of the singleton |
| `videoUrl` | `string` | S3 URL of the background video |
| `sizeBytes` | `long` | File size in bytes |
| `mimeType` | `string` | Uploaded content type, e.g. `"video/mp4"` |
| `createdAt` | `string` date-time | First upload time |
| `updatedAt` | `string` date-time | Last replacement time |

---

## 1. `GET /api/v1/videos` — List and filter videos

Returns a page of videos sorted newest first (`createdAt DESC`). Read-only, no side effects, no caching.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `videoType` | `FILM` \| `VIDEO_CLIP` | No | — | Restrict to one video type |
| `memories` | `boolean` | No | — | Album-of-memories flag. Only applied together with `videoType=VIDEO_CLIP` |
| `topicId` | `long` | No | — | Restrict to one topic |
| `page` | `int` | No | `0` | Zero-based page index; negatives clamp to `0` |
| `size` | `int` | No | `10` | Page size; clamped to `1..100` |

**Filter precedence** — `VideoService.getVideoListing()` picks exactly one repository query, in this order:

1. `topicId` present → filter by topic. **`videoType` and `memories` are ignored.**
2. `videoType=VIDEO_CLIP` **and** `memories` present → filter by type + album flag.
3. `videoType` present → filter by type only.
4. Nothing present → all videos.

> **Note:** Because of step 1, `?topicId=7&videoType=FILM` silently returns clips as well as films.
> Filter client-side if you need both dimensions at once.

**Response `200 OK`**

```json
{
  "content": [
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
      "castMembers": [],
      "highlightClips": [],
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
      "updatedAt": "2026-08-20T16:05:03"
    }
  ],
  "totalElements": 137,
  "totalPages": 14,
  "last": false,
  "first": true,
  "size": 10,
  "number": 0,
  "numberOfElements": 1,
  "empty": false
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | `videoType` is not `FILM` or `VIDEO_CLIP`, or `topicId` / `page` / `size` is not a number — see the note below |

> **Note:** A bad enum or numeric query parameter raises `MethodArgumentTypeMismatchException`, which
> `GlobalExceptionHandler` does not handle. It falls through to the catch-all `@ExceptionHandler(Exception.class)`
> and answers `500 INTERNAL_ERROR` instead of `400`. Validate parameter values on the client.

**Example**

```bash
# Everything, newest first
curl -s "http://localhost:8080/api/v1/videos?page=0&size=12"

# Only films
curl -s "http://localhost:8080/api/v1/videos?videoType=FILM&size=20"

# Only "album of memories" clip collections
curl -s "http://localhost:8080/api/v1/videos?videoType=VIDEO_CLIP&memories=true"

# Everything filed under topic 7
curl -s "http://localhost:8080/api/v1/videos?topicId=7"
```

---

## 2. `GET /api/v1/videos/featured` — Featured videos

Returns the page of videos an administrator has flagged for the homepage carousel. Sorted by
`featuredOrder ASC, id DESC`; because `featuredOrder` is nullable, PostgreSQL places videos with no
explicit order last. The topic is join-fetched so `topicNameCkb` / `topicNameKmr` are populated without
extra queries.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | `int` | No | `0` | Zero-based page index; negatives clamp to `0` |
| `size` | `int` | No | `20` | Page size; clamped to `1..100` |

**Response `200 OK`**

Identical `Page<VideoDTO>` shape as endpoint 1, restricted to `featured = true` rows.

```json
{
  "content": [
    {
      "id": 42,
      "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33-anfal-cover-ckb.jpg",
      "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/aa71c3d5-9f20-4c8e-8b16-77d9e4a3c012-anfal-hero.jpg",
      "videoType": "FILM",
      "albumOfMemories": false,
      "clearTopic": false,
      "topicId": 7,
      "topicNameCkb": "بەڵگەنامەیی",
      "topicNameKmr": "Belgefîlm",
      "contentLanguages": ["CKB", "KMR"],
      "ckbContent": { "title": "کۆچی ئەنفال", "description": "<p>…</p>" },
      "kmrContent": { "title": "Koça Enfalê", "description": "<p>…</p>" },
      "sourceUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/8d1a4b60-53e7-41f9-9c25-0af6b3d81e77-anfal-part-1.mp4",
      "castMembers": [],
      "highlightClips": [],
      "publishmentDate": "2026-03-14",
      "tagsCkb": ["ئەنفال"],
      "tagsKmr": ["Enfal"],
      "keywordsCkb": [],
      "keywordsKmr": [],
      "createdAt": "2026-03-14T09:22:41",
      "updatedAt": "2026-08-20T16:05:03"
    }
  ],
  "totalElements": 3,
  "totalPages": 1,
  "last": true,
  "first": true,
  "size": 20,
  "number": 0,
  "numberOfElements": 1,
  "empty": false
}
```

Use `featureImageUrl` for the carousel slide when present, and fall back to `ckbCoverUrl` /
`kmrCoverUrl` for the visitor's language when it is not.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `500` | `INTERNAL_ERROR` | `page` or `size` is not a number |

**Example**

```bash
curl -s http://localhost:8080/api/v1/videos/featured
```

> **Note:** This is not the same endpoint as the site-wide `GET /featured` legacy alias, which mixes
> featured news, projects, writings, videos, sound tracks, image collections and the donation page into
> one carousel feed. The number of slides that feed will emit is capped by `SiteSettings.maxFeaturedSlides`
> (default `7`); `GET /api/v1/videos/featured` applies no such cap.

---

## 3. `GET /api/v1/videos/{id}` — One video

Full detail for a single video, including all clip items, sources, cast members and highlight clips.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `long` | Yes | Video primary key |

**Response `200 OK`** — a bare `VideoDTO`, not wrapped, not paged.

A `VIDEO_CLIP` example (note `videoClipItems` present, `videoSources` and `sourceUrl` absent):

```json
{
  "id": 88,
  "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/5c0f1d83-71ab-4e46-a7f2-3d90c8b41e6f-halabja-album-ckb.jpg",
  "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/e8b41a27-6c95-4f0d-9a13-72fe5d0c8b34-halabja-album-kmr.jpg",
  "videoType": "VIDEO_CLIP",
  "albumOfMemories": true,
  "clearTopic": false,
  "topicId": 11,
  "topicNameCkb": "بیرەوەری",
  "topicNameKmr": "Bîranîn",
  "contentLanguages": ["CKB", "KMR"],
  "ckbContent": {
    "title": "ئەلبوومی بیرەوەری هەڵەبجە",
    "description": "<p>کۆمەڵێک کلیپی کورت لە ئەرشیڤی هەڵەبجە.</p>",
    "location": "هەڵەبجە",
    "director": "شیلان عومەر",
    "producer": "دەزگای کەلەپووری کوردی"
  },
  "kmrContent": {
    "title": "Albûma Bîranîna Helebceyê",
    "description": "<p>Komek klîpên kurt ji arşîva Helebceyê.</p>",
    "location": "Helebce",
    "director": "Şîlan Umer",
    "producer": "Saziya Kelepûra Kurdî"
  },
  "videoClipItems": [
    {
      "id": 301,
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/b71e9c04-2f58-4a3d-8e60-14c7d95a2b8f-halabja-clip-1.mp4",
      "clipNumber": 1,
      "durationSeconds": 96,
      "resolution": "720p",
      "fileFormat": "mp4",
      "fileSizeMb": 42.7,
      "titleCkb": "بەیانی ١٦ی ئازار",
      "titleKmr": "Sibeha 16ê Adarê",
      "descriptionCkb": "<p>دیمەنی ئەرشیڤی لە ناوەندی شار.</p>",
      "descriptionKmr": "<p>Dîmenên arşîvî ji navenda bajêr.</p>"
    },
    {
      "id": 302,
      "externalUrl": "https://www.youtube.com/watch?v=nQ2sK7pR1Ac",
      "embedUrl": "https://www.youtube.com/embed/nQ2sK7pR1Ac",
      "clipNumber": 2,
      "durationSeconds": 143,
      "titleCkb": "لێدوانی شایەتحاڵان",
      "titleKmr": "Şahidiya şahidan"
    }
  ],
  "castMembers": [
    {
      "nameCkb": "شیلان عومەر",
      "nameKmr": "Şîlan Umer",
      "roleCkb": "دەرهێنەر",
      "roleKmr": "Derhêner",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/70d2c58a-91b4-4c3e-8f57-6e1a93d4c027-shilan.jpg"
    }
  ],
  "highlightClips": [
    {
      "titleCkb": "کورتەی ئەلبووم",
      "titleKmr": "Kurteya albûmê",
      "url": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/45a8f3d1-0b67-4e29-9c84-2f5d7a013e6b-halabja-highlight.mp4",
      "durationSeconds": 58
    }
  ],
  "publishmentDate": "2026-03-16",
  "tagsCkb": ["هەڵەبجە", "بیرەوەری"],
  "tagsKmr": ["Helebce", "Bîranîn"],
  "keywordsCkb": ["هەڵەبجە", "١٩٨٨"],
  "keywordsKmr": ["Helebce", "1988"],
  "createdAt": "2026-03-16T11:04:12",
  "updatedAt": "2026-07-02T08:41:55"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `VIDEO_NOT_FOUND` | No video with that id. `details.id` echoes the requested id |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number (e.g. `/api/v1/videos/abc`) |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/videos/99999",
  "method": "GET",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VIDEO_NOT_FOUND",
  "message": "Internal error",
  "messageEn": "Internal error",
  "messageKu": "هەڵەی ناوخۆیی",
  "details": { "id": "99999" }
}
```

> **Note:** The `code` is correct (`VIDEO_NOT_FOUND`) but the three message strings currently read
> "Internal error" / "هەڵەی ناوخۆیی". The `video.not_found` key is absent from every
> `src/main/resources/i18n/messages_*.properties` bundle, and `GlobalExceptionHandler.fallbackByCode()`
> has no `VIDEO_*` branch, so it falls to its `default` arm. **Branch on `code` and `status`, never on
> the message text.**

**Example**

```bash
curl -s http://localhost:8080/api/v1/videos/88
```

---

## 4. `GET /api/v1/videos/search/tag` — Search by tag

Case-insensitive partial (`LIKE %value%`) match against both the CKB and KMR tag collections. Uses
`EXISTS` sub-queries so a video matching several tags is still returned once. Sorted `createdAt DESC`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `value` | `string` | **Yes** | — | Tag fragment. Trimmed server-side; must not be blank |
| `page` | `int` | No | `0` | Zero-based page index |
| `size` | `int` | No | `10` | Page size; clamped to `1..100` |

**Response `200 OK`** — `Page<VideoDTO>`, same shape as endpoint 1.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `value` was not sent at all |
| `400` | `BAD_REQUEST` | `value` was sent but is empty or whitespace-only (`details.field = "tag"`) |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not a number |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/videos/search/tag \
  --data-urlencode "value=ئەنفال" \
  --data-urlencode "size=20"
```

---

## 5. `GET /api/v1/videos/search/keyword` — Search by keyword

Broader search than the tag search. Case-insensitive partial match across, in one query:

- `ckbContent.title`, `kmrContent.title`
- `ckbContent.description`, `kmrContent.description`
- `ckbContent.director`, `kmrContent.director`
- the `keywordsCkb` collection
- the `keywordsKmr` collection

Tags, locations and producers are **not** searched — use endpoint 4 for tags. Sorted `createdAt DESC`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `value` | `string` | **Yes** | — | Search fragment. Trimmed server-side; must not be blank |
| `page` | `int` | No | `0` | Zero-based page index |
| `size` | `int` | No | `10` | Page size; clamped to `1..100` |

**Response `200 OK`** — `Page<VideoDTO>`, same shape as endpoint 1.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `value` was not sent at all |
| `400` | `BAD_REQUEST` | `value` was sent but is empty or whitespace-only (`details.field = "keyword"`) |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not a number |

**Example**

```bash
curl -s --get http://localhost:8080/api/v1/videos/search/keyword \
  --data-urlencode "value=Germiyan"
```

Because the description column is Tiptap HTML, a keyword search can match markup as well as prose — for
example searching `img` will match any description containing an image tag. Keep search terms specific.

---

## 6. `GET /api/v1/videos/topics` — List video topics

Every topic in `publishment_topics` whose `entityType = "VIDEO"`. Sound, image and writing topics live in
the same table under their own `entityType` and are never returned here. Use the returned `id` as the
`topicId` filter on endpoint 1.

**Auth:** None (public)
**Content-Type:** `application/json`

**Response `200 OK`** — a bare JSON array of `TopicView`. Not paged, not wrapped, not sorted (rows come
back in whatever order PostgreSQL returns them; sort client-side if order matters).

```json
[
  {
    "id": 7,
    "nameCkb": "بەڵگەنامەیی",
    "nameKmr": "Belgefîlm",
    "createdAt": "2026-01-08T13:20:44"
  },
  {
    "id": 11,
    "nameCkb": "بیرەوەری",
    "nameKmr": "Bîranîn",
    "createdAt": "2026-02-19T09:57:02"
  },
  {
    "id": 14,
    "nameCkb": "چاوپێکەوتن",
    "createdAt": "2026-04-03T15:11:38"
  }
]
```

Only one of `nameCkb` / `nameKmr` is guaranteed — the server requires at least one at creation time, not
both. The third entry above shows a CKB-only topic; `nameKmr` is simply absent.

**Errors**

None specific to this endpoint. An empty registry returns `[]` with `200`.

**Example**

```bash
curl -s http://localhost:8080/api/v1/videos/topics
```

---

## 7. `GET /api/v1/videos/film-reklam-video` — Film section background video

The homepage Film section has one site-wide background video that plays muted and looping behind the
film cards. It is a singleton (`film_reklam_videos` holds at most one row), so there is no id in the path
and no pagination. It is **not** a `Video` row — it has no bilingual content, no topic, no tags, and no
featured flag.

This is the Film counterpart of `GET /api/v1/sound-tracks/sound-reklam-video`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Response `200 OK`** — wrapped in `ApiResponse<FilmReklamVideoResponse>`.

```json
{
  "success": true,
  "message": "Film reklam video fetched successfully",
  "data": {
    "id": 1,
    "videoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/video/9a3f27e6-c841-4b05-97de-6f2c30d15a84-film-section-loop.mp4",
    "sizeBytes": 48219043,
    "mimeType": "video/mp4",
    "createdAt": "2026-05-04T12:30:19",
    "updatedAt": "2026-08-11T10:02:47"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No background video has been uploaded yet |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/videos/film-reklam-video",
  "method": "GET",
  "traceId": "7c2b91d4-5e08-4a6f-b310-2d9f4e78c1a5",
  "code": "NOT_FOUND",
  "message": "Film reklam video was not found.",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە"
}
```

`404` here is the **normal empty state**, not a failure. The website should hide the background layer and
render the Film section as usual. Do not surface an error to the visitor.

The `message` field above assumes `Accept-Language: ckb` or `kmr` — the `video.reklamVideo.not_found` key
exists in `messages_ckb.properties` and `messages_kmr.properties`. See the caveat in
[Notes & gotchas](#notes--gotchas) about `messageEn`.

**Example**

```bash
curl -s http://localhost:8080/api/v1/videos/film-reklam-video
```

---

## Enums used by this API

### `VideoType`

`src/main/java/ak/dev/khi_backend/khi_app/model/publishment/video/VideoType.java`

| Value | Meaning |
|-------|---------|
| `FILM` | A film or documentary. Sources live in `videoSources[]`; `videoClipItems` is always empty and `albumOfMemories` is always `false` |
| `VIDEO_CLIP` | A collection of short clips. Each clip is an entry in `videoClipItems[]`; `videoSources` / `sourceUrl` are always empty. May be flagged `albumOfMemories` |

### `Language`

`src/main/java/ak/dev/khi_backend/khi_app/enums/Language.java`. Used in `contentLanguages[]`.

| Value | Meaning |
|-------|---------|
| `CKB` | Sorani / Central Kurdish. Pairs with `ckbContent`, `ckbCoverUrl`, `tagsCkb`, `keywordsCkb` |
| `KMR` | Kurmanji / Northern Kurdish. Pairs with `kmrContent`, `kmrCoverUrl`, `tagsKmr`, `keywordsKmr` |

`contentLanguages` is advisory metadata set by the editor. It is not enforced against which content
blocks actually exist — a video can declare `["CKB", "KMR"]` and still return only `ckbContent`. Render
whichever block is actually present.

---

## Notes & gotchas

**No caching.** `VideoService` carries no `@Cacheable` or `@CacheEvict`. Redis is configured
(`khi:` key prefix, 10-minute default TTL) but this domain does not use it — every request hits
PostgreSQL. Cache on the client or the CDN if you need it.

**Bilingual rendering.** Pick the language block by the visitor's site language, then fall back:
`ckbContent` for CKB, `kmrContent` for KMR, and either one when the other is absent. The same applies to
`ckbCoverUrl` / `kmrCoverUrl`. `hoverCoverUrl` is language-neutral.

**Descriptions are HTML, not plain text.** `description`, `descriptionCkb` and `descriptionKmr` hold
Tiptap HTML. The server has already extracted any inline `data:` base64 payloads to S3 and rewritten the
`src` / `href` attributes, so what you receive contains only remote URLs. Render it as HTML with your
usual sanitiser.

**Which source to play.** For a `FILM`, prefer `videoSources[]` — it is the complete, ordered list and
tells you which entry is `main`. `sourceUrl` / `sourceExternalUrl` / `sourceEmbedUrl` are a
backward-compatible mirror of that one `main` entry only; a two-part film exposes only part one through
them. For a `VIDEO_CLIP`, iterate `videoClipItems[]` (already sorted by `clipNumber`) and pick
`url` → `embedUrl` → `externalUrl` in whatever order your player supports.

**Empty vs absent collections.** `tagsCkb`, `tagsKmr`, `keywordsCkb`, `keywordsKmr`, `contentLanguages`,
`castMembers` and `highlightClips` are always present, possibly as `[]`. `videoClipItems` and
`videoSources` are **omitted entirely** when empty. Guard for `undefined`, not just for empty arrays.

**`clearTopic` is request-only.** It is a primitive `boolean`, so `@JsonInclude(NON_NULL)` cannot
suppress it and it appears as `"clearTopic": false` on every response. Ignore it when reading.

**Timestamps.** `createdAt` / `updatedAt` are `LocalDateTime` serialised as ISO local date-time with no
offset (`2026-03-14T09:22:41`). They are stored in UTC (`hibernate.jdbc.time_zone=UTC`). `publishmentDate`
is a `LocalDate` serialised as `yyyy-MM-dd`. The `timestamp` on error bodies is an `Instant` and does
carry a `Z`.

**S3 URL shape.** Uploaded media is public-read at
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/<folder>/<uuid>-<sanitised-filename>`,
where `<folder>` is `video` for `video/*`, `images` for `image/*`, `audio` for `audio/*`, and `files`
otherwise. Non-alphanumeric characters in the original filename are replaced with `_`. Externally hosted
sources (`externalUrl`, `embedUrl`) are stored verbatim and can point anywhere.

**Do not send `?lang=`.** `I18nConfig` registers a `LocaleChangeInterceptor` on the `lang` parameter
alongside an `AcceptHeaderLocaleResolver`, whose `setLocale()` throws `UnsupportedOperationException`.
Any request carrying `?lang=…` therefore returns `500 INTERNAL_ERROR`. Use the `Accept-Language` header
(`ckb`, `kmr`, `en`) instead — that is what actually drives the localised `message` on error bodies.

> **Note:** `messageEn` on error responses does not currently come from the English bundle. The file is
> named `" messages_en.properties"` with a leading space, so `classpath:i18n/messages_en.properties` never
> resolves and `messageEn` always falls back to the generic string from
> `GlobalExceptionHandler.fallbackByCode()`. `messageKu` has the same problem for a different reason: the
> handler resolves it with `Locale.forLanguageTag("ku")` while the bundles are `_ckb` and `_kmr`, so no
> file matches. Only `message` — driven by `Accept-Language` — reaches the real translations.

**N+1 on list endpoints.** The paged endpoints join-fetch only `topic`. `videoSources`, `castMembers`,
`highlightClips` and `videoClipItems` are lazy with no `@BatchSize`, so a page of 20 videos triggers
extra `SELECT`s per row while the DTO is being built. The tag, keyword and language collections are eager
with `@BatchSize(25)` and are batched. Keep `size` modest (10–20) on list screens and fetch detail
lazily.

**Search is not indexed.** Both search endpoints use `LOWER(col) LIKE LOWER('%value%')`, which cannot use
a b-tree index. Expect these to degrade as the catalogue grows.

---

## Related documentation

- Counterpart (writes, topics management, featuring): [`../internal/VIDEO_API.md`](../internal/VIDEO_API.md)
- Database schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (groups `public`, `internal`, `all`).
  Every `/api/v1/videos/**` path — public reads and admin writes alike — appears in the `public` group,
  because `OpenApiConfig` groups by path prefix rather than by required role.
- Servers: `http://localhost:8080` (local); production is deployed on Railway.
