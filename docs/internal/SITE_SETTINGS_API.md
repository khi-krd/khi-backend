# Site Configuration API — Internal (Authenticated)

This is the admin-dashboard half of the public-site configuration domain: creating and editing the
team and partner lists, the branding row (logo, donate-band photo, hero slide cap), the footer
social links, and the hamburger navigation menu. Every endpoint on this page requires a JWT and one
of the roles `ADMIN` or `SUPER_ADMIN`. The public website reads the same records through the
[external Site Configuration API](../external/SITE_SETTINGS_API.md), which is anonymous and
read-only.

| | |
|---|---|
| **Base paths** | `/api/v1/about/team`, `/api/v1/about/partners`, `/api/v1/site-settings`, `/api/v1/settings/social`, `/api/v1/nav-menu` |
| **Audience** | Admin dashboard (JWT required), roles `ADMIN` or `SUPER_ADMIN` |
| **Controllers** | `src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/api/site/NavMenuController.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/service/site/NavMenuService.java` |
| **DTOs** | `src/main/java/ak/dev/khi_backend/khi_app/dto/site/SiteContentDtos.java`<br>`src/main/java/ak/dev/khi_backend/khi_app/dto/site/NavMenuDtos.java` |
| **Repositories** | `src/main/java/ak/dev/khi_backend/khi_app/repository/site/` |
| **Entities** | `TeamMember`, `Partner`, `SiteSettings`, `SocialLink`, `NavMenuItem`, `NavMenuLink` |
| **Tables** | `team_members`, `partners`, `site_settings`, `social_links`, `nav_menu_items`, `nav_menu_links` |
| **Response envelope** | `ApiResponse<T>` — `{ "success", "message", "data" }` — on every endpoint |
| **Content type** | `application/json` on every endpoint. Nothing in this domain is multipart. |
| **Verified against source** | 2026-08-26 |

---

## Authentication

Send the JWT either as a bearer header or as the HttpOnly cookie issued at login:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

or

```
Cookie: <JWT_COOKIE_NAME>=eyJhbGciOiJIUzI1NiJ9...
```

The cookie name comes from the `JWT_COOKIE_NAME` environment variable. Sessions are stateless
(`SessionCreationPolicy.STATELESS`); tokens are jjwt-signed and validated per request by
`JWTAuthenticationFilter`. Granted authorities are `ROLE_<NAME>` plus the permission strings
`user:create`, `user:read`, `user:update`, `user:delete`.

Roles in this codebase are `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`. **There is no
`RoleHierarchy` bean**, so `SUPER_ADMIN` does not automatically satisfy a check for `ADMIN`. Every
endpoint in this document accepts both roles explicitly; `GUEST` and `EMPLOYEE` are refused.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/v1/about/team` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a team member |
| 2 | `PUT` | `/api/v1/about/team/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace a team member |
| 3 | `DELETE` | `/api/v1/about/team/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a team member |
| 4 | `POST` | `/api/v1/about/partners` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a partner |
| 5 | `PUT` | `/api/v1/about/partners/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace a partner |
| 6 | `DELETE` | `/api/v1/about/partners/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a partner |
| 7 | `PUT` | `/api/v1/site-settings` | JWT | `ADMIN`, `SUPER_ADMIN` | Save branding and the hero slide cap |
| 8 | `POST` | `/api/v1/settings/social` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a social link |
| 9 | `PUT` | `/api/v1/settings/social/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace a social link |
| 10 | `DELETE` | `/api/v1/settings/social/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a social link |
| 11 | `POST` | `/api/v1/nav-menu` | JWT | `ADMIN`, `SUPER_ADMIN` | Create a menu item with its links |
| 12 | `PUT` | `/api/v1/nav-menu/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Replace a menu item, optionally its links |
| 13 | `DELETE` | `/api/v1/nav-menu/{id}` | JWT | `ADMIN`, `SUPER_ADMIN` | Hard-delete a menu item and its links |

The matching **read** endpoints (`GET /api/v1/about/team`, `GET /api/v1/about/partners`,
`GET /api/v1/site-settings`, `GET /api/v1/settings/social`, `GET /api/v1/nav-menu`,
`GET /api/v1/nav-menu/{id}`, `GET /api/v1/featured`, `GET /api/v1/sitemap`) are all `permitAll` and
are documented in the [external file](../external/SITE_SETTINGS_API.md). The dashboard calls those
same public routes to populate its forms — including the `?includeInactive=true` variants.

### Where the authorization comes from

Endpoints #1–#6 and #8–#13 are authorized purely by URL rules in `SecurityConfig`; none of those
handler methods carries a `@PreAuthorize`:

```java
.requestMatchers(HttpMethod.POST,
        "/api/v1/featured/**", "/api/v1/about/team/**", "/api/v1/about/partners/**",
        "/api/v1/settings/social/**", "/api/v1/nav-menu/**")
    .hasAnyRole("ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.PUT,
        "/api/v1/featured/**", "/api/v1/about/team/**", "/api/v1/about/partners/**",
        "/api/v1/settings/social/**", "/api/v1/donations/settings",
        "/api/v1/site-settings", "/api/v1/nav-menu/**")
    .hasAnyRole("ADMIN", "SUPER_ADMIN")
.requestMatchers(HttpMethod.DELETE,
        "/api/v1/featured/**", "/api/v1/about/team/**", "/api/v1/about/partners/**",
        "/api/v1/settings/social/**", "/api/v1/nav-menu/**")
    .hasAnyRole("ADMIN", "SUPER_ADMIN")
```

The team and partner routes are additionally covered by the later catch-all
`.requestMatchers("/api/v1/about/**").hasAnyRole("ADMIN", "SUPER_ADMIN")`, which applies to every
verb other than `GET`. The two rules agree.

Endpoint #7 is the only one with a method-level annotation, and it grants exactly the same pair of
roles as the URL rule, so it narrows nothing:

```java
@PutMapping("/site-settings")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
```

> **Note:** the two mechanisms produce **different error bodies**. A rejection from `SecurityConfig`
> happens in the filter chain before the dispatcher, so the response is Spring Boot's default error
> JSON. A rejection from `@PreAuthorize` (endpoint #7 only) is an `AccessDeniedException` inside the
> dispatcher and is rendered by `GlobalExceptionHandler` as a proper `ApiErrorResponse` with
> `code: "FORBIDDEN"`. Both are `403`. `SecurityConfig` wires no `AuthenticationEntryPoint`, so an
> **anonymous** call is also refused with `403`, not `401`.

---

## Conventions that apply to every write here

**These are true `PUT`s, not `PATCH`es.** `SiteContentService.applyTeam()`, `applyPartner()`,
`applySocial()` and `NavMenuService.apply()` all assign **every** field from the request onto the
entity. A field you omit is written as `null` (via `trimToNull`), not left alone. Always send the
complete object on update — read the record first, edit the fields you want, send the whole thing
back. The single exception is `PUT /api/v1/site-settings`, which is deliberately tri-state; it is
called out on that endpoint.

**Blank means null.** Every optional string goes through `trimToNull()`: leading and trailing
whitespace is stripped, and a string that is empty after trimming is stored as `NULL`. Required
strings are only `.trim()`ed.

**Defaults for omitted `displayOrder` / `active`.**

| Field | Omitted / `null` becomes |
|-------|--------------------------|
| `displayOrder` on a team member, partner, social link or nav menu item | `0` |
| `displayOrder` on a nav menu **link** | its 1-based position in the `links` array |
| `active` anywhere | `true` (`request.getActive() == null \|\| request.getActive()`) |

**`201` on create, `200` on update and delete.** `POST` handlers carry
`@ResponseStatus(HttpStatus.CREATED)`. There is no `Location` header.

**Delete responses have no `data` key.** They return `ApiResponse<Void>` with `data = null`, and
`ApiResponse` is annotated `@JsonInclude(NON_NULL)`, so the body is just
`{ "success": true, "message": "…" }`.

**Nulls are omitted everywhere else too.** `spring.jackson.default-property-inclusion` is
`non_null` application-wide. A nullable field that is `null` is absent from the JSON, never
`"field": null`. Responses are also pretty-printed (`spring.jackson.serialization.indent_output:
true`).

**Unknown fields in a request body are ignored, not rejected.** Send an extra key and it is
dropped silently; the record is created or updated from the fields the DTO does declare.

**Deletes are hard deletes and do not touch S3.** All image fields in this domain
(`TeamMember.imageUrl`, `Partner.logoUrl`, `SiteSettings.logoUrl`, `SiteSettings.donateImageUrl`,
`NavMenuItem.imageUrl`) are plain strings. Nothing in `SiteContentService` or `NavMenuService`
calls the storage layer, so deleting a record leaves its object in the
`s3-khiwebsite` bucket (`us-east-1`, base folder `khi-web-folders`) forever. Upload images first
through `POST /api/v1/media/upload` (also `ADMIN`/`SUPER_ADMIN`) and paste the returned URL into
these payloads.

**No cache invalidation is needed.** Neither service caches anything, and the public reads are
uncached too, so a write is visible on the next public request. (The only `@CacheEvict` nearby is on
`setServiceFeatured()`, which belongs to the Services domain.)

---

## 1. `POST /api/v1/about/team` — Create a team member

Inserts a row into `team_members`. No uniqueness constraint exists on this table — creating two
members with the same name succeeds.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters** — none.
**Query parameters** — none.

**Request body — `TeamMemberRequest`**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `nameCkb` | string | **yes** | `@NotBlank`; column `varchar(300)` | Full name, Sorani. Trimmed. |
| `nameKmr` | string | no | column `varchar(300)` | Full name, Kurmanji |
| `roleCkb` | string | **yes** | `@NotBlank`; column `varchar(300)` | Job title, Sorani. Trimmed. |
| `roleKmr` | string | no | column `varchar(300)` | Job title, Kurmanji |
| `bioCkb` | string | no | column `TEXT` | Short biography, Sorani |
| `bioKmr` | string | no | column `TEXT` | Short biography, Kurmanji |
| `office` | string | no | column `varchar(200)` | Free text, e.g. city or department |
| `imageUrl` | string | no | column `TEXT` | Portrait URL, normally S3 |
| `displayOrder` | integer | no | defaults to `0` | Sort key, ascending |
| `active` | boolean | no | defaults to `true` | `false` hides the member from `GET /api/v1/about/team` |

> **Note:** only `nameCkb` and `roleCkb` are validated. There are **no `@Size` constraints** on this
> DTO, so a 400-character `nameCkb` passes bean validation and then fails at the database against
> `varchar(300)`. That surfaces as `409 CONFLICT` (`DataIntegrityViolationException`), not as a
> `400 VALIDATION_ERROR`. Enforce the column lengths in your form.

```json
{
  "nameCkb": "د. ئاراس مەحموود",
  "nameKmr": "Dr. Aras Mehmûd",
  "roleCkb": "بەڕێوەبەری گشتی",
  "roleKmr": "Rêveberê giştî",
  "bioCkb": "توێژەری مێژووی کوردستانی هاوچەرخ، دامەزرێنەری ئەرشیفی دەنگی هەولێر.",
  "bioKmr": "Lêkolînerê dîroka Kurdistana hemdem, damezrînerê arşîva dengî ya Hewlêrê.",
  "office": "هەولێر",
  "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/team/aras-mahmud.jpg",
  "displayOrder": 1,
  "active": true
}
```

**Response `201 Created`**

```json
{
  "success": true,
  "message": "Team member created",
  "data": {
    "id": 7,
    "nameCkb": "د. ئاراس مەحموود",
    "nameKmr": "Dr. Aras Mehmûd",
    "roleCkb": "بەڕێوەبەری گشتی",
    "roleKmr": "Rêveberê giştî",
    "bioCkb": "توێژەری مێژووی کوردستانی هاوچەرخ، دامەزرێنەری ئەرشیفی دەنگی هەولێر.",
    "bioKmr": "Lêkolînerê dîroka Kurdistana hemdem, damezrînerê arşîva dengî ya Hewlêrê.",
    "office": "هەولێر",
    "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/team/aras-mahmud.jpg",
    "displayOrder": 1,
    "active": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `nameCkb` or `roleCkb` missing, empty or whitespace-only. `fieldErrors` names the field. |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type (e.g. `"displayOrder": "first"`). |
| `403` | — | Caller is anonymous, or holds only `GUEST` / `EMPLOYEE`. Filter-chain rejection, Spring Boot default error body. |
| `409` | `CONFLICT` | A value exceeds its column length, or any other database integrity violation. |
| `500` | `INTERNAL_ERROR` | Unexpected server fault. |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/about/team",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "هەڵەی پشکنینەوە (Validation) هەیە.",
  "messageEn": "One or more fields failed validation.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    { "field": "roleCkb", "message": "must not be blank", "messageEn": "must not be blank", "messageKu": "must not be blank" }
  ]
}
```

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/about/team \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "nameCkb": "د. ئاراس مەحموود",
        "roleCkb": "بەڕێوەبەری گشتی",
        "office": "هەولێر",
        "displayOrder": 1
      }'
```

---

## 2. `PUT /api/v1/about/team/{id}` — Replace a team member

Loads the row, applies **every** field from the request, saves. Omitted optional fields are
cleared.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `team_members` |

**Request body** — identical to endpoint #1 (`TeamMemberRequest`). Same constraints, same defaults.
Sending `{"nameCkb":"…","roleCkb":"…"}` on an existing member wipes `nameKmr`, `roleKmr`, `bioCkb`,
`bioKmr`, `office` and `imageUrl`, resets `displayOrder` to `0` and `active` to `true`.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Team member updated",
  "data": {
    "id": 7,
    "nameCkb": "د. ئاراس مەحموود",
    "nameKmr": "Dr. Aras Mehmûd",
    "roleCkb": "سەرۆکی دەزگا",
    "roleKmr": "Serokê saziyê",
    "office": "هەولێر",
    "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/team/aras-mahmud.jpg",
    "displayOrder": 1,
    "active": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `nameCkb` or `roleCkb` blank |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | No such member. `message` / `messageEn` / `messageKu` all read `"Team member not found: 7"`; `details.resource` repeats it. |
| `409` | `CONFLICT` | Column-length or other integrity violation |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 404,
  "path": "/api/v1/about/team/7",
  "method": "PUT",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "NOT_FOUND",
  "message": "Team member not found: 7",
  "messageEn": "Team member not found: 7",
  "messageKu": "Team member not found: 7",
  "details": { "resource": "Team member not found: 7" }
}
```

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/about/team/7 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "nameCkb": "د. ئاراس مەحموود",
        "nameKmr": "Dr. Aras Mehmûd",
        "roleCkb": "سەرۆکی دەزگا",
        "roleKmr": "Serokê saziyê",
        "office": "هەولێر",
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/team/aras-mahmud.jpg",
        "displayOrder": 1,
        "active": true
      }'
```

---

## 3. `DELETE /api/v1/about/team/{id}` — Delete a team member

Existence is checked with `existsById` first, then `deleteById`. Hard delete — the row is gone.
The portrait in S3 is **not** removed.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `team_members` |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Team member deleted"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | No such member — `"Team member not found: 7"` |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/about/team/7 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 4. `POST /api/v1/about/partners` — Create a partner

Inserts a row into `partners`. No uniqueness constraint on this table either.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Request body — `PartnerRequest`**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `nameCkb` | string | **yes** | `@NotBlank`; column `varchar(300)` | Organization name, Sorani. Trimmed. |
| `nameKmr` | string | no | column `varchar(300)` | Organization name, Kurmanji |
| `descriptionCkb` | string | no | column `TEXT` | One-line description, Sorani |
| `descriptionKmr` | string | no | column `TEXT` | One-line description, Kurmanji |
| `logoUrl` | string | no | column `TEXT` | Logo URL |
| `websiteUrl` | string | no | column `TEXT` | External link. Not validated as a URL. |
| `displayOrder` | integer | no | defaults to `0` | Sort key, ascending |
| `active` | boolean | no | defaults to `true` | `false` hides the partner from `GET /api/v1/about/partners` |

Same missing-`@Size` caveat as the team DTO: only `nameCkb` is validated.

```json
{
  "nameCkb": "زانکۆی سەڵاحەدین — هەولێر",
  "nameKmr": "Zanîngeha Selahedîn — Hewlêr",
  "descriptionCkb": "هاوبەشی توێژینەوەی مێژوویی و پارێزگاری لە بەڵگەنامەکان.",
  "descriptionKmr": "Hevkarê lêkolîna dîrokî û parastina belgeyan.",
  "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/partners/salahaddin-university.png",
  "websiteUrl": "https://su.edu.krd",
  "displayOrder": 1,
  "active": true
}
```

**Response `201 Created`**

```json
{
  "success": true,
  "message": "Partner created",
  "data": {
    "id": 2,
    "nameCkb": "زانکۆی سەڵاحەدین — هەولێر",
    "nameKmr": "Zanîngeha Selahedîn — Hewlêr",
    "descriptionCkb": "هاوبەشی توێژینەوەی مێژوویی و پارێزگاری لە بەڵگەنامەکان.",
    "descriptionKmr": "Hevkarê lêkolîna dîrokî û parastina belgeyan.",
    "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/partners/salahaddin-university.png",
    "websiteUrl": "https://su.edu.krd",
    "displayOrder": 1,
    "active": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `nameCkb` blank |
| `400` | `BAD_REQUEST` | Unparseable or unknown-property body |
| `403` | — | Insufficient role or anonymous |
| `409` | `CONFLICT` | Column-length or other integrity violation |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/about/partners \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "nameCkb": "زانکۆی سەڵاحەدین — هەولێر",
        "websiteUrl": "https://su.edu.krd",
        "displayOrder": 1
      }'
```

---

## 5. `PUT /api/v1/about/partners/{id}` — Replace a partner

Full replacement, exactly like endpoint #2. Omitted optional fields are cleared.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `partners` |

**Request body** — `PartnerRequest`, identical to endpoint #4.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Partner updated",
  "data": {
    "id": 2,
    "nameCkb": "زانکۆی سەڵاحەدین — هەولێر",
    "nameKmr": "Zanîngeha Selahedîn — Hewlêr",
    "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/partners/salahaddin-university-2026.png",
    "websiteUrl": "https://su.edu.krd",
    "displayOrder": 2,
    "active": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `nameCkb` blank |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | `"Partner not found: 2"` |
| `409` | `CONFLICT` | Integrity violation |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/about/partners/2 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "nameCkb": "زانکۆی سەڵاحەدین — هەولێر",
        "nameKmr": "Zanîngeha Selahedîn — Hewlêr",
        "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/partners/salahaddin-university-2026.png",
        "websiteUrl": "https://su.edu.krd",
        "displayOrder": 2,
        "active": true
      }'
```

---

## 6. `DELETE /api/v1/about/partners/{id}` — Delete a partner

Hard delete. The logo in S3 is not removed.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `partners` |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Partner deleted"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | `"Partner not found: 2"` |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/about/partners/2 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 7. `PUT /api/v1/site-settings` — Save branding and the hero slide cap

Saves the singleton `site_settings` row, creating it if it does not exist
(`findFirstByOrderByIdAsc().orElseGet(SiteSettings::new)`).

**This endpoint does not follow the full-replacement rule.** Every field is optional and
**tri-state**:

| You send | Effect |
|----------|--------|
| field omitted (or JSON `null`) | the stored value is left untouched |
| `""` | the stored value is cleared to `NULL` (string fields only) |
| a value | trimmed and stored |

`maxFeaturedSlides` has no "clear" form because the column is `NOT NULL` — omit it to leave it
alone. On the very first save, if `maxFeaturedSlides` is omitted and no row exists yet, the service
writes `SiteSettings.DEFAULT_MAX_FEATURED_SLIDES` (`7`) so the `NOT NULL` column is satisfied.

An empty body `{}` is legal: it is a no-op that returns the current settings.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN` — enforced twice, by the
`PUT /api/v1/site-settings` rule in `SecurityConfig` **and** by
`@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")` on the handler.
**Content-Type:** `application/json`

**Path parameters** — none.
**Query parameters** — none.

**Request body — `SiteSettingsRequest`**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `maxFeaturedSlides` | integer | no | `@Min(1)`, `@Max(20)`; column `NOT NULL` | Hard cap on the homepage hero rail. Also the cap the six content toggles and the donation toggle check before turning `featured` on. |
| `logoUrl` | string | no | column `varchar(1200)` | Header and footer logo. Should be a transparent PNG — it renders on cream in the header and near-black in the footer. |
| `donateImageUrl` | string | no | column `varchar(1200)` | Photograph for the donate band above the footer. Rendered sharp inside the slanted panel and blurred behind it. |

The controller javadoc states that URLs "must be absolute `https://`". **Nothing enforces this** —
there is no `@Pattern`, no `@URL`, and the service only trims. Validate it in the dashboard form.

```json
{
  "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/branding/khi-logo-transparent.png",
  "donateImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/branding/donate-band.jpg",
  "maxFeaturedSlides": 7
}
```

Clearing the donate-band picture while leaving everything else alone:

```json
{
  "donateImageUrl": ""
}
```

**Response `200 OK`**

`ApiResponse<SiteSettingsResponse>`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | number | no (after a save) | Primary key |
| `logoUrl` | string | yes | Stored logo URL |
| `donateImageUrl` | string | yes | Stored donate-band URL |
| `maxFeaturedSlides` | number | no | Current cap |
| `updatedAt` | string | no (after a save) | `yyyy-MM-dd HH:mm:ss`, stamped by a `@PrePersist` / `@PreUpdate` hook calling `LocalDateTime.now()` |

```json
{
  "success": true,
  "message": "Site settings updated",
  "data": {
    "id": 1,
    "logoUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/branding/khi-logo-transparent.png",
    "donateImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/branding/donate-band.jpg",
    "maxFeaturedSlides": 7,
    "updatedAt": "2026-08-26 09:14:22"
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `maxFeaturedSlides` outside `1..20`. `fieldErrors[0].field` is `maxFeaturedSlides`. |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | `FORBIDDEN` | Caller authenticated but holds neither `ADMIN` nor `SUPER_ADMIN` — this is the one endpoint in the domain whose `403` is produced by `@PreAuthorize`, so the body is a proper `ApiErrorResponse` with `details.path`, `details.method` and `details.hint`. |
| `409` | `CONFLICT` | A URL longer than `varchar(1200)` |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/v1/site-settings",
  "method": "PUT",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "هەڵەی پشکنینەوە (Validation) هەیە.",
  "messageEn": "One or more fields failed validation.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    {
      "field": "maxFeaturedSlides",
      "message": "must be less than or equal to 20",
      "messageEn": "must be less than or equal to 20",
      "messageKu": "must be less than or equal to 20"
    }
  ]
}
```

> **Note:** lowering `maxFeaturedSlides` below the number of currently featured records does **not**
> unfeature anything. The extra records keep `featured = true` in their own tables and simply fall
> off the end of the rail after the sort. Raising the cap again brings them back.

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/site-settings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "maxFeaturedSlides": 5 }'
```

---

## 8. `POST /api/v1/settings/social` — Create a social link

Inserts a row into `social_links`. `platform` is normalized with
`platform.trim().toUpperCase(Locale.ROOT)` **before** the insert, and the table has a unique
constraint `uk_social_platform` on that column — so `"facebook"` and `"FaceBook"` both collide with
an existing `FACEBOOK` row.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Request body — `SocialLinkRequest`**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `platform` | string | **yes** | `@NotBlank`, `@Size(max = 60)`; unique, uppercased | Platform identifier. Free text — no allowed list exists in the code. |
| `url` | string | **yes** | `@NotBlank`, `@Size(max = 2000)`; column `TEXT` | Profile URL. Trimmed, otherwise stored verbatim; no URL-format validation. |
| `labelCkb` | string | no | column `varchar(200)` | Display label, Sorani |
| `labelKmr` | string | no | column `varchar(200)` | Display label, Kurmanji |
| `displayOrder` | integer | no | defaults to `0` | Sort key, ascending |
| `active` | boolean | no | defaults to `true` | `false` hides the link from the default public listing |

```json
{
  "platform": "facebook",
  "url": "https://www.facebook.com/kurdistanheritageinstitute",
  "labelCkb": "فەیسبووک",
  "labelKmr": "Facebook",
  "displayOrder": 1,
  "active": true
}
```

**Response `201 Created`** — note the uppercased `platform`:

```json
{
  "success": true,
  "message": "Social link created",
  "data": {
    "id": 1,
    "platform": "FACEBOOK",
    "url": "https://www.facebook.com/kurdistanheritageinstitute",
    "labelCkb": "فەیسبووک",
    "labelKmr": "Facebook",
    "displayOrder": 1,
    "active": true
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `platform` or `url` blank, `platform` longer than 60, `url` longer than 2000 |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | — | Insufficient role or anonymous |
| `409` | `CONFLICT` | A row with that `platform` already exists (`uk_social_platform`). The message is the generic conflict text — the violated constraint name is not surfaced in the body. |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 409,
  "path": "/api/v1/settings/social",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "CONFLICT",
  "message": "کێشەی تێکچوون هەیە",
  "messageEn": "Conflict",
  "messageKu": "کێشەی تێکچوون هەیە"
}
```

> **Note:** the duplicate is caught by the database, not by the service — there is no
> `existsByPlatform` guard. If you need a friendly "that platform already exists" message, check
> the current list with `GET /api/v1/settings/social?includeInactive=true` before posting.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/settings/social \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "platform": "youtube",
        "url": "https://www.youtube.com/@kurdistanheritage",
        "labelCkb": "یوتیوب",
        "labelKmr": "YouTube",
        "displayOrder": 2
      }'
```

---

## 9. `PUT /api/v1/settings/social/{id}` — Replace a social link

Full replacement. Omitted `labelCkb` / `labelKmr` are cleared; omitted `displayOrder` resets to `0`;
omitted `active` resets to `true`.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `social_links` |

**Request body** — `SocialLinkRequest`, identical to endpoint #8. Changing `platform` to a value
another row already owns fails with `409`.

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Social link updated",
  "data": {
    "id": 1,
    "platform": "FACEBOOK",
    "url": "https://www.facebook.com/khi.erbil",
    "labelCkb": "فەیسبووک",
    "labelKmr": "Facebook",
    "displayOrder": 1,
    "active": false
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `platform` or `url` blank or over length |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | `"Social link not found: 1"` |
| `409` | `CONFLICT` | `platform` collides with another row |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/settings/social/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "platform": "FACEBOOK",
        "url": "https://www.facebook.com/khi.erbil",
        "labelCkb": "فەیسبووک",
        "labelKmr": "Facebook",
        "displayOrder": 1,
        "active": false
      }'
```

---

## 10. `DELETE /api/v1/settings/social/{id}` — Delete a social link

Hard delete. Prefer setting `active: false` via endpoint #9 if you may want the link back — the
platform slot is freed by a delete, and re-creating it later means a new id.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `social_links` |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Social link deleted"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | `"Social link not found: 1"` |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/settings/social/3 \
  -H "Authorization: Bearer $TOKEN"
```

---

## The nav-menu tree

Before endpoints #11–#13, here is the shape you are writing.

The menu is exactly **two levels**:

```
NavMenuItem            (nav_menu_items)   — one hamburger entry, has its own background photo
 └── NavMenuLink[]     (nav_menu_links)   — secondary links listed under it
```

`NavMenuLink` has **no CRUD of its own**. There is no `/api/v1/nav-menu/{id}/links` route. The whole
set is carried on the item request as the `links` array and the server replaces it wholesale:

| `links` in the request | Effect |
|------------------------|--------|
| omitted / `null` | the item's existing links are left completely untouched |
| `[]` | `item.getLinks().clear()` runs and `orphanRemoval = true` deletes every link row |
| a populated array | the old rows are deleted and the array is inserted fresh — **link ids are not preserved** |

The association is `@OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true,
fetch = LAZY)` with `@OrderBy("displayOrder ASC, id ASC")`.

`itemKey` is the stable handle. It is lowercased (`trim().toLowerCase(Locale.ROOT)`) and must be
unique across the table (`uk_nav_item_key`, checked in the service with
`existsByItemKeyIgnoreCase` / `existsByItemKeyIgnoreCaseAndIdNot` before the write). The public
website keys its automatically generated sub-links off this value, so **do not change it after
creation** — rename the labels instead.

---

## 11. `POST /api/v1/nav-menu` — Create a menu item

Creates the item and, if `links` is present, its links in one transaction.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Request body — `NavMenuItemRequest`**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `itemKey` | string | **yes** | `@NotBlank`, `@Size(max = 60)`; unique, lowercased | Stable handle. Do not change after creation. |
| `labelCkb` | string | **yes** | `@NotBlank`, `@Size(max = 200)` | Menu label, Sorani |
| `labelKmr` | string | no | `@Size(max = 200)` | Menu label, Kurmanji |
| `descriptionCkb` | string | no | column `TEXT` | Sub-label under the item, Sorani |
| `descriptionKmr` | string | no | column `TEXT` | Sub-label under the item, Kurmanji |
| `href` | string | **yes** | `@NotBlank`, `@Size(max = 300)` | Target path or URL. Trimmed, otherwise stored verbatim. |
| `imageUrl` | string | no | column `TEXT` | Background photo shown on hover |
| `displayOrder` | integer | no | defaults to `0` | Sort key among items, ascending |
| `active` | boolean | no | defaults to `true` | `false` hides the item from `GET /api/v1/nav-menu` |
| `links` | array | no | `@Valid` — each element is validated | Secondary links. Omit to create the item with none. |

**`links[]` — `NavMenuLinkRequest`**

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `labelCkb` | string | **yes** | `@NotBlank`, `@Size(max = 200)` | Link label, Sorani |
| `labelKmr` | string | no | `@Size(max = 200)` | Link label, Kurmanji |
| `href` | string | **yes** | `@NotBlank`, `@Size(max = 300)` | Target path or URL |
| `displayOrder` | integer | no | **defaults to the element's 1-based index**, not `0` | Sort key within the item |
| `active` | boolean | no | defaults to `true` | `false` hides the link from the default public listing |

```json
{
  "itemKey": "news",
  "labelCkb": "هەواڵەکان",
  "labelKmr": "Nûçe",
  "descriptionCkb": "دوایین هەواڵ و چالاکییەکانی دەزگا",
  "descriptionKmr": "Nûçe û çalakiyên dawî yên saziyê",
  "href": "/news",
  "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/news.jpg",
  "displayOrder": 1,
  "active": true,
  "links": [
    { "labelCkb": "هەموو هەواڵەکان", "labelKmr": "Hemû nûçe", "href": "/news" },
    { "labelCkb": "ڕاگەیاندنەکان", "labelKmr": "Ragihandin", "href": "/news?type=announcement" }
  ]
}
```

**Response `201 Created`**

The response always includes inactive links (`toResponse(saved, true)`).

```json
{
  "success": true,
  "message": "Nav menu item created",
  "data": {
    "id": 1,
    "itemKey": "news",
    "labelCkb": "هەواڵەکان",
    "labelKmr": "Nûçe",
    "descriptionCkb": "دوایین هەواڵ و چالاکییەکانی دەزگا",
    "descriptionKmr": "Nûçe û çalakiyên dawî yên saziyê",
    "href": "/news",
    "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/news.jpg",
    "displayOrder": 1,
    "active": true,
    "links": [
      {
        "id": 11,
        "labelCkb": "هەموو هەواڵەکان",
        "labelKmr": "Hemû nûçe",
        "href": "/news",
        "displayOrder": 1,
        "active": true
      },
      {
        "id": 12,
        "labelCkb": "ڕاگەیاندنەکان",
        "labelKmr": "Ragihandin",
        "href": "/news?type=announcement",
        "displayOrder": 2,
        "active": true
      }
    ]
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `itemKey`, `labelCkb` or `href` blank or over length; any element of `links` failing its own constraints (`fieldErrors[].field` reads e.g. `links[0].href`) |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | — | Insufficient role or anonymous |
| `409` | `CONFLICT` | `itemKey` already in use. Message key `navMenu.itemKey.duplicate`; `details` carries `{ "itemKey": "news" }`. |
| `500` | `INTERNAL_ERROR` | Unexpected server fault |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 409,
  "path": "/api/v1/nav-menu",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "CONFLICT",
  "message": "ئەم کلیلە (itemKey) پێشتر بەکارهاتووە.",
  "messageEn": "Conflict",
  "messageKu": "کێشەی تێکچوون هەیە",
  "details": { "itemKey": "news" }
}
```

**Example**

```bash
curl -s -X POST http://localhost:8080/api/v1/nav-menu \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "itemKey": "gallery",
        "labelCkb": "گەلەری",
        "labelKmr": "Galerî",
        "href": "/gallery",
        "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/gallery.jpg",
        "displayOrder": 2,
        "links": [
          { "labelCkb": "هەموو کۆمەڵەکان", "href": "/gallery" }
        ]
      }'
```

---

## 12. `PUT /api/v1/nav-menu/{id}` — Replace a menu item

Loads the item, re-checks `itemKey` uniqueness against every **other** row
(`existsByItemKeyIgnoreCaseAndIdNot`), applies every scalar field, and — only if `links` is
non-null — replaces the link set.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`
**Content-Type:** `application/json`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `nav_menu_items` |

**Request body** — `NavMenuItemRequest`, identical to endpoint #11. Remember:

- every scalar field is replaced, so omitting `imageUrl` clears it;
- `links` omitted leaves the existing links alone — this is the one field that is *not* replaced by
  omission;
- `links: []` deletes them all;
- a populated `links` array deletes the old rows and inserts new ones with new ids.

Renaming an item without touching its links:

```json
{
  "itemKey": "news",
  "labelCkb": "هەواڵ و ڕاگەیاندن",
  "labelKmr": "Nûçe û ragihandin",
  "href": "/news",
  "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/nav-menu/news.jpg",
  "displayOrder": 1,
  "active": true
}
```

Replacing the link set and hiding one of them:

```json
{
  "itemKey": "news",
  "labelCkb": "هەواڵ و ڕاگەیاندن",
  "href": "/news",
  "displayOrder": 1,
  "active": true,
  "links": [
    { "labelCkb": "هەموو هەواڵەکان", "href": "/news", "displayOrder": 1 },
    { "labelCkb": "ئەرشیفی ٢٠٢٥", "href": "/news?year=2025", "displayOrder": 2, "active": false }
  ]
}
```

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Nav menu item updated",
  "data": {
    "id": 1,
    "itemKey": "news",
    "labelCkb": "هەواڵ و ڕاگەیاندن",
    "href": "/news",
    "displayOrder": 1,
    "active": true,
    "links": [
      { "id": 21, "labelCkb": "هەموو هەواڵەکان", "href": "/news", "displayOrder": 1, "active": true },
      { "id": 22, "labelCkb": "ئەرشیفی ٢٠٢٥", "href": "/news?year=2025", "displayOrder": 2, "active": false }
    ]
  }
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | Same constraints as endpoint #11 |
| `400` | `BAD_REQUEST` | Body is not valid JSON, or a field has the wrong JSON type |
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | No item with that id. Message key `navMenu.not_found`; `details` carries `{ "id": 1 }`. |
| `409` | `CONFLICT` | `itemKey` already used by a different row |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/v1/nav-menu/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "itemKey": "news",
        "labelCkb": "هەواڵ و ڕاگەیاندن",
        "href": "/news",
        "displayOrder": 1,
        "active": true,
        "links": []
      }'
```

---

## 13. `DELETE /api/v1/nav-menu/{id}` — Delete a menu item

Existence is checked with `existsById`, then `deleteById`. Because the association is
`cascade = ALL, orphanRemoval = true`, **every `nav_menu_links` row belonging to the item is deleted
with it**. The background photo in S3 is not removed.

**Auth:** `Authorization: Bearer <token>`, roles `ADMIN`, `SUPER_ADMIN`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | number (`Long`) | yes | Primary key in `nav_menu_items` |

**Response `200 OK`**

```json
{
  "success": true,
  "message": "Nav menu item deleted"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `403` | — | Insufficient role or anonymous |
| `404` | `NOT_FOUND` | Message key `navMenu.not_found`, `details.id` set |
| `500` | `INTERNAL_ERROR` | Unexpected server fault — **and a non-numeric `{id}`**, see the note below |

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/nav-menu/1 \
  -H "Authorization: Bearer $TOKEN"
```

---

## Reference: how a record becomes a featured slide

These toggle endpoints are **not part of this document's endpoint list** — each belongs to its own
content domain and is documented there. They are described here because they are the only way to
change what
[`GET /api/v1/featured`](../external/SITE_SETTINGS_API.md#1-get-apiv1featured--homepage-hero-rail)
returns, and because the dashboard's "Featured" screen drives all of them from one place.

### The two families

**Hero carousel** — these compete for the `maxFeaturedSlides` budget you set with endpoint #7:

| Toggle endpoint | `@PreAuthorize` | Service method |
|-----------------|-----------------|----------------|
| `PATCH /api/v1/news/{id}/featured` | `hasRole('ADMIN')` | `setNewsFeatured` |
| `PATCH /api/v1/projects/{id}/featured` | `hasRole('ADMIN')` | `setProjectFeatured` |
| `PATCH /api/v1/writings/{id}/featured` | `hasRole('ADMIN')` | `setWritingFeatured` |
| `PATCH /api/v1/videos/{id}/featured` | `hasRole('ADMIN')` | `setVideoFeatured` |
| `PATCH /api/v1/sound-tracks/{id}/featured` | `hasRole('ADMIN')` | `setSoundTrackFeatured` |
| `PATCH /api/v1/image-collections/{id}/featured` | `hasRole('ADMIN')` | `setImageCollectionFeatured` |
| `PATCH /api/v1/donations/settings/featured` | `hasRole('ADMIN')` | `setDonationFeatured` |

**Page-level highlight** — these take no share of the budget and there is no cap on how many may be
on at once:

| Toggle endpoint | `@PreAuthorize` | Service method | What it highlights |
|-----------------|-----------------|----------------|--------------------|
| `PATCH /api/v1/about/{id}/featured` | `hasAnyRole('ADMIN','SUPER_ADMIN')` | `setAboutFeatured` | The lead record on `/about` |
| `PATCH /api/v1/services/{id}/featured` | `hasAnyRole('ADMIN','SUPER_ADMIN')` | `setServiceFeatured` | The rail inside the `/services` hero |

> **Note:** the seven carousel toggles use `hasRole('ADMIN')` and there is **no `RoleHierarchy` bean
> in this application**, so a `SUPER_ADMIN` account is refused by all seven. For
> `PATCH /api/v1/donations/settings/featured` this directly contradicts `SecurityConfig`, whose
> `PATCH /api/v1/donations/settings/featured` rule grants `ADMIN` **and** `SUPER_ADMIN` — the
> annotation is the narrower of the two and wins. The two page-level toggles do accept both roles.
> The behaviour described here is what the code does today.

### Request body — `SiteContentDtos.FeaturedRequest`

All nine handlers take `@RequestBody SiteContentDtos.FeaturedRequest` — **without `@Valid`**.

> **Note:** `FeaturedRequest` declares `@NotBlank` on `type`, `slug`, `title`, `description` and
> `imageUrl`. Because no handler annotates the parameter with `@Valid`, **none of those constraints
> is ever evaluated** and none of those five fields is read by any of the toggle service methods.
> Send only the three fields below. (The same file also declares a `FeatureToggleRequest` DTO
> carrying exactly `featured` + `featuredOrder`; it is referenced by nothing in the codebase.)

| Field | Type | Required | Behaviour |
|-------|------|----------|-----------|
| `featured` | boolean | no | `null` or omitted is treated as **`true`**. An empty body `{}` therefore *features* the record. |
| `featuredOrder` | integer | no | Sort key for the rail: lower shows first, `null` sorts last. Cleared to `null` whenever `featured` resolves to `false`. |
| `featureImageUrl` | string | no | Tri-state. Omitted leaves the stored value alone (so a plain feature/unfeature call never disturbs it); `""` clears it and the slide falls back to the record's cover; a value is trimmed and stored. Survives an unfeature. |

Every handler returns **`204 No Content`** with an empty body, except
`PATCH /api/v1/donations/settings/featured`, which returns
`ApiResponse<DonationSettingsResponse>` so the dashboard can re-render the donation screen in one
call.

### The rules each toggle enforces

1. **Load or 404.** `EntityNotFoundException` → `404 NOT_FOUND`, message `"News not found: 42"` and
   so on.
2. **Cap check (carousel family only).** If the call is turning `featured` on **and** the record is
   not already featured, the service compares `countAllFeatured()` against `maxFeaturedSlides` and
   throws `IllegalStateException` when the budget is full → **`400 BAD_REQUEST`** with
   `message: "Maximum of 7 featured slides allowed across all content. Unfeature one first."`.
   Re-ordering an already-featured record skips the check, because it is already inside the total.
   `countAllFeatured()` sums the six publication tables plus the donation singleton — About and
   Service rows are excluded on purpose.
3. **Image requirement (About, Service, Donation only).** Featuring an About page with no
   `featureImageUrl`, a Service with neither `featureImageUrl` nor any usable gallery picture, or
   the Donation page with neither `featureImageUrl` nor `heroImageUrl`, throws
   `IllegalArgumentException` → **`400 BAD_REQUEST`**. The six publication toggles have **no** such
   guard, which is why a cover-less article can be "featured" and still never appear on the
   homepage.
4. **Donation preconditions.** `setDonationFeatured` throws `IllegalStateException` → `400` with
   `"Donation settings have not been saved yet — save the donation page before featuring it."`
   when no `donation_settings` row exists.
5. **Cache eviction.** `setServiceFeatured` is annotated `@CacheEvict(value = "services",
   allEntries = true)` because the Services read paths are cached. None of the others evicts
   anything — nothing in this domain is cached.

```bash
# Feature news 42 as the second slide
curl -s -X PATCH http://localhost:8080/api/v1/news/42/featured \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "featured": true, "featuredOrder": 2 }'

# Unfeature it (featuredOrder is cleared, featureImageUrl is kept)
curl -s -X PATCH http://localhost:8080/api/v1/news/42/featured \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "featured": false }'
```

---

## Enums and controlled vocabularies

Nothing in this domain is backed by a Java `enum`. The two identifier fields that behave like one
are free text with a uniqueness constraint:

| Field | Storage rule | Uniqueness | Values in use |
|-------|--------------|------------|---------------|
| `SocialLink.platform` | `trim().toUpperCase(Locale.ROOT)`, `varchar(60)` | `uk_social_platform` | `FACEBOOK`, `INSTAGRAM`, `YOUTUBE`, `X`, `TELEGRAM` — not a closed list |
| `NavMenuItem.itemKey` | `trim().toLowerCase(Locale.ROOT)`, `varchar(60)` | `uk_nav_item_key` | `news`, `projects`, `writings`, `videos`, `sound`, `gallery`, `about`, `services`, `donate`, `contact` — not a closed list |

Bilingual fields use the `…Ckb` / `…Kmr` suffix convention rather than a `language` discriminator.
`CKB` is Central Kurdish (Sorani, Arabic script, RTL) and `KMR` is Northern Kurdish (Kurmanji,
Latin script, LTR) in the platform-wide `Language` enum; this domain never returns that enum.

`ErrorCode` values this domain can produce: `VALIDATION_ERROR`, `BAD_REQUEST`, `NOT_FOUND`,
`FORBIDDEN`, `CONFLICT`, `METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`.

---

## Notes & gotchas

**Full-replacement `PUT` is the biggest footgun here.** Endpoints #2, #5, #9 and #12 rewrite every
scalar field from the request. A dashboard form that only submits the fields the user touched will
silently blank the rest. `PUT /api/v1/site-settings` is the deliberate exception.

**`active: false` is not access control.** The public read endpoints expose hidden rows on request:
`GET /api/v1/settings/social?includeInactive=true` and `GET /api/v1/nav-menu?includeInactive=true`
are `permitAll`, and `GET /api/v1/nav-menu/{id}` returns an inactive item and all of its inactive
links unconditionally. Use `active` for "not shown on the site", never for "not ready to be seen".

**Team and partner rows have no `includeInactive` read.** `GET /api/v1/about/team` and
`GET /api/v1/about/partners` call `findAllByActiveTrue…` with no parameter, so once you set
`active: false` on a member the dashboard cannot list them back through the API — only a direct
`PUT` by id (which you can no longer discover from the list) or a database query will bring them
back. Prefer deleting over deactivating for these two, or keep the ids.

**Column lengths are enforced by PostgreSQL, not by bean validation, for team and partner rows.**
`TeamMemberRequest` and `PartnerRequest` carry no `@Size`. Over-length input becomes a
`409 CONFLICT` from `DataIntegrityViolationException` rather than a `400` with `fieldErrors`.
The nav-menu, social-link and site-settings DTOs *do* carry `@Size` / `@Min` / `@Max`.

**`featured_items` is a dead table.** The `FeaturedItem` entity and `FeaturedItemRepository` are
compiled and the table is created by `ddl-auto: update`, but no service injects the repository and
no controller maps to it. `SecurityConfig` still reserves `POST` / `PUT` / `DELETE` on
`/api/v1/featured/**` for `ADMIN`/`SUPER_ADMIN`; those verbs have no handler and answer
`405 METHOD_NOT_ALLOWED` after passing the role check. The rail is driven entirely by the `featured`
columns on the content tables.

**No S3 cleanup anywhere in this domain.** Deleting a team member, partner, social link or nav menu
item leaves any uploaded image in the bucket. Upload through `POST /api/v1/media/upload`
(`ADMIN`/`SUPER_ADMIN`, multipart, max file and max request both 1 GB) and treat the returned URL as
a permanent reference.

**Nothing here is cached.** Neither `SiteContentService` nor `NavMenuService` declares
`@Cacheable`, so a write is visible to the public reads immediately. Redis (key prefix `khi:`,
default TTL 10 minutes) backs other domains.

**`GET /api/v1/nav-menu` avoids the N+1.** All three read methods on `NavMenuItemRepository` carry
`@EntityGraph(attributePaths = "links")`, which matters because `spring.jpa.open-in-view` is
`false` — a lazily loaded list touched after the transaction closes would throw.

**A non-numeric `{id}` answers `500`, not `400`.** `GlobalExceptionHandler` is a plain
`@RestControllerAdvice` with an `@ExceptionHandler(Exception.class)` catch-all. Because
`ExceptionHandlerExceptionResolver` runs before Spring's `DefaultHandlerExceptionResolver`, that
catch-all intercepts `MethodArgumentTypeMismatchException` — which Spring would otherwise render as
`400` — and turns it into `500 INTERNAL_ERROR` with `"An unexpected error occurred. Please try
again later."`. `PUT /api/v1/about/team/abc` is therefore a `500`. Verbs
(`HttpRequestMethodNotSupportedException` → `405`), unmapped paths
(`NoResourceFoundException` → `404`) and unreadable bodies
(`HttpMessageNotReadableException` → `400`) *are* handled explicitly and behave normally.

**Two Jackson mappers exist; only one of them serializes your responses.**
`JacksonConfig` declares a hand-built Jackson 2 `com.fasterxml.jackson.databind.ObjectMapper` bean
carrying a `DeserializationProblemHandler` that tolerates a stray `id` property and rejects every
other unknown field. Spring Boot 4.0.2 auto-configures a **Jackson 3** mapper for HTTP and only
falls back to the Jackson 2 converter when Jackson 3 is absent or
`spring.http.converters.preferred-json-mapper` says otherwise — neither is the case here. The
practical consequences: the `spring.jackson.*` properties in `application.yaml` do govern
request and response JSON, unknown request fields are ignored rather than rejected, and the
`UnrecognizedPropertyException` handler in `GlobalExceptionHandler` (a Jackson 2 type) never
fires. The Jackson 2 bean is still injected by name in several multipart controllers for manual
`@RequestPart` parsing, so it is not dead code — it just does not govern the endpoints in this
file.

**Timestamps.** `SiteSettings.updatedAt` is the only timestamp this domain writes, and it is
stamped by a `@PrePersist` / `@PreUpdate` hook calling `LocalDateTime.now()` rather than by
Hibernate's `@CreationTimestamp` / `@UpdateTimestamp`. Database timestamps are stored UTC
(`hibernate.jdbc.time_zone=UTC`); Jackson is configured with time zone `Asia/Baghdad`, date format
`yyyy-MM-dd` and date-time format `yyyy-MM-dd HH:mm:ss`.

**`X-Trace-Id` round-trips.** `TraceIdFilter` reads an incoming `X-Trace-Id` header (or generates
one) and stamps it into `ApiErrorResponse.traceId`. Send your own correlation id and quote it when
reporting a bug.

**Error messages are English in practice.** `messageEn` resolves against `Locale.ENGLISH` and
`messageKu` against locale `ku`. Neither bundle is loadable at runtime — the `classpath:i18n/messages`
basename only picks up `messages_ckb.properties` and `messages_kmr.properties` — so both fall back
to the hard-coded strings in `GlobalExceptionHandler.fallbackByCode()` (`"Conflict"`,
`"Resource not found"`, …). Sending `Accept-Language: ckb` or `Accept-Language: kmr` *does* swap the
top-level `message` to the Sorani or Kurmanji phrase from the bundle, which is why the `409` example
above shows the specific `navMenu.itemKey.duplicate` text in `message` but the generic
`"Conflict"` in `messageEn`. Key your UI off `code`, not off these strings.

**Live spec.** Swagger UI is at `/swagger-ui.html`, the raw document at `/v3/api-docs`. Every
endpoint in this file is in the `all` group (`/v3/api-docs/all`), tagged **Public Site** or
**Nav Menu**. The springdoc `internal` group only matches `/api/auth/logout`, `/api/auth/logout-all`,
`/api/auth/sessions/**`, `/api/user/**` and `/api/v1/media/**`, so **none** of these endpoints
appears in `/v3/api-docs/internal`; the team and partner writes appear in the `public` group instead,
because that group matches `/api/v1/about/**` wholesale. Group membership is not an authorization
statement — `SecurityConfig` and the `@PreAuthorize` annotations are the only authority.

**Servers.** Local development runs on `http://localhost:8080`; production is deployed on Railway.

---

## Related documentation

- Counterpart (public reads): [`../external/SITE_SETTINGS_API.md`](../external/SITE_SETTINGS_API.md)
- About pages: [`./ABOUT_API.md`](./ABOUT_API.md)
- Services: [`./SERVICE_API.md`](./SERVICE_API.md)
- Donations (including `PATCH /api/v1/donations/settings/featured`): [`./DONATION_API.md`](./DONATION_API.md)
- Content whose toggles feed the rail: [`./NEWS_API.md`](./NEWS_API.md),
  [`./PROJECT_API.md`](./PROJECT_API.md), [`./WRITING_API.md`](./WRITING_API.md),
  [`./VIDEO_API.md`](./VIDEO_API.md), [`./SOUNDTRACK_API.md`](./SOUNDTRACK_API.md),
  [`./IMAGE_COLLECTION_API.md`](./IMAGE_COLLECTION_API.md)
- Authentication and tokens: [`../external/AUTH_API.md`](../external/AUTH_API.md),
  [`./AUTH_SESSIONS_API.md`](./AUTH_SESSIONS_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
