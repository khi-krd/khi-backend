#!/usr/bin/env bash
#
# seed-about-services.sh — load bilingual (CKB + KMR) demo content for the
# Kurdish Heritage Institute into the About and Service modules.
#
#   3 About pages   (scripts/seed-data/about.json)
#   8 Services      (scripts/seed-data/services.json)
#
# Seeds through the REST API rather than SQL, so validation, slug uniqueness and
# the Tiptap HTML processor all run exactly as they do in production.
#
# The script is an UPSERT and safe to re-run:
#   About    matched by slugCkb        -> PUT when it exists, POST when it does not
#   Service  matched by navAnchorId    -> PUT when it exists, POST when it does not
#
# ── Usage ────────────────────────────────────────────────────────────────────
#   ./scripts/seed-about-services.sh                 # logs in automatically
#   BASE=https://blissful-spontaneity-production.up.railway.app ./scripts/seed-about-services.sh
#
#   # only one module
#   ./scripts/seed-about-services.sh about
#   ./scripts/seed-about-services.sh services
#
#   # different account, or a token you already have
#   SEED_USER=someone SEED_PASS=…  ./scripts/seed-about-services.sh
#   TOKEN=<admin jwt>              ./scripts/seed-about-services.sh
#
#   # also feature one About page + one service on the homepage carousel.
#   # both URLs are required for that step: About has no cover to fall back on,
#   # and these seeded services carry no gallery image.
#   ABOUT_HERO_URL=https://…/about-hero-2560.jpg \
#   SERVICE_HERO_URL=https://…/studio-hero-2560.jpg \
#   ./scripts/seed-about-services.sh
#
# ── Notes ────────────────────────────────────────────────────────────────────
#   * With no TOKEN set, the script logs in via POST /api/auth/login as SEED_USER
#     and uses the returned JWT for every following request.
#   * That account must have the ADMIN role: SUPER_ADMIN gets 403 on the featured
#     PATCH (see new documentation/FEATURED_ABOUT_SERVICE_DONATION.md §6.4).
#   * No media URLs are baked into the seed content on purpose — nothing points
#     at an S3 object that may not exist. Upload real pictures via
#     POST /api/v1/media/upload and add them afterwards.
#   * Requires: curl, jq.

set -euo pipefail

BASE="${BASE:-https://blissful-spontaneity-production.up.railway.app}"
WHAT="${1:-all}"

# Dev credentials for the local seed run. Override with SEED_USER / SEED_PASS, or skip
# the login entirely by exporting TOKEN. NOTE: these are committed to the repo — fine
# for a local dev account, but do not reuse this password on a real deployment.
SEED_USER="${SEED_USER:-brwa}"
SEED_PASS="${SEED_PASS:-123123}"
TOKEN="${TOKEN:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="$SCRIPT_DIR/seed-data"

# ── Preflight ────────────────────────────────────────────────────────────────

for bin in curl jq; do
  command -v "$bin" >/dev/null 2>&1 || { echo "✖ $bin is required but not installed."; exit 1; }
done

for f in about.json services.json; do
  [[ -f "$DATA_DIR/$f" ]] || { echo "✖ missing $DATA_DIR/$f"; exit 1; }
  jq empty "$DATA_DIR/$f" || { echo "✖ $f is not valid JSON"; exit 1; }
done

echo "→ base   : $BASE"
echo "→ seeding: $WHAT"

# ── Helpers ──────────────────────────────────────────────────────────────────

# call <METHOD> <PATH> [BODY] -> sets STATUS and RESPONSE
call() {
  local method="$1" path="$2" body="${3:-}" tmp
  tmp="$(mktemp)"
  local args=(-sS -o "$tmp" -w '%{http_code}'
              -X "$method" "$BASE$path"
              -H "Authorization: Bearer $TOKEN"
              -H "Accept-Language: ckb")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$body")
  fi
  STATUS="$(curl "${args[@]}" || true)"
  STATUS="${STATUS:-000}"
  RESPONSE="$(cat "$tmp")"
  rm -f "$tmp"
}

# json <FILTER> — read a value out of the last RESPONSE, empty string on any miss
json() { printf '%s' "$RESPONSE" | jq -r "$1 // empty" 2>/dev/null || true; }

# login — POST /api/auth/login and keep the JWT in TOKEN.
# Deliberately does NOT reuse call(): that helper always sends an Authorization header,
# and the login route must be hit unauthenticated.
login() {
  local tmp body
  tmp="$(mktemp)"
  body="$(jq -nc --arg u "$SEED_USER" --arg p "$SEED_PASS" '{username:$u, password:$p}')"
  STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' \
              -X POST "$BASE/api/auth/login" \
              -H 'Content-Type: application/json' \
              -H 'Accept-Language: ckb' \
              --data-binary "$body" || true)"
  STATUS="${STATUS:-000}"
  RESPONSE="$(cat "$tmp")"
  rm -f "$tmp"

  if ! ok; then
    echo "✖ login failed for '$SEED_USER' — HTTP $STATUS"
    echo "  $(json '.message // .response // .' | head -2)"
    echo "  Is the server up at $BASE?  Override with SEED_USER / SEED_PASS, or export TOKEN."
    exit 1
  fi

  TOKEN="$(json '.token')"
  if [[ -z "$TOKEN" ]]; then
    echo "✖ login returned no token. Body was:"
    printf '   %s\n' "$RESPONSE"
    exit 1
  fi
  echo "→ auth   : logged in as $SEED_USER"
}

ok() { [[ "$STATUS" == 2* ]]; }

# A 400 that means "this record is already there" rather than "your payload is wrong":
# duplicate navAnchorId (services) or an already-taken slug (About).
duplicate() {
  if [[ "$STATUS" != "400" ]]; then return 1; fi
  printf '%s' "$RESPONSE" | grep -qiE 'navAnchorId|slug already exists|duplicate'
}

# Prints the server's real reason. The localized "message" is often just "Bad request",
# so code + details are what actually identify the problem.
fail() {
  local code reason details
  code="$(json '.code')"
  reason="$(json '.details.reason // .message')"
  details="$(printf '%s' "$RESPONSE" | jq -c '.details // empty' 2>/dev/null || true)"
  echo "   ✖ HTTP $STATUS ${code:+[$code]} ${reason}"
  if [[ -n "$details" && "$details" != "null" ]]; then
    echo "     details: $details"
  fi
  if [[ "$STATUS" == "500" ]]; then
    echo "     hint: the featured columns are probably missing on this database —"
    echo "           run scripts/sql/2026-08-17-featured-about-service-donation.sql"
  fi
  return 0
}

# ── About ────────────────────────────────────────────────────────────────────

seed_about() {
  echo "── About pages ──────────────────────────────────────────"
  local count
  count="$(jq 'length' "$DATA_DIR/about.json")"

  for i in $(seq 0 $((count - 1))); do
    local page slug_ckb title existing_id
    page="$(jq -c ".[$i]" "$DATA_DIR/about.json")"
    slug_ckb="$(printf '%s' "$page" | jq -r '.slugCkb')"
    title="$(printf '%s' "$page" | jq -r '.ckbContent.title')"

    # already there? the slug lookup accepts either language's slug
    call GET "/api/v1/about/slug/$slug_ckb"
    existing_id=""
    if ok; then existing_id="$(json '.data.id')"; fi

    if [[ -n "$existing_id" ]]; then
      call PUT "/api/v1/about/$existing_id" "$page"
      if ok; then echo "   ✔ updated  #$existing_id  $slug_ckb — $title"
      else echo "   updating #$existing_id  $slug_ckb"; fail; fi
    else
      call POST "/api/v1/about" "$page"
      # About POST/PUT return the DTO unwrapped — no { data } envelope here
      if ok; then echo "   ✔ created  #$(json '.id')  $slug_ckb — $title"
      elif duplicate; then echo "   • exists   $slug_ckb — left as it is (lookup unavailable)"
      else echo "   creating $slug_ckb"; fail; fi
    fi
  done
  echo
}

# ── Services ─────────────────────────────────────────────────────────────────

seed_services() {
  echo "── Services ─────────────────────────────────────────────"

  # one admin listing -> navAnchorId => id, so re-runs update instead of duplicate
  local index="{}"
  call GET "/api/v1/services/admin/all?page=0&size=200"
  if ok; then
    index="$(printf '%s' "$RESPONSE" \
      | jq -c '[.data.content[]? | select(.navAnchorId != null)
                | {key: .navAnchorId, value: .id}] | from_entries' 2>/dev/null || echo '{}')"
  else
    echo "   ! listing unavailable (HTTP $STATUS) — cannot match what is already there,"
    echo "     so this run can only CREATE. Records that exist are reported as '• exists'."
  fi

  local count
  count="$(jq 'length' "$DATA_DIR/services.json")"

  for i in $(seq 0 $((count - 1))); do
    local svc anchor title existing_id
    svc="$(jq -c ".[$i]" "$DATA_DIR/services.json")"
    anchor="$(printf '%s' "$svc" | jq -r '.navAnchorId')"
    title="$(printf '%s' "$svc" | jq -r '.contents[] | select(.languageCode=="CKB") | .title')"
    existing_id="$(printf '%s' "$index" | jq -r --arg a "$anchor" '.[$a] // empty')"

    if [[ -n "$existing_id" ]]; then
      call PUT "/api/v1/services/$existing_id" "$svc"
      if ok; then echo "   ✔ updated  #$existing_id  $anchor — $title"
      else echo "   updating #$existing_id  $anchor"; fail; fi
    else
      call POST "/api/v1/services" "$svc"
      if ok; then echo "   ✔ created  #$(json '.data.id')  $anchor — $title"
      elif duplicate; then echo "   • exists   $anchor — left as it is (listing unavailable)"
      else echo "   creating $anchor"; fail; fi
    fi
  done
  echo
}

# ── Optional: put one of each on the homepage carousel ───────────────────────

feature_samples() {
  local about_url="${ABOUT_HERO_URL:-}" service_url="${SERVICE_HERO_URL:-}"

  if [[ -z "$about_url" && -z "$service_url" ]]; then
    echo "── Featured (skipped) ───────────────────────────────────"
    echo "   Set ABOUT_HERO_URL and/or SERVICE_HERO_URL to also feature a slide."
    echo "   About REQUIRES an image (it has no cover); these services have no"
    echo "   gallery image, so they need one too."
    echo
    return
  fi

  echo "── Featured ─────────────────────────────────────────────"

  if [[ -n "$about_url" ]]; then
    local id=""
    call GET "/api/v1/about/slug/derbarey-ime"
    if ok; then id="$(json '.data.id')"; fi
    if [[ -n "$id" ]]; then
      call PATCH "/api/v1/about/$id/featured" \
        "$(jq -nc --arg u "$about_url" '{featured:true, featuredOrder:1, featureImageUrl:$u}')"
      if ok; then echo "   ✔ featured About #$id at order 1"; else echo "   featuring About #$id"; fail; fi
    else
      echo "   ✖ About page 'derbarey-ime' not found — seed it first."
    fi
  fi

  if [[ -n "$service_url" ]]; then
    local id=""
    call GET "/api/v1/services/admin/all?page=0&size=200"
    if ok; then
      id="$(json '[.data.content[]? | select(.navAnchorId=="recording-studio") | .id][0]')"
    fi
    if [[ -n "$id" ]]; then
      call PATCH "/api/v1/services/$id/featured" \
        "$(jq -nc --arg u "$service_url" '{featured:true, featuredOrder:2, featureImageUrl:$u}')"
      if ok; then echo "   ✔ featured Service #$id at order 2"; else echo "   featuring Service #$id"; fail; fi
    else
      echo "   ✖ Service 'recording-studio' not found — seed it first."
    fi
  fi
  echo
}

# ── Run ──────────────────────────────────────────────────────────────────────

if [[ -n "$TOKEN" ]]; then
  echo "→ auth   : using TOKEN from the environment"
else
  login
fi
echo

case "$WHAT" in
  about)    seed_about ;;
  services) seed_services ;;
  all)      seed_about; seed_services; feature_samples ;;
  *)        echo "✖ unknown target '$WHAT' (use: about | services | all)"; exit 1 ;;
esac

echo "── Verify ───────────────────────────────────────────────"
echo "   curl -s '$BASE/api/v1/about?size=20'            | jq '.data.content[] | {id, slugCkb, title: .ckbContent.title}'"
echo "   curl -s '$BASE/api/v1/services?size=20'         | jq '.data.content[] | {id, navAnchorId, serviceType}'"
echo "   curl -s '$BASE/api/v1/services/featured'        | jq '.data.content[] | {id, featured, featuredOrder}'"
echo "   curl -s '$BASE/featured?locale=ckb'             | jq '.data[] | {id, type, title}'"
echo "   curl -s '$BASE/featured?locale=kmr'             | jq '.data[] | {id, type, title}'"
echo "✔ done."
