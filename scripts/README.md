# scripts

## `seed-about-services.sh`

Loads bilingual (CKB + KMR) demo content for the **Kurdish Heritage Institute** into the
About and Service modules through the REST API — so slug validation, language checks and the
Tiptap HTML processor all run exactly as in production.

| File | Contents |
| --- | --- |
| `seed-data/about.json` | **3 About pages** — main "About the institute" (with stats + founder), "Mission & objectives", "History of the founding" |
| `seed-data/services.json` | **8 services** — music & maqam archive, digital archive, library, recording studio, publishing, field research, training, concert hall & events |

Every entry has full CKB (Sorani, Arabic script) and KMR (Kurmanji, Latin script) text: title,
subtitle/meta, a Tiptap HTML body, and — for services — the new plain-text
`featureDescription` used by the homepage carousel.

### Run it

```bash
# local — logs in on its own
./scripts/seed-about-services.sh

# staging / production
BASE=https://blissful-spontaneity-production.up.railway.app ./scripts/seed-about-services.sh

# just one module
./scripts/seed-about-services.sh about
./scripts/seed-about-services.sh services
```

Requires `curl` and `jq`. No token needed: the script calls `POST /api/auth/login` as
`SEED_USER` / `SEED_PASS` (defaults `brwa` / `123123`) and uses the returned JWT for
everything after that.

```bash
SEED_USER=someone SEED_PASS=… ./scripts/seed-about-services.sh   # different account
TOKEN=<admin jwt>             ./scripts/seed-about-services.sh   # skip the login
```

That account needs the **ADMIN** role — `SUPER_ADMIN` gets `403` on the featured `PATCH`
(see `new documentation/FEATURED_ABOUT_SERVICE_DONATION.md` §6.4).

> The dev password lives in the script so a local seed is one command. Don't reuse that
> password on a deployment that matters — override with `SEED_USER` / `SEED_PASS` instead.

### Re-running is safe

It upserts: About is matched by `slugCkb` (via `GET /api/v1/about/slug/{slug}`), services by
`navAnchorId` (via `GET /api/v1/services/admin/all`). Existing records are `PUT`, new ones
`POST`. Nothing is duplicated and nothing is deleted.

### Featuring a slide (optional)

The seed content deliberately contains **no image URLs** — nothing points at an S3 object that
may not exist. To also put a slide on the homepage carousel, upload a wide picture first and
pass its URL:

```bash
curl -X POST "$BASE/api/v1/media/upload" -H "Authorization: Bearer $TOKEN" \
  -F "file=@about-hero-2560.jpg" -F "type=image"     # -> data.fileUrl

TOKEN=… \
ABOUT_HERO_URL=https://…/about-hero-2560.jpg \
SERVICE_HERO_URL=https://…/studio-hero-2560.jpg \
./scripts/seed-about-services.sh
```

Both URLs are required for that step: About has no cover to fall back on, and these seeded
services carry no gallery image. Recommended picture: 2560 × 1440 JPEG, under ~500 KB, subject
upper-middle toward the left.

### Adding pictures to the seeded content

Images live **inside** the Tiptap body (`ckbContent.body` / `kmrContent.body` for About,
`contents[].description` for services) as normal `<img>` tags pointing at S3 — upload via
`POST /api/v1/media/upload`, paste the URL into the editor, save. Services can additionally
carry `galleryMedia[]` slots and `heroVideoUrl` / `heroPosterUrl`.

### Keeping the files valid

`SeedDataFilesTests` deserializes both files into `AboutRequest` / `ServiceRequest` with a
strict Jackson mapper and re-checks the service-layer rules (unique slugs, one row per
language, allowed `layoutType`, `publishedAt` format, plain-text `featureDescription` within
the column length). If a DTO field is renamed, that test fails instead of the seed run.
