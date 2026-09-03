# UML Sequence Diagrams

Eighteen diagrams showing the order in which objects call each other for the operations that
matter. The security and identity flows come first, because every other request in the system
passes through them, and the content and media flows come after.

Where [`UML_CLASS.md`](./UML_CLASS.md) shows which classes exist and
[`UML_COMPONENT.md`](./UML_COMPONENT.md) shows how they are wired, this file shows what actually
happens on the wire: which call comes before which, where the transaction opens, which write lands
in S3 before the first `INSERT`, and which branch quietly returns a status nobody expects.

Everything below was re-read from `src/main/java/ak/dev/khi_backend/` before it was drawn. Where
the code disagreed with the written catalogue, the code won; those cases are listed under
[Corrections against the catalogue](#corrections-against-the-catalogue) at the end.

---

## Contents

**Security and identity**

- [The authenticated request](#the-authenticated-request) — the filter chain and the two
  authorization layers, end to end
- [The anonymous request to a public endpoint](#the-anonymous-request-to-a-public-endpoint) — why
  public does not mean unfiltered
- [Registration](#registration) — the `sessions` row that is written before the token exists
- [Registration with a profile image](#registration-with-a-profile-image) — local disk, not S3
- [Login](#login) — the hand-rolled lock counter and its three exits
- [Logout and logout-all](#logout-and-logout-all) — the two writes that kill a stateless token
- [Password reset](#password-reset) — token issue and token redemption
- [Session revocation](#session-revocation) — an owner check standing in for authorization

**Content and media**

- [Creating content with media](#creating-content-with-media) — S3 first, transaction second
- [The Tiptap editor image pipeline](#the-tiptap-editor-image-pipeline) — two routes in, one column out
- [Cached read](#cached-read) — what the Redis key really looks like
- [Cache eviction on write](#cache-eviction-on-write) — the read-through race
- [Deleting a content root](#deleting-a-content-root) — JPA cascades, S3 does not
- [The one delete path that cleans up S3](#the-one-delete-path-that-cleans-up-s3) — and why its
  ordering is wrong
- [Global search](#global-search) — six sections, twelve sequential queries
- [Assembling the featured rail](#assembling-the-featured-rail) — built at request time from seven tables
- [Public submission and the admin queue](#public-submission-and-the-admin-queue) — both halves of
  the authorization model in one round trip

**Reference**

- [Corrections against the catalogue](#corrections-against-the-catalogue)
- [Related](#related)

---

## How to read these

Every diagram here is a Mermaid `sequenceDiagram`. The notation used in this file, and nothing
more:

| Notation | Meaning |
| --- | --- |
| `autonumber` | Steps are numbered in call order. Quote the number when discussing a diagram. |
| `participant Svc as UserService` | The left name is the short id used by the arrows; the right name is the real class, bean or table. Participants are real types, not roles. |
| `A->>B: text` | A synchronous call from A into B. |
| `A-->>B: text` | A reply travelling back. |
| `A-)B: text` | Fire and forget — the caller does not wait. Used for `@CacheEvict`, which Spring runs after the method returns. |
| `A-xB: text` / `A--xB: text` | A rejected or aborted message: an error response, a rollback, a request that never reaches its target. |
| `alt` / `else` / `end` | Mutually exclusive branches. Every branch shown is reachable in the code. |
| `opt` / `end` | A block that runs only under the stated condition. |
| `loop` / `end` | A block repeated per item. |
| `par` / `and` / `end` | Two threads running concurrently. Used once, for the cache race. |
| `Note over A,B: text` | A fact about the span between two participants that is not itself a call. |

Two conventions worth stating once, because they recur in almost every diagram:

- **`ApiErrorResponse` is bilingual.** It carries `messageEn` and `messageKu` always, plus a
  `message` resolved from the request's `Accept-Language`, plus `traceId`, `code` (the `ErrorCode`
  enum) and, for `@Valid` failures, one `fieldErrors` entry per field. Same shape in
  [`ApiErrorResponse`](../../src/main/java/ak/dev/khi_backend/khi_app/exceptions/ApiErrorResponse.java)
  and [`ApiFieldError`](../../src/main/java/ak/dev/khi_backend/khi_app/exceptions/ApiFieldError.java).
- **Not every error is an `ApiErrorResponse`.** Three other error shapes exist, and the diagrams
  distinguish them: the two-field JSON that `JWTAuthenticationFilter` writes itself, the bare
  Spring Security default for a path-matcher denial, and the plain-string bodies that a few `user`
  controllers return by hand.

---

## Security and identity

### The authenticated request

Start here. A single request with a Bearer token crosses two filters, two independent
authorization layers and up to three database reads before the controller sees it, and each stage
rejects differently — with a different status code and a different body shape. Everything below is
the real path through
[`TraceIdFilter`](../../src/main/java/ak/dev/khi_backend/khi_app/config/TraceIdFilter.java),
[`JWTAuthenticationFilter`](../../src/main/java/ak/dev/khi_backend/user/jwt/JWTAuthenticationFilter.java)
and [`SecurityConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/SecurityConfig.java).

The worked example is `PATCH /api/v1/news/42/featured`, because no `authorizeHttpRequests` rule
matches `PATCH` on that path — the only PATCH matcher in the content block covers
`/api/v1/videos/**`. It therefore falls through to `.anyRequest().authenticated()`, and the only
thing that restricts it to admins is the `@PreAuthorize("hasRole('ADMIN')")` on
[`NewsController.setFeatured`](../../src/main/java/ak/dev/khi_backend/khi_app/api/news/NewsController.java).

```mermaid
sequenceDiagram
autonumber
participant Client
participant Trace as TraceIdFilter
participant JwtF as JWTAuthenticationFilter
participant Tokens as TokenService
participant Svc as UserService
participant DB as PostgreSQL
participant Ctx as SecurityContextHolder
participant Authz as AuthorizationFilter
participant MethodSec as MethodSecurity
participant Ctrl as NewsController
participant Advice as GlobalExceptionHandler

Client->>Trace: PATCH /api/v1/news/42/featured with Authorization Bearer
Trace->>Trace: read X-Trace-Id or mint a UUID
Trace->>Trace: MDC.put traceId and echo the X-Trace-Id response header
Trace->>JwtF: continue the chain
JwtF->>JwtF: shouldNotFilter strips the context path and compares the result
alt path is one of the five public auth endpoints or under /api/users/auth
    JwtF->>Authz: skip the filter body entirely
else every other path
    JwtF->>JwtF: resolveToken reads Authorization Bearer then falls back to the JWT cookie
    JwtF->>JwtF: getSubject verifies HMAC256 signature issuer audience and expiry
    alt token expired
        JwtF->>JwtF: clearAuthCookie
        JwtF-xClient: 401 JSON error TOKEN_EXPIRED written by the filter itself
    else signature or claims invalid
        JwtF->>JwtF: clearAuthCookie
        JwtF-xClient: 403 JSON error INVALID_TOKEN
    else signature accepted
        JwtF->>Tokens: isTokenBlacklisted token
        Tokens->>DB: SELECT token_blacklist by token
        Tokens->>DB: SELECT sessions by the sessionId claim
        Tokens-->>JwtF: true when blacklisted or the session is missing inactive or past expiry
        alt revoked
            JwtF->>JwtF: clearAuthCookie
            JwtF-xClient: 401 JSON error TOKEN_REVOKED
        else still live
            JwtF->>Svc: loadUserByUsername subject
            Svc->>DB: SELECT users_tbl by username
            Svc->>Svc: unlockIfLockExpired then throw LockedException if locked or password expired
            Svc-->>JwtF: the User entity which implements UserDetails
            JwtF->>Ctx: setAuthentication with authorities read from the token claim
            JwtF->>Authz: continue the chain
        end
    end
end
Authz->>Authz: walk authorizeHttpRequests in declaration order
Note over Authz: no PATCH rule matches this path so it lands on anyRequest authenticated
alt the baseline rule is not satisfied
    Authz-xClient: 403 from Spring Security default exception handling
else baseline satisfied
    Authz->>MethodSec: dispatch to the handler method
    MethodSec->>MethodSec: evaluate PreAuthorize hasRole ADMIN
    alt denied
        MethodSec->>Advice: AuthorizationDeniedException reaches the controller advice
        Advice-->>Client: 403 ApiErrorResponse code FORBIDDEN with traceId and bilingual messages
    else allowed
        MethodSec->>Ctrl: invoke setFeatured
        Ctrl->>DB: SiteContentService updates the featured flag
        Ctrl-->>Client: 204 No Content with the X-Trace-Id header from TraceIdFilter
    end
end
```

**What to notice**

- `JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` never run. They are `@Component`
  beans, but `SecurityConfig` never calls `.exceptionHandling(...)`, so nothing registers them on
  the chain and Spring Security's own defaults handle the filter-level 401 and 403. The practical
  effect is that an unauthenticated request to a protected path returns a bare **403**, not the 401
  that the unused entry point would have sent. This is drawn as it actually behaves.
- The same logical "403 forbidden" therefore has two completely different response bodies. Denied
  by `authorizeHttpRequests`, it is a bare Spring Security error produced outside the
  `DispatcherServlet`. Denied by `@PreAuthorize`, the exception is raised inside the dispatch and is
  caught by the single
  [`GlobalExceptionHandler`](../../src/main/java/ak/dev/khi_backend/khi_app/exceptions/GlobalExceptionHandler.java),
  which returns a full `ApiErrorResponse` with `traceId`, `messageEn` and `messageKu`.
- Authorities come from the **token**, not from the row just loaded.
  `jwtTokenProvider.getAuthorities(token)` reads the `authorities` claim baked in at login, so
  promoting or demoting a user in `users_tbl` changes nothing until that user's token is replaced.
  The freshly loaded `User` is used only as the principal.
- A bad signature returns **403**, an expired token returns **401**. That asymmetry is written into
  `doFilterInternal` and is easy to trip over when debugging a client.
- "Stateless" is nominal. Every authenticated request performs at least three reads: the blacklist
  table, the `sessions` row, and `users_tbl`. Nothing here is cached.
- The filter only populates the context when `SecurityContextHolder.getContext().getAuthentication()`
  is still null, and it never rejects a request merely for lacking a token. Deciding that a missing
  token is a problem is `AuthorizationFilter`'s job, further down.

### The anonymous request to a public endpoint

Public reads still traverse the whole chain. `shouldNotFilter` exempts only five auth paths plus
`/api/users/auth/**`, so `GET /api/v1/news/42` runs through `JWTAuthenticationFilter` like
everything else — and that has a sharp consequence when a stale cookie is still attached to an
otherwise anonymous request.

Source:
[`JWTAuthenticationFilter.shouldNotFilter`](../../src/main/java/ak/dev/khi_backend/user/jwt/JWTAuthenticationFilter.java)
and the `GET /api/v1/**` permitAll rule in
[`SecurityConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/SecurityConfig.java).

```mermaid
sequenceDiagram
autonumber
participant Client
participant Trace as TraceIdFilter
participant JwtF as JWTAuthenticationFilter
participant Ctx as SecurityContextHolder
participant Authz as AuthorizationFilter
participant Ctrl as NewsController
participant Svc as NewsService
participant Redis
participant DB as PostgreSQL

Client->>Trace: GET /api/v1/news/42 with no Authorization header
Trace->>Trace: mint a UUID traceId and set the X-Trace-Id response header
Trace->>JwtF: continue the chain
JwtF->>JwtF: shouldNotFilter returns false because /api/v1/news is not an auth endpoint
JwtF->>JwtF: resolveToken checks the Authorization header then the JWT cookie
alt no token anywhere
    Note over JwtF,Ctx: the filter calls doFilter and returns without touching the SecurityContext
    JwtF->>Authz: continue with an empty SecurityContext
else a stale cookie is still attached
    JwtF->>JwtF: getSubject rejects the expired or tampered token
    JwtF-xClient: 401 TOKEN_EXPIRED or 403 INVALID_TOKEN even though the endpoint is public
end
Authz->>Authz: walk the rules until GET /api/v1/** matches permitAll
Note over Authz: permitAll short-circuits here and the empty context is never challenged
Authz->>Ctrl: dispatch getNewsById
Ctrl->>Svc: getNewsById 42
Svc->>Redis: look up the khi cache key
alt cache hit
    Redis-->>Svc: cached Serializable DTO
else cache miss
    Svc->>DB: SELECT news and its embedded ckb and kmr columns
    Svc->>Redis: store the DTO with a 10 minute TTL
end
Svc-->>Ctrl: NewsDto
Ctrl-->>Client: 200 ApiResponse plus the X-Trace-Id header
```

**What to notice**

- `permitAll` is evaluated by `AuthorizationFilter`, which sits *after* `JWTAuthenticationFilter`.
  Public does not mean unfiltered; it means the authorization decision passes without inspecting
  the context.
- The stale-cookie branch is the non-obvious one. Because `shouldNotFilter` covers only
  `/api/auth/register`, `/api/auth/register-with-image`, `/api/auth/login`, `/api/auth/reset-token`,
  `/api/auth/reset-password` and `/api/users/auth/**`, a visitor whose auth cookie has expired gets
  a 401 on a page that anonymous visitors can read fine. The filter does clear the bad cookie on the
  way out, so the retry succeeds.
- `OPTIONS` is short-circuited twice over. `doFilterInternal` sets the response status to 200 and
  passes the request straight down the chain without reading a token at all, and
  `authorizeHttpRequests` then permits `OPTIONS /**` as its very first rule.
- `TraceIdFilter` runs for anonymous traffic too, so every public response carries an `X-Trace-Id`
  that matches the `traceId` in the application log lines for that request.

### Registration

`POST /api/auth/register` takes a JSON body and returns a signed token, but the interesting part is
that it also writes a `sessions` row before the token exists. The JWT is not self-sufficient: every
later request re-reads that row. This diagram shows where the row is created and how the same token
leaves the server twice, once in the body and once in a `Set-Cookie` header.

Source: [`UserAPI`](../../src/main/java/ak/dev/khi_backend/user/api/UserAPI.java),
[`UserService.register`](../../src/main/java/ak/dev/khi_backend/user/service/UserService.java),
[`UserValidator`](../../src/main/java/ak/dev/khi_backend/user/service/UserValidator.java),
[`JwtTokenProvider`](../../src/main/java/ak/dev/khi_backend/user/jwt/JwtTokenProvider.java),
[`JwtCookieService`](../../src/main/java/ak/dev/khi_backend/user/jwt/JwtCookieService.java).

```mermaid
sequenceDiagram
autonumber
participant Client
participant API as UserAPI
participant Svc as UserService
participant Val as UserValidator
participant Bcrypt as BCryptPasswordEncoder
participant Users as UserRepository
participant Jwt as JwtTokenProvider
participant DB as PostgreSQL
participant Cookie as JwtCookieService

Client->>API: POST /api/auth/register with RegisterRequestDTO
Note over API: Jakarta bean validation on the DTO runs before the method body
API->>Svc: register dto with a null image
Svc->>Val: validateAndNormalizeEmail
Val->>Val: trim and lower-case
Val->>Val: match ValidationPatterns.EMAIL and cap length at 160
Val->>Val: reject 27 disposable domains such as mailinator.com and yopmail.com
Val-->>Svc: normalized lower-case email
Svc->>Val: validatePassword
Val->>Val: length between 6 and 128 and nothing else
Val-->>Svc: ok
Svc->>Users: findByUsername then findByEmail
Users->>DB: two SELECTs on users_tbl
alt username or email already taken
    Svc-->>API: 400 with Token where token is null
    API-->>Client: 400 and no Set-Cookie because the token is blank
else both free
    Svc->>Bcrypt: encode raw password
    Bcrypt-->>Svc: bcrypt hash
    Svc->>Users: save User role GUEST isActivated true failedAttempts 0 expiry now plus 90 days
    Users->>DB: INSERT into users_tbl
    Svc->>Jwt: generateToken user request
    Jwt->>DB: INSERT into sessions with a UUID sessionId User-Agent and remote address
    Jwt->>Jwt: sign HMAC256 with issuer audience sub id ROLE authorities sessionId exp
    Jwt-->>Svc: compact JWT string
    Svc-->>API: 201 CREATED with Token holding the JWT
    API->>Cookie: addAuthCookie response token
    Cookie-->>API: Set-Cookie named by JWT_COOKIE_NAME with HttpOnly SameSite path and max-age
    API-->>Client: 201 carrying the token in the body and the same token in the cookie
end
```

**What to notice**

- The `sessions` row is inserted by `JwtTokenProvider.generateToken` before the JWT is signed,
  because the row's `sessionId` becomes a claim inside the token. Token minting is a write, not a
  pure function.
- `UserValidator.validatePassword` only checks length 6 to 128. Its own Javadoc and the call-site
  comments in `UserService` claim complexity, personal-info and blocklist checks; the method body
  has none of them. The disposable-domain blocklist is real, but it lives in the *email* validator.
- `withAuthCookie` in `UserAPI` is what attaches the cookie, not the service. It only fires when
  the status is 2xx *and* `Token.token` is non-blank, which is why the failure branch above emits
  no cookie even though it also returns a `Token` object. Every cookie attribute comes from
  [`JwtCookieProperties`](../../src/main/java/ak/dev/khi_backend/user/configs/JwtCookieProperties.java),
  whose class defaults are `khi_auth_token`, HttpOnly true, SameSite Strict, path `/`, max-age
  86400 and **Secure false** — `application.yaml` overrides each one from a `JWT_COOKIE_*`
  environment variable, so `JWT_COOKIE_SECURE` must be set to `true` in production.
- The new account is created with `Role.GUEST`. Registration alone grants no write access anywhere
  in `khi_app`; a `SUPER_ADMIN` has to promote the row afterwards.
- A rejected email or a too-short password surfaces to the client as
  `Invalid image: <the real reason>`. `UserService.register` catches `IllegalArgumentException` in
  a block whose message was written for image failures, and both validators throw that same type.

### Registration with a profile image

The multipart variant looks like a small change to the JSON route, but the ordering is the point:
the file is written to disk *before* the `users_tbl` row is built, so a storage failure means the
account is never created at all. This diagram shows where the two multipart parts split and where
the write actually lands.

Source:
[`UserAPI.registerWithImage`](../../src/main/java/ak/dev/khi_backend/user/api/UserAPI.java),
[`UserService.storeProfileImage`](../../src/main/java/ak/dev/khi_backend/user/service/UserService.java).

```mermaid
sequenceDiagram
autonumber
participant Client
participant API as UserAPI
participant Svc as UserService
participant Val as UserValidator
participant Disk as LocalFilesystem
participant Users as UserRepository
participant Jwt as JwtTokenProvider
participant Cookie as JwtCookieService

Client->>API: POST /api/auth/register-with-image as multipart/form-data
Note over Client,API: part named data holds the RegisterRequestDTO JSON and part named image holds the file
API->>Svc: register dto profileImage request
Svc->>Val: validateAndNormalizeEmail then validatePassword
Svc->>Users: findByUsername and findByEmail
Note over Svc: nothing has been persisted yet and the image is handled next
Svc->>Svc: reject the file over 5 MB
Svc->>Svc: reject any content type outside image/jpeg image/png image/gif image/webp
Svc->>Disk: Files.createDirectories on app.upload.dir
Svc->>Disk: Files.copy the stream to uploads/profile-images/UUID.ext
alt the file cannot be stored
    Disk-->>Svc: IOException or a validation failure
    Svc-->>API: 400 Invalid image with the reason or 500 on an IOException
    API-xClient: no users_tbl row and no sessions row were ever written
else the file landed on disk
    Disk-->>Svc: relative path uploads/profile-images/UUID.ext
    Svc->>Users: save User with profileImage set to that relative path
    Svc->>Jwt: generateToken user request
    Jwt-->>Svc: JWT plus a fresh sessions row
    Svc-->>API: 201 CREATED with Token
    API->>Cookie: addAuthCookie
    API-->>Client: 201 with the token in the body and in Set-Cookie
end
```

**What to notice**

- Registration does **not** touch S3. `UserService.storeProfileImage` writes to the local
  filesystem under `app.upload.dir`, default `uploads/profile-images`, and stores a *relative path*
  in `users_tbl.profile_image`. S3 only enters the picture later, through
  [`UserProfileService`](../../src/main/java/ak/dev/khi_backend/user/service/UserProfileService.java)
  behind `POST /api/user/profile-image`, which stores a full public HTTPS URL instead. Two
  different storage backends therefore coexist in one column.
- Because the file write precedes `userRepository.save`, the failure case the question usually
  asks about — storage succeeded, user creation failed — is the one that *can* happen. It leaves an
  orphaned file on disk with no row pointing at it, and nothing ever cleans it up.
- The reverse case cannot happen: there is no user row waiting to be repaired when the upload
  fails, so no compensating delete is needed and none is written.
- `UserService` is annotated `@Transactional`, but every failure is caught inside the method and
  converted to a `ResponseEntity`, so the transaction is never marked rollback-only. The rollback
  safety here comes from the ordering, not from the annotation.
- The 5 MB ceiling is a constant in `UserService`; the servlet container's own multipart limits are
  configured separately in `application.yaml`, so the smaller of the two wins.

### Login

Login never goes through Spring Security's `AuthenticationManager`. `UserService.login` compares
BCrypt hashes itself and maintains the lock counter by hand. This diagram shows the three outcomes
and, more usefully, exactly when the counter is incremented, reset and auto-cleared.

Source: [`UserService.login`](../../src/main/java/ak/dev/khi_backend/user/service/UserService.java),
constants in
[`SecurityConstants`](../../src/main/java/ak/dev/khi_backend/user/consts/SecurityConstants.java)
where `MAX_FAILED_ATTEMPTS` is 5 and `LOCK_DURATION_MINUTES` is 1.

```mermaid
sequenceDiagram
autonumber
participant Client
participant API as UserAPI
participant Svc as UserService
participant Users as UserRepository
participant Bcrypt as BCryptPasswordEncoder
participant Jwt as JwtTokenProvider
participant Cookie as JwtCookieService

Client->>API: POST /api/auth/login with username or email plus password
API->>Svc: login dto request
Svc->>Users: findByUsernameOrEmailExact for the indexed hit
alt phase 1 found nothing
    Svc->>Users: findByUsernameOrEmailIgnoreCase as the fallback
end
alt no such user
    Users-->>Svc: UsernameNotFoundException
    Svc-->>API: 401 Invalid credentials
    API-->>Client: 401 with the same wording used for a wrong password
else user found
    Svc->>Svc: unlockIfLockExpired clears the lock once lockTime plus 1 minute has passed
    alt account is still locked
        Svc-->>API: 403 Account is locked due to 5 failed attempts
        API-->>Client: 403 and no Set-Cookie
    else lock is not active
        Svc->>Bcrypt: matches raw password against the stored hash
        Bcrypt-->>Svc: true or false
        alt password does not match
            Svc->>Svc: recordFailedLoginAttempt increments failed_attempts
            alt the counter has reached 5
                Svc->>Users: save is_locked true and lock_time now
                Svc-->>API: 403 locked for 1 minute or reset the password to regain access at once
            else attempts still remaining
                Svc->>Users: save the new counter
                Svc-->>API: 401 Invalid credentials with the number of attempts left
            end
            API-->>Client: 401 or 403 depending on the branch above
        else password matches
            Svc->>Users: resetFailedAttempts sets the counter to 0 and clears lock_time
            alt password_expiry_date is in the past
                Svc-->>API: 403 Your password has expired
                API-->>Client: 403 with no token
            else password still valid
                Svc->>Jwt: generateToken user request
                Jwt->>Jwt: INSERT a NEW sessions row then sign the JWT
                Jwt-->>Svc: JWT
                Svc-->>API: 200 OK with Token
                API->>Cookie: addAuthCookie
                API-->>Client: 200 with the token in the body and in Set-Cookie
            end
        end
    end
end
```

**What to notice**

- The `DaoAuthenticationProvider` and `AuthenticationManager` beans in
  [`AppConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/AppConfig.java) are wired into
  the security chain but the login endpoint never calls them. `UserService` does the hash
  comparison directly, which is why the lock policy is hand-rolled rather than delegated to
  `UserDetails.isAccountNonLocked`.
- The lock is self-healing and short. `unlockIfLockExpired` runs at the top of every login and on
  every `loadUserByUsername`, so after one minute the account silently unlocks and the counter
  resets to zero. There is no admin unlock step.
- A successful password reset also clears the lock. The 403 message says so explicitly, which makes
  `POST /api/auth/reset-token` a supported way around the lockout.
- Every login inserts another `sessions` row. Nothing caps the count and nothing deactivates the
  previous ones, so a user who logs in from five browsers has five live sessions until they revoke
  them.
- The unknown-user and wrong-password branches both return 401 with wording that differs: the
  wrong-password branch leaks the remaining attempt count, the unknown-user branch does not. That
  difference is enough to distinguish a real account from a fake one.

### Logout and logout-all

The point of these two endpoints is not the response body, it is the pair of writes that make a
stateless token stop working on the very next request. This diagram shows both writes and why
`logout-all` does not need to blacklist any token other than the caller's own.

Source: [`UserAPI.logout` and `UserAPI.logoutAll`](../../src/main/java/ak/dev/khi_backend/user/api/UserAPI.java),
[`TokenService`](../../src/main/java/ak/dev/khi_backend/user/service/TokenService.java).

```mermaid
sequenceDiagram
autonumber
participant Client
participant JwtF as JWTAuthenticationFilter
participant API as UserAPI
participant Tokens as TokenService
participant Sessions as SessionRepository
participant DB as PostgreSQL
participant Cookie as JwtCookieService

Note over JwtF,API: SecurityConfig maps both paths to authenticated so the filter has already validated the token
Client->>JwtF: POST /api/auth/logout with the Bearer header or the cookie
JwtF->>API: authenticated request
API->>API: extractToken reads the Authorization header first then the cookie
alt no token could be extracted
    API->>Cookie: clearAuthCookie
    API-->>Client: 400 Authentication token is missing
else token present
    API->>Tokens: blacklistToken token
    Tokens->>DB: INSERT into token_blacklist unless a row for this token already exists
    Tokens->>Tokens: getSessionIdFromToken reads the sessionId claim
    Tokens->>DB: UPDATE sessions set is_active false and logout_timestamp now
    API->>Cookie: clearAuthCookie writes Set-Cookie with an empty value and max-age 0
    API-->>Client: 200 Successfully logged out
end

Client->>JwtF: POST /api/auth/logout-all
JwtF->>API: authenticated request with the User entity as the principal
API->>Sessions: findByUser which returns every row not only the active ones
Sessions->>DB: SELECT sessions by user_id
API->>Sessions: saveAll with is_active false and logout_timestamp now
API->>Tokens: blacklistToken for the current token only
Tokens->>DB: INSERT into token_blacklist
API->>Cookie: clearAuthCookie
API-->>Client: 200 Logged out from all devices successfully
```

**What to notice**

- A blacklisted token dies immediately because `JWTAuthenticationFilter` calls
  `TokenService.isTokenBlacklisted` on every single request, before it will populate the security
  context. The JWT is still cryptographically valid; it is simply no longer trusted.
- That method returns `true` for four separate reasons: a matching `token_blacklist` row, a missing
  or blank `sessionId` claim, no `sessions` row for that id, or a session that is inactive or past
  its `expires_at`. Only the first is an actual blacklist entry.
- Those extra three conditions are why `logout-all` works. It never sees the other devices' tokens,
  so it cannot blacklist them — it just flips their `sessions` rows to inactive, and the session
  check inside `isTokenBlacklisted` fails those tokens on their next request.
- `logout-all` deactivates rows returned by `findByUser`, which includes already-inactive and
  already-expired rows. It rewrites `logout_timestamp` on all of them, overwriting the historical
  logout time of sessions that ended long ago.
- Clearing the cookie is a courtesy for browser clients. A client holding the raw token in
  JavaScript storage is stopped by the database writes, not by the `Set-Cookie`.

### Password reset

Two endpoints, and the honest version of the second one is more interesting than the first. Note
where the token is checked relative to the password rules, and note that in the default build
nothing is emailed.

Source: [`UserService.createPasswordResetToken` and `resetPassword`](../../src/main/java/ak/dev/khi_backend/user/service/UserService.java),
[`LoggingPasswordResetDeliveryService`](../../src/main/java/ak/dev/khi_backend/user/service/LoggingPasswordResetDeliveryService.java).

Issuing the token:

```mermaid
sequenceDiagram
autonumber
participant Client
participant API as UserAPI
participant Svc as UserService
participant Users as UserRepository
participant DB as PostgreSQL
participant Delivery as LoggingPasswordResetDelivery
participant Logs as ApplicationLog

Client->>API: POST /api/auth/reset-token with the email query parameter
Note over API: the parameter is validated by NotBlank Email and Size 160 on the controller
API->>Svc: createPasswordResetToken email
Svc->>Users: two phase lookup exact then case insensitive
alt no such user
    Svc-->>Client: 200 If an account exists instructions have been prepared
else user found
    Svc->>Svc: UUID.randomUUID becomes the reset token
    Svc->>Users: save reset_token and reset_token_expiration now plus 30 minutes
    Users->>DB: UPDATE users_tbl
    Svc->>Delivery: deliver user token
    Delivery->>Logs: INFO line with userId and email but not the token
    Delivery->>Logs: DEBUG line that contains the raw token
    Svc-->>Client: 200 with the same neutral message as the unknown-user branch
end
Note over Delivery,Logs: LoggingPasswordResetDelivery is the only implementation of the interface so no email is ever sent
```

Redeeming it:

```mermaid
sequenceDiagram
autonumber
participant Client
participant API as UserAPI
participant Svc as UserService
participant Val as UserValidator
participant Bcrypt as BCryptPasswordEncoder
participant Users as UserRepository
participant DB as PostgreSQL

Client->>API: POST /api/auth/reset-password with email resetToken newPassword confirmPassword
API->>Svc: resetPassword dto
Svc->>Users: two phase lookup on the email
alt newPassword does not equal confirmPassword
    Svc-->>Client: 400 New password and confirm password do not match
else they match
    Svc->>Val: validatePassword length 6 to 128
    Svc->>Val: validatePasswordNotReused compares against the current bcrypt hash
    Note over Svc,Val: both password rules run BEFORE the reset token is looked at
    alt reset_token or reset_token_expiration is null
        Svc-->>Client: 400 No reset token found please request a new one
    else the supplied token does not match the stored one
        Svc-->>Client: 400 Invalid reset token
    else reset_token_expiration is in the past
        Svc-->>Client: 400 Reset token expired please request a new one
    else the token is valid
        Svc->>Bcrypt: encode newPassword
        Svc->>Users: save the hash clear reset_token and expiration set expiry now plus 90 days
        Svc->>Users: also clear is_locked failed_attempts and lock_time
        Users->>DB: UPDATE users_tbl
        Svc-->>Client: 200 Password has been successfully reset
    end
end
```

**What to notice**

- Delivery is a log line and nothing more. `LoggingPasswordResetDeliveryService` writes an INFO
  entry naming the user and a DEBUG entry containing the raw token. Whether a reset is usable at
  all therefore depends on the configured log level, and on someone with log access relaying it.
  The `PasswordResetDeliveryService` interface exists so a real provider can be dropped in; nothing
  in the repository implements one.
- Both endpoints return the identical neutral message whether or not the account exists, so the
  request step does not enumerate accounts. The reset step is less careful: an unknown email returns
  `Invalid reset request` while a known email with a bad token returns `Invalid reset token`.
- The token is validated *after* the password rules. A caller with a valid email and a garbage token
  still learns whether their proposed password was reused, because that check runs first.
- `validatePasswordNotReused` throws `IllegalArgumentException`, and `resetPassword` has no catch
  clause for it — it falls into `catch (Exception)` and returns **500**, not the 400 the message
  text implies.
- A successful reset also clears `is_locked`, `failed_attempts` and `lock_time`, which is what makes
  the reset flow a legitimate escape from the five-failed-attempt lockout.
- The reset token lives on `users_tbl` itself, in `reset_token` and `reset_token_expiration`, with a
  30 minute window. There is no separate reset-token table and no cleanup job for expired values.

### Session revocation

`SessionAPI` carries no `@PreAuthorize` at all. `SecurityConfig` only requires that the caller be
authenticated, so the entire protection for someone else's session is a hand-written owner check in
the handler body. This diagram shows the ordering of the four exits.

Source: [`SessionAPI.revokeSession`](../../src/main/java/ak/dev/khi_backend/user/api/SessionAPI.java),
rule `.requestMatchers("/api/auth/sessions/**").authenticated()` in
[`SecurityConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/SecurityConfig.java).

```mermaid
sequenceDiagram
autonumber
participant Client
participant JwtF as JWTAuthenticationFilter
participant Authz as AuthorizationFilter
participant Ctrl as SessionAPI
participant Sessions as SessionRepository
participant DB as PostgreSQL

Client->>JwtF: DELETE /api/auth/sessions/SESSION_ID with a Bearer token
JwtF->>JwtF: validate the token and confirm it is not revoked
JwtF->>Authz: SecurityContext principal is the User entity
Authz->>Authz: /api/auth/sessions/** requires only authenticated
Authz->>Ctrl: invoke revokeSession with AuthenticationPrincipal User
alt principal is null
    Ctrl-->>Client: 401 Not authenticated as a plain string body
else principal present
    Ctrl->>Sessions: findBySessionId
    Sessions->>DB: SELECT sessions by session_id
    alt no such session
        Ctrl-->>Client: 404 Session not found
    else session belongs to a different user
        Note over Ctrl: session.user.userId is compared against principal.userId
        Ctrl-->>Client: 403 You can only revoke your own sessions
    else caller owns the session
        Ctrl->>Sessions: set is_active false and logout_timestamp now then save
        Sessions->>DB: UPDATE sessions
        Ctrl-->>Client: 200 Session revoked successfully
    end
end
```

**What to notice**

- Any authenticated role reaches this handler, including a freshly registered `GUEST`. Nothing but
  the `userId` comparison stops one user from revoking another's session, so that one line is the
  authorization control.
- The 404 is returned before the ownership check, so a caller can probe whether an arbitrary
  `sessionId` exists — 404 for unknown, 403 for someone else's.
- The 403 here is a bare string body, not the `ApiErrorResponse` that `khi_app` returns. It is
  produced by the controller rather than raised as `AccessDeniedException`, so the global advice
  never sees it and there is no `traceId` or bilingual message in the payload.
- Revoking does not blacklist the revoked device's token. It does not need to: the next request
  from that device fails inside `TokenService.isTokenBlacklisted`, which treats an inactive
  `sessions` row exactly like a blacklisted token.
- `@AuthenticationPrincipal User` resolves only because `JwtTokenProvider.getAuthentication` sets
  the concrete `User` entity as the principal. If the filter had put a bare username string there,
  as an earlier revision did, this handler would see null and return 401 for everyone.

---

## Content and media

### Creating content with media

The multipart create is the most misread path in the codebase. The endpoint table says
`POST /api/v1/image-collections` takes a `data` part plus files; what it does not say is that every
byte reaches S3 *before* the first `INSERT` is issued, and that S3 has no part in the database
transaction. Image Collections show this most clearly because a single request can touch S3 up to
three times for covers, once more per inline editor asset, and once per album image.

Source:
[`ImageCollectionController`](../../src/main/java/ak/dev/khi_backend/khi_app/api/publishment/image/ImageCollectionController.java),
[`ImageCollectionService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/publishment/image/ImageCollectionService.java),
[`S3Service`](../../src/main/java/ak/dev/khi_backend/khi_app/service/S3Service.java).

```mermaid
sequenceDiagram
autonumber
participant Client as Dashboard
participant Ctl as ImageCollectionController
participant Svc as ImageCollectionService
participant Tiptap as TiptapHtmlProcessor
participant S3 as S3Service
participant Repos as JpaRepositories
participant DB as PostgreSQL
participant Redis as Redis

Client->>Ctl: POST /api/v1/image-collections multipart with data plus covers plus images
Ctl->>Ctl: objectMapper.readValue(dataJson, CreateRequest.class)
Ctl->>Svc: create(dto, ckbCover, kmrCover, hoverCover, images)
Note over Svc,DB: the transaction opens here and @CacheEvict has not fired yet
Svc->>Svc: validateCreate checks collectionType, contentLanguages and that some cover was supplied
Svc->>S3: upload(bytes, filename, contentType) once per supplied cover
S3->>S3: detectFolder(contentType) then generateKey(folder, filename)
S3-->>Svc: up to three permanent https URLs
Svc->>Repos: topicRepository.save(newTopic) only when the request inlined one
Repos->>DB: INSERT publishment_topics
Svc->>Tiptap: process(description) for the CKB and KMR ImageContent
Tiptap->>S3: upload(decoded base64 bytes) for every inline data URI found
Tiptap-->>Svc: HTML that now holds S3 URLs only
Svc->>Svc: buildAlbumItems validates the item count for SINGLE, GALLERY or PHOTO_STORY
loop once per album item
    Svc->>Tiptap: process(descriptionCkb) and process(descriptionKmr)
    Svc->>Svc: extractAndSetImageMetadata reads the bytes once and pulls width and height from ImageIO
    Svc->>S3: upload(fileBytes, filename, contentType)
    S3-->>Svc: URL written onto the ImageAlbumItem
end
Svc->>Repos: imageCollectionRepository.save(entity)
Repos->>DB: INSERT image_collections including the title_ckb and title_kmr embedded columns
Repos->>DB: INSERT image_album_items plus the tag, keyword and language side tables
Svc->>Repos: imageCollectionLogRepository.save(log CREATE)
Repos->>DB: INSERT image_collection_logs
alt every statement commits
    DB-->>Svc: commit
    Svc-)Redis: @CacheEvict imageCollections allEntries fires after the method returns
    Svc-->>Ctl: Response built by toResponse
    Ctl-->>Client: 201 CREATED wrapped in ApiResponse.success
else any INSERT or later check fails
    DB--xSvc: rollback
    Note over Svc,S3: no row survives but every object already written to S3 stays in the bucket
    Ctl--xClient: 400 IMAGE_VALIDATION or 502 STORAGE_ERROR as ApiErrorResponse
end
```

**What to notice**

- S3 comes first, always. `resolveCoverUrl` uploads before `imageCollectionRepository.save` is even
  reached, and the transaction rollback has no compensating delete. A failed create leaves orphaned
  objects under `khi-web-folders/images/`; a failed *upload* by contrast leaves no row, which is the
  safer of the two failures.
- `create` and `update` disagree about validation order. `create` uploads the covers and *then* lets
  `buildAlbumItems` call `validateAlbumItemCount`, so a `SINGLE` collection sent with three images
  orphans its cover before failing. `update` was fixed for this — it calls `validateAlbumUpdate`
  before any upload, with a comment saying exactly why.
- Metadata does **not** come from `MediaMetadataExtractor`. `extractAndSetImageMetadata` uses
  `javax.imageio.ImageIO` inline in the service and only fills `widthPx`, `heightPx`,
  `fileSizeBytes` and `mimeType`. Extraction failure is caught and logged, never fatal.
- The audit row is written through a *different* repository in the same transaction, and it stores
  `imageCollectionId` as a plain `Long` with no foreign key — which is what makes the delete path
  further down leave its history behind.
- `@CacheEvict` carries the default `beforeInvocation = false`, so a create that throws never evicts.
  That is harmless here, but it is the same default that creates the stale window shown later.

### The Tiptap editor image pipeline

An admin writing an article produces HTML with `img` tags in it, and those images can arrive by two
completely different routes. The clean route uploads first and embeds a permanent URL. The dirty
route pastes the picture straight into the editor, which produces a base64 data URI, and it is
`TiptapHtmlProcessor` that stops that binary from ever reaching a `TEXT` column.

Source:
[`MediaController`](../../src/main/java/ak/dev/khi_backend/khi_app/api/media/MediaController.java),
[`MediaService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/media/MediaService.java),
[`TiptapHtmlProcessor`](../../src/main/java/ak/dev/khi_backend/khi_app/service/media/TiptapHtmlProcessor.java).

```mermaid
sequenceDiagram
autonumber
participant Editor as TiptapEditor
participant MC as MediaController
participant MS as MediaService
participant CC as ContentController
participant CS as ContentService
participant TP as TiptapHtmlProcessor
participant S3 as S3Service
participant DB as PostgreSQL

Note over Editor,MC: SecurityConfig maps /api/v1/media/** to ADMIN or SUPER_ADMIN, above the public GET rules
Editor->>MC: POST /api/v1/media/upload with a file part and an optional type hint
MC->>MC: the controller defaults a null or blank type to image
MC->>MS: upload(file, resolvedType)
MS->>MS: resolveMediaType maps image and gallery to IMAGE, video to VIDEO, audio to AUDIO, document and pdf to DOCUMENT
MS->>S3: upload(file getInputStream, size, filename, contentType, mediaType)
S3->>S3: RequestBody.fromContentProvider streams the file without materialising it in the heap
S3-->>MS: permanent https URL under khi-web-folders
MS-->>MC: UploadResponse with fileUrl, fileName, fileSize and contentType
MC-->>Editor: 200 ApiResponse.success
Editor->>Editor: writes that URL into the src attribute of the image node

Editor->>CC: POST or PUT the content with the editor HTML in the bilingual description
CC->>CS: create or update
CS->>TP: process(html)
alt the HTML holds no data prefix
    TP-->>CS: the identical string, returned by the early out before any regex runs
else the HTML holds a well formed inline base64 asset
    TP->>TP: DATA_URI_SRC matches src on img, video, audio and source tags
    TP->>TP: Base64 getDecoder decode then filename tiptap plus nanoTime plus extensionFor(mime)
    TP->>S3: upload(bytes, filename, mime, mediaTypeFor(mime))
    S3-->>TP: public URL
    TP->>TP: rewrites that one attribute, then a second pass runs DATA_URI_HREF for PDF download links
    TP-->>CS: rewritten HTML
else the base64 payload is malformed or the upload throws
    TP->>TP: catches the exception, logs a warning and counts it as failed
    TP--xCS: replacement is m.group(0), so the original data URI is handed straight back
end
CS->>DB: persists whatever process returned into the description TEXT column
```

**What to notice**

- The two routes converge but are not equally guarded. `/api/v1/media/**` is **ADMIN or
  SUPER_ADMIN**, while `POST /api/v1/videos/**` and the other content writes are **EMPLOYEE and
  above**. An EMPLOYEE can therefore create an article but gets a denial from the upload endpoint,
  and their only working option is to paste — which lands them on the base64 branch.
- The failure branch is a silent success. A malformed payload does not fail the save; the original
  `data:...;base64,...` attribute is written into the `TEXT` column verbatim. The row is valid, the
  page renders, and the database is now storing binary in a text field with no error anywhere.
- `process` is idempotent by design. Re-saving already-clean HTML costs one `contains("data:")`
  check, which is why every module can call it unconditionally on every write.
- Two regex passes, not one: `src` first for images, video, audio and `source`, then `href` for
  `a` tags carrying PDFs and documents. An asset embedded as a download link is handled by the
  second pass only.
- Nothing ever deletes what this uploads. `MediaController` has a `DELETE` endpoint, but its own
  javadoc calls it reserved for future orphan cleanup; replacing an image in the editor abandons the
  previous object.

### Cached read

`@Cacheable` is only wired on five caches, and the interesting part is what the key looks like on
the wire. The Redis key is not the Spring key — Spring Boot prepends `khi:` **and** the cache name
**and** a double colon. Seeing the composed key is what makes the eviction diagram below legible.

Source:
[`ImageCollectionService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/publishment/image/ImageCollectionService.java),
[`CacheConfig`](../../src/main/java/ak/dev/khi_backend/khi_app/config/CacheConfig.java),
[`application.yaml`](../../src/main/resources/application.yaml).

```mermaid
sequenceDiagram
autonumber
participant A as VisitorA
participant B as VisitorB
participant Ctl as ImageCollectionController
participant Svc as ImageCollectionService
participant Redis as Redis
participant Repo as ImageCollectionRepository
participant DB as PostgreSQL

A->>Ctl: GET /api/v1/image-collections with page 0 and size 20
Ctl->>Svc: getAll(0, 20)
Note over Svc,Redis: the annotation is @Cacheable on imageCollections with key all p plus page plus s plus size
Svc->>Redis: GET khi:imageCollections::all:p0:s20
Redis-->>Svc: nil
Svc->>Repo: findAllIds(PageRequest.of(0, 20))
Repo->>DB: SELECT ic.id ORDER BY publishment_date DESC and created_at DESC
DB-->>Repo: a page of at most 20 ids
Svc->>Repo: findAllByIds(ids)
Repo->>DB: SELECT ic WHERE ic.id IN the 20 ids
DB-->>Repo: bare rows, then @BatchSize IN queries hydrate album, tags, keywords, languages and topic
Repo-->>Svc: entities reordered by hydrateAndSort to match the id page
Svc->>Svc: toResponse builds a PageImpl of ImageCollectionDTO.Response
Svc->>Redis: SET khi:imageCollections::all:p0:s20 with a 600000 ms TTL
Note over Svc,Redis: cache-null-values is false, so an empty page is still cached but a null return would not be
Ctl-->>A: 200 ApiResponse.success

B->>Ctl: GET /api/v1/image-collections with page 0 and size 20
Ctl->>Svc: getAll(0, 20)
Svc->>Redis: GET khi:imageCollections::all:p0:s20
Redis-->>Svc: the serialized PageImpl
Note over Svc,DB: neither repository method is called and no SQL is issued on this request
Ctl-->>B: 200 ApiResponse.success
```

**What to notice**

- The composed Redis key is `khi:` + cache name + `::` + the SpEL key. `use-key-prefix: true` with
  `key-prefix: "khi:"` produces `khi:imageCollections::all:p0:s20`, not `khi:all:p0:s20`.
- Spring Boot's Redis cache serializes with **JDK serialization**, so every type reachable from the
  return value must implement `Serializable` — including `PageImpl`. Each cached DTO pins
  `serialVersionUID = 1L` on purpose: without it the JVM derives the ID from the class structure and
  merely adding a field makes every pre-deploy entry fail to read back with `InvalidClassException`
  until the 10 minute TTL flushes it. `CacheSerializationTests` fails the build if a
  non-`Serializable` field creeps in.
- Only five caches exist — `news`, `projects`, `soundTracks`, `imageCollections`, `services`.
  Videos and Writings have **no** cache annotations at all, so every video listing is a live query.
- `getById`, `getBySlug` and `getFeatured` are deliberately uncached; only the paginated list and
  search shapes are. That keeps single-item reads correct at the cost of a query.
- `CacheConfig` defines no `RedisCacheConfiguration` bean on purpose — declaring one would replace
  the property-derived config wholesale and silently drop both the TTL and the key prefix.

### Cache eviction on write

Every cached service evicts with `allEntries = true` on its write paths, which sounds airtight. It
is not, and the reason is ordering rather than coverage: a reader that misses the cache before the
write can complete its Redis `SET` after the writer's `DEL`.

Source:
[`ImageCollectionService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/publishment/image/ImageCollectionService.java),
[`SiteContentService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java).

```mermaid
sequenceDiagram
autonumber
participant Vis as Visitor
participant Adm as Admin
participant Svc as ImageCollectionService
participant Redis as Redis
participant DB as PostgreSQL

par reader thread
    Vis->>Svc: getAll(0, 20) against a cold key
    Svc->>Redis: GET khi:imageCollections::all:p0:s20
    Redis-->>Svc: nil
    Svc->>DB: findAllIds then findAllByIds
    DB-->>Svc: the page exactly as it stands before the edit
and writer thread
    Adm->>Svc: update(id, dto, covers, images)
    Note over Adm,Svc: @CacheEvict imageCollections allEntries with the default beforeInvocation false
    Svc->>DB: UPDATE image_collections and rewrite image_album_items
    DB-->>Svc: commit
    Svc->>Redis: DEL every key under khi:imageCollections
end
Svc->>Redis: SET khi:imageCollections::all:p0:s20 from the reader that started first
Note over Svc,Redis: the write lands after the delete, so the pre-edit page is now the cached page
Vis->>Svc: any later getAll(0, 20)
Svc->>Redis: GET khi:imageCollections::all:p0:s20
Redis-->>Svc: the pre-edit page, served until the 10 minute TTL expires
```

**What to notice**

- `beforeInvocation` is left at its default `false` everywhere, so eviction runs *after* the method
  body, never before it. There is no transaction-aware cache manager configured, so nothing pins the
  eviction to the commit either. The read-through race above is the direct consequence, and its
  worst case is a full TTL of stale data.
- `allEntries = true` means one image-collection edit clears every cached image page — every
  `type:`, `tag:`, `keyword:` and `topic:` key — not just the page that changed. Eviction is blunt
  rather than surgical, which is the right trade for how rarely these write.
- Caches are per-domain and never cross. Editing an image collection does not touch `news`,
  `projects`, `soundTracks` or `services`.
- The featured toggles are the real gap. `setImageCollectionFeatured`, `setNewsFeatured`,
  `setProjectFeatured`, `setVideoFeatured`, `setSoundTrackFeatured` and `setWritingFeatured` carry
  **no** `@CacheEvict` at all. Only `setServiceFeatured` evicts. Since `featureImageUrl` is part of
  the cached `Response`, changing a hero image through
  `PATCH /api/v1/image-collections/{id}/featured` leaves the old URL in Redis for up to ten minutes.
  The hero rail itself is safe because `getFeatured` is uncached.

### Deleting a content root

Deleting a content root is where JPA and S3 part company for good. JPA cleans up thoroughly. S3 is
not consulted at all.

Source:
[`ImageCollectionService.delete`](../../src/main/java/ak/dev/khi_backend/khi_app/service/publishment/image/ImageCollectionService.java),
[`ImageCollection`](../../src/main/java/ak/dev/khi_backend/khi_app/model/publishment/image/ImageCollection.java),
[`SecurityConfig`](../../src/main/java/ak/dev/khi_backend/user/configs/SecurityConfig.java).

```mermaid
sequenceDiagram
autonumber
participant Adm as Admin
participant Sec as SecurityFilterChain
participant Ctl as ImageCollectionController
participant Svc as ImageCollectionService
participant Repos as JpaRepositories
participant DB as PostgreSQL
participant S3 as S3Bucket
participant Redis as Redis

Adm->>Sec: DELETE /api/v1/image-collections/42 with a Bearer token
Sec->>Sec: the DELETE matcher over the content prefixes requires ADMIN or SUPER_ADMIN
Sec->>Ctl: delete(42) which carries no @PreAuthorize of its own
Ctl->>Svc: delete(42)
Svc->>Repos: findByIdWithGraph(42)
Repos->>DB: SELECT with the entity graph over imageAlbum, topic, tags, keywords and languages
alt no such row
    DB-->>Svc: empty Optional
    Svc-->>Ctl: logged at debug and returns, the delete is idempotent
    Ctl-->>Adm: 204 NO CONTENT
else the row exists
    Svc->>Repos: imageCollectionLogRepository.save(log DELETE)
    Repos->>DB: INSERT image_collection_logs holding an id and title snapshot
    Svc->>Repos: imageCollectionRepository.delete(entity)
    Repos->>DB: DELETE image_album_items through cascade ALL plus orphanRemoval
    Repos->>DB: DELETE image_collection_languages, image_tags_ckb, image_tags_kmr, image_keywords_ckb and image_keywords_kmr
    Repos->>DB: DELETE image_collections, which takes title_ckb and title_kmr with it
    Note over Repos,DB: publishment_topics is a shared ManyToOne target so the topic row survives
    Note over DB,S3: nothing in this path calls S3Service, so every cover and album object stays in the bucket
    Svc-)Redis: @CacheEvict imageCollections allEntries after the method returns
    Ctl-->>Adm: 204 NO CONTENT
end
```

**What to notice**

- **No S3 delete happens here, or on any other content delete.** Across the whole service layer the
  only calls to `deleteFile` / `deleteFiles` / `deleteByKey` are in `MediaService.delete` and in the
  two singleton promo-video paths. Deleting a video, an image collection, a news item, a project, a
  writing or a sound track orphans every object it uploaded, permanently.
- The embedded bilingual content has no table of its own to delete. `title_ckb` and `title_kmr` are
  columns on `image_collections`, so they disappear with the parent row and are invisible in the
  cascade.
- Audit rows deliberately outlive the entity. `image_collection_logs.image_collection_id` is a plain
  `Long` with no foreign key, so the `DELETE` row remains queryable after the collection is gone.
- The log row is written *before* `repository.delete`, so a rollback drops both together — you never
  get a `DELETE` log entry for a delete that did not happen.
- Deleting a non-existent id is a 204, not a 404. Both `ImageCollectionService.delete` and
  `VideoService.deleteVideo` return silently on a missing row.

### The one delete path that cleans up S3

The singleton promo-video endpoints are the only deletes that also remove the object from the
bucket — and they do it in an order that can leave a dead URL behind. This is the exact inverse of
the failure above, which is why it is worth drawing beside it.

Source:
[`VideoService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/publishment/video/VideoService.java).

```mermaid
sequenceDiagram
autonumber
participant Adm as Admin
participant Svc as VideoService
participant Repo as FilmReklamVideoRepository
participant DB as PostgreSQL
participant S3 as S3Service

Adm->>Svc: DELETE /api/v1/videos/film-reklam-video
Note over Svc,DB: @Transactional opens here
Svc->>Repo: findTopByOrderByIdAsc()
Repo->>DB: SELECT the singleton row
DB-->>Svc: FilmReklamVideo carrying its videoUrl
Svc->>Repo: delete(reklamVideo)
Svc->>S3: deleteFile(videoUrl)
S3->>S3: extractKeyFromUrl then DeleteObjectRequest against s3-khiwebsite
Note over Svc,S3: the object is removed inside the transaction, before the commit
alt the commit succeeds
    DB-->>Svc: row gone and object gone, consistent
else the commit fails
    DB--xSvc: rollback restores the row
    Note over Svc,S3: the restored row now points at an object that no longer exists
end
```

**What to notice**

- The delete is issued *inside* the transaction and cannot be undone by a rollback. This is the dead
  URL case: the row comes back, the object does not.
- `updateFilmReklamVideo` deliberately inverts the order — it uploads the replacement, saves the
  row, and only then deletes the previous object — so a failed upload leaves the existing video
  untouched. That is the safe ordering; the delete path did not get it.
- `SoundTrackService` has exactly the same pair of singleton promo-video methods with the same
  ordering, so the behaviour applies to the sound-reklam video too.

### Global search

One endpoint fans out over six content models. The interesting question is whether the fan-out is
concurrent — it looks like a natural place for `CompletableFuture` — and the answer from the code is
no. Six sections run one after another on the request thread, each doing exactly two queries.

Source:
[`GlobalSearchController`](../../src/main/java/ak/dev/khi_backend/khi_app/api/search/GlobalSearchController.java),
[`GlobalSearchService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/search/GlobalSearchService.java).

```mermaid
sequenceDiagram
autonumber
participant Client as Website
participant Ctl as GlobalSearchController
participant Svc as GlobalSearchService
participant Repo as ContentRepositories
participant DB as PostgreSQL

Client->>Ctl: GET /api/v1/search with q, type ALL, page 0 and size 10
Ctl->>Svc: search(q, type, page, size)
Svc->>Svc: query is q trimmed, typeUpper is type uppercased, pageable is PageRequest.of(page, size)
Note over Svc: searchesType returns true when typeUpper is ALL or equals the section name
opt searchesType PROJECT
    Svc->>Repo: projectRepo.findIdsByGlobalSearch(query, pageable)
    Repo->>DB: SELECT DISTINCT id over titles, descriptions, tags, keywords and topic names
    DB-->>Svc: a Page of Long
    alt the id page is empty
        Svc->>Svc: SearchSection.empty(page, size) and the second query is skipped
    else ids were returned
        Svc->>Repo: projectRepo.findAllByIds(ids)
        Repo->>DB: SELECT p WHERE p.id IN the ids, scalar columns only
        DB-->>Svc: bare entities with no collection joins
        Svc->>Svc: reindex by id to restore the ordered page, then build one SearchItem each
        Svc->>Svc: snippet strips Tiptap tags, collapses whitespace and truncates at 200 characters
    end
end
Note over Svc,DB: NEWS, VIDEO, WRITING, SOUNDTRACK and IMAGE then repeat the identical two-phase shape in that order
Note over Svc,DB: nothing runs in parallel and nothing is async, so type ALL costs twelve sequential queries
Svc->>Svc: builder assembles GlobalSearchResponse with query, page, size, type and the six sections
Svc-->>Ctl: GlobalSearchResponse
Ctl-->>Client: 200 ApiResponse.success with the message Search completed
```

**What to notice**

- **Sequential, not parallel.** The six calls are plain `if` statements against a builder on one
  thread. Latency is the sum of twelve queries, not the max of six pairs.
- Two phases exist to dodge N+1, not to page twice. Phase one returns ids only so `DISTINCT` over
  the tag and keyword join tables stays cheap; phase two loads scalar columns and never touches a
  collection, so the `@BatchSize` annotations are never triggered.
- `size` is per **section**, not per response. `type=ALL` with `size=10` can return sixty items, and
  each section carries its own `totalElements` and `totalPages`.
- Sections not requested are `null`, not empty. With `type=NEWS` the other five fields are absent
  from the JSON entirely, which is a different shape from an empty section.
- Two parameters do less than they look like. `locale` is accepted by the controller and never
  passed to `search`, so every `SearchItem` carries both dialects regardless; and `q` is optional in
  effect, because a null or blank value becomes `""` and the `LIKE %%` pattern matches everything —
  which quietly turns the endpoint into an unfiltered browse.

### Assembling the featured rail

`GET /api/v1/featured` looks like it should read a curated `featured_items` table. It does not. The
rail is assembled at request time from a boolean column on seven different tables, sorted globally,
and then cut to a limit that lives in `site_settings`.

Source:
[`PublicSiteController`](../../src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java),
[`LegacyFeaturedController`](../../src/main/java/ak/dev/khi_backend/khi_app/api/site/LegacyFeaturedController.java),
[`SiteContentService.getFeatured`](../../src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java).

```mermaid
sequenceDiagram
autonumber
participant Site as Website
participant P as PublicSiteController
participant L as LegacyFeaturedController
participant Svc as SiteContentService
participant Repos as ContentRepositories
participant DB as PostgreSQL

alt current route
    Site->>P: GET /api/v1/featured with an optional locale
    P->>Svc: getFeatured(locale)
else compatibility route kept for the deployed client
    Site->>L: GET /featured with an optional locale
    L->>Svc: getFeatured(locale)
end
Svc->>Svc: resolveFeaturedLocale then a kmr boolean that picks which embedded content to read
Svc->>Repos: newsRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc
Svc->>Repos: projectRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc
Svc->>Repos: writingRepository.findFeaturedWithTopic
Svc->>Repos: videoRepository.findFeaturedWithTopic
Svc->>Repos: soundTrackRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc
Svc->>Repos: imageCollectionRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc
Repos->>DB: six independent SELECTs on the featured boolean of six content tables
DB-->>Svc: the flagged rows of each type
Svc->>Repos: donationSettingsRepository.findAll then findFirst then filter on isFeatured
Repos->>DB: SELECT donation_settings
DB-->>Svc: at most one singleton row, since donation is a settings row not a list
Note over Svc: Service and About are excluded on purpose, their flag highlights them on their own page instead
Svc->>Svc: each row becomes a FeaturedResponse from its bilingual content plus featureImageUrl over the cover
Svc->>Svc: sort by featuredOrder with null pushed to MAX_VALUE, ties broken by id descending
Svc->>Repos: siteSettingsRepository.findFirstByOrderByIdAsc
Repos->>DB: SELECT site_settings for maxFeaturedSlides
Svc->>Svc: truncate to maxFeaturedSlides then renumber displayOrder from 1
Note over Repos,DB: featured_items exists as a table with a repository, but FeaturedItemRepository is injected nowhere and getFeatured never reads it
alt served by PublicSiteController
    P-->>Site: 200 a bare JSON array of FeaturedResponse
else served by LegacyFeaturedController
    L-->>Site: 200 the same array wrapped in ApiResponse with success and message
end
```

**What to notice**

- **`featured_items` is dead weight.** The entity, the table and `FeaturedItemRepository` all exist,
  but no class injects the repository. The rail is built purely from the per-entity `featured` /
  `featuredOrder` / `featureImageUrl` columns. Anything written into `featured_items` is invisible
  to the site.
- Two controllers, two response shapes, one service. `/api/v1/featured` returns a bare array;
  `/featured` returns the same array inside `ApiResponse`. `GET /featured` is `permitAll` by an
  explicit matcher, `/api/v1/featured` by the blanket `GET /api/v1/**` rule.
- `featured` means two different things depending on the domain. For the six publication types plus
  Donations it means "hero slide". For **Services and About** it means "highlight on your own page"
  — they are never collected here and take no share of `maxFeaturedSlides`.
- The cap is enforced on the **write** side, not the read side. Each `set...Featured` method calls
  `countAllFeatured()` and throws `IllegalStateException` — which `GlobalExceptionHandler` renders
  as `400` with `code = BAD_REQUEST` — when turning a flag on would exceed `maxFeaturedSlides`
  (default 7). Turning a flag *off* skips the check, and an already-featured record skips it too.
- A row with no `featuredOrder` is not dropped; it sorts to `Integer.MAX_VALUE` and competes at the
  back, broken by newest id first. `displayOrder` in the response is recomputed 1..N and is not the
  stored `featuredOrder`.

### Public submission and the admin queue

The contact form is the only write path an anonymous visitor can reach, and it demonstrates both
halves of the two-layer authorization model in one round trip: `permitAll` on the way in, a role
matcher on the way out, and a status field that any admin can move anywhere. The filter steps at
the top are the same ones drawn in
[The authenticated request](#the-authenticated-request); they are repeated here only to show that
an anonymous write is not exempt from them.

Source:
[`PublicSiteController`](../../src/main/java/ak/dev/khi_backend/khi_app/api/site/PublicSiteController.java),
[`SiteContentService`](../../src/main/java/ak/dev/khi_backend/khi_app/service/site/SiteContentService.java),
[`JWTAuthenticationFilter`](../../src/main/java/ak/dev/khi_backend/user/jwt/JWTAuthenticationFilter.java).

```mermaid
sequenceDiagram
autonumber
participant Vis as AnonymousVisitor
participant Adm as Admin
participant TF as TraceIdFilter
participant JF as JWTAuthenticationFilter
participant Sec as AuthorizationFilter
participant Ctl as PublicSiteController
participant Svc as SiteContentService
participant DB as PostgreSQL

Vis->>TF: POST /api/v1/contact/messages with no Authorization header and no cookie
TF->>TF: generates a UUID, puts it in MDC as traceId and echoes it as X-Trace-Id
TF->>JF: continue the chain
JF->>JF: shouldNotFilter is false here since only the auth endpoints are exempt, so the filter does run
JF->>JF: resolveToken finds neither a Bearer header nor the JWT cookie, so it calls the chain with an empty SecurityContext
JF->>Sec: continue the chain
Sec->>Sec: POST /api/v1/contact/messages is an explicit permitAll matcher, so the empty context is accepted
alt the body satisfies @Valid
    Sec->>Ctl: submitContactMessage(request)
    Ctl->>Svc: submitContactMessage(request)
    Svc->>Svc: builds ContactMessage with trimmed name, email and subject and a hardcoded status NEW
    Svc->>DB: INSERT contact_messages with status NEW and a CreationTimestamp
    DB-->>Svc: generated id
    Ctl-->>Vis: 201 CREATED with the stored ContactMessageResponse
else a @NotBlank, @Email or @Size constraint fails
    Ctl--xVis: 400 from GlobalExceptionHandler with code VALIDATION_ERROR and one fieldErrors entry per field
end

Adm->>JF: GET /api/v1/contact/messages with a Bearer token
JF->>JF: getSubject, then the TokenBlacklist check, then loadUserByUsername into SecurityContextHolder
JF->>Sec: continue the chain
alt the caller holds ADMIN or SUPER_ADMIN
    Sec->>Ctl: getContactMessages(page, size)
    Ctl->>Svc: getContactMessages(page, size)
    Svc->>DB: SELECT contact_messages newest first
    Ctl-->>Adm: 200 a Page of ContactMessageResponse
else anonymous, EMPLOYEE or GUEST
    Sec--xAdm: rejected by the matcher before the controller is ever reached
end

Adm->>Ctl: PATCH /api/v1/contact/messages/7/status with a status string
Ctl->>Svc: updateContactMessageStatus(7, request)
Svc->>Svc: validateStatus trims, upper cases and checks membership of the seven allowed values
Svc->>DB: UPDATE contact_messages SET status
Note over Svc,DB: there is no transition guard, so CLOSED can go straight back to NEW in one call
Ctl-->>Adm: 200 the updated ContactMessageResponse
```

**What to notice**

- The JWT filter still runs on the anonymous request. `shouldNotFilter` only exempts the auth
  endpoints, so `POST /api/v1/contact/messages` passes through `JWTAuthenticationFilter`, which
  simply finds no token and calls the chain with an empty `SecurityContext`. The submission is
  authorized by the `permitAll` matcher, not by skipping the filter.
- Read and write asymmetry is the whole point. `POST` is `permitAll`; `GET` and `PATCH` on the same
  resource are matched to `ADMIN, SUPER_ADMIN`. An EMPLOYEE who can create news articles cannot read
  a single contact message.
- The status column is a plain `varchar(30)`, not an enum, and `validateStatus` only checks
  membership in `{NEW, PENDING, IN_REVIEW, APPROVED, COMPLETED, REJECTED, CLOSED}`. It never looks
  at the current value, so every one of the 49 transitions is legal. The same validator serves both
  donation types, which start at `PENDING` while contact messages start at `NEW`. The state machine
  is drawn in [`UML_STATE.md`](./UML_STATE.md#submission-status).
- An unknown status raises `IllegalArgumentException`, which `GlobalExceptionHandler` renders as
  `400` with `code = BAD_REQUEST` and `details.reason` — not `VALIDATION_ERROR`, which is reserved
  for `@Valid` field failures.
- Path-level denials never produce an `ApiErrorResponse`. `SecurityConfig` registers no
  `.exceptionHandling(...)`, so `JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` are never
  installed and a matcher rejection is answered by Spring Security's own defaults. Only a
  `@PreAuthorize` denial reaches `GlobalExceptionHandler.handleAccessDenied` and comes back as a
  proper `403 FORBIDDEN` body with `traceId` and bilingual messages.

---

## Corrections against the catalogue

Everything below was drawn from the code rather than the written brief, and disagrees with it.

1. **The `user` package has no `GlobalExceptionHandler`.**
   `user/exceptions/GlobalExceptionHandler.java` contains only a private placeholder class,
   `UserExceptionHandlerPlaceholder`, with a comment explaining that two beans of that name collide
   at startup. There is exactly one `@RestControllerAdvice` in the application, in `khi_app`, and it
   is unscoped, so it also handles exceptions thrown by `user/api` controllers.
2. **`JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` are dead code.** `SecurityConfig`
   never calls `.exceptionHandling(...)`, and nothing else in the source references either class.
   Spring Security's built-in defaults handle filter-level rejection instead, so an unauthenticated
   request to a protected path returns 403 rather than 401.
3. **`JWTAuthenticationFilter` writes its own error responses.** Expired, invalid and revoked tokens
   are answered with a two-field JSON body straight from the filter, never as `ApiErrorResponse`,
   and an invalid signature yields 403 while an expired token yields 401.
4. **Registration writes profile images to local disk, not to S3.**
   `UserService.storeProfileImage` uses `Files.copy` into `app.upload.dir`. S3 is used only by
   `UserProfileService`, behind `POST /api/user/profile-image`. The registration flow stores a
   relative path; the profile flow stores a full HTTPS URL, in the same column.
5. **The image is stored before the user row, not after.** The failure mode is an orphaned file with
   no row, never a user row with a broken image reference.
6. **`UserValidator.validatePassword` enforces length only**, 6 to 128 characters. There are no
   complexity rules, no personal-information checks and no password blocklist, despite the Javadoc
   on the method and the comments at every call site claiming all three. The disposable-domain
   blocklist is real but belongs to `validateAndNormalizeEmail`.
7. **`UserService.register` mislabels validation failures.** Its `catch (IllegalArgumentException)`
   block prefixes every message with `Invalid image: `, and both the email and password validators
   throw that type, so a disposable-email rejection is reported to the client as an image error.
8. **`UserService.resetPassword` returns 500 for a reused password.**
   `validatePasswordNotReused` throws `IllegalArgumentException`, which falls through to the generic
   `catch (Exception)` and produces `An error occurred while resetting your password.`
9. **`MediaMetadataExtractor` is never called in production code.** The brief lists it as a
   cross-cutting concern beside `S3Service` and `TiptapHtmlProcessor`. It is a `@Component`, it does
   work as documented, and it is exercised by `MediaMetadataExtractorTests` and
   `MediaMetadataExtractorRealFileTest` — but no service, controller or configuration class injects
   it. Image dimensions come from `javax.imageio.ImageIO` inline in `ImageCollectionService`
   instead, and no duration or bitrate is derived anywhere on a live request path.
10. **Content deletes never touch S3.** `S3Service` does expose `deleteFile`, `deleteByKey` and
    `deleteFiles`, but the only production callers are `MediaService.delete` and the singleton
    promo-video methods in `VideoService` and `SoundTrackService`. Every other delete orphans its
    objects.
11. **The Redis cache is not cross-cutting.** Only five cache names exist — `news`, `projects`,
    `soundTracks`, `imageCollections`, `services`. Videos and Writings are entirely uncached, and
    within a cached domain only the paginated list and search shapes are annotated.
12. **`featured_items` is an unused table.** The entity and `FeaturedItemRepository` exist and
    `ddl-auto: update` creates the table, but nothing injects the repository.
    `SiteContentService.getFeatured` builds the rail from per-entity `featured` columns.
13. **`ErrorCode` has more members than the brief lists.** Alongside the generic codes it carries a
    full four-member family per content domain — `*_NOT_FOUND`, `*_CONFLICT`, `*_VALIDATION`,
    `*_MEDIA_INVALID` for PROJECT, NEWS, VIDEO, IMAGE, SOUND and WRITING — plus `ACCOUNT_LOCKED`.
    The brief named only a scattered handful of these.
14. **`ApiErrorResponse` is bilingual, not trilingual.** It carries `messageEn` and `messageKu`
    always, plus one `message` resolved from `Accept-Language` — which is one of those two, not a
    third language. Some Javadoc in `GlobalExceptionHandler` calls this "trilingual"; the field set
    in `ApiErrorResponse` and `ApiFieldError` says otherwise, and this file uses "bilingual"
    throughout.

---

## Related

- [`./UML_STATE.md`](./UML_STATE.md) — the state machines these call flows drive: submission status,
  publication lifecycle, session and token lifetime, account lock.
- [`./UML_COMPONENT.md`](./UML_COMPONENT.md) — the same filter chain and the same services drawn as
  components, plus deployment and configuration.
- [`./UML_CLASS.md`](./UML_CLASS.md) — the Java types behind every participant above: the service
  interfaces, the embeddables and the exception hierarchy.
- [`./ER.md`](./ER.md) — the conceptual and logical data model behind every `INSERT` and `SELECT`
  drawn here.
- [`../external/`](../external/) — the endpoint reference: paths, request and response bodies,
  status codes, per-endpoint roles.
- [`../internal/`](../internal/) — service-layer and operational documentation.
- [`../database/`](../database/) — the physical schema: every table, column, index and constraint.
