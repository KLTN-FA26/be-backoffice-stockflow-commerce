-- =============================================================================
-- Inventory item: drop min_qty. The minimum stock threshold is the reorder point.
-- Module: inventory
--
-- kltn-docs 02 §4 step 4: "ngưỡng tồn tối thiểu (reorder point) từ module 01", and BR-08 raises a
-- replenishment proposal when (on hand + on order - committed) < Reorder Point. 01 §3/§4 step 5 "ngưỡng
-- min/max" is therefore reorder_point / max_qty. min_qty (V20260928003000) was a second "minimum" that no
-- code reads; only the demo and test seeds wrote it. Keeping it would let the two disagree.
--
-- Dropping the column drops ck_inventory_items_thresholds with it (it names min_qty); the per-column
-- checks that remain meaningful are put back. max_qty against reorder_point is the policy screen's rule
-- (see the open PR on maxQty), not restated here.
-- =============================================================================

ALTER TABLE inventory.inventory_items DROP COLUMN min_qty;

ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT ck_inventory_items_thresholds CHECK ((max_qty IS NULL OR max_qty >= 0)
        AND (reorder_point IS NULL OR reorder_point >= 0));
