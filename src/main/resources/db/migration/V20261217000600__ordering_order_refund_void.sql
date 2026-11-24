-- =============================================================================
-- WF-05 BE-52 (M-11): a refund record voided while a further claim of the order is open - or its goods are back -
-- moves no status (the claim decides where the order goes), so ordering.order_status_history, whose rows are status
-- changes (ck_order_status_history_change), had nothing to say that it happened. The void is recorded here, an
-- append-only row of its own, and GET /orders/{id}/history lists it for staff as an entry of type REFUND_VOIDED - as
-- ordering.order_remainder_closure does for a closure that left the status alone.
--
-- A void that does move the order (REFUNDED / PARTIALLY_REFUNDED back to waiting) keeps its status row
-- (REFUND_VOIDED: <reason>) and writes nothing here. One row per voided record: an event handled twice writes one.
-- =============================================================================

CREATE TABLE ordering.order_refund_void (
    id               UUID          NOT NULL,
    order_id         UUID          NOT NULL,
    refund_record_id UUID          NOT NULL,
    reason           VARCHAR(500),
    voided_by        UUID,
    voided_at        TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_order_refund_void PRIMARY KEY (id),
    CONSTRAINT uk_order_refund_void_record UNIQUE (refund_record_id),
    CONSTRAINT ck_order_refund_void_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT fk_order_refund_void_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_order_refund_void_record FOREIGN KEY (refund_record_id) REFERENCES payment.refund_record (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_order_refund_void_actor FOREIGN KEY (voided_by) REFERENCES identity.app_user (id)
);
CREATE INDEX ix_order_refund_void_order ON ordering.order_refund_void (order_id, voided_at);

CREATE TRIGGER tg_order_refund_void_append_only BEFORE DELETE OR UPDATE ON ordering.order_refund_void
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

COMMENT ON TABLE ordering.order_refund_void IS
    'WF-05 BE-52: a refund record of the order voided while a claim of it is open, which moved no status. Shown to '
    'staff in the order''s history (REFUND_VOIDED). Append-only.';
