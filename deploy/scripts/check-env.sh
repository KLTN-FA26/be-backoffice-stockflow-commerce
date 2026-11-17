#!/usr/bin/env bash
# Compares the server's .env with .env.example, by NAME only - no value is ever printed.
#
#   Run from /opt/stockflow (where the deploy copies .env.example):   scripts/check-env.sh
#
# Reports:
#   MISSING   set in .env.example, absent from .env           -> add it (copy the line and fill it in)
#   EMPTY     required by docker-compose.yml (${VAR:?...}) but empty in .env -> compose will refuse to start
#   CHANGE_ME still holding the template's placeholder       -> replace it
#   OPTIONAL  commented out in .env.example, absent from .env -> fine unless you need that feature
#   UNKNOWN   in .env, not in .env.example                     -> a typo, or a variable no longer used
# Exit code 1 when anything is MISSING, EMPTY or CHANGE_ME.
set -euo pipefail

cd "$(dirname "$0")/.."
example=.env.example
env=${1:-.env}
[[ -f $example ]] || { echo "no $example here ($(pwd))" >&2; exit 2; }
[[ -f $env ]] || { echo "no $env here ($(pwd)): cp $example $env && chmod 600 $env" >&2; exit 2; }

names() { grep -E "^${2:-}[A-Z][A-Z0-9_]*=" "$1" | sed -E "s/^${2:-}([A-Z][A-Z0-9_]*)=.*/\1/" | sort -u; }
active=$(names "$example")
optional=$(names "$example" '# ?' | comm -23 - <(echo "$active"))
present=$(names "$env")
required=$(grep -oE '\$\{[A-Z][A-Z0-9_]*:\?' docker-compose.yml | sed -E 's/\$\{([A-Z0-9_]+):\?/\1/' | sort -u)
value() { grep -E "^$1=" "$env" | tail -1 | cut -d= -f2- | tr -d '\r'; }

problems=0
report() { printf '%-10s %s\n' "$1" "$2"; }

while read -r name; do
  [[ -z $name ]] && continue
  if ! grep -qx "$name" <<<"$present"; then report MISSING "$name"; problems=1; fi
done <<<"$active"

while read -r name; do
  [[ -z $name ]] && continue
  # IMAGE_TAG is written by scripts/deploy.sh, not by hand.
  [[ $name == IMAGE_TAG ]] && continue
  if grep -qx "$name" <<<"$present" && [[ -z $(value "$name") ]]; then report EMPTY "$name"; problems=1; fi
done <<<"$required"

while read -r name; do
  [[ -z $name ]] && continue
  if [[ $(value "$name") == CHANGE_ME* ]]; then report CHANGE_ME "$name"; problems=1; fi
done <<<"$present"

while read -r name; do
  [[ -z $name ]] && continue
  grep -qx "$name" <<<"$present" || report OPTIONAL "$name"
done <<<"$optional"

while read -r name; do
  [[ -z $name ]] && continue
  grep -qx "$name" <<<"$active" || grep -qx "$name" <<<"$optional" || [[ $name == IMAGE_TAG ]] || report UNKNOWN "$name"
done <<<"$present"

if (( problems )); then echo "-> fix MISSING / EMPTY / CHANGE_ME, then: docker compose config --quiet && echo CONFIG OK"; exit 1; fi
echo "OK: every variable of $example is in $env"
