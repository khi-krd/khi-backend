# Auth Sessions API — Internal (Authenticated)

The authenticated half of `/api/auth`: ending the current session, ending every session, and the
per-device session console (list and revoke). Used by the admin dashboard's account menu and the
"active devices" panel. Every endpoint here requires a valid JWT; the public registration and login
endpoints are documented in [`../external/AUTH_API.md`](../external/AUTH_API.md).

| | |
|---|---|
| **Base paths** | `/api/auth/logout`, `/api/auth/logout-all`, `/api/auth/sessions` |
| **Audience** | Admin dashboard / staff tooling (JWT required) |
| **Controllers** | `src/main/java/ak/dev/khi_backend/user/api/UserAPI.java` (logout, logout-all), `src/main/java/ak/dev/khi_backend/user/api/SessionAPI.java` (sessions) |
| **Services** | `src/main/java/ak/dev/khi_backend/user/service/TokenService.java`, `src/main/java/ak/dev/khi_backend/user/jwt/JwtCookieService.java`, `SessionRepository` (used directly by both controllers) |
| **Entities** | `Session`, `TokenBlacklist`, `User` |
| **Response envelope** | Bare `ResponseEntity` — `text/plain` for every mutation, a JSON array of `SessionDTO` for the listing. No `ApiResponse<T>` wrapper. |
| **Verified against source** | 2026-08-26 |

**Authorization (from `SecurityConfig`, in rule order)**

```java
.requestMatchers("/api/auth/sessions/**").authenticated()
.requestMatchers("/api/auth/logout", "/api/auth/logout-all").authenticated()
```

Any authenticated role is accepted — `GUEST`, `EMPLOYEE`, `ADMIN`, `SUPER_ADMIN`. No handler in this
file carries `@PreAuthorize`, so nothing narrows those rules further. A user can only ever see and
revoke **their own** sessions; there is no cross-user session administration endpoint.

---

## How revocation works

### The two-table model

| Table | Entity | Written by | Purpose |
|-------|--------|-----------|---------|
| `sessions` | `Session` | `JwtTokenProvider.generateToken` on every register/login | One row per issued token. Holds device, IP and validity window. Flipping `is_active` to `false` kills the token. |
| `token_blacklist` | `TokenBlacklist` | `TokenService.blacklistToken` on logout | The raw JWT string, so a token stays dead even if its session row is later resurrected. |

### `TokenService.isTokenBlacklisted(token)` — the gate on every request

`JWTAuthenticationFilter` calls this after signature verification. It returns `true` (reject) when **any**
of the following is true:

1. `token` is null or blank.
2. A `token_blacklist` row exists for that exact token string.
3. The token carries no `sessionId` claim.
4. No `sessions` row matches that `sessionId`.
5. The matching session has `is_active != true`.
6. The matching session has `expires_at == null` or `expires_at` in the past.

A rejected token produces `401 {"error":"TOKEN_REVOKED","message":"Session invalidated, please login again"}`
straight from the filter and the auth cookie is cleared. That is the response the frontend should treat as
"you were logged out elsewhere — go back to the login screen".

### `Session` lifecycle

| Column | Field | Set when | Notes |
|--------|-------|----------|-------|
| `id` | `Long id` | insert | Surrogate PK, `GenerationType.AUTO`. Not exposed by the API. |
| `session_id` | `String sessionId` | insert | `UUID.randomUUID().toString()`, unique, also embedded in the JWT as the `sessionId` claim. This is the identifier used by the API. |
| `user_id` | `User user` | insert | Lazy `@ManyToOne`, not null. |
| `device_info` | `String deviceInfo` | insert | Verbatim `User-Agent` request header at login. May be null if the client sends none — no parsing, no truncation. |
| `ip_address` | `String ipAddress` | insert | `HttpServletRequest.getRemoteAddr()`. `server.forward-headers-strategy: framework` is enabled, so behind the Railway proxy this resolves to the real client IP from `X-Forwarded-For`. |
| `login_timestamp` | `Instant loginTimestamp` | insert | Token issue time. |
| `expires_at` | `Instant expiresAt` | insert | `loginTimestamp + ${JWT_EXPIRATION_MS}` — identical to the JWT `exp`. |
| `is_active` | `Boolean isActive` | insert `true` | Set to `false` by logout, logout-all, single revoke, revoke-all, and by `TokenService.blacklistToken`. |
| `logout_timestamp` | `Instant logoutTimestamp` | on revoke | `Instant.now()` at the moment the session is deactivated. Null while active. |

Rows are **deleted** (not just deactivated) in exactly two places:
`UserProfileService.deleteAccount` and `UserService.deleteUser`. Nothing purges expired rows — there is no
`@Scheduled` job in this codebase, so both `sessions` and `token_blacklist` grow without bound.

---

## Endpoints at a glance

| # | Method | Path | Auth | Roles | Purpose |
|---|--------|------|------|-------|---------|
| 1 | `POST` | `/api/auth/logout` | JWT | Any authenticated | Blacklist the current token, deactivate its session, clear the cookie. |
| 2 | `POST` | `/api/auth/logout-all` | JWT | Any authenticated | Deactivate every session of the current user and blacklist the current token. |
| 3 | `GET` | `/api/auth/sessions/getAllSessions` | JWT | Any authenticated | List the current user's active sessions. |
| 4 | `DELETE` | `/api/auth/sessions/{sessionId}` | JWT | Any authenticated (owner only) | Revoke one session by its `sessionId`. |
| 5 | `DELETE` | `/api/auth/sessions/revokeAll` | JWT | Any authenticated | Revoke every active session of the current user. |

---

## 1. `POST /api/auth/logout` — Log out of this device

Resolves the current token (header first, then cookie), writes it to `token_blacklist`, marks the
matching `sessions` row inactive with a `logout_timestamp`, and sends a `Set-Cookie` header that expires
the auth cookie. Other devices are unaffected.

**Auth:** `Authorization: Bearer <token>` or the `${JWT_COOKIE_NAME}` cookie — any authenticated role
**Content-Type:** none (no request body)
**Produces:** `text/plain;charset=UTF-8`

**Path parameters** — none.

**Query parameters** — none.

**Request headers**

| Name | Required | Description |
|------|----------|-------------|
| `Authorization` | No | `Bearer <jwt>`. Declared on the handler as an optional `@RequestHeader`. If absent, the token is read from the auth cookie instead. |

**Request body** — none.

**Response `200 OK`**

Headers: `Set-Cookie: khi_auth_token=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`

```
Successfully logged out
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `400` | — | Plain text `Authentication token is missing` — the handler's own guard when neither the header nor the cookie yields a token. The auth cookie is still cleared. In practice unreachable: `SecurityConfig` marks this path `.authenticated()`, so a token-less request is stopped by the security layer first. |
| `401` | — | Expired or revoked token: `JWTAuthenticationFilter` writes `{"error":"TOKEN_EXPIRED", ...}` / `{"error":"TOKEN_REVOKED", ...}` itself. With **no** token at all you get Spring Security's default, not this body — `SecurityConfig` never calls `.exceptionHandling(...)`, so the `JwtAuthenticationEntryPoint` bean is never registered and never runs. |
| `403` | — | `{"error":"INVALID_TOKEN","message":"Invalid token"}` from the filter for a malformed or wrongly signed token. |

Logging out twice with the same token is not an error path you will see: the second call is rejected by
the filter with `401 TOKEN_REVOKED` before the controller runs. `TokenService.blacklistToken` is itself
idempotent (`findByToken(...).orElseGet(insert)`).

**Example**

```bash
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 2. `POST /api/auth/logout-all` — Log out everywhere

Loads **every** session row for the current user — `sessionRepository.findByUser(user)`, not only the
active ones — sets `is_active = false` and `logout_timestamp = now` on all of them, saves the batch, then
blacklists the current token and clears the auth cookie. Every other device is signed out on its next
request with `401 TOKEN_REVOKED`.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Content-Type:** none (no request body)
**Produces:** `text/plain;charset=UTF-8`

**Request headers**

| Name | Required | Description |
|------|----------|-------------|
| `Authorization` | No | Optional `@RequestHeader`; the cookie is the fallback. If neither carries a token the session deactivation still happens and only the blacklist insert is skipped. |

**Response `200 OK`**

Headers: `Set-Cookie: khi_auth_token=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`

```
Logged out from all devices successfully
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | — | Plain text `Not authenticated` — the handler's guard when `@AuthenticationPrincipal UserDetails principal` is null. Unreachable through the normal filter chain, which always populates the principal for this path. |
| `401` / `403` | — | Standard filter/entry-point responses for a missing, expired, revoked or invalid token. |

> **Note — already-closed sessions are re-stamped.** Because the query is `findByUser` rather than
> `findByUserAndIsActive`, sessions that were closed weeks ago get their `logout_timestamp` overwritten
> with the current time. Treat `logout_timestamp` as "last time this row was deactivated", not as a
> reliable audit trail of the original logout.

**Example**

```bash
curl -s -X POST http://localhost:8080/api/auth/logout-all \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 3. `GET /api/auth/sessions/getAllSessions` — List active sessions

Returns the current user's **active** sessions (`findByUserAndIsActive(user, true)`), mapped to
`SessionDTO`. Includes the session the request itself is authenticated with — match
`sessionId` against the `sessionId` claim of your own token to label it "this device". The list is
returned in whatever order PostgreSQL yields; there is no `ORDER BY`.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Produces:** `application/json`

**Path parameters** — none.

**Query parameters** — none. There is no pagination, filtering or sorting.

**Response `200 OK`** — `SessionDTO[]`

| Field | Type | Description |
|-------|------|-------------|
| `sessionId` | string (UUID) | Identifier to pass to endpoint 4. |
| `deviceInfo` | string \| absent | Raw `User-Agent` captured at login. Omitted when null (`spring.jackson.default-property-inclusion: non_null`). |
| `ipAddress` | string \| absent | Client IP captured at login. |
| `loginTimestamp` | string (ISO-8601 UTC) | When the token was issued. |
| `expiresAt` | string (ISO-8601 UTC) | When the token/session stops being accepted. |
| `isActive` | boolean | Always `true` in this response — the query filters on it. |
| `logoutTimestamp` | string \| absent | Always absent here, for the same reason. |

```json
[
  {
    "sessionId": "6f0a1c8e-7d43-4a11-9c2b-51e0d9a4bb37",
    "deviceInfo": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
    "ipAddress": "37.236.108.42",
    "loginTimestamp": "2026-08-26T06:14:22Z",
    "expiresAt": "2026-08-27T06:14:22Z",
    "isActive": true
  },
  {
    "sessionId": "b1c53d90-2f77-4d18-8a45-0c9e2f7a3d61",
    "deviceInfo": "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
    "ipAddress": "185.51.203.17",
    "loginTimestamp": "2026-08-25T18:02:09Z",
    "expiresAt": "2026-08-26T18:02:09Z",
    "isActive": true
  }
]
```

An account with no active sessions returns `[]`.

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | — | Plain text `Not authenticated` if `@AuthenticationPrincipal User user` were null (unreachable via the normal chain), otherwise the standard filter/entry-point 401. |
| `403` | — | `{"error":"INVALID_TOKEN", ...}` from the filter. |

**Example**

```bash
curl -s http://localhost:8080/api/auth/sessions/getAllSessions \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 4. `DELETE /api/auth/sessions/{sessionId}` — Revoke one session

Marks a single session inactive and stamps `logout_timestamp`. The JWT bound to that session fails
`isTokenBlacklisted` on its next request, so the other device is signed out immediately. Ownership is
enforced in the handler: revoking someone else's session is a `403`.

The raw JWT of the revoked session is **not** written to `token_blacklist` — only the session row is
flipped. That is sufficient, because the session check is part of `isTokenBlacklisted`.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role, owner only
**Produces:** `text/plain;charset=UTF-8`

**Path parameters**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `sessionId` | string (UUID) | Yes | The `sessionId` from endpoint 3. No format validation is applied — an unknown value simply yields `404`. |

**Query parameters** — none.

**Request body** — none.

**Response `200 OK`**

```
Session revoked successfully
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `404` | — | Plain text `Session not found` — no `sessions` row with that `sessionId`. |
| `403` | — | Plain text `You can only revoke your own sessions` — the row belongs to a different `userId`. |
| `401` | — | Plain text `Not authenticated` (guard), or the standard filter/entry-point 401. |

Revoking a session that is already inactive succeeds and simply re-stamps `logout_timestamp`.

> **Gotcha — revoking your own current session.** Nothing stops you from passing the `sessionId` of the
> token you are calling with. The call returns `200`, and every subsequent request with that token gets
> `401 TOKEN_REVOKED`. Unlike `/api/auth/logout`, `SessionAPI` does **not** clear the auth cookie, so the
> browser keeps sending a dead cookie until it expires. Call `POST /api/auth/logout` when the intent is
> "sign me out here".

**Example**

```bash
curl -s -X DELETE \
  http://localhost:8080/api/auth/sessions/b1c53d90-2f77-4d18-8a45-0c9e2f7a3d61 \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## 5. `DELETE /api/auth/sessions/revokeAll` — Revoke every active session

Loads the current user's active sessions, marks them all inactive with `logout_timestamp = now`, and
saves the batch. **The caller's own session is included**, so this signs out the current device too.

**Auth:** `Authorization: Bearer <token>` or the auth cookie — any authenticated role
**Produces:** `text/plain;charset=UTF-8`

**Path parameters** — none.

**Request body** — none.

**Response `200 OK`**

```
All sessions revoked successfully
```

**Errors**

| Status | `code` | When |
|--------|--------|------|
| `401` | — | Plain text `Not authenticated` (guard), or the standard filter/entry-point 401. |

> **Note — route ambiguity is resolved in favour of the literal path.** `DELETE /api/auth/sessions/revokeAll`
> also matches the `/{sessionId}` template of endpoint 4. Spring's `PatternsRequestCondition` ranks the
> literal pattern above the templated one, so `revokeAll` always reaches this handler — a user can never
> have a session whose `sessionId` is the string `revokeAll` (they are UUIDs), so there is no collision in
> practice.

**How it differs from `POST /api/auth/logout-all`**

| | `DELETE /api/auth/sessions/revokeAll` | `POST /api/auth/logout-all` |
|---|---|---|
| Sessions touched | Active only (`findByUserAndIsActive`) | All rows for the user (`findByUser`) |
| Blacklists the current JWT | No | Yes |
| Clears the auth cookie | No | Yes |
| Intended caller | "Sign out my other devices" panel | Account menu "log out everywhere" |

Both leave the current token unusable, because the session behind it is deactivated either way.

**Example**

```bash
curl -s -X DELETE http://localhost:8080/api/auth/sessions/revokeAll \
  -H "Authorization: Bearer $KHI_TOKEN"
```

---

## Enums used by this API

None. `SessionDTO` carries no enum fields, and no endpoint on this base path accepts or returns `Role`,
`Permission` or `Language`.

---

## Notes & gotchas

- **Response types are mixed.** Only endpoint 3 returns JSON; the four mutations return bare strings with
  `Content-Type: text/plain;charset=UTF-8`. Do not call `response.json()` on them.
- **No caching.** No `@Cacheable`/`@CacheEvict` anywhere in this domain; every call hits PostgreSQL.
  The controllers use `SessionRepository` directly — there is no session service layer.
- **`TokenBlacklist.token` is `length = 512`.** A JWT longer than 512 characters (many authorities, long
  username) will fail the insert with a `DataIntegrityViolationException` → `409 CONFLICT`. Current tokens
  are well under that, but it is a hard ceiling on claim growth.
- **`TokenService.blacklistToken` also deactivates the session** it finds through the `sessionId` claim, so
  a single logout writes to both tables.
- **Expiry alone is enough.** Once `expires_at` passes, `isTokenBlacklisted` rejects the token even if the
  row is still `is_active = true`, and the filter rejects the JWT on `exp` before that.
- **A locked or password-expired user with a live token gets `500`, not `401`.**
  `JWTAuthenticationFilter` calls `userDetailsService.loadUserByUsername(...)`, and `UserService` throws
  `LockedException` from there when the account is locked or the password has passed its 90-day expiry.
  That exception is caught by the filter's outer `catch (Exception)` and answered as
  `500 {"error":"SERVER_ERROR","message":"Internal server error"}`. Deleting the account is handled
  correctly (its sessions are deleted, so the request is rejected as `401 TOKEN_REVOKED` first).
- **Nothing purges the tables.** No `@Scheduled` bean exists. `sessions` gains a row per login and
  `token_blacklist` a row per logout, forever.
- **Timestamps** are stored UTC (`hibernate.jdbc.time_zone=UTC`) and serialised as ISO-8601 `Instant`
  strings ending in `Z`.
- **`X-Trace-Id`** is echoed on every response by `TraceIdFilter`; send your own value to correlate a
  frontend action with the server logs.
- **Live spec.** These paths are in the Swagger `internal` group: `/swagger-ui.html`, `/v3/api-docs?group=internal`.

---

## Related documentation

- Counterpart (registration, login, password reset): [`../external/AUTH_API.md`](../external/AUTH_API.md)
- Sibling (self-service profile, `/api/user/**`): [`USER_PROFILE_API.md`](USER_PROFILE_API.md)
- Schema: [`../database/SCHEMA.md`](../database/SCHEMA.md)
