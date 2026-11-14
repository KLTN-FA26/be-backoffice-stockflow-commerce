-- =============================================================================
-- Stock ledger balance report (2026-11-14 plan, BE2-C): for every warehouse and SKU, the whole stock ledger summed
-- must equal the stock on hand. Read-only. Run it on any database, after the demo seed or real data:
--
--   docker exec -i sf-schema psql -U postgres -d <db> -X < tools/db/stock_ledger_balance.sql
--
-- Why it matters: the stock summary report (GET /api/v1/reports/stock-summary) is the ledger summed - opening is every
-- line before the period, closing every line up to its end (StockLedgerTotals). It is only true if every change of
-- on hand wrote its ledger line (kltn-docs 20 BR-16). A ledger line changes warehouse W by
--   quantity * ([to_location in W] - [from_location in W])
-- where "in W" is the location code's prefix, the part before the first '-' (06 BR-13), so moves inside a warehouse
-- count zero; summed over the whole ledger that is what W holds of the SKU now.
--
-- Every row printed is a (warehouse, SKU) whose ledger and on hand disagree; no row = balanced.
--
-- Not under tools/db/qa/ (and so not run by RepositorySqlQaTest), on purpose: the demo seed fails it. Its stock
-- (V20261111000001: SOFA-3S-GREY, TABLE-OAK-160 at HCM, 75 pieces) was loaded as rows with triggers off, as opening
-- stock with no receipt (lot UNTRACED, 20 §4.7 step 3); the only ledger lines it has are the boxing of loose stock into
-- cartons (V20261111000110: MOVE / STOCK_PACKAGE inside HCM, zero for the warehouse). So the ledger says 0 where the
-- shelves hold 33 and 42. StockLedgerTotalsIntegrationTest holds the invariant over flows it builds. Once the demo
-- data's opening stock has ledger lines (a demo migration of BE4), this belongs under qa/ as an expect_true on
-- "no row".
-- =============================================================================

WITH warehouses AS (
    SELECT upper(prefix) AS prefix FROM warehouse.warehouse
), ledger AS (
    SELECT w.prefix, m.sku,
           SUM(m.quantity * ((CASE WHEN upper(split_part(m.to_location_code, '-', 1)) = w.prefix THEN 1 ELSE 0 END)
                           - (CASE WHEN upper(split_part(m.from_location_code, '-', 1)) = w.prefix THEN 1 ELSE 0 END)))
               AS ledger_units
      FROM inventory.stock_movement m
      JOIN warehouses w ON w.prefix IN (upper(split_part(m.to_location_code, '-', 1)),
                                        upper(split_part(m.from_location_code, '-', 1)))
     GROUP BY w.prefix, m.sku
), books AS (
    SELECT upper(split_part(location_code, '-', 1)) AS prefix, sku, SUM(on_hand) AS on_hand
      FROM inventory.stock_item
     GROUP BY 1, 2
)
SELECT COALESCE(l.prefix, b.prefix) AS warehouse,
       COALESCE(l.sku, b.sku) AS sku,
       COALESCE(l.ledger_units, 0) AS ledger_units,
       COALESCE(b.on_hand, 0) AS on_hand,
       COALESCE(b.on_hand, 0) - COALESCE(l.ledger_units, 0) AS unexplained
  FROM ledger l
  FULL JOIN books b ON b.prefix = l.prefix AND b.sku = l.sku
 WHERE COALESCE(l.ledger_units, 0) <> COALESCE(b.on_hand, 0)
 ORDER BY 1, 2;
