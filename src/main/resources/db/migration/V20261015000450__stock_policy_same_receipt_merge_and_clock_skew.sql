-- =============================================================================
-- inventory.enforce_sku_policy, two corrections that keep every rule it enforces (lane A, 2026-10-12).
--
-- 1. FIFO and moves. Raising an existing FIFO layer's on_hand was refused outright ("FIFO replenishment
--    requires a new receipt layer"). That is right for replenishment - new goods must not take an old
--    layer's age - but it also refused moving units of a receipt into a layer of the SAME receipt at another
--    location: a second move to the same bin, a second hold issued to the print shop, finished goods handed
--    to packing in two parts. Those units carry the same receipt time, lot, expiry and serial (the identity
--    check above it still refuses anything else), so no age is lost. The application now names the row it
--    merges into in a transaction-local setting, stockflow.same_receipt_merge, set immediately before that
--    row's UPDATE and cleared after it (StockItemRepositoryAdapter.saveMerged). Every other increase - a FOUND
--    adjustment, a count, anything not a merge - is refused as before.
--
-- 2. Receipt time. received_at is stamped by the application's clock and compared with the database's;
--    measured in the test environment, the database container runs ~430 ms behind the JVM, so a receipt
--    stamped "now" was "in the future" for the database (SupplierProcurementIntegrationTest). Hosts in
--    production drift the same way within NTP limits. A receipt is refused when it is more than one minute in
--    the future: post-dated receipts (a typo, a wrong day) are still refused; clock skew is not mistaken for
--    one.
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
        -- Replenishment must be a new layer; otherwise FIFO loses the age of incoming units. Units of this
        -- very receipt moved in from another location (identity unchanged, checked just above) are not
        -- replenishment: the application names the row it merges into for that one statement.
        IF p.removal_strategy='FIFO'
           AND current_setting('stockflow.same_receipt_merge', true) IS DISTINCT FROM NEW.id::text THEN
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
    IF NEW.received_at > clock_timestamp() + interval '1 minute' OR NEW.expiry_date < receipt_day
       OR NEW.expiry_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       OR (p.max_shelf_life_days IS NOT NULL AND (NEW.received_at IS NULL OR NEW.expiry_date > receipt_day + p.max_shelf_life_days)) THEN
        RAISE EXCEPTION 'Invalid receipt/expiry date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $function$;

