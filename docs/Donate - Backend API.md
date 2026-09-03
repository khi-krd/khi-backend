# Donate - Backend API

Spec for **khi-backend** / CMS so `/[locale]/donate` works in **khi-website**.

**Out of scope:** payment gateway integration, online checkout, webhooks, transaction verification, or any automated money movement. Financial donations are **intent registrations** only — the user chooses an amount and method, then manually transfers funds using displayed account details.

---

## Overview

The Donate page is a single scroll page with five sections:

| # | Section | Purpose |
| --- | --- | --- |
| 1 | **Hero** | Full-bleed background image + CTAs to forms |
| 2 | **Types grid** | “What can I donate?” cards (archive + financial categories) |
| 3 | **Participation** | Two path cards linking to forms + explanatory copy |
| 4 | **Forms** | Archive submission form + financial intent form |
| 5 | **Closing** | Supporters block + copy-to-clipboard bank/FastPay numbers |

**Locales**

| URL | Notes |
| --- | --- |
| `/ckb/donate` | Sorani (Central Kurdish) |
| `/ku/donate` | Kurmanji |

Most page copy is **i18n** today (`messages/ckb.json`, `messages/ku.json` under the `Donate` namespace). The backend currently drives **settings**, **type visibility toggles**, and **form submissions**.

---

## What the frontend expects from the backend

| Data | Source today | Backend endpoint |
| --- | --- | --- |
| Hero background image | API → mock fallback | `GET /api/v1/donations/settings` → `heroImageUrl` |
| FIB account number (display) | API → mock fallback | `settings.accountNumber` |
| FastPay number (display) | API → mock fallback | `settings.iban` *(mapped to FastPay in frontend)* |
| Show/hide archive types | API | `settings.archiveDonationsEnabled` + types |
| Show/hide financial type | API | `settings.financialDonationsEnabled` + types |
| Archive form submission | API (partial) | `POST /api/v1/donations/archive` |
| Financial intent submission | API (partial) | `POST /api/v1/donations/financial` |
| Type cards (titles, descriptions, images, order) | **Static mock** (backend ready, site not wired yet) | `GET /api/v1/donations/type-cards` |
| Supporters section image | **Static mock** | Not from API yet |
| Amount presets (25k/50k/100k) | **Static mock** | Not from API yet |
| All headings, labels, descriptions | **i18n** | Optional future CMS fields in settings |

**Dev fallback:** if the API is empty/unavailable, the site shows mock data from `src/lib/mock/donate.ts`.

---

## Endpoints

### Public (site)

| Method | Path | Use |
| --- | --- | --- |
| `GET` | `/api/v1/donations/settings` | Page config + hero + bank display numbers |
| `GET` | `/api/v1/donations/types` | Enable/disable donation categories |
| `GET` | `/api/v1/donations/type-cards` | "What can I donate?" picture cards (CMS-driven) |
| `POST` | `/api/v1/donations/archive` | Archive donation offer submission |
| `POST` | `/api/v1/donations/financial` | Financial donation **intent** submission |

### Admin (CMS)

| Method | Path | Use |
| --- | --- | --- |
| `PUT` | `/api/v1/donations/settings` | Update page settings |
| `GET` | `/api/v1/donations/type-cards?includeInactive=true` | List type cards, hidden ones included |
| `POST` | `/api/v1/donations/type-cards` | Create a "What can I donate?" card |
| `PUT` | `/api/v1/donations/type-cards/{id}` | Replace a card (full row) |
| `DELETE` | `/api/v1/donations/type-cards/{id}` | Delete a card |
| `GET` | `/api/v1/donations/archive` | List archive submissions (`page`, `size`) |
| `PATCH` | `/api/v1/donations/archive/{id}/status` | Review workflow |
| `GET` | `/api/v1/donations/financial` | List financial intents (`page`, `size`) |
| `PATCH` | `/api/v1/donations/financial/{id}/status` | Review workflow |

**Response envelope** (consistent with other KHI APIs):

```json
{
  "success": true,
  "message": "…",
  "data": { /* payload */ }
}
```

The frontend unwraps `data` automatically when `success === true`.

---

## 1. Donation settings

Singleton record (one row). Controls hero media, bank display info, and form availability.

### `GET /api/v1/donations/settings`

### Fields

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | number | — | Present on read |
| `titleCkb` | string | Optional | Not used by frontend yet; reserved for CMS-driven hero title |
| `titleKmr` | string | Optional | Same |
| `descriptionCkb` | string | Optional | Reserved for CMS-driven hero intro |
| `descriptionKmr` | string | Optional | Same |
| `heroImageUrl` | string | Recommended | Full URL for hero background |
| `bankName` | string | Optional | Not displayed yet; useful for CMS/admin |
| `accountName` | string | Optional | Not displayed yet |
| `accountNumber` | string | Recommended | Shown as **FIB account** on closing section |
| `iban` | string | Recommended | Shown as **FastPay number** on closing section *(frontend maps* `iban` *→* `fastpayNumber`*)* |
| `swiftCode` | string | Optional | Not displayed yet |
| `paymentInstructionsCkb` | string | Optional | Not displayed yet |
| `paymentInstructionsKmr` | string | Optional | Not displayed yet |
| `financialDonationsEnabled` | boolean | Recommended | Default `true`. `false` hides the financial type card + form path |
| `archiveDonationsEnabled` | boolean | Recommended | Default `true`. `false` hides archive-related type cards |

### Example response

```json
{
  "success": true,
  "message": "Donation settings fetched",
  "data": {
    "id": 1,
    "titleCkb": "بەخشین",
    "titleKmr": "Bexşîn",
    "descriptionCkb": "…",
    "descriptionKmr": "…",
    "heroImageUrl": "https://cdn.example.com/donations/hero.jpg",
    "bankName": "First Iraqi Bank",
    "accountName": "Kurdish Heritage Institute",
    "accountNumber": "2345 8901 4567 1201",
    "iban": "0770 123 4567",
    "swiftCode": null,
    "paymentInstructionsCkb": null,
    "paymentInstructionsKmr": null,
    "financialDonationsEnabled": true,
    "archiveDonationsEnabled": true
  }
}
```

### `PUT /api/v1/donations/settings`

Same body shape as above (without `id` on create). Admin-only.

---

## 2. Donation types

Coarse category toggles. The frontend has **5 fixed type cards** (hardcoded IDs + i18n copy); the API only controls whether archive-related cards and the financial card appear.

### `GET /api/v1/donations/types`

Returns an array (not paginated):

```json
{
  "success": true,
  "message": "Donation types fetched",
  "data": [
    {
      "code": "FINANCIAL",
      "titleCkb": "پشتیوانیی دارایی",
      "titleKmr": "Piştgiriya darayî",
      "enabled": true
    },
    {
      "code": "ARCHIVE",
      "titleCkb": "بەخشینی ئەرشیفی",
      "titleKmr": "Bexşa arşîvê",
      "enabled": true
    }
  ]
}
```

### Type codes

| `code` | Frontend cards affected |
| --- | --- |
| `ARCHIVE` | `visualArchive`, `documents`, `oralHeritage`, `scientific` |
| `FINANCIAL` | `financial` |

### Visibility logic (as implemented in frontend)

- If `archiveDonationsEnabled === false` → all archive cards hidden.
- If `financialDonationsEnabled === false` → financial card hidden.
- If types array is empty → all cards shown (fallback).
- If types exist → card group shown only when matching `code` has `enabled: true`.

### Per-card metadata — now CMS-driven

**Update 2026-09-03:** the "What can I donate?" cards themselves are now database
rows with full CRUD at `/api/v1/donations/type-cards` — see
[`DONATION_TYPE_CARDS.md`](DONATION_TYPE_CARDS.md). The table below describes the
hardcoded frontend cards, which remain the fallback until the website is wired to
the new endpoint:

| Frontend `id` | Index | Archive group? |
| --- | --- | --- |
| `visualArchive` | 1 (featured) | Yes |
| `documents` | 2 | Yes |
| `oralHeritage` | 3 | Yes |
| `financial` | 4 | No (financial) |
| `scientific` | 5 | Yes |

Titles/descriptions come from i18n keys: `Donate.types.items.{id}.title` / `.description`.

---

## 3. Archive donation submission

Users offer physical or digital archive materials (photos, manuscripts, cassettes, etc.). This is an **offer/intake form**, not an instant upload to the public archive.

### `POST /api/v1/donations/archive`

**Content-Type:** `application/json` *(current frontend implementation)*

### Request body

| Field | Type | Required | Frontend source | Notes |
| --- | --- | --- | --- | --- |
| `donorName` | string | ✅ | Form: “User name” | Trimmed |
| `email` | string | — | Hardcoded `""` today | Accept empty; optional for future |
| `phone` | string | ✅ | Form: “Contact number” | Trimmed |
| `materialType` | enum | ✅ | Form dropdown | See mapping below |
| `title` | string | Optional | Form: “Register name” | Optional display/credit name |
| `description` | string | Optional | Form: “Note / brief history” | Free text |
| `estimatedDate` | string | Optional | Not collected yet | e.g. `"1950"` — reserve for future |
| `attachmentUrl` | string | Optional | **Not sent yet** | See file upload gap below |

### Material type enum + frontend mapping

| Frontend value | API value |
| --- | --- |
| `cassetteAudio` | `AUDIO` |
| `photograph` | `PHOTOGRAPH` |
| `manuscript` | `MANUSCRIPT` |
| `document` | `DOCUMENT` |
| `video` | `VIDEO` |
| `other` | `OTHER` |

Allowed values: `PHOTOGRAPH` | `MANUSCRIPT` | `DOCUMENT` | `AUDIO` | `VIDEO` | `OTHER`

### Example request

```json
{
  "donorName": "Ahmed Hassan",
  "email": "",
  "phone": "07701234567",
  "materialType": "PHOTOGRAPH",
  "title": "Historic Erbil family photo",
  "description": "Black-and-white photograph from the 1960s."
}
```

### Response

```json
{
  "success": true,
  "message": "Archive donation offer received",
  "data": {
    "id": 201,
    "donorName": "Ahmed Hassan",
    "email": "",
    "phone": "07701234567",
    "materialType": "PHOTOGRAPH",
    "title": "Historic Erbil family photo",
    "description": "Black-and-white photograph from the 1960s.",
    "estimatedDate": null,
    "attachmentUrl": null,
    "status": "PENDING",
    "createdAt": "2026-07-22T10:00:00"
  }
}
```

### Status workflow (admin)

| Status | Meaning |
| --- | --- |
| `PENDING` | New submission |
| `APPROVED` | Accepted / follow-up scheduled |
| `REJECTED` | Declined or duplicate |

`PATCH /api/v1/donations/archive/{id}/status`

```json
{ "status": "APPROVED" }
```

### Admin list

`GET /api/v1/donations/archive?page=0&size=20`

Paginated Spring-style response inside `data`:

```json
{
  "content": [ /* ArchiveDonationResponse[] */ ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

---

## 4. File upload gap (archive form)

The archive form UI includes an optional file field, but **the frontend does not upload files yet**. Validation on the client:

| Rule | Value |
| --- | --- |
| Max size | 10 MB |
| Accepted MIME types | `image/jpeg`, `image/png`, `image/webp`, `image/gif`, `audio/mpeg`, `audio/wav`, `audio/ogg`, `video/mp4`, `video/webm`, `application/pdf` |

**Recommended backend options** (pick one):

**Option A — Multipart on same endpoint**

```
POST /api/v1/donations/archive
Content-Type: multipart/form-data
```

Fields: same as JSON + `file` (optional). Backend stores file, sets `attachmentUrl` on the record.

**Option B — Two-step upload**

```
POST /api/v1/uploads/donations   → { "url": "https://…" }
POST /api/v1/donations/archive     → { …, "attachmentUrl": "https://…" }
```

Until upload is wired, accept submissions without `attachmentUrl`.

---

## 5. Financial donation intent

Records that a user **intends** to donate a specific amount via a manual transfer method. No payment processing.

### `POST /api/v1/donations/financial`

### Request body

| Field | Type | Required | Frontend source | Notes |
| --- | --- | --- | --- | --- |
| `donorName` | string | ✅ | Form | Trimmed |
| `email` | string | — | Hardcoded `""` | Optional |
| `phone` | string | Optional | Not collected | Reserve |
| `amount` | number | ✅ | Form | Must be `> 0` |
| `currency` | string | ✅ | Form | Uppercased: `IQD` or `USD` |
| `paymentMethod` | string | ✅ | Form selection | See mapping |
| `transactionReference` | string | Optional | Not collected | User could add later |
| `message` | string | Optional | Not collected | Reserve |

### Payment method mapping (frontend → API)

Both UI options map to the same backend value today:

| Frontend `id` | API `paymentMethod` |
| --- | --- |
| `fib` | `BANK_TRANSFER` |
| `fastpay` | `BANK_TRANSFER` |

Consider storing the **UI choice** (`FIB` / `FASTPAY`) as a separate field if you need to distinguish them in admin.

### Amount presets (static today)

Frontend shows three preset buttons (IQD only in UI):

| Preset id | Value |
| --- | --- |
| `large` | 100,000 |
| `medium` | 50,000 |
| `small` | 25,000 |

These are **not from the API**. User can also enter any custom amount.

### Example request

```json
{
  "donorName": "Sara Mohammed",
  "email": "",
  "amount": 50000,
  "currency": "IQD",
  "paymentMethod": "BANK_TRANSFER"
}
```

### Response

```json
{
  "success": true,
  "message": "Financial donation received",
  "data": {
    "id": 200,
    "donorName": "Sara Mohammed",
    "email": "",
    "phone": null,
    "amount": 50000,
    "currency": "IQD",
    "paymentMethod": "BANK_TRANSFER",
    "transactionReference": null,
    "message": null,
    "status": "PENDING",
    "createdAt": "2026-07-22T10:00:00"
  }
}
```

After success, the UI shows a confirmation message and the closing section already displays FIB/FastPay numbers from settings for manual transfer.

### Admin

Same status enum as archive: `PENDING` | `APPROVED` | `REJECTED`.

- `GET /api/v1/donations/financial?page=0&size=20`
- `PATCH /api/v1/donations/financial/{id}/status`

---

## 6. How the site uses the API

1. `GET /api/v1/donations/settings` → hero image, bank numbers, enable flags
2. `GET /api/v1/donations/types` → filter type cards
3. Render page with i18n copy + API media/settings
4. User submits archive form → `POST /api/v1/donations/archive`
5. User submits financial form → `POST /api/v1/donations/financial`
6. Closing section shows `accountNumber` + `iban` from settings (copy-to-clipboard)

**Anchor targets**

| Element | ID |
| --- | --- |
| Archive form | `#archive-form` |
| Financial form | `#financial-form` |

Hero CTAs and participation cards link to these anchors.

---

## 7. Validation (backend)

### Settings

- `heroImageUrl`, `accountNumber`, `iban`: valid URLs/strings when provided.
- Booleans default to `true` if omitted on first seed.

### Archive submission

- `donorName`: non-empty, max ~200 chars.
- `phone`: non-empty (frontend requires it).
- `materialType`: must be valid enum.
- `attachmentUrl`: valid URL if present.
- Rate limiting / spam protection recommended (public endpoint).

### Financial intent

- `donorName`: non-empty.
- `amount`: finite number `> 0`.
- `currency`: `IQD` or `USD` (uppercase).
- `paymentMethod`: accept `BANK_TRANSFER` at minimum.
- Do **not** attempt card capture or gateway redirects.

### Types

- Exactly two codes expected: `FINANCIAL`, `ARCHIVE`.
- `enabled` boolean required per row.

---

## 8. Database model (suggested)

### `donation_settings` (singleton)

One row. Columns match settings fields above.

### `donation_types`

| Column | Type |
| --- | --- |
| `code` | enum: `FINANCIAL`, `ARCHIVE` |
| `title_ckb` | text |
| `title_kmr` | text |
| `enabled` | boolean |

### `archive_donations`

| Column | Type |
| --- | --- |
| `id` | bigint PK |
| `donor_name` | varchar |
| `email` | varchar nullable |
| `phone` | varchar |
| `material_type` | enum |
| `title` | varchar nullable |
| `description` | text nullable |
| `estimated_date` | varchar nullable |
| `attachment_url` | varchar nullable |
| `status` | enum: PENDING/APPROVED/REJECTED |
| `created_at` | timestamp |
| `updated_at` | timestamp |

### `financial_donations`

| Column | Type |
| --- | --- |
| `id` | bigint PK |
| `donor_name` | varchar |
| `email` | varchar nullable |
| `phone` | varchar nullable |
| `amount` | decimal |
| `currency` | varchar(3) |
| `payment_method` | varchar |
| `transaction_reference` | varchar nullable |
| `message` | text nullable |
| `status` | enum |
| `created_at` | timestamp |
| `updated_at` | timestamp |

---

## 9. CMS checklist

- [ ]  
    
    Settings editor (hero image upload, FIB account, FastPay number, enable toggles)
    
- [ ]  
    
    Donation types toggles (`FINANCIAL` / `ARCHIVE`)
    
- [ ]  
    
    Archive submissions inbox (list, detail, status change, export)
    
- [ ]  
    
    Financial intents inbox (list, detail, status change, filter by currency/status)
    
- [ ]  
    
    File upload for archive attachments (when frontend is wired)
    
- [ ]  
    
    Optional: notification email on new submission
    
- [ ]  
    
    Optional: migrate page copy from i18n into `titleCkb` / `descriptionCkb` fields
    

---

## 10. Future backend extensions (not required for v1)

| Feature | Why |
| --- | --- |
| Per-type card images + order | Replace static mock images in types grid |
| `supportersImageUrl` in settings | Closing section image |
| `amountPresets[]` in settings | CMS-managed suggested amounts |
| Separate `fastpayNumber` field | Stop overloading `iban` |
| `preferredPaymentChannel` on financial records | Distinguish FIB vs FastPay |
| Localized settings via `contents[]` | Match Services page pattern instead of `titleCkb`/`titleKmr` |

---

## 11. Known gaps to coordinate

1. **File upload** — UI exists; backend should accept files; frontend still needs wiring.
2. `email` **always empty** — both forms omit it; backend should not require it.
3. `iban` **used for FastPay** — naming mismatch; consider a dedicated field.
4. **Payment method granularity** — both map to `BANK_TRANSFER`; admin cannot see FIB vs FastPay choice.
5. **Settings localized titles** — defined in schema but not consumed by the page yet (i18n used instead).
6. **Type card content/images** — fully static; API only toggles visibility groups.

---

## Code references (khi-website)

| What | Path |
| --- | --- |
| Zod schemas | `src/types/donation.ts` |
| API fetch + submit | `src/lib/api/donations.ts` |
| Server actions | `src/lib/actions/donations.ts` |
| Mock fallbacks | `src/lib/mock/donate.ts` |
| Archive form schema | `src/lib/schemas/donate-archive-form.ts` |
| Financial form schema | `src/lib/schemas/donate-financial-form.ts` |
| Page | `src/app/[locale]/donate/page.tsx` |
| Components | `src/components/donate/*` |