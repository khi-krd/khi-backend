# Donation API — External (Public)

The Donation domain is the institute's fundraising and acquisition front door. It covers three
things that all live under `/api/v1/donations`:

1. **Donation page configuration** — a single settings row holding the bilingual page copy
   (Sorani / Kurmanji), the hero image, the institute's **publicly displayed bank details**, and two
   switches that decide whether each donation channel is currently accepting submissions.
2. **Financial donations** — a donor pledges money. The API records the pledge; it does **not**
   take a card payment, does not talk to a payment gateway, and never confirms settlement. The
   donor transfers money to the bank account shown on the page and reports the transfer here.
3. **Archive donations** — a donor offers physical or digital material (photographs, manuscripts,
   documents, audio, video) to the institute's archive. This is an *offer*, reviewed by staff later.

Everything on this page is callable by an anonymous visitor with no token. The admin-side
counterparts — reading what donors submitted, moving submissions through their status workflow, and
editing the donation page itself — live in [`../internal/DONATION_API.md`](../internal/DONATION_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/donations` |
| **Audience** | Public website (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java` |
| **Entities** | `DonationSettings`, `FinancialDonation`, `ArchiveDonation`, `DonationTypeCard` |
| **Tables** | `donation_settings`, `financial_donations`, `archive_donations`, `donation_type_cards` |
| **Repositories** | `DonationSettingsRepository`, `FinancialDonationRepository`, `ArchiveDonationRepository`, `DonationTypeCardRepository` |
| **Verified against source** | 2026-09-03 |

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/donations/settings` | None | — | Donation page copy, hero image and bank details |
| 2 | `GET` | `/api/v1/donations/types` | None | — | The two donation channels and whether each is open |
| 3 | `POST` | `/api/v1/donations/financial` | None | — | Submit a financial donation pledge |
| 4 | `POST` | `/api/v1/donations/archive` | None | — | Offer archive material to the institute |
| 5 | `GET` | `/api/v1/donations/type-cards` | None | — | The "What can I donate?" picture cards, CMS-managed |

Everything else under `/api/v1/donations` requires a token — see the internal document.

### Why these five are public

`SecurityConfig` names the two `POST` submission paths explicitly:

```java
// SecurityConfig.java — public visitor submissions
.requestMatchers(HttpMethod.POST,
        "/api/v1/contact/messages",
        "/api/v1/donations/financial",
        "/api/v1/donations/archive"
).permitAll()
```

`GET /api/v1/donations/settings` and `GET /api/v1/donations/types` are **not** named in any
admin-only matcher, so they fall through to the blanket public-read rule further down the ladder:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

The `GET` matchers that *are* admin-only cover `/api/v1/donations/financial` and
`/api/v1/donations/archive` — the submission *lists* — not `/settings`, `/types` or
`/type-cards`. `GET /type-cards` rides the same blanket rule, including with
`?includeInactive=true` — an inactive card is hidden from the website, not secret.

> **Note:** the practical consequence is that `bankName`, `accountName`, `accountNumber`, `iban`
> and `swiftCode` are **public data**. Anyone can read them without a token. This is intentional —
> the donation page has to print them — but they must never be treated as a shared secret or reused
> as verification material anywhere in the system.

---

## Response envelope

All five endpoints return the `ApiResponse<T>` envelope
(`ak.dev.khi_backend.khi_app.dto.ApiResponse`):

```json
{
  "success": true,
  "message": "Donation settings fetched",
  "data": {}
}
```

`ApiResponse` is annotated `@JsonInclude(NON_NULL)` and the application sets
`spring.jackson.default-property-inclusion: non_null`, so **null fields are dropped from the JSON
entirely** — both from the envelope and from the payload DTOs. A missing key means `null`; never
assume a key is present.

The two `POST` endpoints answer `201 Created` and still use the same envelope. Neither sets a
`Location` header — there is no public endpoint to read a submission back, so there would be
nothing to point at.

---

## 1. `GET /api/v1/donations/settings` — Donation page configuration

Returns the singleton donation settings row: bilingual page copy, hero image, the institute's bank
details, payment instructions, and the two channel switches. Read-only, no side effects, no cache.

**This endpoint never returns 404.** When the `donation_settings` table is empty (a fresh
database), the service synthesises a defaults-only response instead of failing, so the website can
render a usable donation page on day one.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters** — none.

**Query parameters** — none. In particular there is **no** `locale` parameter: the response always
carries both languages and the client picks. (`GET /api/v1/featured` is the only endpoint in this
controller that takes `locale`.)

**Response `200 OK`** — `data` is a `DonationSettingsResponse`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the settings row. Absent when no row has been saved yet |
| `titleCkb` | string | yes | Sorani page title. Max 500 chars in the database |
| `titleKmr` | string | yes | Kurmanji page title. Max 500 chars |
| `descriptionCkb` | string | yes | Sorani page copy. `TEXT`, unbounded |
| `descriptionKmr` | string | yes | Kurmanji page copy. `TEXT`, unbounded |
| `heroImageUrl` | string | yes | Absolute S3 URL of the donation page hero image |
| `bankName` | string | yes | Receiving bank. **Public** |
| `accountName` | string | yes | Account holder name. **Public** |
| `accountNumber` | string | yes | Account number. **Public** |
| `iban` | string | yes | IBAN. **Public** |
| `swiftCode` | string | yes | SWIFT / BIC. **Public** |
| `paymentInstructionsCkb` | string | yes | Sorani "how to transfer" text. `TEXT` |
| `paymentInstructionsKmr` | string | yes | Kurmanji "how to transfer" text. `TEXT` |
| `financialDonationsEnabled` | boolean | no | `false` closes `POST /api/v1/donations/financial` |
| `archiveDonationsEnabled` | boolean | no | `false` closes `POST /api/v1/donations/archive` |
| `featured` | boolean | no | Whether a donation slide is currently published on the homepage carousel |
| `featuredOrder` | integer (int32) | yes | Sort position of that slide. `null` (omitted) when not featured |
| `featureImageUrl` | string | yes | Wide picture used for the carousel slide. Falls back to `heroImageUrl` when blank |

`financialDonationsEnabled`, `archiveDonationsEnabled` and `featured` are backed by Java primitives,
so they are always present in the JSON. Every other field can be absent.

```json
{
  "success": true,
  "message": "Donation settings fetched",
  "data": {
    "id": 1,
    "titleCkb": "پشتیوانی لە ئەرشیفی کوردی بکە",
    "titleKmr": "Piştgiriya arşîva kurdî bike",
    "descriptionCkb": "بەخشینەکانت یارمەتی پاراستن، دیجیتاڵکردن و بڵاوکردنەوەی ئەرشیفی مێژوویی کوردی دەدەن — لە دەستنووسی کۆن تا تۆمارە دەنگییەکانی هەشتاکان.",
    "descriptionKmr": "Bexşînên te alîkariya parastin, dîjîtalkirin û belavkirina arşîva dîrokî ya kurdî dikin — ji destnivîsên kevn heta tomarên dengî yên salên heştêyî.",
    "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/hero-reading-room.jpg",
    "bankName": "Bank of Baghdad — Erbil Branch",
    "accountName": "Kurdish Heritage Institute",
    "accountNumber": "0011-234567-001",
    "iban": "IQ98BOBI850123456789012",
    "swiftCode": "BOBIIQBA",
    "paymentInstructionsCkb": "دوای ئەنجامدانی گواستنەوەکە، ژمارەی مامەڵەکە لە فۆرمی خوارەوەدا بنووسە تاکو بتوانین بەخشینەکەت تۆمار بکەین.",
    "paymentInstructionsKmr": "Piştî ku te veguhastin kir, hejmara danûstandinê di forma jêrîn de binivîse da ku em bexşîna te tomar bikin.",
    "financialDonationsEnabled": true,
    "archiveDonationsEnabled": true,
    "featured": true,
    "featuredOrder": 3,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/slide-wide.jpg"
  }
}
```

**Response on an empty database**

No row saved yet. Only the three primitives survive; everything else is `null` and therefore
omitted:

```json
{
  "success": true,
  "message": "Donation settings fetched",
  "data": {
    "financialDonationsEnabled": true,
    "archiveDonationsEnabled": true,
    "featured": false
  }
}
```

Render the page against your own fallback copy when `titleCkb` / `descriptionCkb` are absent, and
hide the bank-details block when `accountNumber` and `iban` are both absent.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Any verb other than `GET` on this path. `PUT` is admin-only by an explicit rule, and every other verb falls to `anyRequest().authenticated()`, so an anonymous caller is rejected by the security chain before the `405` can be produced. Body is not `ApiErrorResponse` |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

**Example**

```bash
curl -s http://localhost:8080/api/v1/donations/settings
```

---

## 2. `GET /api/v1/donations/types` — Available donation channels

Returns the two donation channels the institute supports, with bilingual labels and a live
`enabled` flag for each. Read-only.

The list is **hardcoded in `SiteContentService.getDonationTypes()`** — it is not a database table
and not an enum class. The codes and the Sorani / Kurmanji titles are literals in the service; only
`enabled` is dynamic, and it is read straight off the donation settings row (the same values as
`financialDonationsEnabled` / `archiveDonationsEnabled` on endpoint 1).

Use it to decide which of the two donation forms to render and which to grey out. If you already
call `GET /api/v1/donations/settings` you have the same information — this endpoint exists so a
simple channel-picker screen can render without pulling bank details it does not need.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters** — none. **Query parameters** — none.

**Response `200 OK`** — `data` is an array of `DonationTypeResponse`, always exactly two entries in
this fixed order: `FINANCIAL`, then `ARCHIVE`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `code` | string | no | `FINANCIAL` or `ARCHIVE`. Stable identifier — branch your UI on this, not on the titles |
| `titleCkb` | string | no | Sorani label, hardcoded |
| `titleKmr` | string | no | Kurmanji label, hardcoded |
| `enabled` | boolean | no | Whether the matching `POST` endpoint is currently accepting submissions |

```json
{
  "success": true,
  "message": "Donation types fetched",
  "data": [
    {
      "code": "FINANCIAL",
      "titleCkb": "بەخشینی دارایی",
      "titleKmr": "Bexşîna aborî",
      "enabled": true
    },
    {
      "code": "ARCHIVE",
      "titleCkb": "بەخشینی ئەرشیفی",
      "titleKmr": "Bexşîna arşîvê",
      "enabled": false
    }
  ]
}
```

> **Note:** there is no English title. The two labels are Sorani and Kurmanji only, matching the
> `Language` enum (`CKB` / `KMR`). An English-language build of the website has to supply its own
> strings keyed off `code`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Any verb other than `GET`. This path has no write handler at all, but `anyRequest().authenticated()` rejects the anonymous caller before the `405` can be produced. Body is not `ApiErrorResponse` |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

**Example**

```bash
curl -s http://localhost:8080/api/v1/donations/types
```

---

## 3. `POST /api/v1/donations/financial` — Submit a financial donation pledge

Records a donor's statement that they have transferred, or intend to transfer, money to the
institute. **No payment is processed.** Nothing is charged, no gateway is contacted, and the record
means "a person said this", not "money arrived".

Side effects:

- Inserts one row into `financial_donations` with `status = "PENDING"` (hardcoded — a `status` sent
  in the body is not a field on the request DTO and is never read).
- `created_at` is stamped by Hibernate `@CreationTimestamp` and is not updatable.
- Nothing else. **No email is sent**, to the donor or to staff. There is no notification, no
  webhook, and no receipt. The only way anyone learns about the pledge is an admin opening
  `GET /api/v1/donations/financial` in the dashboard.

**Gate:** the handler first calls `ensureFinancialDonationsEnabled()`. If a settings row exists and
`financialDonationsEnabled` is `false`, the submission is refused with **`400`**, not `403` or
`409`. If no settings row exists at all, the gate passes and the submission is accepted.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters** — none. **Query parameters** — none.

**Request body** — `FinancialDonationRequest`, validated with `@Valid`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `donorName` | string | **yes** | `@NotBlank`. No `@Size` — but the column is `varchar(200)` | Donor's name as they want it recorded. Trimmed before saving |
| `email` | string | no | `@Email` only — `null` and `""` both pass. Column is `varchar(254)` | Contact email. When omitted the service stores `""`, never `null` |
| `phone` | string | no | none. Column is `varchar(60)` | Contact phone, free-form. Trimmed; blank becomes `null` |
| `amount` | number | **yes** | `@NotNull`, `@DecimalMin("0.01")`. Column is `numeric(19,2)` | Pledged amount. Sent as a JSON number or a quoted decimal string |
| `currency` | string | **yes** | `@NotBlank`, then checked against `{IQD, USD}` in the service | Currency code. Trimmed and uppercased before comparison and storage |
| `paymentMethod` | string | **yes** | `@NotBlank`. **Not** validated against any list. Column is `varchar(80)` | Free text describing how the money was sent. Trimmed |
| `transactionReference` | string | no | none. Column is `varchar(200)` | Bank / transfer reference the donor quotes so staff can reconcile |
| `message` | string | no | `@Size(max = 5000)`. Column is `TEXT` | Free note from the donor. Trimmed; blank becomes `null` |

`currency` is the only enumerated field. `paymentMethod` is deliberately open text — the website's
dropdown values are a frontend convention, and the backend stores whatever arrives.

```json
{
  "donorName": "Rêbwar Ehmed",
  "email": "rebwar.ehmed@example.com",
  "phone": "+964 750 123 4567",
  "amount": 250000.00,
  "currency": "IQD",
  "paymentMethod": "Bank transfer",
  "transactionReference": "BOB-2026-08-26-99412",
  "message": "بۆ پرۆژەی دیجیتاڵکردنی دەستنووسە کۆنەکان."
}
```

A minimal valid body — only the four required fields:

```json
{
  "donorName": "شیلان مەحمود",
  "amount": 50,
  "currency": "USD",
  "paymentMethod": "Cash at the office"
}
```

**Response `201 Created`** — `data` is a `FinancialDonationResponse`, the persisted row echoed back.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key of the new row |
| `donorName` | string | no | As stored (trimmed) |
| `email` | string | no | As stored. `""` when the donor did not give one — the key is present but empty |
| `phone` | string | yes | Absent when omitted |
| `amount` | number | no | As stored, rounded to 2 decimal places by the column |
| `currency` | string | no | Uppercased: `IQD` or `USD` |
| `paymentMethod` | string | no | As stored (trimmed) |
| `transactionReference` | string | yes | Absent when omitted |
| `message` | string | yes | Absent when omitted |
| `status` | string | no | Always `PENDING` on creation |
| `createdAt` | string | no | ISO-8601 local date-time, e.g. `2026-08-26T09:14:22.481`. No zone offset — it is UTC |

```json
{
  "success": true,
  "message": "Financial donation received",
  "data": {
    "id": 412,
    "donorName": "Rêbwar Ehmed",
    "email": "rebwar.ehmed@example.com",
    "phone": "+964 750 123 4567",
    "amount": 250000.00,
    "currency": "IQD",
    "paymentMethod": "Bank transfer",
    "transactionReference": "BOB-2026-08-26-99412",
    "message": "بۆ پرۆژەی دیجیتاڵکردنی دەستنووسە کۆنەکان.",
    "status": "PENDING",
    "createdAt": "2026-08-26T09:14:22.481"
  }
}
```

> **Note:** the response echoes back everything the donor typed, including their email, phone and
> transaction reference. Do not log this response body in the browser, and do not persist it in
> `localStorage` or in analytics events.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `donorName`, `currency` or `paymentMethod` blank; `amount` missing or below `0.01`; `email` not an email; `message` over 5 000 chars. `fieldErrors` names each failing field |
| `400` | `BAD_REQUEST` | `currency` is not `IQD` or `USD` — `details.reason` reads `Unsupported currency: EUR (allowed: [IQD, USD])` |
| `400` | `BAD_REQUEST` | Financial donations are switched off — `details.reason` is `Financial donations are disabled` |
| `400` | `BAD_REQUEST` | Body missing, not JSON, or a field has the wrong JSON type (e.g. `"amount": "many"`) |
| `403` | — | Any verb other than `POST` on this path. `GET` is the admin inbox and every other verb falls to `anyRequest().authenticated()`, so the anonymous caller is rejected by the security chain, not with a `405`. Body is not `ApiErrorResponse` |
| `409` | `CONFLICT` | A value overflows its column — see the gotcha on unbounded fields below |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

Validation failure body (`amount` missing, `currency` blank):

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/donations/financial",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "Validation error.",
  "messageEn": "Validation error.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    {
      "field": "amount",
      "message": "must not be null",
      "messageEn": "must not be null",
      "messageKu": "must not be null"
    },
    {
      "field": "currency",
      "message": "must not be blank",
      "messageEn": "must not be blank",
      "messageKu": "must not be blank"
    }
  ]
}
```

Channel-closed body:

```json
{
  "timestamp": "2026-08-26T09:20:04Z",
  "status": 400,
  "path": "/api/v1/donations/financial",
  "method": "POST",
  "traceId": "8c2d5a91-77b4-4e13-9c02-1a6f3e7d5b48",
  "code": "BAD_REQUEST",
  "message": "Bad request.",
  "messageEn": "Bad request.",
  "messageKu": "داواکاری هەڵەیە.",
  "details": {
    "reason": "Financial donations are disabled"
  }
}
```

Check `financialDonationsEnabled` (endpoint 1) or the `FINANCIAL` entry's `enabled` (endpoint 2)
before showing the form, so a donor never fills it in only to be rejected.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/donations/financial \
  -H 'Content-Type: application/json' \
  -d '{
        "donorName": "Rêbwar Ehmed",
        "email": "rebwar.ehmed@example.com",
        "phone": "+964 750 123 4567",
        "amount": 250000.00,
        "currency": "IQD",
        "paymentMethod": "Bank transfer",
        "transactionReference": "BOB-2026-08-26-99412"
      }'
```

---

## 4. `POST /api/v1/donations/archive` — Offer archive material

Records an offer to donate physical or digital material to the institute's archive: a box of family
photographs, a manuscript, cassette recordings, official papers. This is a **proposal**, not a
transfer of custody. Staff read it later and contact the donor.

Side effects:

- Inserts one row into `archive_donations` with `status = "PENDING"` (hardcoded).
- `created_at` is stamped by `@CreationTimestamp`.
- Nothing else. **No email, no notification, no acknowledgement of any kind.** Tell the donor on the
  success screen that a staff member will be in touch, because the backend will not tell them.

**Gate:** `ensureArchiveDonationsEnabled()` runs first. With a settings row where
`archiveDonationsEnabled` is `false`, the submission is refused with **`400`**. With no settings row,
the submission is accepted.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters** — none. **Query parameters** — none.

**Request body** — `ArchiveDonationRequest`, validated with `@Valid`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `donorName` | string | **yes** | `@NotBlank`. Column is `varchar(200)` | Donor's name. Trimmed |
| `email` | string | no | `@Email` only — `null` and `""` pass. Column is `varchar(254)` | Contact email. Stored as `""` when omitted |
| `phone` | string | no | none. Column is `varchar(60)` | Contact phone. Trimmed; blank becomes `null` |
| `materialType` | string | **yes** | `@NotBlank`, then checked against the six-value set below | What kind of material is being offered. Trimmed and uppercased |
| `title` | string | no | `@Size(max = 500)`. Column is `varchar(500)` | The form labels this **"Register name"** — the display or credit name for the collection. Stored as `""` when omitted |
| `description` | string | no | `@Size(max = 10000)`. Column is `TEXT` | Free-text note or brief provenance history. Stored as `""` when omitted |
| `estimatedDate` | string | no | none. Column is `varchar(80)` | Free-text period, deliberately not a date type — `"1958–1972"`, `"late 1980s"`, `"ناديار"` all work |
| `attachmentUrl` | string | no | none, not URL-validated. Column is `TEXT` | Link to a sample scan or photo of the material |

> **Note:** the entity columns for `title` and `description` are `NOT NULL`, but the request fields
> are optional. The service bridges the gap by writing `""` (`orEmpty(...)`), so an omitted `title`
> or `description` comes back as an empty string, never as an absent key. Do not use "key present"
> to test whether the donor filled the field in — test for `""`.

> **Note:** there is **no public upload endpoint**. `POST /api/v1/media/upload` is restricted to
> `ADMIN` / `SUPER_ADMIN` by `SecurityConfig` (`/api/v1/media/**`), so an anonymous donor cannot put
> a file into S3 to populate `attachmentUrl`. The field is a plain free-text string and is not
> validated as a URL or checked for reachability. In practice either leave it out of the public form,
> or accept a link to the donor's own storage and treat it as untrusted user input when staff open it.

```json
{
  "donorName": "شیلان مەحمود",
  "email": "shilan.mahmud@example.com",
  "phone": "+964 751 987 6543",
  "materialType": "PHOTOGRAPH",
  "title": "ئەرشیفی وێنەی خێزانی مەحمود",
  "description": "نزیکەی ١٢٠ وێنەی ڕەش و سپی لە هەولێر و کۆیە، هی نێوان ١٩٥٨ و ١٩٧٢. زۆربەیان لەسەر کاغەزی ئەسڵین و لە دۆخێکی باشدان.",
  "estimatedDate": "1958–1972",
  "attachmentUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/attachments/mahmud-family-sample.jpg"
}
```

A minimal valid body — only the two required fields:

```json
{
  "donorName": "Dilan Şêrko",
  "materialType": "MANUSCRIPT"
}
```

**Response `201 Created`** — `data` is an `ArchiveDonationResponse`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key of the new row |
| `donorName` | string | no | As stored (trimmed) |
| `email` | string | no | As stored. `""` when omitted |
| `phone` | string | yes | Absent when omitted |
| `materialType` | string | no | Uppercased, one of the six codes |
| `title` | string | no | As stored. `""` when omitted |
| `description` | string | no | As stored. `""` when omitted |
| `estimatedDate` | string | yes | Absent when omitted |
| `attachmentUrl` | string | yes | Absent when omitted |
| `status` | string | no | Always `PENDING` on creation |
| `createdAt` | string | no | ISO-8601 local date-time. No zone offset — it is UTC |

```json
{
  "success": true,
  "message": "Archive donation offer received",
  "data": {
    "id": 87,
    "donorName": "شیلان مەحمود",
    "email": "shilan.mahmud@example.com",
    "phone": "+964 751 987 6543",
    "materialType": "PHOTOGRAPH",
    "title": "ئەرشیفی وێنەی خێزانی مەحمود",
    "description": "نزیکەی ١٢٠ وێنەی ڕەش و سپی لە هەولێر و کۆیە، هی نێوان ١٩٥٨ و ١٩٧٢. زۆربەیان لەسەر کاغەزی ئەسڵین و لە دۆخێکی باشدان.",
    "estimatedDate": "1958–1972",
    "attachmentUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/attachments/mahmud-family-sample.jpg",
    "status": "PENDING",
    "createdAt": "2026-08-26T11:02:41.903"
  }
}
```

Minimal submission echoed back — note `email`, `title` and `description` present as empty strings:

```json
{
  "success": true,
  "message": "Archive donation offer received",
  "data": {
    "id": 88,
    "donorName": "Dilan Şêrko",
    "email": "",
    "materialType": "MANUSCRIPT",
    "title": "",
    "description": "",
    "status": "PENDING",
    "createdAt": "2026-08-26T11:05:12.220"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `donorName` or `materialType` blank; `email` not an email; `title` over 500 chars; `description` over 10 000 chars |
| `400` | `BAD_REQUEST` | `materialType` is not one of the six codes — `details.reason` reads `Unsupported materialType: BOOK (allowed: [...])` |
| `400` | `BAD_REQUEST` | Archive donations are switched off — `details.reason` is `Archive donations are disabled` |
| `400` | `BAD_REQUEST` | Body missing, not JSON, or a field has the wrong JSON type |
| `403` | — | Any verb other than `POST` on this path. `GET` is the admin inbox and every other verb falls to `anyRequest().authenticated()`, so the anonymous caller is rejected by the security chain, not with a `405`. Body is not `ApiErrorResponse` |
| `409` | `CONFLICT` | A value overflows its column — see the gotcha below |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

Unsupported material type:

```json
{
  "timestamp": "2026-08-26T11:08:33Z",
  "status": 400,
  "path": "/api/v1/donations/archive",
  "method": "POST",
  "traceId": "b1f4d0c6-9a35-4f27-8e13-2d5c7a9b4e60",
  "code": "BAD_REQUEST",
  "message": "Bad request.",
  "messageEn": "Bad request.",
  "messageKu": "داواکاری هەڵەیە.",
  "details": {
    "reason": "Unsupported materialType: BOOK (allowed: [PHOTOGRAPH, MANUSCRIPT, DOCUMENT, AUDIO, VIDEO, OTHER])"
  }
}
```

The `allowed:` list is rendered from a Java `Set`, so **its order is not stable** between JVM runs.
Parse it as a set if you parse it at all; better, hardcode the six codes in your form.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/donations/archive \
  -H 'Content-Type: application/json' \
  -d '{
        "donorName": "Dilan Şêrko",
        "email": "dilan.sherko@example.com",
        "materialType": "MANUSCRIPT",
        "title": "Destnivîsên helbestên bavê min",
        "description": "Sê defterên destnivîsî, helbestên kurmancî, ji salên 1970î.",
        "estimatedDate": "circa 1970"
      }'
```

---

## 5. `GET /api/v1/donations/type-cards` — "What can I donate?" cards

Returns the picture cards of the donate page's "دەتوانم چی ببەخشم؟ / What can I donate?"
mosaic, sorted by `displayOrder` ascending. Unlike `/donations/types` (two hardcoded
channels), these are **database rows** (`donation_type_cards`) with full CRUD from the
dashboard — see the admin side in [`../internal/DONATION_API.md`](../internal/DONATION_API.md)
and the full guide in [`../DONATION_TYPE_CARDS.md`](../DONATION_TYPE_CARDS.md).

The website draws the **first card big** (with its description); the rest are small
tiles. Number chips ("01", "02"…) are derived from list position, not stored. An
empty array is a normal state — the website hides the whole section.

**Auth:** None (public)

**Query parameters**

| Name | Type | Default | Description |
|------|------|---------|-------------|
| `includeInactive` | boolean | `false` | `true` returns hidden (`active: false`) rows too — used by the dashboard |

**Response `200 OK`** — `data` is an array of `DonationTypeCardResponse`:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | number | no | Row id |
| `titleCkb` | string | yes | Sorani title (at least one of the two titles is always set) |
| `titleKmr` | string | yes | Kurmanji title |
| `descriptionCkb` | string | yes | Sorani description — drawn on the featured (first) card |
| `descriptionKmr` | string | yes | Kurmanji description |
| `imageUrl` | string | no | Background picture (S3 URL) |
| `displayOrder` | number | no | Sort key; lowest value = the big featured card |
| `active` | boolean | no | `false` = hidden from the website |

```json
{
  "success": true,
  "message": "Donation type cards fetched",
  "data": [
    {
      "id": 1,
      "titleCkb": "ئەرشیفی بینراو",
      "titleKmr": "Arşîva dîtbarî",
      "descriptionCkb": "وێنەی کۆنی کەسایەتییەکان، جلوبەرگ و شوێنەوارەکان.",
      "descriptionKmr": "Wêneyên kevn yên kesayetan, cil û cihên dîrokî.",
      "imageUrl": "https://s3-khiwebsite.s3.../images/donate-visual-archive.jpg",
      "displayOrder": 0,
      "active": true
    }
  ]
}
```

**Example**

```bash
curl -s http://localhost:8080/api/v1/donations/type-cards
```

---

## Enums used by this API

Neither of these is a Java `enum` — both are `Set<String>` constants in `SiteContentService`,
compared after `trim().toUpperCase(Locale.ROOT)`. Lowercase input is therefore accepted:
`"photograph"` is stored as `"PHOTOGRAPH"`, `"usd"` as `"USD"`.

### `materialType` — `ARCHIVE_MATERIAL_TYPES`

| Value | Meaning |
|-------|---------|
| `PHOTOGRAPH` | Prints, negatives, slides, family photo albums |
| `MANUSCRIPT` | Handwritten works — poetry notebooks, diaries, drafts |
| `DOCUMENT` | Printed or official paper — certificates, letters, pamphlets, newspapers |
| `AUDIO` | Sound recordings — cassettes, reel-to-reel, digital audio files |
| `VIDEO` | Moving images — tape, film, digital video files |
| `OTHER` | Anything that does not fit above. Put the detail in `description` |

### `currency` — `DONATION_CURRENCIES`

| Value | Meaning |
|-------|---------|
| `IQD` | Iraqi dinar |
| `USD` | United States dollar |

No other currency is accepted, and there is no conversion: `amount` is stored exactly as sent, in
the currency sent.

### `status` — set by the server, never by the donor

Both `POST` endpoints hardcode `PENDING`. The full seven-value workflow (`NEW`, `PENDING`,
`IN_REVIEW`, `APPROVED`, `COMPLETED`, `REJECTED`, `CLOSED`) is documented in
[`../internal/DONATION_API.md`](../internal/DONATION_API.md#the-status-workflow) — only staff can
move a submission through it, and there is no public endpoint to read a status back.

### `Language`

The `Language` enum (`CKB` Sorani, `KMR` Kurmanji) is **not referenced anywhere in this domain**.
Bilingual content is structural — paired `*Ckb` / `*Kmr` fields — and no donation field is validated
against the enum. `Language` still governs `Accept-Language` handling on error responses.

---

## Notes & gotchas

- **This is not a payment API.** No gateway, no card, no charge, no settlement callback. A row in
  `financial_donations` is a self-reported promise, and `status` moves by hand in the dashboard.
  Never present a `201` from endpoint 3 as "payment successful" — say "we have recorded your pledge".
- **Nothing is emailed.** Neither `POST` sends a confirmation to the donor or an alert to staff.
  Your success screen is the donor's only receipt; give them their `id` and `createdAt` so support
  has something to search on.
- **Submissions are write-only for the public.** There is no `GET /api/v1/donations/financial/{id}`,
  no lookup by email, no cancel and no edit. Once submitted, a donor has no way to see or withdraw
  their submission through the API.
- **Check the channel switch before rendering the form.** A disabled channel answers `400`, not a
  friendly `403`, and only after the donor has typed everything in. Read
  `financialDonationsEnabled` / `archiveDonationsEnabled` from endpoint 1, or `enabled` from
  endpoint 2, on page load.
- **The switch does nothing when the settings row is missing.** `ensureFinancialDonationsEnabled()`
  and `ensureArchiveDonationsEnabled()` inspect `findAll().stream().findFirst()`; on an empty
  `donation_settings` table there is nothing to inspect, so both channels are open. A fresh
  deployment accepts donations before anyone has configured the donation page.
- **Unbounded request fields can overflow their columns.** `donorName` (200), `phone` (60),
  `paymentMethod` (80), `transactionReference` (200) and `estimatedDate` (80) have column limits but
  **no `@Size` constraint**, so an over-long value passes bean validation and fails in Postgres
  (SQLSTATE `22001`) as a `DataIntegrityViolationException` → `409 CONFLICT` with a misleading
  "already exists" message. Enforce `maxlength` in the form: 200 / 60 / 80 / 200 / 80, plus 500 on
  `title` and the two documented `@Size` limits (5 000 on `message`, 10 000 on `description`).
- **`email` is optional but the column is `NOT NULL`.** Omitting it stores `""`. `@Email` in Jakarta
  Validation passes `null` and `""`, which is why an empty-string email from the website's form does
  not fail validation.
- **`amount` is `numeric(19,2)`.** More than two decimal places is silently rounded by Postgres.
  Round in the form so the donor sees the value that will be stored.
- **Timestamps.** `createdAt` is serialised as ISO-8601 local date-time with no zone offset
  (`2026-08-26T09:14:22.481`). The database runs `hibernate.jdbc.time_zone=UTC`, so treat it as UTC
  and convert to `Asia/Baghdad` for display.
- **No caching in this domain.** `SiteContentService` carries no `@Cacheable` or `@CacheEvict` on
  any donation method. Redis is configured for the application (key prefix `khi:`, 10-minute default
  TTL) but not used here, so an admin edit to the donation page is visible on the next public read
  immediately, with no eviction step.
- **No rate limiting and no CAPTCHA.** Both `POST` endpoints are open to anonymous callers with no
  throttle, no honeypot and no duplicate detection. Two identical submissions create two rows. Any
  abuse protection has to live in front of the API or in the client.
- **CORS.** Browser callers must come from an allowed origin (`app.cors.allowed-origins`), which
  covers the production website and dashboard on Railway plus `http://localhost:5173` and
  `http://localhost:3000` for local work. `allow-credentials` is `true`.
- **`X-Trace-Id`.** Returned on every response, and echoed back if you send one. It is the same
  value as `traceId` inside any `ApiErrorResponse` — show it on your error screen so support can
  find the log line.
- **Localized error text is unreliable.** The English bundle file on disk is named
  `" messages_en.properties"` with a leading space, so it never loads, and the Sorani/Kurmanji
  bundles contain literal `?` characters where Kurdish letters should be. Render your own copy from
  `code` (and `details.reason` where present) rather than displaying `message` / `messageEn` /
  `messageKu` to a donor.
- **Send only the documented fields.** The two request DTOs have no unknown-field tolerance in their
  contract; extra keys are not part of the API and there is no guarantee about how they are handled.

---

## Related documentation

- Counterpart: [`../internal/DONATION_API.md`](../internal/DONATION_API.md)
- Sibling public submission form: [`CONTACT_API.md`](CONTACT_API.md)
- Institutional pages that share the homepage carousel: [`ABOUT_API.md`](ABOUT_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
