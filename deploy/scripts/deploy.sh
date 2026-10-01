#!/usr/bin/env bash
# =============================================================================
# Deploys one image tag and waits until the application reports ready. Rolls back to the previous
# tag if it does not.
#
#   bash scripts/deploy.sh sha-1a2b3c4   # deploy that tag (what CI runs)
#   bash scripts/deploy.sh               # re-apply the tag recorded in .deployed-tag
#
# Run from /opt/stockflow. GHCR login, when needed, is done by the caller beforehand.
#
# Expect a short outage on every deploy (Flyway + JVM start, one to two minutes on 2 vCPUs): there
# is one application container and it is replaced, not run side by side - a second JVM would not
# fit in 4 GB. Running two side by side would not help yet anyway - JwtKeysConfig generates a fresh signing key at startup, so a new instance
# cannot validate the old one's tokens, and every deploy signs every user out.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

TAG_FILE=.deployed-tag
NGINX_HASH_FILE=.nginx-config-hash
# Above the app healthcheck's 240 s start period, so a slow first boot on 2 vCPUs is not
# mistaken for a failed release.
TIMEOUT_SECONDS=${DEPLOY_TIMEOUT_SECONDS:-300}

previous_tag=$(cat "$TAG_FILE" 2>/dev/null || true)
export IMAGE_TAG=${1:-$previous_tag}
if [ -z "$IMAGE_TAG" ]; then
  echo "No tag given and no $TAG_FILE recorded." >&2
  exit 1
fi

wait_until_healthy() {
  local container status waited=0
  container=$(docker compose ps -q app)
  while [ "$waited" -lt "$TIMEOUT_SECONDS" ]; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo missing)
    case "$status" in
      healthy)   return 0 ;;
      unhealthy) return 1 ;;
    esac
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

# The bind-mounted config files change under a running nginx without compose noticing, and the
# templates are rendered only when the container STARTS - so `nginx -s reload` would never pick up
# a template change. Restart only when something under nginx/ changed, and only after a throwaway
# container (same entrypoint, so the templates are rendered) has validated the result: a broken
# config must not take down the running proxy.
apply_nginx_config() {
  local current
  current=$(find nginx -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
  if [ "$current" = "$(cat "$NGINX_HASH_FILE" 2>/dev/null || true)" ]; then
    return 0
  fi
  if docker compose run --rm --no-deps -T nginx nginx -t; then
    docker compose restart nginx
    echo "$current" > "$NGINX_HASH_FILE"
    echo "==> nginx configuration changed; restarted."
  else
    echo "WARN: new nginx configuration is invalid; the running proxy keeps the old one." >&2
  fi
}

# Fresh Cloudflare ranges on every deploy; a failed fetch keeps the committed snapshot.
bash scripts/refresh-cloudflare-ips.sh || echo "WARN: could not refresh Cloudflare ranges; keeping the current lists."

echo "==> Deploying $IMAGE_TAG (previous: ${previous_tag:-none})"
docker compose pull app
docker compose up -d --remove-orphans

if wait_until_healthy; then
  echo "$IMAGE_TAG" > "$TAG_FILE"
  apply_nginx_config
  docker image prune -f >/dev/null
  echo "==> $IMAGE_TAG is live."
  exit 0
fi

echo "==> $IMAGE_TAG did not become healthy within ${TIMEOUT_SECONDS}s. Last application log lines:" >&2
docker compose logs --tail=150 app >&2 || true

if [ -n "$previous_tag" ] && [ "$previous_tag" != "$IMAGE_TAG" ]; then
  # Safe even after the failed version ran its migrations: Flyway ignores applied migrations newer
  # than the code it ships with (ignoreMigrationPatterns defaults to *:future).
  echo "==> Rolling back to $previous_tag" >&2
  export IMAGE_TAG=$previous_tag
  docker compose up -d app
  wait_until_healthy && echo "==> Rolled back to $previous_tag." >&2 \
    || echo "==> ROLLBACK ALSO FAILED. Investigate with: docker compose logs app" >&2
fi
exit 1
