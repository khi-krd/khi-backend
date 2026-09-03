# Authentication API — External (Public)

Everything an anonymous visitor needs to obtain a JWT: self-registration (with or without an avatar),
login by username **or** email, and the two-step password-reset flow. These are the only `/api/auth/**`
routes that `SecurityConfig` marks `permitAll()`; every other auth route (logout, session management)
requires a token and is documented in
[`../internal/AUTH_SESSIONS_API.md`](../internal/AUTH_SESSIONS_API.md).

| | |
|---|---|
| **Base path** | `/api/auth` |
| **Audience** | Public website / dashboard login screen (no auth) |
| **Controller** | `src/main/java/ak/dev/khi_backend/user/api/UserAPI.java` |
| **Services** | `src/main/java/ak/dev/khi_backend/user/service/UserService.java`, `TokenService.java`, `UserValidator.java`, `LoggingPasswordResetDeliveryService.java` |
| **JWT** | `src/main/java/ak/dev/khi_backend/user/jwt/JwtTokenProvider.java`, `JwtCookieService.java`, `JWTAuthenticationFilter.java` |
| **Entities** | `User`, `Session`, `TokenBlacklist` |
| **Response envelope** | `Token` (`{ "token", "response" }`) for register/login; **plain text** for the reset endpoints. No `ApiResponse<T>` wrapper here. |
| **Verified against source** | 2026-08-26 |

---

## How authentication works

### 1. Token format and claims

Tokens are signed with **HMAC-SHA256** (`Algorithm.HMAC256(${JWT_SECRET})`) by
`JwtTokenProvider.generateToken(...)`. Decoded payload of a real token:

```json
{
  "iss": "Akar Dev",
  "aud": "User Management By Akar Arkan Rasul",
  "iat": 1787654062,
  "sub": "aram_karim",
  "id": 42,
  "ROLE": "GUEST",
  "authorities": ["user:read", "ROLE_GUEST"],
  "sessionId": "6f0a1c8e-7d43-4a11-9c2b-51e0d9a4bb37",
  "exp": 1787740462
}
```

| Claim | Type | Meaning |
|-------|------|---------|
| `iss` | string | Always `Akar Dev` — verified on every request. |
| `aud` | string | Always `User Management By Akar Arkan Rasul` — verified on every request. |
| `sub` | string | The user's `username` (never the email). |
| `id` | number | `users_tbl.user_id`. |
| `ROLE` | string | Role enum name: `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`. |
| `authorities` | string[] | Granted authorities: the role's permissions (`user:create`, `user:read`, `user:update`, `user:delete`) plus `ROLE_<NAME>`. Order is not guaranteed. |
| `sessionId` | string | UUID of the `sessions` row created at token issue time. This is what makes a token revocable. |
| `iat` / `exp` | number | Issued-at and expiry, seconds since epoch. Lifetime = `${JWT_EXPIRATION_MS}` milliseconds (code default `86400000` = 24 h). |

> **Note:** the platform brief lists `jjwt 0.12.3` as the JWT library. `jjwt` is on the classpath
> (`pom.xml`), but **no code uses it** — every token is created and verified with
> `com.auth0:java-jwt:4.4.0`. The wire format is standard JWS/HS256 either way.

### 2. Two ways to send the token

`JWTAuthenticationFilter.resolveToken()` accepts either carrier, header first:

1. `Authorization: Bearer <token>`
2. The HttpOnly cookie named `${JWT_COOKIE_NAME}` (code default `khi_auth_token`).

`POST /register`, `/register-with-image` and `/login` set that cookie automatically on any 2xx response
that carries a non-blank token (`UserAPI.withAuthCookie`). Cookie attributes are entirely env-driven:

| Attribute | Property | Code default | Notes |
|-----------|----------|--------------|-------|
| name | `${JWT_COOKIE_NAME}` | `khi_auth_token` | Read back by `JwtCookieService.resolveToken`. |
| `HttpOnly` | `${JWT_COOKIE_HTTP_ONLY}` | `true` | Not readable from JavaScript. |
| `Secure` | `${JWT_COOKIE_SECURE}` | `false` | Must be `true` in production (HTTPS). |
| `SameSite` | `${JWT_COOKIE_SAME_SITE}` | `Strict` | See the cross-site gotcha below. |
| `Path` | `${JWT_COOKIE_PATH}` | `/` | |
| `Max-Age` | `${JWT_COOKIE_MAX_AGE}` | `86400` (seconds) | Independent of the JWT `exp` — keep them aligned. |

`application.yaml` binds all six with **no fallback** (`cookie-name: ${JWT_COOKIE_NAME}` etc.), so the
application will not start unless every one of these environment variables is set, along with
`JWT_SECRET` and `JWT_EXPIRATION_MS`.

> **Gotcha:** with `SameSite=Strict` the browser will not attach the cookie to requests originating
> from a different site. A frontend on `*.vercel.app` calling the Railway API is cross-site, so the
> cookie will not travel — use the `Authorization: Bearer` header from those origins, or deploy with
> `JWT_COOKIE_SAME_SITE=None` **and** `JWT_COOKIE_SECURE=true`. CORS already allows credentials
> (`app.cors.allow-credentials: true`).

### 3. What the server does with the token on each request

`JWTAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter` on every request except
`OPTIONS` and the five public auth paths. In order it:

1. Verifies signature, issuer, audience and expiry.
2. Calls `TokenService.isTokenBlacklisted(token)`, which is `true` when **any** of these hold:
   the token is a row in `token_blacklist`; the `sessionId` claim is missing; no `sessions` row has that
   `sessionId`; the session's `is_active` is not `true`; or the session's `expires_at` is in the past.
3. Loads the `User` through `UserService.loadUserByUsername` and populates the `SecurityContext`.

Sessions are **stateless** on the servlet side (`SessionCreationPolicy.STATELESS`); the `sessions`
table is the application's own revocation list, not an HTTP session.

Failures inside the filter are written directly by the filter, **not** by `GlobalExceptionHandler`, so
they have their own two-field shape:

| Status | Body | When |
|--------|------|------|
| `401` | `{"error":"TOKEN_EXPIRED","message":"Session expired, please login again"}` | `exp` in the past. Auth cookie is cleared. |
| `403` | `{"error":"INVALID_TOKEN","message":"Invalid token"}` | Bad signature, wrong issuer/audience, malformed token. Cookie cleared. |
| `401` | `{"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}` | Blacklisted token, or its session is gone/inactive/expired. Cookie cleared. |
| `500` | `{"error":"SERVER_ERROR","message":"Internal server error"}` | Any other failure inside the filter. |

### 4. Passwords

Stored as **BCrypt** hashes (`AppConfig` exposes `new BCryptPasswordEncoder()` with the default
strength 10) in `users_tbl.password`. Plaintext is never persisted, never logged and never returned:
`User.password` is `@JsonIgnore`, and `UserResponseDTO` has no password field at all.

Two account-protection mechanisms sit on top:

| Mechanism | Constant | Value | Effect |
|-----------|----------|-------|--------|
| Failed-login lock | `SecurityConstants.MAX_FAILED_ATTEMPTS` | `5` | The 5th consecutive wrong password sets `is_locked = true` and `lock_time = now`. |
| Lock duration | `SecurityConstants.LOCK_DURATION_MINUTES` | `1` | Auto-unlocked on the next login attempt after 1 minute (`unlockIfLockExpired`). A successful password reset also clears the lock immediately. |
| Password expiry | `UserService.PASSWORD_EXPIRY` | `90` days | Set at registration and on every password change. An expired password blocks login with `403`. |
| Reset-token expiry | `UserService.RESET_TOKEN_EXPIRY` | `30` minutes | |

---

## Role model

`Role` (`ak.dev.khi_backend.user.enums.Role`) grants `ROLE_<NAME>` plus a permission set. Self-registration
through this API always produces `GUEST` — the DTO has no role field, and `UserService.register` hard-codes
`Role.GUEST`. Elevation to `EMPLOYEE`/`ADMIN`/`SUPER_ADMIN` is a manual/database operation today.

| Role | Authorities | What it can reach across the API |
|------|-------------|----------------------------------|
| `GUEST` | `ROLE_GUEST`, `user:read` | Everything public, plus its own `/api/user/**` profile and `/api/auth/sessions/**`. No content writes. |
| `EMPLOYEE` | `ROLE_EMPLOYEE`, `user:create`, `user:read`, `user:update` | Adds `POST`/`PUT` on `/api/v1/{projects,news,videos,image-collections,sound-tracks,albums,writings}/**` and `PATCH /api/v1/videos/**`. Cannot delete content. |
| `ADMIN` | `ROLE_ADMIN`, `user:create`, `user:read`, `user:update`, `user:delete` | Everything `EMPLOYEE` can do, plus content `DELETE`, `/api/v1/media/**`, contact/donation inboxes, site settings, nav menu, about/team/partners, social links, `/api/v1/services/admin/**`. |
| `SUPER_ADMIN` | Same permission set as `ADMIN` | Same as `ADMIN`, and is the only role accepted on `/api/users/**` (see the note below). |

> **Note — reserved-but-unmapped prefix.** `SecurityConfig` contains
> `.requestMatchers("/api/users/**").hasRole("SUPER_ADMIN")` and, above it,
> `"/api/users/auth/**"` as `permitAll()`. **No controller in this codebase maps `/api/users`** — the
> self-service controller is `/api/user` (singular) and admin user-management endpoints do not exist.
> Both rules are therefore dead: any call to `/api/users/...` ends at Spring's no-handler path and
> returns `404 NOT_FOUND` (for `/api/users/auth/**`, which is public) or `403` (for the rest, once you
> are authenticated as a non-super-admin). Do not build a frontend against `/api/users/**`.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/auth/register` | None | — | Create a `GUEST` account from JSON; returns a JWT and sets the auth cookie. |
| 2 | `POST` | `/api/auth/register-with-image` | None | — | Same, as `multipart/form-data` with an optional avatar. |
| 3 | `POST` | `/api/auth/login` | None | — | Exchange username-or-email + password for a JWT. |
| 4 | `POST` | `/api/auth/reset-token` | None | — | Generate a 30-minute password-reset token for an email. |
| 5 | `POST` | `/api/auth/reset-password` | None | — | Consume the reset token and set a new password. |

---

## 1. `POST /api/auth/register` — Register (JSON)

Creates a new user with role `GUEST` and `isActivated = true`, hashes the password with BCrypt, sets
`passwordExpiryDate = now + 90 days`, **immediately issues a JWT** (which also inserts a `sessions` row)
and sets the auth cookie. The success message says "You can now login", but the caller is already
logged in — use the returned token directly.

**Auth:** None (public)
**Content-Type:** `application/json`

**Path parameters** — none.

**Query parameters** — none.

**Request body** — `RegisterRequestDTO`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `name` | string | Yes | `@NotBlank`, `@Size(max = 120)` | Display name. Any script — Sorani, Kurmanji or Latin. |
| `username` | string | Yes | `@NotBlank`, `@Size(min = 3, max = 80)`, `@Pattern("^[A-Za-z0-9_]+$")` | Letters, digits and underscore only. Unique (`uk_users_username`). Case-sensitive on write; login matching falls back to case-insensitive. |
| `email` | string | Yes | `@NotBlank`, `@Size(max = 160)`, `@Email(regexp = "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,10}$")` | Unique (`uk_users_email`). Trimmed and lower-cased server-side before the uniqueness check. `user@localhost` is rejected — a dotted TLD of 2–10 letters is mandatory. |
| `password` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | No complexity rule is enforced: `UserValidator.validatePassword` only re-checks the 6–128 length. |
| `pincode` | number (int64) | No | none | Free numeric field persisted to `users_tbl.pincode`. No range validation despite the `length = 6` column hint. |

Service-layer checks that run **after** DTO validation (`UserValidator`):

- Email is normalised to `trim().toLowerCase()` and re-matched against the same regex.
- Disposable-mailbox domains are rejected: `mailinator.com`, `guerrillamail.com`, `tempmail.com`,
  `throwaway.email`, `yopmail.com`, `sharklasers.com`, `guerrillamailblock.com`, `grr.la`,
  `dispostable.com`, `trashmail.com`, `mailnesia.com`, `maildrop.cc`, `fakeinbox.com`,
  `10minutemail.com`, `temp-mail.org`, `getnada.com`, `mohmal.com`, `burnermail.io`, `discard.email`,
  `emailondeck.com`, `crazymailing.com`, `tempail.com`, `trash-mail.com`, `mintemail.com`,
  `mailcatch.com`, `tempr.email`, `tempinbox.com`.
- `username` and `email` uniqueness.

```json
{
  "name": "ئاکار ئەرکان",
  "username": "akar_arkan",
  "email": "akar.arkan@khi.krd",
  "password": "Hewler2026",
  "pincode": 442001
}
```

**Response `201 Created`**

Headers: `Set-Cookie: khi_auth_token=<jwt>; Path=/; Max-Age=86400; HttpOnly; SameSite=Strict`

```json
{
  "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJBa2FyIERldiIsInN1YiI6ImFrYXJfYXJrYW4ifQ.9sT1nB2mQx0e7Yy4Rk3aVp8Lc6Wd5Zf1Hh2Jj3Kk4Ll",
  "response": "Registration successful. You can now login."
}
```

**Errors**

| Status | Body shape | `code` | When |
|--------|-----------|--------|------|
| `400` | `ApiErrorResponse` | `VALIDATION_ERROR` | A DTO constraint failed (blank name, 2-character username, bad email, 5-character password). `fieldErrors[]` names each field. |
| `400` | `Token` | — | `{"response":"Username is already taken."}` |
| `400` | `Token` | — | `{"response":"Email is already registered."}` |
| `400` | `Token` | — | `{"response":"Invalid image: <reason>"}` — see the note below; covers avatar problems **and** email/password rule failures. |
| `400` | `ApiErrorResponse` | `BAD_REQUEST` | Body is not valid JSON, or contains a field the DTO does not declare (`details.unknownField`). |
| `500` | `Token` | — | `{"response":"An unexpected error occurred. Please try again later."}` |

> **Note — misleading error prefix (real behaviour).** `UserService.register` catches
> `IllegalArgumentException` and answers `"Invalid image: " + message`. `UserValidator` throws that same
> exception type for **email and password** problems, so a disposable email address is reported as
> `{"response":"Invalid image: Disposable or temporary email addresses are not allowed. Please use a
> permanent email."}`. Do not surface the prefix to end users verbatim.

> **Note — duplicate user is 400 here, 409 elsewhere.** `UserAlreadyExistsException` is caught inside
> `register`, so this endpoint answers `400` with the `Token` envelope. The same exception escaping
> `PUT /api/user/profile` reaches `GlobalExceptionHandler` and answers `409 CONFLICT` with an
> `ApiErrorResponse`. Two different shapes for the same condition — branch on status, not on body.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "ئاکار ئەرکان",
        "username": "akar_arkan",
        "email": "akar.arkan@khi.krd",
        "password": "Hewler2026",
        "pincode": 442001
      }'
```

---

## 2. `POST /api/auth/register-with-image` — Register with an avatar (multipart)

Identical to endpoint 1 in every respect except that the JSON payload arrives as a part named `data`
and an optional image arrives as a part named `image`. Spring Boot deserialises the `data` part with
Jackson automatically (`MultipartJsonConfig` is intentionally empty — no converter registration is
needed on Spring Boot 4).

**Auth:** None (public)
**Content-Type:** `multipart/form-data`

**Parts**

| Part | Content-Type | Required | Description |
|------|--------------|----------|-------------|
| `data` | `application/json` | Yes | A `RegisterRequestDTO` JSON blob — exactly the body documented in endpoint 1, same constraints. In Postman set the part type to *Text* and override its Content-Type to `application/json`. |
| `image` | `image/jpeg`, `image/png`, `image/gif`, `image/webp` | No | Avatar. Max **5 MB** (`UserService.MAX_FILE_SIZE`); any other content type is rejected. |

**Response `201 Created`** — same `Token` body and `Set-Cookie` header as endpoint 1.

**Errors** — everything from endpoint 1, plus:

| Status | Body shape | `code` | When |
|--------|-----------|--------|------|
| `400` | `Token` | — | `{"response":"Invalid image: File size exceeds maximum limit of 5MB"}` |
| `400` | `Token` | — | `{"response":"Invalid image: Invalid file type. Only JPEG, PNG, GIF and WebP are allowed"}` |
| `400` | `ApiErrorResponse` | `BAD_REQUEST` | Malformed multipart request or a missing `data` part (`MultipartException`). |
| `413` | `ApiErrorResponse` | `PAYLOAD_TOO_LARGE` | Only if the part exceeds the servlet limit of 1 GB; the 5 MB rule fires first and produces `400`. |

> **Note — the avatar from registration is written to local disk, not S3.** `UserService.storeProfileImage`
> writes to `${app.upload.dir:uploads/profile-images}` and stores the **relative path**
> (`uploads/profile-images/<uuid>.jpg`) in `users_tbl.profile_image`. Nothing serves that path over
> HTTP, and on Railway the container filesystem is ephemeral, so the value is effectively unusable by
> the browser. `POST /api/user/profile-image`
> ([internal doc](../internal/USER_PROFILE_API.md#4-post-apiuserprofile-image--upload-or-replace-the-avatar))
> is the S3-backed path and returns a real `https://` URL — have new users upload their avatar there
> right after registering.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/auth/register-with-image \
  -F 'data={"name":"ئاکار ئەرکان","username":"akar_arkan","email":"akar.arkan@khi.krd","password":"Hewler2026"};type=application/json' \
  -F 'image=@./akar-avatar.jpg;type=image/jpeg'
```

---

## 3. `POST /api/auth/login` — Login

Looks the account up by **username or email**, verifies the BCrypt hash, resets the failed-attempt
counter, issues a JWT (creating a new `sessions` row for this device) and sets the auth cookie. Every
successful login creates one more active session — there is no per-user session cap.

Lookup is two-phase (`UserService.findUserByUsernameOrEmail`): an exact indexed match on
`username OR email` first, then a `LOWER(...)` fallback so `AKAR.ARKAN@KHI.KRD` still finds
`akar.arkan@khi.krd`.

**Auth:** None (public)
**Content-Type:** `application/json`

**Request body** — `LoginRequestDTO`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `username` | string | Yes | `@NotBlank`, `@Size(max = 160)` | Username **or** email address. |
| `password` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | Raw password. |

```json
{
  "username": "akar.arkan@khi.krd",
  "password": "Hewler2026"
}
```

**Response `200 OK`**

Headers: `Set-Cookie: khi_auth_token=<jwt>; Path=/; Max-Age=86400; HttpOnly; SameSite=Strict`

```json
{
  "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJBa2FyIERldiIsInN1YiI6ImFrYXJfYXJrYW4ifQ.9sT1nB2mQx0e7Yy4Rk3aVp8Lc6Wd5Zf1Hh2Jj3Kk4Ll",
  "response": "Login successfully done."
}
```

Because Jackson omits nulls globally (`spring.jackson.default-property-inclusion: non_null`), every
failed login returns a **single-field** object — there is no `"token": null` key:

```json
{
  "response": "Invalid credentials. You have 4 attempt(s) remaining before your account is temporarily locked."
}
```

**Errors**

| Status | Body shape | `code` | When |
|--------|-----------|--------|------|
| `400` | `ApiErrorResponse` | `VALIDATION_ERROR` | `username` or `password` missing/too short. |
| `401` | `Token` | — | Wrong password: `"Invalid credentials. You have N attempt(s) remaining before your account is temporarily locked."` (`N` = 4, 3, 2, 1). |
| `401` | `Token` | — | No such username/email: `"Invalid credentials"` (no enumeration hint). |
| `403` | `Token` | — | The attempt that trips the lock: `"Account locked after 5 failed attempts. Please try again in 1 minute(s), or use 'Forgot password' to regain access immediately."` |
| `403` | `Token` | — | A later attempt while still locked: `"Account is locked due to 5 failed attempts. Please try again after 1 minute(s)."` |
| `403` | `Token` | — | `"Your password has expired. Please reset it."` (90 days since last change). |
| `500` | `Token` | — | `"An unexpected error occurred. Please try again later."` |

**Example**

```bash
curl -s -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"akar_arkan","password":"Hewler2026"}'
```

---

## 4. `POST /api/auth/reset-token` — Request a password-reset token

Generates a random UUID reset token, stores it on the user row with a 30-minute expiry, and hands it to
`PasswordResetDeliveryService`. **Always answers `200` with the same text**, whether or not the address
exists, so the endpoint cannot be used to enumerate accounts.

The email is passed as a **query parameter**, not a JSON body. `UserAPI` is annotated `@Validated`, so the
parameter constraints are enforced and produce a `ConstraintViolationException` → `400`.

**Auth:** None (public)
**Content-Type:** none (no request body)
**Produces:** `text/plain;charset=UTF-8`

**Query parameters**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `email` | string | Yes | — | `@NotBlank`, `@Email(regexp = ValidationPatterns.EMAIL)`, `@Size(max = 160)`. Internally resolved through the same username-or-email lookup as login, so a bare username also matches — but it must still satisfy the email regex to get past validation. |

**Response `200 OK`**

```
If an account exists for that email, password reset instructions have been prepared.
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | `MISSING_PARAMETER` | `?email=` omitted entirely. `details.missingParameter` = `email`. |
| `400` | `VALIDATION_ERROR` | Blank, malformed or >160-character email. `fieldErrors[0].field` = `email`. |
| `500` | — | Plain text `An error occurred while creating reset token.` (database failure while saving the token). |

**Example**

```bash
curl -s -X POST 'http://localhost:8080/api/auth/reset-token?email=akar.arkan@khi.krd'
```

---

## 5. `POST /api/auth/reset-password` — Set a new password with the token

Validates the token and replaces the password hash. On success it also clears the reset token, pushes
`passwordExpiryDate` 90 days out, and **unlocks the account** (`isLocked = false`, `failedAttempts = 0`,
`lockTime = null`) — which is why the lock messages suggest "Forgot password" as an immediate way back in.

**Auth:** None (public)
**Content-Type:** `application/json`
**Produces:** `text/plain;charset=UTF-8`

**Request body** — `PasswordResetRequestDTO`

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `email` | string | Yes | `@NotBlank`, `@Email(regexp = ValidationPatterns.EMAIL)`, `@Size(max = 160)` | The account to reset. |
| `resetToken` | string | Yes | `@NotBlank` | The UUID issued by endpoint 4. |
| `newPassword` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | Must differ from the current password. |
| `confirmPassword` | string | Yes | `@NotBlank`, `@Size(min = 6, max = 128)` | Must equal `newPassword`; checked in the service layer. |

```json
{
  "email": "akar.arkan@khi.krd",
  "resetToken": "b3f4a1d2-9c88-4a3e-b0f7-6d2e5c9a71ff",
  "newPassword": "Slemani2026",
  "confirmPassword": "Slemani2026"
}
```

**Response `200 OK`**

```
Password has been successfully reset.
```

**Errors**

| Status | Body shape | `code` | When |
|--------|-----------|--------|------|
| `400` | `ApiErrorResponse` | `VALIDATION_ERROR` | A DTO constraint failed. |
| `400` | text | — | `New password and confirm password do not match.` |
| `400` | text | — | `No reset token found. Please request a new reset token.` (the account has no pending token) |
| `400` | text | — | `Invalid reset token.` |
| `400` | text | — | `Reset token expired. Please request a new reset token.` (older than 30 minutes) |
| `400` | text | — | `Invalid reset request.` (no account for that email) |
| `500` | text | — | `An error occurred while resetting your password.` — **also returned when the new password equals the current one**, see the note below. |

> **Note — "same password" surfaces as 500, and leaks before the token is checked.**
> `UserService.resetPassword` runs `validatePassword` and `validatePasswordNotReused` **before** it
> compares `resetToken`. Those helpers throw `IllegalArgumentException`, which the method's own
> `catch (Exception e)` converts into `500 "An error occurred while resetting your password."` — so a
> user who retypes their existing password gets a server error instead of a 400 with a usable message.
> The same ordering means an attacker who knows an email address can distinguish "this candidate
> password is the current one" (500) from "it is not" (400 `Invalid reset token.`) **without holding a
> valid token**. Both should be fixed by validating the token first; the doc records today's behaviour.

> **Note — a password reset does not revoke existing sessions.** `resetPassword` never touches the
> `sessions` or `token_blacklist` tables, so JWTs issued before the reset stay valid until they expire.
> After a reset, prompt the user to run
> [`POST /api/auth/logout-all`](../internal/AUTH_SESSIONS_API.md#2-post-apiauthlogout-all--log-out-everywhere).

**Example**

```bash
curl -s -X POST http://localhost:8080/api/auth/reset-password \
  -H 'Content-Type: application/json' \
  -d '{
        "email": "akar.arkan@khi.krd",
        "resetToken": "b3f4a1d2-9c88-4a3e-b0f7-6d2e5c9a71ff",
        "newPassword": "Slemani2026",
        "confirmPassword": "Slemani2026"
      }'
```

---

## The password-reset flow, end to end

```
Visitor                     API                          Database                Delivery
  |                          |                              |                       |
  |-- POST /reset-token ---->|                              |                       |
  |    ?email=...            |-- find user (username|email) ->                      |
  |                          |-- reset_token = UUID         |                       |
  |                          |   reset_token_expiration     |                       |
  |                          |   = now + 30 min ----------->|                       |
  |                          |-- deliver(user, token) ------------------------------>|
  |<-- 200 "If an account …" |                              |                       |
  |                          |                              |     (default impl:
  |                          |                              |      writes a log line)
  |-- POST /reset-password ->|                              |                       |
  |    {email, resetToken,   |-- compare token + expiry ---->                       |
  |     newPassword, confirm}|-- password = bcrypt(new)      |                      |
  |                          |   reset_token = NULL          |                      |
  |                          |   password_expiry = +90d      |                      |
  |                          |   is_locked = false --------->|                      |
  |<-- 200 "Password has …"  |                              |                       |
  |-- POST /login ---------->|                              |                       |
```

### The delivery caveat — read this before wiring the UI

`PasswordResetDeliveryService` has exactly one implementation in this build,
`LoggingPasswordResetDeliveryService`, and it **sends nothing**. It writes two log lines:

```java
log.info("Password reset token prepared for userId={} email={}. Configure a real delivery provider to send it securely.",
         user.getUserId(), user.getEmail());
log.debug("Password reset token for userId={}: {}", user.getUserId(), resetToken);
```

Consequences the frontend team must plan around:

1. **No email, SMS or push is ever sent.** There is no mail dependency in `pom.xml` and no SMTP config in
   `application.yaml`.
2. **The token is not even in the normal logs.** `logging.level.ak.dev.khi_backend` is `INFO`, so the
   second line — the only one containing the token value — is suppressed. Today the token can only be
   read by lowering that logger to `DEBUG` or by querying `users_tbl.reset_token` directly.
3. **`POST /reset-token` is therefore not a self-service flow yet.** Until a real
   `PasswordResetDeliveryService` bean is added, treat password reset as an operator-assisted procedure,
   or keep the "Forgot password" link hidden.
4. The `200` response is deliberately identical for known and unknown addresses, so the UI must not
   claim "we sent you an email".

---

## Enums used by this API

### `Role` — `users_tbl.role`, JWT `ROLE` claim

| Value | Meaning |
|-------|---------|
| `GUEST` | Default for every self-registered account. Read-only outside its own profile. |
| `EMPLOYEE` | Content contributor: create and update content, no deletes. |
| `ADMIN` | Full content and site administration, including deletes and the media pipeline. |
| `SUPER_ADMIN` | Same permissions as `ADMIN`; the only role that satisfies the (currently unmapped) `/api/users/**` rule. |

### `Permission` — values inside the JWT `authorities` array

| Value | Granted to |
|-------|-----------|
| `user:read` | `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `user:create` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `user:update` | `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN` |
| `user:delete` | `ADMIN`, `SUPER_ADMIN` |

These permission strings are carried in the token but no endpoint in this codebase authorises on them —
authorisation is by `ROLE_*` only.

---

## Error bodies you can receive from this domain

Three different shapes reach the client on this base path. Branch on the HTTP status first.

**A. `Token` envelope** — every non-2xx that `UserService` handles itself (`register`, `login`).
Nulls are omitted, so failures carry `response` only:

```json
{
  "response": "Email is already registered."
}
```

**B. `text/plain`** — the two reset endpoints, success and failure alike.

**C. `ApiErrorResponse`** — anything that reaches `GlobalExceptionHandler` (bean validation, malformed
JSON, missing parameters, oversized uploads, unexpected failures):

```json
{
  "timestamp": "2026-08-26T09:14:22Z",
  "status": 400,
  "path": "/api/auth/register",
  "method": "POST",
  "traceId": "3f9c1b7e-2a44-4c0d-9f1e-8b7a6d5c4e33",
  "code": "VALIDATION_ERROR",
  "message": "Validation error.",
  "messageEn": "Validation error.",
  "messageKu": "Validation error.",
  "fieldErrors": [
    {
      "field": "username",
      "message": "Username must be between 3 and 80 characters",
      "messageEn": "Username must be between 3 and 80 characters",
      "messageKu": "Username must be between 3 and 80 characters"
    }
  ]
}
```

Reading it correctly:

- `traceId` is echoed on every response in the `X-Trace-Id` header (`TraceIdFilter`); you may also send
  your own `X-Trace-Id` and it will be reused.
- The top-level `message` comes from the resource bundle keyed by `Accept-Language`
  (`en` → `i18n/messages_en.properties`, `ckb` → `messages_ckb.properties`, `kmr` → `messages_kmr.properties`;
  default `en`). It is the **generic** text for the error class, never the specific cause.
- The specific cause lives in `fieldErrors[]` (bean validation) or in `details` — for
  `IllegalArgumentException`-driven `BAD_REQUEST` responses the real reason is `details.reason`, and the
  three message fields all read "Bad request." Always render `details.reason` / `fieldErrors[]` when present.
- Field-level messages are the literal English constraint messages from the DTOs; they are not translated,
  so `message`, `messageEn` and `messageKu` are identical there.

> **Note — `messageKu` is not reliably Kurdish.** `GlobalExceptionHandler` resolves it with
> `Locale.forLanguageTag("ku")`, but the bundles shipped are `messages_en`, `messages_ckb` and
> `messages_kmr`. There is no `messages_ku.properties` and no default `messages.properties`, so the
> lookup falls back and `messageKu` usually returns the English (or hard-coded fallback) string. Use
> `Accept-Language: ckb` (or `kmr`) and read `message` if you need Kurdish copy.

> **Note — 401/403 raised by the security layer bypass `ApiErrorResponse` entirely.**
> `JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` both call `response.sendError(...)`, which
> produces the framework's default error body (`timestamp`, `status`, `error`, `path`), not the shape
> above. Additionally `spring.web.error.include-message: always` in `application.yaml` is not a
> recognised Spring Boot property — the real key is `server.error.include-message` — so the `message`
> field of those default bodies is empty. Only failures raised *inside* the MVC layer get a full
> `ApiErrorResponse`.

---

## Notes & gotchas

- **No caching.** Nothing in this domain is `@Cacheable`/`@CacheEvict`; every call hits PostgreSQL. The
  Redis cache (`khi:` prefix, 10-minute TTL) is used by the content modules only.
- **Registration always yields `GUEST`.** There is no public way to request a higher role: the DTO has no
  role field. `UserCreateRequestDTO` (which does accept `role`) is only used by
  `UserService.createUser`, which no controller exposes today.
- **`isActivated` is not enforced on login.** `UserService.login` does not consult it, and
  `JWTAuthenticationFilter` builds the authentication token without checking `UserDetails.isEnabled()`.
  Setting `is_activated = false` therefore does **not** block a user from logging in or from using an
  existing token. Revoke sessions instead (see the internal doc).
- **Every login writes a row.** `sessions` and `token_blacklist` grow forever — there is no `@Scheduled`
  cleanup anywhere in the codebase. Plan a database job.
- **Timestamps.** Hibernate writes UTC (`hibernate.jdbc.time_zone=UTC`); `Instant` fields serialise as
  ISO-8601 with a `Z` suffix (`2026-08-26T09:14:22Z`). `spring.jackson.time-zone: Asia/Baghdad` affects
  formatted date/date-time types, not `Instant`. Request `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss` binding
  applies to MVC form/query parsing.
- **Pretty-printed JSON.** `spring.jackson.serialization.indent_output: true` is on globally, so
  responses are indented — do not assume compact payloads when measuring sizes.
- **CORS.** Allowed origins are the Railway dashboard and website, `khi-frontend*.vercel.app`,
  `http://localhost:5173` and `http://localhost:3000`, with credentials enabled and a 1-hour preflight cache.
- **Live spec.** Swagger UI `/swagger-ui.html`, JSON `/v3/api-docs`, groups `public`, `internal`, `all`.
  The `public` group already lists exactly these five endpoints. Note that the Swagger cookie security
  scheme hard-codes the cookie name `auth_token`, which will not match `${JWT_COOKIE_NAME}` unless you
  set that variable to `auth_token` — "try it out" with the cookie scheme may therefore not authenticate.
- **Servers.** `http://localhost:8080` locally; production runs on Railway. The second server entry in
  `OpenApiConfig` (`https://api.khi.local`) is an unresolved placeholder.

---

## Related documentation

- Counterpart (logout + sessions): [`../internal/AUTH_SESSIONS_API.md`](../internal/AUTH_SESSIONS_API.md)
- Counterpart (self-service profile): [`../internal/USER_PROFILE_API.md`](../internal/USER_PROFILE_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
