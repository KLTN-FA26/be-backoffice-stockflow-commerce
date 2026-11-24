-- WF-05 audit round 2, returns H1 (decision Q-4; Luật Thương mại 2005 Điều 62, TT 91/2026/TT-BTC Điều 10 khoản 5c):
-- goods a customer sent back are the company's only once the return is agreed - its refund approved with units
-- refunded. Q-4: "Hàng đã về kho mà hoàn 0 đồng: không có thoả thuận trả hàng thì hàng vẫn là của khách ... Khu cách
-- ly là vị trí vật lý; không có bút toán nhập kho".
--
-- A return receipt still puts a sealed carton into quarantine when it is confirmed (03 §4.2 step 12, Q-11): the money is
-- decided on the goods that came back, after they came back. Until then the carton is the customer's as much as the
-- company's, so it waits; the refund decision then settles it, in the transaction that resolves the return:
--
--   PENDING        back on a return request, its refund not decided: IN_STOCK in quarantine, never released to sale
--                  nor written off (CartonQuarantineService.release, StockPackageContents.adjust).
--   COMPANY        the return refunded units: the company's goods, released or written off as any quarantined carton.
--   CUSTOMER_HELD  the return refunded nothing (refused, Q-4, or nothing to refund): the stock-in is reversed by one
--                  ADJUSTMENT line referencing the return (RETURN_REQUEST), the carton is CONSUMED again - out of the
--                  books - and stands in the quarantine area held for the customer. Sales record what the customer
--                  chose (custody_choice: SEND_BACK, at the customer's cost, or DESTROY_ON_BEHALF, with the customer's
--                  written consent); the warehouse manager carries it out from the custody list (TH-6).
--   CUSTOMER_SENT_BACK / CUSTOMER_DESTROYED  custody ended as chosen: who, when, the note (the hand-over or destruction
--                  record).
--
-- A carton of a carrier's return (RTO) never was the customer's: no return request, no ownership. Rows stocked before
-- this migration keep none either - they stay what they were.

ALTER TABLE inventory.stock_package
    ADD COLUMN return_request_id uuid,
    ADD COLUMN return_ownership varchar(24),
    ADD COLUMN custody_held_at timestamptz,
    ADD COLUMN custody_choice varchar(24),
    ADD COLUMN custody_ended_at timestamptz,
    ADD COLUMN custody_ended_by uuid,
    ADD COLUMN custody_note varchar(500);

ALTER TABLE inventory.stock_package
    ADD CONSTRAINT fk_stock_package_return_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_package_custody_ended_by FOREIGN KEY (custody_ended_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_stock_package_return_ownership CHECK (return_ownership IN
        ('PENDING', 'COMPANY', 'CUSTOMER_HELD', 'CUSTOMER_SENT_BACK', 'CUSTOMER_DESTROYED')),
    ADD CONSTRAINT ck_stock_package_return_ownership_of_return CHECK
        ((return_request_id IS NULL) = (return_ownership IS NULL)),
    ADD CONSTRAINT ck_stock_package_custody_held CHECK (
        (custody_held_at IS NOT NULL) = (COALESCE(return_ownership, '') LIKE 'CUSTOMER\_%')),
    ADD CONSTRAINT ck_stock_package_custody_choice CHECK (
        custody_choice IS NULL
        OR (custody_choice IN ('SEND_BACK', 'DESTROY_ON_BEHALF') AND return_ownership LIKE 'CUSTOMER\_%')),
    -- Custody ends only as the customer chose, by someone, with the record of it.
    ADD CONSTRAINT ck_stock_package_custody_ended CHECK (
        (COALESCE(return_ownership, '') IN ('CUSTOMER_SENT_BACK', 'CUSTOMER_DESTROYED'))
            = (custody_ended_at IS NOT NULL)
        AND (custody_ended_at IS NULL OR (custody_ended_by IS NOT NULL AND custody_note IS NOT NULL))
        AND (return_ownership IS DISTINCT FROM 'CUSTOMER_SENT_BACK' OR custody_choice = 'SEND_BACK')
        AND (return_ownership IS DISTINCT FROM 'CUSTOMER_DESTROYED' OR custody_choice = 'DESTROY_ON_BEHALF'));

-- Waiting for the refund decision, the carton is in stock (in quarantine); the customer's is out of the books. Checked
-- at commit, not as a CHECK: the carton's status (JPA) and its ownership (the reversal) change in two statements of
-- one transaction, and either order passes through a state the rule refuses.
CREATE FUNCTION inventory.check_stock_package_return_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    now_row inventory.stock_package%ROWTYPE;
BEGIN
    SELECT * INTO now_row FROM inventory.stock_package WHERE id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF (now_row.return_ownership = 'PENDING' AND now_row.status <> 'IN_STOCK')
       OR (now_row.return_ownership LIKE 'CUSTOMER\_%' AND now_row.status <> 'CONSUMED') THEN
        RAISE EXCEPTION 'carton % is % but %: a carton waiting for its return''s refund is in stock, the customer''s '
            'is out of the books (WF-05 Q-4)', now_row.lpn, now_row.status, now_row.return_ownership
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_stock_package_return_ownership AFTER INSERT OR UPDATE OF status, return_ownership
    ON inventory.stock_package DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.return_ownership IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_package_return_ownership();

CREATE INDEX ix_stock_package_return_request ON inventory.stock_package (return_request_id)
    WHERE return_request_id IS NOT NULL;
CREATE INDEX ix_stock_package_customer_held ON inventory.stock_package (custody_held_at)
    WHERE return_ownership = 'CUSTOMER_HELD';

COMMENT ON COLUMN inventory.stock_package.return_request_id IS 'The return request the carton last came back on (WF-05 H1); null for a carton never returned by a customer, or returned by the carrier (RTO).';
COMMENT ON COLUMN inventory.stock_package.return_ownership IS 'Whose goods a carton back on a return request is (WF-05 Q-4): PENDING until the refund is decided, COMPANY once units were refunded, CUSTOMER_HELD when nothing was (the stock-in reversed, held for the customer), CUSTOMER_SENT_BACK / CUSTOMER_DESTROYED once custody ended as the customer chose.';
COMMENT ON COLUMN inventory.stock_package.custody_choice IS 'What the customer chose for goods held for them (WF-05 Q-4), as Sales recorded it on the return: SEND_BACK (at the customer''s cost) or DESTROY_ON_BEHALF (with their written consent).';
COMMENT ON COLUMN inventory.stock_package.custody_note IS 'The record of the custody''s end: the hand-over note or the destruction record (biên bản), by the warehouse manager.';

-- The stock row of a carton waiting for its refund decision, or held for the customer, never leaves quarantine: a
-- release would put the customer's goods on sale (H1). CartonQuarantineService.release refuses first.
CREATE FUNCTION inventory.check_stock_item_returned_carton() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    ownership VARCHAR(24);
BEGIN
    SELECT p.return_ownership INTO ownership FROM inventory.stock_package p WHERE p.id = NEW.stock_package_id;
    IF ownership = 'PENDING' OR ownership LIKE 'CUSTOMER\_%' THEN
        RAISE EXCEPTION 'carton % came back on a return whose refund is % : its goods stay in quarantine (WF-05 Q-4)',
            NEW.stock_package_id, ownership USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_item_returned_carton BEFORE INSERT OR UPDATE OF status, on_hand, stock_package_id
    ON inventory.stock_item
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL AND NEW.on_hand > 0 AND NEW.status <> 'QUARANTINE')
    EXECUTE FUNCTION inventory.check_stock_item_returned_carton();

-- The ledger line that reverses a refused return's stock-in: an ADJUSTMENT out of the quarantine area, of the whole
-- carton, naming the return request. It is the one way a quarantined unit leaves with that reference.
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_reference;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN
    ('GOODS_RECEIPT_LINE', 'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK', 'TRANSFER_ORDER_LINE',
     'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER', 'PRODUCTION_ORDER', 'EXPIRY_SWEEP',
     'DAMAGE_REPORT', 'STOCK_PACKAGE', 'GOODS_RECEIPT', 'RETURN_REQUEST'));

ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_quarantine_exit;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_quarantine_exit CHECK (
    from_status IS DISTINCT FROM 'QUARANTINE'
    OR (to_location_code IS NOT NULL AND to_status = 'QUARANTINE')
    OR reference_type = 'STOCK_ADJUSTMENT'
    OR (reference_type = 'PUTAWAY_TASK' AND to_status = 'AVAILABLE')
    OR reference_type IN ('QC_INSPECTION', 'RETURN_REQUEST_LINE', 'RETURN_REQUEST'));

ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_return_refused CHECK (
    reference_type <> 'RETURN_REQUEST'
    OR (movement_type = 'ADJUSTMENT' AND from_location_code IS NOT NULL AND to_location_code IS NULL
        AND from_stock_package_id IS NOT NULL AND from_status = 'QUARANTINE'));

COMMENT ON CONSTRAINT ck_stock_movement_return_refused ON inventory.stock_movement IS 'WF-05 Q-4: a return refunded nothing, so the stock-in of its carton is reversed - the whole carton out of quarantine, naming the return request. The goods are the customer''s, held for them.';
