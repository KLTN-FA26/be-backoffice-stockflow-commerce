#!/usr/bin/env bash
# =============================================================================
# Deploys one image tag and waits until the application reports ready. Rolls back to the previous
# tag if it does not.
#
#   bash scripts/deploy.sh sha-1a2b3c4   # deploy that tag (what CI runs)
#   bash scripts/deploy.sh               # re-apply the running tag, e.g. after editing .env
#
# Run from /opt/stockflow. GHCR login is needed only when the tag is not already on this machine;
# CI logs in for its own run and out again afterwards.
#
# The running tag is recorded as IMAGE_TAG in .env, the file compose reads by itself. Anywhere
# else (a separate state file, the shell), a plain `docker compose up -d` would not see it and
# would recreate the application from a different image. ROLLBACK_TAG beside it is the last
# different tag that ran: the image kept for a rollback, which re-applying the same tag must not
# lose.
#
# Expect a short outage on every deploy (Flyway + JVM start, one to two minutes on 2 vCPUs): there
# is one application container and it is replaced, not run side by side - a second JVM would not
# fit in 4 GB. Side by side would not help yet anyway: JwtKeysConfig generates a fresh signing key
# at startup, so a new instance cannot validate the old one's tokens, and every deploy signs every
# user out.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

# One deploy at a time: CI and an operator rolling back by hand must not interleave `up`, the
# health wait and the rollback. The lock is on the directory itself - CI's rsync replaces files in
# it, which would silently break a lock held on one of them.
exec 9<.
flock -n 9 || { echo "Another deploy is running in $(pwd)." >&2; exit 1; }

NGINX_HASH_FILE=.nginx-config-hash
# Above the app healthcheck's 240 s start period, so a slow first boot on 2 vCPUs is not
# mistaken for a failed release.
TIMEOUT_SECONDS=${DEPLOY_TIMEOUT_SECONDS:-300}

[ -f .env ] || { echo "No .env here - copy .env.example to .env and fill it in first." >&2; exit 1; }

env_value() { sed -n "s/^$1=//p" .env | tail -n 1; }

set_env() {
  if grep -q "^$1=" .env; then
    sed -i "s/^$1=.*/$1=$2/" .env
  else
    printf '\n# Written by scripts/deploy.sh - do not edit.\n%s=%s\n' "$1" "$2" >> .env
  fi
}

# Records a tag that has just become healthy. The tag it replaced becomes the rollback target only
# when it differs - re-applying the running tag (after an .env change) keeps the real one.
record_tag() {
  if [ -n "$previous_tag" ] && [ "$previous_tag" != "$1" ]; then
    set_env ROLLBACK_TAG "$previous_tag"
    rollback_tag=$previous_tag
  fi
  set_env IMAGE_TAG "$1"
}

app_image=$(env_value APP_IMAGE)
# .deployed-tag is where earlier versions of this script kept the tag; read once, then superseded.
previous_tag=$(env_value IMAGE_TAG)
[ -n "$previous_tag" ] || previous_tag=$(cat .deployed-tag 2>/dev/null || true)
rollback_tag=$(env_value ROLLBACK_TAG)
export IMAGE_TAG=${1:-$previous_tag}
if [ -z "$IMAGE_TAG" ]; then
  echo "No tag given and none recorded in .env." >&2
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
# container (same entrypoint, so the templates are rendered) has validated the result.
#
# An invalid configuration FAILS the deploy. The running proxy keeps serving from memory, but the
# broken files are already on disk: the next restart - a reboot, an OOM kill - would crash-loop it
# and take every site down, long after a green deploy had made everyone stop looking.
apply_nginx_config() {
  local current
  current=$(find nginx -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
  if [ "$current" = "$(cat "$NGINX_HASH_FILE" 2>/dev/null || true)" ]; then
    return 0
  fi
  docker compose run --rm --no-deps -T nginx nginx -t || return 1
  docker compose restart nginx
  echo "$current" > "$NGINX_HASH_FILE"
  echo "==> nginx configuration changed; restarted."
}

# Keeps the running tag and the rollback target, removes older application images.
# `docker image prune` alone would never reach them: every deploy's image carries its own tag, so
# none of them is ever dangling.
remove_old_images() {
  [ -n "$app_image" ] || return 0
  docker images "$app_image" --format '{{.Tag}}' | while read -r tag; do
    case "$tag" in
      "$IMAGE_TAG"|"$rollback_tag") ;;
      sha-*) docker rmi "$app_image:$tag" >/dev/null 2>&1 || true ;;
    esac
  done
  docker image prune -f >/dev/null
}

# Fresh Cloudflare ranges on every deploy; a failed fetch keeps the current lists.
bash scripts/refresh-cloudflare-ips.sh || echo "WARN: could not refresh Cloudflare ranges; keeping the current lists."

echo "==> Deploying $IMAGE_TAG (previous: ${previous_tag:-none})"
# Pull only what is missing. Re-applying the running tag after an .env change, or rolling back to a
# tag still on disk, must not depend on a registry login that only exists during a CI job.
if [ -n "$app_image" ] && docker image inspect "$app_image:$IMAGE_TAG" >/dev/null 2>&1; then
  echo "==> $app_image:$IMAGE_TAG is already here; not pulling."
else
  docker compose pull app
fi
docker compose up -d --remove-orphans

if wait_until_healthy; then
  record_tag "$IMAGE_TAG"
  rm -f .deployed-tag
  if ! apply_nginx_config; then
    echo "==> $IMAGE_TAG is live, but the new nginx configuration is INVALID (output above)." >&2
    echo "    The proxy still runs the previous one from memory; fix the config and deploy again." >&2
    exit 1
  fi
  remove_old_images
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
  if wait_until_healthy; then
    # IMAGE_TAG in .env was never moved off the previous tag, so there is nothing to record.
    echo "==> Rolled back to $previous_tag." >&2
  else
    echo "==> ROLLBACK ALSO FAILED. Investigate with: docker compose logs app" >&2
  fi
fi
exit 1
