-- A ledger line is one carton and records no number of units (owner decision 2026-11-26, ADR-0011, step 3b).
--
-- inventory.stock_movement.quantity was "the units the line moved". Since V20261230000200 every new line names exactly
-- one carton (stock_package_id), and a carton in stock is always whole (check_stock_package_contents: an IN_STOCK
-- carton holds exactly its nominal_qty), so the figure always equalled the nominal_qty of the carton beside it: it
-- could say nothing the carton does not say. Written as what it is:
--
--   inventory.stock_movement   quantity dropped; stock_package_id NOT NULL
--
-- Whoever wants units reads inventory.stock_package.nominal_qty through the line's carton (ADR-0011).
--
-- History: a line that names no carton - loose stock from before everything was boxed (V20261111000110), which
-- V20261230000200 let stand behind a NOT VALID CHECK - is deleted, not converted: without a quantity it would record
-- nothing, and no carton can be made up for it (owner decision QD-1: every database is rebuilt from the demo seed on
-- deploy). No database built from the baseline has one (1,509 ledger lines of the demo seed looked at: every one names
-- a carton, and every one's quantity is its carton's nominal_qty). With them gone the column is NOT NULL and
-- ck_stock_movement_names_carton, which said the same of new lines only, goes.
--
-- Readers: looked for in pg_proc.prosrc, pg_views, pg_matviews, pg_constraint, pg_trigger and pg_index of a database
-- with every migration applied (Postgres does not track what a function body mentions). Only ck_stock_movement_qty
-- ("quantity > 0") reads the column and it goes with it. No function, view, trigger or index does:
-- procurement.check_return_receipt_closed, the one function that mentions both the ledger and a "quantity", already
-- counts the ledger's lines against the cartons put into stock (count(*), V20261230000200) - its "quantity" is
-- goods_receipt_return_cartons.quantity - so it is left as it is and refuses exactly what it refused.

-- ---------------------------------------------------------------------------------------------- 1. history

-- The ledger is append-only (tg_stock_movement_append_only); removing the lines of loose stock is the one delete.
ALTER TABLE inventory.stock_movement DISABLE TRIGGER tg_stock_movement_append_only;

DELETE FROM inventory.stock_movement WHERE stock_package_id IS NULL;

ALTER TABLE inventory.stock_movement ENABLE TRIGGER tg_stock_movement_append_only;

-- ---------------------------------------------------------------------------------------------- 2. nothing is lost

-- A line whose quantity is not its carton's standard count says something the carton cannot: part of a carton moved.
-- That cannot be kept by dropping the number, and is not guessed away. No database built from the baseline has one;
-- one kept by hand is looked at first.
DO $$
DECLARE
    odd TEXT;
BEGIN
    SELECT string_agg(line, '; ') INTO odd
      FROM (SELECT m.id || ' ' || m.movement_type || ' of ' || m.quantity || ' names carton ' || p.lpn || ' of '
                   || p.nominal_qty AS line
              FROM inventory.stock_movement m
              JOIN inventory.stock_package p ON p.id = m.stock_package_id
             WHERE m.quantity <> p.nominal_qty
             ORDER BY m.occurred_at, m.id
             LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_movement: line(s) move another quantity than the standard count of the carton they '
            'name: %; a ledger line is one whole carton (ADR-0011) - settle these rows before migrating', odd
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------- 3. the column

ALTER TABLE inventory.stock_movement
    DROP CONSTRAINT ck_stock_movement_qty,
    DROP CONSTRAINT ck_stock_movement_names_carton,
    DROP COLUMN quantity,
    ALTER COLUMN stock_package_id SET NOT NULL;

-- A carton's history, newest first: every line has a carton now, so the index is no longer a partial one.
DROP INDEX inventory.ix_stock_movement_package;
CREATE INDEX ix_stock_movement_package ON inventory.stock_movement (stock_package_id, occurred_at);

COMMENT ON TABLE inventory.stock_movement IS
    'The append-only stock ledger: one line is one whole carton (stock_package_id) that arrived, moved, changed status '
    'or left. It keeps no quantity: the units of a line are its carton''s nominal_qty (ADR-0011, V20261230000400).';
COMMENT ON COLUMN inventory.stock_movement.stock_package_id IS
    'The carton the line is about, and all it moved: stock is whole sealed cartons, so a line is one carton, and '
    'movement_type with from_location_code / to_location_code says whether it arrived, moved or left (ADR-0011). Its '
    'units are inventory.stock_package.nominal_qty.';
