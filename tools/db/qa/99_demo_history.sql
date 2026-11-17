-- =============================================================================
-- The demo's four months of trading (V20261128000200; 2026-11-14 plan, BE4-A) still show what they are for: the
-- receivables report has a debt in every age bucket and a deposit overdue on signing, warehouse DNI's projected free
-- room is below zero and its manager was told, every delivered order went the whole chain, and the stock summary has
-- receipts, picks, a return and a count adjustment to show. Ages are counted from the Vietnam business day, as the
-- migration counts its days. Needs the demo seed. Rolled back at the end.
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

-- What each customer owes after delivery, by age, as ReceivablesAdapter reads it (15 §4.3).
CREATE TEMP TABLE qa99_owed AS
SELECT i.customer_id, i.milestone, i.status, i.amount - i.paid_amount - i.reduced_amount AS owed,
       (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - i.due_date AS age, i.reached_at IS NOT NULL AS reached
  FROM payment.installment i
  JOIN customer.customer c ON c.id = i.customer_id
 WHERE c.tax_code IN ('0399990011', '0399990012', '0399990013', '0399990014')
   AND i.status NOT IN ('PAID', 'CANCELLED');

\echo '--- the receivables report'
SELECT pg_temp.expect_true('DH1 not yet due: a delivery whose days to pay run on',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'AFTER_DELIVERY' AND reached AND age <= 0 AND owed > 0));
SELECT pg_temp.expect_true('DH2 1-30 days overdue',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'AFTER_DELIVERY' AND reached AND age BETWEEN 1 AND 30));
SELECT pg_temp.expect_true('DH3 31-60 days overdue, partly paid',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'AFTER_DELIVERY' AND reached AND age BETWEEN 31 AND 60
                                      AND status = 'PARTIALLY_PAID'));
SELECT pg_temp.expect_true('DH4 61-90 days overdue',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'AFTER_DELIVERY' AND reached AND age BETWEEN 61 AND 90));
SELECT pg_temp.expect_true('DH5 over 90 days overdue',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'AFTER_DELIVERY' AND reached AND age > 90));
SELECT pg_temp.expect_true('DH6 committed: signed, its goods not delivered yet',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'AFTER_DELIVERY' AND NOT reached));
SELECT pg_temp.expect_true('DH7 a deposit due on signing, past its due day and unpaid (signingOverdue)',
    EXISTS (SELECT 1 FROM qa99_owed WHERE milestone = 'ON_SIGNING' AND age > 0 AND owed > 0));
SELECT pg_temp.expect_true('DH8 the money came in on many days: payment receipts on at least 15 different days',
    (SELECT count(DISTINCT r.received_on) FROM payment.payment_receipt r
       JOIN customer.customer c ON c.id = r.customer_id
      WHERE c.tax_code IN ('0399990011', '0399990012', '0399990013', '0399990014')) >= 15);

\echo '--- the capacity board'
SELECT pg_temp.expect_true('DH9 DNI''s projected free room is below zero: its bins less what stands in them less what '
                               || 'its production orders still bring',
    (SELECT w.utilization_factor * (SELECT sum(b.width * b.length * lv.usable_height)
                                      FROM warehouse.bin b
                                      JOIN warehouse.shelf_level lv ON lv.id = b.level_id
                                      JOIN warehouse.storage_location l ON l.id = b.location_id
                                     WHERE l.warehouse_id = w.id AND l.status = 'ACTIVE')
            - (SELECT coalesce(sum(u.length_cm * u.width_cm * u.height_cm / 1000000), 0)
                 FROM inventory.stock_package p JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
                WHERE p.status = 'IN_STOCK' AND p.location_code LIKE 'DNI-%')
            - (SELECT coalesce(sum((pl.cartons - pl.received_cartons) * u.length_cm * u.width_cm * u.height_cm
                                   / 1000000), 0)
                 FROM ordering.production_order po
                 JOIN ordering.production_order_line pl ON pl.production_order_id = po.id
                 JOIN ordering.order_line ol ON ol.id = pl.order_line_id
                 JOIN inventory.uom_conversions u ON u.id = ol.uom_conversion_id
                WHERE po.receiving_warehouse_id = w.id
                  AND po.status IN ('SENT', 'ACCEPTED', 'SAMPLE_PENDING', 'IN_PRODUCTION', 'PARTIALLY_RECEIVED'))
       FROM warehouse.warehouse w WHERE w.prefix = 'DNI') < 0);
SELECT pg_temp.expect_true('DH10 its warehouse manager was told once, WARN, linking to the board (C42)',
    (SELECT count(*) FROM notification.user_notification n
       JOIN identity.app_user u ON u.id = n.recipient_id
      WHERE u.username = 'demo.quanly.dn' AND n.category = 'WAREHOUSE_CAPACITY' AND n.severity = 'WARN'
        AND n.link = '/warehouse/capacity/' || (SELECT id FROM warehouse.warehouse WHERE prefix = 'DNI')) = 1);

\echo '--- the whole chain behind each delivered order'
SELECT pg_temp.expect_true('DH11 nine delivered orders (one with a return open), each with its picks, one labelled '
                               || 'carton per carton ordered, a load plan, a load handed over and a delivered shipment',
    (SELECT count(*) FROM ordering.customer_order o
      WHERE o.order_number LIKE 'SO-%-9100__' AND o.status IN ('DELIVERED', 'RETURN_REQUESTED')
        AND (SELECT count(*) FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
              WHERE k.order_id = o.id AND l.status = 'PICKED')
            = (SELECT sum(cartons) FROM ordering.order_line WHERE order_id = o.id)
        AND (SELECT count(*) FROM fulfillment.package p JOIN fulfillment.shipment s ON s.id = p.shipment_id
              WHERE s.order_id = o.id AND p.status = 'LABELED')
            = (SELECT sum(cartons) FROM ordering.order_line WHERE order_id = o.id)
        AND EXISTS (SELECT 1 FROM fulfillment.shipment s
                      JOIN fulfillment.dispatch_load d ON d.id = s.load_id AND d.status = 'DISPATCHED'
                      JOIN fulfillment.load_plan_vehicle v ON v.id = d.load_plan_vehicle_id
                      JOIN fulfillment.load_plan lp ON lp.id = v.load_plan_id AND lp.status = 'DISPATCHED'
                     WHERE s.order_id = o.id AND s.status = 'DELIVERED')) = 9);
SELECT pg_temp.expect_true('DH12 every carton delivered left by a PICK line of its order',
    NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                  JOIN ordering.customer_order o ON o.id = k.order_id
                 WHERE o.order_number LIKE 'SO-%-9100__' AND l.status = 'PICKED'
                   AND NOT EXISTS (SELECT 1 FROM inventory.stock_movement m
                                    WHERE m.movement_type = 'PICK' AND m.reference_id = l.id AND m.order_id = o.id)));

\echo '--- the stock summary has every kind of line to show'
SELECT pg_temp.expect_true('DH13 at DNI: received, picked, returned and adjusted lines',
    (SELECT count(DISTINCT CASE WHEN movement_type = 'RECEIPT' AND reference_type = 'GOODS_RECEIPT_LINE' THEN 'received'
                                WHEN movement_type = 'PICK' THEN 'picked'
                                WHEN movement_type = 'RECEIPT' AND reference_type = 'GOODS_RECEIPT' THEN 'returned'
                                WHEN movement_type = 'ADJUSTMENT' THEN 'adjusted' END)
       FROM inventory.stock_movement
      WHERE coalesce(to_location_code, from_location_code) LIKE 'DNI-%') = 4);
SELECT pg_temp.expect_true('DH14 the return came back on a closed return receipt into quarantine; the count''s variance '
                               || 'was approved by someone other than the counter',
    EXISTS (SELECT 1 FROM ordering.return_request r
              JOIN procurement.goods_receipts g ON g.id = r.goods_receipt_id
             WHERE r.status = 'RECEIVED' AND g.status = 'CLOSED' AND g.receipt_type = 'RETURN'
               AND r.receiving_location_code = 'DNI-QC01')
    AND EXISTS (SELECT 1 FROM inventory.stock_adjustment a
                 WHERE a.reason_code = 'COUNT_VARIANCE' AND a.status = 'POSTED' AND a.decided_by <> a.counted_by
                   AND a.location_code = 'DNI-QC01'));

ROLLBACK;
