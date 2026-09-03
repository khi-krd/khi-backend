# User Profile API — Internal (Authenticated)

Self-service account management for whoever holds the JWT: read your profile, rename yourself, change
your password, upload or drop your avatar, and delete your account. Every route acts on the caller's own
row — there is no user-id parameter anywhere and no way to touch another account.

| | |
|---|---|
| **Base path** | `/api/user` (singular — **not** `/api/users`) |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controller** | `src/main/java/ak/dev/khi_backend/user/api/UserProfileAPI.java` |
| **Service** | `src/main/java/ak/dev/khi_backend/user/service/UserProfileService.java` (plus `UserValidator`, `S3Service`) |
| **Entities** | `User`, `Session` |
| **Response envelope** | Bare DTO — `UserResponseDTO` for four endpoints, `Map<String,String>` for the password change, empty body for account deletion. No `ApiResponse<T>` wrapper. |
| **Verified against source** | 2026-08-26 |

**Authorization (from `SecurityConfig`)**

```java
.requestMatchers("/api/user/**").authenticated()
```

Any authenticated role — `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`. No handler carries `@PreAuthorize`,
so nothing narrows that rule.

**How the caller is identified.** Every handler takes Spring's `Authentication` and uses
`auth.getName()`, which is the JWT `sub` claim (the username), then loads the row with
`userRepository.findByUsername(...)`. The controller javadoc explains the choice: it survives both a
`String` principal and a `UserDetails` principal, whereas `@AuthenticationPrincipal UserDetails` silently
injects `null` for the former.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `GET` | `/api/user/me` | JWT | Any authenticated | Read the current user's profile. |
| 2 | `PUT` | `/api/user/profile` | JWT | Any authenticated | Change display name and/or username. |
| 3 | `PUT` | `/api/user/password` | JWT | Any authenticated | Change password (requires the current one). |
| 4 | `POST` | `/api/user/profile-image` | JWT | Any authenticated | Upload or replace the avatar (multipart, S3). |
| 5 | `DELETE` | `/api/user/profile-image` | JWT | Any authenticated | Remove the avatar and delete it from S3. |
| 6 | `DELETE` | `/api/user/account` | JWT | Any authenticated | Permanently delete the account and its sessions. |

### `UserResponseDTO` — returned by endpoints 1, 2, 4 and 5

| Field | Type | Description |
|-------|------|-------------|
| `userId` | number (int64) | `users_tbl.user_id`. |
| `name` | string | Display name, max 120 chars. |
| `username` | string | Unique login handle. |
| `email` | string | Unique, lower-cased. **Not editable through this API.** |
| `role` | string enum | `GUEST` \| `EMPLOYEE` \| `ADMIN` \| `SUPER_ADMIN`. Read-only here. |
| `pincode` | number (int64) \| absent | Optional numeric field captured at registration. |
| `isActivated` | boolean | Account flag. Read-only here, and not enforced at login — see the note in [`../external/AUTH_API.md`](../external/AUTH_API.md#notes--gotchas). |
| `profileImage` | string \| absent | Full `https://` S3 URL when set through endpoint 4; a legacy relative path (`uploads/profile-images/<uuid>.jpg`) for avatars supplied at registration. |
| `createdAt` | string (ISO-8601 UTC) | |
| `updatedAt` | string (ISO-8601 UTC) | Touched by endpoints 2, 3, 4 and 5. |
| `passwordExpiryDate` | string (ISO-8601 UTC) | 90 days after the last password change; login is refused once it passes. |

Nulls are omitted globally (`spring.jackson.default-property-inclusion: non_null`), so an absent key means
"null", not "unchanged". The password hash is never present: `User.password` is `@JsonIgnore` and the DTO
has no such field. `resetToken`, `resetTokenExpiration`, `lockTime`, `failedAttempts` and `isLocked` are
likewise never exposed.

---

## 1. `GET /api/user/me` — Read the current profile

Straight read of the row behind the token. No side effects, no caching.

**Auth:** `Authorization: Bearer <token>` or the `${JWT_COOKIE_NAME}` cookie — any authenticated role
**Produces:** `application/json`

**Path parameters** — none.
**Query parameters** — none.
**Request body** — none.

**Response `200 OK`** — `UserResponseDTO`

```json
{
  "userId": 42,
  "name": "ئاکار ئەرکان",
  "username": "akar_arkan",
  "email": "akar.arkan@khi.krd",
  "role": "ADMIN",
  "pincode": 442001,
  "isActivated": true,
  "profileImage": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/1f4c9e77-3a20-4c1b-8de5-2b6f0a91c4d3-user_profile_images_akar-avatar.jpg",
  "createdAt": "2026-02-11T07:35:44Z",
  "updatedAt": "2026-08-26T06:20:03Z",
  "passwordExpiryDate": "2026-11-24T06:20:03Z"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | — | Expired or revoked token — `JWTAuthenticationFilter` writes `{"error":"TOKEN_EXPIRED"\|"TOKEN_REVOKED", ...}` itself. A request with no token at all gets Spring Security's default instead: the `JwtAuthenticationEntryPoint` bean is never registered on the chain. |
| `403` | — | `{"error":"INVALID_TOKEN","message":"Invalid token"}` from the filter. |
| `404` | `NOT_FOUND` | `ApiErrorResponse` — the `sub` claim does not match any `users_tbl.username`. Happens after a username change (see endpoint 2) or if the row was deleted out of band. |

**Example**

```bash
curl -s http://localhost:8080/api/user/me \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 2. `PUT /api/user/profile` — Update name and/or username

Partial update: only the fields you send are applied. `email`, `role`, `pincode` and `isActivated` cannot
be changed here. A username change is checked against `users_tbl.username` for uniqueness before it is
applied, and `updatedAt` is stamped on every successful call.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Content-Type:** `application/json`
**Produces:** `application/json`

**Request body** — `UpdateProfileRequestDTO`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `username` | string | No | `@Size(min = 3, max = 80)`, `@Pattern("^$\|^[A-Za-z0-9_]+$")` | New login handle. Letters, digits and underscore only. Must not already be taken. |
| `name` | string | No | `@Size(max = 120)` | New display name. An empty string is accepted and stored as empty. |

```json
{
  "username": "akar_arkan_khi",
  "name": "ئاکار ئەرکان ڕەسوڵ"
}
```

**Response `200 OK`** — the full updated `UserResponseDTO` (same shape as endpoint 1).

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | `username` shorter than 3 or longer than 80, contains a character outside `[A-Za-z0-9_]`, or `name` longer than 120. `fieldErrors[]` names the field. |
| `409` | `CONFLICT` | The requested username belongs to another account. `message` resolves from the bundle (`error.user.already_exists`), and `details` carries `hint` and `suggestion`. |
| `404` | `NOT_FOUND` | The token's `sub` matches no user. |
| `401` / `403` | — | Standard filter/entry-point responses. |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 409,
  "path": "/api/user/profile",
  "method": "PUT",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "CONFLICT",
  "message": "Username or email is already registered.",
  "messageEn": "Username or email is already registered.",
  "messageKu": "Username or email is already registered.",
  "details": {
    "hint": "Try a different username or email address.",
    "suggestion": "If this is your account, try logging in or use the 'Forgot password' option."
  }
}
```

> **Note — sending `"username": ""` is rejected, not ignored.** The `@Pattern` allows an empty string but
> `@Size(min = 3)` does not, so `""` produces `400 VALIDATION_ERROR`. The service also guards with
> `!dto.getUsername().isBlank()`, which is therefore unreachable. To leave a field unchanged, **omit the
> key** (send `null`), do not send an empty string.

> **Note — changing your username breaks the token you are holding (real behaviour).** The JWT `sub`
> claim still carries the old username, and `JWTAuthenticationFilter` resolves the principal with
> `userDetailsService.loadUserByUsername(sub)`. After the rename that lookup throws
> `UsernameNotFoundException` inside the filter, where the outer `catch (Exception)` turns it into
> `500 {"error":"SERVER_ERROR","message":"Internal server error"}` — on **every** subsequent request,
> including `/api/auth/logout`. The old `sessions` row also stays `is_active = true` until it expires.
> The client must force a fresh login with the new username immediately after a successful rename;
> treat the `200` from this endpoint as "you have been signed out".

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/user/profile \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ئاکار ئەرکان ڕەسوڵ"}'
```

---

## 3. `PUT /api/user/password` — Change password

Verifies the current password with BCrypt, checks `newPassword == confirmPassword`, re-checks the length
rule, refuses reuse of the current password, then stores the new BCrypt hash, pushes
`passwordExpiryDate` 90 days out and stamps `updatedAt`.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Content-Type:** `application/json`
**Produces:** `application/json`

**Request body** — `ChangePasswordRequestDTO`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `currentPassword` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | The password in use right now. |
| `newPassword` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | Must differ from `currentPassword`. No complexity rule — `UserValidator.validatePassword` only re-checks the 6–128 length. |
| `confirmPassword` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | Must equal `newPassword`; compared in the service layer. |

```json
{
  "currentPassword": "Hewler2026",
  "newPassword": "Slemani2026",
  "confirmPassword": "Slemani2026"
}
```

**Response `200 OK`**

```json
{
  "message": "Password updated successfully"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `VALIDATION_ERROR` | Any field blank or outside 6–128 characters. |
| `401` | `UNAUTHORIZED` | `currentPassword` is wrong (`BadCredentialsException`). `message` resolves to the bundle text for `error.user.bad_credentials`, not to the service's Sorani string. |
| `400` | `BAD_REQUEST` | `newPassword != confirmPassword` — `details.reason` = `"New password and confirm password do not match."` |
| `400` | `BAD_REQUEST` | The new password equals the current one — `details.reason` = `"New password must be different from your current password."` |
| `404` | `NOT_FOUND` | The token's `sub` matches no user. |
| `401` / `403` | — | Standard filter/entry-point responses. |

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/user/password",
  "method": "PUT",
  "traceId": "8c2d5a01-4f6b-49ce-9a77-1d3e6b0f2a55",
  "code": "BAD_REQUEST",
  "message": "Bad request.",
  "messageEn": "Bad request.",
  "messageKu": "Bad request.",
  "details": {
    "reason": "New password must be different from your current password."
  }
}
```

> **Read `details.reason`, not `message`.** `GlobalExceptionHandler.handleIllegalArgument` resolves
> `message`/`messageEn`/`messageKu` from the `error.bad_request` bundle key, which exists — so all three
> read "Bad request." and the actual cause survives only in `details.reason`.

> **Note — a password change does not revoke sessions.** No `sessions` row is touched and nothing is
> blacklisted, so tokens issued before the change (including on other devices) keep working until they
> expire. Chain a call to
> [`POST /api/auth/logout-all`](AUTH_SESSIONS_API.md#2-post-apiauthlogout-all--log-out-everywhere)
> if the intent is "someone may know my old password".

**Example**

```bash
curl -s -X PUT http://localhost:8080/api/user/password \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"Hewler2026","newPassword":"Slemani2026","confirmPassword":"Slemani2026"}'
```

---

## 4. `POST /api/user/profile-image` — Upload or replace the avatar

Validates the file, deletes the previous avatar from S3 when it is one of ours, uploads the new bytes,
and stores the resulting public `https://` URL in `users_tbl.profile_image`. Replacing an avatar is a
single call — there is no separate "delete then upload" sequence.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Content-Type:** `multipart/form-data`
**Produces:** `application/json`

**Parts**

| Part | Content-Type | Required | Description |
|------|--------------|----------|-------------|
| `file` | `image/jpeg`, `image/png`, `image/gif`, `image/webp` | Yes | The avatar. Maximum **5 MB** (`UserProfileService.MAX_FILE_SIZE`). Any other content type is rejected. This endpoint has no JSON part. |

**Where the file lands.** `S3Service.upload(bytes, filename, contentType)` routes by content type, so
every avatar goes to the shared `images/` folder of the bucket. The service prefixes the original file
name with `user_profile_images_` (after replacing anything outside `[A-Za-z0-9._-]` with `_`) so avatars
remain recognisable in the S3 console:

```
bucket : s3-khiwebsite            (region us-east-1)
key    : khi-web-folders/images/<uuid>-user_profile_images_<sanitized-filename>
url    : https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/<uuid>-user_profile_images_<sanitized-filename>
```

> **Note — the `user_profile_images/` folder does not exist.** `UserProfileService` declares
> `S3_PROFILE_FOLDER = "user_profile_images"` and its javadoc discusses using it as a folder, but the code
> only uses it as a filename prefix; the object still lands under `khi-web-folders/images/`. Do not build
> S3 lifecycle rules or console filters around a `user_profile_images/` prefix.

**Response `200 OK`** — the full updated `UserResponseDTO`, with `profileImage` set to the new URL.

```json
{
  "userId": 42,
  "name": "ئاکار ئەرکان",
  "username": "akar_arkan",
  "email": "akar.arkan@khi.krd",
  "role": "ADMIN",
  "pincode": 442001,
  "isActivated": true,
  "profileImage": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/9b7e1c04-52af-4a6d-b0c8-7e91f3d2a418-user_profile_images_akar-avatar.jpg",
  "createdAt": "2026-02-11T07:35:44Z",
  "updatedAt": "2026-08-26T09:41:12Z",
  "passwordExpiryDate": "2026-11-24T06:20:03Z"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `BAD_REQUEST` | Empty or missing file — `details.reason` = `"فایلەکە بەتاڵە"` (the file is empty). |
| `400` | `BAD_REQUEST` | Larger than 5 MB — `details.reason` = `"قەبارەی وێنە دەبێت لە ٥ مێگابایت کەمتر بێت"`. |
| `400` | `BAD_REQUEST` | Unsupported content type — `details.reason` = `"تەنها JPEG, PNG, GIF, WebP قبوڵ دەکرێت"`. |
| `400` | `BAD_REQUEST` | Malformed multipart body or missing `file` part (`MultipartException`); `details.hint` explains the expected part names. |
| `413` | `PAYLOAD_TOO_LARGE` | Only above the servlet limit of **1 GB** (`spring.servlet.multipart.max-file-size`). The 5 MB service check fires first for anything smaller, so realistic oversize uploads return `400`, not `413`. Note that the `413` message text hard-codes "5 MB" regardless. |
| `500` | `INTERNAL_ERROR` | `IOException` while reading the part, or an S3 failure surfaced as a `RuntimeException` (`"بارکردنی فایل سەرکەوتوو نەبوو"`). |
| `404` | `NOT_FOUND` | The token's `sub` matches no user. |
| `401` / `403` | — | Standard filter/entry-point responses. |

> **The three validation messages above are Sorani-only strings hard-coded in the service.** They arrive
> in `details.reason`; `message`/`messageEn`/`messageKu` all read the generic "Bad request." bundle text.
> Render `details.reason` or map on the HTTP status plus your own copy.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/user/profile-image \
  -H "Authorization: Bearer $KHI_TOKEN" \
  -F 'file=@./akar-avatar.jpg;type=image/jpeg'
```

---

## 5. `DELETE /api/user/profile-image` — Remove the avatar

Deletes the current avatar object from S3 (best effort) and sets `users_tbl.profile_image` to `NULL`.
Safe to call when there is no avatar — it is a no-op that still returns the profile and stamps `updatedAt`.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Produces:** `application/json`

**Path parameters** — none.
**Query parameters** — none.
**Request body** — none.

**Response `200 OK`** — the updated `UserResponseDTO`. Because null properties are omitted, `profileImage`
is **absent from the JSON**, not `null`:

```json
{
  "userId": 42,
  "name": "ئاکار ئەرکان",
  "username": "akar_arkan",
  "email": "akar.arkan@khi.krd",
  "role": "ADMIN",
  "pincode": 442001,
  "isActivated": true,
  "createdAt": "2026-02-11T07:35:44Z",
  "updatedAt": "2026-08-26T09:52:37Z",
  "passwordExpiryDate": "2026-11-24T06:20:03Z"
}
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | The token's `sub` matches no user. |
| `401` / `403` | — | Standard filter/entry-point responses. |

An S3 deletion failure never fails the request: `S3Service.deleteFile` logs and swallows the error, so the
database is cleared even if the object survives in the bucket.

> **Note — legacy local-disk avatars are only unlinked, never deleted.** `deleteS3Image` acts only when
> the stored value starts with `http` **and** `S3Service.isOurS3Url(...)` matches. Avatars supplied at
> registration are relative paths (`uploads/profile-images/<uuid>.jpg`), so they are silently skipped and
> the file is orphaned on disk. See the registration note in
> [`../external/AUTH_API.md`](../external/AUTH_API.md#2-post-apiauthregister-with-image--register-with-an-avatar-multipart).

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/user/profile-image \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 6. `DELETE /api/user/account` — Delete the account

Permanent and immediate. In order, `UserProfileService.deleteAccount`:

1. Deletes the avatar object from S3 (skipped for legacy local paths).
2. Deletes **every** `sessions` row for the user — active and historical alike.
3. Deletes the `users_tbl` row.

There is no confirmation step, no password re-entry, no soft delete and no undo.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Produces:** empty body

**Path parameters** — none.
**Query parameters** — none.
**Request body** — none.

**Response `204 No Content`** — no body.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | `NOT_FOUND` | The token's `sub` matches no user (for example, calling it twice). |
| `401` / `403` | — | Standard filter/entry-point responses. |
| `500` | `INTERNAL_ERROR` | Database failure during the delete. |

**What happens to the token afterwards.** The JWT is not blacklisted, but its session row is gone, so
`TokenService.isTokenBlacklisted` returns `true` and the next request is answered
`401 {"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}` with the auth cookie
cleared by the filter. This endpoint itself does **not** send a `Set-Cookie` clear header, so clear any
client-side state yourself.

**What is not cleaned up.** `token_blacklist` rows for the deleted user remain (they carry no foreign key).
No content is reassigned or removed — no entity in `ak.dev.khi_backend.khi_app` references `User`, so
projects, news, videos and the rest are unaffected by the deletion.

**Example**

```bash
curl -s -i -X DELETE http://localhost:8080/api/user/account \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## Enums used by this API

### `Role` — `UserResponseDTO.role` (read-only on every endpoint here)

| Value | Meaning |
|-------|---------|
| `GUEST` | Default for self-registered accounts; read-only outside its own profile. |
| `EMPLOYEE` | Content contributor: create and update content, no deletes. |
| `ADMIN` | Full content and site administration, including deletes and the media pipeline. |
| `SUPER_ADMIN` | Same permissions as `ADMIN`. |

Nothing on `/api/user/**` can change the role. The `Language` enum (`CKB` / `KMR`) is not used by this
domain — user records are not localised.

---

## Notes & gotchas

- **`/api/user` is singular.** `/api/users/**` is reserved for `SUPER_ADMIN` in `SecurityConfig` but no
  controller maps it; see the discrepancy callout in
  [`../external/AUTH_API.md`](../external/AUTH_API.md#role-model).
- **Email is immutable through this API.** `UpdateProfileRequestDTO` has no `email` field, and no exposed
  endpoint calls `UserService.updateUser` (the DTO that does accept `email`, `role` and `isActivated` is
  unreachable over HTTP in this build).
- **No caching.** Nothing in `UserProfileService` is `@Cacheable`/`@CacheEvict`; every call hits
  PostgreSQL, and the whole service is `@Transactional`.
- **Two different avatar pipelines exist.** Registration writes to local disk; this API writes to S3. A
  user who registered with an image will keep an unusable relative path until they call endpoint 4 once.
- **Upload limits differ by layer.** Service check: 5 MB and four image types. Servlet check:
  1 GB file / 1 GB request, 2 MB in-memory threshold. The 413 handler's message hard-codes 5 MB.
- **Timestamps** are stored UTC and serialised as ISO-8601 `Instant` strings ending in `Z`;
  `spring.jackson.serialization.indent_output: true` means responses are pretty-printed.
- **`X-Trace-Id`** is echoed on every response by `TraceIdFilter`, and appears as `traceId` inside every
  `ApiErrorResponse` — quote it in bug reports.
- **`messageKu` is usually not Kurdish.** `GlobalExceptionHandler` resolves it with locale `ku`, for which
  no bundle exists (`messages_en`, `messages_ckb`, `messages_kmr` are the shipped files). Send
  `Accept-Language: ckb` or `kmr` and read `message` instead.
- **Live spec.** These paths are in the Swagger `internal` group: `/swagger-ui.html`,
  `/v3/api-docs?group=internal`.

---

## Related documentation

- Sibling (logout + session revocation): [`AUTH_SESSIONS_API.md`](AUTH_SESSIONS_API.md)
- Counterpart (registration, login, password reset): [`../external/AUTH_API.md`](../external/AUTH_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
