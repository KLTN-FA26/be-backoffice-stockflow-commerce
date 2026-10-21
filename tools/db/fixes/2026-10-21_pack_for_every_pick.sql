-- =============================================================================
-- Backfill: give every fulfillment.pick its fulfillment.pack (FE test of SCRUM-468, 2026-10-21).
--
-- The application opens a pick and its pack in one transaction (FulfillmentServiceImpl.createTask) and
-- every read of a task needs both: GET /fulfillment/tasks maps each row through requirePack, so a single
-- pick without a pack turns the whole page into a 404. Picks without one came from the seed generator,
-- which wrote a pack only for PICKED picks (tools/db/seed/gen_seed_test_data.py, fixed the same day).
--
-- The missing pack is written as the application would have left it: PENDING from the pick's creation,
-- or CANCELLED when the pick was (OrderCancellationListener cancels both). Ids are derived from the
-- pick's id, so a second run inserts nothing (uk_fulfillment_pack_pick as well). Data only, no schema
-- change; run it on a deployed database once. From a checkout, against the VPS (deploy/README.md
-- "Database access"); the container's own POSTGRES_USER / POSTGRES_DB are DB_USER / DB_NAME of its .env:
--
--   ssh deploy@<vps-ip> 'cd /opt/stockflow && docker compose exec -T postgres \
--       sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\""' \
--       < tools/db/fixes/2026-10-21_pack_for_every_pick.sql
--
-- Against the local docker compose database:
--
--   docker exec -i stockflow-postgres psql -v ON_ERROR_STOP=1 -U stockflow -d stockflow \
--       < tools/db/fixes/2026-10-21_pack_for_every_pick.sql
-- =============================================================================

BEGIN;

\echo '--- picks without a pack, before'
SELECT pk.status AS pick_status, count(*) AS picks
  FROM fulfillment.pick pk
 WHERE NOT EXISTS (SELECT 1 FROM fulfillment.pack pa WHERE pa.pick_id = pk.id)
 GROUP BY pk.status
 ORDER BY pk.status;

INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, version, created_at, created_by)
SELECT md5('backfill:pack:' || pk.id)::uuid,
       pk.id,
       CASE WHEN pk.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END,
       NULL,
       0,
       pk.created_at,
       'backfill-2026-10-21'
  FROM fulfillment.pick pk
 WHERE NOT EXISTS (SELECT 1 FROM fulfillment.pack pa WHERE pa.pick_id = pk.id)
ON CONFLICT (pick_id) DO NOTHING;

\echo '--- picks without a pack, after (must be 0)'
SELECT count(*) AS picks_without_pack
  FROM fulfillment.pick pk
 WHERE NOT EXISTS (SELECT 1 FROM fulfillment.pack pa WHERE pa.pick_id = pk.id);

COMMIT;
