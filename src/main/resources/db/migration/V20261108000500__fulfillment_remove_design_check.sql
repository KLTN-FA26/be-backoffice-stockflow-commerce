-- =============================================================================
-- The warehouse no longer checks designs (kltn-docs 08 §1, decided 2026-11-04): printed goods arrive from the producer
-- already carrying the customer's design, so packing checks only the SKU and the count. The design checksum compared at
-- packing completion (BR-DSG-003), the hold it raised on the package and the order (DESIGN_MISMATCH), the warehouse
-- manager's re-verification and the download of design files at the warehouse are gone from the code; this removes
-- what the database kept for them. Every row dropped, and every value changed, is archived in platform.legacy_archive
-- first.
--
-- Kept on purpose:
--   * ordering.order_hold rows whose reason is DESIGN_MISMATCH and that were resolved, and that value in
--     ck_order_hold_reason of that table: the history of holds raised and resolved (the column is read as text, never
--     as the enum);
--   * the order lines' design snapshot (print lines): the order still names the design it was sold with until lines
--     name the customer's printed SKU.
--
-- Data:
--   * a package held for a design check goes back to PACKING: the packer completes it again, and no design is checked;
--   * an order on a DESIGN_MISMATCH hold goes back to the status it was held from, the change in its history; its open
--     hold row is archived and removed (a resolution names who resolved it - ck_order_hold_resolution - and nobody
--     did: the status history says why the hold ended).
--
-- Order:
--   1. archive; 2. held packages and orders released; 3. CHECKs recreated without the hold, the request id column
--   dropped; 4. the verification table with its guard; 5. the re-verification permission, advancing app_role.version
--   of every role that held it.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'fulfillment.design_verification', v.id, to_jsonb(v) FROM fulfillment.design_verification v
UNION ALL
SELECT 'fulfillment.pack.design_hold', p.id,
       jsonb_build_object('id', p.id, 'status', p.status, 'held_request_id', p.held_request_id)
FROM fulfillment.pack p WHERE p.status = 'ON_HOLD' OR p.held_request_id IS NOT NULL
UNION ALL
SELECT 'ordering.customer_order.design_hold', o.id,
       jsonb_build_object('id', o.id, 'status', o.status, 'status_before_hold', o.status_before_hold,
                          'hold_reason', o.hold_reason, 'hold_note', o.hold_note)
FROM ordering.customer_order o WHERE o.status = 'ON_HOLD' AND o.hold_reason = 'DESIGN_MISMATCH'
UNION ALL
SELECT 'ordering.order_hold', h.id, to_jsonb(h) FROM ordering.order_hold h
WHERE h.reason = 'DESIGN_MISMATCH' AND h.resolved_at IS NULL
UNION ALL
SELECT 'identity.permission', p.id, to_jsonb(p) FROM identity.permission p
WHERE p.code LIKE 'fulfillment-design-reverification:%'
UNION ALL
SELECT 'identity.role_permission', rp.id, to_jsonb(rp) FROM identity.role_permission rp
WHERE rp.permission_id IN (SELECT id FROM identity.permission WHERE code LIKE 'fulfillment-design-reverification:%');

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), as they were. Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Held packages and orders released
-- ---------------------------------------------------------------------------------------------
UPDATE fulfillment.pack SET status = 'PACKING' WHERE status = 'ON_HOLD';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, occurred_at)
SELECT gen_random_uuid(), o.id, 'ON_HOLD', o.status_before_hold,
       'DESIGN_MISMATCH resolved: designs are no longer checked at packing (V20261108000500)', NOW()
FROM ordering.customer_order o
WHERE o.status = 'ON_HOLD' AND o.hold_reason = 'DESIGN_MISMATCH';

-- Back where it was held from (enforce_order_transition allows exactly that), its hold cleared in the same row.
UPDATE ordering.customer_order
SET status = status_before_hold, status_before_hold = NULL, hold_reason = NULL, hold_note = NULL
WHERE status = 'ON_HOLD' AND hold_reason = 'DESIGN_MISMATCH';

DELETE FROM ordering.order_hold WHERE reason = 'DESIGN_MISMATCH' AND resolved_at IS NULL;


-- ---------------------------------------------------------------------------------------------
-- 3. CHECKs and columns
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pack
    DROP CONSTRAINT ck_pack_status,
    DROP CONSTRAINT ck_pack_started,
    DROP COLUMN held_request_id,
    ADD CONSTRAINT ck_pack_status CHECK (status IN ('PENDING', 'PACKING', 'PACKED', 'CANCELLED')),
    ADD CONSTRAINT ck_pack_started CHECK (status NOT IN ('PACKING', 'PACKED') OR started_at IS NOT NULL);

ALTER TABLE ordering.customer_order
    DROP CONSTRAINT ck_order_hold_reason,
    ADD CONSTRAINT ck_order_hold_reason
        CHECK ((status = 'ON_HOLD') = (hold_reason IS NOT NULL)
               AND (hold_reason IS NULL
                    OR hold_reason IN ('CREDIT', 'PAYMENT', 'ADDRESS', 'INVENTORY', 'CUSTOMER', 'FRAUD', 'OTHER')));


-- ---------------------------------------------------------------------------------------------
-- 4. The verification record
-- ---------------------------------------------------------------------------------------------
-- Append-only, its rows archived above; dropped whole with its guard.
DROP TABLE fulfillment.design_verification;
DROP FUNCTION fulfillment.reject_design_verification_mutation();


-- ---------------------------------------------------------------------------------------------
-- 5. Permissions
-- ---------------------------------------------------------------------------------------------
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id AND p.code LIKE 'fulfillment-design-reverification:%'
    RETURNING rp.role_id
)
UPDATE identity.app_role r SET version = version + 1
WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE code LIKE 'fulfillment-design-reverification:%';
