ALTER TABLE inventory.sku_policy ADD COLUMN last_evaluated_at timestamptz;
CREATE TABLE inventory.stock_alert (
    id uuid PRIMARY KEY,
    sku varchar(64) NOT NULL,
    kind varchar(16) NOT NULL CHECK(kind IN ('REORDER','SAFETY')),
    status varchar(16) NOT NULL CHECK(status IN ('OPEN','RESOLVED')),
    observed_qty bigint NOT NULL CHECK(observed_qty>=0),
    threshold_value integer NOT NULL CHECK(threshold_value>=0),
    opened_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolution_reason varchar(32),
    acknowledged_by uuid,
    acknowledged_at timestamptz,
    CHECK((status='RESOLVED')=(resolved_at IS NOT NULL)),
    CHECK((acknowledged_by IS NULL)=(acknowledged_at IS NULL))
);
CREATE UNIQUE INDEX uk_open_stock_alert ON inventory.stock_alert(sku,kind) WHERE status='OPEN';
CREATE INDEX ix_stock_alert_history ON inventory.stock_alert(opened_at DESC,id);
CREATE TABLE notification.inventory_alert_delivery (
    id uuid PRIMARY KEY,
    alert_id uuid NOT NULL,
    event_status varchar(16) NOT NULL CHECK(event_status IN ('OPEN','RESOLVED')),
    recipient varchar(320) NOT NULL DEFAULT '',
    subject varchar(300) NOT NULL,
    body text NOT NULL,
    status varchar(24) NOT NULL CHECK(status IN ('READY','SENDING','SENT','FAILED','BLOCKED_CONFIG','SUPERSEDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    next_attempt_at timestamptz NOT NULL,
    lease_token uuid,
    leased_until timestamptz,
    last_error varchar(200),
    created_at timestamptz NOT NULL,
    sent_at timestamptz,
    UNIQUE(alert_id,event_status,recipient),
    CHECK((status='SENDING')=(lease_token IS NOT NULL AND leased_until IS NOT NULL))
);
CREATE INDEX ix_alert_delivery_due ON notification.inventory_alert_delivery(next_attempt_at,id)
    WHERE status IN ('READY','BLOCKED_CONFIG','SENDING');
