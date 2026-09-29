-- Product is the configuration source. Inventory holds the synchronously applied execution policy.
ALTER TABLE product.sku
    ADD COLUMN reorder_point integer,
    ADD COLUMN safety_stock integer,
    ADD COLUMN removal_strategy varchar(16) NOT NULL DEFAULT 'FEFO',
    ADD COLUMN serial_tracked boolean NOT NULL DEFAULT false,
    ADD COLUMN expiry_tracked boolean NOT NULL DEFAULT false,
    ADD COLUMN max_shelf_life_days integer,
    ADD CONSTRAINT ck_sku_thresholds CHECK
        ((reorder_point IS NULL OR reorder_point >= 0) AND (safety_stock IS NULL OR safety_stock >= 0)
         AND (reorder_point IS NULL OR safety_stock IS NULL OR safety_stock <= reorder_point)),
    ADD CONSTRAINT ck_sku_tracking CHECK (NOT (lot_tracked AND serial_tracked)),
    ADD CONSTRAINT ck_sku_removal CHECK (removal_strategy IN ('FIFO','FEFO')),
    ADD CONSTRAINT ck_sku_expiry CHECK
        ((NOT expiry_tracked OR ((lot_tracked OR serial_tracked) AND removal_strategy='FEFO'))
         AND (max_shelf_life_days IS NULL OR (expiry_tracked AND max_shelf_life_days BETWEEN 1 AND 36500)));

CREATE TABLE inventory.sku_policy (
    sku varchar(64) PRIMARY KEY,
    reorder_point integer,
    safety_stock integer,
    removal_strategy varchar(16) NOT NULL CHECK (removal_strategy IN ('FIFO','FEFO')),
    tracking_mode varchar(16) NOT NULL CHECK (tracking_mode IN ('NONE','LOT','SERIAL')),
    expiry_tracked boolean NOT NULL,
    max_shelf_life_days integer,
    CONSTRAINT ck_policy_thresholds CHECK
        ((reorder_point IS NULL OR reorder_point >= 0) AND (safety_stock IS NULL OR safety_stock >= 0)
         AND (reorder_point IS NULL OR safety_stock IS NULL OR safety_stock <= reorder_point)),
    CONSTRAINT ck_policy_expiry CHECK
        ((NOT expiry_tracked OR (tracking_mode <> 'NONE' AND removal_strategy='FEFO'))
         AND (max_shelf_life_days IS NULL OR (expiry_tracked AND max_shelf_life_days BETWEEN 1 AND 36500)))
);

-- Unknown legacy receipt times remain unknown. Never manufacture a receipt timestamp from created_at.
ALTER TABLE inventory.stock_item ADD COLUMN received_at timestamptz, ADD COLUMN serial_number varchar(100);
-- One stock layer per receipt. Legacy null-time rows keep their original uniqueness.
DROP INDEX inventory.uk_stock_item_sku_location_lot;
CREATE UNIQUE INDEX uk_stock_item_sku_location_lot
    ON inventory.stock_item(sku, location_code, COALESCE(lot_number,''), COALESCE(serial_number,''), received_at)
    NULLS NOT DISTINCT;
CREATE UNIQUE INDEX uk_stock_item_live_serial ON inventory.stock_item(sku, serial_number)
    WHERE serial_number IS NOT NULL AND on_hand > 0;
ALTER TABLE inventory.stock_item ADD CONSTRAINT ck_stock_serial_quantity
    CHECK (serial_number IS NULL OR (length(trim(serial_number)) > 0 AND on_hand <= 1));

-- Serializes stock ingress and policy changes even when no stock row existed at the initial check.
CREATE FUNCTION inventory.enforce_sku_policy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p inventory.sku_policy%ROWTYPE; receipt_day date;
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
    IF NEW.received_at > CURRENT_TIMESTAMP OR NEW.expiry_date < receipt_day
       OR NEW.expiry_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       OR (p.max_shelf_life_days IS NOT NULL AND NEW.expiry_date > receipt_day + p.max_shelf_life_days) THEN
        RAISE EXCEPTION 'Invalid receipt/expiry date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_stock_sku_policy BEFORE INSERT OR UPDATE ON inventory.stock_item
    FOR EACH ROW EXECUTE FUNCTION inventory.enforce_sku_policy();
