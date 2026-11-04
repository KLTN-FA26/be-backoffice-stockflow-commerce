#!/usr/bin/env bash
# =============================================================================
# Regenerates the two Cloudflare range lists nginx trusts, from Cloudflare's published lists.
#
#   ./scripts/refresh-cloudflare-ips.sh            # write the files
#   ./scripts/refresh-cloudflare-ips.sh --reload   # ... and reload nginx if the config still parses
#
# Cloudflare changes these rarely, but it does change them. A missing range means requests through
# that edge are dropped (444) and their client address is never restored. The committed files are
# a snapshot; run this on the VPS at install and from a monthly cron.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
SNIPPETS=nginx/snippets

ranges=$(
  { curl -fsS --max-time 10 https://www.cloudflare.com/ips-v4; echo
    curl -fsS --max-time 10 https://www.cloudflare.com/ips-v6; echo; } | sed '/^\s*$/d'
)

# Every line becomes an nginx directive, so every line must be a CIDR. `curl -f` only rejects HTTP
# errors: a captive portal or a challenge page answers 200 with HTML, and writing that out would
# leave an nginx config that cannot start.
cidr='^([0-9]{1,3}(\.[0-9]{1,3}){3}|[0-9a-fA-F:]+:[0-9a-fA-F:]*)/[0-9]{1,3}$'
if printf '%s\n' "$ranges" | grep -Evq "$cidr"; then
  echo "Cloudflare answered with something that is not a list of CIDR ranges - keeping the current lists:" >&2
  printf '%s\n' "$ranges" | grep -Ev "$cidr" | head -3 >&2
  exit 1
fi

# Refuse to write an empty or truncated list: that would lock every visitor out.
count=$(printf '%s\n' "$ranges" | wc -l)
if [ "$count" -lt 10 ]; then
  echo "Only $count ranges fetched - refusing to overwrite the current lists." >&2
  exit 1
fi

# No date in the header: deploy.sh restarts nginx when these files change, and a date would change
# them on every run.
header="# GENERATED from https://www.cloudflare.com/ips by scripts/refresh-cloudflare-ips.sh. Do not edit by hand."

# Written beside the targets and renamed into place, so nginx never reads a half-written file.
{ echo "$header"; printf '%s\n' "$ranges" | sed 's/.*/set_real_ip_from &;/'; } \
  > "$SNIPPETS/.cloudflare-realip.conf.new"
{ echo "$header"; printf '%s\n' "$ranges" | sed 's/.*/& 1;/'; } \
  > "$SNIPPETS/.cloudflare-geo.conf.new"
mv "$SNIPPETS/.cloudflare-realip.conf.new" "$SNIPPETS/cloudflare-realip.conf"
mv "$SNIPPETS/.cloudflare-geo.conf.new" "$SNIPPETS/cloudflare-geo.conf"

echo "Wrote $count Cloudflare ranges."

if [ "${1:-}" = "--reload" ]; then
  docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload
fi
