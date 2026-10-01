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

# Refuse to write an empty or truncated list: that would lock every visitor out.
count=$(printf '%s\n' "$ranges" | wc -l)
if [ "$count" -lt 10 ]; then
  echo "Only $count ranges fetched - refusing to overwrite the current lists." >&2
  exit 1
fi

# No date in the header: deploy.sh restarts nginx when these files change, and a date would change
# them on every run.
header="# GENERATED from https://www.cloudflare.com/ips by scripts/refresh-cloudflare-ips.sh. Do not edit by hand."

{ echo "$header"; printf '%s\n' "$ranges" | sed 's/.*/set_real_ip_from &;/'; } \
  > "$SNIPPETS/cloudflare-realip.conf"
{ echo "$header"; printf '%s\n' "$ranges" | sed 's/.*/& 1;/'; } \
  > "$SNIPPETS/cloudflare-geo.conf"

echo "Wrote $count Cloudflare ranges."

if [ "${1:-}" = "--reload" ]; then
  docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload
fi
