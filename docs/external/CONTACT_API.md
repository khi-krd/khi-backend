# Contact API — External (Public)

The Contact domain covers two unrelated things that happen to share a URL prefix:

1. **Contact pages** — bilingual (Sorani / Kurmanji) "how to reach us" pages, one per office.
   Each page carries a title, subtitle, address, working hours, a rich Tiptap HTML description,
   phone numbers, an email address, an embedded map and map coordinates. The public website
   renders these read-only.
2. **Contact messages** — the visitor contact form. An anonymous visitor POSTs a message; it is
   stored with status `NEW` and read later from the admin dashboard.

Everything on this page is callable without a token. The admin-side counterparts (list-all,
create, update, delete, read messages, change message status) live in
[`../internal/CONTACT_API.md`](../internal/CONTACT_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/contact` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/contact/ContactController.java` |
| **Controller (messages)** | `src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/contact/ContactService.java` |
| **Service (messages)** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **DTOs** | `ContactDTOs.java`, `SiteContentDtos.java` |
| **Entities** | `Contact`, `ContactContent` (embeddable), `ContactMessage` |
| **Tables** | `contact_pages`, `contact_messages` |
| **Verified against source** | 2026-08-26 |

---

## The one thing frontend developers get wrong

`GET /api/v1/contact` and `GET /api/v1/contact/active` are **not** the same endpoint and **not**
the same authorization level.

| Call | Auth | Returns | Shape |
|------|------|---------|-------|
| `GET /api/v1/contact/active` | **public** | active pages only, ordered by `displayOrder` | **paged** — `data` is a Spring Data page object |
| `GET /api/v1/contact` | **ADMIN / SUPER_ADMIN** | every page, active **and** inactive | **flat list** — `data` is a JSON array |

`SecurityConfig` pins the bare collection path behind an admin role before the public catch-all
rule is reached:

```java
// SecurityConfig.java — rule 9, evaluated BEFORE the GET /api/v1/** permitAll rule
.requestMatchers(HttpMethod.GET,
        "/api/v1/contact/messages",
        ...
        "/api/v1/contact"
).hasAnyRole("ADMIN", "SUPER_ADMIN")
...
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

Because the matcher is the exact literal `/api/v1/contact` (no `/**`), the sub-paths
`/active`, `/{id}` and `/slug/{slug}` fall through to the public catch-all and stay open.
A public site that calls `GET /api/v1/contact` gets **403**, not data. Use `/active`.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/contact/active` | None | — | Paged list of active contact pages, ordered by `displayOrder` |
| 2 | `GET` | `/api/v1/contact/{id}` | None | — | One contact page by numeric id |
| 3 | `GET` | `/api/v1/contact/slug/{slug}` | None | — | One contact page by CKB **or** KMR slug |
| 4 | `POST` | `/api/v1/contact/messages` | None | — | Submit the visitor contact form |

---

## Response envelope

Every endpoint on this page returns the `ApiResponse<T>` envelope:

```json
{
  "success": true,
  "message": "Contact page fetched",
  "data": {}
}
```

`ApiResponse` is annotated `@JsonInclude(NON_NULL)` and the application sets
`spring.jackson.default-property-inclusion: non_null` globally, so **null fields are omitted
from the JSON entirely** — both from the envelope and from `ContactResponse`. Never assume a
key is present; assume `undefined` means `null`.

---

## The `ContactResponse` object

Returned by endpoints 1, 2 and 3. Source: `ContactDTOs.ContactResponse`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key |
| `slugCkb` | string | no | Sorani URL slug. Unique across all contact pages |
| `slugKmr` | string | yes | Kurmanji URL slug. Unique when present |
| `ckbContent` | object (`ContactContentResponse`) | yes | Sorani text block — see below |
| `kmrContent` | object (`ContactContentResponse`) | yes | Kurmanji text block — see below |
| `phone` | string | yes | Primary phone, free-form (e.g. `+964 750 123 4567`) |
| `secondaryPhone` | string | yes | Secondary phone |
| `email` | string | yes | Contact email |
| `mapEmbedUrl` | string | yes | Google Maps (or any iframe-compatible) embed URL |
| `latitude` | number (double) | yes | Latitude for a custom marker / "open in maps" link |
| `longitude` | number (double) | yes | Longitude |
| `heroImageUrl` | string | yes | Absolute S3 URL of the page hero image |
| `officeType` | string | yes | Free text, not an enum. Conventionally `HQ` or `REGIONAL` |
| `badgeCkb` | string | yes | Short Sorani badge label shown next to the office name |
| `badgeKmr` | string | yes | Short Kurmanji badge label |
| `displayOrder` | integer (int32) | yes | Sort key for `/active`, ascending. Defaults to `0` |
| `active` | boolean | no | Whether the page is published. Primitive — always present |
| `createdAt` | string | yes | `yyyy-MM-dd HH:mm:ss`, no zone offset — treat as UTC |
| `updatedAt` | string | yes | `yyyy-MM-dd HH:mm:ss`, no zone offset — treat as UTC |

### `ContactContentResponse`

The same shape for both languages. Source: `ContactDTOs.ContactContentResponse`, persisted as the
`ContactContent` `@Embeddable`.

| Field | Type | Nullable | DB column limit | Description |
|-------|------|----------|-----------------|-------------|
| `title` | string | yes | 300 | Page title in this language |
| `subtitle` | string | yes | 500 | Short subtitle / call to action |
| `address` | string | yes | 500 | Physical address in this language |
| `workingHours` | string | yes | 300 | Office hours in this language |
| `description` | string | yes | `TEXT` | Tiptap **HTML**. Contains inline `<img>` / `<video>` / `<audio>` / `<a href>` tags pointing at S3 |

> **Note:** `description` is raw HTML produced by the admin's Tiptap editor. Render it as HTML
> (`dangerouslySetInnerHTML` / `v-html`), not as plain text. It is *not* sanitized by the API
> beyond the base64 → S3 rewrite performed by `TiptapHtmlProcessor`, so sanitize on the client
> if your threat model requires it.

> **Note:** `Contact.java` and `ContactDTOs.java` both carry a Javadoc paragraph claiming
> "Contact no longer carries a hero image … field". That comment is stale: `heroImageUrl` is a
> real, persisted, returned field (`contact_pages.hero_image_url`). Trust the field, not the comment.

---

## 1. `GET /api/v1/contact/active` — List published contact pages

Returns every contact page whose `active` flag is `true`, ordered by `displayOrder` ascending,
wrapped in a Spring Data page. This is the endpoint the public website's "Contact" index uses.
No side effects, no caching — the service method is `@Transactional(readOnly = true)` and hits
PostgreSQL on every call.

**Auth:** None (public)
**Content-Type:** — (no request body)
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

`data` is the Spring Data `Page` serialized directly (serialization mode `DIRECT`), so it carries
Spring's own page metadata alongside `content`:

```json
{
  "success": true,
  "message": "Active contact pages fetched",
  "data": {
    "content": [
      {
        "id": 1,
        "slugCkb": "peywendi",
        "slugKmr": "tekili",
        "ckbContent": {
          "title": "پەیوەندیمان پێوە بکە",
          "subtitle": "تیمی ئێمە ئامادەیە بۆ وەڵامدانەوەی پرسیارەکانت",
          "address": "هەولێر، شەقامی ٦٠ مەتری، نزیک پارکی سامی عەبدولڕەحمان",
          "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ – ١٧:٠٠",
          "description": "<p>بارەگای سەرەکی دەزگای کەلەپووری کوردستان لە هەولێرە.</p><img src=\"https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/contact-hq-erbil.jpg\" alt=\"بارەگای هەولێر\">"
        },
        "kmrContent": {
          "title": "Bi me re têkilî deynin",
          "subtitle": "Tîma me amade ye ku bersiva pirsên we bide",
          "address": "Hewlêr, Kolana 60 Metreyî, nêzî Parka Samî Ebdulrehman",
          "workingHours": "Şemî – Pêncşem, 9:00 – 17:00",
          "description": "<p>Navenda sereke ya Enstîtuya Mîrateya Kurdistanê li Hewlêrê ye.</p>"
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
        "id": 2,
        "slugCkb": "peywendi-dhok",
        "slugKmr": "tekili-duhok",
        "ckbContent": {
          "title": "نووسینگەی دهۆک",
          "address": "دهۆک، گەڕەکی مالتا، تەنیشت زانکۆی دهۆک",
          "workingHours": "شەممە – چوارشەممە، ٩:٠٠ – ١٦:٠٠",
          "description": "<p>نووسینگەی هەرێمی دهۆک خزمەتگوزاری ئەرشیف پێشکەش دەکات.</p>"
        },
        "kmrContent": {
          "title": "Nivîsgeha Duhokê",
          "address": "Duhok, Taxa Malta, li kêleka Zanîngeha Duhokê",
          "workingHours": "Şemî – Çarşem, 9:00 – 16:00",
          "description": "<p>Nivîsgeha herêmî ya Duhokê xizmetên arşîvê pêşkêş dike.</p>"
        },
        "phone": "+964 751 445 8890",
        "email": "duhok@khi.krd",
        "latitude": 36.8669,
        "longitude": 42.9884,
        "officeType": "REGIONAL",
        "displayOrder": 1,
        "active": true,
        "createdAt": "2026-04-02 13:41:05",
        "updatedAt": "2026-04-02 13:41:05"
      }
    ],
    "pageable": {
      "offset": 0,
      "pageNumber": 0,
      "pageSize": 20,
      "paged": true,
      "unpaged": false,
      "sort": { "empty": true, "sorted": false, "unsorted": true }
    },
    "totalElements": 2,
    "totalPages": 1,
    "size": 20,
    "number": 0,
    "numberOfElements": 2,
    "first": true,
    "last": true,
    "empty": false,
    "sort": { "empty": true, "sorted": false, "unsorted": true }
  }
}
```

> **Note:** `sort.unsorted` is `true` even though the rows *are* ordered. The ordering comes from
> the repository method name (`findAllByActiveTrueOrderByDisplayOrderAsc`), not from a `Sort`
> object attached to the `PageRequest`, so Spring reports the page as unsorted. Rely on array
> order, not on the `sort` metadata. There is no `sort` request parameter — sending one is ignored.

An empty result set is a normal `200` with `"content": []`, `"empty": true`, `"totalElements": 0`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | `page` < 0 (`details.reason` = "Page index must not be less than zero") or `size` < 1 ("Page size must not be less than one") |
| `500` | `INTERNAL_ERROR` | `page` or `size` is not a number — see the type-coercion note under [Notes & gotchas](#notes--gotchas) |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/contact/active?page=0&size=20"
```

---

## 2. `GET /api/v1/contact/{id}` — One contact page by id

Fetches a single contact page by primary key. Read-only, no side effects.

**Auth:** None (public)
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

```json
{
  "success": true,
  "message": "Contact page fetched",
  "data": {
    "id": 1,
    "slugCkb": "peywendi",
    "slugKmr": "tekili",
    "ckbContent": {
      "title": "پەیوەندیمان پێوە بکە",
      "subtitle": "تیمی ئێمە ئامادەیە بۆ وەڵامدانەوەی پرسیارەکانت",
      "address": "هەولێر، شەقامی ٦٠ مەتری، نزیک پارکی سامی عەبدولڕەحمان",
      "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ – ١٧:٠٠",
      "description": "<p>بۆ پەیوەندی ڕاستەوخۆ تەلەفۆنمان بۆ بکە یان ئیمەیڵ بنێرە.</p>"
    },
    "kmrContent": {
      "title": "Bi me re têkilî deynin",
      "address": "Hewlêr, Kolana 60 Metreyî",
      "workingHours": "Şemî – Pêncşem, 9:00 – 17:00",
      "description": "<p>Ji bo têkiliya rasterast telefonê bikin an e-name bişînin.</p>"
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
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No row with that id. `message`, `messageEn`, `messageKu` and `details.resource` all read `Contact not found: 42` |
| `500` | `INTERNAL_ERROR` | `{id}` is not a valid `long` (e.g. `/api/v1/contact/abc`) |

**Example**

```bash
curl -s http://localhost:8080/api/v1/contact/1
```

> **Note:** this endpoint does **not** filter on `active`. An anonymous caller who guesses or
> remembers an id can read a contact page that has been unpublished. Treat "inactive" as
> "hidden from the index", not as "private".

---

## 3. `GET /api/v1/contact/slug/{slug}` — One contact page by slug

Resolves a page from either language slug. The repository query is
`findBySlugCkbOrSlugKmr(slug, slug)`, so `/slug/peywendi` and `/slug/tekili` both return the same
row. This is what the public website's localized routes (`/ckb/peywendi`, `/kmr/tekili`) call.

**Auth:** None (public)
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `slug` | string | yes | The Sorani (`slugCkb`) **or** Kurmanji (`slugKmr`) slug. Matched exactly — case-sensitive, no trimming, no normalisation |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none |

**Response `200 OK`**

Identical body to endpoint 2.

```json
{
  "success": true,
  "message": "Contact page fetched",
  "data": {
    "id": 2,
    "slugCkb": "peywendi-dhok",
    "slugKmr": "tekili-duhok",
    "ckbContent": {
      "title": "نووسینگەی دهۆک",
      "address": "دهۆک، گەڕەکی مالتا، تەنیشت زانکۆی دهۆک",
      "workingHours": "شەممە – چوارشەممە، ٩:٠٠ – ١٦:٠٠",
      "description": "<p>نووسینگەی هەرێمی دهۆک خزمەتگوزاری ئەرشیف پێشکەش دەکات.</p>"
    },
    "kmrContent": {
      "title": "Nivîsgeha Duhokê",
      "address": "Duhok, Taxa Malta",
      "workingHours": "Şemî – Çarşem, 9:00 – 16:00",
      "description": "<p>Nivîsgeha herêmî ya Duhokê xizmetên arşîvê pêşkêş dike.</p>"
    },
    "phone": "+964 751 445 8890",
    "email": "duhok@khi.krd",
    "latitude": 36.8669,
    "longitude": 42.9884,
    "officeType": "REGIONAL",
    "displayOrder": 1,
    "active": true,
    "createdAt": "2026-04-02 13:41:05",
    "updatedAt": "2026-04-02 13:41:05"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | No page matches the slug in either language. Message reads `Contact page not found: peywendi-xyz` |

**Example**

```bash
curl -s http://localhost:8080/api/v1/contact/slug/tekili-duhok
```

> **Note:** the literal path segment `slug` shadows nothing — `/api/v1/contact/active` and
> `/api/v1/contact/slug/...` are distinct mappings and take priority over `/{id}`. A page whose
> `slugCkb` is literally `active` is still reachable through `/api/v1/contact/slug/active`.

---

## 4. `POST /api/v1/contact/messages` — Submit the visitor contact form

Stores a visitor message and returns it. This is the only write on the public surface and it is
explicitly allow-listed in `SecurityConfig`:

```java
.requestMatchers(HttpMethod.POST,
        "/api/v1/contact/messages", ...
).permitAll()
```

Side effects: one row inserted into `contact_messages` with `status = "NEW"`. The status is set by
the server and cannot be influenced by the request body. Nothing else happens — **no email is
sent, no notification is dispatched, no webhook fires**. Staff see the message only when the
dashboard calls `GET /api/v1/contact/messages`.

`name`, `email` and `subject` are trimmed before saving. `phone` and `locale` are trimmed and
stored as `null` when blank. `message` is stored **verbatim**, including leading/trailing
whitespace and newlines.

**Auth:** None (public)
**Content-Type:** `application/json`
**Produces:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| — | — | — | — |

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| — | — | — | — | none |

**Request body** — `SiteContentDtos.ContactMessageRequest`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `name` | string | yes | `@NotBlank`, `@Size(max = 200)` | Visitor's name |
| `email` | string | yes | `@NotBlank`, `@Email`, `@Size(max = 254)` | Reply-to address |
| `phone` | string | no | `@Size(max = 60)` | Free-form phone number |
| `subject` | string | yes | `@NotBlank`, `@Size(max = 300)` | Subject line |
| `message` | string | yes | `@NotBlank`, `@Size(max = 10000)` | Message body, plain text |
| `locale` | string | no | `@Size(max = 10)` | Which language the visitor used. Free text — not validated against the `Language` enum. The website sends `ckb` or `kmr` |

Any other field in the body is not part of the DTO and is not persisted.

```json
{
  "name": "ڕێبین ئەحمەد",
  "email": "rebin.ahmed@example.com",
  "phone": "+964 751 234 5678",
  "subject": "داواکاری گەڕان لە ئەرشیفی وێنە مێژووییەکان",
  "message": "سڵاو، دەمەوێت زانیاری زیاتر لەسەر ئەرشیفی وێنەکانی هەولێر لە ساڵانی ١٩٦٠ بزانم. سوپاس.",
  "locale": "ckb"
}
```

**Response `201 Created`**

There is no `Location` header — the created resource is not publicly readable.

```json
{
  "success": true,
  "message": "Contact message received",
  "data": {
    "id": 187,
    "name": "ڕێبین ئەحمەد",
    "email": "rebin.ahmed@example.com",
    "phone": "+964 751 234 5678",
    "subject": "داواکاری گەڕان لە ئەرشیفی وێنە مێژووییەکان",
    "message": "سڵاو، دەمەوێت زانیاری زیاتر لەسەر ئەرشیفی وێنەکانی هەولێر لە ساڵانی ١٩٦٠ بزانم. سوپاس.",
    "locale": "ckb",
    "status": "NEW",
    "createdAt": "2026-08-26T09:14:22.481"
  }
}
```

`ContactMessageResponse` fields:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key |
| `name` | string | no | As submitted, trimmed |
| `email` | string | no | As submitted, trimmed |
| `phone` | string | yes | `null` when omitted or blank |
| `subject` | string | no | As submitted, trimmed |
| `message` | string | no | As submitted, untrimmed |
| `locale` | string | yes | `null` when omitted or blank |
| `status` | string | no | Always `NEW` on creation |
| `createdAt` | string | no | ISO-8601 local date-time, e.g. `2026-08-26T09:14:22.481`. **No zone offset** — see the timestamp note below |

> **Note:** `createdAt` here is serialized by Jackson from a `LocalDateTime`, so it is ISO-8601
> (`2026-08-26T09:14:22.481`). On contact **pages** (`ContactResponse`) the service formats the
> same kind of value by hand as `yyyy-MM-dd HH:mm:ss` (`2026-08-26 09:14:22`). Two different
> timestamp formats in one domain — parse each with the format documented for that endpoint.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | A `@NotBlank` / `@Email` / `@Size` constraint failed. `fieldErrors[]` names each offending field |
| `400` | `BAD_REQUEST` | Body is missing or not parseable JSON (`details.hint` explains) |
| `500` | `INTERNAL_ERROR` | `Content-Type` is not `application/json` — see [Notes & gotchas](#notes--gotchas) |

Validation failure body:

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/contact/messages",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "One or more fields failed validation.",
  "messageEn": "One or more fields failed validation.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    {
      "field": "email",
      "message": "must be a well-formed email address",
      "messageEn": "must be a well-formed email address",
      "messageKu": "must be a well-formed email address"
    },
    {
      "field": "message",
      "message": "must not be blank",
      "messageEn": "must not be blank",
      "messageKu": "must not be blank"
    }
  ]
}
```

Per-field messages come straight from Jakarta Bean Validation's default English text; they are
looked up in the message bundle first, and since no key matches, the same English string is
returned for all three locales. Do not display `fieldErrors[].messageKu` to Kurdish users —
map the `field` name to your own copy instead.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/contact/messages \
  -H "Content-Type: application/json" \
  -H "Accept-Language: ckb" \
  -d '{
        "name": "Rêbin Ehmed",
        "email": "rebin.ahmed@example.com",
        "phone": "+964 751 234 5678",
        "subject": "Daxwaza lêgerînê di arşîva wêneyan de",
        "message": "Silav, ez dixwazim zêdetir li ser arşîva wêneyên Hewlêrê bizanim.",
        "locale": "kmr"
      }'
```

---

## Enums used by this API

Contact does **not** use the `Language` enum. Bilingual content is structural: each page carries a
`ckbContent` and a `kmrContent` object, and two slug columns. There is no `?lang=` parameter and
no per-language endpoint — pick the block you need on the client.

| Concept | Values | Where |
|---------|--------|-------|
| Language blocks | `ckbContent` (Sorani), `kmrContent` (Kurmanji) | `ContactResponse` |
| `officeType` | Free-text `varchar(40)`, no validation. Conventionally `HQ`, `REGIONAL` | `Contact.officeType` |
| `locale` (contact message) | Free-text `varchar(10)`, no validation. Website sends `ckb` or `kmr` | `ContactMessage.locale` |
| Message `status` | `NEW`, `PENDING`, `IN_REVIEW`, `APPROVED`, `COMPLETED`, `REJECTED`, `CLOSED` — a `Set<String>` in `SiteContentService`, stored as `varchar(30)`. Public submissions are always `NEW`; the rest are set by staff | `ContactMessage.status` |

`Accept-Language` accepts `en`, `ckb` and `kmr` (`AcceptHeaderLocaleResolver`, default `en`). It
only affects the `message` field of an error body — never the content itself.

---

## Notes & gotchas

- **No caching.** `ContactService` carries no `@Cacheable` / `@CacheEvict`. Redis is configured for
  the application (prefix `khi:`, TTL 10 minutes) but the contact reads bypass it and query
  PostgreSQL on every request. Cache on the CDN / client if you need it.
- **`/active` is the only endpoint that filters on `active`.** `/{id}` and `/slug/{slug}` return
  unpublished pages too.
- **Two timestamp formats.** Contact pages: `"2026-08-19 11:05:47"` (string, formatted in
  `ContactService`). Contact messages: `"2026-08-26T09:14:22.481"` (ISO-8601, Jackson). Neither
  carries a zone offset. The database runs `hibernate.jdbc.time_zone=UTC`; treat both as UTC and
  convert to `Asia/Baghdad` on the client if you display them.
- **Null fields vanish.** With `default-property-inclusion: non_null`, absent keys mean `null`.
  `active` is a Java primitive so it is always present; `displayOrder` is boxed and can be absent
  in theory, though the service defaults it to `0` on write.
- **Type-coercion failures answer 500, not 400.** `GlobalExceptionHandler` has no handler for
  `MethodArgumentTypeMismatchException` or `HttpMediaTypeNotSupportedException`, so both land in
  the `@ExceptionHandler(Exception.class)` catch-all and return `500 INTERNAL_ERROR`.
  Practically: `/api/v1/contact/abc`, `?page=x`, or posting the contact form with
  `Content-Type: text/plain` all produce 500. Validate ids and numbers client-side.
- **`X-Trace-Id`.** Every response carries an `X-Trace-Id` header; if you send one, the server
  reuses it. The same value appears as `traceId` in any error body — log it and quote it in bug
  reports.
- **CORS.** Allowed origins are configured in `application.yaml` (`app.cors.allowed-origins`) and
  include the production website, the dashboard, and `http://localhost:5173` / `:3000` for local
  development. Credentials are allowed, methods `GET, POST, PUT, DELETE, OPTIONS, PATCH`.
  `OPTIONS /**` is `permitAll`, so preflight never needs a token.
- **No spam protection.** `POST /api/v1/contact/messages` has no captcha, no rate limiter, no
  honeypot and no per-IP throttle anywhere in the codebase. If you need abuse protection, add it
  in front of the API (edge / reverse proxy) — the backend will accept whatever it is given.
- **Error bodies are only partly bilingual.** `messageEn` and `messageKu` are always present, but
  several handlers fall back to the raw English reason for both. In particular a `404` sets
  `message`, `messageEn` and `messageKu` to the same English sentence (`Contact not found: 42`).
  Do not surface those strings to visitors — key your own copy off `code` instead.
- **Live spec.** Swagger UI at `/swagger-ui.html`, raw JSON at `/v3/api-docs`. Group `public`
  (`/v3/api-docs/public`) matches `/api/v1/contact/**`, which means it also lists the admin-only
  contact operations. The Swagger grouping is a documentation convenience, not the authorization
  boundary — `SecurityConfig` is.
- **Servers.** `http://localhost:8080` locally; production runs on Railway behind HTTPS
  (`server.forward-headers-strategy: framework`).

---

## Related documentation

- Counterpart: [`../internal/CONTACT_API.md`](../internal/CONTACT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
