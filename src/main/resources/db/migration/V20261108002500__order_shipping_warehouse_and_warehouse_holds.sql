-- 4B-7 (kltn-docs 17 §4.2 steps 2-3, BR-21, BR-22; 20 §5.6, BR-01, BR-17; team plan QD-4): an order of a contract ships
-- from exactly one warehouse, which Sales chooses once the order is confirmed. Its stock is held there by SKU, in whole
-- cartons, with no deadline; the cartons and bins are chosen at release (QD-4: 20 §5.6 over 17 §4.2 step 3).

-- The order's shipping warehouse ------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order
    ADD COLUMN shipping_warehouse_id        UUID,
    ADD COLUMN shipping_warehouse_chosen_at TIMESTAMPTZ,
    ADD COLUMN shipping_warehouse_chosen_by UUID,
    ADD CONSTRAINT fk_order_shipping_warehouse FOREIGN KEY (shipping_warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_order_shipping_warehouse_chosen_by FOREIGN KEY (shipping_warehouse_chosen_by)
        REFERENCES identity.app_user (id),
    -- Chosen by someone, at a time, for an order of a signed contract (17 §4.2 step 2).
    ADD CONSTRAINT ck_order_shipping_warehouse CHECK (
        (shipping_warehouse_id IS NULL) = (shipping_warehouse_chosen_at IS NULL)
        AND (shipping_warehouse_id IS NULL) = (shipping_warehouse_chosen_by IS NULL)
        AND (shipping_warehouse_id IS NULL OR contract_signed_at IS NOT NULL)),
    -- 17 BR-22: released from the warehouse it ships from - and an order of a contract is released from one only.
    ADD CONSTRAINT ck_order_released_from_shipping_warehouse CHECK (
        (shipping_warehouse_id IS NULL OR warehouse_id IS NULL OR warehouse_id = shipping_warehouse_id)
        AND (contract_signed_at IS NULL OR released_at IS NULL OR shipping_warehouse_id IS NOT NULL));

CREATE INDEX ix_customer_order_shipping_warehouse ON ordering.customer_order (shipping_warehouse_id, status)
    WHERE shipping_warehouse_id IS NOT NULL;

COMMENT ON COLUMN ordering.customer_order.shipping_warehouse_id IS
    'The one warehouse an order of a contract ships from (17 BR-22): Sales chooses it once the order is CONFIRMED; it '
    'holds its stock there only, and changes only while nothing is held.';

-- A SKU reservation held at one warehouse -----------------------------------------------------------------------------
ALTER TABLE inventory.stock_reservation
    ADD COLUMN warehouse_code VARCHAR(64),
    ADD CONSTRAINT fk_stock_reservation_warehouse FOREIGN KEY (warehouse_code)
        REFERENCES warehouse.warehouse (prefix) ON DELETE RESTRICT,
    -- 20 BR-17: a SKU reservation of the order's shipping warehouse; an allocation sits on a stock item of it already.
    ADD CONSTRAINT ck_stock_reservation_warehouse CHECK (warehouse_code IS NULL OR stock_item_id IS NULL);

CREATE INDEX ix_stock_reservation_warehouse_held ON inventory.stock_reservation (sku, warehouse_code)
    WHERE stock_item_id IS NULL AND status = 'HELD' AND warehouse_code IS NOT NULL;

COMMENT ON COLUMN inventory.stock_reservation.warehouse_code IS
    'The warehouse a SKU reservation is held at (20 §5.6, BR-17): an order of a contract holds its stock at its shipping '
    'warehouse only. Null for a checkout hold, promised against the whole system.';

-- Where it is held never changes; its allocations are on stock of that warehouse.
CREATE FUNCTION inventory.check_stock_reservation_warehouse()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent_warehouse VARCHAR;
    item_location    VARCHAR;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.warehouse_code IS DISTINCT FROM OLD.warehouse_code THEN
        RAISE EXCEPTION 'reservation %: the warehouse it is held at does not change (20 BR-17)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.allocated_from_id IS NOT NULL AND NEW.stock_item_id IS NOT NULL THEN
        SELECT r.warehouse_code INTO parent_warehouse FROM inventory.stock_reservation r
         WHERE r.id = NEW.allocated_from_id;
        IF parent_warehouse IS NOT NULL THEN
            SELECT s.location_code INTO item_location FROM inventory.stock_item s WHERE s.id = NEW.stock_item_id;
            IF split_part(item_location, '-', 1) <> parent_warehouse THEN
                RAISE EXCEPTION 'allocation %: reservation % is held at %, the stock is at % (20 BR-17)', NEW.id,
                    NEW.allocated_from_id, parent_warehouse, item_location USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_reservation_warehouse
    BEFORE INSERT OR UPDATE OF warehouse_code, allocated_from_id, stock_item_id ON inventory.stock_reservation
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_reservation_warehouse();

-- The no-oversell safety net, per SKU as before - and, for a hold at a warehouse, per SKU at that warehouse: what is
-- held there never exceeds its unheld AVAILABLE stock (20 BR-01, BR-17). Looser than the application's rule (expired
-- lots and frozen counts are not excluded), so it never refuses what the application allowed.
CREATE OR REPLACE FUNCTION inventory.check_sku_reservation_covered()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    free BIGINT;
    held BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT COALESCE(SUM(on_hand - reserved), 0) INTO free
      FROM inventory.stock_item WHERE sku = NEW.sku AND status = 'AVAILABLE';
    SELECT COALESCE(SUM(quantity), 0) INTO held
      FROM inventory.stock_reservation WHERE sku = NEW.sku AND stock_item_id IS NULL AND status = 'HELD';
    IF held > free THEN
        RAISE EXCEPTION 'SKU reservations of % (%) exceed its unheld available stock (%)', NEW.sku, held, free
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.warehouse_code IS NOT NULL THEN
        SELECT COALESCE(SUM(on_hand - reserved), 0) INTO free
          FROM inventory.stock_item
         WHERE sku = NEW.sku AND status = 'AVAILABLE' AND split_part(location_code, '-', 1) = NEW.warehouse_code;
        SELECT COALESCE(SUM(quantity), 0) INTO held
          FROM inventory.stock_reservation
         WHERE sku = NEW.sku AND stock_item_id IS NULL AND status = 'HELD' AND warehouse_code = NEW.warehouse_code;
        IF held > free THEN
            RAISE EXCEPTION 'SKU reservations of % at % (%) exceed its unheld available stock there (%) (20 BR-17)',
                NEW.sku, NEW.warehouse_code, held, free USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END
$$;

-- Who chooses the shipping warehouse (Sales) and who follows the stock held there --------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'sales-order-shipping-warehouses:' || a.action, 'sales-order-shipping-warehouses', a.action,
       0, NOW(), 'flyway'
  FROM unnest(ARRAY['READ', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, actions) AS (
    VALUES
        -- 17 §4.2 step 2: Sales chooses it.
        ('SALES_STAFF',       'READ,UPDATE'),
        ('SALES_MANAGER',     'READ'),
        -- They follow what is held before releasing and planning production.
        ('ORDER_COORDINATOR', 'READ'),
        ('WAREHOUSE_MANAGER', 'READ'),
        ('SYSTEM_ADMIN',      'READ,UPDATE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = 'sales-order-shipping-warehouses'
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
