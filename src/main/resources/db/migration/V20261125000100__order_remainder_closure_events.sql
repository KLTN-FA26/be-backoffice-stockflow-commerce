-- Owner decision WF4-CLOSE-HISTORY-1113 / WF4-CLOSE-HIST-SCHEMA-1115 (kltn-docs 17 §4.8 step 33): the history of an order
-- shows a row "Đóng phần còn lại" with the reason every time Sales close the rest of a partly fulfilled order, even
-- when the closure leaves the order's status where it was (the cartons checked and labelled stay on it and are
-- delivered, V20261124000700).
--
-- Why a table of its own. A closure that leaves the status alone has nothing to put in ordering.order_status_history,
-- whose rows are status changes (ck_order_status_history_change: from_status <> to_status). That constraint stays as it
-- is and no from = to row is written there. The closure is recorded like Sales' decision on partial shipments is
-- (ordering.order_partial_shipment_decision): an append-only row of its own, which GET /orders/{id}/history lists with
-- the order's status changes as an entry of its own type (REMAINDER_CLOSURE), for staff only.
--
-- closure_id is the key the closure is settled under on the contract's installments (OrderRemainderClosed.closureId),
-- so a row and its settlement can be put side by side; a retried unit of work writes one row.
--
-- No backfill. Before this table a closure was recorded nowhere but in the order's lines (units closed) and, when it
-- moved the order's status, in its status history with the reason REMAINDER_CLOSED_<code>[: note]; one that left the
-- status alone left no record of who closed it, when or why, so there is nothing to carry over that is complete, and
-- the status rows that exist stay where they are.

CREATE TABLE ordering.order_remainder_closure (
    id               uuid                     NOT NULL,
    order_id         uuid                     NOT NULL,
    closure_id       uuid                     NOT NULL,
    reason_code      varchar(32)              NOT NULL,
    note             varchar(1000),
    closed_by        uuid,
    closed_at        timestamptz              NOT NULL,
    version          bigint                   NOT NULL DEFAULT 0,
    created_at       timestamptz              NOT NULL DEFAULT now(),
    created_by       varchar(100),
    last_modified_at timestamptz,
    last_modified_by varchar(100),
    CONSTRAINT pk_order_remainder_closure PRIMARY KEY (id),
    CONSTRAINT uk_order_remainder_closure_closure UNIQUE (closure_id),
    -- The codes of CancellationReasonCode a closure may carry (Order#closeRemainder refuses REPLACEMENT_WITHDRAWN).
    CONSTRAINT ck_order_remainder_closure_reason CHECK (reason_code IN ('CUSTOMER_REQUEST', 'PAYMENT_NOT_RECEIVED',
        'PAYMENT_FAILED', 'OUT_OF_STOCK', 'SUSPECTED_FRAUD', 'CREDIT_REJECTED', 'DUPLICATE_ORDER', 'PRODUCTION_ISSUE',
        'DELIVERY_FAILED', 'OTHER')),
    CONSTRAINT ck_order_remainder_closure_other_note CHECK (reason_code <> 'OTHER' OR (note IS NOT NULL AND btrim(note) <> '')),
    CONSTRAINT ck_order_remainder_closure_note CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT fk_order_remainder_closure_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_remainder_closure_actor FOREIGN KEY (closed_by) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_order_remainder_closure_order ON ordering.order_remainder_closure (order_id, closed_at);

CREATE TRIGGER tg_order_remainder_closure_append_only BEFORE DELETE OR UPDATE ON ordering.order_remainder_closure
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

COMMENT ON TABLE ordering.order_remainder_closure IS 'Every closure of the rest of a partly fulfilled order: who, when and why. Append-only; the order''s history of closures, shown to staff beside its status changes.';
