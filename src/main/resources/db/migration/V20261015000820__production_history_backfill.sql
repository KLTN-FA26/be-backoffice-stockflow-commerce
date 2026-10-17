-- =============================================================================
-- Second backfill of print runs (V20261015000720) and packing areas (V20261015000810), from the history a develop
-- database actually holds (review of #109, item 11).
--
-- The rehearsal on the generated develop seed found both earlier backfills writing nothing: develop never wrote the
-- "Printed N, scrap M" reason V20261015000720 parses (its PRINTING -> QC history rows have no reason), and it wrote no
-- ledger line for a hand-over to packing, which V20261015000810 reads. Without this file the workshop dashboard shows
-- nothing printed before the upgrade.
--
-- 1. Print runs. An order whose printed_quantity is more than its print runs and a subcontractor's receipts account
--    for gets one run for the difference, at the time it left the press: its latest PRINTING -> QC history row, by
--    whoever recorded that. Scrap of that run is 0: what was spoilt is already counted, once, by production_scrap and
--    the order's scrap_quantity; a split between "spoilt on the press" and "failed QC" was never recorded and is not
--    guessed. An order with no PRINTING -> QC row gets no run - there is no time to put it at.
-- 2. Packing areas. A completed ORDER whose goods are still held for its sales order line in exactly one
--    PACKING area of its warehouse gets that area. Anything else stays NULL: the goods shipped since, are spread over
--    several areas, or were never in the ledger - nothing is invented.
--
-- Both statements only fill what is missing, so the file is safe on a database where the earlier ones did fill rows.
-- =============================================================================

INSERT INTO production.production_print_run (id, production_order_id, printed_quantity, scrap_quantity, recorded_at,
                                            recorded_by)
SELECT gen_random_uuid(), o.id, o.printed_quantity - r.recorded - s.received, 0, left_press.changed_at,
       left_press.changed_by
  FROM production.production_order o
 CROSS JOIN LATERAL (SELECT COALESCE(SUM(printed_quantity), 0) AS recorded FROM production.production_print_run
                      WHERE production_order_id = o.id) r
 CROSS JOIN LATERAL (SELECT COALESCE(SUM(quantity), 0) AS received FROM production.production_subcontract_receipt
                      WHERE production_order_id = o.id) s
  JOIN LATERAL (SELECT h.changed_at, h.changed_by
                  FROM production.production_order_status_history h
                 WHERE h.production_order_id = o.id AND h.from_status = 'PRINTING' AND h.to_status = 'QC'
                 ORDER BY h.changed_at DESC, h.id DESC
                 LIMIT 1) left_press ON TRUE
 WHERE o.printed_quantity - r.recorded - s.received > 0;

UPDATE production.production_order po
   SET packing_location_code = held.location_code
  FROM (SELECT o.id AS production_order_id, min(si.location_code) AS location_code
          FROM production.production_order o
          JOIN ordering.order_line_reservation lr ON lr.order_line_id = o.sales_order_line_id
          JOIN inventory.stock_reservation r ON r.id = lr.reservation_id AND r.status = 'HELD'
                                            AND r.order_id = o.sales_order_id
          JOIN inventory.stock_item si ON si.id = r.stock_item_id
          JOIN warehouse.storage_location sl ON sl.location_code = si.location_code AND sl.warehouse_id = o.warehouse_id
          JOIN warehouse.area a ON a.location_id = sl.id AND a.type = 'PACKING'
         WHERE o.type = 'ORDER' AND o.status = 'COMPLETED' AND o.packing_location_code IS NULL
         GROUP BY o.id
        HAVING count(DISTINCT si.location_code) = 1) held
 WHERE po.id = held.production_order_id;
