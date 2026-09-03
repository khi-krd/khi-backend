# Internal API — Authenticated

Everything in this folder requires a **valid JWT**. These are the endpoints behind
`.authenticated()`, `.hasRole(...)`, `.hasAnyRole(...)` or a method-level `@PreAuthorize` — the
admin dashboard and staff tooling surface.

**91 endpoints · 15 documents · verified against source 2026-08-26**

If an endpoint needs no token, it is not here — look in [`../external/`](../external/).

---

## Getting a token

There is no login endpoint in this folder, because logging in is a public operation. Get a token
from [`../external/AUTH_API.md`](../external/AUTH_API.md), then send it either way:

```bash
# Header
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/user/me

# Or rely on the HttpOnly cookie set at login (name comes from JWT_COOKIE_NAME)
curl -b cookies.txt http://localhost:8080/api/user/me
```

Then read [`AUTH_SESSIONS_API.md`](AUTH_SESSIONS_API.md) for logout, the token blacklist, and
per-device session revocation.

---

## What is in this folder

| Document | Endpoints | Minimum role | What it covers |
|----------|-----------|--------------|----------------|
| [`AUTH_SESSIONS_API.md`](AUTH_SESSIONS_API.md) | 5 | any authenticated | Logout, logout-all, list and revoke sessions, token blacklist |
| [`USER_PROFILE_API.md`](USER_PROFILE_API.md) | 6 | any authenticated | Self-service profile: name, username, password, avatar, account deletion |
| [`MEDIA_API.md`](MEDIA_API.md) | 3 | `ADMIN` | The shared S3 upload pipeline every Tiptap editor uses |
| [`ABOUT_API.md`](ABOUT_API.md) | 4 | `ADMIN` | Create, update, delete and feature About pages |
| [`CONTACT_API.md`](CONTACT_API.md) | 6 | `ADMIN` | Contact page management and the visitor message inbox |
| [`SERVICE_API.md`](SERVICE_API.md) | 8 | `ADMIN` | Service catalogue writes, admin listings, bulk delete |
| [`PROJECT_API.md`](PROJECT_API.md) | 4 | `EMPLOYEE` (delete: `ADMIN`) | Project create, update, delete, feature |
| [`NEWS_API.md`](NEWS_API.md) | 7 | `EMPLOYEE` (delete: `ADMIN`) | News writes, bulk create, bulk delete, feature |
| [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md) | 5 | `EMPLOYEE` (delete: `ADMIN`) | Collection writes and album item management |
| [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md) | 7 | `EMPLOYEE` (delete: `ADMIN`) | Track writes, files, brochures, attachments, advert banner |
| [`VIDEO_API.md`](VIDEO_API.md) | 9 | `EMPLOYEE` (delete: `ADMIN`) | Video writes, topics, film advert banner |
| [`WRITING_API.md`](WRITING_API.md) | 8 | `EMPLOYEE` (delete: `ADMIN`) | Book writes, series linking and genre CRUD |
| [`TOPIC_API.md`](TOPIC_API.md) | 3 | `EMPLOYEE` | Create, update and delete taxonomy topics |
| [`SITE_SETTINGS_API.md`](SITE_SETTINGS_API.md) | 13 | `ADMIN` | Featured rail, navigation, team, partners, social links, site settings |
| [`DONATION_API.md`](DONATION_API.md) | 9 | `ADMIN` | Donation settings, "What can I donate?" cards, and reading and triaging donor submissions |

---

## The role model

| Role | Can do |
|------|--------|
| `GUEST` | Default for self-registration. Own profile and sessions only |
| `EMPLOYEE` | Above, plus create and update content: projects, news, videos, image collections, sound tracks, writings |
| `ADMIN` | Above, plus delete content, manage site configuration, read donor and visitor submissions, toggle featured |
| `SUPER_ADMIN` | Everything `ADMIN` can do. Reserved for user administration |

The pattern across content domains is consistent and worth memorising:
**`EMPLOYEE` writes, `ADMIN` deletes, `ADMIN` features.**

> **Note:** `SecurityConfig` reserves `/api/users/**` for `SUPER_ADMIN`, but no controller currently
> maps that prefix — there is no user-administration API. The rule is dormant. `SUPER_ADMIN`
> therefore has no capability that `ADMIN` lacks today.

---

## Things that apply to every endpoint here

**A `403` is not a bug.** It means your token is valid but your role is too low. Check the minimum
role in the table above; the per-endpoint documents give the exact rule and where it comes from.

**Two layers decide authorization.** `SecurityConfig` sets a baseline per path and method; a
method-level `@PreAuthorize` can narrow it further. Where the two differ, the documents say so
explicitly. The featured toggles are the common case — most are `@PreAuthorize("hasRole('ADMIN')")`
even where the surrounding path rule allows `EMPLOYEE`.

**Uploads go through S3.** Content writes are multipart. Read [`MEDIA_API.md`](MEDIA_API.md) first
if you are building an editor — it explains the upload-then-save workflow and how Tiptap HTML gets
its image URLs rewritten.

**Deletes cascade further than you expect.** Removing a parent row usually removes its bilingual
content rows, its child items and its S3 objects. Each document has a "Notes and gotchas" section
covering what a delete actually destroys.

**Donor and visitor data is in here for a reason.** Contact messages and donation submissions are
readable only by `ADMIN` and above. Treat those endpoints as handling personal data — see
[`../database/FIELDS.md`](../database/FIELDS.md#personal-data-fields).

---

## Related

- [`../external/README.md`](../external/README.md) — the public counterpart of these APIs
- [`../diagrams/FLOWCHARTS.md`](../diagrams/FLOWCHARTS.md) — trace an unexpected 403 through the authorization ladder
- [`../database/SCHEMA.md`](../database/SCHEMA.md) — what your writes actually persist
- [`../database/FIELDS.md`](../database/FIELDS.md) — flags, ordering, audit and slug behaviour
- [`../README.md`](../README.md) — platform-wide facts and conventions
