-- =============================================================================
-- WF4-F2C (owner decision 2026-11-28; kltn-docs 08 §4.2 step 4): a carton removed at the check station is told to the
-- order's history, whether the order was held for Sales (partial shipment not allowed) or goes on as a partial shipment
-- (already allowed). The removal moves no status, so ordering.order_status_history (ck_order_status_history_change) has
-- no row for it; it is an append-only row of its own, as ordering.order_refund_void is, listed for staff by
-- GET /orders/{id}/history as an entry of type CARTON_REMOVAL. One row per package: a request handled twice writes one.
-- package_id carries no foreign key: the row is history and outlives the package's own life.
-- =============================================================================

CREATE TABLE ordering.order_carton_removal (
    id                UUID         NOT NULL,
    order_id          UUID         NOT NULL,
    package_id        UUID         NOT NULL,
    lpn               VARCHAR(30)  NOT NULL,
    reason            VARCHAR(30)  NOT NULL,
    note              VARCHAR(500),
    continues_partial BOOLEAN      NOT NULL,
    removed_by        UUID,
    removed_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_order_carton_removal PRIMARY KEY (id),
    CONSTRAINT uk_order_carton_removal_package UNIQUE (package_id),
    CONSTRAINT ck_order_carton_removal_note CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT fk_order_carton_removal_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_order_carton_removal_actor FOREIGN KEY (removed_by) REFERENCES identity.app_user (id)
);
CREATE INDEX ix_order_carton_removal_order ON ordering.order_carton_removal (order_id, removed_at);

CREATE TRIGGER tg_order_carton_removal_append_only BEFORE DELETE OR UPDATE ON ordering.order_carton_removal
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

COMMENT ON TABLE ordering.order_carton_removal IS
    'WF4-F2C: a carton removed at the check station, with whether the order goes on as a partial shipment. Shown to '
    'staff in the order''s history (CARTON_REMOVAL). Append-only.';
