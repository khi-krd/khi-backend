# Donation API — Internal (Authenticated)

The admin side of the Donation domain. Four groups of endpoints, all under `/api/v1/donations`:

1. **Submission inboxes** — read the financial pledges and archive-material offers that anonymous
   visitors sent through the public forms, newest first, paged.
2. **Status workflow** — move a single submission to another workflow state. Status is the *only*
   mutable field on a submission; nothing else about a donor's record can be edited or deleted
   through the API.
3. **Donation page configuration** — save the singleton `donation_settings` row (bilingual copy,
   hero image, the institute's bank details, the two channel switches) and toggle whether a donation
   slide appears on the homepage carousel.
4. **"What can I donate?" cards** — full CRUD on the `donation_type_cards` rows that draw the
   donate page's picture-card mosaic (see [`../DONATION_TYPE_CARDS.md`](../DONATION_TYPE_CARDS.md)).

The public counterparts — reading the donation page and submitting the two forms — are documented in
[`../external/DONATION_API.md`](../external/DONATION_API.md).

| | |
|---|---|
| **Base path** | `/api/v1/donations` |
| **Audience** | Admin dashboard (JWT required, ADMIN / SUPER_ADMIN) |
| **Controller** | `src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java` |
| **Entities** | `DonationSettings`, `FinancialDonation`, `ArchiveDonation`, `DonationTypeCard` (plus `SiteSettings`, read-only, for the featured cap) |
| **Tables** | `donation_settings`, `financial_donations`, `archive_donations`, `donation_type_cards` |
| **Repositories** | `DonationSettingsRepository`, `FinancialDonationRepository`, `ArchiveDonationRepository`, `DonationTypeCardRepository` |
| **Verified against source** | 2026-09-03 |

---

## Privacy

`financial_donations` and `archive_donations` hold **donor personal data**, submitted by members of
the public who were not asked to create an account and cannot see or withdraw their own record.

Personal data in these tables:

| Table | Personal / sensitive columns |
|-------|------------------------------|
| `financial_donations` | `donor_name`, `email`, `phone`, `message` (free text), `transaction_reference` (identifies a bank transfer), and `amount` + `donor_name` together as a financial fact about a named individual |
| `archive_donations` | `donor_name`, `email`, `phone`, `description` (free text, usually family or provenance history — often about third parties who never consented), `attachment_url` (frequently a scan of a personal document), and `title`, which is commonly a family name |

Consequences for anyone building against this API:

- **The two inbox endpoints are the only place this data is exposed, and they are `ADMIN` /
  `SUPER_ADMIN` only.** `EMPLOYEE` and `GUEST` are rejected. That restriction is what keeps the data
  private — do not proxy these endpoints behind a looser gate, and do not mirror the payloads into a
  less-protected store.
- **Do not cache donor payloads in the browser.** No `localStorage`, no `sessionStorage`, no
  analytics events, no error-reporting breadcrumbs carrying the response body.
- **Do not put donor fields in URLs.** Query strings end up in server logs, proxy logs and browser
  history. The API never requires it, so there is no reason to.
- **There is no delete, no export and no anonymisation endpoint.** A request from a donor to erase
  their data cannot be honoured through this API; it needs a direct database operation. Plan for
  that before promising anything on the public form.
- **There is no audit trail.** Nothing records which admin read the inbox or who changed a status.
  If you need accountability, it has to be added.
- `attachment_url` points at a file whose access control lives outside this table. Treat the link as
  untrusted user input — it is not validated, not scanned and not necessarily hosted by the
  institute.

---

## Authentication

Send the JWT either way:

```
Authorization: Bearer <token>
```

or as the HttpOnly cookie whose name comes from the `JWT_COOKIE_NAME` environment variable.
`JWTAuthenticationFilter` checks the `Authorization` header first, then falls back to the cookie.
Sessions are stateless (`SessionCreationPolicy.STATELESS`), but tokens can be revoked through
`/api/auth/logout` and `/api/auth/logout-all`, and a revoked token is rejected.

Granted authorities on a successful login are `ROLE_<NAME>` plus the permission strings
`user:create`, `user:read`, `user:update`, `user:delete`. Only the role is consulted in this domain.

| Role | Read inboxes | Change status | Save settings | Toggle featured |
|------|--------------|---------------|---------------|-----------------|
| `GUEST` | no | no | no | no |
| `EMPLOYEE` | no | no | no | no |
| `ADMIN` | yes | yes | yes | **yes** |
| `SUPER_ADMIN` | yes | yes | yes | **no — see the discrepancy below** |

---

## Authorization, exactly as SecurityConfig writes it

Six separate rules touch this domain, in this order:

```java
// 1. public submissions — documented in ../external/DONATION_API.md
.requestMatchers(HttpMethod.POST,
        "/api/v1/contact/messages",
        "/api/v1/donations/financial",
        "/api/v1/donations/archive"
).permitAll()

// 2. the two inboxes — exact literal paths, GET only
.requestMatchers(HttpMethod.GET,
        "/api/v1/contact/messages",
        "/api/v1/donations/financial",
        "/api/v1/donations/archive",
        "/api/v1/contact",
        "/api/v1/services/admin/**",
        "/api/v1/services/search/admin"
).hasAnyRole("ADMIN", "SUPER_ADMIN")

// 3. every PATCH under the two submission trees, plus the featured toggle
.requestMatchers(HttpMethod.PATCH,
        "/api/v1/contact/messages/**",
        "/api/v1/donations/financial/**",
        "/api/v1/donations/archive/**",
        "/api/v1/donations/settings/featured"
).hasAnyRole("ADMIN", "SUPER_ADMIN")

// 4. the settings save — exact literal path, PUT only.
//    /donations/type-cards/** also appears in the POST / PUT / DELETE
//    admin lists (alongside /settings/social/**), covering the card CRUD.
.requestMatchers(HttpMethod.PUT,
        "/api/v1/featured/**",
        ...
        "/api/v1/donations/settings",
        "/api/v1/donations/type-cards/**",
        "/api/v1/site-settings",
        "/api/v1/nav-menu/**"
).hasAnyRole("ADMIN", "SUPER_ADMIN")

// 5. catch-all public read, reached only by paths rule 2 did not name
.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
```

Two consequences worth internalising:

- Rule 2 uses the **exact literal** `/api/v1/donations/financial`, not `/**`. The bare collection
  path is admin-only; anything below it is not covered by rule 2 and falls to rule 5. There is no
  handler for `GET /api/v1/donations/financial/{id}`, so this leaks nothing today — it answers `404`
  — but adding a per-submission read endpoint under that prefix would publish donor records to the
  world. Any such endpoint must be added to rule 2 in the same commit.
- Rule 5 is why `GET /api/v1/donations/settings`, `GET /api/v1/donations/types` and
  `GET /api/v1/donations/type-cards` are public. Those
  live in [`../external/DONATION_API.md`](../external/DONATION_API.md).

### Discrepancy: `PATCH /donations/settings/featured` is ADMIN-only in practice

`SecurityConfig` rule 3 grants the featured toggle to `ADMIN` **and** `SUPER_ADMIN`. The handler
narrows it:

```java
@PatchMapping("/donations/settings/featured")
@PreAuthorize("hasRole('ADMIN')")          // ← not hasAnyRole('ADMIN','SUPER_ADMIN')
public ApiResponse<DonationSettingsResponse> setDonationFeatured(...)
```

`@PreAuthorize` runs after the filter chain and only ever narrows, so **a `SUPER_ADMIN` passes
`SecurityConfig` and is then rejected by method security with `403`**. Every other admin write in
this controller uses `hasAnyRole('ADMIN','SUPER_ADMIN')` (see `updateSiteSettings`), so this looks
like an oversight rather than a deliberate policy.

> **Note:** this is a real, reproducible mismatch between `SecurityConfig` and the handler's
> `@PreAuthorize`. It is documented here as observed behaviour and has **not** been changed. Until it
> is fixed, hide or disable the donation feature toggle in the dashboard for `SUPER_ADMIN` users, or
> have them use `PUT /api/v1/donations/settings` (which accepts the same three featured fields and
> carries no `@PreAuthorize`) as the workaround.

The same mismatch changes the *shape* of the rejection — see [Auth failure bodies](#auth-failure-bodies).

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/v1/donations/financial` | JWT | `ADMIN`, `SUPER_ADMIN` | Paged inbox of financial pledges, newest first |
| 2 | `GET` | `/api/v1/donations/archive` | JWT | `ADMIN`, `SUPER_ADMIN` | Paged inbox of archive-material offers, newest first |
| 3 | `PATCH` | `/api/v1/donations/financial/{id}/status` | JWT | `ADMIN`, `SUPER_ADMIN` | Move one pledge to another workflow state |
| 4 | `PATCH` | `/api/v1/donations/archive/{id}/status` | JWT | `ADMIN`, `SUPER_ADMIN` | Move one offer to another workflow state |
| 5 | `PUT` | `/api/v1/donations/settings` | JWT | `ADMIN`, `SUPER_ADMIN` | Save the whole donation page configuration |
| 6 | `PATCH` | `/api/v1/donations/settings/featured` | JWT | `ADMIN` **only** (see above) | Publish / unpublish the donation slide on the homepage carousel |
| 7 | `POST` | `/api/v1/donations/type-cards` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a "What can I donate?" card |
| 8 | `PUT` | `/api/v1/donations/type-cards/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace one card (whole row) |
| 9 | `DELETE` | `/api/v1/donations/type-cards/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Delete one card |

---

## Response envelope

Every endpoint here returns the `ApiResponse<T>` envelope:

```json
{
  "success": true,
  "message": "Financial donations fetched",
  "data": {}
}
```

`ApiResponse` is `@JsonInclude(NON_NULL)` and the application sets
`spring.jackson.default-property-inclusion: non_null`, so null fields are omitted from the JSON
entirely. Assume `undefined` means `null`.

All six handlers return the envelope directly (no `ResponseEntity` wrapper), so the HTTP status is
always `200 OK` on success — including for the two `PATCH` endpoints and the `PUT`.

---

## The status workflow

`SiteContentService.SUBMISSION_STATUSES` is a seven-value `Set<String>`, shared with the contact
message inbox:

| Value | Intended meaning for a donation |
|-------|-------------------------------|
| `NEW` | Untouched. Not used by donations — the two `POST` handlers hardcode `PENDING` — but accepted if you set it |
| `PENDING` | The value every submission is created with. Awaiting a first look |
| `IN_REVIEW` | A staff member is working on it: reconciling a bank transfer, or appraising offered material |
| `APPROVED` | Accepted in principle. The pledge is confirmed, or the institute wants the material |
| `COMPLETED` | Finished. Money reconciled and receipted, or material physically received and accessioned |
| `REJECTED` | Declined. Duplicate or unverifiable pledge, or material out of scope / unsuitable |
| `CLOSED` | Ended without a decision — donor unreachable, withdrawn, or abandoned |

> **Note:** **there is no state machine.** `validateStatus()` only checks membership in the set. Any
> status can move to any other, `COMPLETED` back to `PENDING` included, and re-setting the current
> status is a legal no-op write. If your dashboard needs a directed workflow, enforce it in the UI —
> the backend will not.

Input is `trim().toUpperCase(Locale.ROOT)`-normalised before the check, so `"approved"` and
`" Approved "` both store `APPROVED`. Anything outside the set is a `400 BAD_REQUEST`.

---

## 1. `GET /api/v1/donations/financial` — Financial donation inbox

Returns every financial pledge, paged, sorted `createdAt DESC`. Read-only, transactional
(`@Transactional(readOnly = true)`), no cache.

There is no filtering of any kind: no `status` parameter, no date range, no currency filter, no
free-text search, no sort override. The page window is the only control you have. Filter client-side
or add a repository query.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters** — none.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | integer (int32) | no | `0` | Zero-based page index |
| `size` | integer (int32) | no | `20` | Page size. **Not capped** — `size=100000` is accepted and will try to serialise the whole table |

Both are plain `int` parameters with no `@Min` / `@Max`. A negative `page` or a `size` of `0` makes
`PageRequest.of(...)` throw `IllegalArgumentException`, which the advice turns into a
`400 BAD_REQUEST` with the raw message in `details.reason`. A non-numeric value
(`?page=abc`) has no dedicated handler and falls through to `500 INTERNAL_ERROR` — guard both in the
dashboard.

**Response `200 OK`** — `data` is a Spring Data `Page<FinancialDonationResponse>`.

`FinancialDonationResponse` fields:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key. Quote this to the donor as a reference |
| `donorName` | string | no | **Personal data.** As the donor typed it, trimmed |
| `email` | string | no | **Personal data.** `""` when the donor gave none — the key is present but empty, never `null` |
| `phone` | string | yes | **Personal data.** Absent when not given |
| `amount` | number | no | Pledged amount, 2 decimal places |
| `currency` | string | no | `IQD` or `USD` |
| `paymentMethod` | string | no | Free text as typed by the donor. Not validated against any list |
| `transactionReference` | string | yes | **Financial identifier.** Bank / transfer reference for reconciliation |
| `message` | string | yes | **Free text from the donor.** Up to 5 000 chars |
| `status` | string | no | One of the seven workflow values |
| `createdAt` | string | no | ISO-8601 local date-time, e.g. `2026-08-26T09:14:22.481`. No offset — it is UTC |

```json
{
  "success": true,
  "message": "Financial donations fetched",
  "data": {
    "content": [
      {
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
      },
      {
        "id": 411,
        "donorName": "Şilan Mehmûd",
        "email": "",
        "amount": 500.00,
        "currency": "USD",
        "paymentMethod": "Cash at the Erbil office",
        "status": "COMPLETED",
        "createdAt": "2026-08-25T15:48:03.117"
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
    "totalElements": 412,
    "totalPages": 21,
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

Donation 411 shows the shape of an anonymous-ish submission: `email` present but `""`, and `phone`,
`transactionReference` and `message` omitted entirely because they are `null`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | No token, or a token whose role is not `ADMIN` / `SUPER_ADMIN`. Body is **not** `ApiErrorResponse` — see [Auth failure bodies](#auth-failure-bodies) |
| `401` | — | Token expired or revoked. Body is the JWT filter's own shape |
| `400` | `BAD_REQUEST` | `page` negative or `size` ≤ 0 — `details.reason` carries the `PageRequest` message |
| `500` | `INTERNAL_ERROR` | `page` / `size` not parseable as an integer, or an unexpected fault |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/donations/financial?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 2. `GET /api/v1/donations/archive` — Archive donation inbox

Returns every archive-material offer, paged, sorted `createdAt DESC`. Same shape, same constraints
and same lack of filtering as endpoint 1.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters** — none.

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | integer (int32) | no | `0` | Zero-based page index |
| `size` | integer (int32) | no | `20` | Page size. Not capped |

**Response `200 OK`** — `data` is a `Page<ArchiveDonationResponse>`.

`ArchiveDonationResponse` fields:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | integer (int64) | no | Primary key |
| `donorName` | string | no | **Personal data.** Trimmed |
| `email` | string | no | **Personal data.** `""` when not given |
| `phone` | string | yes | **Personal data.** Absent when not given |
| `materialType` | string | no | One of `PHOTOGRAPH`, `MANUSCRIPT`, `DOCUMENT`, `AUDIO`, `VIDEO`, `OTHER` |
| `title` | string | no | The public form calls this "Register name" — the display / credit name. `""` when not given, never `null` |
| `description` | string | no | **Free text from the donor**, up to 10 000 chars, typically a provenance history. `""` when not given |
| `estimatedDate` | string | yes | Free-text period, deliberately not a date. Absent when not given |
| `attachmentUrl` | string | yes | Link to a sample scan or photo. **Not validated, not scanned, not necessarily institute-hosted** |
| `status` | string | no | One of the seven workflow values |
| `createdAt` | string | no | ISO-8601 local date-time. No offset — it is UTC |

```json
{
  "success": true,
  "message": "Archive donations fetched",
  "data": {
    "content": [
      {
        "id": 87,
        "donorName": "شیلان مەحمود",
        "email": "shilan.mahmud@example.com",
        "phone": "+964 751 987 6543",
        "materialType": "PHOTOGRAPH",
        "title": "ئەرشیفی وێنەی خێزانی مەحمود",
        "description": "نزیکەی ١٢٠ وێنەی ڕەش و سپی لە هەولێر و کۆیە، هی نێوان ١٩٥٨ و ١٩٧٢.",
        "estimatedDate": "1958–1972",
        "attachmentUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/attachments/mahmud-family-sample.jpg",
        "status": "IN_REVIEW",
        "createdAt": "2026-08-26T11:02:41.903"
      },
      {
        "id": 86,
        "donorName": "Dilan Şêrko",
        "email": "",
        "materialType": "MANUSCRIPT",
        "title": "",
        "description": "",
        "status": "PENDING",
        "createdAt": "2026-08-24T08:19:57.640"
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
    "totalElements": 87,
    "totalPages": 5,
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

Offer 86 is the minimum a donor can submit: name and material type only. `title` and `description`
come back as empty strings because their columns are `NOT NULL` and the service writes `""` — do not
test for key presence to decide whether the donor filled them in.

**Errors** — identical to endpoint 1.

| Status | `code` | When |
|--------|--------|------|
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `400` | `BAD_REQUEST` | `page` negative or `size` ≤ 0 |
| `500` | `INTERNAL_ERROR` | Non-numeric `page` / `size`, or an unexpected fault |

**Example**

```bash
curl -s "http://localhost:8080/api/v1/donations/archive?page=0&size=50" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 3. `PATCH /api/v1/donations/financial/{id}/status` — Change a pledge's status

Sets `status` on one financial donation and returns the whole updated record. This is the **only**
mutation available on a submission — no other field can be edited, and there is no delete endpoint.

Side effects: one `UPDATE` on `financial_donations`. `created_at` is `updatable = false` so it never
moves. Nothing is emailed, nothing is logged beyond the framework's own logging, and no audit row is
written.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the financial donation |

**Query parameters** — none.

**Request body** — `StatusRequest`, validated with `@Valid`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `status` | string | **yes** | `@NotBlank`, then membership in `SUBMISSION_STATUSES` | Target workflow state. Trimmed and uppercased before the check and before storage |

```json
{ "status": "COMPLETED" }
```

**Response `200 OK`** — `data` is the full `FinancialDonationResponse` (same fields as endpoint 1).

```json
{
  "success": true,
  "message": "Financial donation status updated",
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
    "status": "COMPLETED",
    "createdAt": "2026-08-26T09:14:22.481"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `400` | `VALIDATION_ERROR` | `status` missing or blank. `fieldErrors[0].field` is `status` |
| `400` | `BAD_REQUEST` | `status` outside the seven-value set — `details.reason` is `Unsupported status: DONE` |
| `400` | `BAD_REQUEST` | Body missing or not valid JSON |
| `404` | `NOT_FOUND` | No financial donation with that id. `message` is `Financial donation not found: 999` and `details.resource` repeats it |
| `500` | `INTERNAL_ERROR` | `{id}` is not a number (`/api/v1/donations/financial/abc/status`) — there is no `MethodArgumentTypeMismatchException` handler |

Not-found body:

```json
{
  "timestamp": "2026-08-26T12:31:07Z",
  "status": 404,
  "path": "/api/v1/donations/financial/999/status",
  "method": "PATCH",
  "traceId": "5a7c9e11-3b42-4d88-9f60-c2e4a1b70d93",
  "code": "NOT_FOUND",
  "message": "Financial donation not found: 999",
  "messageEn": "Financial donation not found: 999",
  "messageKu": "Financial donation not found: 999",
  "details": {
    "resource": "Financial donation not found: 999"
  }
}
```

> **Note:** `EntityNotFoundException` is the only handler in `GlobalExceptionHandler` that does not
> localise — it copies the raw English message into `message`, `messageEn` **and** `messageKu`. Do
> not display `messageKu` from a 404 to a Kurdish-language user.

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/donations/financial/412/status \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"COMPLETED"}'
```

---

## 4. `PATCH /api/v1/donations/archive/{id}/status` — Change an offer's status

Sets `status` on one archive donation and returns the whole updated record. Identical mechanics to
endpoint 3, against a different table.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | integer (int64) | yes | Primary key of the archive donation |

**Query parameters** — none.

**Request body** — `StatusRequest`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `status` | string | **yes** | `@NotBlank`, then membership in `SUBMISSION_STATUSES` | Target workflow state. Trimmed and uppercased |

```json
{ "status": "APPROVED" }
```

**Response `200 OK`** — `data` is the full `ArchiveDonationResponse` (same fields as endpoint 2).

```json
{
  "success": true,
  "message": "Archive donation status updated",
  "data": {
    "id": 87,
    "donorName": "شیلان مەحمود",
    "email": "shilan.mahmud@example.com",
    "phone": "+964 751 987 6543",
    "materialType": "PHOTOGRAPH",
    "title": "ئەرشیفی وێنەی خێزانی مەحمود",
    "description": "نزیکەی ١٢٠ وێنەی ڕەش و سپی لە هەولێر و کۆیە، هی نێوان ١٩٥٨ و ١٩٧٢.",
    "estimatedDate": "1958–1972",
    "attachmentUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/attachments/mahmud-family-sample.jpg",
    "status": "APPROVED",
    "createdAt": "2026-08-26T11:02:41.903"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies) |
| `400` | `VALIDATION_ERROR` | `status` missing or blank |
| `400` | `BAD_REQUEST` | `status` outside the seven-value set |
| `404` | `NOT_FOUND` | No archive donation with that id — `Archive donation not found: 999` |
| `500` | `INTERNAL_ERROR` | Non-numeric `{id}`, or an unexpected fault |

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/donations/archive/87/status \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"APPROVED"}'
```

---

## 5. `PUT /api/v1/donations/settings` — Save the donation page

Upserts the singleton `donation_settings` row: bilingual copy, hero image, bank details, payment
instructions, the two channel switches, and optionally the three homepage-carousel fields.

**There is no id in the path.** The service does
`donationSettingsRepository.findAll().stream().findFirst().orElseGet(DonationSettings::new)` — it
edits whichever row it finds, or creates the first one. Sending this on an empty table creates the
row; sending it again edits that row.

Side effects: one `INSERT` or `UPDATE` on `donation_settings`. Turning `featured` on or off changes
what `GET /api/v1/featured` returns on the public homepage immediately — there is no cache to evict.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
(from `SecurityConfig` only — this handler carries no `@PreAuthorize`)
**Content-Type:** `application/json`

**Path parameters** — none. **Query parameters** — none.

**Request body** — `DonationSettingsRequest`. The handler declares `@Valid`, but **the DTO has no
validation constraints at all**, so nothing here can fail bean validation. Every field is optional.

The fields split into three groups with three different omission behaviours. This is the single most
important thing to get right on this endpoint.

**Group A — plain content fields. Omitting one CLEARS it.**

Each is written unconditionally with `trimToNull(...)`: a missing key deserialises to `null`, and
`null` is stored. `""` and whitespace-only also store `null`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `titleCkb` | string | no | none (column `varchar(500)`) | Sorani page title. Also the carousel slide title |
| `titleKmr` | string | no | none (column `varchar(500)`) | Kurmanji page title |
| `descriptionCkb` | string | no | none (`TEXT`) | Sorani page copy. Also the slide description |
| `descriptionKmr` | string | no | none (`TEXT`) | Kurmanji page copy |
| `heroImageUrl` | string | no | none (`TEXT`), not URL-validated | Donation page hero image. Also the slide's fallback picture |
| `bankName` | string | no | none (`varchar(300)`) | **Publicly readable.** Receiving bank |
| `accountName` | string | no | none (`varchar(300)`) | **Publicly readable.** Account holder |
| `accountNumber` | string | no | none (`varchar(120)`) | **Publicly readable.** Account number |
| `iban` | string | no | none (`varchar(120)`), no format check | **Publicly readable.** IBAN |
| `swiftCode` | string | no | none (`varchar(60)`), no format check | **Publicly readable.** SWIFT / BIC |
| `paymentInstructionsCkb` | string | no | none (`TEXT`) | Sorani "how to transfer" text |
| `paymentInstructionsKmr` | string | no | none (`TEXT`) | Kurmanji "how to transfer" text |

**Group B — the two channel switches. Omitting one turns it ON.**

```java
settings.setFinancialDonationsEnabled(
        request.getFinancialDonationsEnabled() == null || request.getFinancialDonationsEnabled());
```

`null || value` means an omitted key resolves to `true`.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `financialDonationsEnabled` | boolean | no | none. **Omitted → `true`** | Gates the public `POST /api/v1/donations/financial` |
| `archiveDonationsEnabled` | boolean | no | none. **Omitted → `true`** | Gates the public `POST /api/v1/donations/archive` |

> **Note:** a dashboard that closes a channel and later saves the page from a form that does not
> include these two checkboxes will silently **re-open** it. Always send both booleans explicitly on
> every `PUT`.

**Group C — the carousel fields. Omitting one LEAVES IT ALONE.**

Each is guarded by an explicit `!= null` check, deliberately, so a client that knows nothing about
featuring cannot unfeature the page by accident.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | boolean | no | Omitted → unchanged | `true` publishes a donation slide on the homepage carousel; `false` removes it and nulls `featuredOrder` |
| `featuredOrder` | integer (int32) | no | Omitted → unchanged. **Only applied when the row ends up featured** | Sort position among carousel slides; lower shows first, `null` sorts last |
| `featureImageUrl` | string | no | Omitted → unchanged; `""` → cleared | Wide picture for the slide. Falls back to `heroImageUrl` when blank |

Two rules fire only when `featured` is being turned **on** (`false` → `true`):

1. **Global slide cap.** `countAllFeatured() >= getMaxFeaturedSlides()` is refused. The count sums
   featured `news`, `projects`, `writings`, `videos`, `sound_tracks`, `image_collections` and the
   donation page itself; featured Services and About pages are deliberately excluded because their
   flag is a page-level highlight, not a carousel slide. The cap is
   `site_settings.max_featured_slides`, default **7**.
2. **A picture is required.** `featureImageUrl` or `heroImageUrl` must be non-blank after this
   request's changes are applied, otherwise the save is refused.

Both refusals are `400 BAD_REQUEST` and **abort the entire save** — the `@Transactional` method
throws before `save()`, so none of Group A or Group B is written either.

Order of evaluation inside the method matters: `featureImageUrl` is applied *before* the picture
check, so one request can set the image and turn featuring on together.

**Full request example**

```json
{
  "titleCkb": "پشتیوانی لە ئەرشیفی کوردی بکە",
  "titleKmr": "Piştgiriya arşîva kurdî bike",
  "descriptionCkb": "بەخشینەکانت یارمەتی پاراستن، دیجیتاڵکردن و بڵاوکردنەوەی ئەرشیفی مێژوویی کوردی دەدەن.",
  "descriptionKmr": "Bexşînên te alîkariya parastin, dîjîtalkirin û belavkirina arşîva dîrokî ya kurdî dikin.",
  "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/hero-reading-room.jpg",
  "bankName": "Bank of Baghdad — Erbil Branch",
  "accountName": "Kurdish Heritage Institute",
  "accountNumber": "0011-234567-001",
  "iban": "IQ98BOBI850123456789012",
  "swiftCode": "BOBIIQBA",
  "paymentInstructionsCkb": "دوای ئەنجامدانی گواستنەوەکە، ژمارەی مامەڵەکە لە فۆرمەکەدا بنووسە.",
  "paymentInstructionsKmr": "Piştî ku te veguhastin kir, hejmara danûstandinê di formê de binivîse.",
  "financialDonationsEnabled": true,
  "archiveDonationsEnabled": false,
  "featured": true,
  "featuredOrder": 3,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/slide-wide.jpg"
}
```

**Response `200 OK`** — `data` is a `DonationSettingsResponse`, the full saved row. Field-by-field
documentation is in
[`../external/DONATION_API.md`](../external/DONATION_API.md#1-get-apiv1donationssettings--donation-page-configuration);
the internal response is byte-for-byte the same DTO the public read returns.

```json
{
  "success": true,
  "message": "Donation settings saved",
  "data": {
    "id": 1,
    "titleCkb": "پشتیوانی لە ئەرشیفی کوردی بکە",
    "titleKmr": "Piştgiriya arşîva kurdî bike",
    "descriptionCkb": "بەخشینەکانت یارمەتی پاراستن، دیجیتاڵکردن و بڵاوکردنەوەی ئەرشیفی مێژوویی کوردی دەدەن.",
    "descriptionKmr": "Bexşînên te alîkariya parastin, dîjîtalkirin û belavkirina arşîva dîrokî ya kurdî dikin.",
    "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/hero-reading-room.jpg",
    "bankName": "Bank of Baghdad — Erbil Branch",
    "accountName": "Kurdish Heritage Institute",
    "accountNumber": "0011-234567-001",
    "iban": "IQ98BOBI850123456789012",
    "swiftCode": "BOBIIQBA",
    "paymentInstructionsCkb": "دوای ئەنجامدانی گواستنەوەکە، ژمارەی مامەڵەکە لە فۆرمەکەدا بنووسە.",
    "paymentInstructionsKmr": "Piştî ku te veguhastin kir, hejmara danûstandinê di formê de binivîse.",
    "financialDonationsEnabled": true,
    "archiveDonationsEnabled": false,
    "featured": true,
    "featuredOrder": 3,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/slide-wide.jpg"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` / `401` | — | See [Auth failure bodies](#auth-failure-bodies). `SUPER_ADMIN` **is** allowed here |
| `400` | `BAD_REQUEST` | Turning `featured` on with the global cap already reached — `details.reason` is `Maximum of 7 featured slides allowed across all content. Unfeature one first.` |
| `400` | `BAD_REQUEST` | Turning `featured` on with both `featureImageUrl` and `heroImageUrl` blank — `details.reason` is `featureImageUrl or heroImageUrl is required to feature the donation page.` |
| `400` | `BAD_REQUEST` | Body missing, not JSON, or a field has the wrong JSON type |
| `409` | `CONFLICT` | A value overflows its column (SQLSTATE `22001`) — none of these fields has a `@Size` constraint |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

Cap-exceeded body:

```json
{
  "timestamp": "2026-08-26T13:02:44Z",
  "status": 400,
  "path": "/api/v1/donations/settings",
  "method": "PUT",
  "traceId": "c4e8b2d7-1f65-4a90-83c1-7b0d9e2a5f34",
  "code": "BAD_REQUEST",
  "message": "Bad request.",
  "messageEn": "Bad request.",
  "messageKu": "داواکاری هەڵەیە.",
  "details": {
    "reason": "Maximum of 7 featured slides allowed across all content. Unfeature one first."
  }
}
```

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/donations/settings \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "titleCkb": "پشتیوانی لە ئەرشیفی کوردی بکە",
        "titleKmr": "Piştgiriya arşîva kurdî bike",
        "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/hero-reading-room.jpg",
        "bankName": "Bank of Baghdad — Erbil Branch",
        "accountName": "Kurdish Heritage Institute",
        "accountNumber": "0011-234567-001",
        "iban": "IQ98BOBI850123456789012",
        "swiftCode": "BOBIIQBA",
        "financialDonationsEnabled": true,
        "archiveDonationsEnabled": false
      }'
```

---

## 6. `PATCH /api/v1/donations/settings/featured` — Toggle the homepage donation slide

Publishes or removes the donation slide on the homepage carousel, without touching the page copy or
the bank details. This is the donation counterpart of `PATCH /{resource}/{id}/featured` on the six
publication types; there is no id in the path because donation settings are a singleton row.

Side effects: one `UPDATE` on `donation_settings` touching `featured`, `featured_order` and
optionally `feature_image_url`. The public `GET /api/v1/featured` reflects it on the next request.

**Auth:** `Authorization: Bearer <token>`, role **`ADMIN` only**

```java
@PatchMapping("/donations/settings/featured")
@PreAuthorize("hasRole('ADMIN')")
```

`SecurityConfig` would allow `SUPER_ADMIN` here; `@PreAuthorize` does not. See
[the discrepancy note](#discrepancy-patch-donationssettingsfeatured-is-admin-only-in-practice).

**Content-Type:** `application/json`

**Path parameters** — none. **Query parameters** — none.

**Request body** — `FeaturedRequest`, **without `@Valid`**.

The handler signature is `@RequestBody FeaturedRequest request` — no `@Valid`. `FeaturedRequest` is a
shared DTO that declares `@NotBlank` on `type`, `slug`, `title`, `description` and `imageUrl`, but
because the parameter is unvalidated **none of those constraints is enforced here and none of those
fields is read**. Send only the three fields below.

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `featured` | boolean | no | not validated. **`null` / omitted is treated as `true`** | `true` publishes the slide, `false` removes it |
| `featuredOrder` | integer (int32) | no | not validated | Sort position; lower shows first, `null` sorts last. Applied only when featuring |
| `featureImageUrl` | string | no | not validated | Wide slide picture. `null` / omitted leaves the stored value alone; `""` clears it and falls back to `heroImageUrl` |

> **Note:** `featured` defaults to `true` when omitted, so an empty body `{}` **features** the page.
> Always send `featured` explicitly.

`featuredOrder` behaves differently here than on endpoint 5. This handler writes it
**unconditionally**:

```java
settings.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
```

So `{"featured": true}` with no `featuredOrder` **resets the stored order to `null`**, whereas the
same omission on `PUT /donations/settings` leaves it untouched. If you are re-featuring and want to
keep a position, send `featuredOrder` every time.

Preconditions, all of them `400 BAD_REQUEST`:

1. **A settings row must already exist.** Unlike endpoint 5 this handler does not create one —
   `orElseThrow` fires with `Donation settings have not been saved yet — save the donation page before featuring it.`
2. **Global slide cap**, checked only when turning on and only when the page is not already featured
   (`countAllFeatured() >= getMaxFeaturedSlides()`, default 7).
3. **A picture is required** when turning on: `featureImageUrl` or `heroImageUrl` must be non-blank
   after this request's `featureImageUrl` is applied.

**Request examples**

Feature at position 3:

```json
{ "featured": true, "featuredOrder": 3 }
```

Feature with a new wide picture in the same call:

```json
{
  "featured": true,
  "featuredOrder": 1,
  "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/slide-wide-autumn.jpg"
}
```

Unfeature (this also nulls `featuredOrder`):

```json
{ "featured": false }
```

Clear the wide picture and fall back to `heroImageUrl`, staying featured:

```json
{ "featured": true, "featuredOrder": 3, "featureImageUrl": "" }
```

**Response `200 OK`** — `data` is the full `DonationSettingsResponse`, so the dashboard can re-render
the whole donation screen from one call.

```json
{
  "success": true,
  "message": "Donation featured state updated",
  "data": {
    "id": 1,
    "titleCkb": "پشتیوانی لە ئەرشیفی کوردی بکە",
    "titleKmr": "Piştgiriya arşîva kurdî bike",
    "descriptionCkb": "بەخشینەکانت یارمەتی پاراستن، دیجیتاڵکردن و بڵاوکردنەوەی ئەرشیفی مێژوویی کوردی دەدەن.",
    "descriptionKmr": "Bexşînên te alîkariya parastin, dîjîtalkirin û belavkirina arşîva dîrokî ya kurdî dikin.",
    "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/hero-reading-room.jpg",
    "bankName": "Bank of Baghdad — Erbil Branch",
    "accountName": "Kurdish Heritage Institute",
    "accountNumber": "0011-234567-001",
    "iban": "IQ98BOBI850123456789012",
    "swiftCode": "BOBIIQBA",
    "financialDonationsEnabled": true,
    "archiveDonationsEnabled": true,
    "featured": true,
    "featuredOrder": 3,
    "featureImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/donations/slide-wide.jpg"
  }
}
```

After unfeaturing, `featuredOrder` is `null` and therefore absent from the JSON.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | `FORBIDDEN` | Valid token but role is not `ADMIN` — **including `SUPER_ADMIN`**. Because this handler has `@PreAuthorize`, the rejection reaches `GlobalExceptionHandler` and the body **is** a proper `ApiErrorResponse` |
| `403` / `401` | — | No token at all, or an expired / revoked token — filter-chain rejection, not `ApiErrorResponse`. See [Auth failure bodies](#auth-failure-bodies) |
| `400` | `BAD_REQUEST` | No `donation_settings` row exists yet |
| `400` | `BAD_REQUEST` | Global slide cap reached |
| `400` | `BAD_REQUEST` | Turning on with no `featureImageUrl` and no `heroImageUrl` |
| `400` | `BAD_REQUEST` | Body missing or not valid JSON |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

`SUPER_ADMIN` rejection body — note this one *does* carry `code`:

```json
{
  "timestamp": "2026-08-26T13:41:19Z",
  "status": 403,
  "path": "/api/v1/donations/settings/featured",
  "method": "PATCH",
  "traceId": "9d3a6f02-84be-4c17-b5e9-0c1f7a28d456",
  "code": "FORBIDDEN",
  "message": "You do not have permission to access this resource. Contact an administrator if you believe this is a mistake.",
  "messageEn": "You do not have permission to access this resource. Contact an administrator if you believe this is a mistake.",
  "messageKu": "ڕێگەپێنەدراو — ئەمەی خواستت تێپەڕاندنی ئاستی دەستوور دەخوازێت.",
  "details": {
    "path": "/api/v1/donations/settings/featured",
    "method": "PATCH",
    "hint": "Your current role does not allow this action. Contact an administrator if you believe you should have access."
  }
}
```

No-settings-row body:

```json
{
  "timestamp": "2026-08-26T13:45:02Z",
  "status": 400,
  "path": "/api/v1/donations/settings/featured",
  "method": "PATCH",
  "traceId": "2b8e1c47-6d90-4f3a-a712-5e9c0b4d8317",
  "code": "BAD_REQUEST",
  "message": "Bad request.",
  "messageEn": "Bad request.",
  "messageKu": "داواکاری هەڵەیە.",
  "details": {
    "reason": "Donation settings have not been saved yet — save the donation page before featuring it."
  }
}
```

**Example**

```bash
curl -s -X PATCH http://localhost:8080/api/v1/donations/settings/featured \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"featured":true,"featuredOrder":3}'
```

---

## 7–9. `/api/v1/donations/type-cards` — "What can I donate?" card CRUD

The picture cards of the donate page's "دەتوانم چی ببەخشم؟ / What can I donate?" mosaic
are rows in `donation_type_cards` (entity `DonationTypeCard`, repository
`DonationTypeCardRepository`). The dashboard has full CRUD; there is no fixed card
count and no unique column — create as many as needed. The public read (including
`?includeInactive=true`, which is deliberately public) is documented in
[`../external/DONATION_API.md`](../external/DONATION_API.md); the full guide with
seed data for the five cards the site shows today is
[`../DONATION_TYPE_CARDS.md`](../DONATION_TYPE_CARDS.md).

**Auth:** JWT, `ADMIN` or `SUPER_ADMIN` for all three writes.

### Request body (`POST` and `PUT` — same `DonationTypeCardRequest`)

| Field | Type | Required | Rules |
|-------|------|----------|-------|
| `titleCkb` | string | one of the two titles | max 200; trimmed, blank → `null` |
| `titleKmr` | string | one of the two titles | max 200; trimmed, blank → `null` |
| `descriptionCkb` | string | no | max 1000; trimmed, blank → `null` |
| `descriptionKmr` | string | no | max 1000; trimmed, blank → `null` |
| `imageUrl` | string | **yes** | max 2000; S3 URL from the normal image-upload flow |
| `displayOrder` | number | no | `null` → 0; lowest value = the big featured card |
| `active` | boolean | no | `null` → `true`; `false` hides the card from the website |

The either-or title rule cannot be expressed in bean validation, so it is enforced in
`SiteContentService.applyDonationTypeCard()` and surfaces as a plain `400` with the
message `"At least one of titleCkb / titleKmr is required."`.

**`PUT` replaces the whole row** — a client that sends only the changed field blanks
the others, exactly like the social-links `PUT`.

### Responses

- `POST` → `201 Created`, `data` = the saved card, message `"Donation type card created"`.
- `PUT` → `200 OK`, `data` = the saved card, message `"Donation type card updated"`.
- `DELETE` → `200 OK`, `data: null`, message `"Donation type card deleted"`.

### Errors

| Status | When |
|--------|------|
| `400` | Both titles blank; missing / over-long `imageUrl`; over-long titles or descriptions |
| `404` | `PUT` / `DELETE` on an unknown id — `"Donation type card not found: {id}"` |
| `401` / `403` | No token / non-admin token |

---

## Enums used by this API

None of these is a Java `enum`. All three are `Set<String>` / literal constants in
`SiteContentService`, compared after `trim().toUpperCase(Locale.ROOT)`.

### `status` — `SUBMISSION_STATUSES`

Accepted by endpoints 3 and 4, returned on every submission. See
[The status workflow](#the-status-workflow) for what each value means in practice.

| Value | Meaning |
|-------|---------|
| `NEW` | Accepted by the validator; never set by the donation `POST` handlers |
| `PENDING` | Creation state for every submission |
| `IN_REVIEW` | A staff member is actively working on it |
| `APPROVED` | Accepted in principle |
| `COMPLETED` | Finished — money reconciled, or material accessioned |
| `REJECTED` | Declined |
| `CLOSED` | Ended without a decision |

### `materialType` — `ARCHIVE_MATERIAL_TYPES`

Returned by endpoint 2. Set on submission only; no endpoint can change it afterwards.

| Value | Meaning |
|-------|---------|
| `PHOTOGRAPH` | Prints, negatives, slides, family albums |
| `MANUSCRIPT` | Handwritten works — poetry notebooks, diaries, drafts |
| `DOCUMENT` | Printed or official paper — certificates, letters, pamphlets, newspapers |
| `AUDIO` | Sound recordings — cassettes, reel-to-reel, digital audio |
| `VIDEO` | Moving images — tape, film, digital video |
| `OTHER` | Anything else; detail is in `description` |

### `currency` — `DONATION_CURRENCIES`

Returned by endpoint 1. Set on submission only.

| Value | Meaning |
|-------|---------|
| `IQD` | Iraqi dinar |
| `USD` | United States dollar |

No conversion happens anywhere. Summing a page of donations requires grouping by `currency` first.

### `Language`

The `Language` enum (`CKB` / `KMR`) is **not referenced anywhere in this domain**. Bilingual content
is structural — paired `*Ckb` / `*Kmr` fields on `DonationSettings` — and no donation field is
validated against it. `Language` still governs `Accept-Language` on error responses.

---

## Auth failure bodies

Five of the six handlers here have **no `@PreAuthorize`**, so their rejections happen inside the
Spring Security filter chain, *before* `GlobalExceptionHandler` can see them. Do not expect the
`ApiErrorResponse` envelope on those, and do not try to read `code` from one.

| Situation | Status | Body |
|-----------|--------|------|
| No token at all | `403` | Spring Security's default entry point (`Http403ForbiddenEntryPoint`). The project's `JwtAuthenticationEntryPoint` bean exists but is never registered on the chain, so its 401 never fires. Expect an empty body or Spring Boot's generic error JSON |
| Valid token, wrong role (`EMPLOYEE`, `GUEST`) | `403` | Same — `AccessDeniedHandlerImpl`, not the advice |
| Expired token | `401` | `JWTAuthenticationFilter` writes it directly: `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}`. The auth cookie is cleared |
| Malformed / unverifiable token | `403` | `{"error":"INVALID_TOKEN","message":"Invalid token"}`. Cookie cleared |
| Token revoked by logout | `401` | `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}`. Cookie cleared |

**Endpoint 6 is the exception.** `PATCH /donations/settings/featured` has `@PreAuthorize`, so a
`SUPER_ADMIN` (or any authenticated non-`ADMIN` who somehow passed the filter chain) is rejected by
method security *inside* the handler invocation, which the advice does catch — that rejection is a
full `ApiErrorResponse` with `code: "FORBIDDEN"`, as shown above.

So the dashboard must handle **two different 403 shapes on the same domain**. Branch on the HTTP
status first, then fall back to a generic message when the body has no `code`.

---

## Notes & gotchas

- **`PUT /donations/settings` is destructive for content, permissive for switches, and conservative
  for featuring.** Three different omission behaviours in one request body (Groups A / B / C above).
  Load the current settings with `GET /api/v1/donations/settings`, merge your edits into that object,
  and send the whole thing back. Never send a partial body.
- **Omitting the two channel switches re-opens both channels.** The single most likely way to
  accidentally reopen fundraising after deliberately closing it.
- **`featuredOrder` semantics differ between endpoints 5 and 6.** Endpoint 5 only writes it when it
  is non-null *and* the row ends up featured; endpoint 6 writes it unconditionally, so omitting it
  while featuring resets it to `null`.
- **An empty body `{}` on endpoint 6 features the page.** `featured == null` is treated as `true`.
- **`FeaturedRequest`'s `@NotBlank` fields are dead on endpoint 6.** The parameter has no `@Valid`,
  and `setDonationFeatured()` reads only `featured`, `featuredOrder` and `featureImageUrl`. Adding
  `@Valid` to that handler would immediately break every dashboard call — the five `@NotBlank` fields
  are never sent.
- **The featured cap is global and shared.** `countAllFeatured()` sums featured News, Projects,
  Writings, Videos, Sound Tracks, Image Collections and the donation page. Featuring the donation
  page can be blocked by an unrelated article, and the error message does not say which record is in
  the way. Surface the current count next to the toggle.
- **`donation_settings` is a singleton by convention only.** There is no unique constraint, no index
  and no ordering: every access is `findAll().stream().findFirst()`, and `findAll()` has no
  `ORDER BY`, so if a second row ever exists, which one wins is whatever Postgres returns first.
  Do not create a second row.
- **`financial_enabled` and `archive_enabled` are nullable columns behind Java primitives.** This is
  the exact shape that once caused a production outage on `featured` (repaired by
  [`../../scripts/sql/2026-08-17-featured-about-service-donation.sql`](../../scripts/sql/2026-08-17-featured-about-service-donation.sql),
  which added `featured`, `featured_order` and `feature_image_url`, backfilled `false` and set
  `NOT NULL`). Those two columns were never hardened — a `NULL` in either makes every read of
  `donation_settings` fail with a 500. Note also that the column names (`financial_enabled`,
  `archive_enabled`) do **not** match the field names (`financialDonationsEnabled`,
  `archiveDonationsEnabled`).
- **Submissions can only ever change status.** No delete, no edit, no export, no reply, no
  assignment, no per-submission `GET`, no bulk operation. If a donor asks for their record to be
  corrected or removed, it needs a database operation.
- **The inboxes have no filtering and no size cap.** Sorting is fixed at `createdAt DESC` and the
  declared indexes lead with `status` (`idx_financial_donation_status_created`,
  `idx_archive_donation_status_created`), so they do not serve that unfiltered sort. On a large table
  this is a full scan plus a sort; keep `size` modest and paginate.
- **No caching in this domain.** `SiteContentService` has no `@Cacheable` / `@CacheEvict` on any
  donation method. Redis is configured for the app (`khi:` prefix, 10-minute default TTL) but not
  used here, so every write is visible to the public site immediately and no eviction step is needed.
- **No audit trail and no notifications.** Nothing records who read the inbox, who changed a status,
  or who edited the bank details; nothing emails anyone when a submission arrives. Staff have to poll
  the dashboard.
- **Type-coercion failures answer 500, not 400.** `GlobalExceptionHandler` registers no handler for
  `MethodArgumentTypeMismatchException`, so `PATCH /api/v1/donations/financial/abc/status` and
  `?page=x` both fall to `@ExceptionHandler(Exception.class)` → `500 INTERNAL_ERROR`. Validate ids
  and paging inputs in the dashboard before sending.
- **Column overflow reads as a duplicate-key error.** Not one field on `DonationSettingsRequest`
  carries a `@Size` constraint, and several submission fields do not either, so an over-long value
  passes bean validation and fails in Postgres (SQLSTATE `22001`). Hibernate raises `DataException`,
  Spring translates it to `DataIntegrityViolationException`, and the advice answers `409 CONFLICT`
  with the message "A record with this data already exists" — which is wrong and confusing here.
  Enforce lengths client-side: `titleCkb` / `titleKmr` 500, `bankName` / `accountName` 300,
  `accountNumber` 120, `iban` 120, `swiftCode` 60.
- **Localized error text is unreliable.** The English bundle on disk is named
  `" messages_en.properties"` with a leading space so it never loads, and the Sorani/Kurmanji bundles
  contain literal `?` characters where Kurdish letters should be. For service-thrown
  `IllegalArgumentException` / `IllegalStateException` the real cause is in `details.reason`; for a
  404 the raw English text is copied into all three message fields. Render your own copy from `code`
  plus `details.reason` rather than displaying `message` / `messageEn` / `messageKu` to staff.
- **Timestamps.** `createdAt` is ISO-8601 local date-time with no offset (`2026-08-26T09:14:22.481`).
  The database runs `hibernate.jdbc.time_zone=UTC` — treat it as UTC and convert to `Asia/Baghdad`
  for display.
- **`X-Trace-Id`.** Returned on every response and echoed if you supply one; it is the `traceId`
  inside any `ApiErrorResponse`. Show it in error toasts.
- **Live spec.** Swagger UI at `/swagger-ui.html`, JSON at `/v3/api-docs`, groups `public`,
  `internal` and `all`. Local base URL is `http://localhost:8080`; production runs on Railway.

---

## Related documentation

- Counterpart: [`../external/DONATION_API.md`](../external/DONATION_API.md)
- Sibling submission inbox with the same status workflow: [`CONTACT_API.md`](CONTACT_API.md)
- Login, token lifetime and revocation: [`AUTH_SESSIONS_API.md`](AUTH_SESSIONS_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
