-- A control row serializes dispatch with cancellation/recovery. Modulith remains the only queue.
CREATE TABLE notification.po_delivery_control (
    purchase_order_id UUID PRIMARY KEY,
    generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
    suppressed BOOLEAN NOT NULL DEFAULT FALSE
);
ALTER TABLE notification.delivery_log ADD COLUMN delivery_generation INTEGER NOT NULL DEFAULT 0
    CHECK (delivery_generation >= 0);
CREATE INDEX ix_delivery_log_generation ON notification.delivery_log(operation_reference, delivery_generation);
-- Seed suppression for historical publications before the new worker starts.
INSERT INTO notification.po_delivery_control(purchase_order_id, suppressed)
SELECT id, status = 'CANCELLED' OR supplier_confirmation_status IN ('CONFIRMED','REJECTED')
FROM procurement.purchase_order po
WHERE status = 'CANCELLED' OR supplier_confirmation_status IN ('CONFIRMED','REJECTED')
   OR EXISTS (SELECT 1 FROM notification.delivery_log l WHERE l.operation_reference = 'purchase-order:' || po.id);

CREATE TABLE procurement.po_delivery_decision (
    id UUID PRIMARY KEY,
    purchase_order_id UUID NOT NULL REFERENCES procurement.purchase_order(id),
    generation INTEGER NOT NULL CHECK (generation >= 0),
    previous_expected_at DATE,
    expected_at DATE,
    reason VARCHAR(1000),
    reconciled BOOLEAN NOT NULL,
    acknowledge_past_due BOOLEAN NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL','API')),
    recipient VARCHAR(500) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    UNIQUE (purchase_order_id, generation),
    CHECK (generation = 0 OR (reconciled AND NULLIF(btrim(reason), '') IS NOT NULL)),
    CHECK (previous_expected_at IS NOT DISTINCT FROM expected_at OR NULLIF(btrim(reason), '') IS NOT NULL)
);
CREATE INDEX ix_po_delivery_decision_history ON procurement.po_delivery_decision(purchase_order_id, requested_at, id);
CREATE FUNCTION procurement.guard_sent_delivery_date() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status NOT IN ('DRAFT','APPROVED') AND NEW.expected_at IS DISTINCT FROM OLD.expected_at THEN
        RAISE EXCEPTION 'Cannot amend the delivery date of a sent purchase order' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status = 'SENT' AND
       (NEW.sent_at IS NULL OR NEW.expected_at IS NULL OR NEW.expected_at < (NEW.sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) THEN
        RAISE EXCEPTION 'Confirm the delivery date before sending' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_sent_delivery_date BEFORE UPDATE ON procurement.purchase_order
    FOR EACH ROW EXECUTE FUNCTION procurement.guard_sent_delivery_date();
