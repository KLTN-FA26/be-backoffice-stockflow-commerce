-- The default purchase unit belonged to purchase orders, which are gone: no document starts in a unit chosen
-- per item any more. Dropping the column takes its CHECK and its partial unique index with it; both are named
-- here so the drop is explicit. The trigger function that creates an item's base unit names the column in its
-- body, and Postgres does not track dependencies in function bodies: rewrite it first, as it was in the baseline
-- without the column.

CREATE OR REPLACE FUNCTION inventory.create_base_uom() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, created_by)
    VALUES (gen_random_uuid(), NEW.id, NEW.unit_of_measure, 1, TRUE, NEW.created_by);
    RETURN NEW;
END $$;

DROP INDEX IF EXISTS inventory.uk_uom_conversions_default_purchase;
ALTER TABLE inventory.uom_conversions DROP CONSTRAINT IF EXISTS ck_uom_conversions_default_purchase;
ALTER TABLE inventory.uom_conversions DROP COLUMN default_purchase;
