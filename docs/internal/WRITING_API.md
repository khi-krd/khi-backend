# Writings API — Internal (Authenticated)

The write side of the books catalogue. These five endpoints are what the admin dashboard calls to
publish a book, replace its files, retire it, chain it into a series, and promote it to the homepage
carousel. Every one of them needs a JWT; none of them is reachable by the public website.

Two of the five are `multipart/form-data`: the entire record is sent as a JSON blob in a part named
`data`, alongside up to five binary parts (three cover images and two downloadable book files, one
per language). The public read endpoints — list, detail, series browse and the three searches — are
documented separately in [`../external/WRITING_API.md`](../external/WRITING_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/writings` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/publishment/writing/WritingController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/publishment/writing/WritingService.java`, `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/publishment/writing/WritingDtos.java`, `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java` |
| **Entities** | `Writing`, `WritingContent` (embeddable), `WritingLog`, `PublishmentTopic`, `SiteSettings`, `BookGenre` (editor-managed genre rows) |
| **Enums** | `Language`, `WritingFileFormat` (plus the legacy `BookGenre` request shim) |
| **Storage** | AWS S3, region `us-east-1`, bucket `s3-khiwebsite`, base folder `khi-web-folders` |
| **Verified against source** | 2026-09-03 |

> **Update 2026-09-03 — genres are now editor-managed rows.** Books link to `book_genres` rows
> instead of carrying enum values. Create/update accept a new preferred field
> `"genreIds": [1, 5, 12]`; the old `"bookGenres": ["POETRY"]` keeps working for one release
> (each code resolved against the rows' slugs, aliases normalised as before) and is ignored when
> `genreIds` is non-empty. Genre CRUD itself lives at `/api/v1/book-genres` (admin writes) — full
> contract, migration and seed data in [`../BOOK_GENRES.md`](../BOOK_GENRES.md).

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/writings` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Create a book (multipart) |
| 2 | `PUT` | `/api/v1/writings/{id}` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Update a book (multipart) |
| 3 | `DELETE` | `/api/v1/writings/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a book |
| 4 | `POST` | `/api/v1/writings/series/link` | JWT | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` | Attach an existing book to a series |
| 5 | `PATCH` | `/api/v1/writings/{id}/featured` | JWT | `ADMIN` only | Toggle the homepage carousel flag |
| 6 | `POST` | `/api/v1/book-genres` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a genre row — see [`../BOOK_GENRES.md`](../BOOK_GENRES.md) |
| 7 | `PUT` | `/api/v1/book-genres/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace a genre row (slug immutable while books use it) |
| 8 | `DELETE` | `/api/v1/book-genres/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete a genre, detaching it from every book |

### Where those roles come from

`SecurityConfig` matches by HTTP method against the shared content-path list, which includes
`/api/v1/writings/**`:

- `POST` and `PUT` → `hasAnyRole("EMPLOYEE", "ADMIN", "SUPER_ADMIN")`
- `DELETE` → `hasAnyRole("ADMIN", "SUPER_ADMIN")`
- `PATCH` → **no writings rule exists**. The only `PATCH` matcher covers `/api/v1/videos/**`, so a
  `PATCH` on a writing falls through to `anyRequest().authenticated()` and is then narrowed by the
  `@PreAuthorize("hasRole('ADMIN')")` on the handler method.

> **Note — `SUPER_ADMIN` cannot feature a book.** `Role.SUPER_ADMIN` grants the single authority
> `ROLE_SUPER_ADMIN` (plus the four `user:*` permissions); it does **not** also grant `ROLE_ADMIN`.
> `@PreAuthorize("hasRole('ADMIN')")` therefore rejects a super-admin on endpoint 5 with `403`,
> even though a super-admin can delete the same book. This is a real inconsistency between
> `SecurityConfig`'s `hasAnyRole("ADMIN", "SUPER_ADMIN")` convention and this one handler. Sign in
> as an `ADMIN` to use endpoint 5.

### Sending the token

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

or the HttpOnly cookie whose name comes from the `JWT_COOKIE_NAME` environment variable. The JWT
filter checks the header first and falls back to the cookie. Sessions are stateless
(`SessionCreationPolicy.STATELESS`) — there is nothing server-side to keep alive.

### What a rejected request looks like

There are three distinct shapes, and they are not interchangeable:

| Situation | Status | Body |
|-----------|--------|------|
| No token, or a token the URL rules reject (e.g. a `GUEST` posting a book) | `403` | **Empty.** `SecurityConfig` configures no `exceptionHandling`, so Spring Security's default `Http403ForbiddenEntryPoint` answers with no body at all. |
| Expired token | `401` | `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}` — written directly by `JWTAuthenticationFilter`, **not** an `ApiErrorResponse`. The auth cookie is cleared. |
| Malformed token | `403` | `{"error":"INVALID_TOKEN","message":"Invalid token"}`. Cookie cleared. |
| Revoked / logged-out token | `401` | `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}`. Cookie cleared. |
| `@PreAuthorize` denial on endpoint 5 | `403` | A full `ApiErrorResponse` with `code: "FORBIDDEN"` — this one *does* go through the global handler. |

Everything past the security layer returns the standard `ApiErrorResponse`:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/writings/9999",
  "method": "PUT",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "WRITING_NOT_FOUND",
  "message": "Internal error",
  "messageEn": "Internal error",
  "messageKu": "هەڵەی ناوخۆیی",
  "details": { "id": "9999" }
}
```

> **Note:** `WRITING_NOT_FOUND` has no entry in `messages_*.properties` and no case in the handler's
> `fallbackByCode` switch, so its human-readable text degrades to "Internal error" while the status
> stays a correct `404`. Branch on `status` and `code`, never on `message`.

---

## The series model

Everything on this page that touches `seriesId`, `parentBookId` or `seriesOrder` behaves as
described in the external doc's [series
section](../external/WRITING_API.md#the-series-model), which walks through a three-volume example
end to end. The short version, because you need it to read the request tables below:

- **Every book has a `seriesId`.** `Writing`'s `@PrePersist` fills it with
  `series-<System.currentTimeMillis()>` and sets `seriesOrder` to `1.0` if you did not supply them.
  A standalone book is simply a series of one.
- **`parentBookId` is what actually joins a series.** When it is present the service copies the
  parent's `seriesId` onto the child and **discards any `seriesId` you sent**.
- **`seriesOrder` is a `double`**, so `1.5` slots a companion volume between volumes 1 and 2 without
  renumbering anything.
- **`seriesTotalBooks` is denormalised** and re-stamped onto every book of the affected series after
  each create, update, delete and link.

---

## 1. `POST /api/v1/writings` — Create a book

Creates one bilingual book record, uploading any supplied covers and book files to S3 first. Returns
the fully-hydrated writing object.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data` (required — the handler declares
`consumes = MULTIPART_FORM_DATA_VALUE`)

### Multipart parts

| Part name | Content type | Required | Description |
|-----------|--------------|----------|-------------|
| `data` | `application/json` (or `text/plain`) | **yes** | The whole record as a JSON blob. See the table below. |
| `ckbCoverImage` | `image/*` | no | Sorani cover. Overrides `data.ckbCoverUrl` when present. |
| `kmrCoverImage` | `image/*` | no | Kurmanji cover. Overrides `data.kmrCoverUrl`. |
| `hoverCoverImage` | `image/*` | no | Card hover overlay. Overrides `data.hoverCoverUrl`. |
| `ckbBookFile` | `application/pdf`, `application/epub+zip`, … | no | The downloadable Sorani edition. Overrides `data.ckbContent.fileUrl`. |
| `kmrBookFile` | same | no | The downloadable Kurmanji edition. Overrides `data.kmrContent.fileUrl`. |

The `data` part is bound as a raw `String` and then parsed with the application's own
`ObjectMapper` bean, so setting the part's `Content-Type` to `application/json` is good practice but
not technically required. `MultipartJsonConfig` in the codebase is an intentionally empty marker
class — Spring Boot handles JSON multipart parts natively and no converter registration is needed.

Limits: `spring.servlet.multipart.max-file-size` and `max-request-size` are both **1GB**.

### S3 routing

Uploads go through `S3Service.upload(bytes, filename, contentType)`, which picks a folder from the
part's MIME type and generates a UUID-prefixed, sanitised key:

| MIME prefix | S3 folder | Typical part |
|-------------|-----------|--------------|
| `image/` | `khi-web-folders/images/` | the three cover parts |
| `video/` | `khi-web-folders/video/` | — |
| `audio/` | `khi-web-folders/audio/` | — |
| anything else | `khi-web-folders/files/` | `ckbBookFile`, `kmrBookFile` |

The resulting public URL is
`https://s3-khiwebsite.s3.us-east-1.amazonaws.com/<key>`. Every character in the original file name
outside `[A-Za-z0-9._-]` is replaced with `_`.

### The `data` blob (`WritingDtos.CreateRequest`)

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `contentLanguages` | array of `Language` | **yes** | non-empty, enforced by the service | Which language blocks this book declares — `["CKB"]`, `["KMR"]` or both |
| `genreIds` | array of integer (int64) | one of the two genre fields | at least one genre required, enforced by the service | Ids of `book_genres` rows — the preferred way. An unknown id is a `400` (`Unknown genre id: {id}`). |
| `bookGenres` | array of `BookGenre` codes | one of the two genre fields | — | Legacy alternative, kept for one release: enum codes resolved against the rows' slugs. Ignored when `genreIds` is non-empty. A historical novel is `["HISTORY", "NOVEL"]`. |
| `ckbContent` | `LanguageContentDto` | conditional | required, with a non-blank `title`, when `contentLanguages` contains `CKB` | Sorani text and file metadata |
| `kmrContent` | `LanguageContentDto` | conditional | required, with a non-blank `title`, when `contentLanguages` contains `KMR` | Kurmanji text and file metadata |
| `ckbCoverUrl` | string | no | declared `@Size(max=2000)` | Sorani cover URL. Ignored if `ckbCoverImage` is uploaded. Trimmed; blank becomes null. |
| `kmrCoverUrl` | string | no | declared `@Size(max=2000)` | Kurmanji cover URL |
| `hoverCoverUrl` | string | no | declared `@Size(max=2000)` | Hover overlay URL |
| `topicId` | integer (int64) | no | must exist | Existing `PublishmentTopic`. Takes precedence over `newTopic`. |
| `newTopic` | `{ nameCkb, nameKmr }` | no | each declared `@Size(max=300)` | Creates a new topic inline with `entityType = "WRITING"`. Used only when `topicId` is absent and at least one name is non-blank. |
| `publishedByInstitute` | boolean | no | primitive, defaults to `false` | `true` when KHI itself published the book |
| `tags` | `{ ckb: [...], kmr: [...] }` | no | — | Display labels. Blank entries are dropped, values trimmed. |
| `keywords` | `{ ckb: [...], kmr: [...] }` | no | — | Search terms. Same cleaning. |
| `seriesId` | string | no | declared `@Size(max=100)` | Join an existing series by key. **Silently ignored when `parentBookId` is present.** Auto-generated when both are absent. |
| `seriesName` | string | no | declared `@Size(max=300)` | Display name of the series |
| `seriesOrder` | number (double) | no | declared `@Min(0)` | Position. Defaults to `MAX(seriesOrder)+1` within the parent's series when `parentBookId` is given, otherwise to `1.0`. |
| `parentBookId` | integer (int64) | no | must exist | Root volume of the series to join |

`LanguageContentDto`, used for both `ckbContent` and `kmrContent`:

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `title` | string | yes for a declared language | declared `@Size(max=300)`, DB column 300 | Book title. Trimmed. |
| `description` | string | no | declared `@Size(max=10000)`, DB column `TEXT` | Synopsis as Tiptap HTML. Passed through `TiptapHtmlProcessor` — see the callout below. |
| `writer` | string | no | declared `@Size(max=200)`, DB column 200 | Author name in this language |
| `fileUrl` | string | no | declared `@Size(max=1000)`, DB column 1000 | External book URL. Ignored when the matching `*BookFile` part is uploaded. |
| `fileFormat` | `WritingFileFormat` | no | must be a valid enum value | `PDF`, `EPUB`, `DOCX`, … Case-insensitive on input. |
| `fileSizeBytes` | integer (int64) | no | declared `@Min(0)` | Size in bytes — editor-supplied, never derived |
| `pageCount` | integer | no | declared `@Min(1)` | Page count for this edition |
| `genre` | string | no | declared `@Size(max=150)`, DB column 150 | Free-text genre label, display only. Unrelated to `bookGenres`. |

> **Note — the declared `jakarta.validation` constraints on this DTO are not enforced.** The handler
> takes `data` as a `String` and calls `objectMapper.readValue(...)` by hand; there is no `@Valid`
> anywhere on the multipart path. `@NotNull`, `@NotEmpty`, `@Size` and `@Min` on `CreateRequest`,
> `UpdateRequest` and `LanguageContentDto` are therefore dead annotations. Only the service's own
> checks fire (languages non-empty, genres non-empty, per-language content present, per-language
> title non-blank on create). A `title` longer than 300 characters will reach PostgreSQL and come
> back as a `409 CONFLICT` (`DataIntegrityViolationException`) rather than a `400`. **Validate
> lengths client-side.**

> **Note — unknown fields in `data` are rejected.** The `data` blob is parsed by the application's
> own `ObjectMapper` bean (`khi_app/config/JacksonConfig.java`), which is a bare `new ObjectMapper()`
> and therefore keeps Jackson 2's `FAIL_ON_UNKNOWN_PROPERTIES` default of `true`. A custom
> `DeserializationProblemHandler` whitelists exactly one name — `id` — so you may echo a
> response-shaped payload back, but any other stray field produces
> `400 BAD_REQUEST` with `details.reason` = `Invalid JSON: Unrecognized field "…"`. This is the
> opposite of the plain-JSON endpoints 4 and 5, whose bodies go through the framework's message
> converter and silently ignore unknown properties.

> **Note — `TiptapHtmlProcessor` runs on `description`.** Before persistence, both descriptions are
> scanned for inline `data:` URIs on `src` (`<img>`, `<video>`, `<audio>`, `<source>`) and `href`
> (`<a>`) attributes. Each payload is base64-decoded, uploaded to the MIME-appropriate S3 folder,
> and the attribute is rewritten to the public URL. HTML that already contains only URLs passes
> through untouched. A single malformed or failed payload is logged and left in place — the save
> still succeeds.

### Enum coercion on input

`BookGenre` has a lenient `@JsonCreator`. Values are upper-cased and trimmed, three legacy names are
remapped, and **anything unrecognised becomes `OTHER` without an error**:

| You send | Stored |
|----------|--------|
| `"history"` | `HISTORY` (case-insensitive) |
| `"POLITICAL"` | `POLITICS` |
| `"ACADEMIC"` | `EDUCATIONAL` |
| `"ESSAY"` | `OTHER` |
| `"COOKING"` (not in the enum) | `OTHER` |

`Language` and `WritingFileFormat` are strict by comparison — an unrecognised value throws and the
whole request fails with `400 BAD_REQUEST` and `details.reason` = `Invalid JSON: Cannot construct
instance of ...`. Both are case-insensitive for valid values (`"pdf"` → `PDF`).

> **Note:** because unknown legacy codes silently collapse to `OTHER`, a typo in `bookGenres` will
> not be reported. Prefer `genreIds` — an unknown id fails loudly with
> `400 Unknown genre id: {id}`.

### Order of operations

The service does the work in this sequence, which matters when something fails midway:

1. Validate languages, genres, per-language content and per-language title.
2. Upload the three cover parts, falling back to the `*CoverUrl` strings.
3. Upload the two book file parts.
4. Resolve the topic — look up `topicId` (404 if missing) or create `newTopic`.
5. Resolve `parentBookId` (404 if missing), copy its `seriesId`, compute `seriesOrder` if omitted.
6. Build the entity, run `TiptapHtmlProcessor` over the descriptions, save.
7. Recount the series and stamp `seriesTotalBooks` onto every member.
8. Write a `WritingLog` row with `action = "CREATED"`.

> **Note:** uploads happen *before* the topic and parent lookups on the create path, so a create that
> fails with a `404` for a bad `topicId` will already have left the uploaded files in S3 with nothing
> referencing them. (The update path deliberately resolves references first, for exactly this
> reason.) Nothing cleans those objects up.

### Request example

```json
{
  "contentLanguages": ["CKB", "KMR"],
  "bookGenres": ["HISTORY", "POLITICS"],
  "publishedByInstitute": true,
  "topicId": 7,
  "ckbContent": {
    "title": "مێژووی کوردستان — بەرگی یەکەم",
    "description": "<p>لێکۆڵینەوەیەکی فراوان لەسەر گۆڕانکارییە سیاسییەکانی کوردستان لە نێوان ١٩٢٠ و ١٩٤٦.</p>",
    "writer": "د. عەبدوڵا حەسەن",
    "fileFormat": "PDF",
    "fileSizeBytes": 18734221,
    "pageCount": 312,
    "genre": "لێکۆڵینەوەی مێژوویی"
  },
  "kmrContent": {
    "title": "Dîroka Kurdistanê — Berga Yekem",
    "description": "<p>Lêkolînek berfireh li ser guhertinên siyasî yên Kurdistanê di navbera 1920 û 1946 de.</p>",
    "writer": "Dr. Ebdulla Hesen",
    "fileFormat": "PDF",
    "fileSizeBytes": 17980114,
    "pageCount": 298,
    "genre": "Lêkolîna dîrokî"
  },
  "tags": {
    "ckb": ["مێژوو", "کوردستان"],
    "kmr": ["dîrok", "Kurdistan"]
  },
  "keywords": {
    "ckb": ["سەدەی بیستەم", "کۆماری مەهاباد"],
    "kmr": ["sedsala bîstan", "Komara Mehabadê"]
  },
  "seriesName": "مێژووی کوردستان",
  "seriesOrder": 1
}
```

### Response `201 Created`

```json
{
  "success": true,
  "message": "Writing created successfully",
  "data": {
    "id": 41,
    "contentLanguages": ["CKB", "KMR"],
    "ckbCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/6b2f0e51-9c3a-4a1d-bd77-0f5a1c2e8b44-berg_ckb.jpg",
    "kmrCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/a41d7c92-5f60-4d8e-8b21-3ce9042f77aa-berg_kmr.jpg",
    "hoverCoverUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/d0c3b118-77ea-4c55-9f30-51b8a2d6e4c7-hover.jpg",
    "ckbContent": {
      "title": "مێژووی کوردستان — بەرگی یەکەم",
      "description": "<p>لێکۆڵینەوەیەکی فراوان لەسەر گۆڕانکارییە سیاسییەکانی کوردستان لە نێوان ١٩٢٠ و ١٩٤٦.</p>",
      "writer": "د. عەبدوڵا حەسەن",
      "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/1c9d5a70-3e8b-42f1-9a6c-77b0d4e2f915-mejuy_kurdistan_1.pdf",
      "fileFormat": "PDF",
      "fileSizeBytes": 18734221,
      "pageCount": 312,
      "genre": "لێکۆڵینەوەی مێژوویی"
    },
    "kmrContent": {
      "title": "Dîroka Kurdistanê — Berga Yekem",
      "description": "<p>Lêkolînek berfireh li ser guhertinên siyasî yên Kurdistanê di navbera 1920 û 1946 de.</p>",
      "writer": "Dr. Ebdulla Hesen",
      "fileUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/files/7fa2c341-90d6-4b18-a55e-2d8e6b0c19f3-diroka_kurdistane_1.pdf",
      "fileFormat": "PDF",
      "fileSizeBytes": 17980114,
      "pageCount": 298,
      "genre": "Lêkolîna dîrokî"
    },
    "topic": { "id": 7, "nameCkb": "مێژووی هاوچەرخ", "nameKmr": "Dîroka hevçerx" },
    "bookGenres": ["HISTORY", "POLITICS"],
    "publishedByInstitute": true,
    "tags": {
      "ckb": ["مێژوو", "کوردستان"],
      "kmr": ["dîrok", "Kurdistan"]
    },
    "keywords": {
      "ckb": ["سەدەی بیستەم", "کۆماری مەهاباد"],
      "kmr": ["sedsala bîstan", "Komara Mehabadê"]
    },
    "seriesInfo": {
      "seriesId": "series-1758042911233",
      "seriesName": "مێژووی کوردستان",
      "seriesOrder": 1.0,
      "totalBooks": 1,
      "parent": false
    },
    "createdAt": "2026-03-14T09:14:22",
    "updatedAt": "2026-03-14T09:14:22",
    "series": {
      "seriesId": "series-1758042911233",
      "seriesName": "مێژووی کوردستان",
      "seriesOrder": 1.0,
      "totalBooks": 1,
      "parent": false
    },
    "topicId": 7,
    "topicNameCkb": "مێژووی هاوچەرخ",
    "topicNameKmr": "Dîroka hevçerx"
  }
}
```

Field-by-field documentation of this object lives in the external doc's
[writing object section](../external/WRITING_API.md#the-writing-object). Note that `"parent"` is
`false` and `totalBooks` is `1` here: with only one member, the series is not yet a series.

### Errors

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | `data` is not valid JSON, carries an unknown field (other than `id`), or contains an invalid `Language` / `WritingFileFormat`. `details.reason` starts with `Invalid JSON:`. |
| 400 | `BAD_REQUEST` | `contentLanguages` missing or empty — `details` = `{ "field": "contentLanguages" }` |
| 400 | `BAD_REQUEST` | Both `genreIds` and `bookGenres` missing or empty — `details` = `{ "field": "genreIds" }` |
| 400 | `BAD_REQUEST` | A `genreIds` entry matches no genre row — message `Unknown genre id: {id}` |
| 400 | `BAD_REQUEST` | A declared language has no content object — `details` = `{ "language": "KMR", "message": "ناوەڕۆک بۆ KMR دیاری نەکراوە" }` |
| 400 | `BAD_REQUEST` | A declared language's `title` is blank — `details` = `{ "language": "CKB", "message": "ناونیشان بۆ CKB پێویستە" }` |
| 400 | `BAD_REQUEST` | A part could not be read from the request — `details` = `{ "description": "فایلی کتێبی CKB", "error": "…" }` |
| 400 | `BAD_REQUEST` | S3 rejected the upload. See the storage note below. |
| 400 | `BAD_REQUEST` | The multipart envelope itself is malformed (`MultipartException`) |
| 403 | — | No token, or a role below `EMPLOYEE`. Empty body. |
| 404 | `NOT_FOUND` | `topicId` does not exist — `details` = `{ "topicId": 7 }` |
| 404 | `NOT_FOUND` | `parentBookId` does not exist — `details` = `{ "id": 999 }` |
| 409 | `CONFLICT` | A value overflowed its column, or another DB constraint fired |
| 413 | `PAYLOAD_TOO_LARGE` | A part exceeded the 1GB limit |
| 500 | `INTERNAL_ERROR` | The `data` part was omitted, or the request `Content-Type` was not `multipart/form-data` |

> **Note — S3 failures come back as `400`, not `502`.** `S3Service` throws
> `BadRequestException("s3.upload.failed", …)` on an `S3Exception`, and `WritingService` throws
> `BadRequestException("media.upload.failed", …)` when a part cannot be read. The codebase does
> define `Errors.storage(...)` → `502 STORAGE_ERROR`, but nothing on the writings path uses it. A
> storage outage is therefore indistinguishable from a client mistake by status code alone; check
> `message`/`details` and the `traceId` in the server log.

> **Note — the `413` message names the wrong limit.** `GlobalExceptionHandler` hardcodes
> `MAX_UPLOAD_MB = 5`, so an oversized upload reports "exceeds the maximum allowed size of 5 MB"
> while the configured limit is 1GB. The status and `code` are correct; the text is not.

### Example

```bash
curl -s -X POST http://localhost:8080/api/v1/writings \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data=@book.json;type=application/json' \
  -F 'ckbCoverImage=@berg_ckb.jpg;type=image/jpeg' \
  -F 'kmrCoverImage=@berg_kmr.jpg;type=image/jpeg' \
  -F 'hoverCoverImage=@hover.jpg;type=image/jpeg' \
  -F 'ckbBookFile=@mejuy_kurdistan_1.pdf;type=application/pdf' \
  -F 'kmrBookFile=@diroka_kurdistane_1.pdf;type=application/pdf'
```

Inline instead of a file, if your shell tolerates the quoting:

```bash
curl -s -X POST http://localhost:8080/api/v1/writings \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"contentLanguages":["CKB"],"bookGenres":["POETRY"],"ckbContent":{"title":"دیوانی نالی","writer":"نالی"}};type=application/json' \
  -F 'ckbBookFile=@diwani_nali.pdf;type=application/pdf'
```

---

## 2. `PUT /api/v1/writings/{id}` — Update a book

A partial update: **every field in `data` is optional, and an omitted field is left alone.** The
same five binary parts are accepted and behave the same way. Returns the updated writing object.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `multipart/form-data`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the book to update |

### Multipart parts

Identical to endpoint 1 — `data` (required) plus `ckbCoverImage`, `kmrCoverImage`,
`hoverCoverImage`, `ckbBookFile`, `kmrBookFile` (all optional).

### The `data` blob (`WritingDtos.UpdateRequest`)

| Field | Type | Omitting it means | Notes |
|-------|------|-------------------|-------|
| `contentLanguages` | array of `Language` | unchanged | Replaced wholesale when non-null **and non-empty**. Every language you list must have its content object present in the same request, or you get a `400`. |
| `genreIds` | array of integer (int64) | unchanged | Ids of `book_genres` rows — the preferred way. Replaces the genre set wholesale when non-null **and non-empty**; wins over `bookGenres`. |
| `bookGenres` | array of `BookGenre` codes | unchanged | Legacy alternative (codes resolved against slugs). Replaced wholesale when non-null **and non-empty**. See the callout below about `[]`. |
| `ckbContent` | `LanguageContentDto` | unchanged | Merged field-by-field into the stored block. Creates the block if the book had none. |
| `kmrContent` | `LanguageContentDto` | unchanged | Same |
| `ckbCoverUrl` | string | unchanged | `""` clears the cover to null; a URL replaces it. Overridden by `ckbCoverImage`. |
| `kmrCoverUrl` | string | unchanged | Same |
| `hoverCoverUrl` | string | unchanged | Same |
| `topicId` | integer (int64) | unchanged | Reassigns the topic. Ignored when `clearTopic` is `true`. |
| `newTopic` | `{ nameCkb, nameKmr }` | unchanged | Creates and assigns a topic. Used only when `topicId` is absent and `clearTopic` is not `true`. |
| `clearTopic` | boolean | unchanged | `true` detaches the topic and wins over `topicId` / `newTopic`. The `PublishmentTopic` row itself is not deleted. |
| `publishedByInstitute` | boolean (nullable here) | unchanged | Set only when non-null. Note this is a boxed `Boolean` on update but a primitive on create. |
| `tags` | `{ ckb, kmr }` | unchanged | Each side replaced independently and only when that side is non-null. `{"ckb": []}` clears the CKB tags and leaves KMR alone. |
| `keywords` | `{ ckb, kmr }` | unchanged | Same |
| `seriesName` | string | unchanged | Set when non-null |
| `seriesOrder` | number (double) | unchanged | Set when non-null |
| `parentBookId` | integer (int64) | unchanged | Attaches to that parent and copies its `seriesId`. See the callout below. |

There is **no `seriesId` field on `UpdateRequest`.** The only way to change a book's series through
this endpoint is `parentBookId`; use endpoint 4 for the explicit version.

> **Note — `"genreIds": []` / `"bookGenres": []` do nothing.** The resolved genre set is applied
> only when non-empty, so an empty array is treated exactly like an omitted field. A book cannot be
> stripped of all its genres through this API. Send at least one genre, or leave the keys out.

> **Note — you cannot un-link a book from its parent.** `parentBookId` is only applied when non-null;
> sending `null` is indistinguishable from omitting it. Detaching a child volume requires either a
> direct DB update or deleting the parent (which clears `parentBookId` on all children as a
> side-effect — see endpoint 3).

> **Note — `applyContent` ignores `contentLanguages` on update.** Sending `ckbContent` writes the
> Sorani block even if `CKB` is not in the stored or requested `contentLanguages`. The two fields can
> drift apart; keep them consistent from the client.

### How each source of a URL wins

**Cover images** — `resolveUpdate(part, dataUrl, storedUrl)`:

| Binary part uploaded | `*CoverUrl` in `data` | Result |
|----------------------|-----------------------|--------|
| yes | anything | the new S3 URL |
| no | omitted / `null` | unchanged |
| no | `""` | cleared to `null` |
| no | `"https://…"` | that URL, trimmed |

**Book files** — same precedence, applied to `ckbContent.fileUrl` / `kmrContent.fileUrl`:

| Binary part uploaded | `fileUrl` in the content block | Result |
|----------------------|-------------------------------|--------|
| yes | anything | the new S3 URL |
| no | omitted / `null` | unchanged — a metadata-only edit keeps the stored file |
| no | `""` | cleared to `null` |
| no | `"https://…"` | that URL, trimmed |

Inside a content block, the text fields (`title`, `description`, `writer`, `genre`) follow the same
rule: omitted leaves them alone, `""` clears them to `null`. The typed fields (`fileFormat`,
`fileSizeBytes`, `pageCount`) can only be **set**, never cleared, because `null` already means
"leave alone".

### Order of operations

1. Load the book (`404` if it does not exist).
2. Validate: for each language listed in `contentLanguages`, the matching content object must be
   present. Titles are **not** required on update.
3. Resolve the topic and the parent book **before** touching S3 — a bad `topicId` or `parentBookId`
   fails here without orphaning an upload. (The create path does not do this.)
4. Resolve the three covers, then upload the two book files.
5. Apply topic, genres, `publishedByInstitute`, languages, content merge, tags and keywords.
6. Apply series changes and save.
7. Recount **both** the old and the new series if the `seriesId` changed.
8. Write a `WritingLog` row with `action = "UPDATED"`.

### Request example — swap the Sorani PDF and add a genre

```json
{
  "bookGenres": ["HISTORY", "POLITICS", "BIOGRAPHY"],
  "ckbContent": {
    "fileFormat": "PDF",
    "fileSizeBytes": 19442100,
    "pageCount": 318
  }
}
```

```bash
curl -s -X PUT http://localhost:8080/api/v1/writings/41 \
  -H "Authorization: Bearer $TOKEN" \
  -F 'data={"bookGenres":["HISTORY","POLITICS","BIOGRAPHY"],"ckbContent":{"fileFormat":"PDF","fileSizeBytes":19442100,"pageCount":318}};type=application/json' \
  -F 'ckbBookFile=@mejuy_kurdistan_1_v2.pdf;type=application/pdf'
```

### Request example — detach the topic and clear the hover image

```json
{
  "clearTopic": true,
  "hoverCoverUrl": ""
}
```

### Response `200 OK`

The same envelope and object as endpoint 1, with `message` = `"Writing updated successfully"` and a
refreshed `updatedAt`.

### Errors

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | `data` unparseable, unknown field, or invalid `Language` / `WritingFileFormat` |
| 400 | `BAD_REQUEST` | A language listed in `contentLanguages` has no content object — `details` = `{ "language": "KMR" }` |
| 400 | `BAD_REQUEST` | A part could not be read, or S3 rejected the upload |
| 403 | — | No token, or a role below `EMPLOYEE`. Empty body. |
| 404 | `WRITING_NOT_FOUND` | No book with `{id}` — `details` = `{ "id": "9999" }` |
| 404 | `NOT_FOUND` | `topicId` does not exist — `details` = `{ "topicId": 7 }` |
| 404 | `NOT_FOUND` | `parentBookId` does not exist — `details` = `{ "id": 999 }` |
| 409 | `CONFLICT` | A value overflowed its column |
| 413 | `PAYLOAD_TOO_LARGE` | A part exceeded 1GB |
| 500 | `INTERNAL_ERROR` | `{id}` is not numeric, the `data` part was omitted, or the `Content-Type` was wrong |

---

## 3. `DELETE /api/v1/writings/{id}` — Delete a book

Hard-deletes the row. There is no soft-delete flag on `Writing`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** none (no request body)

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the book to delete |

### What happens

1. The book is loaded. **If it does not exist the call is a no-op and still returns `204`.** This
   endpoint is idempotent by design.
2. Every child volume (`seriesBooks`) has its `parentBookId` set to `null` and is saved and flushed —
   PostgreSQL would otherwise reject the delete on the self-referencing foreign key.
3. `WritingLog.detachFromWriting(id)` nulls the live FK on every existing audit row. The audit trail
   survives via the denormalised `writing_id_ref` column.
4. A final `WritingLog` row is written with `writing = null`, `writingId = <id>`,
   `action = "DELETED"`.
5. The `writings` row is deleted and flushed. The `@ElementCollection` tables
   (`writing_book_genres`, `writing_content_languages`, `writing_tags_ckb`, `writing_tags_kmr`,
   `writing_keywords_ckb`, `writing_keywords_kmr`) cascade away with it.
6. The series is recounted and `seriesTotalBooks` re-stamped on the survivors.

**Response `204 No Content`** — empty body, no envelope.

### Errors

| Status | `code` | When |
|--------|--------|------|
| 403 | — | No token, or a role below `ADMIN` (an `EMPLOYEE` who can create and update a book cannot delete it). Empty body. |
| 409 | `CONFLICT` | An unexpected foreign-key constraint fired |
| 500 | `INTERNAL_ERROR` | `{id}` is not numeric |

There is no `404` here. Deleting a non-existent id returns `204`.

> **Note — S3 objects are not deleted.** Nothing on the writings path calls `S3Service.deleteFile`.
> Cover images, hover images and book PDFs stay in the bucket forever after their record is gone,
> and so do the files replaced by an update in endpoint 2. Budget for periodic bucket reconciliation.

> **Note — deleting a series parent leaves multiple roots.** Children keep their `seriesId` and
> `seriesOrder` but lose their `parentBookId`, which makes each of them satisfy `isSeriesParent()`.
> `GET /api/v1/writings/series/parents` will then list every surviving volume of that series, and
> each will report `"parent": true`. `GET /api/v1/writings/series/{seriesId}` is unaffected — it
> keys purely on `seriesId`. Re-link the survivors with endpoint 4 to restore a single root.

### Example

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X DELETE http://localhost:8080/api/v1/writings/41 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 4. `POST /api/v1/writings/series/link` — Attach a book to a series

Retro-fits an already-published book into an existing series by pointing it at a parent volume. This
is the explicit counterpart to passing `parentBookId` on create or update.

**Auth:** `Authorization: Bearer <token>`, roles `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

### Request body (`WritingDtos.LinkToSeriesRequest`)

Unlike the multipart endpoints, this body **is** annotated `@Valid`, so the bean-validation
constraints below are genuinely enforced and produce a `400 VALIDATION_ERROR` with a `fieldErrors`
array.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `bookId` | integer (int64) | **yes** | `@NotNull` | The book being moved into the series |
| `parentBookId` | integer (int64) | **yes** | `@NotNull` | The series root. Its `seriesId` is copied onto the child. |
| `seriesOrder` | number (double) | **yes** | `@NotNull`, `@Min(1)` | Position in the series. Fractional values are allowed (`2.5`). |
| `seriesName` | string | no | `@Size(max=300)` | Overrides the display name. When omitted the parent's `seriesName` is copied — which may itself be `null`. |

Unknown properties in this body are ignored by the framework's message converter, in contrast to the
multipart `data` part.

> **Note — `@Min(1)` here but `@Min(0)` on create.** `CreateRequest.seriesOrder` declares `@Min(0)`
> (and is unenforced anyway), while this endpoint rejects `seriesOrder` below `1`. A book created
> with `seriesOrder: 0` cannot be re-linked at the same position.

```json
{
  "bookId": 57,
  "parentBookId": 41,
  "seriesOrder": 3,
  "seriesName": "مێژووی کوردستان"
}
```

### What happens

1. Load `bookId` (`404 WRITING_NOT_FOUND` if missing) and `parentBookId`
   (`404 NOT_FOUND` if missing).
2. Set the child's `parentBook`, copy the parent's `seriesId`, set `seriesOrder`, and set
   `seriesName` to the request value or the parent's.
3. Save, recount the **destination** series, and stamp `seriesTotalBooks` on all its members.
4. Write a `WritingLog` row with `action = "LINKED_TO_SERIES"`.

### Response `200 OK`

The updated child book, in the standard envelope:

```json
{
  "success": true,
  "message": "Book linked to series",
  "data": {
    "id": 57,
    "contentLanguages": ["CKB", "KMR"],
    "ckbContent": { "title": "مێژووی کوردستان — بەرگی سێیەم", "writer": "د. عەبدوڵا حەسەن" },
    "kmrContent": { "title": "Dîroka Kurdistanê — Berga Sêyem", "writer": "Dr. Ebdulla Hesen" },
    "bookGenres": ["HISTORY"],
    "publishedByInstitute": true,
    "tags": { "ckb": ["مێژوو"], "kmr": ["dîrok"] },
    "keywords": { "ckb": [], "kmr": [] },
    "seriesInfo": {
      "seriesId": "series-1758042911233",
      "seriesName": "مێژووی کوردستان",
      "seriesOrder": 3.0,
      "parentBookId": 41,
      "totalBooks": 3,
      "parent": false
    },
    "createdAt": "2026-06-09T16:55:44",
    "updatedAt": "2026-08-26T09:14:22",
    "series": {
      "seriesId": "series-1758042911233",
      "seriesName": "مێژووی کوردستان",
      "seriesOrder": 3.0,
      "parentBookId": 41,
      "totalBooks": 3,
      "parent": false
    }
  }
}
```

### Errors

| Status | `code` | When |
|--------|--------|------|
| 400 | `VALIDATION_ERROR` | `bookId`, `parentBookId` or `seriesOrder` is null, or `seriesOrder < 1`, or `seriesName` is over 300 characters. `fieldErrors` names each one. |
| 400 | `BAD_REQUEST` | The body is missing or is not valid JSON |
| 403 | — | No token, or a role below `EMPLOYEE`. Empty body. |
| 404 | `WRITING_NOT_FOUND` | `bookId` does not exist — `details` = `{ "id": "57" }` |
| 404 | `NOT_FOUND` | `parentBookId` does not exist — `details` = `{ "id": 41 }` |

A `400 VALIDATION_ERROR` body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/writings/series/link",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "One or more fields failed validation.",
  "messageEn": "One or more fields failed validation.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    {
      "field": "seriesOrder",
      "message": "must be greater than or equal to 1",
      "messageEn": "must be greater than or equal to 1",
      "messageKu": "must be greater than or equal to 1"
    }
  ]
}
```

> **Note:** `fieldErrors[].messageKu` falls back to the English default whenever the constraint's
> message is not a key in `messages_ku`. Constraint messages on this DTO
> (`"Book ID is required"`, `"Parent book ID is required"`, `"Series order is required"`) are literal
> English strings, so all three localised fields carry the same text.

> **Note — the source series is not recounted.** `linkBookToSeries` calls
> `updateSeriesCount` only for the destination `seriesId`. Moving a book out of series A into series
> B leaves A's `seriesTotalBooks` one too high on every remaining member until something else touches
> A. `GET /api/v1/writings/series/{seriesId}` reports a live count and is always correct; the
> `seriesInfo.totalBooks` on a writing object is the cached one.

> **Note — no self-link guard.** Nothing rejects `bookId == parentBookId`. Doing so makes a book its
> own parent, which leaves `isSeriesParent()` permanently `false` for it (because `parentBook` is no
> longer null) and hides it from `GET /api/v1/writings/series/parents`. Guard against this in the
> dashboard. There is likewise no cycle detection for longer chains, and no check that the parent is
> itself a root rather than a child.

### Example

```bash
curl -s -X POST http://localhost:8080/api/v1/writings/series/link \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"bookId":57,"parentBookId":41,"seriesOrder":3,"seriesName":"مێژووی کوردستان"}'
```

---

## 5. `PATCH /api/v1/writings/{id}/featured` — Toggle the homepage carousel flag

Sets or clears the `featured` flag, its ordering, and the optional wide hero image used by the
homepage carousel. Delegates to `SiteContentService.setWritingFeatured`, the same method used by the
news, project, video, sound-track and image-collection featured toggles.

**Auth:** `Authorization: Bearer <token>`, role **`ADMIN` only** — see the
[`SUPER_ADMIN` callout](#where-those-roles-come-from) at the top of this page
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the book |

### Request body (`SiteContentDtos.FeaturedRequest`)

The DTO is shared across the whole featured subsystem, so it declares far more fields than this
handler reads. **Exactly three are used:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `featured` | boolean | no | `true` (or **omitted / `null`**) turns featuring **on**; `false` turns it off. There is no way to express "leave unchanged". |
| `featuredOrder` | integer | no | Sort key for the carousel; lower shows first. Applied only when featuring on — turning off always sets it to `null`. `null` sorts last. |
| `featureImageUrl` | string | no | Wide hero picture. Omit / `null` leaves the stored value alone; `""` clears it so the carousel falls back to a cover. |

The remaining fields — `type`, `slug`, `title`, `description`, `imageUrl`, `imageAlt`, `locale`,
`displayOrder`, `active` — are accepted and silently discarded by this endpoint.

> **Note — the `@NotBlank` constraints on this DTO are not enforced here.** `FeaturedRequest`
> declares `@NotBlank` on `type`, `slug`, `title`, `description` and `imageUrl`, but the handler's
> `@RequestBody` carries no `@Valid`. A body of `{"featured": true}` is accepted. Other controllers
> that share this DTO may validate it; this one does not.

> **Note — omitting `featured` means "turn it on".** The service reads
> `request.getFeatured() == null || request.getFeatured()`. An empty object `{}` features the book.
> Always send `featured` explicitly.

```json
{
  "featured": true,
  "featuredOrder": 2,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/8e4f21b0-c7d3-4a09-b8f6-19e5a03c62d1-hero_mejuy_kurdistan.jpg"
}
```

To un-feature:

```json
{ "featured": false }
```

### The global featured cap

Featuring is capped **across all content types at once**. Before turning a book on, the service sums
the featured counts of news, projects, writings, videos, sound tracks and image collections, plus one
if the donation settings row is flagged featured. If that total already meets the limit, the request
fails with `400`.

The limit is `site_settings.max_featured_slides`, editable via `PUT /api/v1/site-settings`, and
defaults to **7** (`SiteSettings.DEFAULT_MAX_FEATURED_SLIDES`) when no row exists or the stored value
is not positive.

The check is skipped when the book is already featured, so re-ordering an already-featured book
never trips it.

### Response `204 No Content`

Empty body, no envelope. Re-read the book with
`GET /api/v1/writings/{id}` if you need the resulting state — `featureImageUrl` is on the writing
object, though `featured` and `featuredOrder` themselves are not exposed on it. Use
`GET /api/v1/writings/featured` to see the resulting carousel order.

### Errors

| Status | `code` | When |
|--------|--------|------|
| 400 | `BAD_REQUEST` | The cap is reached. `message` = `"Maximum of 7 featured slides allowed across all content. Unfeature one first."`, `details.reason` carries the same text. |
| 400 | `BAD_REQUEST` | The body is missing or is not valid JSON |
| 403 | `FORBIDDEN` | Authenticated but not `ROLE_ADMIN` — including `SUPER_ADMIN` and `EMPLOYEE`. Full `ApiErrorResponse`, since this denial comes from `@PreAuthorize` rather than the filter chain. |
| 403 | — | No token at all. Empty body, from the security filter chain. |
| 404 | `NOT_FOUND` | No book with `{id}`. `message` = `"Writing not found: 9999"`, `details` = `{ "resource": "Writing not found: 9999" }`. |
| 500 | `INTERNAL_ERROR` | `{id}` is not numeric |

### Example

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X PATCH http://localhost:8080/api/v1/writings/41/featured \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"featured":true,"featuredOrder":2}'
```

---

## Enums used by this API

### `Language`

Accepted in `contentLanguages`. Case-insensitive on input; an unrecognised value fails the request.

| Value | Meaning |
|-------|---------|
| `CKB` | Central Kurdish / Sorani |
| `KMR` | Northern Kurdish / Kurmanji |

### `BookGenre` (legacy request shim)

> **Since 2026-09-03 genres are editor-managed rows** (`book_genres`, CRUD at
> `/api/v1/book-genres` — see [`../BOOK_GENRES.md`](../BOOK_GENRES.md)) and the preferred request
> field is `genreIds`. This enum survives only to parse the legacy `bookGenres` request field:
> each parsed value's canonical code is looked up against the rows' slugs. The table below is
> therefore also the seeded starting set of rows.

Accepted as an array in `bookGenres`. Case-insensitive; **unrecognised values silently become
`OTHER`**.

| Value | Sorani | Meaning |
|-------|--------|---------|
| `POETRY` | شیعر | Poetry collections |
| `NOVEL` | ڕۆمان | Novels and long-form fiction |
| `SHORT_STORY` | چیرۆکی کورت | Short stories and novellas |
| `DRAMA` | شانۆ | Plays and dramatic works |
| `HISTORY` | مێژوو | Historical works |
| `BIOGRAPHY` | ژیاننامە | Biographies and memoirs |
| `PHILOSOPHY` | فەلسەفە | Philosophy |
| `RELIGION` | ئایین | Religious and theological texts |
| `FOLKLORE` | زارگوتن | Folklore, oral tradition, mythology |
| `POLITICS` | سیاسەت | Political science and theory |
| `SOCIOLOGY` | کۆمەڵناسی | Sociology and social studies |
| `ECONOMICS` | ئابووری | Economics and finance |
| `LAW` | یاسا | Law and legal studies |
| `LINGUISTICS` | زمانناسی | Linguistics and language studies |
| `ARTS` | هونەر | Visual arts, music, crafts |
| `CULTURAL` | کولتووری | Cultural studies and heritage |
| `SCIENCE` | زانست | Natural and applied sciences |
| `MEDICINE` | پزیشکی | Medical and health sciences |
| `EDUCATIONAL` | پەروەردەیی | Textbooks and academic works |
| `CHILDREN` | منداڵان | Children's books |
| `TRAVEL` | گەشتوگوزار | Travel and geography |
| `OTHER` | یتر | Uncategorised |

Three legacy constants still exist in the Java enum for database compatibility and are normalised on
both input and output. Do not send them:

| Legacy value | Normalised to |
|--------------|---------------|
| `POLITICAL` | `POLITICS` |
| `ACADEMIC` | `EDUCATIONAL` |
| `ESSAY` | `OTHER` |

Genres are persisted in the collection table `writing_book_genres (writing_id, book_genre)` with a
composite primary key, so duplicates in your array collapse silently.

### `WritingFileFormat`

Accepted in `ckbContent.fileFormat` / `kmrContent.fileFormat`. Case-insensitive; an unrecognised
value fails the request.

| Value | Meaning |
|-------|---------|
| `PDF` | PDF document |
| `DOCX` | Microsoft Word, modern |
| `DOC` | Microsoft Word, legacy |
| `TXT` | Plain text |
| `EPUB` | E-book |
| `ODT` | OpenDocument Text |
| `RTF` | Rich Text Format |
| `HTML` | HTML document |
| `OTHER` | Anything else |

This field is pure metadata. Nothing validates it against the uploaded file, and nothing derives it
from the MIME type — uploading a `.docx` while declaring `PDF` is accepted and stored as-is.

---

## Notes & gotchas

**Audit trail.** Every create, update and series-link writes a `writing_logs` row with
`actorId = "system"` and `actorName = "System"` — **the authenticated user is never recorded.**
`WritingLog` has `actorId`, `actorName`, `requestId` and `meta` columns, but `WritingService.logAction`
hardcodes the first two and never populates the last two. Deletes are logged the same way, with the
FK nulled and the id preserved in `writing_id_ref`. If you need real attribution, the log table is
not it today.

**Column limits that the API will not warn you about.** Because bean validation is bypassed on the
multipart path, these are enforced only by PostgreSQL and surface as `409 CONFLICT`:

| Field | Column | Limit |
|-------|--------|-------|
| `ckbContent.title` / `kmrContent.title` | `title_ckb` / `title_kmr` | 300 |
| `ckbContent.writer` / `kmrContent.writer` | `writer_ckb` / `writer_kmr` | 200 |
| `ckbContent.fileUrl` / `kmrContent.fileUrl` | `file_url_ckb` / `file_url_kmr` | 1000 |
| `ckbContent.genre` / `kmrContent.genre` | `genre_ckb` / `genre_kmr` | 150 |
| `seriesId` | `series_id` | 100 |
| `seriesName` | `series_name` | 300 |
| each tag | `tag_ckb` / `tag_kmr` | 80 |
| each keyword | `keyword_ckb` / `keyword_kmr` | 120 |
| `newTopic.nameCkb` / `nameKmr` | `name_ckb` / `name_kmr` | 300 |

`description`, `ckbCoverUrl`, `kmrCoverUrl`, `hoverCoverUrl` and `featureImageUrl` are `TEXT` columns
with no length limit.

**Inline topic creation has no de-duplication.** `newTopic` always inserts a fresh
`publishment_topics` row. Two editors typing the same topic name produce two topics. Call
`GET /api/v1/writings/topics` first and prefer `topicId`.

**Topics are never garbage-collected.** `clearTopic: true` detaches the association; the topic row
stays. Deleting a book leaves its topic behind too.

**No caching to invalidate.** `WritingService` carries no `@Cacheable` or `@CacheEvict`. Redis is
configured application-wide (`khi:` prefix, 10-minute default TTL) but this domain does not use it,
so a write is visible on the next public read with no eviction step.

**`seriesTotalBooks` is eventually consistent at best.** It is recomputed with a
`COUNT(*)` plus a `saveAll` over every member of the series on each mutation, and `updateSeriesCount`
is a `protected` method invoked from within the same bean — the `@Transactional` on it is inert and
it simply joins the caller's transaction. Combined with the missing source-series recount on endpoint
4, treat this number as a display hint. `GET /api/v1/writings/series/{seriesId}` returns a live
count.

**Exception handling has a hole around framework-level errors.** The project's
`@RestControllerAdvice` declares a catch-all `@ExceptionHandler(Exception.class)` and does not extend
`ResponseEntityExceptionHandler`. Exceptions Spring MVC would normally map to `400` — a missing
`data` part (`MissingServletRequestPartException`), a wrong request `Content-Type`
(`HttpMediaTypeNotSupportedException`), a non-numeric `{id}` (`MethodArgumentTypeMismatchException`)
— reach that handler first and come back as `500 INTERNAL_ERROR` with `details.traceId`. Validate
these client-side.

**Live spec.** Swagger UI at `/swagger-ui.html`, raw JSON at `/v3/api-docs`. The `internal` and
`all` groups include these endpoints; `persist-authorization` is on, so the Bearer token survives a
page reload.

**Servers.** `http://localhost:8080` for local development; production runs on Railway.

---

## Related documentation

- Counterpart: [`../external/WRITING_API.md`](../external/WRITING_API.md) — the public list, detail,
  series and search endpoints, plus the full response-object reference
- Authentication: [`../external/AUTH_API.md`](../external/AUTH_API.md),
  [`AUTH_SESSIONS_API.md`](AUTH_SESSIONS_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
