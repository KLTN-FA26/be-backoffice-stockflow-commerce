-- =============================================================================
-- Data fix: every pick has its pack row (E2E QA 2026-10-21, F1).
-- Module: fulfillment
--
-- The application opens the pack row together with the pick (FulfillmentServiceImpl.createTask), but picks loaded
-- around it - tools/db/seed/seed_test_data.sql before its generator was fixed made three PICKING picks without one -
-- have none, and the task list answered 404 for everyone because of them. The code now reads a missing row as
-- "packing not started"; this gives those picks the row packing needs: PENDING (nothing packed yet), or CANCELLED for
-- a cancelled pick, as the cancellation listener would have left it. Idempotent: a pick that has its row is skipped
-- (uk_fulfillment_pack_pick), and on a database without such picks nothing changes.
-- =============================================================================

INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, created_at, created_by)
SELECT md5('pack-backfill:' || p.id::text)::uuid,
       p.id,
       CASE WHEN p.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END,
       NULL,
       p.created_at,
       'V20261021001100'
  FROM fulfillment.pick p
 WHERE NOT EXISTS (SELECT 1 FROM fulfillment.pack k WHERE k.pick_id = p.id);
