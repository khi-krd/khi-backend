# External API — Public

Everything in this folder is callable by an **anonymous visitor**. No token, no cookie, no headers
beyond `Content-Type`. These are the endpoints `SecurityConfig` marks `permitAll()`, and they are
what the public KHI website runs on.

**80 endpoints · 14 documents · verified against source 2026-08-26**

If an endpoint needs a token, it is not here — look in [`../internal/`](../internal/).

---

## What is in this folder

| Document | Endpoints | Base paths | What it covers |
|----------|-----------|------------|----------------|
| [`AUTH_API.md`](AUTH_API.md) | 5 | `/api/auth` | Register, login, password reset. How the JWT and its cookie work, and the role model |
| [`ABOUT_API.md`](ABOUT_API.md) | 3 | `/api/v1/about` | About pages — bilingual bodies and stat items |
| [`CONTACT_API.md`](CONTACT_API.md) | 4 | `/api/v1/contact` | Contact pages, and the public "send us a message" form |
| [`SERVICE_API.md`](SERVICE_API.md) | 6 | `/api/v1/services` | Service catalogue, type filtering, search |
| [`PROJECT_API.md`](PROJECT_API.md) | 5 | `/api/v1/projects` | Projects with content blocks, tag and keyword search |
| [`NEWS_API.md`](NEWS_API.md) | 8 | `/api/v1/news` | News articles, category taxonomy, five search axes |
| [`IMAGE_COLLECTION_API.md`](IMAGE_COLLECTION_API.md) | 6 | `/api/v1/image-collections` | Photo collections and album items |
| [`SOUNDTRACK_API.md`](SOUNDTRACK_API.md) | 12 | `/api/v1/sound-tracks` | Audio tracks, state and type filters, album of memories |
| [`VIDEO_API.md`](VIDEO_API.md) | 7 | `/api/v1/videos` | Videos, cast, clips, and the film advert banner |
| [`WRITING_API.md`](WRITING_API.md) | 9 | `/api/v1/writings` | Books, genres, series linking, writer search |
| [`TOPIC_API.md`](TOPIC_API.md) | 2 | `/api/v1/topics` | The shared VIDEO / SOUND / IMAGE / WRITING taxonomy |
| [`SEARCH_API.md`](SEARCH_API.md) | 1 | `/api/v1/search` | One endpoint that searches every content type at once |
| [`SITE_SETTINGS_API.md`](SITE_SETTINGS_API.md) | 8 | `/api/v1/featured`, `/nav-menu`, `/settings/social`, `/site-settings`, `/sitemap` | Homepage featured rail, navigation, team, partners, sitemap |
| [`DONATION_API.md`](DONATION_API.md) | 5 | `/api/v1/donations` | Donation settings, "What can I donate?" cards, and the public donation submission forms |

---

## Building a page? Start with these

| Page | Read |
|------|------|
| Homepage | [`SITE_SETTINGS_API.md`](SITE_SETTINGS_API.md) — the featured rail aggregates every content type |
| Navigation bar | [`SITE_SETTINGS_API.md`](SITE_SETTINGS_API.md) — `GET /api/v1/nav-menu` |
| Any content listing | The matching domain document — they all paginate the same way |
| Search results | [`SEARCH_API.md`](SEARCH_API.md) |
| Contact form | [`CONTACT_API.md`](CONTACT_API.md) — `POST /api/v1/contact/messages` |
| Donation form | [`DONATION_API.md`](DONATION_API.md) |
| Login / signup | [`AUTH_API.md`](AUTH_API.md) |

---

## Things that apply to every endpoint here

**No auth header.** Sending one is harmless but pointless. If you get a `401` or `403` from a path
documented in this folder, you have hit a different route than you think — check the method, and
check for a trailing segment. `GET /api/v1/contact/active` is public; `GET /api/v1/contact` is
admin-only. Cases like that are called out in the individual documents.

**Bilingual content.** Content carries `CKB` (Sorani) and `KMR` (Kurmanji) variants. Which one you
get depends on the endpoint — some return both and let you choose, some take a language parameter.
Each document says which.

**Pagination.** Listing endpoints are paginated. Parameter names and defaults are not uniform
across domains, so check the document rather than assuming.

**Errors.** Standard envelope with `code`, `message`, `messageEn`, `messageKu` and a `traceId`.
See [`../README.md`](../README.md#the-error-envelope). `/api/auth/**` is the exception: a rejected
token is answered by `JWTAuthenticationFilter` before the exception advice ever sees the request,
so you get a bare `{"error":"TOKEN_EXPIRED"}`-style body instead. Handle both shapes on your login
and session paths.

**Write endpoints exist but are not here.** Public visitors can submit three things and nothing
else: a contact message, a financial donation, and an archive donation. Everything else that
writes requires a token.

---

## Related

- [`../internal/README.md`](../internal/README.md) — the authenticated counterpart of these APIs
- [`../diagrams/README.md`](../diagrams/README.md) — the same APIs as pictures
- [`../database/SCHEMA.md`](../database/SCHEMA.md) — what the returned objects look like in storage
- [`../README.md`](../README.md) — platform-wide facts and conventions
