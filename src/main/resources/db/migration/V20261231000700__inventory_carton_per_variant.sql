-- The carton belongs to the catalogue variant (owner decisions of 2026-11-26, Q1/Q2, reversing kltn-docs B20).
--
-- Until now a customer's SKU got a copy of its variant's carton at design approval (ItemSpecificationsImpl.copy), so a
-- carton measured again on the variant never reached the SKUs already issued: their cartons kept the old size and
-- weight, and putaway, capacity, labels and load plans were sized wrong with nothing reporting it. Customers order in
-- the company's carton and every producer packs a cup in the same carton, so the carton is the variant's:
--
--   1. inventory.inventory_items.variant_id: the catalogue variant an item's SKU belongs to (its source variant for a
--      customer's SKU, itself for a catalogue variant). It never changes, so inventory reads a SKU's carton without
--      asking product.
--   2. inventory.uom_conversions is keyed by variant_id instead of inventory_item_id. The copies of one variant are
--      merged onto it: the variant's own active carton stays active; with none, the most common active copy (same
--      count, size and weight) becomes it. Every other copy a document or a carton counts in is retired and kept as
--      history (BR-10); the rest are deleted. Copies sharing a code keep the constraint by a suffix (THUNG -> THUNG_R2).
--   3. A carton is declared only on a catalogue variant (tg_uom_conversions_catalogue_variant), one active per variant
--      (uk_uom_conversions_one_carton), codes unique per variant (uk_uom_conversions_unit).
--   4. Every check that compared a carton's item with a line's or a package's item now compares variants:
--      check_line_carton_unit (quote and order lines), check_goods_receipt_line_unit, check_stock_package_created,
--      check_packing_unit_code, check_item_unit_code; the in-use guard watches variant_id.
--   5. Q10: a receipt line may count in a retired carton when it is the carton of the order line its production order
--      line was made for. A contract signed before the company changed carton is received as it was signed.
--
-- Cartons already in stock keep the carton row they were received in (stock_package.uom_conversion_id, nominal_qty).

-- 1. The variant of every item ------------------------------------------------------------------------------------

ALTER TABLE inventory.inventory_items ADD COLUMN variant_id uuid;

UPDATE inventory.inventory_items i
   SET variant_id = COALESCE(v.source_variant_id, v.id)
  FROM product.variants v
 WHERE v.sku = i.sku;

ALTER TABLE inventory.inventory_items ALTER COLUMN variant_id SET NOT NULL;
ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT fk_inventory_items_carton_variant FOREIGN KEY (variant_id) REFERENCES product.variants(id);
CREATE INDEX ix_inventory_items_variant ON inventory.inventory_items (variant_id);
COMMENT ON COLUMN inventory.inventory_items.variant_id IS 'The catalogue variant whose carton this SKU is packed in: the source variant of a customer''s SKU, the variant itself for a catalogue variant (V20261231000700). Never changes.';

CREATE FUNCTION inventory.guard_item_variant_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'inventory item %: the catalogue variant it is packed as never changes', OLD.sku
        USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_inventory_items_variant_fixed';
END
$$;

CREATE TRIGGER tg_inventory_items_variant_fixed BEFORE UPDATE OF variant_id ON inventory.inventory_items
    FOR EACH ROW WHEN (NEW.variant_id IS DISTINCT FROM OLD.variant_id)
    EXECUTE FUNCTION inventory.guard_item_variant_change();

CREATE OR REPLACE FUNCTION inventory.create_variant_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO inventory.inventory_items(id, sku, variant_id, created_by)
    VALUES (gen_random_uuid(), NEW.sku, COALESCE(NEW.source_variant_id, NEW.id), NEW.created_by)
    ON CONFLICT (sku) DO NOTHING;
    RETURN NEW;
END $$;

-- 2. Merge the copies onto the variant ----------------------------------------------------------------------------

-- Triggers whose column lists or WHEN clauses name inventory_item_id go first; they are rebuilt in step 4.
DROP TRIGGER tg_uom_conversions_locked_in_use ON inventory.uom_conversions;
DROP TRIGGER tg_uom_conversions_unit_code ON inventory.uom_conversions;

ALTER TABLE inventory.uom_conversions ADD COLUMN variant_id uuid;

UPDATE inventory.uom_conversions u
   SET variant_id = i.variant_id
  FROM inventory.inventory_items i
 WHERE i.id = u.inventory_item_id;

-- The one carton each variant keeps active: its own, else the most common active copy, earliest first.
CREATE TEMPORARY TABLE carton_keep ON COMMIT DROP AS
WITH candidates AS (
    SELECT u.id, u.variant_id, u.created_at,
           (i.sku = v.sku) AS own,
           count(*) OVER (PARTITION BY u.variant_id, u.factor, u.length_cm, u.width_cm, u.height_cm, u.weight_kg)
               AS alike
      FROM inventory.uom_conversions u
      JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
      JOIN product.variants v ON v.id = u.variant_id
     WHERE u.active
)
SELECT DISTINCT ON (variant_id) variant_id, id
  FROM candidates
 ORDER BY variant_id, own DESC, alike DESC, created_at, id;

UPDATE inventory.uom_conversions u
   SET active = false
 WHERE u.active
   AND NOT EXISTS (SELECT 1 FROM carton_keep k WHERE k.id = u.id);

DELETE FROM inventory.uom_conversions u
 WHERE NOT EXISTS (SELECT 1 FROM carton_keep k WHERE k.id = u.id)
   AND NOT inventory.unit_in_use(u.id);

-- Codes shared by the merged rows: the active one keeps its code, the others take a suffix.
WITH named AS (
    SELECT u.id, u.unit_of_measure,
           row_number() OVER (PARTITION BY u.variant_id, u.unit_of_measure
                              ORDER BY u.active DESC, u.created_at, u.id) AS n
      FROM inventory.uom_conversions u
)
UPDATE inventory.uom_conversions u
   SET unit_of_measure = left(named.unit_of_measure, 16 - length('_R' || named.n)) || '_R' || named.n
  FROM named
 WHERE named.id = u.id
   AND named.n > 1;

-- 3. Re-key ---------------------------------------------------------------------------------------------------------

DROP INDEX inventory.uk_uom_conversions_one_carton;
ALTER TABLE inventory.uom_conversions DROP CONSTRAINT uk_uom_conversions_unit;
ALTER TABLE inventory.uom_conversions DROP CONSTRAINT fk_uom_conversions_item;
ALTER TABLE inventory.uom_conversions DROP COLUMN inventory_item_id;

ALTER TABLE inventory.uom_conversions ALTER COLUMN variant_id SET NOT NULL;
ALTER TABLE inventory.uom_conversions
    ADD CONSTRAINT fk_uom_conversions_variant FOREIGN KEY (variant_id) REFERENCES product.variants(id)
        ON DELETE CASCADE;
ALTER TABLE inventory.uom_conversions
    ADD CONSTRAINT uk_uom_conversions_unit UNIQUE (variant_id, unit_of_measure);
CREATE UNIQUE INDEX uk_uom_conversions_one_carton ON inventory.uom_conversions (variant_id) WHERE active;

COMMENT ON TABLE inventory.uom_conversions IS 'The cartons of a catalogue variant (kltn-docs 01 BR-03; owner decision Q1 of 2026-11-26): pieces per carton (factor), size and full weight. Every SKU of the variant - its customers'' SKUs included - is packed in its one active carton; retired ones are kept for the documents and cartons that counted in them (BR-10). Keyed by variant since V20261231000700.';
COMMENT ON COLUMN inventory.uom_conversions.variant_id IS 'The catalogue variant (never a customer''s SKU, tg_uom_conversions_catalogue_variant). Never changes once a document counts in the carton.';

-- 4. Checks, now by variant -----------------------------------------------------------------------------------------

CREATE FUNCTION inventory.check_carton_on_catalogue_variant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM product.variants v WHERE v.id = NEW.variant_id AND v.owner_customer_id IS NOT NULL) THEN
        RAISE EXCEPTION 'carton %: a customer''s SKU is packed in its variant''s carton and has none of its own',
            NEW.unit_of_measure USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_uom_conversions_catalogue_variant';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_uom_conversions_catalogue_variant BEFORE INSERT OR UPDATE OF variant_id
    ON inventory.uom_conversions
    FOR EACH ROW EXECUTE FUNCTION inventory.check_carton_on_catalogue_variant();

CREATE TRIGGER tg_uom_conversions_locked_in_use BEFORE UPDATE OF factor, variant_id, unit_of_measure
    ON inventory.uom_conversions
    FOR EACH ROW WHEN (NEW.factor IS DISTINCT FROM OLD.factor
                       OR NEW.variant_id IS DISTINCT FROM OLD.variant_id
                       OR NEW.unit_of_measure IS DISTINCT FROM OLD.unit_of_measure)
    EXECUTE FUNCTION inventory.guard_unit_in_use_change();

CREATE OR REPLACE FUNCTION inventory.check_packing_unit_code() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.inventory_items i
                WHERE i.variant_id = NEW.variant_id AND i.unit_of_measure = NEW.unit_of_measure) THEN
        RAISE EXCEPTION 'carton %: that is the unit the pieces of its variant''s SKUs are counted in', NEW.unit_of_measure
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_uom_conversions_unit_code';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_uom_conversions_unit_code BEFORE INSERT OR UPDATE OF unit_of_measure, variant_id
    ON inventory.uom_conversions
    FOR EACH ROW EXECUTE FUNCTION inventory.check_packing_unit_code();

CREATE OR REPLACE FUNCTION inventory.check_item_unit_code() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.uom_conversions c
                WHERE c.variant_id = NEW.variant_id AND c.unit_of_measure = NEW.unit_of_measure) THEN
        RAISE EXCEPTION 'item %: a carton of its variant is already called %', NEW.sku, NEW.unit_of_measure
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_inventory_items_unit_code';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION inventory.check_stock_package_created() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unit inventory.uom_conversions%ROWTYPE;
BEGIN
    IF NEW.status NOT IN ('RECEIVING', 'IN_STOCK') THEN
        RAISE EXCEPTION 'stock package % is created RECEIVING or IN_STOCK, not %', NEW.lpn, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_stock_package_unit reports it
    END IF;
    IF NOT EXISTS (SELECT 1 FROM inventory.inventory_items i
                    WHERE i.id = NEW.inventory_item_id AND i.variant_id = unit.variant_id) THEN
        RAISE EXCEPTION 'stock package %: carton % is not the carton of its SKU''s variant', NEW.lpn, unit.unit_of_measure
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT unit.active AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines
                                        WHERE id = NEW.goods_receipt_line_id
                                          AND uom_conversion_id = NEW.uom_conversion_id) THEN
        RAISE EXCEPTION 'stock package %: unit % is retired; only goods received in it are still packed in it',
            NEW.lpn, unit.unit_of_measure USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.nominal_qty <> unit.factor THEN
        RAISE EXCEPTION 'stock package % holds % per %, not %', NEW.lpn, unit.factor, unit.unit_of_measure,
            NEW.nominal_qty USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION ordering.check_line_carton_unit() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unit      inventory.uom_conversions%ROWTYPE;
    variant   UUID;
    new_unit  BOOLEAN;
    owner     UUID;
    customer  UUID;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    -- A customer's SKU is packed in its variant's carton (Q1).
    SELECT COALESCE(v.source_variant_id, v.id), v.owner_customer_id INTO variant, owner
      FROM product.variants v WHERE v.sku = NEW.sku;
    IF variant IS DISTINCT FROM unit.variant_id THEN
        RAISE EXCEPTION '% line: unit % is not the carton of %', TG_TABLE_NAME, unit.unit_of_measure, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.units_per_carton <> unit.factor THEN
        RAISE EXCEPTION '% line: % holds % pieces, not %', TG_TABLE_NAME, unit.unit_of_measure, unit.factor,
            NEW.units_per_carton USING ERRCODE = 'check_violation';
    END IF;
    -- 14 BR-04: the customer's own SKU.
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

CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_unit() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unit     inventory.uom_conversions%ROWTYPE;
    new_unit BOOLEAN;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF NOT EXISTS (SELECT 1 FROM inventory.inventory_items i
                    WHERE i.id = NEW.inventory_item_id AND i.variant_id = unit.variant_id) THEN
        RAISE EXCEPTION 'receipt line: the unit is not the carton of the received SKU''s variant'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        new_unit := TRUE;
    ELSE
        new_unit := NEW.uom_conversion_id IS DISTINCT FROM OLD.uom_conversion_id;
    END IF;
    -- Q10: a retired carton still takes the goods of the order line that was signed in it, and nothing else.
    IF new_unit AND NOT unit.active AND NOT EXISTS (
            SELECT 1 FROM ordering.production_order_line pl
              JOIN ordering.order_line ol ON ol.id = pl.order_line_id
             WHERE pl.id = NEW.production_order_line_id
               AND ol.uom_conversion_id = NEW.uom_conversion_id) THEN
        RAISE EXCEPTION 'receipt line: unit % is retired; only the goods of a line signed in it are received in it',
            unit.unit_of_measure USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
