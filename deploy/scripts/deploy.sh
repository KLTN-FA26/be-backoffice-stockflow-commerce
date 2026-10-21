#!/usr/bin/env bash
# =============================================================================
# Deploys one image tag and waits until the application reports ready. Rolls back to the previous
# tag if it does not.
#
#   bash scripts/deploy.sh sha-1a2b3c4   # deploy that tag (what CI runs)
#   bash scripts/deploy.sh               # re-apply the running tag, e.g. after editing .env
#   bash scripts/deploy.sh --rotate-jwt-key              # new JWT signing key; everyone signs in again
#   bash scripts/deploy.sh --install-jwt-key <pem-file>  # use this PKCS#8 key instead; everyone signs in again
#
# The two key commands touch only ./jwt and restart the running tag; nothing is pulled or migrated. ./jwt belongs to
# the container user (uid 10001, mode 700), so the deploy user cannot rm or copy there itself: both go through a
# throwaway root container of the running image. If the application does not come back healthy with the new key, the
# previous key is put back and the application restarted with it.
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
# fit in 4 GB. Users stay signed in across it: the JWT signing key is kept in ./jwt (see
# prepare_jwt_key_dir), not generated per start.
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
key_action=
key_source=
case "${1:-}" in
  --rotate-jwt-key)
    key_action=rotate; shift ;;
  --install-jwt-key)
    key_action=install; key_source=${2:-}; shift $(( $# < 2 ? $# : 2 ))
    if [ -z "$key_source" ] || [ ! -r "$key_source" ]; then
      echo "Usage: bash scripts/deploy.sh --install-jwt-key <readable PKCS#8 PEM file>" >&2
      exit 1
    fi
    if ! grep -q -- '-----BEGIN PRIVATE KEY-----' "$key_source"; then
      echo "$key_source is not a PKCS#8 PEM key (BEGIN PRIVATE KEY). Make one with:" >&2
      echo "  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out signing-key.pem" >&2
      exit 1
    fi ;;
  --*)
    echo "Unknown option $1" >&2; exit 1 ;;
esac
if [ -n "$key_action" ] && [ $# -gt 0 ]; then
  echo "The key commands restart the running tag; they take no tag." >&2
  exit 1
fi
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

# A dump of the database before every deploy that changes the running tag. A new image may carry a
# migration that cannot be undone (a contract step drops tables), and the rollback below only
# rolls back the image - Flyway never un-applies a migration. So the deploy does not start without a
# restorable copy of what is there now. Restore with:
#   docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists < backups/<file>
# Kept: the last 10 pre-deploy dumps, next to the nightly ones of the README's cron. They are on the
# same disk, so they protect against a bad release, not against losing the machine.
backup_database() {
  if [ -z "$(docker compose ps --status running -q postgres 2>/dev/null)" ]; then
    echo "==> postgres is not running yet (first deploy?); no pre-deploy dump."
    return 0
  fi
  local db user file
  db=$(env_value DB_NAME)
  user=$(env_value DB_USER)
  mkdir -p backups
  file="backups/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ)-from-${previous_tag:-none}.dump"
  # pg_dump turns statement_timeout and lock_timeout off for its own session, so the server's 10 s
  # statement limit does not cut a long table short. Written beside the target first: a dump that
  # stopped half way must never look like a good one.
  if docker compose exec -T postgres pg_dump -U "$user" -Fc "$db" > "$file.partial" && [ -s "$file.partial" ]; then
    mv "$file.partial" "$file"
    echo "==> Database dumped to $file ($(du -h "$file" | cut -f1))."
  else
    rm -f "$file.partial"
    echo "==> The pre-deploy database dump FAILED; nothing was deployed." >&2
    return 1
  fi
  ls -1t backups/pre-deploy-*.dump 2>/dev/null | tail -n +11 | xargs -r rm -f
}

# The JWT signing key's directory (JWT_PRIVATE_KEY_PATH in docker-compose.yml). The application runs as the image's
# unprivileged user, uid/gid 10001 (pinned in the Dockerfile so it survives base-image bumps), and writes the key there
# on its first start, so the directory must be that user's. A throwaway root container of the same image sets it,
# which needs no sudo here. Owner-only: whoever reads the key can sign a token for any account. Idempotent; run on
# every deploy.
JWT_KEY_OWNER=10001:10001
jwt_root() { # runs a shell command as root in a throwaway container of the running image, ./jwt at /keys
  docker run --rm -i --user 0 --entrypoint sh -v "$(pwd)/jwt:/keys" "$app_image:$IMAGE_TAG" -c "$1"
}
prepare_jwt_key_dir() {
  mkdir -p jwt
  jwt_root "chown -R $JWT_KEY_OWNER /keys && chmod 700 /keys && find /keys -type f -exec chmod 600 {} +" </dev/null
}

# --rotate-jwt-key / --install-jwt-key: see the header. The previous key is kept as signing-key.pem.previous until the
# application is healthy with the new one, then removed (a leaked key must not linger).
change_jwt_key() {
  prepare_jwt_key_dir
  jwt_root 'if [ -f /keys/signing-key.pem ]; then mv -f /keys/signing-key.pem /keys/signing-key.pem.previous; fi' \
    </dev/null
  if [ "$key_action" = install ]; then
    jwt_root 'umask 077 && cat > /keys/signing-key.pem.new && mv -f /keys/signing-key.pem.new /keys/signing-key.pem' \
      < "$key_source"
  fi
  # rotate: no key file - the application generates a new one on start.
  prepare_jwt_key_dir
  docker compose restart app
  if wait_until_healthy; then
    jwt_root 'rm -f /keys/signing-key.pem.previous' </dev/null
    echo "==> New JWT signing key in use ($key_action); every user signs in again once."
    return 0
  fi
  echo "==> The application did not become healthy with the new key. Last log lines:" >&2
  docker compose logs --tail=60 app >&2 || true
  jwt_root 'if [ -f /keys/signing-key.pem.previous ]; then mv -f /keys/signing-key.pem.previous /keys/signing-key.pem; fi' \
    </dev/null
  docker compose restart app
  if wait_until_healthy; then
    echo "==> Previous key restored; nothing changed." >&2
  else
    echo "==> The application is not healthy with the previous key either. Investigate: docker compose logs app" >&2
  fi
  return 1
}

if [ -n "$key_action" ]; then
  if [ -z "$(docker compose ps -q app 2>/dev/null)" ]; then
    echo "The application is not running; deploy a tag first." >&2
    exit 1
  fi
  change_jwt_key
  exit $?
fi

# Fresh Cloudflare ranges on every deploy; a failed fetch keeps the current lists.
bash scripts/refresh-cloudflare-ips.sh || echo "WARN: could not refresh Cloudflare ranges; keeping the current lists."

echo "==> Deploying $IMAGE_TAG (previous: ${previous_tag:-none})"
if [ "$IMAGE_TAG" != "$previous_tag" ]; then
  backup_database || exit 1
fi
# Pull only what is missing. Re-applying the running tag after an .env change, or rolling back to a
# tag still on disk, must not depend on a registry login that only exists during a CI job.
if [ -n "$app_image" ] && docker image inspect "$app_image:$IMAGE_TAG" >/dev/null 2>&1; then
  echo "==> $app_image:$IMAGE_TAG is already here; not pulling."
else
  docker compose pull app
fi
if ! prepare_jwt_key_dir; then
  echo "==> Could not prepare ./jwt for the JWT signing key; nothing was deployed." >&2
  exit 1
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
