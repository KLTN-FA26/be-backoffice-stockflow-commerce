-- =============================================================================
-- The workshop dashboard counts activity in a period (user decision QA3, 2026-10-13): units actually printed and
-- scrapped in the period, whenever their production order was created. Scrap already has its own timestamped rows
-- (production_scrap, one unit counted once whether spoilt on the press, failed QC or written off when a job was
-- stopped). Printing had none: production_order.printed_quantity is a running total. production_print_run is one
-- row per recorded run (or the run a stop recorded), with its printed and scrap counts and when.
-- A subcontractor's goods are counted from production_subcontract_receipt (received_at), not here.
--
-- Backfill, for databases with workshop history: each PRINTING -> QC history row says "Printed N, scrap M" with its
-- time; whatever an order printed beyond those (the run recorded by a stop) is one row at its cancellation time.
-- =============================================================================

CREATE TABLE production.production_print_run
(
    id                  UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    printed_quantity    INTEGER       NOT NULL,
    scrap_quantity      INTEGER       NOT NULL,
    recorded_at         TIMESTAMPTZ   NOT NULL,
    recorded_by         UUID,

    CONSTRAINT pk_production_print_run PRIMARY KEY (id),
    CONSTRAINT fk_production_print_run_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_print_run_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_print_run_quantities
        CHECK (printed_quantity > 0 AND scrap_quantity >= 0 AND scrap_quantity <= printed_quantity)
);

CREATE INDEX ix_production_print_run_order ON production.production_print_run (production_order_id);
CREATE INDEX ix_production_print_run_recorded ON production.production_print_run (recorded_at);
CREATE INDEX ix_production_scrap_recorded ON production.production_scrap (recorded_at);
CREATE INDEX ix_production_subcontract_receipt_received
    ON production.production_subcontract_receipt (received_at);

INSERT INTO production.production_print_run (id, production_order_id, printed_quantity, scrap_quantity, recorded_at,
                                            recorded_by)
SELECT gen_random_uuid(), h.production_order_id,
       CAST(substring(h.reason FROM '^Printed ([0-9]+), scrap') AS INTEGER),
       CAST(substring(h.reason FROM '^Printed [0-9]+, scrap ([0-9]+)') AS INTEGER),
       h.changed_at, h.changed_by
  FROM production.production_order_status_history h
 WHERE h.from_status = 'PRINTING' AND h.to_status = 'QC' AND h.reason ~ '^Printed [0-9]+, scrap [0-9]+'
   AND CAST(substring(h.reason FROM '^Printed ([0-9]+), scrap') AS INTEGER) > 0;

INSERT INTO production.production_print_run (id, production_order_id, printed_quantity, scrap_quantity, recorded_at,
                                            recorded_by)
SELECT gen_random_uuid(), o.id, o.printed_quantity - r.recorded - s.received, 0, o.cancelled_at, NULL
  FROM production.production_order o
 CROSS JOIN LATERAL (SELECT COALESCE(SUM(printed_quantity), 0) AS recorded FROM production.production_print_run
                      WHERE production_order_id = o.id) r
 CROSS JOIN LATERAL (SELECT COALESCE(SUM(quantity), 0) AS received FROM production.production_subcontract_receipt
                      WHERE production_order_id = o.id) s
 WHERE o.status = 'CANCELLED' AND o.cancelled_at IS NOT NULL
   AND o.printed_quantity - r.recorded - s.received > 0;
