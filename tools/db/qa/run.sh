#!/usr/bin/env bash
# Runs the adversarial database checks against a database that has every migration AND the demo
# seed applied (a local-profile database). Each script rolls itself back.
#
#   tools/db/qa/run.sh                 # database "stockflow" in the compose container
#   tools/db/qa/run.sh my_scratch_db   # another database in the same container
#
# Exit code 0 = every rule held. A line starting with FAIL names the rule that did not.
set -euo pipefail
DB="${1:-stockflow}"
CONTAINER="${PG_CONTAINER:-stockflow-postgres}"
USER_NAME="${PG_USER:-stockflow}"
DIR="$(cd "$(dirname "$0")" && pwd)"

for script in "$DIR"/[0-9][0-9]_*.sql; do
  echo "== $(basename "$script")"
  docker exec -i "$CONTAINER" psql -U "$USER_NAME" -d "$DB" -q -X -o /dev/null < "$script" 2>&1 \
    | sed -e 's/^psql:<stdin>:[0-9]*: //' -e 's/^NOTICE:  //'
done
