-- =============================================================================
-- The stock ledger balances (kltn-docs 20 BR-16; 2026-11-14 plan, BE2-C and BE4-A): for every warehouse and SKU the
-- whole ledger summed is the stock on hand. The stock summary report (GET /api/v1/reports/stock-summary) is that ledger
-- summed, so it is only true while every carton that entered or left stock wrote its line. Stock on hand is the
-- cartons IN_STOCK, each its nominal_qty (ADR-0011, V20261231000600: the carton is the stock). A line changes warehouse W by
--   nominal_qty of its carton * ([to_location in W] - [from_location in W])
-- where "in W" is the location code's prefix (06 BR-13): a move inside a warehouse counts zero.
--
-- Until V20261128000100 the demo seed failed this (its opening stock had no ledger line, open-questions C45), and the
-- check lived only as the report tools/db/stock_ledger_balance.sql. It holds on the demo data now, so it is guarded
-- here; the report stays for a database that is not the demo one, and prints the rows that disagree.
--
-- First the check bites: a ledger line written with no stock behind it, and stock written with no ledger line, are each
-- seen. Then the data as it is balances. Needs the demo seed (HCM's opening stock). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Every (warehouse, SKU) whose ledger and on hand disagree, as tools/db/stock_ledger_balance.sql computes it.
CREATE FUNCTION pg_temp.unbalanced() RETURNS TABLE (warehouse TEXT, sku TEXT, ledger_units BIGINT, on_hand BIGINT)
LANGUAGE sql AS $$
    WITH warehouses AS (
        SELECT upper(prefix) AS prefix FROM warehouse.warehouse
    ), ledger AS (
        SELECT w.prefix, m.sku,
               SUM(p.nominal_qty * ((CASE WHEN upper(split_part(m.to_location_code, '-', 1)) = w.prefix THEN 1 ELSE 0 END)
                               - (CASE WHEN upper(split_part(m.from_location_code, '-', 1)) = w.prefix THEN 1 ELSE 0 END)))
                   AS ledger_units
          FROM inventory.stock_movement m
          JOIN inventory.stock_package p ON p.id = m.stock_package_id
          JOIN warehouses w ON w.prefix IN (upper(split_part(m.to_location_code, '-', 1)),
                                            upper(split_part(m.from_location_code, '-', 1)))
         GROUP BY w.prefix, m.sku
    ), books AS (
        SELECT upper(split_part(p.location_code, '-', 1)) AS prefix, i.sku, SUM(p.nominal_qty) AS on_hand
          FROM inventory.stock_package p
          JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
         WHERE p.status = 'IN_STOCK'
         GROUP BY 1, 2
    )
    SELECT COALESCE(l.prefix, b.prefix)::text, COALESCE(l.sku, b.sku)::text, COALESCE(l.ledger_units, 0)::bigint,
           COALESCE(b.on_hand, 0)::bigint
      FROM ledger l
      FULL JOIN books b ON b.prefix = l.prefix AND b.sku = l.sku
     WHERE COALESCE(l.ledger_units, 0) <> COALESCE(b.on_hand, 0)
$$;

\echo '--- the check bites'
SAVEPOINT breach;
-- An adjustment line with no stock moved (as if a write-up were posted to the ledger only).
-- It names a carton standing there, as every line does (V20261231000200); the carton itself is not touched. The line
-- counts for the carton's standard count, the ledger keeping no quantity of its own (V20261231000400).
INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, to_status,
                                      reference_type, reference_id, occurred_at, version, created_at, stock_package_id)
SELECT md5('qa99:ledger-only')::uuid, 'ADJUSTMENT', 'LY-GIAY-DEMO-12OZ-DO', 'LOT-2026-03', 'HCM-B01-1-A', 'AVAILABLE',
       'STOCK_ADJUSTMENT', md5('qa99:no-adjustment')::uuid, NOW(), 0, NOW(), p.id
  FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
 WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO' AND p.location_code = 'HCM-B01-1-A' AND p.status = 'IN_STOCK'
 ORDER BY p.lpn LIMIT 1;
SELECT pg_temp.expect_true('LB1 a ledger line with no stock behind it is seen, by warehouse and SKU',
    EXISTS (SELECT 1 FROM pg_temp.unbalanced() u
             WHERE u.warehouse = 'HCM' AND u.sku = 'LY-GIAY-DEMO-12OZ-DO'
               AND u.ledger_units = u.on_hand + (SELECT p.nominal_qty FROM inventory.stock_movement m
                                                   JOIN inventory.stock_package p ON p.id = m.stock_package_id
                                                  WHERE m.id = md5('qa99:ledger-only')::uuid)));
ROLLBACK TO SAVEPOINT breach;

SAVEPOINT breach;
-- Stock written with no ledger line: a carton of 1,000 blue demo cups written off by hand, the carton only.
UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW()
 WHERE lpn = 'LPN-20261111-000001';
SELECT pg_temp.expect_true('LB2 stock that changed with no ledger line is seen too',
    EXISTS (SELECT 1 FROM pg_temp.unbalanced() u
             WHERE u.warehouse = 'HCM' AND u.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND u.ledger_units = u.on_hand + 1000));
ROLLBACK TO SAVEPOINT breach;

\echo '--- the data balances'
SELECT pg_temp.expect_true('LB3 every warehouse and SKU: the ledger summed is the stock on hand'
                               || coalesce(' - off: ' || (SELECT string_agg(u.warehouse || ' ' || u.sku || ' ledger '
                                                                           || u.ledger_units || ' on hand ' || u.on_hand,
                                                                           '; ')
                                                            FROM pg_temp.unbalanced() u), ''),
    NOT EXISTS (SELECT 1 FROM pg_temp.unbalanced()));
SELECT pg_temp.expect_true('LB4 the seeded opening stock has its opening line: one RECEIPT per carton of HCM (C45)',
    (SELECT count(*) FROM inventory.stock_movement m
       JOIN inventory.stock_package p ON p.id = m.stock_package_id
      WHERE m.movement_type = 'RECEIPT' AND m.reference_type = 'STOCK_PACKAGE' AND m.reference_id = p.id
        AND p.created_by = 'V20261206000100') = 75);

ROLLBACK;
