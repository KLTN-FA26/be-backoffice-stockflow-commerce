#!/usr/bin/env bash
# =============================================================================
# Creates a staff account on the dev server, or resets an existing one's password and adds roles.
#
#   bash scripts/create-user.sh admin admin@example.com ECOMMERCE_ADMIN
#   bash scripts/create-user.sh kho1  kho1@example.com  WAREHOUSE_STAFF WAREHOUSE_MANAGER
#
# Why this exists: the dev profile carries no demo users that can sign in (the demo seed's authors
# are DISABLED on purpose) and the application has no first-admin bootstrap, so without it nobody
# can obtain a token on a server where security is on. Locally nobody notices, because the local
# profile switches security off.
#
# Role codes: WAREHOUSE_STAFF WAREHOUSE_MANAGER INVENTORY_PLANNER QC_STAFF CUSTOMER SALES_STAFF
#             ORDER_COORDINATOR ECOMMERCE_ADMIN PROCUREMENT_STAFF ACCOUNTANT
#
# The password is read from the terminal, never from the command line (which would leave it in
# shell history and the process list), hashed with bcrypt in the {bcrypt} format the application's
# DelegatingPasswordEncoder expects, and handed to psql as a variable - never spliced into SQL.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -lt 3 ]; then
  sed -n '3,6p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi

username=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
email=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')
shift 2
roles=$(IFS=,; echo "$*")

# Checked here so a typo fails with a clear message and a non-zero exit, before anything is asked
# or written. The SQL below checks again against identity.app_role, the actual source of truth.
known="WAREHOUSE_STAFF WAREHOUSE_MANAGER INVENTORY_PLANNER QC_STAFF CUSTOMER SALES_STAFF ORDER_COORDINATOR ECOMMERCE_ADMIN PROCUREMENT_STAFF ACCOUNTANT"
for role in "$@"; do
  case " $known " in
    *" $role "*) ;;
    *) echo "Unknown role '$role'. One of: $known" >&2; exit 1 ;;
  esac
done

command -v htpasswd >/dev/null || { echo "htpasswd not found: apt install -y apache2-utils" >&2; exit 1; }

read -r -s -p "Password for $username (12+ characters): " password; echo
read -r -s -p "Again: " again; echo
[ "$password" = "$again" ] || { echo "Passwords differ." >&2; exit 1; }
[ "${#password}" -ge 12 ] || { echo "Use at least 12 characters - this server is on the internet." >&2; exit 1; }

# htpasswd writes "x:$2y$10$..."; $2y$ and $2a$ are the same algorithm under a different tag, and
# $2a$ is the one every bcrypt implementation accepts.
hash=$(printf '%s' "$password" | htpasswd -n -i -B -C 10 x | cut -d: -f2 | sed 's/^\$2y\$/$2a$/')
unset password again

# Credentials for psql come from the postgres container's own environment.
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -v username="$1" -v email="$2" -v hash="$3" -v roles="$4"' sh \
    "$username" "$email" "{bcrypt}$hash" "$roles" <<'SQL'
BEGIN;

-- Every requested role must exist; a typo would otherwise create an account that can do nothing.
SELECT CASE WHEN count(*) = cardinality(string_to_array(:'roles', ','))
            THEN 'roles ok'
            ELSE 'unknown role in: ' || :'roles' END AS result
FROM identity.app_role WHERE code = ANY (string_to_array(:'roles', ','))
\gset role_
SELECT :'role_result' = 'roles ok' AS roles_valid \gset
\if :roles_valid
\else
  \echo :role_result
  -- \quit would exit 0 and the caller would report success; a failing statement under
  -- ON_ERROR_STOP rolls the transaction back and exits non-zero.
  SELECT 1 / 0 AS aborted_unknown_role;
\endif

-- gen_random_uuid rather than the application's UUIDv7: the time ordering only matters for index
-- locality on bulk inserts, and this is a handful of rows.
INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES (gen_random_uuid(), :'username', :'email', :'hash', :'username', 'ACTIVE', 0, now(), 'create-user.sh')
ON CONFLICT (username) DO UPDATE
    SET password_hash = EXCLUDED.password_hash, status = 'ACTIVE',
        version = identity.app_user.version + 1, last_modified_at = now(), last_modified_by = 'create-user.sh';

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT gen_random_uuid(), u.id, r.id, 0, now(), 'create-user.sh'
FROM identity.app_user u
JOIN identity.app_role r ON r.code = ANY (string_to_array(:'roles', ','))
WHERE u.username = :'username'
ON CONFLICT (user_id, role_id) DO NOTHING;

COMMIT;

SELECT u.username, u.status, string_agg(r.code, ', ' ORDER BY r.code) AS roles
FROM identity.app_user u
JOIN identity.user_role ur ON ur.user_id = u.id
JOIN identity.app_role r ON r.id = ur.role_id
WHERE u.username = :'username'
GROUP BY u.username, u.status;
SQL

cat <<EOF

Sign in:  POST https://api.\${DOMAIN}/api/v1/identity/auth/login
          {"username": "$username", "password": "..."}
Then send the accessToken as  Authorization: Bearer <token>  (Swagger UI: the Authorize button).
EOF
