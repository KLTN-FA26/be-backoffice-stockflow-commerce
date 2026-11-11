-- =============================================================================
-- Quote and order lines name the customer's own SKU and count cartons (kltn-docs 14 BR-04, BR-07, BR-08; 01 BR-10,
-- BR-11; 13 BR-02, BR-05; team plan 4B-2, QD-16).
--
--   * ordering.quote_line: a line Sales writes now names the customer's own SKU (sku), the carton unit it is sold in
--     (uom_conversion_id), how many cartons (cartons) and the pieces per carton fixed on the line (units_per_carton:
--     the unit's factor when the line was written, 14 BR-08 - a later carton spec does not move it). The price stays
--     per piece; the line's total is cartons x units_per_carton x price. What the customer accepted is
--     accepted_cartons at accepted_unit_price.
--     A line drafted before (a blank, a design and units: blank_sku, design_snapshot_id, quantity) is kept as it
--     was, read only (QD-16). ck_quote_line_shape lets a row be one or the other, never a mix, and one SKU is on a
--     quote once (14 BR-22: one line per SKU).
--   * ordering.quote_price_tier.min_quantity counts the line's measure: cartons on a carton line, units on a line
--     drafted before.
--   * ordering.order_line: an order line placed from a carton line keeps the unit, the cartons and the pieces per
--     carton. quantity stays in pieces - what is reserved, picked and invoiced - and is cartons x units_per_carton.
--   * A carton unit a quote or order line counts in keeps its factor, item and name (01 BR-10): the lines' trigger
--     takes the unit's advisory lock shared before it reads the unit and checks the unit is the SKU's and the
--     factor is the one fixed on the line, as purchase lines do (V20261020000400, V20261020000600);
--     inventory.unit_in_use names both tables, and inventory.item_in_use names quote_line.sku. A new quote line is
--     never written in a retired unit; an order line copies the unit its accepted quote line fixed, retired since or
--     not (14 BR-08: a later carton spec does not move a quote already made).
--   * The SKU of a carton line is the customer's own (14 BR-04): the quote's or the order's customer owns it. And a
--     quote's lines are all carton lines or all lines drafted before, checked when the transaction commits - a draft
--     revised onto the customer's SKUs replaces its lines in one go.
--   * catalog.customer_price: a price set from now on is for one of the customer's own SKUs (13 BR-02, BR-05), its
--     tiers counted in cartons. Prices set before on stock variants stay, counted in units, for checkout until it is
--     removed (team plan 5-2); tg_customer_price_own_sku refuses a new one.
-- Nothing is back-filled: no row written before says how many cartons it meant.
-- =============================================================================

-- Quote lines -----------------------------------------------------------------------------------------
ALTER TABLE ordering.quote_line
    ALTER COLUMN blank_sku DROP NOT NULL,
    ALTER COLUMN design_snapshot_id DROP NOT NULL,
    ALTER COLUMN quantity DROP NOT NULL,
    ADD COLUMN sku               VARCHAR(64),
    ADD COLUMN uom_conversion_id UUID,
    ADD COLUMN cartons           INTEGER,
    ADD COLUMN units_per_carton  INTEGER,
    ADD COLUMN accepted_cartons  INTEGER,
    DROP CONSTRAINT ck_quote_line_accepted,
    DROP CONSTRAINT ck_quote_line_accepted_values;

ALTER TABLE ordering.quote_line
    ADD CONSTRAINT fk_quote_line_customer_sku FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_quote_line_unit FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_quote_line_shape CHECK (
        (sku IS NULL AND uom_conversion_id IS NULL AND cartons IS NULL AND units_per_carton IS NULL
            AND accepted_cartons IS NULL
            AND blank_sku IS NOT NULL AND design_snapshot_id IS NOT NULL AND quantity IS NOT NULL)
        OR (sku IS NOT NULL AND uom_conversion_id IS NOT NULL AND cartons IS NOT NULL AND units_per_carton IS NOT NULL
            AND blank_sku IS NULL AND design_snapshot_id IS NULL AND quantity IS NULL AND print_spec IS NULL
            AND accepted_quantity IS NULL)),
    ADD CONSTRAINT ck_quote_line_cartons CHECK (cartons IS NULL OR (cartons > 0
        AND cartons::BIGINT * units_per_carton <= 10000000)),
    ADD CONSTRAINT ck_quote_line_units_per_carton CHECK (units_per_carton IS NULL OR units_per_carton > 1),
    ADD CONSTRAINT ck_quote_line_accepted CHECK (
        (COALESCE(accepted_quantity, accepted_cartons) IS NULL) = (accepted_unit_price IS NULL)),
    ADD CONSTRAINT ck_quote_line_accepted_values CHECK (
        (accepted_quantity IS NULL OR accepted_quantity > 0)
        AND (accepted_cartons IS NULL OR (accepted_cartons > 0
            AND accepted_cartons::BIGINT * units_per_carton <= 10000000))
        AND (accepted_unit_price IS NULL OR (accepted_unit_price > 0
            AND accepted_unit_price = TRUNC(accepted_unit_price))));

CREATE UNIQUE INDEX uk_quote_line_customer_sku ON ordering.quote_line (quote_id, sku) WHERE sku IS NOT NULL;
CREATE INDEX ix_quote_line_sku ON ordering.quote_line (sku) WHERE sku IS NOT NULL;
CREATE INDEX ix_quote_line_unit ON ordering.quote_line (uom_conversion_id) WHERE uom_conversion_id IS NOT NULL;

COMMENT ON COLUMN ordering.quote_line.sku IS
    '4B-2: the customer''s own SKU the line quotes (14 BR-04); null on a line drafted before, which names blank_sku and design_snapshot_id.';
COMMENT ON COLUMN ordering.quote_line.cartons IS
    '4B-2: cartons Sales proposes (14 BR-08); the customer accepts accepted_cartons, at or above the line''s MOQ in cartons.';
COMMENT ON COLUMN ordering.quote_line.units_per_carton IS
    '4B-2: pieces per carton, the carton unit''s factor when the line was written, fixed on the line (14 BR-08, 01 BR-10).';
COMMENT ON COLUMN ordering.quote_price_tier.min_quantity IS
    'Where the tier starts: cartons on a carton line (14 BR-07), units on a line drafted before 4B-2. The price is per piece.';

-- Order lines -----------------------------------------------------------------------------------------
ALTER TABLE ordering.order_line
    ADD COLUMN uom_conversion_id UUID,
    ADD COLUMN cartons           INTEGER,
    ADD COLUMN units_per_carton  INTEGER,
    ADD CONSTRAINT fk_order_line_unit FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_order_line_cartons CHECK (
        (uom_conversion_id IS NULL AND cartons IS NULL AND units_per_carton IS NULL)
        OR (uom_conversion_id IS NOT NULL AND cartons > 0 AND units_per_carton > 1
            AND quantity::BIGINT = cartons::BIGINT * units_per_carton));

CREATE INDEX ix_order_line_unit ON ordering.order_line (uom_conversion_id) WHERE uom_conversion_id IS NOT NULL;

-- A line of the customer's own SKU is printed goods too: it copies the quote's requested delivery date (14 §4.2 step 7).
ALTER TABLE ordering.order_line
    DROP CONSTRAINT ck_order_line_requested_delivery,
    ADD CONSTRAINT ck_order_line_requested_delivery CHECK (
        requested_delivery_date IS NULL OR design_snapshot_id IS NOT NULL OR cartons IS NOT NULL);

COMMENT ON COLUMN ordering.order_line.cartons IS
    '4B-2: cartons ordered, from the accepted quote line; quantity = cartons x units_per_carton pieces. Null on a line placed before, or bought at checkout.';

-- The line's carton unit: the SKU's, at the factor fixed on the line ------------------------------------------
-- The unit's advisory lock is taken SHARED before the unit is read, so a change of its factor, item or name either
-- waits for this line to commit or is refused (V20261020000600).
CREATE FUNCTION ordering.check_line_carton_unit()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    unit      inventory.uom_conversions%ROWTYPE;
    item_sku  VARCHAR(64);
    new_unit  BOOLEAN;
    owner     UUID;
    customer  UUID;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    SELECT i.sku INTO item_sku FROM inventory.inventory_items i WHERE i.id = unit.inventory_item_id;
    IF item_sku IS DISTINCT FROM NEW.sku THEN
        RAISE EXCEPTION '% line: unit % is not a unit of %', TG_TABLE_NAME, unit.unit_of_measure, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.units_per_carton <> unit.factor THEN
        RAISE EXCEPTION '% line: % holds % pieces, not %', TG_TABLE_NAME, unit.unit_of_measure, unit.factor,
            NEW.units_per_carton USING ERRCODE = 'check_violation';
    END IF;
    -- 14 BR-04: the customer's own SKU.
    SELECT v.owner_customer_id INTO owner FROM product.variants v WHERE v.sku = NEW.sku;
    IF TG_TABLE_NAME = 'quote_line' THEN
        SELECT q.customer_id INTO customer FROM ordering.quote q WHERE q.id = NEW.quote_id;
    ELSE
        SELECT o.customer_id INTO customer FROM ordering.customer_order o WHERE o.id = NEW.order_id;
    END IF;
    IF owner IS DISTINCT FROM customer THEN
        RAISE EXCEPTION '% line: % is not a SKU of customer % (14 BR-04)', TG_TABLE_NAME, NEW.sku, customer
            USING ERRCODE = 'check_violation';
    END IF;
    -- A new quote line takes an active unit; an order line copies the unit of its accepted quote line.
    IF TG_TABLE_NAME = 'order_line' THEN
        new_unit := FALSE;
    ELSIF TG_OP = 'INSERT' THEN
        new_unit := TRUE;
    ELSE
        new_unit := NEW.uom_conversion_id IS DISTINCT FROM OLD.uom_conversion_id;
    END IF;
    IF new_unit AND NOT unit.active THEN
        RAISE EXCEPTION '% line: unit % of % is retired', TG_TABLE_NAME, unit.unit_of_measure, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_quote_line_carton_unit
    BEFORE INSERT OR UPDATE OF sku, uom_conversion_id, units_per_carton
    ON ordering.quote_line
    FOR EACH ROW WHEN (NEW.uom_conversion_id IS NOT NULL)
    EXECUTE FUNCTION ordering.check_line_carton_unit();

CREATE TRIGGER tg_order_line_carton_unit
    BEFORE INSERT OR UPDATE OF sku, uom_conversion_id, units_per_carton
    ON ordering.order_line
    FOR EACH ROW WHEN (NEW.uom_conversion_id IS NOT NULL)
    EXECUTE FUNCTION ordering.check_line_carton_unit();

-- A quote's lines are all carton lines or all lines drafted before ----------------------------------------------
CREATE FUNCTION ordering.check_quote_line_kinds()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    the_quote UUID;
BEGIN
    the_quote := CASE WHEN TG_OP = 'DELETE' THEN OLD.quote_id ELSE NEW.quote_id END;
    IF EXISTS (SELECT 1 FROM ordering.quote_line WHERE quote_id = the_quote AND sku IS NULL)
        AND EXISTS (SELECT 1 FROM ordering.quote_line WHERE quote_id = the_quote AND sku IS NOT NULL) THEN
        RAISE EXCEPTION 'quote %: its lines are all cartons of the customer''s SKUs or all drafted before (QD-16)',
            the_quote USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_quote_line_kinds
    AFTER INSERT OR UPDATE OF sku ON ordering.quote_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_quote_line_kinds();

-- "Is this unit / item used by a document?" now names the quote and order lines -------------------------------
CREATE OR REPLACE FUNCTION inventory.unit_in_use(p_uom_conversion_id UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_package WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM ordering.order_line WHERE uom_conversion_id = p_uom_conversion_id)
$$;

CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id uuid, p_sku character varying)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $function$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.pricing_rule WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku OR sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$function$;

-- Customer prices: the customer's own SKU, counted in cartons --------------------------------------------------
CREATE FUNCTION catalog.check_customer_price_own_sku()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM product.variants v
                    WHERE v.sku = NEW.sku AND v.owner_customer_id = NEW.customer_id) THEN
        RAISE EXCEPTION 'customer price: % is not a SKU of customer % (13 BR-02, BR-05)', NEW.sku, NEW.customer_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_customer_price_own_sku
    BEFORE INSERT OR UPDATE OF customer_id, sku ON catalog.customer_price
    FOR EACH ROW EXECUTE FUNCTION catalog.check_customer_price_own_sku();

COMMENT ON COLUMN catalog.customer_price_tier.min_quantity IS
    'Where the tier starts: cartons for the customer''s own SKU (13 BR-05, 4B-2); units on a price set on a stock variant before, kept for checkout only.';
