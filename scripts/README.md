# scripts

## `seed-about-services.sh`

Loads bilingual (CKB + KMR) demo content for the **Kurdish Heritage Institute** into the
About and Service modules through the REST API — so slug validation, language checks and the
Tiptap HTML processor all run exactly as in production.

| File | Contents |
| --- | --- |
| `seed-data/about.json` | **3 About pages**, short set — "About the institute" (stats + founder), "Mission & objectives", "History of the founding" |
| `seed-data/about-detailed.json` | **7 About pages**, long-form set — institute profile, founder Mazhar Khaleqi, archive & collections, departments & structure, the digitization project, cooperation & academic recognition, visiting & using the institute |
| `seed-data/services.json` | **8 services** — music & maqam archive, digital archive, library, recording studio, publishing, field research, training, concert hall & events |

Both About files are seeded by default and their slugs never overlap, so they coexist as 10
pages ordered by `displayOrder` (1–3 short, 10–16 detailed). Seed only one with
`ABOUT_FILES=about-detailed.json ./scripts/seed-about-services.sh about`.

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

### Media in the seed content

The content ships with **real media from the project's own S3 bucket** — every URL was taken
from the live deployment and HEAD-checked (200), nothing is hotlinked from a third party:

| Where | What |
| --- | --- |
| About bodies (CKB + KMR) | inline `<img>` per page, plus inline `<video>` on the profile, archive and visit pages |
| About `heroVideoUrl` / `heroPosterUrl` | the institute documentary + its poster frame, on both profile pages |
| Service `galleryMedia[]` | 2–6 ordered slots per service; the field-research service has six (clothing, pastoral life, tobacco, snow pit, jamadani, gopal) |
| Service `heroVideoUrl` / `heroPosterUrl` | recording studio → documentary, concert hall → *govend* dance clip |
| Service `thumbnailUrls[]` | one card thumbnail each |
| Service descriptions (CKB + KMR) | inline `<img>` / `<video>` |

Every `VIDEO` gallery slot carries a `posterUrl` — `serviceSlideImage()` uses a video slot's
poster as the featured picture, so a slot without one contributes no image at all.

`founderImageUrl` is deliberately left `null`: no verified portrait of Mazhar Khaleqi was
available, and pointing that field at some other photograph would caption a real person
wrongly. Upload one and set it when you have it.

### Featuring a slide (optional)

To put a slide on the homepage carousel with a picture from the seed media:

```bash
SEED_FEATURED=1 ./scripts/seed-about-services.sh
```

Those fallbacks are covers, not wide 16:9 crops. For anything visitor-facing, upload a proper
hero image and name it instead:

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
