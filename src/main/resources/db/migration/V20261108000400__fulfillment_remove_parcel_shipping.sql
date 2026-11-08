-- =============================================================================
-- Every order ships as a full truckload through a load plan, and nothing is collected on delivery (kltn-docs 09 BR-08,
-- decided 2026-11-05: "chỉ còn giao nguyên chuyến", "không có thu hộ khi giao"). Parcel shipping - a load opened by
-- hand for one carrier, shipments put on and taken off its manifest, a shipment's carrier chosen in advance - the
-- Order Coordinator's choice of delivery mode, and COD (the carrier collecting a deposit order's balance, ON_DELIVERY)
-- are gone from the code; this removes what the database kept for them. Every value of a column dropped, and every row
-- of the table dropped, is archived whole in platform.legacy_archive first.
--
-- Kept on purpose:
--   * loads of the parcel shipping already DISPATCHED or CANCELLED stay as history: they have no plan vehicle, which
--     only a closed load may lack from now on;
--   * shipment.carrier_id, set at the handover from the vehicle's booked carrier; whole_order_handover;
--   * customer_order.packing_started_at (when the first package was opened), no longer a delivery-mode lock;
--   * the money ledger's carrier side - payment.receivable.collector = CARRIER, CARRIER_REMITTANCE transfers and the
--     legacy payment.cod_remittance tables: a carrier may still owe what it collected before today, and its remittance
--     still pays that receivable. The ledger is replaced by the contract's instalments later in the rescope.
--
-- Data:
--   * a parcel load still LOADING is called off (CANCELLED): its shipments leave the manifest and its parcels the
--     vehicle, and wait at dispatch for a load plan like any other READY_TO_DISPATCH shipment;
--   * a shipment waiting for dispatch forgets the carrier chosen for it in advance: its vehicle's booking names one;
--   * COD a carrier still owes is billed to the carrier now, as delivery would have billed it: a CARRIER receivable
--     (DEPOSIT_BALANCE, due today) for each shipment - or whole-order handover - of an ON_DELIVERY order handed over and
--     not yet delivered or returned, and for each delivery announced but not yet billed (an OrderDelivered of an
--     ON_DELIVERY order still waiting for payment's listener). Delivery then finds the receivable and bills nothing more:
--     the customer, who pays the carrier, never owes it, and the carrier's remittance pays it as before. A later
--     shipment of such an order leaves once the money in, the remittance included, covers it (BEFORE_DELIVERY);
--   * an ON_DELIVERY order and an ON_DELIVERY customer's terms become BEFORE_DELIVERY;
--   * ShipmentStatusChanged lost its deliveryMode: publications not yet completed have the field cut out of their text
--     (archived first), keeping Jackson's compact form - the registry finds a publication by that exact text - so none
--     carries a field the record no longer has.
--
-- Order, so no step meets a reference a later step removes:
--   1. archive;
--   2. data: live parcel loads called off, COD a carrier still owes billed to it, waiting shipments' carriers
--      cleared, ON_DELIVERY replaced;
--   3. the functions that read a delivery mode are rewritten, the manifest's mode trigger and index dropped;
--   4. CHECKs naming the columns, then the columns, then the delivery-mode history table; CHECKs recreated;
--   5. event publications; 6. permissions, advancing app_role.version of every role that held one.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE live_parcel_loads ON COMMIT DROP AS
SELECT id FROM fulfillment.dispatch_load WHERE mode = 'PARCEL' AND status NOT IN ('DISPATCHED', 'CANCELLED');

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'fulfillment.shipment', s.id,
       jsonb_build_object('id', s.id, 'delivery_mode', s.delivery_mode, 'cod_amount', s.cod_amount,
                          'label_cod_amount', s.label_cod_amount, 'label_printed_at', s.label_printed_at,
                          'status', s.status, 'carrier_id', s.carrier_id)
FROM fulfillment.shipment s
UNION ALL
SELECT 'fulfillment.dispatch_load', l.id,
       jsonb_build_object('id', l.id, 'mode', l.mode, 'cod_total', l.cod_total, 'status', l.status,
                          'shipment_count', l.shipment_count, 'package_count', l.package_count)
FROM fulfillment.dispatch_load l
UNION ALL
SELECT 'fulfillment.dispatch_load_shipment', m.id,
       jsonb_build_object('id', m.id, 'mode', m.mode, 'label_cod_amount', m.label_cod_amount,
                          'cod_amount', m.cod_amount, 'active', m.active)
FROM fulfillment.dispatch_load_shipment m
UNION ALL
SELECT 'fulfillment.dispatch_load_package', p.id, to_jsonb(p)
FROM fulfillment.dispatch_load_package p
WHERE p.active AND p.load_id IN (SELECT id FROM live_parcel_loads)
UNION ALL
SELECT 'ordering.customer_order', o.id,
       jsonb_build_object('id', o.id, 'delivery_mode', o.delivery_mode, 'cod_amount', o.cod_amount,
                          'balance_policy', o.balance_policy)
FROM ordering.customer_order o
UNION ALL
SELECT 'ordering.order_shipment', s.id,
       jsonb_build_object('id', s.id, 'cod_amount', s.cod_amount, 'delivery_mode', s.delivery_mode)
FROM ordering.order_shipment s
UNION ALL
SELECT 'ordering.order_delivery_mode_change', c.id, to_jsonb(c)
FROM ordering.order_delivery_mode_change c
UNION ALL
SELECT 'customer.credit_profile.deposit_balance_policy', c.id,
       jsonb_build_object('id', c.id, 'deposit_balance_policy', c.deposit_balance_policy)
FROM customer.credit_profile c WHERE c.deposit_balance_policy = 'ON_DELIVERY'
-- The permissions and their grants (step 6).
UNION ALL
SELECT 'identity.permission', p.id, to_jsonb(p) FROM identity.permission p
WHERE p.code LIKE 'fulfillment-load-cancellations:%' OR p.code LIKE 'sales-order-delivery-modes:%'
   OR p.code = 'fulfillment-dispatch:CREATE'
UNION ALL
SELECT 'identity.role_permission', rp.id, to_jsonb(rp) FROM identity.role_permission rp
WHERE rp.permission_id IN (SELECT id FROM identity.permission
                            WHERE code LIKE 'fulfillment-load-cancellations:%' OR code LIKE 'sales-order-delivery-modes:%'
                               OR code = 'fulfillment-dispatch:CREATE');

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300) and '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400), as they were. Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Data
-- ---------------------------------------------------------------------------------------------
-- A parcel load still loading is called off: parcels off the vehicle before their shipments leave the manifest
-- (fk_dispatch_load_package_shipment), then the load. Its counters follow what is left on it, as the application keeps
-- them.
UPDATE fulfillment.dispatch_load_package
SET active = FALSE, removed_at = NOW()
WHERE active AND load_id IN (SELECT id FROM live_parcel_loads);
UPDATE fulfillment.dispatch_load_shipment
SET active = FALSE, removed_at = NOW()
WHERE active AND load_id IN (SELECT id FROM live_parcel_loads);
UPDATE fulfillment.dispatch_load
SET status = 'CANCELLED', cancelled_at = NOW(),
    cancel_reason = 'Parcel shipping was removed (V20261108000400): its shipments wait for a load plan',
    shipment_count = 0, package_count = 0
WHERE id IN (SELECT id FROM live_parcel_loads);

-- COD a carrier still owes, billed to it now (before the policies change below): goods handed over and not yet
-- delivered or returned...
INSERT INTO payment.receivable (id, order_id, customer_id, kind, collector, amount, currency, issued_at, due_date,
                                shipment_id, order_number)
SELECT gen_random_uuid(), o.id, o.customer_id, 'DEPOSIT_BALANCE', 'CARRIER', s.cod_amount, o.currency, NOW(),
       CURRENT_DATE, s.id, o.order_number
FROM ordering.order_shipment s
JOIN ordering.customer_order o ON o.id = s.order_id
WHERE o.balance_policy = 'ON_DELIVERY' AND o.customer_id IS NOT NULL
  AND s.status IN ('HANDED_OVER', 'DELIVERY_FAILED') AND s.cod_amount > 0
  AND NOT EXISTS (SELECT 1 FROM payment.receivable r WHERE r.order_id = o.id AND r.shipment_id = s.id);
-- ... an order handed over whole (no shipment recorded on it) ...
INSERT INTO payment.receivable (id, order_id, customer_id, kind, collector, amount, currency, issued_at, due_date,
                                shipment_id, order_number)
SELECT gen_random_uuid(), o.id, o.customer_id, 'DEPOSIT_BALANCE', 'CARRIER', o.cod_amount, o.currency, NOW(),
       CURRENT_DATE, NULL, o.order_number
FROM ordering.customer_order o
WHERE o.balance_policy = 'ON_DELIVERY' AND o.customer_id IS NOT NULL
  AND o.status IN ('SHIPPED', 'DELIVERY_FAILED') AND o.cod_amount > 0
  AND NOT EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = o.id)
  AND NOT EXISTS (SELECT 1 FROM payment.receivable r WHERE r.order_id = o.id AND r.shipment_id IS NULL);
-- ... and a delivery announced that payment has not billed yet: what ReceivableService would have billed for it.
INSERT INTO payment.receivable (id, order_id, customer_id, kind, collector, amount, currency, issued_at, due_date,
                                shipment_id, order_number)
SELECT gen_random_uuid(), d.order_id, d.customer_id, 'DEPOSIT_BALANCE', 'CARRIER', d.owed, d.currency, d.delivered_at,
       (d.delivered_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, d.shipment_id, d.order_number
FROM (SELECT DISTINCT ON ((j ->> 'orderId'), coalesce(j ->> 'shipmentId', ''))
             (j ->> 'orderId')::uuid AS order_id, (j ->> 'customerId')::uuid AS customer_id,
             (j ->> 'shipmentId')::uuid AS shipment_id, j ->> 'orderNumber' AS order_number,
             j ->> 'currency' AS currency,
             coalesce((j ->> 'billedAmount')::numeric,
                      (j ->> 'orderTotal')::numeric - coalesce((j ->> 'paidAmount')::numeric, 0)) AS owed,
             CASE WHEN jsonb_typeof(j -> 'deliveredAt') = 'number' THEN to_timestamp((j ->> 'deliveredAt')::numeric)
                  ELSE (j ->> 'deliveredAt')::timestamptz END AS delivered_at
      FROM (SELECT e.serialized_event::jsonb AS j FROM event_publication e
             WHERE e.event_type = 'com.stockflow.contracts.OrderDelivered' AND e.completion_date IS NULL
               AND e.listener_id LIKE 'com.stockflow.payment.%') pending
      WHERE j ->> 'balancePolicy' = 'ON_DELIVERY' AND j ->> 'paymentTerm' = 'DEPOSIT'
        AND j ->> 'customerId' IS NOT NULL) d
WHERE d.owed > 0
  AND EXISTS (SELECT 1 FROM ordering.customer_order o WHERE o.id = d.order_id)
  AND NOT EXISTS (SELECT 1 FROM payment.receivable r
                   WHERE r.order_id = d.order_id AND r.shipment_id IS NOT DISTINCT FROM d.shipment_id);

-- The carrier chosen in advance for a shipment still waiting: the vehicle's booking names its carrier now.
UPDATE fulfillment.shipment SET carrier_id = NULL WHERE status = 'READY_TO_DISPATCH' AND carrier_id IS NOT NULL;

-- Nothing is collected on delivery. The COD an order fixed goes in the same statement (archived above):
-- ck_order_cod_amount, dropped in step 4, allows one only on an ON_DELIVERY order.
UPDATE ordering.customer_order SET balance_policy = 'BEFORE_DELIVERY', cod_amount = 0
WHERE balance_policy = 'ON_DELIVERY';
UPDATE customer.credit_profile SET deposit_balance_policy = 'BEFORE_DELIVERY'
WHERE deposit_balance_policy = 'ON_DELIVERY';


-- ---------------------------------------------------------------------------------------------
-- 3. Functions, triggers and indexes that read a delivery mode
-- ---------------------------------------------------------------------------------------------
-- A manifest line: the load open, the shipment waiting at the load's warehouse, on no load of another plan. The carrier
-- chosen in advance and the load's mode are no longer compared: there is neither.
CREATE OR REPLACE FUNCTION fulfillment.check_load_shipment()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    s fulfillment.shipment%ROWTYPE;
    l fulfillment.dispatch_load%ROWTYPE;
    plan_id UUID;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO l FROM fulfillment.dispatch_load WHERE id = NEW.load_id;
    IF l.status NOT IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING') THEN
        RAISE EXCEPTION 'load % is %', l.code, l.status USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    IF s.status <> 'READY_TO_DISPATCH' THEN
        RAISE EXCEPTION 'shipment % is %, not waiting for dispatch', s.code, s.status USING ERRCODE = 'check_violation';
    END IF;
    IF s.warehouse_id <> l.warehouse_id THEN
        RAISE EXCEPTION 'shipment % does not belong on load %', s.code, l.code USING ERRCODE = 'check_violation';
    END IF;
    SELECT v.load_plan_id INTO plan_id FROM fulfillment.load_plan_vehicle v WHERE v.id = l.load_plan_vehicle_id;
    IF EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment o
                 JOIN fulfillment.dispatch_load ol ON ol.id = o.load_id
                 LEFT JOIN fulfillment.load_plan_vehicle ov ON ov.id = ol.load_plan_vehicle_id
                WHERE o.shipment_id = NEW.shipment_id AND o.active AND o.load_id <> NEW.load_id
                  AND ov.load_plan_id IS DISTINCT FROM plan_id) THEN
        RAISE EXCEPTION 'shipment % is on a load of another plan than load %', s.code, l.code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

-- A parcel in a plan: sealed and measured, of a shipment waiting for dispatch at the plan's warehouse.
CREATE OR REPLACE FUNCTION fulfillment.check_load_plan_package()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    p fulfillment.package%ROWTYPE;
    s fulfillment.shipment%ROWTYPE;
    plan fulfillment.load_plan%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    SELECT * INTO plan FROM fulfillment.load_plan WHERE id = NEW.load_plan_id;
    IF p.status <> 'SEALED' OR p.weight_kg IS NULL OR p.weight_kg <= 0 OR p.length_cm IS NULL OR p.length_cm <= 0
        OR p.width_cm IS NULL OR p.width_cm <= 0 OR p.height_cm IS NULL OR p.height_cm <= 0
        OR p.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: package % is not a sealed, measured parcel of its shipment',
            p.label_code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    IF s.status <> 'READY_TO_DISPATCH' OR s.warehouse_id <> plan.warehouse_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: shipment % is not waiting for dispatch at the plan''s warehouse',
            s.code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    RETURN NEW;
END
$function$;

-- The manifest line copied its load's mode, and a parcel manifest held a shipment alone; neither exists any more.
DROP TRIGGER tg_dispatch_load_shipment_mode ON fulfillment.dispatch_load_shipment;
DROP FUNCTION fulfillment.fill_load_shipment_mode();
DROP INDEX fulfillment.uk_dispatch_load_shipment_active;


-- ---------------------------------------------------------------------------------------------
-- 4. CHECKs, columns, the delivery-mode history
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.shipment
    DROP CONSTRAINT ck_shipment_delivery_mode,
    DROP CONSTRAINT ck_shipment_ftl_no_cod,
    DROP CONSTRAINT ck_shipment_label_cod,
    DROP CONSTRAINT ck_shipment_amounts,
    DROP COLUMN delivery_mode,
    DROP COLUMN cod_amount,
    DROP COLUMN label_cod_amount,
    DROP COLUMN label_printed_at,
    ADD CONSTRAINT ck_shipment_package_count CHECK (package_count > 0);

ALTER TABLE fulfillment.dispatch_load
    DROP CONSTRAINT ck_dispatch_load_mode,
    DROP CONSTRAINT ck_dispatch_load_parcel_flow,
    DROP CONSTRAINT ck_dispatch_load_ftl_vehicle,
    DROP CONSTRAINT ck_dispatch_load_ftl_booked,
    DROP CONSTRAINT ck_dispatch_load_totals,
    DROP COLUMN mode,
    DROP COLUMN cod_total,
    -- Every live load is a load plan's vehicle; only history (dispatched or cancelled parcel loads) has none.
    ADD CONSTRAINT ck_dispatch_load_vehicle
        CHECK (load_plan_vehicle_id IS NOT NULL OR status IN ('DISPATCHED', 'CANCELLED')),
    -- A plan's vehicle is booked before it loads; parcel loads of the past never were.
    ADD CONSTRAINT ck_dispatch_load_vehicle_booked
        CHECK (load_plan_vehicle_id IS NULL OR status NOT IN ('VEHICLE_BOOKED', 'LOADING', 'DISPATCHED')
               OR booked_at IS NOT NULL),
    ADD CONSTRAINT ck_dispatch_load_totals CHECK (shipment_count >= 0 AND package_count >= 0);

ALTER TABLE fulfillment.dispatch_load_shipment
    DROP CONSTRAINT ck_dispatch_load_shipment_cod,
    DROP CONSTRAINT ck_dispatch_load_shipment_mode,
    DROP COLUMN mode,
    DROP COLUMN label_cod_amount,
    DROP COLUMN cod_amount;

ALTER TABLE ordering.customer_order
    DROP CONSTRAINT ck_order_delivery_mode,
    DROP CONSTRAINT ck_order_ftl_no_cod,
    DROP CONSTRAINT ck_order_cod_amount,
    DROP CONSTRAINT ck_order_balance_policy,
    DROP COLUMN delivery_mode,
    DROP COLUMN cod_amount,
    ADD CONSTRAINT ck_order_balance_policy
        CHECK ((payment_term = 'DEPOSIT') = (balance_policy IS NOT NULL)
               AND (balance_policy IS NULL OR balance_policy IN ('BEFORE_DELIVERY', 'ON_CREDIT')));
COMMENT ON COLUMN ordering.customer_order.packing_started_at IS
    'When fulfillment opened the order''s first package. No longer locks a delivery mode (V20261108000400).';

ALTER TABLE ordering.order_shipment
    DROP CONSTRAINT ck_order_shipment_cod_amount,
    DROP CONSTRAINT ck_order_shipment_delivery_mode,
    DROP COLUMN cod_amount,
    DROP COLUMN delivery_mode;

-- Append-only, its rows archived above; dropped whole with its trigger.
DROP TABLE ordering.order_delivery_mode_change;

ALTER TABLE customer.credit_profile
    DROP CONSTRAINT ck_credit_profile_balance_policy,
    ADD CONSTRAINT ck_credit_profile_balance_policy
        CHECK (allow_deposit = (deposit_balance_policy IS NOT NULL)
               AND (deposit_balance_policy IS NULL OR deposit_balance_policy IN ('BEFORE_DELIVERY', 'ON_CREDIT'))
               AND (deposit_balance_policy IS DISTINCT FROM 'ON_CREDIT' OR allow_credit));


-- ---------------------------------------------------------------------------------------------
-- 5. Event publications
-- ---------------------------------------------------------------------------------------------
-- ShipmentStatusChanged lost deliveryMode; a publication still waiting would be read back into the new record. The
-- field is cut out of the text rather than through jsonb, which would reorder the keys and add spaces: the registry
-- marks a publication complete by finding its exact serialized text.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'public.event_publication', e.id, to_jsonb(e)
FROM event_publication e
WHERE e.event_type = 'com.stockflow.contracts.ShipmentStatusChanged' AND e.completion_date IS NULL
  AND e.serialized_event LIKE '%"deliveryMode":%';
UPDATE event_publication
SET serialized_event = regexp_replace(serialized_event, ',"deliveryMode":(null|"[^"]*")', '', 'g')
WHERE event_type = 'com.stockflow.contracts.ShipmentStatusChanged'
  AND completion_date IS NULL
  AND serialized_event LIKE '%"deliveryMode":%';


-- ---------------------------------------------------------------------------------------------
-- 6. Permissions
-- ---------------------------------------------------------------------------------------------
-- Opening a load by hand (fulfillment-dispatch:CREATE), cancelling one plainly and choosing a delivery mode are gone.
-- Every role that held one moves its version, so cached grants are dropped at once (ADR-0008).
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id
      AND (p.code LIKE 'fulfillment-load-cancellations:%' OR p.code LIKE 'sales-order-delivery-modes:%'
           OR p.code = 'fulfillment-dispatch:CREATE')
    RETURNING rp.role_id
)
UPDATE identity.app_role r SET version = version + 1
WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission
WHERE code LIKE 'fulfillment-load-cancellations:%' OR code LIKE 'sales-order-delivery-modes:%'
   OR code = 'fulfillment-dispatch:CREATE';
