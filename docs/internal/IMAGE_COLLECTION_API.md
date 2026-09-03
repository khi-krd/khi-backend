# Image Collections API — Internal (Authenticated)

The write side of the image-collection domain: the endpoints the KHI admin dashboard uses to create,
edit, delete and feature photo collections. Every call needs a JWT. Two of the five are multipart —
one create route accepts uploads, a second create route exists for URL-only payloads, and the update
route is multipart-only.

The read endpoints the public website uses live in the
[external counterpart](../external/IMAGE_COLLECTION_API.md), which also documents the shared
`Response` object these endpoints echo back.

| | |
|---|---|
| **Base path** | `/api/v1/image-collections` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/image/ImageCollectionController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/image/ImageCollectionService.java` |
| **Featured service** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/image/ImageCollectionDTO.java`, `SiteContentDtos.FeaturedRequest` |
| **Entities** | `ImageCollection`, `ImageAlbumItem`, `ImageContent` (embeddable), `ImageCollectionLog`, `PublishmentTopic` |
| **Response envelope** | `ApiResponse<Response>` on create/update, empty `204` body on delete and featured |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either as a header or as the HttpOnly cookie issued at login:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

```
Cookie: <JWT_COOKIE_NAME>=eyJhbGciOiJIUzI1NiJ9...
```

The cookie name is environment-driven (`${JWT_COOKIE_NAME}`). Sessions are stateless
(`SessionCreationPolicy.STATELESS`) — there is no server-side session to keep alive, and every
request must carry the token.

Granted authorities are `ROLE_<NAME>` plus the permission set `user:create|read|update|delete`.
Roles, in ascending privilege: `GUEST` (the default for self-registration), `EMPLOYEE`, `ADMIN`,
`SUPER_ADMIN`. **There is no role hierarchy bean** — `hasRole('ADMIN')` matches `ROLE_ADMIN` only and
does not imply `SUPER_ADMIN`. That detail matters for the featured endpoint below.

### Authentication and authorization failures

Auth failures are produced by three different layers, and only one of them emits the standard
`ApiErrorResponse`. Handle all four shapes.

| Situation | Status | Body | Produced by |
|-----------|--------|------|-------------|
| Expired token | `401` | `{ "error": "TOKEN_EXPIRED", "message": "Session expired, please login again" }` | `JWTAuthenticationFilter`; also clears the auth cookie |
| Token blacklisted by a logout | `401` | `{ "error": "TOKEN_REVOKED", "message": "Session invalidated, please login again" }` | `JWTAuthenticationFilter`; also clears the auth cookie |
| Token present but unparseable / bad signature | `403` | `{ "error": "INVALID_TOKEN", "message": "Invalid token" }` | `JWTAuthenticationFilter`; also clears the auth cookie |
| **No token at all** | `403` | empty | Spring Security's default `Http403ForbiddenEntryPoint` — no `authenticationEntryPoint` is configured, so an anonymous request to a protected route is refused with 403, **not 401** |
| Valid token, role rejected by a `SecurityConfig` matcher (endpoints 1–4) | `403` | Spring Boot's default error JSON (`timestamp`, `status`, `error`, `message`, `path`) | `AccessDeniedHandlerImpl` → `sendError` → `BasicErrorController` |
| Valid token, role rejected by `@PreAuthorize` (endpoint 5) | `403` | `ApiErrorResponse`, `code: FORBIDDEN`, key `error.user.access_denied`, `details` = `path` / `method` / `hint` | `GlobalExceptionHandler.handleAccessDenied` — method security throws inside the dispatcher, so the advice sees it |

The practical consequence: **do not branch on `401` to trigger a re-login.** A missing token and a bad
token both yield `403`; only an expired or revoked token yields `401`. Branch on the `error` field of
the filter's body when it is present.

---

## Endpoints at a glance

| # | Method | Path | Consumes | Auth | Roles | Purpose |
|---|--------|------|----------|------|-------|---------|
| 1 | `POST` | `/api/v1/image-collections` | `multipart/form-data` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a collection, uploading covers and album pictures |
| 2 | `POST` | `/api/v1/image-collections/json` | `application/json` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a collection from URLs only, no file uploads |
| 3 | `PUT` | `/api/v1/image-collections/{id}` | `multipart/form-data` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Partial update; the album is replaced wholesale when touched |
| 4 | `DELETE` | `/api/v1/image-collections/{id}` | — | JWT | `ADMIN`, `SUPER_ADMIN` | Delete the collection and cascade-delete its album rows |
| 5 | `PATCH` | `/api/v1/image-collections/{id}/featured` | `application/json` | JWT | `ADMIN` **only** | Flag / unflag for the homepage carousel |

All five produce `application/json` on success (endpoints 4 and 5 return `204` with no body).

### Where those role rules come from

`SecurityConfig` groups the seven content domains together:

```java
.requestMatchers(HttpMethod.POST,   ".../api/v1/image-collections/**", ...).hasAnyRole("EMPLOYEE", "ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.PUT,    ".../api/v1/image-collections/**", ...).hasAnyRole("EMPLOYEE", "ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.DELETE, ".../api/v1/image-collections/**", ...).hasAnyRole("ADMIN", "SUPER_ADMIN")
```

The `PATCH` matcher in that block covers `/api/v1/videos/**` only. A `PATCH` to
`/api/v1/image-collections/{id}/featured` therefore falls through to `.anyRequest().authenticated()`
and is gated solely by the handler's `@PreAuthorize("hasRole('ADMIN')")`.

> **Note:** Because `@PreAuthorize("hasRole('ADMIN')")` is an exact role check and no `RoleHierarchy`
> bean exists, **a `SUPER_ADMIN` receives `403 FORBIDDEN` from the featured endpoint** even though it
> may create, update and delete the same collection. Every other write on this controller accepts
> both `ADMIN` and `SUPER_ADMIN`. A comment in `SecurityConfig` ("the `/{id}/featured` toggles under
> the same prefix stay ADMIN-only via `@PreAuthorize`") suggests the ADMIN gate is deliberate, but the
> exclusion of `SUPER_ADMIN` is almost certainly not.

---

## Request DTOs

### `CreateRequest`

Used by endpoint 1 (inside the `data` part) and endpoint 2 (as the whole body).

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `slugCkb` | string | No | DB `UNIQUE`, max 240 | Sorani URL slug. Trimmed; an all-whitespace value becomes null |
| `slugKmr` | string | No | DB `UNIQUE`, max 240 | Kurmanji URL slug, same handling |
| `collectionType` | enum | **Yes** | `@NotNull`; `SINGLE` \| `GALLERY` \| `PHOTO_STORY` | Drives the album-size rule |
| `ckbCoverUrl` | string | Conditional | — | Sorani cover URL. Ignored when a `ckbCoverImage` file part is present |
| `kmrCoverUrl` | string | Conditional | — | Kurmanji cover URL. Ignored when `kmrCoverImage` is present |
| `hoverCoverUrl` | string | Conditional | — | Hover-overlay URL. Ignored when `hoverCoverImage` is present |
| `topicId` | integer (int64) | No | Must reference a topic whose `entityType` is `IMAGE` | Link to an existing topic. Wins over `newTopic` |
| `newTopic` | object | No | At least one of `nameCkb` / `nameKmr` non-blank | Creates a new `IMAGE` topic inline. Only consulted when `topicId` is null |
| `publishmentDate` | string `yyyy-MM-dd` | No | — | Editorial publication date; the primary sort key on the public list |
| `contentLanguages` | array of enum | **Yes** | `@NotNull`, and the service additionally rejects an empty array | Subset of `["CKB", "KMR"]` |
| `ckbContent` | object | No | See below | Sorani content block. Stored only when `CKB` is in `contentLanguages` |
| `kmrContent` | object | No | See below | Kurmanji content block. Stored only when `KMR` is in `contentLanguages` |
| `tags` | object | No | `{ "ckb": [string], "kmr": [string] }`, each tag max 100 chars | Sets; duplicates collapse, insertion order preserved |
| `keywords` | object | No | Same shape, each keyword max 150 chars | Sets |
| `imageAlbum` | array | Conditional | Size must satisfy the collection-type rule | Album items. See [Album construction](#album-construction) |

At least one cover source is mandatory — see the [cover rule](#the-cover-rule).

**`LanguageContentDto`** (`ckbContent` / `kmrContent`)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `title` | string | max 300 | Collection title in that language |
| `description` | string (HTML) | `TEXT` | Tiptap rich text. Inline `data:` URIs are uploaded to S3 and rewritten before storage |
| `location` | string | max 250 | e.g. `هەولێر، هەرێمی کوردستان` |
| `collectedBy` | string | max 250 | Photographer / archivist credit |
| `topic` | string | — | **Accepted and discarded.** `buildContent()` never reads it and `ImageContent` has no such column |

If all four persisted fields are blank the whole content block is stored as `null`, even when the
language is listed in `contentLanguages`.

**`InlineTopicRequest`** (`newTopic`)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `nameCkb` | string | max 300 | Sorani topic name |
| `nameKmr` | string | max 300 | Kurmanji topic name |

The created topic is saved with `entityType = "IMAGE"`. There is no duplicate check — posting the
same `newTopic` twice creates two topics.

**`ImageItemDto`** (`imageAlbum[]`, request side)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | Update only | Existing `ImageAlbumItem.id`. Must belong to this collection. Ignored on create |
| `imageUrl` | string | Conditional | Direct S3/CDN URL. Overwritten when a file is paired with this index |
| `externalUrl` | string | Conditional | External page link |
| `embedUrl` | string | Conditional | Iframe-ready embed URL |
| `captionCkb` | string | No | max 500 |
| `captionKmr` | string | No | max 500 |
| `descriptionCkb` | string (HTML) | No | Tiptap HTML, processed like the content descriptions |
| `descriptionKmr` | string (HTML) | No | Same |
| `sortOrder` | integer | No | Defaults to the item's index in the request when omitted |
| `fileSizeBytes`, `widthPx`, `heightPx`, `mimeType`, `aspectRatio`, `humanReadableSize` | — | — | Response-only. Accepted by the parser and then ignored; the server derives them |

Each item needs a source: a paired upload file, or one of `imageUrl` / `externalUrl` / `embedUrl`.
On update, an item identified by `id` may omit all of them and keep its persisted source.

### `UpdateRequest`

Same shape as `CreateRequest` with three differences:

* `collectionType` and `contentLanguages` are **not** `@NotNull` — omit them to leave the stored
  values alone.
* `clearTopic` (boolean, default `false`) is added. When `true`, the topic link is set to `null` and
  `topicId` / `newTopic` are ignored entirely.
* `imageAlbum` is documented in the source as "null = keep existing, non-null = replace entirely".

---

## The cover rule

`validateCreate` requires **either** a non-empty `ckbCoverImage` file part **or** at least one
non-blank URL among `ckbCoverUrl`, `kmrCoverUrl`, `hoverCoverUrl`. If neither is satisfied the request
fails with `IMAGE_VALIDATION` and `details.field` set to
`"ckbCoverImage | ckbCoverUrl | kmrCoverUrl | hoverCoverUrl"`.

> **Note:** The file half of that check inspects `ckbCoverImage` only. Uploading just `kmrCoverImage`
> and/or `hoverCoverImage`, with no URL anywhere, is rejected — even though the request clearly
> carries a cover. Work around it by sending `ckbCoverImage`, or by including any one of the three
> cover URLs.

There is no cover rule on update: `PUT` accepts a request that changes nothing about the covers.

---

## Album construction

Both create and update build the album by walking a single index `i` from `0` to
`max(nonEmptyFileCount, imageAlbum.size()) - 1` and pairing:

* **file `i`** — the *i*-th **non-empty** part named `images`, in the order the parts arrive. Empty
  parts are skipped, not counted, and never consume an index.
* **dto `i`** — `imageAlbum[i]`, or `null` when the array is shorter than the file list.

Then, per index:

| Situation | Result |
|-----------|--------|
| A file is paired | The file is uploaded to S3; `imageUrl` is set to the resulting URL; `externalUrl` and `embedUrl` are forced to `null`; width/height/size/MIME are extracted from the bytes. **Any `imageUrl` / `externalUrl` / `embedUrl` in the paired DTO is ignored.** |
| No file, DTO carries a source | `imageUrl` / `externalUrl` / `embedUrl` are copied verbatim (trimmed); the metadata fields are cleared to `null` — they cannot be read from a remote URL |
| No file, no DTO source, and (update only) an existing item matched by `id` that already has a source | The persisted source and its extracted metadata are kept |
| No file, no DTO source, nothing persisted | `400 IMAGE_VALIDATION`, key `image.source.required` |

`sortOrder` is `dto.sortOrder` when supplied, otherwise the index `i`. Captions and descriptions come
from the paired DTO; when there is no paired DTO they are left unset (create) or **cleared** (update).

> **Note:** The index pairing means **file-backed items must occupy the leading positions of
> `imageAlbum`**. Sending one file plus `imageAlbum = [{ "imageUrl": "https://…" }, { "captionCkb": "…" }]`
> attaches the file to index 0 (discarding that item's `imageUrl`) and then fails at index 1 with
> `image.source.required`. Order the array so every uploaded picture comes first.

### Collection-type size rules

`validateAlbumItemCount` runs against the *planned* album size, i.e. `max(fileCount, dtoCount)`:

| `collectionType` | Rule | Error key on violation | `details` |
|------------------|------|------------------------|-----------|
| `SINGLE` | exactly 1 | `imageCollection.single.invalid` | `message`, `count` |
| `GALLERY` | at least 1 | `imageCollection.gallery.invalid` | `message` |
| `PHOTO_STORY` | at least 2 | `imageCollection.photoStory.invalid` | `message`, `count` |

On update, when the request does **not** touch the album but does change `collectionType`, the rule is
applied to the currently persisted album size instead — so you cannot switch a 5-picture gallery to
`SINGLE` without also sending a 1-item album.

---

## 1. `POST /api/v1/image-collections` — Create (multipart)

Creates a collection and uploads its covers and album pictures in one request. This is the route the
dashboard's "new collection" form uses.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Consumes:** `multipart/form-data`
**Produces:** `application/json`

**Parts**

| Part name | Content type | Required | Cardinality | Description |
|-----------|--------------|----------|-------------|-------------|
| `data` | `application/json` (recommended) | **Yes** | 1 | The `CreateRequest` JSON blob |
| `ckbCoverImage` | `image/*` | No | 0–1 | Sorani cover. Overrides `data.ckbCoverUrl` |
| `kmrCoverImage` | `image/*` | No | 0–1 | Kurmanji cover. Overrides `data.kmrCoverUrl` |
| `hoverCoverImage` | `image/*` | No | 0–1 | Hover overlay. Overrides `data.hoverCoverUrl` |
| `images` | `image/*` | No | 0–n | Album pictures, index-paired with `data.imageAlbum` |

The handler binds `data` as `@RequestPart("data") String` and parses it with the application's own
`ObjectMapper` bean, so the part's declared content type is not enforced — a plain `text/plain` part
containing JSON works. Setting `application/json` is still the recommended, self-documenting choice
(`MultipartJsonConfig` is a no-op marker class; Spring Boot needs no converter registration for this).

> **Note:** That `ObjectMapper` (`khi_app/config/JacksonConfig.java`) leaves Jackson's
> `FAIL_ON_UNKNOWN_PROPERTIES` at its default and installs a `DeserializationProblemHandler` that
> tolerates exactly one unknown property: `id`. Any other unrecognised key inside `data` is rejected
> with `400 BAD_REQUEST`, `message` = `"Unknown field in request body: <name>"` and
> `details.unknownField` naming it. The `id` exemption exists so a response-shaped payload can be
> posted back unchanged. The `/json` route (endpoint 2) does **not** use this bean — it is
> deserialized by the framework's auto-configured mapper, whose unknown-property strictness is a
> separate setting. Do not rely on either route to catch a typo'd field name for you.

**Upload limits.** `max-file-size: 1GB`, `max-request-size: 1GB`, `file-size-threshold: 2MB`. Tomcat
is configured with `max-swallow-size: 1GB`, `max-http-form-post-size: 1GB` and
`max-parameter-count: 10000`.

**Request body (`data` part)**

```json
{
  "slugCkb": "qelay-hewler-1932",
  "slugKmr": "keleha-hewler-1932",
  "collectionType": "GALLERY",
  "topicId": 7,
  "publishmentDate": "2026-06-12",
  "contentLanguages": ["CKB", "KMR"],
  "ckbContent": {
    "title": "قەڵای هەولێر لە ساڵی ١٩٣٢",
    "description": "<p>کۆمەڵەیەک وێنەی مێژوویی لە قەڵای هەولێر، کۆکراوەتەوە لە ئەرشیفی خێزانی.</p>",
    "location": "هەولێر، هەرێمی کوردستان",
    "collectedBy": "ئارام محەمەد"
  },
  "kmrContent": {
    "title": "Keleha Hewlêrê di sala 1932'an de",
    "description": "<p>Komek wêneyên dîrokî ji Keleha Hewlêrê, ji arşîva malbatî hatine berhevkirin.</p>",
    "location": "Hewlêr, Herêma Kurdistanê",
    "collectedBy": "Aram Mihemed"
  },
  "tags": {
    "ckb": ["قەڵا", "مێژوو", "هەولێر"],
    "kmr": ["Kele", "Dîrok", "Hewlêr"]
  },
  "keywords": {
    "ckb": ["ئەرشیفی وێنە", "کوردستان ١٩٣٢"],
    "kmr": ["arşîva wêneyan", "Kurdistan 1932"]
  },
  "imageAlbum": [
    {
      "captionCkb": "دەروازەی سەرەکی قەڵا",
      "captionKmr": "Deriyê sereke yê keleyê",
      "descriptionCkb": "<p>دەروازەی باشووری قەڵا، وێنەگیراوە لە بەهاری ١٩٣٢.</p>",
      "descriptionKmr": "<p>Deriyê başûrî yê keleyê, di bihara 1932'an de hatiye wênekirin.</p>",
      "sortOrder": 0
    },
    {
      "captionCkb": "بازاڕی قەیسەری",
      "captionKmr": "Sûka Qeyserî",
      "sortOrder": 1
    },
    {
      "externalUrl": "https://www.flickr.com/photos/kurdistan-archive/51234567890",
      "captionCkb": "دیمەنی گشتی شار لە قەڵاوە",
      "captionKmr": "Dîmena giştî ya bajêr ji keleyê",
      "sortOrder": 2
    }
  ]
}
```

With two `images` parts attached, index 0 and 1 take the uploaded files (their captions come from the
first two array entries) and index 2 keeps the Flickr `externalUrl`.

**Response `201 Created`**

```json
{
  "success": true,
  "message": "Image collection created successfully",
  "data": {
    "id": 42,
    "slugCkb": "qelay-hewler-1932",
    "slugKmr": "keleha-hewler-1932",
    "collectionType": "GALLERY",
    "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f1c2d43-6b9a-4d21-9c0e-1a2b3c4d5e6f-qelay-hewler-cover-ckb.jpg",
    "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/c71e9a04-2f55-43b8-8a17-0d9e7c6b5a44-keleha-hewler-cover-kmr.jpg",
    "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/2d4f6a81-9c3b-4e70-b5d2-77aa1c8e3f90-qelay-hewler-hover.jpg",
    "topicId": 7,
    "topicNameCkb": "کەلەپوور",
    "topicNameKmr": "Kelepûr",
    "publishmentDate": "2026-06-12",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "قەڵای هەولێر لە ساڵی ١٩٣٢",
      "description": "<p>کۆمەڵەیەک وێنەی مێژوویی لە قەڵای هەولێر، کۆکراوەتەوە لە ئەرشیفی خێزانی.</p>",
      "location": "هەولێر، هەرێمی کوردستان",
      "collectedBy": "ئارام محەمەد"
    },
    "kmrContent": {
      "title": "Keleha Hewlêrê di sala 1932'an de",
      "description": "<p>Komek wêneyên dîrokî ji Keleha Hewlêrê, ji arşîva malbatî hatine berhevkirin.</p>",
      "location": "Hewlêr, Herêma Kurdistanê",
      "collectedBy": "Aram Mihemed"
    },
    "tags": {
      "ckb": ["قەڵا", "مێژوو", "هەولێر"],
      "kmr": ["Kele", "Dîrok", "Hewlêr"]
    },
    "keywords": {
      "ckb": ["ئەرشیفی وێنە", "کوردستان ١٩٣٢"],
      "kmr": ["arşîva wêneyan", "Kurdistan 1932"]
    },
    "imageAlbum": [
      {
        "id": 118,
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a3c8d5f2-7e14-4b96-92a0-5f6e7d8c9b01-qelay-hewler-01.jpg",
        "captionCkb": "دەروازەی سەرەکی قەڵا",
        "captionKmr": "Deriyê sereke yê keleyê",
        "descriptionCkb": "<p>دەروازەی باشووری قەڵا، وێنەگیراوە لە بەهاری ١٩٣٢.</p>",
        "descriptionKmr": "<p>Deriyê başûrî yê keleyê, di bihara 1932'an de hatiye wênekirin.</p>",
        "sortOrder": 0,
        "fileSizeBytes": 2517891,
        "widthPx": 3000,
        "heightPx": 2000,
        "mimeType": "image/jpeg",
        "aspectRatio": 1.5,
        "humanReadableSize": "2.4 MB"
      },
      {
        "id": 119,
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/f04b7e63-1d28-49ac-b3f5-6e8a90c1d2b3-bazari-qeyseri.jpg",
        "captionCkb": "بازاڕی قەیسەری",
        "captionKmr": "Sûka Qeyserî",
        "sortOrder": 1,
        "fileSizeBytes": 871424,
        "widthPx": 2048,
        "heightPx": 1365,
        "mimeType": "image/jpeg",
        "aspectRatio": 1.5003663003663004,
        "humanReadableSize": "851.0 KB"
      },
      {
        "id": 120,
        "externalUrl": "https://www.flickr.com/photos/kurdistan-archive/51234567890",
        "captionCkb": "دیمەنی گشتی شار لە قەڵاوە",
        "captionKmr": "Dîmena giştî ya bajêr ji keleyê",
        "sortOrder": 2
      }
    ],
    "createdAt": "2026-06-12T10:04:31",
    "updatedAt": "2026-06-12T10:04:31"
  }
}
```

**Errors**

| Status | `code` | Message key | When |
|--------|--------|-------------|------|
| `400` | `IMAGE_VALIDATION` | `imageCollection.type.required` | `collectionType` missing. `details.field = "collectionType"` |
| `400` | `IMAGE_VALIDATION` | `imageCollection.languages.required` | `contentLanguages` missing or empty. `details.field = "contentLanguages"` |
| `400` | `IMAGE_VALIDATION` | `imageCollection.cover.required` | No `ckbCoverImage` file and no cover URL |
| `400` | `IMAGE_VALIDATION` | `imageCollection.single.invalid` | `SINGLE` with an album size other than 1 |
| `400` | `IMAGE_VALIDATION` | `imageCollection.gallery.invalid` | `GALLERY` with an empty album |
| `400` | `IMAGE_VALIDATION` | `imageCollection.photoStory.invalid` | `PHOTO_STORY` with fewer than 2 items |
| `400` | `IMAGE_VALIDATION` | `image.source.required` | An album index has neither a file nor a URL |
| `400` | `IMAGE_VALIDATION` | `error.validation` | `newTopic` supplied with both names blank |
| `400` | `IMAGE_VALIDATION` | `topic.type.mismatch` | `topicId` points at a topic whose `entityType` is not `IMAGE` |
| `400` | `BAD_REQUEST` | `error.http.unknown_field` | Unknown property inside the `data` JSON (`UnrecognizedPropertyException`). `message` reads `"Unknown field in request body: <name>"`, `details.unknownField` names it |
| `400` | `BAD_REQUEST` | `error.http.multipart` | Malformed multipart envelope |
| `400` | `BAD_REQUEST` | `s3.upload.failed` | S3 rejected the `PutObject`, or a part decoded to zero bytes |
| `401` / `403` | varies | — | Token missing, expired, revoked or invalid; or the role is below `EMPLOYEE`. See [Authentication and authorization failures](#authentication-and-authorization-failures) — the body shape differs by layer |
| `404` | `NOT_FOUND` | `topic.not_found` | `topicId` does not exist. `details.id` echoes it |
| `409` | `CONFLICT` | `error.db.conflict` | `slugCkb` or `slugKmr` already taken (DB unique violation) |
| `413` | `PAYLOAD_TOO_LARGE` | `error.http.payload_too_large` | A part exceeds 1 GB |
| `500` | `INTERNAL_ERROR` | `error.internal` | The `data` part is absent or contains malformed JSON — see the note below |
| `502` | `STORAGE_ERROR` | `image.media_upload_failed` | `IOException` while reading a part's bytes. `details.reason` carries the cause |

Example validation failure:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/image-collections",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "IMAGE_VALIDATION",
  "message": "Image validation error",
  "messageEn": "Image validation error",
  "messageKu": "هەڵەی پشکنینەوە لە داتای وێنەدا",
  "details": {
    "message": "جۆری PHOTO_STORY پێویستی بە لانیکەم ٢ وێنەیە",
    "count": 1
  }
}
```

The image domain has no rows in `src/main/resources/i18n/*.properties`, so `message` / `messageEn` /
`messageKu` fall back to the generic per-code text from `GlobalExceptionHandler.fallbackByCode(...)`.
**The precise, actionable text is in `details`, and it is written in Sorani.** Surface `details.message`
in the dashboard, not `messageEn`.

> **Note:** Message resolution is broken in two independent ways, so `messageEn` and `messageKu` are
> effectively always the handler's hard-coded literals rather than translations.
> 1. The English bundle is on disk as `src/main/resources/i18n/ messages_en.properties` — with a
>    **leading space in the filename**. The `MessageSource` basename is `classpath:i18n/messages`, so
>    it looks for `i18n/messages_en.properties` and never finds the file. (The space survives into the
>    built jar as `BOOT-INF/classes/i18n/ messages_en.properties`.)
> 2. `messageKu` is resolved with `Locale.forLanguageTag("ku")`, but the Kurdish bundles are named
>    `messages_ckb.properties` and `messages_kmr.properties`. There is no `messages_ku.properties` and
>    no default `messages.properties`, so that lookup always misses too.
>
> Only `message` can pick up a real translation, and only when the client sends
> `Accept-Language: ckb` or `kmr` **and** the key exists in that bundle. Everything else falls through
> to the literal string the handler passes as its default.

> **Note:** A missing `data` part raises `MissingServletRequestPartException` and malformed JSON in it
> raises a Jackson parse error; neither has an `@ExceptionHandler`, so both are caught by
> `@ExceptionHandler(Exception.class)` and answered as **500 `INTERNAL_ERROR`** instead of 400.
> (`UnrecognizedPropertyException` is the one Jackson failure that *is* mapped, to 400.)

> **Note:** `@Valid` is not applied on this route — the `data` part is parsed by hand, so the
> `@NotNull` annotations on `CreateRequest` never run. Those two fields are still enforced, but by the
> service, which reports them as `IMAGE_VALIDATION` with a `details` map rather than as
> `VALIDATION_ERROR` with a `fieldErrors` array. Endpoint 2 produces the `fieldErrors` shape for the
> identical mistake.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/image-collections \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data=@collection.json;type=application/json' \
  -F 'ckbCoverImage=@qelay-hewler-cover-ckb.jpg;type=image/jpeg' \
  -F 'kmrCoverImage=@keleha-hewler-cover-kmr.jpg;type=image/jpeg' \
  -F 'hoverCoverImage=@qelay-hewler-hover.jpg;type=image/jpeg' \
  -F 'images=@qelay-hewler-01.jpg;type=image/jpeg' \
  -F 'images=@bazari-qeyseri.jpg;type=image/jpeg'
```

Inline JSON instead of a file, for a quick `SINGLE`:

```bash
curl -s -X POST http://localhost:8080/api/v1/image-collections \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"collectionType":"SINGLE","contentLanguages":["CKB"],"ckbContent":{"title":"دەروازەی قەڵا"},"imageAlbum":[{"captionCkb":"دەروازەی سەرەکی"}]};type=application/json' \
  -F 'ckbCoverImage=@cover.jpg;type=image/jpeg' \
  -F 'images=@gate.jpg;type=image/jpeg'
```

---

## 2. `POST /api/v1/image-collections/json` — Create (JSON, URL-only)

The same create operation with no upload capability. The controller calls
`imageCollectionService.create(dto, null, null, null, null)` — all four file arguments are hard-coded
to `null` — so every picture must be supplied as a URL. Use this when the assets already live in S3 or
on a third-party host.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Consumes:** `application/json`
**Produces:** `application/json`

**Request body:** a `CreateRequest`, validated with `@Valid`. Because no file can be attached:

* at least one of `ckbCoverUrl` / `kmrCoverUrl` / `hoverCoverUrl` is **mandatory**;
* every `imageAlbum[i]` must carry `imageUrl`, `externalUrl` or `embedUrl`;
* `imageAlbum` must be present and satisfy the collection-type size rule (a `GALLERY` with no
  `imageAlbum` fails with `imageCollection.gallery.invalid`).

```json
{
  "slugCkb": "cil-u-bergen-kurdi-behdinan",
  "slugKmr": "cil-u-bergen-kurdi-behdinan-kmr",
  "collectionType": "PHOTO_STORY",
  "newTopic": {
    "nameCkb": "جل و بەرگی کوردی",
    "nameKmr": "Cil û bergên kurdî"
  },
  "publishmentDate": "2026-07-03",
  "contentLanguages": ["CKB", "KMR"],
  "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/71a4c9d8-3e56-4b20-9f81-2c3d4e5f6a70-cil-cover-ckb.jpg",
  "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/95b6d1e0-8a47-4c39-bd12-3f4a5b6c7d81-cil-cover-kmr.jpg",
  "ckbContent": {
    "title": "چۆنیەتی دروستکردنی جلی کوردی لە بادینان",
    "description": "<p>چیرۆکێکی وێنەیی لە کارگەیەکی دەستکرد لە دهۆک.</p>",
    "location": "دهۆک، هەرێمی کوردستان",
    "collectedBy": "شیلان یوسف"
  },
  "kmrContent": {
    "title": "Çawa cilên kurdî li Behdînanê tên çêkirin",
    "description": "<p>Çîrokek wênedar ji atolyeyeke destan li Duhokê.</p>",
    "location": "Duhok, Herêma Kurdistanê",
    "collectedBy": "Şîlan Yûsif"
  },
  "tags": {
    "ckb": ["جلوبەرگ", "پیشەسازی دەستی"],
    "kmr": ["Cil û berg", "Pîşesaziya destan"]
  },
  "keywords": {
    "ckb": ["بادینان", "دهۆک"],
    "kmr": ["Behdînan", "Duhok"]
  },
  "imageAlbum": [
    {
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f-cil-01.jpg",
      "captionCkb": "هەڵبژاردنی قوماش",
      "captionKmr": "Bijartina qumaşê",
      "sortOrder": 0
    },
    {
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6f7a8b9c-0d1e-4f2a-b3c4-d5e6f7a8b9c0-cil-02.jpg",
      "captionCkb": "بڕین و دروومان",
      "captionKmr": "Birîn û dirûtin",
      "sortOrder": 1
    },
    {
      "embedUrl": "https://player.vimeo.com/video/912345678",
      "captionCkb": "تەواوکردنی جلەکە",
      "captionKmr": "Temamkirina cilê",
      "sortOrder": 2
    }
  ]
}
```

**Response `201 Created`**

Same envelope and same `"Image collection created successfully"` message as endpoint 1, with an
`imageAlbum` whose items carry no `fileSizeBytes` / `widthPx` / `heightPx` / `mimeType` /
`aspectRatio` / `humanReadableSize` (nothing was uploaded, so nothing could be measured).

**Errors**

Everything from endpoint 1 except the multipart-specific rows, plus:

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `@Valid` rejected the body. Response carries a `fieldErrors` array |
| `400` | `BAD_REQUEST` | Body is empty or not readable JSON (`HttpMessageNotReadableException`). `message` reads `"The request body is missing or contains invalid JSON."`, `details.hint` lists the usual causes |
| `500` | `INTERNAL_ERROR` | `Content-Type` is not `application/json`. Spring raises `HttpMediaTypeNotSupportedException`, which has no `@ExceptionHandler`, so the catch-all answers 500 where 415 would be correct |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/image-collections/json",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "One or more fields failed validation.",
  "messageEn": "One or more fields failed validation.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    {
      "field": "collectionType",
      "message": "collectionType is required",
      "messageEn": "collectionType is required",
      "messageKu": "collectionType is required"
    },
    {
      "field": "contentLanguages",
      "message": "At least one content language is required",
      "messageEn": "At least one content language is required",
      "messageKu": "At least one content language is required"
    }
  ]
}
```

The per-field messages are the raw `@NotNull(message = ...)` strings from `CreateRequest`; they are
looked up in the message source first, miss, and fall through unchanged into all three language slots.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/image-collections/json \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @collection.json
```

---

## 3. `PUT /api/v1/image-collections/{id}` — Update (multipart)

Partial update. Fields absent from the `data` JSON are left alone; the album is the exception — the
moment you touch it, it is replaced wholesale.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Consumes:** `multipart/form-data`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | Yes | `ImageCollection.id` |

**Parts:** identical to endpoint 1 (`data` required; `ckbCoverImage`, `kmrCoverImage`,
`hoverCoverImage`, `images` optional), with `data` holding an `UpdateRequest`.

### Per-field update semantics

| Field | Omitted / `null` | Value supplied |
|-------|------------------|----------------|
| `slugCkb`, `slugKmr` | Unchanged | Set (trimmed). `""` clears the slug to `null` |
| `collectionType` | Unchanged | Set — and the album-size rule is re-checked against the resulting album |
| `ckbCoverUrl` / `kmrCoverUrl` / `hoverCoverUrl` | Unchanged | Set only when non-blank, and only when the matching file part is absent |
| `ckbCoverImage` / `kmrCoverImage` / `hoverCoverImage` (parts) | Unchanged | Uploaded and stored, winning over the matching URL field |
| `topicId` / `newTopic` | Unchanged | Topic re-linked or created — unless `clearTopic` is `true` |
| `clearTopic: true` | — | Topic set to `null`; `topicId` and `newTopic` are ignored |
| `publishmentDate` | Unchanged | Set |
| `contentLanguages` | Unchanged | **Replaced.** Any language dropped from the set has its content block destroyed (`ckbContent` / `kmrContent` set to `null`) |
| `ckbContent` / `kmrContent` | Unchanged | Merged field by field into the existing block — only non-null keys overwrite. If no block existed, a new one is built |
| `tags` / `keywords` | Unchanged | Per language: `ckb` and `kmr` are handled independently, each replaced entirely when non-null. `{"ckb": []}` clears Sorani tags and leaves Kurmanji alone |
| `imageAlbum` | Album unchanged, **unless** non-empty `images` parts are present | **Replaces the whole album** |

> **Note:** A cover URL cannot be cleared through this endpoint. The guard is
> `else if (!isBlank(dto.getCkbCoverUrl()))`, so `""` and `null` are both treated as "leave it alone".
> Only a new file or a new non-blank URL changes a cover.

### Album update rules

`updatesAlbum` is true when `data.imageAlbum` is non-null **or** at least one non-empty `images` part
is present. When it is true:

* the merged list becomes the entire album; **any persisted item whose `id` is not in
  `data.imageAlbum` is deleted** (`orphanRemoval = true`);
* `imageAlbum[i].id` must belong to this collection — an unknown id is a `400`;
* the same `id` may not appear twice — a duplicate is a `400`;
* an item identified by `id` that omits every source field keeps its persisted source and metadata;
* an item that repeats its persisted source verbatim also keeps its metadata (the service compares
  the three URL fields before deciding to clear);
* an item whose source actually changes has `fileSizeBytes`, `widthPx`, `heightPx` and `mimeType`
  reset to `null`.

> **Note:** Captions and descriptions are **not** merged. `mergeAlbumItems` assigns
> `dto != null ? trimOrNull(dto.getCaptionCkb()) : null` for all four text fields, so an item whose
> DTO omits `captionCkb` has its stored caption wiped — and uploading files with no `imageAlbum`
> array at all wipes every caption and description in the collection. Always send the full text for
> every item you keep. This is also why the safest edit flow is: `GET /{id}`, mutate the returned
> `imageAlbum` array, and send it back whole (the stray `id` fields are tolerated by the parser
> exactly for this).

### Validation ordering

The service validates the complete album plan **before** processing Tiptap content or uploading any
cover or album file, because S3 writes cannot be rolled back with the database transaction. Topic
resolution happens next, then the mutations. A validation failure therefore leaves S3 untouched.

**Request body (`data` part) — retitle, re-tag, keep the album**

```json
{
  "collectionType": "GALLERY",
  "publishmentDate": "2026-06-20",
  "ckbContent": {
    "title": "قەڵای هەولێر لە نێوان ١٩٣٠ و ١٩٣٥"
  },
  "tags": {
    "ckb": ["قەڵا", "مێژوو", "هەولێر", "یونسکۆ"]
  }
}
```

**Request body — reorder the album, drop one picture, add one upload**

```json
{
  "imageAlbum": [
    {
      "id": 119,
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/f04b7e63-1d28-49ac-b3f5-6e8a90c1d2b3-bazari-qeyseri.jpg",
      "captionCkb": "بازاڕی قەیسەری",
      "captionKmr": "Sûka Qeyserî",
      "sortOrder": 1
    },
    {
      "id": 118,
      "captionCkb": "دەروازەی سەرەکی قەڵا",
      "captionKmr": "Deriyê sereke yê keleyê",
      "descriptionCkb": "<p>دەروازەی باشووری قەڵا، وێنەگیراوە لە بەهاری ١٩٣٢.</p>",
      "descriptionKmr": "<p>Deriyê başûrî yê keleyê, di bihara 1932'an de hatiye wênekirin.</p>",
      "sortOrder": 0
    },
    {
      "captionCkb": "مزگەوتی قەڵا",
      "captionKmr": "Mizgefta keleyê",
      "sortOrder": 2
    }
  ]
}
```

Item 118 omits every source field and keeps its stored `imageUrl` plus its extracted dimensions.
Item 119 repeats its URL verbatim, so its metadata survives too. The former item 120 (the Flickr link)
is absent from the array and is deleted. The third entry has no `id`, so it takes the single attached
`images` part.

> **Note:** The index pairing bites here. Because the file is meant for the *third* array entry, the
> request above only works if it is the **only** entry without a persisted source *and* it sits at the
> index the file lands on. With one file part, the file always goes to index 0 — which would overwrite
> item 119. To attach a file to a specific position, put that entry first in `imageAlbum`, or send the
> picture as a pre-uploaded `imageUrl` instead.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Image collection updated successfully",
  "data": {
    "id": 42,
    "slugCkb": "qelay-hewler-1932",
    "slugKmr": "keleha-hewler-1932",
    "collectionType": "GALLERY",
    "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8f1c2d43-6b9a-4d21-9c0e-1a2b3c4d5e6f-qelay-hewler-cover-ckb.jpg",
    "topicId": 7,
    "topicNameCkb": "کەلەپوور",
    "topicNameKmr": "Kelepûr",
    "publishmentDate": "2026-06-20",
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": {
      "title": "قەڵای هەولێر لە نێوان ١٩٣٠ و ١٩٣٥",
      "description": "<p>کۆمەڵەیەک وێنەی مێژوویی لە قەڵای هەولێر، کۆکراوەتەوە لە ئەرشیفی خێزانی.</p>",
      "location": "هەولێر، هەرێمی کوردستان",
      "collectedBy": "ئارام محەمەد"
    },
    "kmrContent": {
      "title": "Keleha Hewlêrê di sala 1932'an de",
      "location": "Hewlêr, Herêma Kurdistanê",
      "collectedBy": "Aram Mihemed"
    },
    "tags": {
      "ckb": ["قەڵا", "مێژوو", "هەولێر", "یونسکۆ"],
      "kmr": ["Kele", "Dîrok", "Hewlêr"]
    },
    "keywords": {
      "ckb": ["ئەرشیفی وێنە", "کوردستان ١٩٣٢"],
      "kmr": ["arşîva wêneyan", "Kurdistan 1932"]
    },
    "imageAlbum": [
      {
        "id": 118,
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a3c8d5f2-7e14-4b96-92a0-5f6e7d8c9b01-qelay-hewler-01.jpg",
        "captionCkb": "دەروازەی سەرەکی قەڵا",
        "captionKmr": "Deriyê sereke yê keleyê",
        "sortOrder": 0,
        "fileSizeBytes": 2517891,
        "widthPx": 3000,
        "heightPx": 2000,
        "mimeType": "image/jpeg",
        "aspectRatio": 1.5,
        "humanReadableSize": "2.4 MB"
      },
      {
        "id": 119,
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/f04b7e63-1d28-49ac-b3f5-6e8a90c1d2b3-bazari-qeyseri.jpg",
        "captionCkb": "بازاڕی قەیسەری",
        "captionKmr": "Sûka Qeyserî",
        "sortOrder": 1,
        "fileSizeBytes": 871424,
        "widthPx": 2048,
        "heightPx": 1365,
        "mimeType": "image/jpeg",
        "aspectRatio": 1.5003663003663004,
        "humanReadableSize": "851.0 KB"
      }
    ],
    "createdAt": "2026-06-12T10:04:31",
    "updatedAt": "2026-06-20T14:38:02"
  }
}
```

**Errors**

| Status | `code` | Message key | When |
|--------|--------|-------------|------|
| `400` | `IMAGE_VALIDATION` | `imageCollection.single.invalid` / `gallery.invalid` / `photoStory.invalid` | Resulting album size violates the collection-type rule |
| `400` | `IMAGE_VALIDATION` | `image.source.required` | An album index has no file, no URL and no persisted source. `details.field = "imageAlbum[2]"` |
| `400` | `IMAGE_VALIDATION` | `error.validation` | `imageAlbum[i].id` does not belong to this collection (`details.message` = `وێنەکە لەم کۆمەڵەیەدا نەدۆزرایەوە`) or repeats (`ئایدی وێنە نابێت دووبارە بێتەوە`). `details.field` and `details.id` pinpoint it |
| `400` | `IMAGE_VALIDATION` | `error.validation` | `newTopic` supplied with both names blank |
| `400` | `IMAGE_VALIDATION` | `topic.type.mismatch` | `topicId` belongs to a non-`IMAGE` topic |
| `400` | `BAD_REQUEST` | `error.http.unknown_field` | Unknown property in the `data` JSON (other than `id`). `details.unknownField` names it |
| `400` | `BAD_REQUEST` | `s3.upload.failed` | S3 `PutObject` failed |
| `401` / `403` | varies | — | Token missing, expired, revoked or invalid; or the role is below `EMPLOYEE`. See [Authentication and authorization failures](#authentication-and-authorization-failures) |
| `404` | `IMAGE_NOT_FOUND` | `imageCollection.not_found` | No collection with that id. `details.id` echoes it |
| `404` | `NOT_FOUND` | `topic.not_found` | `topicId` does not exist |
| `409` | `CONFLICT` | `error.db.conflict` | New slug collides with another row |
| `413` | `PAYLOAD_TOO_LARGE` | `error.http.payload_too_large` | Part over 1 GB |
| `500` | `INTERNAL_ERROR` | `error.internal` | `data` part missing / malformed JSON, or a non-numeric `{id}` |
| `502` | `STORAGE_ERROR` | `image.media_upload_failed` | `IOException` reading a part |

**Example**

```bash
# Metadata only, album untouched
curl -s -X PUT http://localhost:8080/api/v1/image-collections/42 \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"ckbContent":{"title":"قەڵای هەولێر لە نێوان ١٩٣٠ و ١٩٣٥"},"publishmentDate":"2026-06-20"};type=application/json'

# Replace the Sorani cover and unlink the topic
curl -s -X PUT http://localhost:8080/api/v1/image-collections/42 \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"clearTopic":true};type=application/json' \
  -F 'ckbCoverImage=@new-cover-ckb.jpg;type=image/jpeg'

# Rebuild the album from a GET response, appending one upload at position 0
curl -s -X PUT http://localhost:8080/api/v1/image-collections/42 \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data=@album.json;type=application/json' \
  -F 'images=@mizgefta-keleye.jpg;type=image/jpeg'
```

---

## 4. `DELETE /api/v1/image-collections/{id}` — Delete a collection

Deletes the collection row. `ImageCollection.imageAlbum` is mapped with
`cascade = CascadeType.ALL, orphanRemoval = true`, so all of its `image_album_items` rows go with it,
and the `@ElementCollection` side tables (`image_collection_languages`, `image_tags_ckb`,
`image_tags_kmr`, `image_keywords_ckb`, `image_keywords_kmr`) are cleared by Hibernate.

A `DELETE` audit row is written to `image_collection_logs` **before** the delete, capturing the id, the
Sorani (or Kurmanji) title, and the collection type.

The linked `PublishmentTopic` is **not** deleted — topics are shared and outlive their collections.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Produces:** `application/json` (declared), but the body is empty
**Returns:** `204 No Content` with `ResponseEntity.noContent()` — no `ApiResponse` envelope

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | Yes | `ImageCollection.id` |

**Response `204 No Content`** — empty body.

> **Note:** The delete is silently idempotent. `ImageCollectionService.delete` returns early with a
> debug log when the id does not exist, so **deleting a non-existent collection also returns `204`,
> never `404`.** Do not use the status code to confirm the row existed.

> **Note:** No S3 object is ever removed. `ImageCollectionService` never calls
> `S3Service.deleteFile(...)` / `deleteFiles(...)`, on delete or on update. Cover images and album
> pictures uploaded to `s3-khiwebsite` outlive the database rows that referenced them, as do the files
> of album items dropped by a `PUT`. Bucket cleanup is currently a manual, out-of-band job.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` / `403` | varies | Token missing, expired, revoked or invalid; or authenticated as `EMPLOYEE` / `GUEST` — delete is `ADMIN`+ only, unlike create and update. See [Authentication and authorization failures](#authentication-and-authorization-failures) |
| `500` | `INTERNAL_ERROR` | `{id}` is not numeric |

**Example**

```bash
curl -s -i -X DELETE http://localhost:8080/api/v1/image-collections/42 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 5. `PATCH /api/v1/image-collections/{id}/featured` — Feature / unfeature

Flags the collection for the homepage carousel, which is served publicly by
`GET /api/v1/image-collections/featured` and by the aggregated `GET /featured` alias. The handler
delegates to `SiteContentService.setImageCollectionFeatured(id, request)` — it does **not** go through
`ImageCollectionService`.

**Auth:** `Authorization: Bearer <token>`, role `ADMIN` (see the note in [Authentication](#authentication) — `SUPER_ADMIN` is rejected)
**Consumes:** `application/json`
**Returns:** `204 No Content`, empty body

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | Yes | `ImageCollection.id` |

**Request body** — `SiteContentDtos.FeaturedRequest`. Only three of its fields are read here:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `featured` | boolean | No | `true` or omitted/`null` → feature it. `false` → unfeature it and null out `featuredOrder` |
| `featuredOrder` | integer | No | Carousel position, ascending. `null` sorts last. Cleared automatically when unfeaturing |
| `featureImageUrl` | string | No | Wide hero picture. Omit/`null` to leave the stored value alone; send `""` to clear it so the site falls back to the cover |

> **Note:** `FeaturedRequest` also declares `type`, `slug`, `title`, `description`, `imageUrl`,
> `imageAlt`, `locale`, `displayOrder` and `active`, and marks the first five `@NotBlank`. The handler
> does **not** annotate the body with `@Valid` and the service reads none of them, so those constraints
> never run and those values are discarded. `{}` is a perfectly valid body meaning "feature this
> collection with no explicit order".

```json
{
  "featured": true,
  "featuredOrder": 2,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b0c5e19-4a72-4f3d-8e91-c2d3b4a5f607-qelay-hewler-hero.jpg"
}
```

Unfeature:

```json
{ "featured": false }
```

**Global slide cap.** Featuring is refused when the total number of featured records across *all*
content types would exceed `SiteSettings.maxFeaturedSlides`. The count spans news, projects, writings,
videos, sound tracks, image collections and the donation-settings row; the default cap is **7**.
Re-featuring an already-featured collection (changing its order, say) is exempt from the check.

**Response `204 No Content`** — empty body. To read back the new state, call
`GET /api/v1/image-collections/featured`; the `Response` DTO does not expose `featured` or
`featuredOrder`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | The cap is reached. `details.reason` reads `"Maximum of 7 featured slides allowed across all content. Unfeature one first."` (`IllegalStateException`) |
| `400` | `BAD_REQUEST` | Body is not readable JSON |
| `401` / `403` | varies | Token missing, expired, revoked or invalid. See [Authentication and authorization failures](#authentication-and-authorization-failures) |
| `403` | `FORBIDDEN` | Any role other than `ADMIN`, **including `SUPER_ADMIN`**. This one is thrown by `@PreAuthorize`, so it comes back as a proper `ApiErrorResponse` with `code: FORBIDDEN` |
| `404` | `NOT_FOUND` | No collection with that id. `message` is the raw `"Image collection not found: 999"` in all three language slots, and `details.resource` repeats it |
| `500` | `INTERNAL_ERROR` | `{id}` is not numeric |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/image-collections/42/featured",
  "method": "PATCH",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "BAD_REQUEST",
  "message": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "messageEn": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "messageKu": "Maximum of 7 featured slides allowed across all content. Unfeature one first.",
  "details": {
    "reason": "Maximum of 7 featured slides allowed across all content. Unfeature one first."
  }
}
```

**Example**

```bash
curl -s -i -X PATCH http://localhost:8080/api/v1/image-collections/42/featured \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured":true,"featuredOrder":2,"featureImageUrl":"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b0c5e19-4a72-4f3d-8e91-c2d3b4a5f607-qelay-hewler-hero.jpg"}'

curl -s -i -X PATCH http://localhost:8080/api/v1/image-collections/42/featured \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"featured":false}'
```

---

## Enums used by this API

### `ImageCollectionType`

| Value | Meaning | Album size enforced |
|-------|---------|---------------------|
| `SINGLE` | One photograph with its own metadata | exactly 1 |
| `GALLERY` | A photo album — many pictures of equal weight | at least 1 |
| `PHOTO_STORY` | Sequential pictures documenting a story or a process | at least 2 |

Required on create (`@NotNull`), optional on update. Unknown values fail deserialization.

### `Language`

| Value | Meaning |
|-------|---------|
| `CKB` | Kurdish Central — Sorani |
| `KMR` | Kurdish Kurmanji |

`@JsonCreator` trims and upper-cases the input, so `"ckb"` and `" CKB "` both deserialize. An
unrecognised value throws `IllegalArgumentException` out of `Language.valueOf`, which Jackson wraps in
a `JsonMappingException`.

> **Note:** Where that wrapped failure lands differs by route. On `/json` (endpoint 2) the framework's
> message converter re-wraps it as `HttpMessageNotReadableException`, which is mapped to
> `400 BAD_REQUEST` with `details.hint` pointing at the body format. Inside the `data` part of a
> multipart request (endpoints 1 and 3) the exception comes straight out of `objectMapper.readValue`
> and, since only `UnrecognizedPropertyException` has a dedicated handler, is caught by
> `@ExceptionHandler(Exception.class)` and answered as **500 `INTERNAL_ERROR`**. The same asymmetry
> applies to a bad `collectionType` value and to any other malformed JSON. Validate enum values in the
> dashboard before submitting.

---

## S3 key layout

Every upload on these endpoints goes through `S3Service.upload(byte[], filename, contentType)`.

| | |
|---|---|
| **Bucket** | `s3-khiwebsite` |
| **Region** | `us-east-1` |
| **Base folder** | `khi-web-folders` |
| **Key template** | `khi-web-folders/<folder>/<uuid>-<sanitized-filename>` |
| **Public URL** | `https://s3-khiwebsite.s3.us-east-1.amazonaws.com/<key>` |

`<folder>` is chosen from the part's `Content-Type`: `image/*` → `images`, `video/*` → `video`,
`audio/*` → `audio`, anything else → `files`. A client that sends `application/octet-stream` for a JPEG
therefore files it under `files/` — set the part's content type correctly.

`<sanitized-filename>` replaces every character outside `[a-zA-Z0-9._-]` with `_`, so a Kurdish
filename collapses to underscores. The UUID prefix makes collisions impossible; the same original
name can be uploaded any number of times.

Covers and album pictures share the `images/` folder — there is no separate cover prefix. (`S3Service`
does expose `uploadAlbumCover` / `uploadAlbumHover`, which write under `khi-web-folders/albums/covers/`
and `khi-web-folders/albums/hover/`, but `ImageCollectionService` never calls them; those belong to the
separate Albums domain.)

Tiptap `description` HTML is scanned by `TiptapHtmlProcessor` before storage: inline
`src="data:…;base64,…"` and `href="data:…;base64,…"` payloads are decoded, uploaded to the MIME-derived
folder, and the attribute is rewritten to the S3 URL. The processor is idempotent, null-safe, and
resilient — a single failed upload leaves that one attribute untouched and the save still succeeds. It
does **not** sanitize markup.

---

## Notes & gotchas

**Cache invalidation.** `create`, `update` and `delete` are annotated
`@CacheEvict(value = "imageCollections", allEntries = true)`, so every cached public list page is
dropped on any write. The Redis cache uses key prefix `khi:`, a 10-minute TTL and **JDK
serialization** — every type reachable from a cached `Page<Response>` must stay `Serializable` with a
pinned `serialVersionUID = 1L`. `Response`, `LanguageContentDto`, `BilingualSet` and `ImageItemDto` all
comply, and `CacheSerializationTests` enforces it. Adding a non-serializable field to any of them
breaks reads until the TTL expires.

> **Note:** `PATCH /{id}/featured` runs in `SiteContentService`, which carries no `@CacheEvict` for
> `imageCollections`. Featuring or unfeaturing therefore leaves the cached public list pages stale for
> up to 10 minutes. `GET /featured` is uncached and updates immediately.

**Transactions vs. S3.** `create`, `update` and `delete` are `@Transactional`; a failure rolls the
database back. S3 writes are not transactional and are not compensated. `update` mitigates this by
validating the whole album plan before any upload, but `create` uploads the three covers *before*
`buildAlbumItems` runs the size check — so a `PHOTO_STORY` posted with one picture leaves three orphan
cover objects in the bucket.

**Audit log.** Every successful create, update and delete writes a row to `image_collection_logs`
(`imageCollectionId`, `collectionTitle`, `action` = `CREATE` / `UPDATE` / `DELETE`, `details` in
Sorani, `performedBy`, `timestamp`). Log failures are swallowed with a warning and never fail the
request. There is no API to read these logs.

> **Note:** `performedBy` is hard-coded to the literal string `"system"` in
> `ImageCollectionService.createLog(...)`. The authenticated principal is never recorded, so the audit
> trail cannot attribute a change to a user.

**Unused error machinery.** `ImageCollectionConflictException` (`IMAGE_CONFLICT`),
`ImageCollectionMediaException` (`IMAGE_MEDIA_INVALID`) and `ImageCollectionInternalException` exist
and have `Errors` factories, but nothing in `ImageCollectionService` throws them. In practice you will
never see `IMAGE_CONFLICT` or `IMAGE_MEDIA_INVALID` from these endpoints.

> **Note:** As a consequence, a duplicate slug surfaces as the generic
> `DataIntegrityViolationException` handler: `409 CONFLICT`, message `"A record with this data already
> exists."`, and `details.hint` reading **"The username or email you provided is already in use. Please
> choose a different one."** — that hint is written for the user-registration flow and is misleading
> here. Check slug uniqueness client-side before saving.

**No admin search endpoint.** `ImageCollectionService` implements `searchByTag`, `searchByKeyword` and
`globalSearch` (all `@Cacheable`, all backed by indexed `LIKE` queries across titles, descriptions,
`collectedBy`, locations, tags, keywords and topic names in both languages), but
`ImageCollectionController` exposes **no** route to any of them. They are reachable only from other
services. If the dashboard needs image search, the endpoint has to be added.

**Slugs are not generated.** Nothing transliterates a title into a slug, lower-cases it, or checks it
before insert. The dashboard supplies both slugs verbatim, both are nullable, and the only guard is
the database `UNIQUE` constraint surfacing as a 409.

**Topic hygiene.** `newTopic` creates a row every time it is sent — there is no de-duplication against
existing `IMAGE` topics. Prefer `GET /api/v1/image-collections/topics` (public) plus `topicId`, and
reserve `newTopic` for genuinely new taxonomy entries.

**Metadata extraction is best-effort.** `extractAndSetImageMetadata` always records `fileSizeBytes`
and `mimeType` from the multipart part, then attempts `ImageIO.read` for the pixel dimensions. A format
`ImageIO` cannot decode (some HEIC, some AVIF, some CMYK JPEGs) logs a warning and leaves `widthPx` /
`heightPx` — and therefore `aspectRatio` — null. The upload still succeeds. Do not assume dimensions
are present just because `imageUrl` points at S3.

---

## Related documentation

- Counterpart: [`../external/IMAGE_COLLECTION_API.md`](../external/IMAGE_COLLECTION_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
- Live spec: Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs` (group `internal`)
