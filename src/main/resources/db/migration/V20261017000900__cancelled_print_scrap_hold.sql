-- Review of PR #109, item 5a (decision 2026-10-16, BR-PRD-10): the custom-printed goods of a cancelled order are
-- scrapped, never sold again. Finished goods wait in a PACKING area under their order's hold, which keeps them out
-- of available-to-promise and out of every other order's reach. Their SCRAP write-off names that hold: approving it
-- closes the hold and deducts the units once; a rejection leaves them held, isolated, until a new write-off is
-- asked for. One live write-off per hold.
--
-- Additive only: a nullable column, its foreign key, a CHECK on rows that set it, and a partial unique index.
-- Existing rows have no hold and are untouched.

ALTER TABLE inventory.stock_adjustment ADD COLUMN reservation_id UUID;

ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT fk_stock_adjustment_reservation
    FOREIGN KEY (reservation_id) REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT;

-- A write-off of held goods only takes them away, and only as scrap.
ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_reservation_scrap
    CHECK (reservation_id IS NULL OR (quantity_delta < 0 AND reason_code = 'SCRAP'));

-- Asked for once while it stands: a second cancellation, a retry or a replayed event finds the first.
CREATE UNIQUE INDEX uk_stock_adjustment_live_reservation ON inventory.stock_adjustment (reservation_id)
    WHERE reservation_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'APPROVED', 'POSTED');

COMMENT ON COLUMN inventory.stock_adjustment.reservation_id IS
    'The hold whose units this SCRAP write-off takes: printed goods of a cancelled order (BR-PRD-10). Approval closes the hold.';
