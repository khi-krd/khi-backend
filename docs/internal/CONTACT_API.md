# Contact API — Internal (Authenticated)

The admin side of the Contact domain. Two groups of endpoints:

1. **Contact page CRUD** — create, list (including unpublished), update and delete the bilingual
   contact pages the public website renders. Bodies are JSON; all media lives inside the Tiptap
   HTML `description`, uploaded beforehand via `POST /api/v1/media/upload`.
2. **Contact message inbox** — read visitor submissions and move them through a status workflow.
   Submissions themselves arrive on the public endpoint documented in
   [`../external/CONTACT_API.md`](../external/CONTACT_API.md).

Every endpoint here requires a JWT belonging to a user with `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`.
`EMPLOYEE` and `GUEST` are rejected.

| | |
|---|---|
| **Base path** | `/api/v1/contact` |
| **Audience** | Admin dashboard (JWT required, ADMIN / SUPER_ADMIN) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/contact/ContactController.java` |
| **Controller (messages)** | `src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/contact/ContactService.java` |
| **Service (messages)** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **DTOs** | `ContactDTOs.java`, `SiteContentDtos.java` |
| **Entities** | `Contact`, `ContactContent` (embeddable), `ContactMessage` |
| **Tables** | `contact_pages`, `contact_messages` |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either way:

```
Authorization: Bearer <token>
```

or as the HttpOnly cookie whose name comes from the `JWT_COOKIE_NAME` environment variable
(`JWTAuthenticationFilter` checks the header first, then the cookie). Sessions are stateless
(`SessionCreationPolicy.STATELESS`) — there is no server-side session to keep alive, but tokens
can be revoked (`/api/auth/logout`, `/api/auth/logout-all`), and a revoked token is rejected.

Granted authorities on a successful login are `ROLE_<NAME>` plus the permission strings
`user:create`, `user:read`, `user:update`, `user:delete`. Only the role matters here.

| Role | Contact pages | Contact messages |
|------|---------------|------------------|
| `GUEST` | no | no |
| `EMPLOYEE` | no | no |
| `ADMIN` | full | full |
| `SUPER_ADMIN` | full | full |

None of these handlers carries a `@PreAuthorize` annotation — authorization comes entirely from
`SecurityConfig`. That matters for the shape of a rejection; see
[Auth failure bodies](#auth-failure-bodies).

---

## Authorization, exactly as SecurityConfig writes it

```java
// rule 9 — evaluated before the public GET catch-all
.requestMatchers(HttpMethod.GET,
        "/api/v1/contact/messages",
        "/api/v1/contact"
).hasAnyRole("ADMIN", "SUPER_ADMIN")

// rule 10
.requestMatchers(HttpMethod.PATCH,
        "/api/v1/contact/messages/**"
).hasAnyRole("ADMIN", "SUPER_ADMIN")

// rule 14
.requestMatchers(HttpMethod.POST,   "/api/v1/contact").hasAnyRole("ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.PUT,    "/api/v1/contact/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.DELETE, "/api/v1/contact/**").hasAnyRole("ADMIN", "SUPER_ADMIN")

// rule 16 — everything not matched above
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

Two consequences worth internalising before you wire up the dashboard:

- **`GET /api/v1/contact` is admin-only; `GET /api/v1/contact/active` is public.** The admin
  matcher is the exact literal `/api/v1/contact`, so only the bare collection path is protected.
  `/active`, `/{id}` and `/slug/{slug}` reach rule 16 and are open to anyone. Use the bare path
  from the dashboard when you need unpublished pages; use `/active` from the website.
  The two also return **different shapes** — a flat array here, a paged object there.
- **`POST /api/v1/contact/messages` is `permitAll` (rule 8) and is matched earlier than
  `POST /api/v1/contact`.** Message submission stays public; only reading and re-statusing
  messages is admin-only.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/contact` | JWT | `ADMIN`, `SUPER_ADMIN` | List **all** contact pages, active and inactive |
| 2 | `POST` | `/api/v1/contact` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a contact page |
| 3 | `PUT` | `/api/v1/contact/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace a contact page |
| 4 | `DELETE` | `/api/v1/contact/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a contact page |
| 5 | `GET` | `/api/v1/contact/messages` | JWT | `ADMIN`, `SUPER_ADMIN` | Paged inbox of visitor messages, newest first |
| 6 | `PATCH` | `/api/v1/contact/messages/{id}/status` | JWT | `ADMIN`, `SUPER_ADMIN` | Move a message to another status |

Everything returns the `ApiResponse<T>` envelope: `{ "success", "message", "data" }`, with null
properties omitted (`@JsonInclude(NON_NULL)` on the envelope plus
`spring.jackson.default-property-inclusion: non_null` globally).

---

## The `ContactRequest` body

Used by both endpoint 2 (`POST`) and endpoint 3 (`PUT`). Source: `ContactDTOs.ContactRequest`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `slugCkb` | string | **yes** | no annotation; enforced in `ContactService.validateSlugs` → 400. DB: `varchar(200)`, `NOT NULL`, `UNIQUE` | Sorani URL slug. Trimmed before saving |
| `slugKmr` | string | no | unique when present; must differ from `slugCkb`. DB: `varchar(200)`, `UNIQUE` | Kurmanji URL slug. Blank → stored as `null` |
| `ckbContent` | object | no | see `ContactContentRequest` | Sorani text block. Omitting it stores an **empty** block (all five fields `null`) |
| `kmrContent` | object | no | see `ContactContentRequest` | Kurmanji text block, same rule |
| `phone` | string | **yes** | `@NotBlank`. DB: `varchar(60)` | Primary phone, free form |
| `secondaryPhone` | string | no | DB: `varchar(60)` | Secondary phone. Blank → `null` |
| `email` | string | **yes** | `@NotBlank`, `@Email`. DB: `varchar(200)` | Contact email |
| `mapEmbedUrl` | string | no | DB: `TEXT` | Google Maps (or any iframe) embed URL |
| `latitude` | number (double) | no | none — any double is accepted, including out-of-range values | Marker latitude |
| `longitude` | number (double) | no | none | Marker longitude |
| `heroImageUrl` | string | no | DB: `TEXT` | Absolute S3 URL. Upload via `POST /api/v1/media/upload` first |
| `officeType` | string | no | DB: `varchar(40)`, free text | Conventionally `HQ` or `REGIONAL`. **Not validated** |
| `badgeCkb` | string | no | DB: `varchar(200)` | Short Sorani badge label |
| `badgeKmr` | string | no | DB: `varchar(200)` | Short Kurmanji badge label |
| `active` | boolean | no | — | `POST`: `null` is treated as `true`. `PUT`: `null` leaves the stored value alone |
| `displayOrder` | integer (int32) | no | — | `POST`: `null` → `0`. `PUT`: `null` leaves the stored value alone. Ascending sort key on `/active` |

Every other string field goes through `blankToNull`: trimmed, and stored as `null` when the result
is empty.

### `ContactContentRequest`

| Field | Type | Required | DB column limit | Description |
|-------|------|----------|-----------------|-------------|
| `title` | string | no | 300 | Page title in this language |
| `subtitle` | string | no | 500 | Subtitle / call to action |
| `address` | string | no | 500 | Physical address |
| `workingHours` | string | no | 300 | Office hours |
| `description` | string | no | `TEXT` | Tiptap HTML. Passed through `TiptapHtmlProcessor.process()` on every write |

> **Note:** none of the content fields carries a `@Size` constraint on the DTO, but the columns
> are length-limited (`title_ckb varchar(300)`, `subtitle_ckb varchar(500)`, and so on). Exceeding a
> limit is not caught by validation — it surfaces as a database error on flush, which
> `GlobalExceptionHandler` reports as `500 INTERNAL_ERROR` (or `409 CONFLICT` if it happens to
> arrive as a `DataIntegrityViolationException`). Enforce the lengths in the dashboard's editor.

### What `TiptapHtmlProcessor` does to `description`

On every create and update, `ContactService.buildContent` runs the description through
`TiptapHtmlProcessor.process()`, which:

- scans for `src="data:<mime>;base64,…"` on `<img>`, `<video>`, `<audio>` and `<source>` tags, and
  `href="data:…"` on `<a>` tags;
- base64-decodes each payload, uploads it to S3 (bucket `s3-khiwebsite`, region `us-east-1`, base
  folder `khi-web-folders`, sub-folder chosen from the MIME type: `images/`, `video/`, `audio/`,
  `files/`);
- rewrites the attribute to the resulting public URL, then persists the rewritten HTML.

It is idempotent (HTML with no `data:` substring is returned untouched), null/blank safe, and
resilient — a malformed payload or a failed upload is logged and that one attribute is left as-is
while the rest of the save proceeds. **The endpoint still returns `200`/`201` in that case**, so
diff the returned `description` against what you sent if you rely on the rewrite.

The intended workflow is the other way round: upload each file once through
`POST /api/v1/media/upload` (also ADMIN/SUPER_ADMIN), bake the returned S3 URL into the editor, and
send HTML that already contains only S3 URLs. Inline base64 works but pushes the whole payload
through this JSON request (multipart limits are 1 GB per file and 1 GB per request; the base64
route is JSON, not multipart, and is bounded by Tomcat's post size — 1 GB).

---

## 1. `GET /api/v1/contact` — List every contact page

Returns all rows from `contact_pages`, published or not. Backed by `contactRepository.findAll()`
with **no sort and no pagination** — the row order is whatever PostgreSQL returns and is not
guaranteed to be stable. Sort in the dashboard (`displayOrder`, then `id`) if order matters.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | none |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none — this endpoint is **not** paginated |

**Response `200 OK`** — `data` is a flat array of `ContactResponse` (schema in
[`../external/CONTACT_API.md`](../external/CONTACT_API.md#the-contactresponse-object)).

```json
{
  "success": true,
  "message": "Contact pages fetched",
  "data": [
    {
      "id": 1,
      "slugCkb": "peywendi",
      "slugKmr": "tekili",
      "ckbContent": {
        "title": "پەیوەندیمان پێوە بکە",
        "subtitle": "تیمی ئێمە ئامادەیە بۆ وەڵامدانەوەی پرسیارەکانت",
        "address": "هەولێر، شەقامی ٦٠ مەتری، نزیک پارکی سامی عەبدولڕەحمان",
        "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ – ١٧:٠٠",
        "description": "<p>بارەگای سەرەکی دەزگای کەلەپووری کوردستان لە هەولێرە.</p>"
      },
      "kmrContent": {
        "title": "Bi me re têkilî deynin",
        "address": "Hewlêr, Kolana 60 Metreyî",
        "workingHours": "Şemî – Pêncşem, 9:00 – 17:00",
        "description": "<p>Navenda sereke li Hewlêrê ye.</p>"
      },
      "phone": "+964 750 123 4567",
      "secondaryPhone": "+964 770 987 6543",
      "email": "info@khi.krd",
      "mapEmbedUrl": "https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3218.44!2d44.0092!3d36.1911",
      "latitude": 36.1911,
      "longitude": 44.0092,
      "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/contact-hero-erbil.jpg",
      "officeType": "HQ",
      "badgeCkb": "بارەگای سەرەکی",
      "badgeKmr": "Navenda Sereke",
      "displayOrder": 0,
      "active": true,
      "createdAt": "2026-03-14 08:22:19",
      "updatedAt": "2026-08-19 11:05:47"
    },
    {
      "id": 3,
      "slugCkb": "peywendi-slemani",
      "slugKmr": "tekili-silemani",
      "ckbContent": {
        "title": "نووسینگەی سلێمانی",
        "address": "سلێمانی، گەڕەکی سالم، نزیک بازاڕی سەرشەقام",
        "description": "<p>ئەم نووسینگەیە لە ئێستادا داخراوە بۆ چاکسازی.</p>"
      },
      "kmrContent": {
        "title": "Nivîsgeha Silêmanî",
        "address": "Silêmanî, Taxa Salim"
      },
      "phone": "+964 770 300 1122",
      "email": "slemani@khi.krd",
      "officeType": "REGIONAL",
      "displayOrder": 2,
      "active": false,
      "createdAt": "2026-05-21 07:10:44",
      "updatedAt": "2026-08-01 15:33:02"
    }
  ]
}
```

An empty table returns `"data": []`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | No token, or a token whose role is not `ADMIN` / `SUPER_ADMIN`. Body is **not** `ApiErrorResponse` — see [Auth failure bodies](#auth-failure-bodies) |
| `401` | — | Token expired or revoked. Body is the JWT filter's own shape |
| `500` | `INTERNAL_ERROR` | Unexpected server-side failure |

**Example**

```bash
curl -s http://localhost:8080/api/v1/contact \
  -H "Authorization: Bearer $TOKEN"
```

---

## 2. `POST /api/v1/contact` — Create a contact page

Creates one row in `contact_pages`. Slug uniqueness is checked in the service *before* the insert;
the database also has unique indexes on both slug columns as a backstop.

Side effects: one insert, plus zero or more S3 uploads if either `description` carries inline
base64. `createdAt` / `updatedAt` are set by Hibernate (`@CreationTimestamp` / `@UpdateTimestamp`).

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json` (declared with `consumes = APPLICATION_JSON_VALUE` — anything
else is rejected)
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | none |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none |

**Request body** — `ContactRequest`, table above.

Validation order matters: Jakarta validation (`phone`, `email`) runs first and produces
`VALIDATION_ERROR`; the slug rules run inside the service and produce `BAD_REQUEST`.

```json
{
  "slugCkb": "peywendi-halabja",
  "slugKmr": "tekili-helebce",
  "ckbContent": {
    "title": "نووسینگەی هەڵەبجە",
    "subtitle": "بەشی ئەرشیفی مێژووی هەڵەبجە",
    "address": "هەڵەبجە، شەقامی سەرەکی، بەرامبەر مۆزەخانەی شەهیدان",
    "workingHours": "شەممە – چوارشەممە، ٩:٠٠ – ١٦:٠٠",
    "description": "<p>ئەم نووسینگەیە کۆکردنەوەی بەڵگەنامەکانی ساڵی ١٩٨٨ بەڕێوە دەبات.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/halabja-office.jpg\" alt=\"نووسینگەی هەڵەبجە\">"
  },
  "kmrContent": {
    "title": "Nivîsgeha Helebceyê",
    "subtitle": "Beşa arşîva dîrokî ya Helebceyê",
    "address": "Helebce, Kolana Sereke, hemberî Muzexaneya Şehîdan",
    "workingHours": "Şemî – Çarşem, 9:00 – 16:00",
    "description": "<p>Ev nivîsgeh berhevkirina belgeyên sala 1988'an bi rê ve dibe.</p>"
  },
  "phone": "+964 770 555 2211",
  "secondaryPhone": "+964 750 555 2212",
  "email": "halabja@khi.krd",
  "mapEmbedUrl": "https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3229.11!2d45.9861!3d35.1778",
  "latitude": 35.1778,
  "longitude": 45.9861,
  "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/contact-hero-halabja.jpg",
  "officeType": "REGIONAL",
  "badgeCkb": "نووسینگەی هەرێمی",
  "badgeKmr": "Nivîsgeha Herêmî",
  "active": true,
  "displayOrder": 3
}
```

**Response `201 Created`**

No `Location` header is set.

```json
{
  "success": true,
  "message": "Contact page created successfully",
  "data": {
    "id": 4,
    "slugCkb": "peywendi-halabja",
    "slugKmr": "tekili-helebce",
    "ckbContent": {
      "title": "نووسینگەی هەڵەبجە",
      "subtitle": "بەشی ئەرشیفی مێژووی هەڵەبجە",
      "address": "هەڵەبجە، شەقامی سەرەکی، بەرامبەر مۆزەخانەی شەهیدان",
      "workingHours": "شەممە – چوارشەممە، ٩:٠٠ – ١٦:٠٠",
      "description": "<p>ئەم نووسینگەیە کۆکردنەوەی بەڵگەنامەکانی ساڵی ١٩٨٨ بەڕێوە دەبات.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/halabja-office.jpg\" alt=\"نووسینگەی هەڵەبجە\">"
    },
    "kmrContent": {
      "title": "Nivîsgeha Helebceyê",
      "subtitle": "Beşa arşîva dîrokî ya Helebceyê",
      "address": "Helebce, Kolana Sereke, hemberî Muzexaneya Şehîdan",
      "workingHours": "Şemî – Çarşem, 9:00 – 16:00",
      "description": "<p>Ev nivîsgeh berhevkirina belgeyên sala 1988'an bi rê ve dibe.</p>"
    },
    "phone": "+964 770 555 2211",
    "secondaryPhone": "+964 750 555 2212",
    "email": "halabja@khi.krd",
    "mapEmbedUrl": "https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3229.11!2d45.9861!3d35.1778",
    "latitude": 35.1778,
    "longitude": 45.9861,
    "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/contact-hero-halabja.jpg",
    "officeType": "REGIONAL",
    "badgeCkb": "نووسینگەی هەرێمی",
    "badgeKmr": "Nivîsgeha Herêmî",
    "displayOrder": 3,
    "active": true,
    "createdAt": "2026-08-26 09:14:22",
    "updatedAt": "2026-08-26 09:14:22"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `phone` blank, `email` blank or not an email address. `fieldErrors[]` names the field |
| `400` | `BAD_REQUEST` | `slugCkb` missing or blank — `details.reason` = `CKB slug is required` |
| `400` | `BAD_REQUEST` | `slugCkb` already used — `details.reason` = `CKB slug already exists: peywendi-halabja` |
| `400` | `BAD_REQUEST` | `slugKmr` already used — `details.reason` = `KMR slug already exists: tekili-helebce` |
| `400` | `BAD_REQUEST` | `slugCkb` equals `slugKmr` — `details.reason` = `CKB slug and KMR slug must be different: …` |
| `400` | `BAD_REQUEST` | Body missing or unparseable JSON |
| `409` | `CONFLICT` | The DB unique index fired anyway (concurrent create with the same slug) |
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `500` | `INTERNAL_ERROR` | Wrong `Content-Type`, a value longer than its column, or an S3/DB failure |

Slug conflict body (no `Accept-Language` header):

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/contact",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "BAD_REQUEST",
  "message": "CKB slug already exists: peywendi-halabja",
  "messageEn": "CKB slug already exists: peywendi-halabja",
  "messageKu": "CKB slug already exists: peywendi-halabja",
  "details": {
    "reason": "CKB slug already exists: peywendi-halabja"
  }
}
```

With `Accept-Language: ckb` the `message` field is replaced by the generic bundle string for
`error.bad_request` and the specific cause survives only in `details.reason` — always read
`details.reason` in the dashboard, never `message`.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/contact \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @halabja-contact.json
```

---

## 3. `PUT /api/v1/contact/{id}` — Replace a contact page

**This is a full replacement, not a patch.** The service assigns every field from the request onto
the entity unconditionally, so any field you omit is written as `null`:

```java
contact.setSlugKmr(blankToNull(request.getSlugKmr()));
contact.setCkbContent(buildContent(request.getCkbContent()));   // null request → empty block
contact.setSecondaryPhone(blankToNull(request.getSecondaryPhone()));
...
if (request.getActive() != null)       contact.setActive(request.getActive());
if (request.getDisplayOrder() != null) contact.setDisplayOrder(request.getDisplayOrder());
```

Only `active` and `displayOrder` are treated as tri-state (omit → keep). Everything else — both
content blocks, `secondaryPhone`, `mapEmbedUrl`, `latitude`, `longitude`, `heroImageUrl`,
`officeType`, `badgeCkb`, `badgeKmr` — is cleared when omitted. Load the current page, mutate it,
send it back whole.

Slug validation runs with the current id excluded, so re-submitting the page's own slugs is fine.
`updatedAt` is refreshed by `@UpdateTimestamp`. Descriptions go through `TiptapHtmlProcessor` again
on every save.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json` (declared `consumes`)
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Contact page primary key |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none |

**Request body** — the same `ContactRequest` as endpoint 2.

```json
{
  "slugCkb": "peywendi-halabja",
  "slugKmr": "tekili-helebce",
  "ckbContent": {
    "title": "نووسینگەی هەڵەبجە",
    "subtitle": "بەشی ئەرشیفی مێژووی هەڵەبجە",
    "address": "هەڵەبجە، شەقامی سەرەکی، بەرامبەر مۆزەخانەی شەهیدان",
    "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ – ١٦:٠٠",
    "description": "<p>کاتی کارکردن نوێکرایەوە بۆ ساڵی ٢٠٢٦.</p>"
  },
  "kmrContent": {
    "title": "Nivîsgeha Helebceyê",
    "subtitle": "Beşa arşîva dîrokî ya Helebceyê",
    "address": "Helebce, Kolana Sereke",
    "workingHours": "Şemî – Pêncşem, 9:00 – 16:00",
    "description": "<p>Demjimêrên xebatê ji bo sala 2026'an hatin nûkirin.</p>"
  },
  "phone": "+964 770 555 2211",
  "secondaryPhone": "+964 750 555 2212",
  "email": "halabja@khi.krd",
  "latitude": 35.1778,
  "longitude": 45.9861,
  "officeType": "REGIONAL",
  "badgeCkb": "نووسینگەی هەرێمی",
  "badgeKmr": "Nivîsgeha Herêmî",
  "active": true,
  "displayOrder": 3
}
```

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Contact page updated successfully",
  "data": {
    "id": 4,
    "slugCkb": "peywendi-halabja",
    "slugKmr": "tekili-helebce",
    "ckbContent": {
      "title": "نووسینگەی هەڵەبجە",
      "subtitle": "بەشی ئەرشیفی مێژووی هەڵەبجە",
      "address": "هەڵەبجە، شەقامی سەرەکی، بەرامبەر مۆزەخانەی شەهیدان",
      "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ – ١٦:٠٠",
      "description": "<p>کاتی کارکردن نوێکرایەوە بۆ ساڵی ٢٠٢٦.</p>"
    },
    "kmrContent": {
      "title": "Nivîsgeha Helebceyê",
      "subtitle": "Beşa arşîva dîrokî ya Helebceyê",
      "address": "Helebce, Kolana Sereke",
      "workingHours": "Şemî – Pêncşem, 9:00 – 16:00",
      "description": "<p>Demjimêrên xebatê ji bo sala 2026'an hatin nûkirin.</p>"
    },
    "phone": "+964 770 555 2211",
    "secondaryPhone": "+964 750 555 2212",
    "email": "halabja@khi.krd",
    "latitude": 35.1778,
    "longitude": 45.9861,
    "officeType": "REGIONAL",
    "badgeCkb": "نووسینگەی هەرێمی",
    "badgeKmr": "Nivîsgeha Herêmî",
    "displayOrder": 3,
    "active": true,
    "createdAt": "2026-08-26 09:14:22",
    "updatedAt": "2026-08-26 10:41:07"
  }
}
```

Note that `mapEmbedUrl` and `heroImageUrl` are gone from the response: they were omitted from the
request, so they were nulled and are then dropped from the JSON by `non_null` inclusion.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No page with that id — message reads `Contact not found: 4` |
| `400` | `VALIDATION_ERROR` | `phone` blank, `email` blank or malformed |
| `400` | `BAD_REQUEST` | Any of the four slug rules (required / CKB taken / KMR taken / identical) |
| `409` | `CONFLICT` | DB unique index violation |
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long`, wrong `Content-Type`, or a storage/DB failure |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/contact/4 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @halabja-contact.json
```

---

## 4. `DELETE /api/v1/contact/{id}` — Delete a contact page

Hard delete. The row is loaded first (so a missing id is a clean `404`) and then removed. Both
embedded content blocks live in the same row and disappear with it. There is **no soft delete and
no undo** — set `active: false` through `PUT` if you only want to unpublish.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Contact page primary key |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none |

**Response `200 OK`**

`data` is `null`, and `@JsonInclude(NON_NULL)` removes the key entirely — the body has exactly two
properties. Note the status is `200`, not `204`.

```json
{
  "success": true,
  "message": "Contact page deleted successfully"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No page with that id — `Contact not found: 4` |
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long`, or a DB failure |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/contact/4 \
  -H "Authorization: Bearer $TOKEN"
```

> **Note:** deleting a contact page does **not** delete the S3 objects referenced from its Tiptap
> `description` or from `heroImageUrl`. Those files stay in `s3-khiwebsite/khi-web-folders/…`
> forever. There is no orphan-cleanup job in the codebase.

---

## 5. `GET /api/v1/contact/messages` — Visitor message inbox

Paged list of every submission, **newest first** (`PageRequest.of(page, size, Sort.by(DESC, "createdAt"))`).
No status filter, no search, no date range — the dashboard must filter client-side or page through
everything. Read-only.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | none |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | integer | no | `0` | Zero-based page index. Must be `>= 0` |
| `size` | integer | no | `20` | Page size. Must be `>= 1` |

**Response `200 OK`**

`data` is a Spring Data `Page` serialized directly, so it carries Spring's page metadata. Unlike
`/contact/active`, the sort **is** attached to the `PageRequest`, so `sort` reports it.

(The `content` array below is truncated to two entries for readability; a real `size=20` page
returns twenty.)

```json
{
  "success": true,
  "message": "Contact messages fetched",
  "data": {
    "content": [
      {
        "id": 187,
        "name": "ڕێبین ئەحمەد",
        "email": "rebin.ahmed@example.com",
        "phone": "+964 751 234 5678",
        "subject": "داواکاری گەڕان لە ئەرشیفی وێنە مێژووییەکان",
        "message": "سڵاو، دەمەوێت زانیاری زیاتر لەسەر ئەرشیفی وێنەکانی هەولێر لە ساڵانی ١٩٦٠ بزانم. سوپاس.",
        "locale": "ckb",
        "status": "NEW",
        "createdAt": "2026-08-26T09:14:22.481"
      },
      {
        "id": 186,
        "name": "Berîvan Silêman",
        "email": "berivan.suleyman@example.com",
        "subject": "Pêşniyara hevkariyê ji bo arşîva dengî",
        "message": "Silav, em dixwazin di projeya arşîva dengî de bi we re hevkariyê bikin.",
        "locale": "kmr",
        "status": "IN_REVIEW",
        "createdAt": "2026-08-25T16:02:58.117"
      }
    ],
    "pageable": {
      "offset": 0,
      "pageNumber": 0,
      "pageSize": 20,
      "paged": true,
      "unpaged": false,
      "sort": { "empty": false, "sorted": true, "unsorted": false }
    },
    "totalElements": 187,
    "totalPages": 10,
    "size": 20,
    "number": 0,
    "numberOfElements": 20,
    "first": true,
    "last": false,
    "empty": false,
    "sort": { "empty": false, "sorted": true, "unsorted": false }
  }
}
```

`ContactMessageResponse` fields: see
[`../external/CONTACT_API.md`](../external/CONTACT_API.md#4-post-apiv1contactmessages--submit-the-visitor-contact-form).
`phone` and `locale` are absent when `null` (message 186 above submitted no phone number).

> **Note:** the whole message body is returned in the list response — there is no per-message
> detail endpoint, and no `GET /api/v1/contact/messages/{id}`. Page size directly controls how
> much text you pull; keep it modest (the `message` column is `TEXT`, capped at 10 000 characters
> by the submit-side validation).

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page` < 0 or `size` < 1 |
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not a number |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/contact/messages?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 6. `PATCH /api/v1/contact/messages/{id}/status` — Change a message's status

Moves one message to another status. The value is trimmed and upper-cased before validation, so
`"in_review"`, `" In_Review "` and `"IN_REVIEW"` are all accepted and all stored as `IN_REVIEW`.
There is no workflow enforcement — any allowed status can follow any other, including going back
to `NEW`. No notification is sent, nothing else on the row changes, and `createdAt` is untouched
(the entity has no `updatedAt`).

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Contact message primary key |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none |

**Request body** — `SiteContentDtos.StatusRequest`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `status` | string | yes | `@NotBlank`; must be one of the seven values below after trim + upper-case | New status |

```json
{ "status": "IN_REVIEW" }
```

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Contact message status updated",
  "data": {
    "id": 187,
    "name": "ڕێبین ئەحمەد",
    "email": "rebin.ahmed@example.com",
    "phone": "+964 751 234 5678",
    "subject": "داواکاری گەڕان لە ئەرشیفی وێنە مێژووییەکان",
    "message": "سڵاو، دەمەوێت زانیاری زیاتر لەسەر ئەرشیفی وێنەکانی هەولێر لە ساڵانی ١٩٦٠ بزانم. سوپاس.",
    "locale": "ckb",
    "status": "IN_REVIEW",
    "createdAt": "2026-08-26T09:14:22.481"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No message with that id — `Contact message not found: 187` |
| `400` | `VALIDATION_ERROR` | `status` missing or blank (`fieldErrors[0].field = "status"`) |
| `400` | `BAD_REQUEST` | Status not in the allowed set — `details.reason` = `Unsupported status: ARCHIVED` |
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long`, or wrong `Content-Type` |

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/contact/messages/187/status \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_REVIEW"}'
```

---

## Enums used by this API

### Message status

Not a Java `enum` — a `Set<String>` constant (`SiteContentService.SUBMISSION_STATUSES`) validated
against a `varchar(30)` column (`ContactMessage.status`, `NOT NULL`, default `"NEW"`). The same set
is shared with financial and archive donation submissions, which is why it contains values that do
not obviously fit a contact message.

| Value | Meaning for a contact message |
|-------|-------------------------------|
| `NEW` | Just submitted. The only value the public endpoint can produce; also the entity default |
| `PENDING` | Acknowledged, waiting on someone |
| `IN_REVIEW` | A staff member is working on it |
| `APPROVED` | Accepted / actioned. Carried over from the donation workflow |
| `COMPLETED` | Answered and finished |
| `REJECTED` | Declined. Carried over from the donation workflow |
| `CLOSED` | Filed without further action |

Input is normalised with `status.trim().toUpperCase(Locale.ROOT)` before the set check.

### Everything else is free text

| Field | Type | Validation |
|-------|------|------------|
| `officeType` | `varchar(40)` | none. `HQ` / `REGIONAL` by convention only — a typo is stored as-is |
| `locale` (message) | `varchar(10)` | `@Size(max = 10)` only. Not checked against the `Language` enum (`CKB` / `KMR`) |
| Language blocks | `ckbContent`, `kmrContent` | structural, not enum-driven — Contact never references the `Language` enum |

---

## Auth failure bodies

Contact handlers have no `@PreAuthorize`, so every rejection happens in the Spring Security filter
chain, **before** `GlobalExceptionHandler` can see it. Do not expect the `ApiErrorResponse`
envelope on a 401 or 403 here, and do not parse `code` from one.

| Situation | Status | Body |
|-----------|--------|------|
| No token at all | `403` | Produced by Spring Security's default entry point (`Http403ForbiddenEntryPoint` — the project's `JwtAuthenticationEntryPoint` bean is never registered on the chain). Expect an empty body or Spring Boot's generic error JSON, not `ApiErrorResponse` |
| Valid token, wrong role (`EMPLOYEE`, `GUEST`) | `403` | Same — `AccessDeniedHandlerImpl`, not the advice |
| Expired token | `401` | `JWTAuthenticationFilter` writes it directly: `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}`. The auth cookie is cleared |
| Malformed / unverifiable token | `403` | `{"error":"INVALID_TOKEN","message":"Invalid token"}`. Cookie cleared |
| Token revoked by logout | `401` | `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}`. Cookie cleared |

The `ApiErrorResponse` shape with `code: "FORBIDDEN"` **does** exist in `GlobalExceptionHandler`,
but it is only reachable for handlers annotated with `@PreAuthorize` — none in this domain. Branch
your dashboard's error handling on the HTTP status, and fall back to a generic message when the
body has no `code`.

---

## Notes & gotchas

- **`GET /api/v1/contact` vs `GET /api/v1/contact/active`.** Different auth, different response
  shape (array vs page), different filtering (all vs `active == true`), different ordering
  (undefined vs `displayOrder ASC`). Repeated here because it is the single most common
  integration mistake in this domain.
- **`PUT` is destructive.** Omitted fields become `null`. Only `active` and `displayOrder` survive
  omission. Always send the complete object.
- **Omitting a content block wipes it.** `buildContent(null)` returns a fresh empty
  `ContactContent`, so a `PUT` without `kmrContent` nulls `title_kmr`, `subtitle_kmr`,
  `address_kmr`, `working_hours_kmr` and `description_kmr`.
- **Slug rules.** `slugCkb` is required and unique; `slugKmr` is optional and unique when present;
  the two must differ. Checks are case-sensitive exact matches on the trimmed value, and they are
  a read-then-write with no lock — two simultaneous creates with the same slug produce a
  `409 CONFLICT` from the database unique index rather than the friendly `400`.
- **No caching, anywhere in this domain.** `ContactService` has no `@Cacheable` / `@CacheEvict`, and
  neither do the contact-message methods on `SiteContentService`. Redis (`khi:` prefix, 10-minute
  default TTL) is configured for the app but not used here, so a write is visible to the public
  site immediately — no cache eviction step is needed after a save.
- **No audit trail.** Nothing records who created, edited or deleted a contact page, or who changed
  a message's status. The service only writes an INFO log line (`Contact page updated — id=4`).
- **Message inbox is read-plus-status only.** There is no delete, no export, no reply, no
  assignment, and no `GET /messages/{id}`. A message row can only ever change its `status`.
- **Type-coercion failures answer 500, not 400/415.** `GlobalExceptionHandler` registers no handler
  for `MethodArgumentTypeMismatchException` or `HttpMediaTypeNotSupportedException`, so
  `DELETE /api/v1/contact/abc`, `?page=x`, and a `POST` with `Content-Type: text/plain` all fall to
  `@ExceptionHandler(Exception.class)` → `500 INTERNAL_ERROR`. Guard in the dashboard.
- **Localized error text is unreliable.** For service-thrown `IllegalArgumentException`s the
  specific cause is in `details.reason`; `message` is replaced by a generic bundle string when
  `Accept-Language` is `ckb` or `kmr`. Separately, the English bundle file on disk is named
  `" messages_en.properties"` (leading space) so it never loads, and the Kurdish bundles contain
  literal `?` characters where Kurdish letters should be. Render your own copy from `code` +
  `details.reason`; do not display `message` / `messageEn` / `messageKu` to staff.
- **Timestamps.** Contact pages return `createdAt` / `updatedAt` as `yyyy-MM-dd HH:mm:ss` strings
  formatted in `ContactService`; contact messages return `createdAt` as ISO-8601
  (`2026-08-26T09:14:22.481`) straight from Jackson. Neither carries a zone offset. The database
  runs `hibernate.jdbc.time_zone=UTC` — treat both as UTC and convert to `Asia/Baghdad` for display.
- **`X-Trace-Id`.** Sent on every response and echoed if you supply it; it is the `traceId` inside
  any `ApiErrorResponse`. Surface it in dashboard error toasts.
- **Swagger group naming is misleading here.** `/v3/api-docs/public` matches `/api/v1/contact/**`,
  so these admin operations appear under the "Public API (no auth)" group. `SecurityConfig` is the
  authority, not the springdoc group.
- **Media pipeline.** `POST /api/v1/media/upload` (multipart) is itself ADMIN/SUPER_ADMIN — the
  same token works. Max file size and max request size are both 1 GB.

---

## Related documentation

- Counterpart: [`../external/CONTACT_API.md`](../external/CONTACT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
