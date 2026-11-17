-- =============================================================================
-- Demo: the two seeded SKUs get a carton size and their opening stock gets its ledger lines (2026-11-14 plan, BE4-A).
-- Demo only (db/demo). Loaded with every trigger on.
--
-- 1. Carton size. The CARTON units of SOFA-3S-GREY and TABLE-OAK-160 (one piece a carton) declared no size, so the
--    capacity board (06 §4.6, BE1) measured the 75 cartons of HCM as "without size" and showed 0 m³ occupied. They take
--    the package size the item master already declares for the piece (01: the carton spec is the shipping package):
--    sofa 215 x 95 x 90 cm, table 165 x 85 x 20 cm. Only the size: no weight is declared on the unit, so the label
--    still asks for the carton's weight (08 BR-07) and the board and putaway keep weighing a carton by the item's
--    weight, as they did. The stock was put away before the volume rule (05 BR-03, 5-4) existed: HCM-A01-2-B holds 25
--    sofa cartons, more than its 8.4 m³, and the board now says so - the state the system leaves when the warehouse
--    manager declares a carton size after the goods are on the shelf (08 edge case "the real carton differs from the
--    declared spec").
--
-- 2. Opening ledger lines. The seed loaded HCM's stock as rows with no ledger line (opening stock, lot UNTRACED -
--    kltn-docs 20 §4.7 step 3, open-questions C45); its only lines are the boxing of loose units into cartons
--    (V20261111000110: MOVE in place), which count zero for the warehouse. So the stock summary report (BE2-C), which is
--    the ledger summed, showed both SKUs at 0 while the shelves hold 33 and 42, and tools/db/stock_ledger_balance.sql
--    failed. Each carton now has the opening line C45 assumes ("tồn đầu kỳ được nạp kèm một dòng sổ mở đầu"): a
--    RECEIPT of its units into it, where it stands, in its lot and status, at the moment it was made - referenced by the
--    carton itself (STOCK_PACKAGE), since no goods receipt brought it in. The report counts such a line as "nhập khác"
--    (other in), not as received from a production order. The ledger and the shelves then agree for every
--    (warehouse, SKU): tools/db/qa/99_stock_ledger_balance.sql holds it.
-- =============================================================================

UPDATE inventory.uom_conversions u
   SET length_cm = i.package_length_cm, width_cm = i.package_width_cm, height_cm = i.package_height_cm,
       version = u.version + 1, last_modified_at = TIMESTAMPTZ '2026-11-11 13:19:36+00', last_modified_by = 'flyway'
  FROM inventory.inventory_items i
 WHERE i.id = u.inventory_item_id
   AND i.sku IN ('SOFA-3S-GREY', 'TABLE-OAK-160')
   AND u.unit_of_measure = 'CARTON'
   AND NOT u.is_base
   AND u.length_cm IS NULL AND u.width_cm IS NULL AND u.height_cm IS NULL;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id,
                                      to_stock_package_id, reason, occurred_at, version, created_at, created_by)
SELECT md5('demo:opening-ledger:' || p.lpn)::uuid, 'RECEIPT', s.sku, s.lot_number, s.lot_id, NULL, p.location_code,
       s.on_hand, NULL, s.status, 'STOCK_PACKAGE', p.id, p.id,
       'Tồn đầu kỳ khi đưa hệ thống vào dùng (dữ liệu demo; open-questions C45)', p.created_at, 0, p.created_at,
       'flyway'
  FROM inventory.stock_package p
  JOIN inventory.stock_item s ON s.stock_package_id = p.id
 WHERE p.created_by = 'V20261111000110'
   AND p.status = 'IN_STOCK'
   AND s.on_hand > 0;
