ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_status;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
    ('DRAFT','PENDING_PAYMENT','PAID','IN_FULFILMENT','ON_HOLD','SHIPPED','DELIVERED','COMPLETED','CANCELLED','RETURNED'));

CREATE TABLE ordering.order_hold (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES ordering.customer_order(id),
    reason VARCHAR(64) NOT NULL,
    raised_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolved_by UUID,
    resolution_note VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),
    CONSTRAINT ck_order_hold_resolution CHECK
        ((resolved_at IS NULL AND resolved_by IS NULL AND resolution_note IS NULL) OR
         (resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution_note IS NOT NULL))
);
CREATE UNIQUE INDEX uk_order_active_hold ON ordering.order_hold(order_id) WHERE resolved_at IS NULL;
