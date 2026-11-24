# Showcase seed for the shared dev server

`showcase.sql` fills a **freshly migrated, empty** database with six months of a printed-cup business, so the
frontend team and the supervisor see every screen with real-looking figures. It is not a Flyway migration and not
`db/demo` (the tests own that one). Nothing in the build runs it. It refuses to load into a database that already
holds users, warehouses or customers.

What it holds and what it leaves out is in the file's header. In short: the team's staff accounts, warehouses BDG and
HNI drawn on the map (walls, dock doors, a walled quarantine room, an office, 24 and 16 shelves of bins in back-to-back
pairs), 5 printable products, 40 customers with about 100 SKUs of their own, 6 producers, 3 truck carriers, and 300
quotes. 271 of those became orders, spread over every stage from draft to delivered (186 delivered). Since
2026-11-24 it also fills every other table a screen reads: e-invoices, returns, delivery outcomes, cycle counts and
adjustments, carrier bookings, design studio, product attributes, chat, notifications and the audit log (193 of 208
tables; the header lists the 15 left empty and why). It holds no files and no events, so nothing is mailed when it
loads. Stock is whole LPN cartons, and every size and weight is the carton's.

Sections 10-90 (between the `-- >>> sections 10-90` markers) were written one area at a time over the rows of
sections 0-20, each in the order the application writes them; change one, then load the whole file into a freshly
migrated database and run the checks below.

Every login it creates has the published demo password `Stockflow@2026`. That is fine on a laptop. On the VPS,
which faces the internet, step 5 below changes it: usernames that existed before get their old passwords back, and
every other account gets a random password nobody knows (an administrator resets it when someone needs that login).

## Resetting the VPS database and loading it

Run in `/opt/stockflow` on the VPS (done this way on 2026-11-22). Users are signed out, and every row is lost. Files
in the S3 bucket stay where they are. The seed loads with the application **stopped**, as it was tested.

```bash
# 1. a backup, copied off the machine afterwards (scp) and checked with pg_restore -l
docker compose exec -T postgres pg_dump -U stockflow -Fc stockflow > /tmp/before-showcase.dump

# 2. the old password hashes
docker compose exec -T postgres psql -U stockflow stockflow -At -F, \
  -c "SELECT username, password_hash FROM identity.app_user" > /tmp/old-passwords.csv
chmod 600 /tmp/old-passwords.csv

# 3. an empty database; the application runs every migration again, then stops
docker compose stop app
docker compose rm -sf postgres
docker volume rm stockflow_postgres-data
docker compose up -d postgres app        # wait until `docker compose ps app` says healthy
docker compose stop app

# 4. the seed (scp tools/db/seed/showcase.sql to /tmp first; about two minutes on the VPS)
docker compose exec -T postgres psql -U stockflow stockflow -v ON_ERROR_STOP=1 -q < /tmp/showcase.sql

# 5. passwords: the old ones back by username, a random unknown one for everyone else. COPY through stdin fails
#    under `docker compose exec`, so the file goes into the container and psql's \copy reads it there.
#    <random-hash>: python3 -c "import bcrypt,secrets;print('{bcrypt}'+bcrypt.hashpw(secrets.token_urlsafe(32).encode(),bcrypt.gensalt(10)).decode())"
docker compose cp /tmp/old-passwords.csv postgres:/tmp/old-passwords.csv
docker compose exec -T postgres psql -U stockflow stockflow -v ON_ERROR_STOP=1 -1 \
  -c "CREATE TEMP TABLE old_pw (username TEXT, password_hash TEXT)" \
  -c "\copy old_pw FROM '/tmp/old-passwords.csv' WITH (FORMAT csv)" \
  -c "UPDATE identity.app_user u SET password_hash = o.password_hash FROM old_pw o WHERE o.username = u.username" \
  -c "UPDATE identity.app_user u SET password_hash = '<random-hash>' WHERE NOT EXISTS (SELECT 1 FROM old_pw o WHERE o.username = u.username)"
docker compose exec -T postgres rm -f /tmp/old-passwords.csv
rm -f /tmp/old-passwords.csv

# 6. empty caches (Redis keeps nothing on disk), then start: the sales rollups are rebuilt from the seeded orders
#    on start (SalesRollupBackfill)
docker compose restart redis
docker compose up -d app
```

## Checking it

- `tools/db/orphan_check.sql` and `tools/db/stock_ledger_balance.sql` both come back clean on it. The scripts in
  `tools/db/qa/` expect `db/demo` rows and do not apply.
- The days are counted back from the day it is loaded, so a database seeded today and one seeded next month
  look alike.
- The contract PDFs, signed scans and production-order slips it names do not exist in storage. Downloading one
  answers 404/503. Every other screen works.

## The layout added on 2026-11-22

The first load drew 10 shelves and no walls or doors. The VPS was not reset again for the new map, because people had
started using it: a one-off script brought the layout up to what `showcase.sql` now draws, in one transaction. The 10
existing shelves kept their ids, codes and bins and moved, 30 empty shelves were added, the areas moved and the maps
widened, and the walls and doors went in. The script was made from the rows a fresh load of the new seed writes, and
checked by applying it to a fresh load of the old seed: the layout came out identical. A backup taken just before it is
`before-layout-20261122T133610.dump`.
