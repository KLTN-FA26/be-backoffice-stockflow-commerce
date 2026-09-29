-- A positive correction is not a receipt: only the approved ledger in this transaction permits it.
CREATE OR REPLACE FUNCTION inventory.enforce_sku_policy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p inventory.sku_policy%ROWTYPE; receipt_day date; approved_adjustment boolean := false;
BEGIN
    -- Holds/consumption already lock individual rows in id order. Taking a SKU-wide lock here
    -- would invert that order when two reservations span different rows of the same SKU.
    IF TG_OP='UPDATE' AND NEW.sku IS NOT DISTINCT FROM OLD.sku
       AND NEW.received_at IS NOT DISTINCT FROM OLD.received_at
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.serial_number IS NOT DISTINCT FROM OLD.serial_number
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date
       AND NEW.on_hand <= OLD.on_hand THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT * INTO p FROM inventory.sku_policy WHERE sku=NEW.sku;
    IF NOT FOUND THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        SELECT EXISTS (
            SELECT 1 FROM inventory.stock_adjustment a JOIN inventory.cycle_count c ON c.id=a.count_id
            WHERE a.stock_id=NEW.id AND a.before_qty=OLD.on_hand AND a.after_qty=NEW.on_hand
              AND a.posting_tx=txid_current() AND c.status='APPROVED'
              AND a.approved_by=c.approved_by AND a.counted_by=c.assigned_to
        ) INTO approved_adjustment;
        IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.received_at IS DISTINCT FROM OLD.received_at
           OR NEW.lot_number IS DISTINCT FROM OLD.lot_number OR NEW.serial_number IS DISTINCT FROM OLD.serial_number
           OR NEW.expiry_date IS DISTINCT FROM OLD.expiry_date THEN
            RAISE EXCEPTION 'Receipt identity is immutable; reconcile via a new stock layer' USING ERRCODE='23514';
        END IF;
        IF NEW.on_hand <= OLD.on_hand THEN RETURN NEW; END IF;
        -- Replenishment must be a new layer; otherwise FIFO loses the age of incoming units.
        IF p.removal_strategy='FIFO' AND NOT approved_adjustment THEN
            RAISE EXCEPTION 'FIFO replenishment requires a new receipt layer' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.on_hand = 0 THEN RETURN NEW; END IF;
    IF (p.tracking_mode='NONE' AND (NEW.lot_number IS NOT NULL OR NEW.serial_number IS NOT NULL))
       OR (p.tracking_mode='LOT' AND (nullif(trim(NEW.lot_number),'') IS NULL OR NEW.serial_number IS NOT NULL))
       OR (p.tracking_mode='SERIAL' AND (nullif(trim(NEW.serial_number),'') IS NULL OR NEW.on_hand > 1))
       OR (p.expiry_tracked AND NEW.expiry_date IS NULL)
       OR (NOT p.expiry_tracked AND NEW.expiry_date IS NOT NULL)
       OR (p.removal_strategy='FIFO' AND NEW.received_at IS NULL) THEN
        RAISE EXCEPTION 'Stock does not satisfy configured SKU policy' USING ERRCODE='23514';
    END IF;
    receipt_day := COALESCE((NEW.received_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date);
    IF NOT approved_adjustment AND (NEW.received_at > CURRENT_TIMESTAMP OR NEW.expiry_date < receipt_day
       OR NEW.expiry_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       OR (p.max_shelf_life_days IS NOT NULL AND NEW.expiry_date > receipt_day + p.max_shelf_life_days)) THEN
        RAISE EXCEPTION 'Invalid receipt/expiry date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
