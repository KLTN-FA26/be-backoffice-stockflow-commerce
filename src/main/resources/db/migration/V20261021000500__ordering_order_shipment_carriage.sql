-- =============================================================================
-- The customer portal's view of a shipment (G4-2a; the part of SCRUM-245/264 deferred from G3-1; kltn-docs 09 BR-05).
-- Module: order
--
-- ordering.order_shipment learns, at the handover, what the customer may read of the shipment besides its status:
-- its number, how many parcels it has, the carrier and how it travels. Fulfillment passes them with the handover
-- (OrderService.recordHandover, in the handover's transaction); nothing internal - who signed, notes - is copied.
--
-- The foreign key V20261021000100 promised: order_shipment.id is fulfillment's shipment id. Until G4-2a nothing wrote
-- order_shipment outside tests, so no row lacks its shipment; the check below names any that does before the
-- constraint would fail on it.
-- =============================================================================

ALTER TABLE ordering.order_shipment
    ADD COLUMN shipment_code VARCHAR(48),
    ADD COLUMN package_count INTEGER,
    ADD COLUMN carrier_name  VARCHAR(120),
    ADD COLUMN delivery_mode VARCHAR(16);

UPDATE ordering.order_shipment os
   SET shipment_code = s.code,
       package_count = s.package_count,
       delivery_mode = s.delivery_mode,
       carrier_name  = (SELECT c.name FROM fulfillment.carrier c WHERE c.id = s.carrier_id)
  FROM fulfillment.shipment s
 WHERE s.id = os.id;

DO
$$
DECLARE
    orphan TEXT;
BEGIN
    SELECT string_agg(os.id::text, ', ') INTO orphan
      FROM ordering.order_shipment os
     WHERE NOT EXISTS (SELECT 1 FROM fulfillment.shipment s WHERE s.id = os.id);
    IF orphan IS NOT NULL THEN
        RAISE EXCEPTION 'ordering.order_shipment rows without a fulfillment.shipment: %', orphan;
    END IF;
END
$$;

ALTER TABLE ordering.order_shipment
    ADD CONSTRAINT fk_order_shipment_shipment FOREIGN KEY (id) REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_order_shipment_package_count CHECK (package_count IS NULL OR package_count > 0),
    ADD CONSTRAINT ck_order_shipment_delivery_mode CHECK (delivery_mode IS NULL
        OR delivery_mode IN ('PARCEL', 'FULL_TRUCKLOAD'));

COMMENT ON COLUMN ordering.order_shipment.id IS 'fulfillment.shipment id (fk_order_shipment_shipment)';
