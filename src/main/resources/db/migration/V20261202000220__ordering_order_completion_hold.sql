-- =============================================================================
-- WF-05 GH-2: Sales pause an order's automatic completion while a delivery is being verified.
--
-- Why. The carrier reports a shipment delivered at 15:00; at 17:00 the customer says nothing arrived. Until the
-- warehouse manager has checked with the carrier and corrected the outcome (or confirmed it), the nightly run
-- (OrderSettlementService.complete, 17 §4.6 step 21) completes the order once the return window has passed and the
-- money is in - and a COMPLETED order's deliveries are no longer corrected (ORDER_DELIVERY_NOT_CORRECTABLE), so the
-- invoice and the receivable could no longer be put right inside the system.
--
-- Why not ON_HOLD. ON_HOLD is a status the order takes instead of its own (17 §4.9): it is set only up to PACKED, it
-- stops the fulfilment of every batch, and a delivered order put ON_HOLD would no longer read as delivered - payment
-- dates the installments after delivery from that (InstallmentOrdersAdapter.deliveredInFullAt). A completion hold
-- changes nothing about the order's status or its other batches: it is a row the completion step reads.
--
-- What this does:
--   * ordering.order_completion_hold: who paused the completion, why (DELIVERY_DISPUTED, or OTHER with a note), and
--     who released it. At most one open per order (uk_order_completion_hold_open); kept after release, as order_hold is.
--   * tg_order_completion_hold_respected: an order is not moved to COMPLETED while its completion is held - the rule
--     the service states first (OrderSettlementService.complete), stated again where no code path can pass it by.
-- No row is written.
-- =============================================================================

CREATE TABLE ordering.order_completion_hold
(
    id               UUID          NOT NULL,
    order_id         UUID          NOT NULL,
    reason           VARCHAR(32)   NOT NULL,
    note             VARCHAR(1000),
    held_at          TIMESTAMPTZ   NOT NULL,
    held_by          UUID          NOT NULL,
    released_at      TIMESTAMPTZ,
    released_by      UUID,
    release_note     VARCHAR(1000),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_completion_hold PRIMARY KEY (id),
    CONSTRAINT fk_order_completion_hold_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_order_completion_hold_held_by FOREIGN KEY (held_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_order_completion_hold_released_by FOREIGN KEY (released_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_completion_hold_reason CHECK (reason IN ('DELIVERY_DISPUTED', 'OTHER')),
    -- OTHER says what it is; a note, when there is one, says something.
    CONSTRAINT ck_order_completion_hold_note CHECK (
        (reason <> 'OTHER' OR note IS NOT NULL) AND (note IS NULL OR btrim(note) <> '')),
    -- Released by a person, with a note, after it was held - or not at all yet.
    CONSTRAINT ck_order_completion_hold_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_note IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND btrim(COALESCE(release_note, '')) <> ''
            AND released_at >= held_at))
);

CREATE UNIQUE INDEX uk_order_completion_hold_open ON ordering.order_completion_hold (order_id)
    WHERE released_at IS NULL;
CREATE INDEX ix_order_completion_hold_order ON ordering.order_completion_hold (order_id, held_at);

COMMENT ON TABLE ordering.order_completion_hold IS
    'WF-05 GH-2: an order''s automatic completion paused while a delivery is verified with the carrier. The order keeps its status and its other batches move on; only the nightly completion waits. At most one open per order.';
COMMENT ON COLUMN ordering.order_completion_hold.reason IS
    'DELIVERY_DISPUTED (the customer says a delivery reported done did not arrive) or OTHER (note required).';

-- A row of the table is what was decided, and stays: released, never deleted; what was held never changes.
CREATE FUNCTION ordering.order_completion_hold_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'completion hold % is kept, released or not', OLD.id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_completion_hold_kept';
    END IF;
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.note IS DISTINCT FROM OLD.note OR NEW.held_at IS DISTINCT FROM OLD.held_at
       OR NEW.held_by IS DISTINCT FROM OLD.held_by
       OR (OLD.released_at IS NOT NULL AND (NEW.released_at IS DISTINCT FROM OLD.released_at
                                            OR NEW.released_by IS DISTINCT FROM OLD.released_by
                                            OR NEW.release_note IS DISTINCT FROM OLD.release_note)) THEN
        RAISE EXCEPTION 'completion hold %: what was held, and its release, do not change', OLD.id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_completion_hold_kept';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_order_completion_hold_kept
    BEFORE UPDATE OR DELETE ON ordering.order_completion_hold
    FOR EACH ROW EXECUTE FUNCTION ordering.order_completion_hold_guard();

-- The order is not completed while its completion is held.
CREATE FUNCTION ordering.order_completion_hold_respected() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = 'COMPLETED' AND OLD.status IS DISTINCT FROM 'COMPLETED' AND EXISTS (
            SELECT 1 FROM ordering.order_completion_hold h WHERE h.order_id = NEW.id AND h.released_at IS NULL) THEN
        RAISE EXCEPTION 'order % is not completed while its completion is held', NEW.order_number
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_completion_hold_respected';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_order_completion_hold_respected
    BEFORE UPDATE OF status ON ordering.customer_order
    FOR EACH ROW EXECUTE FUNCTION ordering.order_completion_hold_respected();
