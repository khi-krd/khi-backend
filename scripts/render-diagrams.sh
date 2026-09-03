#!/usr/bin/env bash
#
# Render every Mermaid diagram in docs/diagrams/ to a standalone SVG in docs/diagrams/.
#
# The markdown is the source of truth. This script is build tooling: it extracts each
# fenced ```mermaid block, renders it, and overwrites the matching SVG. Anything you
# hand-edit inside an SVG is lost on the next run.
#
# Usage:  ./scripts/render-diagrams.sh
# Needs:  node >= 18 and network access on first run (downloads mermaid-cli + Chromium).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/docs/diagrams"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SOURCES=(
  "docs/diagrams/ER.md:er"
  "docs/diagrams/UML_CLASS.md:uml-class"
  "docs/diagrams/UML_SEQUENCE.md:uml-sequence"
  "docs/diagrams/UML_STATE.md:uml-state"
  "docs/diagrams/UML_COMPONENT.md:uml-component"
  "docs/diagrams/FLOWCHARTS.md:flowcharts"
)

echo "Installing mermaid-cli into a temporary directory..."
cd "$WORK"
npm init -y >/dev/null 2>&1
npm install @mermaid-js/mermaid-cli >/dev/null 2>&1
echo '{"args":["--no-sandbox","--disable-dev-shm-usage"]}' > puppeteer.json

extract_and_render() {
  local md="$1" sub="$2"
  local src="$ROOT/$md"
  [ -f "$src" ] || { echo "  skip (missing): $md"; return; }

  python3 - "$src" "$WORK" "$sub" <<'PY'
import re, sys, os, unicodedata
src, work, sub = sys.argv[1], sys.argv[2], sys.argv[3]
base = os.path.splitext(os.path.basename(src))[0].lower().replace('_', '-')
def slug(s):
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    s = re.sub(r'`|\*|\[|\]|\(|\)', '', s)
    return re.sub(r'-+', '-', re.sub(r'[^a-zA-Z0-9]+', '-', s).strip('-').lower())[:60] or "diagram"
os.makedirs(f"{work}/mmd/{sub}", exist_ok=True)
heading, inb, buf, n = "diagram", False, [], 0
for l in open(src, encoding="utf-8").read().splitlines():
    st = l.strip()
    if not inb and re.match(r'^#{2,4}\s', st): heading = re.sub(r'^#+\s*', '', st)
    if not inb and st == "```mermaid": inb, buf = True, []; continue
    if inb and st == "```":
        inb = False; n += 1
        open(f"{work}/mmd/{sub}/{base}-{n:02d}-{slug(heading)}.mmd", "w",
             encoding="utf-8").write("\n".join(buf) + "\n")
        continue
    if inb: buf.append(l)
print(f"  {os.path.basename(src)}: {n} diagrams")
PY

  for f in "$WORK/mmd/$sub"/*.mmd; do
    [ -e "$f" ] || continue
    local name; name="$(basename "$f" .mmd)"
    ./node_modules/.bin/mmdc -i "$f" -o "$OUT/$name.svg" \
      -b white -t default -p puppeteer.json >/dev/null 2>&1 \
      || echo "  FAILED: $name"
  done
}

echo "Extracting and rendering..."
for entry in "${SOURCES[@]}"; do
  extract_and_render "${entry%%:*}" "${entry##*:}"
done

echo "Trimming coordinate precision..."
python3 - "$OUT" <<'PY'
import re, sys, glob, os
out = sys.argv[1]
GEOM = re.compile(r'((?:\sd|\stransform|\spoints|\sviewBox|\swidth|\sheight|\sx|\sy|\scx|\scy|\srx|\sry|\sx1|\sy1|\sx2|\sy2)=")([^"]*)(")')
NUM  = re.compile(r'-?\d+\.\d{3,}')
def rnd(m):
    r = round(float(m.group(0)), 2)
    return str(int(r)) if r == int(r) else f"{r:g}"
b = a = 0
for f in glob.glob(out + "/*.svg"):
    s = open(f, encoding="utf-8").read(); b += len(s)
    s = GEOM.sub(lambda m: m.group(1) + NUM.sub(rnd, m.group(2)) + m.group(3), s)
    open(f, "w", encoding="utf-8").write(s); a += len(s)
print(f"  {b/1048576:.1f} MB -> {a/1048576:.1f} MB")
PY

echo "Done. $(find "$OUT" -maxdepth 1 -name '*.svg' | wc -l | tr -d ' ') SVGs in docs/diagrams/"
echo "Remember to refresh docs/diagrams/README.md if you added or renamed a diagram."
