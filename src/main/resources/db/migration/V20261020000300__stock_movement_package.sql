-- =============================================================================
-- Stock movement ledger: which stock package the stock left and which it went into.
-- Module: inventory   Issue: GitHub #125, Jira SCRUM-473
--
-- A stock package's history is then its ledger rows: received into it, put away with it, picked out
-- of it, moved with it. A side's stock package belongs to that side's location, so one without a
-- location on its side is refused (a RECEIPT has no source, a PICK no target: V20260928005000's shape
-- check). Moving a whole stock package names it on both sides; emptying it onto a bin names it as the
-- source only. The ledger's reference_type 'PACKAGE' stays the shipped parcel (fulfillment.package).
-- Adding the columns does not touch tg_stock_movement_append_only: no row is updated.
-- =============================================================================

ALTER TABLE inventory.stock_movement
    ADD COLUMN from_stock_package_id UUID,
    ADD COLUMN to_stock_package_id   UUID,
    ADD CONSTRAINT fk_stock_movement_from_package FOREIGN KEY (from_stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_movement_to_package FOREIGN KEY (to_stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_stock_movement_package_side CHECK (
        (from_stock_package_id IS NULL OR from_location_code IS NOT NULL)
        AND (to_stock_package_id IS NULL OR to_location_code IS NOT NULL));

CREATE INDEX ix_stock_movement_from_package ON inventory.stock_movement (from_stock_package_id, occurred_at)
    WHERE from_stock_package_id IS NOT NULL;
CREATE INDEX ix_stock_movement_to_package ON inventory.stock_movement (to_stock_package_id, occurred_at)
    WHERE to_stock_package_id IS NOT NULL;

COMMENT ON COLUMN inventory.stock_movement.from_stock_package_id IS 'The stock package the stock left, if any.';
COMMENT ON COLUMN inventory.stock_movement.to_stock_package_id IS 'The stock package the stock went into, if any.';
