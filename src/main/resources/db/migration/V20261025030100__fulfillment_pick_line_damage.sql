-- =============================================================================
-- Damage found while picking (SCRUM-277; docs 04c X-SHORT, 03 §5). Module: fulfillment. Schema owner review: Tu.
--
-- A picker at a line's bin reports that some of the allocated units are damaged (reason and photo, BR-STK-003)
-- instead of confirming the line. In the same transaction inventory moves the damaged units to quarantine as
-- DAMAGED (inventory.stock_damage_report) and the line is superseded, the way a reallocated short line is:
--
--   * the line itself becomes CANCELLED with damaged_qty = the damaged units and damage_report_id set;
--   * the good units stay to be picked on a new OPEN line at the same bin, against the same allocation;
--   * the damaged units are replaced by new OPEN lines where inventory found other stock (FEFO), or wait on a new
--     SHORT line (short_qty = the damaged units) when it found none, the order ON_HOLD (INVENTORY) until the
--     coordinator reallocates that line - the existing X-SHORT path.
--
-- Every line a damage report created or superseded names it (damage_report_id), which is how the same report sent
-- again is answered with the lines it made. Only a superseded line carries damaged units.
--
-- The key to the report is DEFERRABLE: the report and the lines are written by two modules in one transaction.
-- Data: new columns with defaults; every existing line has damaged_qty 0 and no report.
-- =============================================================================

ALTER TABLE fulfillment.pick_line
    ADD COLUMN damaged_qty      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN damage_report_id UUID,
    ADD CONSTRAINT fk_pick_line_damage_report FOREIGN KEY (damage_report_id)
        REFERENCES inventory.stock_damage_report (id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT ck_pick_line_damaged CHECK (damaged_qty >= 0
        AND (damaged_qty = 0 OR (status = 'CANCELLED' AND damage_report_id IS NOT NULL)));

-- BR-04: picked, short and damaged together account for at most the allocation.
ALTER TABLE fulfillment.pick_line DROP CONSTRAINT ck_pick_line_within_allocation;
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_within_allocation
    CHECK (picked_qty + short_qty + damaged_qty <= allocated_qty);

CREATE INDEX ix_pick_line_damage_report ON fulfillment.pick_line (damage_report_id)
    WHERE damage_report_id IS NOT NULL;

COMMENT ON COLUMN fulfillment.pick_line.damaged_qty IS
    'Units the picker found damaged at the bin (SCRUM-277); only on the CANCELLED line the damage report superseded.';
COMMENT ON COLUMN fulfillment.pick_line.damage_report_id IS
    'The damage report (inventory.stock_damage_report) that superseded this line, or that created it.';
