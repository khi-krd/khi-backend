#!/usr/bin/env bash
#
# Seed test contact pages for the two KHI offices — Sulaymaniyah and Duhok —
# including the office photo (heroImageUrl, see docs/internal/CONTACT_API.md
# and the "Contact office photo" guide).
#
# Logs in, then UPSERTS one contact page per office: if a page with the same
# slugCkb already exists it is updated (PUT /api/v1/contact/{id}), otherwise
# created (POST /api/v1/contact). Ids are read from the API each run and
# matched on slugCkb — never hardcoded, they change when seed data is
# recreated. Each office gets bilingual content, phone/email, the city's
# Google Maps embed + coordinates, and an office photo.
#
# Office photo:
#   By default each office gets a 1600×1000 (16:10) HTTPS placeholder photo.
#   To use real photos, point these env vars at local image files — they are
#   uploaded through POST /api/v1/media/upload first and the returned S3 URL
#   is saved in heroImageUrl:
#
#     SLEMANI_PHOTO=~/Pictures/office-slemani.jpg \
#     DUHOK_PHOTO=~/Pictures/office-duhok.jpg \
#     ./scripts/seed-contact-test-data.sh
#
# Usage:
#   ./scripts/seed-contact-test-data.sh                       # against the Railway backend
#   ./scripts/seed-contact-test-data.sh http://localhost:8080 # against a local backend
#
# Safe to re-run: existing offices are updated in place (PUT replaces the
# whole record, and this script always sends every field).

set -euo pipefail

API="${1:-https://blissful-spontaneity-production.up.railway.app}"
USERNAME="brwa"
PASSWORD="123123"

# 16:10 placeholders per the office-photo guide (any HTTPS host works).
SLEMANI_HERO_DEFAULT="https://picsum.photos/seed/khi-slemani/1600/1000"
DUHOK_HERO_DEFAULT="https://picsum.photos/seed/khi-duhok/1600/1000"

echo "==> Logging in as ${USERNAME} @ ${API}"
LOGIN_RESPONSE=$(curl -sS -X POST "${API}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")

# Login answers { "token": "...", "response": "..." } — no ApiResponse envelope.
TOKEN=$(printf '%s' "${LOGIN_RESPONSE}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])' 2>/dev/null || true)

if [ -z "${TOKEN}" ]; then
  echo "!! Login failed. Response was:" >&2
  echo "${LOGIN_RESPONSE}" >&2
  exit 1
fi
echo "==> Login OK"

# Upload a local image via POST /api/v1/media/upload and print data.fileUrl.
upload_photo() {
  local file="$1"
  curl -sS -X POST "${API}/api/v1/media/upload" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "file=@${file}" \
    -F "type=image" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["fileUrl"])'
}

resolve_hero() {
  local label="$1" photo_path="$2" fallback="$3"
  if [ -n "${photo_path}" ]; then
    if [ ! -f "${photo_path}" ]; then
      echo "!! ${label}: photo file not found: ${photo_path}" >&2
      exit 1
    fi
    echo "==> ${label}: uploading photo $(basename "${photo_path}")" >&2
    upload_photo "${photo_path}"
  else
    echo "${fallback}"
  fi
}

SLEMANI_HERO=$(resolve_hero "Sulaymaniyah" "${SLEMANI_PHOTO:-}" "${SLEMANI_HERO_DEFAULT}")
DUHOK_HERO=$(resolve_hero "Duhok" "${DUHOK_PHOTO:-}" "${DUHOK_HERO_DEFAULT}")

# All existing pages (admin list, includes ids) — the upsert lookup table.
EXISTING=$(curl -sS "${API}/api/v1/contact" -H "Authorization: Bearer ${TOKEN}")

# Print the id of the page whose slugCkb matches, or nothing.
id_for_slug() {
  local slug="$1"
  printf '%s' "${EXISTING}" | python3 -c '
import sys, json
slug = sys.argv[1]
for page in json.load(sys.stdin).get("data") or []:
    if page.get("slugCkb") == slug:
        print(page["id"]); break
' "${slug}" 2>/dev/null || true
}

# Create or update one office. PUT replaces the whole record — the bodies
# below always carry every field, so updating in place is safe.
upsert_contact() {
  local label="$1" slug="$2" body="$3"
  local id
  id=$(id_for_slug "${slug}")
  echo
  if [ -n "${id}" ]; then
    echo "==> Updating contact page: ${label} (id ${id}, slug ${slug})"
    curl -sS -X PUT "${API}/api/v1/contact/${id}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${body}" | python3 -m json.tool || true
  else
    echo "==> Creating contact page: ${label} (slug ${slug})"
    curl -sS -X POST "${API}/api/v1/contact" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${body}" | python3 -m json.tool || true
  fi
}

# ─── Sulaymaniyah — headquarters ─────────────────────────────────────────────
# Map: 35.5647 N, 45.4164 E (city centre)
SLEMANI_BODY=$(cat <<EOF
{
  "slugCkb": "peywendi-slemani",
  "slugKmr": "tekili-silemani",
  "ckbContent": {
    "title": "نووسینگەی سەرەکی — سلێمانی",
    "subtitle": "پەیمانگای کەلەپووری کوردی — بارەگای سەرەکی",
    "address": "سلێمانی، گەڕەکی سالم، نزیک پارکی ئازادی، عێراق",
    "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ بەیانی – ٤:٠٠ ئێوارە",
    "description": "<p>بارەگای سەرەکی پەیمانگای کەلەپووری کوردی لە سلێمانی. بۆ بەخشینی ئەرشیف، هاوکاریی زانستی و سەردانی ئەرشیفەکە پەیوەندیمان پێوە بکەن.</p>"
  },
  "kmrContent": {
    "title": "Nivîsgeha Serekî — Silêmanî",
    "subtitle": "Enstîtuya Mîrateya Kurdî — Navenda serekî",
    "address": "Silêmanî, Taxa Salim, nêzîkî Parka Azadî, Iraq",
    "workingHours": "Şemî – Pêncşem, 9:00 – 16:00",
    "description": "<p>Navenda serekî ya Enstîtuya Mîrateya Kurdî li Silêmaniyê. Ji bo bexşîna arşîvê, hevkariya zanistî û serdana arşîvê bi me re têkilî daynin.</p>"
  },
  "phone": "+964 770 111 2233",
  "secondaryPhone": "+964 750 111 2233",
  "email": "sulaymaniyah@khi.example.org",
  "mapEmbedUrl": "https://www.google.com/maps?q=35.5647,45.4164&z=14&output=embed",
  "latitude": 35.5647,
  "longitude": 45.4164,
  "heroImageUrl": "${SLEMANI_HERO}",
  "officeType": "HQ",
  "badgeCkb": "بارەگای سەرەکی",
  "badgeKmr": "Navenda serekî",
  "active": true,
  "displayOrder": 0
}
EOF
)

# ─── Duhok — regional office ─────────────────────────────────────────────────
# Map: 36.8663 N, 42.9884 E (city centre)
DUHOK_BODY=$(cat <<EOF
{
  "slugCkb": "peywendi-dhok",
  "slugKmr": "tekili-duhok",
  "ckbContent": {
    "title": "نووسینگەی دهۆک",
    "subtitle": "پەیمانگای کەلەپووری کوردی — نووسینگەی هەرێمی",
    "address": "دهۆک، شەقامی نوهەدرا، بەرامبەر زانکۆی دهۆک، عێراق",
    "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ بەیانی – ٣:٠٠ ئێوارە",
    "description": "<p>نووسینگەی هەرێمی پەیمانگای کەلەپووری کوردی لە دهۆک. وەرگرتنی بەڵگەنامە و کەرەستە کۆنەکانی ناوچەکە و هاوکاریی توێژەران.</p>"
  },
  "kmrContent": {
    "title": "Nivîsgeha Duhokê",
    "subtitle": "Enstîtuya Mîrateya Kurdî — Nivîsgeha herêmî",
    "address": "Duhok, Kolana Nuhedra, hember Zanîngeha Duhokê, Iraq",
    "workingHours": "Şemî – Pêncşem, 9:00 – 15:00",
    "description": "<p>Nivîsgeha herêmî ya Enstîtuya Mîrateya Kurdî li Duhokê. Wergirtina belge û tiştên kevn yên herêmê û alîkariya lêkolîneran.</p>"
  },
  "phone": "+964 770 444 5566",
  "secondaryPhone": "+964 750 444 5566",
  "email": "duhok@khi.example.org",
  "mapEmbedUrl": "https://www.google.com/maps?q=36.8663,42.9884&z=14&output=embed",
  "latitude": 36.8663,
  "longitude": 42.9884,
  "heroImageUrl": "${DUHOK_HERO}",
  "officeType": "REGIONAL",
  "badgeCkb": "نووسینگەی هەرێمی",
  "badgeKmr": "Nivîsgeha herêmî",
  "active": true,
  "displayOrder": 1
}
EOF
)

upsert_contact "Sulaymaniyah (HQ)"  "peywendi-slemani" "${SLEMANI_BODY}"
upsert_contact "Duhok (regional)"   "peywendi-dhok"    "${DUHOK_BODY}"

echo
echo "==> Done. Verify:"
echo "    curl -s ${API}/api/v1/contact/active | grep heroImageUrl"
