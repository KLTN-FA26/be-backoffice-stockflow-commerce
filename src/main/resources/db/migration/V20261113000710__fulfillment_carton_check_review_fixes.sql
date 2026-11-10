-- =============================================================================
-- The carton check station after its review (team plan 4C-6; reports/4C-6-review.md L3, L6; kltn-docs 08 BR-04, edge
-- case "a wrong address is caught before the first label"; 09 §4.6).
--
-- What this states in the database (each rule is first in the code named after it):
--   1. fulfillment.package(lpn): an index for every LPN that is not null. A loader or the unload desk scans a carton's
--      LPN (PackageJpaRepository.findLabeledByLpn) and fk_package_lpn is checked on every carton row written; the
--      partial unique index uk_package_lpn_checking covers only the cartons being checked, so both read the whole
--      table without this one.
--   2. ordering.customer_order: the first label is not recorded while the order is ON_HOLD. The station checks the
--      order's status before it takes the order's row lock; a hold put on in between (an ADDRESS hold above all) must
--      stop the label that would lock the address it was put on to correct. Order.recordFirstLabel.
--
-- Its own file, numbered after V20261113000700 which creates fulfillment.package.lpn: an earlier number would run
-- before the column exists on a fresh database. Changes no data.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Cartons by LPN
-- ---------------------------------------------------------------------------------------------
CREATE INDEX ix_package_lpn ON fulfillment.package (lpn) WHERE lpn IS NOT NULL;


-- ---------------------------------------------------------------------------------------------
-- 2. No first label on an order on hold
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ordering.guard_order_label_lock()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.first_labeled_at IS NULL THEN
        IF NEW.first_labeled_at IS NOT NULL AND NEW.status = 'ON_HOLD' THEN
            RAISE EXCEPTION 'tg_order_label_lock: order % is on hold; its first carton is labelled once the hold is '
                'resolved (kltn-docs 08 BR-04)', OLD.order_number
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_label_lock';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.first_labeled_at IS DISTINCT FROM OLD.first_labeled_at THEN
        RAISE EXCEPTION 'tg_order_label_lock: order % was first labelled at %; that does not change',
            OLD.order_number, OLD.first_labeled_at
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_label_lock';
    END IF;
    IF ROW(NEW.shipping_recipient_name, NEW.shipping_phone, NEW.shipping_line1, NEW.shipping_line2,
           NEW.shipping_ward_code, NEW.shipping_ward_name, NEW.shipping_province_code, NEW.shipping_province_name,
           NEW.shipping_country_code, NEW.shipping_postal_code)
       IS DISTINCT FROM
       ROW(OLD.shipping_recipient_name, OLD.shipping_phone, OLD.shipping_line1, OLD.shipping_line2,
           OLD.shipping_ward_code, OLD.shipping_ward_name, OLD.shipping_province_code, OLD.shipping_province_name,
           OLD.shipping_country_code, OLD.shipping_postal_code) THEN
        RAISE EXCEPTION 'tg_order_label_lock: order % has a labelled carton; its delivery address does not change '
            '(kltn-docs 17 BR-16)', OLD.order_number
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_label_lock';
    END IF;
    RETURN NEW;
END
$$;
