#!/usr/bin/env bash
#
# Seed the five "دەتوانم چی ببەخشم؟ / What can I donate?" cards on the donate
# page — the exact texts the website shows today (docs/DONATION_TYPE_CARDS.md §6).
#
# Logs in, then UPSERTS each card: cards have no unique column, so the script
# matches on titleCkb — an existing card with the same Sorani title is updated
# in place (PUT /api/v1/donations/type-cards/{id}), otherwise the card is
# created (POST). Safe to re-run.
#
# Card pictures:
#   imageUrl is required. By default each card gets a stable HTTPS placeholder
#   photo. To use real pictures (the current ones live in the website repo
#   under public/menu/), point these env vars at local image files — they are
#   uploaded through POST /api/v1/media/upload first:
#
#     PHOTO_VISUAL_ARCHIVE=~/Pictures/donate-visual-archive.jpg \
#     PHOTO_DOCUMENTS=~/Pictures/donate-documents.jpg \
#     PHOTO_ORAL_HERITAGE=~/Pictures/donate-oral.jpg \
#     PHOTO_FINANCIAL=~/Pictures/donate-financial.jpg \
#     PHOTO_SCIENTIFIC=~/Pictures/donate-scientific.jpg \
#     ./scripts/seed-donation-type-cards.sh
#
# Usage:
#   ./scripts/seed-donation-type-cards.sh                       # against the Railway backend
#   ./scripts/seed-donation-type-cards.sh http://localhost:8080 # against a local backend
#
# Remember: displayOrder 0 (ئەرشیفی بینراو) is the big featured card.

set -euo pipefail

API="${1:-https://blissful-spontaneity-production.up.railway.app}"
USERNAME="brwa"
PASSWORD="123123"

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

resolve_image() {
  local label="$1" photo_path="$2" fallback="$3"
  if [ -n "${photo_path}" ]; then
    if [ ! -f "${photo_path}" ]; then
      echo "!! ${label}: photo file not found: ${photo_path}" >&2
      exit 1
    fi
    echo "==> ${label}: uploading picture $(basename "${photo_path}")" >&2
    upload_photo "${photo_path}"
  else
    echo "${fallback}"
  fi
}

# All existing cards, hidden ones included — the upsert lookup table.
EXISTING=$(curl -sS "${API}/api/v1/donations/type-cards?includeInactive=true")

# Print the id of the card whose titleCkb matches, or nothing.
id_for_title() {
  local title="$1"
  printf '%s' "${EXISTING}" | python3 -c '
import sys, json
title = sys.argv[1]
for card in json.load(sys.stdin).get("data") or []:
    if card.get("titleCkb") == title:
        print(card["id"]); break
' "${title}" 2>/dev/null || true
}

# upsert_card <label> <titleCkb> <titleKmr> <descCkb> <descKmr> <imageUrl> <displayOrder>
upsert_card() {
  local label="$1" title_ckb="$2" title_kmr="$3" desc_ckb="$4" desc_kmr="$5" image_url="$6" order="$7"
  local body id
  # Build the JSON in python so Kurdish text needs no shell escaping.
  body=$(python3 -c '
import json, sys
_, title_ckb, title_kmr, desc_ckb, desc_kmr, image_url, order = sys.argv
print(json.dumps({
    "titleCkb": title_ckb,
    "titleKmr": title_kmr,
    "descriptionCkb": desc_ckb,
    "descriptionKmr": desc_kmr,
    "imageUrl": image_url,
    "displayOrder": int(order),
    "active": True,
}, ensure_ascii=False))
' "${title_ckb}" "${title_kmr}" "${desc_ckb}" "${desc_kmr}" "${image_url}" "${order}")

  id=$(id_for_title "${title_ckb}")
  echo
  if [ -n "${id}" ]; then
    echo "==> Updating card ${order}: ${label} (id ${id})"
    curl -sS -X PUT "${API}/api/v1/donations/type-cards/${id}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${body}" | python3 -m json.tool || true
  else
    echo "==> Creating card ${order}: ${label}"
    curl -sS -X POST "${API}/api/v1/donations/type-cards" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${body}" | python3 -m json.tool || true
  fi
}

IMG_VISUAL=$(resolve_image  "Visual archive" "${PHOTO_VISUAL_ARCHIVE:-}" "https://picsum.photos/seed/khi-donate-visual/1600/1000")
IMG_DOCS=$(resolve_image    "Documents"      "${PHOTO_DOCUMENTS:-}"      "https://picsum.photos/seed/khi-donate-docs/1600/1000")
IMG_ORAL=$(resolve_image    "Oral heritage"  "${PHOTO_ORAL_HERITAGE:-}"  "https://picsum.photos/seed/khi-donate-oral/1600/1000")
IMG_FIN=$(resolve_image     "Financial"      "${PHOTO_FINANCIAL:-}"      "https://picsum.photos/seed/khi-donate-financial/1600/1000")
IMG_SCI=$(resolve_image     "Scientific"     "${PHOTO_SCIENTIFIC:-}"     "https://picsum.photos/seed/khi-donate-scientific/1600/1000")

# The five cards the site shows today — order 0 is the big featured card.
upsert_card "Visual archive (featured)" \
  "ئەرشیفی بینراو" "Arşîva dîtbarî" \
  "وێنەی کۆنی کەسایەتییەکان، جلوبەرگ و شوێنەوارەکان." \
  "Wêneyên kevn yên kesayetan, cil û cihên dîrokî." \
  "${IMG_VISUAL}" 0

upsert_card "Documents" \
  "بەڵگەنامەکان" "Belge" \
  "کتێبی دەگمەن، نامە، نەخشە، قەباڵە و دەستنووس." \
  "Pirtûkên nadiran, name, nexşe, pul û destnivîs." \
  "${IMG_DOCS}" 1

upsert_card "Oral heritage" \
  "کەلەپووری زارەکی" "Mîrateya zimanî" \
  "کاسێت، قەوان، یان هەر فایلێکی دەنگی و ڤیدیۆیی کۆن." \
  "Kaset, qefî, an her pelê dengî û vîdyoyê yê kevn." \
  "${IMG_ORAL}" 2

upsert_card "Financial support" \
  "پشتیوانیی دارایی" "Piştgiriya darayî" \
  "بۆ دابینکردنی تێچووی پڕۆژە گرنگەکان و بەردەوامیی ئنستیتیوت." \
  "Ji bo peydakirina mesrefa projeyên girîng û domdariya enstîtuyê." \
  "${IMG_FIN}" 3

upsert_card "Scientific cooperation" \
  "هاوکاریی زانستی" "Hevkariya zanistî" \
  "یارمەتیدان لە ناسینەوەی وێنە و کەرەستە کۆنەکان." \
  "Alîkariya nasîna wêne û tiştên kevn." \
  "${IMG_SCI}" 4

echo
echo "==> Done. Verify (what the website will draw, sorted by displayOrder):"
echo "    curl -s ${API}/api/v1/donations/type-cards"
