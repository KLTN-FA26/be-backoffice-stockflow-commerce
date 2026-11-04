-- =============================================================================
-- inventory.enforce_sku_policy compared a stock layer's received_at with CURRENT_TIMESTAMP, which in
-- PostgreSQL is the time the TRANSACTION started. A receipt stamped with the time it was confirmed,
-- inside that transaction, was therefore always "in the future" and refused ("Invalid receipt/expiry
-- date"). Found when goods receipts (SCRUM-435) started writing received_at.
--
-- The future check now uses clock_timestamp(), the actual time of the statement. The receipt-day and
-- expiry checks keep the transaction's date: they compare days, and one transaction does not span
-- midnight in a way that matters here. Nothing else in the function changes.
-- =============================================================================

CREATE OR REPLACE FUNCTION inventory.enforce_sku_policy()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE p inventory.inventory_items%ROWTYPE; receipt_day date;
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
    SELECT * INTO p FROM inventory.inventory_items WHERE sku=NEW.sku;
    IF NOT FOUND OR NOT p.policy_configured THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.received_at IS DISTINCT FROM OLD.received_at
           OR NEW.lot_number IS DISTINCT FROM OLD.lot_number OR NEW.serial_number IS DISTINCT FROM OLD.serial_number
           OR NEW.expiry_date IS DISTINCT FROM OLD.expiry_date THEN
            RAISE EXCEPTION 'Receipt identity is immutable; reconcile via a new stock layer' USING ERRCODE='23514';
        END IF;
        IF NEW.on_hand <= OLD.on_hand THEN RETURN NEW; END IF;
        -- Replenishment must be a new layer; otherwise FIFO loses the age of incoming units.
        IF p.removal_strategy='FIFO' THEN
            RAISE EXCEPTION 'FIFO replenishment requires a new receipt layer' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.on_hand = 0 THEN RETURN NEW; END IF;
    IF (p.lot_tracked AND nullif(trim(NEW.lot_number),'') IS NULL)
       OR (NOT p.lot_tracked AND NEW.lot_number IS NOT NULL)
       OR (p.serial_tracked AND (nullif(trim(NEW.serial_number),'') IS NULL OR NEW.on_hand > 1))
       OR (NOT p.serial_tracked AND NEW.serial_number IS NOT NULL)
       OR (p.expiry_tracked AND NEW.expiry_date IS NULL)
       OR (NOT p.expiry_tracked AND NEW.expiry_date IS NOT NULL)
       OR (p.removal_strategy='FIFO' AND NEW.received_at IS NULL) THEN
        RAISE EXCEPTION 'Stock does not satisfy configured SKU policy' USING ERRCODE='23514';
    END IF;
    receipt_day := COALESCE((NEW.received_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date);
    IF NEW.received_at > clock_timestamp() OR NEW.expiry_date < receipt_day
       OR NEW.expiry_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       OR (p.max_shelf_life_days IS NOT NULL AND (NEW.received_at IS NULL OR NEW.expiry_date > receipt_day + p.max_shelf_life_days)) THEN
        RAISE EXCEPTION 'Invalid receipt/expiry date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $function$;

