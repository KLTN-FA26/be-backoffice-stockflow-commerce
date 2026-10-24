-- =============================================================================
-- SCRUM-243 / SCRUM-260 (GitHub #192): return requests ("RMA", đổi trả) get a writer.
-- Module: order (schema ordering), plus the grants of the new endpoints (identity).
-- Docs: 03-state-machines §12 (rewritten with this change) and §9 (RETURN_REQUESTED, RETURNED, REFUNDED,
-- PARTIALLY_REFUNDED); 04c F-RMA; 05 BR-RMA-001/002, BR-ORD-005; 06 OQ-07, OQ-12; B2B plan 2026-10-06 (SCRUM-243
-- narrowed: returns for production defects only).
--
-- ordering.return_request and return_request_line were created by V20260928005400 and never written: no code
-- inserted into them, and no seed or carry-over migration did. They are reshaped here for the flow as built, and the
-- migration refuses to run on a database where they hold rows, rather than guessing what such rows meant.
--
-- 1. return_request: the statuses of the flow (REQUESTED → AWAITING_RETURN → RECEIVED → INSPECTED →
--    SETTLEMENT_PENDING → RESOLVED; REJECTED, CANCELLED, EXPIRED), the reasons of a defect claim, the resolution the
--    customer asked for, who opened it through which channel, the request id that makes opening idempotent, the
--    review (approved or rejected, goods to come back by a deadline or not), the receipt, the inspection, the
--    resolution and its four-eyes settlement, what payment booked, the replacement order and the VAT note. refund_id
--    goes: a return is refunded through several refund rows (one per payment it is placed on), which name the return
--    themselves (V20261025020100).
-- 2. return_request_line: the order line's SKU, price and whether it was printed to the customer's design, snapshot
--    at opening; approved, received (and what SKU arrived), accepted and rejected units; where the received units
--    went (back on sale, scrapped, back to the customer) with the inventory adjustments asked for; and how the
--    accepted units are made good (replaced or credited). The old disposition column goes: one line's units often
--    go three ways.
-- 3. check_return_request_line is rewritten: a return claims at most the units of the line that were delivered
--    (ordered less closed), counting what the line's other returns still claim, and a line has one open return at a
--    time.
-- 4. Evidence (photos), receipt discrepancies and the status history: append-only children of a return.
-- 5. RMA numbers per day, as order numbers are.
-- 6. customer_order.replaces_return_request_id: the free order a return's replacement is shipped as, cancelled only by
--    withdrawing it through its return (cancellation reason REPLACEMENT_WITHDRAWN), which credits the units instead.
-- 7. Grants (ADR-0008: every role that gains a row is advanced).
--
-- Four eyes on anything of value (business review 2026-10-25): a claim Sales opened is approved by someone else, every
-- resolution - money or free replacement goods - and the withdrawal of a replacement by someone other than who
-- proposed it. Goods are counted in parcel by parcel, also after the deadline or a withdrawal (they are physically
-- here); rejected units are the customer's goods (back to them, or scrapped with their consent); only plain stock QC
-- vouched for goes back on sale.
--
-- Schema owner (Tú): please review - written in the range reserved for this branch (V20261025020000-029900).
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ordering.return_request) OR EXISTS (SELECT 1 FROM ordering.return_request_line) THEN
        RAISE EXCEPTION 'V20261025020000: ordering.return_request holds rows; they predate the return flow and '
            'need a person to map them before this migration can run';
    END IF;
END $$;

-- ---- 1. return_request ----------------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request
    DROP CONSTRAINT ck_return_request_status,
    DROP CONSTRAINT ck_return_request_reason,
    DROP CONSTRAINT ck_return_request_decided,
    DROP CONSTRAINT ck_return_request_rejected,
    DROP CONSTRAINT ck_return_request_received,
    DROP CONSTRAINT ck_return_request_refunded,
    DROP CONSTRAINT fk_return_request_refund;
ALTER TABLE ordering.return_request DROP COLUMN refund_id;

ALTER TABLE ordering.return_request
    ALTER COLUMN status TYPE VARCHAR(24),
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN customer_id SET NOT NULL,
    ADD COLUMN order_number            VARCHAR(32)    NOT NULL,
    ADD COLUMN request_id              UUID           NOT NULL,
    ADD COLUMN channel                 VARCHAR(16)    NOT NULL,
    ADD COLUMN requested_by            UUID           NOT NULL,
    ADD COLUMN requested_resolution    VARCHAR(16)    NOT NULL,
    ADD COLUMN currency                VARCHAR(3)     NOT NULL DEFAULT 'VND',
    ADD COLUMN decision_note           VARCHAR(1000),
    ADD COLUMN return_required         BOOLEAN,
    ADD COLUMN return_deadline         TIMESTAMPTZ,
    ADD COLUMN received_by             UUID,
    ADD COLUMN receiving_location_code VARCHAR(64),
    ADD COLUMN receipt_note            VARCHAR(1000),
    -- How many parcels were counted in: a B2B customer often ships one return in several consignments.
    ADD COLUMN parcel_count            INTEGER        NOT NULL DEFAULT 0,
    -- Sales accepted the claim outside the return window, and why (back office only).
    ADD COLUMN window_override_reason  VARCHAR(500),
    ADD COLUMN inspected_by            UUID,
    ADD COLUMN inspected_at            TIMESTAMPTZ,
    ADD COLUMN inspection_note         VARCHAR(1000),
    ADD COLUMN money_mode              VARCHAR(16),
    ADD COLUMN settlement_amount       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN proposed_by             UUID,
    ADD COLUMN proposed_at             TIMESTAMPTZ,
    ADD COLUMN resolution_note         VARCHAR(1000),
    ADD COLUMN settled_by              UUID,
    ADD COLUMN settled_at              TIMESTAMPTZ,
    ADD COLUMN refunded_amount         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN credited_amount         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN offset_amount           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN credit_transfer_id      UUID,
    ADD COLUMN replacement_order_id    UUID,
    ADD COLUMN vat_adjustment_required BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN cancelled_by            UUID,
    ADD COLUMN cancellation_reason     VARCHAR(500),
    ADD COLUMN closed_at               TIMESTAMPTZ,
    -- Withdrawing the free replacement order of a resolved return for credit: proposed, then approved by another
    -- person (or sent back). The replaced units' value, set off against the receivables or kept as credit.
    ADD COLUMN withdrawal_status             VARCHAR(16),
    ADD COLUMN withdrawal_reason             VARCHAR(500),
    ADD COLUMN withdrawal_amount             NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN withdrawal_proposed_by        UUID,
    ADD COLUMN withdrawal_proposed_at        TIMESTAMPTZ,
    ADD COLUMN withdrawal_decided_by         UUID,
    ADD COLUMN withdrawal_decided_at         TIMESTAMPTZ,
    ADD COLUMN withdrawal_decision_note      VARCHAR(500),
    ADD COLUMN withdrawal_offset_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN withdrawal_credited_amount    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN withdrawal_credit_transfer_id UUID;

ALTER TABLE ordering.return_request
    ADD CONSTRAINT uk_return_request_request UNIQUE (request_id),
    ADD CONSTRAINT fk_return_request_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_received_by FOREIGN KEY (received_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_inspected_by FOREIGN KEY (inspected_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_proposed_by FOREIGN KEY (proposed_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_settled_by FOREIGN KEY (settled_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_withdrawal_proposed_by FOREIGN KEY (withdrawal_proposed_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_withdrawal_decided_by FOREIGN KEY (withdrawal_decided_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_return_request_withdrawal_transfer FOREIGN KEY (withdrawal_credit_transfer_id)
        REFERENCES payment.customer_transfer (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_receiving_location FOREIGN KEY (receiving_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_credit_transfer FOREIGN KEY (credit_transfer_id)
        REFERENCES payment.customer_transfer (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_replacement_order FOREIGN KEY (replacement_order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_return_request_status CHECK (status IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED',
        'INSPECTED', 'SETTLEMENT_PENDING', 'RESOLVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    -- What the customer says went wrong. CHANGED_MIND is recorded so it can be refused by name: printed goods are
    -- returned only when defective (BR-RMA-002), and SCRUM-243 narrowed returns to defects.
    ADD CONSTRAINT ck_return_request_reason CHECK (reason_code IN ('PRINT_DEFECT', 'DESIGN_MISMATCH',
        'DAMAGED_IN_TRANSIT', 'WRONG_ITEM', 'SHORT_DELIVERY', 'CHANGED_MIND', 'OTHER')),
    ADD CONSTRAINT ck_return_request_other_note CHECK (reason_code <> 'OTHER' OR note IS NOT NULL),
    ADD CONSTRAINT ck_return_request_channel CHECK (channel IN ('PORTAL', 'BACK_OFFICE')),
    ADD CONSTRAINT ck_return_request_resolution CHECK (requested_resolution IN ('REPLACEMENT', 'REFUND',
        'CREDIT_NOTE')),
    ADD CONSTRAINT ck_return_request_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Reviewed: everything past REQUESTED names who decided and when (a cancellation before review names nobody).
    ADD CONSTRAINT ck_return_request_reviewed CHECK (status IN ('REQUESTED', 'CANCELLED')
        OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    -- A claim Sales opened is approved by someone else (an approval is what sets return_required).
    ADD CONSTRAINT ck_return_request_approval_four_eyes CHECK (channel <> 'BACK_OFFICE' OR return_required IS NULL
        OR decided_by <> requested_by),
    ADD CONSTRAINT ck_return_request_window_override CHECK (window_override_reason IS NULL OR channel = 'BACK_OFFICE'),
    ADD CONSTRAINT ck_return_request_unreviewed CHECK (status <> 'REQUESTED'
        OR (decided_at IS NULL AND return_required IS NULL)),
    ADD CONSTRAINT ck_return_request_approved CHECK (status NOT IN ('AWAITING_RETURN', 'RECEIVED', 'INSPECTED',
        'SETTLEMENT_PENDING', 'RESOLVED', 'EXPIRED') OR return_required IS NOT NULL),
    -- Goods that must come back have a deadline, and only they do; waiting for goods means they must come back.
    ADD CONSTRAINT ck_return_request_deadline CHECK ((return_required IS TRUE) = (return_deadline IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_awaiting CHECK (status NOT IN ('AWAITING_RETURN', 'RECEIVED', 'EXPIRED')
        OR return_required),
    ADD CONSTRAINT ck_return_request_received CHECK ((received_at IS NULL) = (received_by IS NULL)
        AND (received_at IS NULL) = (receiving_location_code IS NULL)
        AND parcel_count >= 0 AND (received_at IS NULL) = (parcel_count = 0)
        AND (received_at IS NULL OR return_required)
        AND (status <> 'RECEIVED' OR received_at IS NOT NULL)
        AND (return_required IS NOT TRUE OR status NOT IN ('INSPECTED', 'SETTLEMENT_PENDING', 'RESOLVED')
             OR received_at IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_inspected CHECK ((inspected_at IS NULL) = (inspected_by IS NULL)
        AND (status NOT IN ('INSPECTED', 'SETTLEMENT_PENDING', 'RESOLVED') OR inspected_at IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_rejected CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_cancelled CHECK ((status = 'CANCELLED')
        = (cancelled_by IS NOT NULL AND cancellation_reason IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_closed CHECK ((status IN ('RESOLVED', 'REJECTED', 'CANCELLED', 'EXPIRED'))
        = (closed_at IS NOT NULL)),
    -- Money: a proposal that credits anything names how (refund, or a credit note against what the customer owes).
    ADD CONSTRAINT ck_return_request_money_mode CHECK ((money_mode IS NULL OR money_mode IN ('REFUND', 'CREDIT_NOTE'))
        AND (settlement_amount > 0) = (money_mode IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_amounts CHECK (settlement_amount >= 0 AND refunded_amount >= 0
        AND credited_amount >= 0 AND offset_amount >= 0
        AND (status <> 'RESOLVED' OR refunded_amount + credited_amount + offset_amount = settlement_amount)
        AND (status = 'RESOLVED' OR (refunded_amount = 0 AND credited_amount = 0 AND offset_amount = 0))
        AND (money_mode IS DISTINCT FROM 'CREDIT_NOTE' OR refunded_amount = 0)
        AND (money_mode IS DISTINCT FROM 'REFUND' OR credited_amount = 0)),
    ADD CONSTRAINT ck_return_request_proposed CHECK ((proposed_at IS NULL) = (proposed_by IS NULL)
        AND (status NOT IN ('SETTLEMENT_PENDING', 'RESOLVED') OR proposed_at IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_settled CHECK ((settled_at IS NULL) = (settled_by IS NULL)
        AND (status = 'RESOLVED') = (settled_at IS NOT NULL)),
    -- Four eyes on every resolution (BR-PAY-003 in the absence of approver limits): free replacement goods are worth
    -- what money is, so who proposed a resolution never approves it.
    ADD CONSTRAINT ck_return_request_four_eyes CHECK (settled_by IS NULL OR settled_by <> proposed_by),
    -- Nghị định 123/2020 / Thông tư 78/2021: money given back after the sale was invoiced needs an adjustment
    -- invoice; the system has no e-invoicing, so the return says it for accounting.
    ADD CONSTRAINT ck_return_request_vat CHECK (vat_adjustment_required = (status = 'RESOLVED' AND settlement_amount > 0)),
    -- The credit entry payment books for a return (V20261025020100) carries exactly what was set off or kept as credit.
    ADD CONSTRAINT ck_return_request_credit_transfer CHECK ((credit_transfer_id IS NOT NULL)
        = (credited_amount + offset_amount > 0)),
    -- A replacement is withdrawn only from a resolved return that had one; proposed, then decided by someone else;
    -- once approved, exactly its value was set off or kept as credit - never refunded in cash here.
    ADD CONSTRAINT ck_return_request_withdrawal CHECK (
        (withdrawal_status IS NULL OR withdrawal_status IN ('PENDING', 'APPROVED', 'REJECTED'))
        AND (withdrawal_status IS NULL OR (status = 'RESOLVED' AND replacement_order_id IS NOT NULL
             AND withdrawal_reason IS NOT NULL AND withdrawal_proposed_by IS NOT NULL
             AND withdrawal_proposed_at IS NOT NULL))
        AND (withdrawal_status IN ('APPROVED', 'REJECTED'))
            = (withdrawal_decided_by IS NOT NULL AND withdrawal_decided_at IS NOT NULL)
        AND (withdrawal_decided_by IS NULL OR withdrawal_decided_by <> withdrawal_proposed_by)
        AND (withdrawal_status IS DISTINCT FROM 'REJECTED' OR withdrawal_decision_note IS NOT NULL)
        AND withdrawal_amount >= 0 AND withdrawal_offset_amount >= 0 AND withdrawal_credited_amount >= 0
        AND (withdrawal_status = 'APPROVED'
             OR (withdrawal_offset_amount = 0 AND withdrawal_credited_amount = 0))
        AND (withdrawal_status IS DISTINCT FROM 'APPROVED'
             OR withdrawal_offset_amount + withdrawal_credited_amount = withdrawal_amount)
        AND (withdrawal_credit_transfer_id IS NOT NULL)
            = (withdrawal_offset_amount + withdrawal_credited_amount > 0));

CREATE INDEX ix_return_request_customer ON ordering.return_request (customer_id, requested_at);
-- The expiry sweep: goods that should have come back by now.
CREATE INDEX ix_return_request_deadline ON ordering.return_request (return_deadline)
    WHERE status = 'AWAITING_RETURN';
CREATE UNIQUE INDEX uk_return_request_replacement_order ON ordering.return_request (replacement_order_id)
    WHERE replacement_order_id IS NOT NULL;

COMMENT ON COLUMN ordering.return_request.note IS
    'The customer''s description of the problem (required for reason OTHER).';
COMMENT ON COLUMN ordering.return_request.settlement_amount IS
    'What the accepted units not replaced are worth (unit price x credited units): refunded, credited, or set off '
    'against the order''s receivables.';

-- ---- 2. return_request_line -----------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request_line
    DROP CONSTRAINT ck_return_request_line_condition,
    DROP CONSTRAINT ck_return_request_line_disposition,
    DROP CONSTRAINT ck_return_request_line_inspected;
ALTER TABLE ordering.return_request_line DROP COLUMN disposition;

ALTER TABLE ordering.return_request_line
    ADD COLUMN sku                           VARCHAR(64)    NOT NULL,
    ADD COLUMN custom_printed                BOOLEAN        NOT NULL,
    ADD COLUMN unit_price                    NUMERIC(19, 4) NOT NULL,
    ADD COLUMN approved_quantity             INTEGER,
    ADD COLUMN received_sku                  VARCHAR(64),
    ADD COLUMN received_quantity             INTEGER,
    ADD COLUMN lot_number                    VARCHAR(64),
    ADD COLUMN accepted_quantity             INTEGER,
    ADD COLUMN rejected_quantity             INTEGER,
    ADD COLUMN inspection_note               VARCHAR(1000),
    ADD COLUMN restock_quantity              INTEGER        NOT NULL DEFAULT 0,
    ADD COLUMN scrap_quantity                INTEGER        NOT NULL DEFAULT 0,
    ADD COLUMN customer_return_quantity      INTEGER        NOT NULL DEFAULT 0,
    ADD COLUMN restock_location_code         VARCHAR(64),
    -- QC vouched the units going back on sale are plain stock: a design is not part of a SKU.
    ADD COLUMN received_plain                BOOLEAN,
    -- How the customer agreed that rejected units - their goods - are scrapped rather than sent back.
    ADD COLUMN disposal_consent_note         VARCHAR(500),
    ADD COLUMN scrap_adjustment_id           UUID,
    ADD COLUMN customer_return_adjustment_id UUID,
    ADD COLUMN replacement_quantity          INTEGER        NOT NULL DEFAULT 0,
    ADD COLUMN credit_quantity               INTEGER        NOT NULL DEFAULT 0,
    ADD COLUMN credit_amount                 NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN replacement_order_line_id     UUID;

ALTER TABLE ordering.return_request_line
    ADD CONSTRAINT fk_return_request_line_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_line_received_item FOREIGN KEY (received_sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_line_restock_location FOREIGN KEY (restock_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_line_scrap_adjustment FOREIGN KEY (scrap_adjustment_id)
        REFERENCES inventory.stock_adjustment (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_line_customer_return_adjustment FOREIGN KEY (customer_return_adjustment_id)
        REFERENCES inventory.stock_adjustment (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_return_request_line_replacement FOREIGN KEY (replacement_order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_return_request_line_price CHECK (unit_price >= 0),
    ADD CONSTRAINT ck_return_request_line_condition CHECK (item_condition IS NULL
        OR item_condition IN ('UNOPENED', 'GOOD', 'DAMAGED', 'DEFECTIVE')),
    ADD CONSTRAINT ck_return_request_line_approved CHECK (approved_quantity IS NULL
        OR approved_quantity BETWEEN 0 AND quantity),
    ADD CONSTRAINT ck_return_request_line_received CHECK ((received_quantity IS NULL OR received_quantity >= 0)
        AND (received_sku IS NULL OR received_quantity IS NOT NULL)),
    -- Inspected: every unit received is accepted (the claim holds) or rejected; never more accepted than approved.
    -- A return whose goods stay with the customer (no return needed) accepts on the evidence and receives nothing.
    ADD CONSTRAINT ck_return_request_line_inspected CHECK ((accepted_quantity IS NULL) = (rejected_quantity IS NULL)
        AND (accepted_quantity IS NULL OR (accepted_quantity >= 0 AND rejected_quantity >= 0
             AND accepted_quantity <= approved_quantity))
        AND (accepted_quantity IS NULL OR received_quantity IS NULL
             OR accepted_quantity + rejected_quantity = received_quantity)),
    -- Where the received units went: every one of them, once inspected.
    ADD CONSTRAINT ck_return_request_line_disposition CHECK (restock_quantity >= 0 AND scrap_quantity >= 0
        AND customer_return_quantity >= 0
        AND restock_quantity + scrap_quantity + customer_return_quantity
            = CASE WHEN accepted_quantity IS NULL THEN 0 ELSE COALESCE(received_quantity, 0) END
        AND customer_return_quantity <= COALESCE(rejected_quantity, 0)
        AND (restock_quantity > 0) = (restock_location_code IS NOT NULL)),
    -- Accepted units are ours once made good: back on sale or scrapped. Rejected units are the customer's: back to
    -- them, or scrapped only with their recorded consent.
    ADD CONSTRAINT ck_return_request_line_customer_goods CHECK (restock_quantity <= COALESCE(accepted_quantity, 0)
        AND (customer_return_quantity >= COALESCE(rejected_quantity, 0) OR disposal_consent_note IS NOT NULL)),
    ADD CONSTRAINT ck_return_request_line_restock_plain CHECK (restock_quantity = 0 OR received_plain IS TRUE),
    -- OQ-12 / BR-RMA-002: goods printed to a customer's design never go back on sale.
    ADD CONSTRAINT ck_return_request_line_restock_custom CHECK (NOT custom_printed OR restock_quantity = 0
        OR (received_sku IS NOT NULL AND received_sku <> sku)),
    ADD CONSTRAINT ck_return_request_line_resolution CHECK (replacement_quantity >= 0 AND credit_quantity >= 0
        AND replacement_quantity + credit_quantity <= COALESCE(accepted_quantity, 0)
        AND credit_amount = unit_price * credit_quantity
        AND (replacement_order_line_id IS NULL OR replacement_quantity > 0));

CREATE UNIQUE INDEX uk_return_request_line_replacement ON ordering.return_request_line (replacement_order_line_id)
    WHERE replacement_order_line_id IS NOT NULL;

-- ---- 3. how much of a line a return may claim ------------------------------------------------------------------------
-- What the line's other returns still claim: the units asked for while waiting for review, the units approved until
-- they are inspected, the units accepted afterwards; nothing once rejected, cancelled or expired. A line takes one open
-- return at a time, so a claim is never decided twice in parallel. The order line is locked first, as the domain locks
-- the order: two returns of one line are checked one after the other.
CREATE OR REPLACE FUNCTION ordering.check_return_request_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    deliverable    INTEGER;
    claimed        INTEGER;
    open_elsewhere BOOLEAN;
BEGIN
    SELECT l.quantity - l.cancelled_quantity INTO deliverable
      FROM ordering.order_line l
      JOIN ordering.return_request r ON r.order_id = l.order_id
     WHERE l.id = NEW.order_line_id AND r.id = NEW.return_request_id
       FOR UPDATE OF l;
    IF deliverable IS NULL THEN
        RAISE EXCEPTION 'return line must be a line of the returned order' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(SUM(CASE
                            WHEN r.status = 'REQUESTED' THEN x.quantity
                            WHEN r.status IN ('AWAITING_RETURN', 'RECEIVED') THEN x.approved_quantity
                            WHEN r.status IN ('INSPECTED', 'SETTLEMENT_PENDING', 'RESOLVED') THEN x.accepted_quantity
                            ELSE 0 END), 0),
           COALESCE(bool_or(r.status IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED', 'INSPECTED',
                                         'SETTLEMENT_PENDING')), FALSE)
      INTO claimed, open_elsewhere
      FROM ordering.return_request_line x
      JOIN ordering.return_request r ON r.id = x.return_request_id
     WHERE x.order_line_id = NEW.order_line_id AND x.return_request_id <> NEW.return_request_id;
    IF TG_OP = 'INSERT' AND open_elsewhere THEN
        RAISE EXCEPTION 'order line % already has an open return', NEW.order_line_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF claimed + NEW.quantity > deliverable THEN
        RAISE EXCEPTION 'would claim % of the % unit(s) delivered', claimed + NEW.quantity, deliverable
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- ---- 4. append-only children ------------------------------------------------------------------------------------------
CREATE TABLE ordering.return_request_evidence
(
    id                UUID         NOT NULL,
    return_request_id UUID         NOT NULL,
    -- CLAIM: the customer's (or Sales') photos of the problem; RECEIPT, INSPECTION: the warehouse's and QC's.
    stage             VARCHAR(16)  NOT NULL,
    file_key          VARCHAR(512) NOT NULL,
    original_name     VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    uploaded_by       UUID         NOT NULL,
    uploaded_at       TIMESTAMPTZ  NOT NULL,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),

    CONSTRAINT pk_return_request_evidence PRIMARY KEY (id),
    CONSTRAINT uk_return_request_evidence_key UNIQUE (file_key),
    CONSTRAINT fk_return_request_evidence_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_evidence_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_return_request_evidence_stage CHECK (stage IN ('CLAIM', 'RECEIPT', 'INSPECTION')),
    CONSTRAINT ck_return_request_evidence_size CHECK (size_bytes > 0)
);

CREATE INDEX ix_return_request_evidence_request ON ordering.return_request_evidence (return_request_id, uploaded_at);

CREATE TRIGGER tg_return_request_evidence_append_only
    BEFORE UPDATE OR DELETE ON ordering.return_request_evidence
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.return_request', 'return_request_id');

-- What the parcel held that the approval did not say: fewer or more units of a line, another SKU than the line's, or
-- something that belongs to no line at all (its SKU free text: it may not be ours).
CREATE TABLE ordering.return_request_discrepancy
(
    id                     UUID        NOT NULL,
    return_request_id      UUID        NOT NULL,
    return_request_line_id UUID,
    kind                   VARCHAR(16) NOT NULL,
    sku                    VARCHAR(64),
    quantity               INTEGER     NOT NULL,
    note                   VARCHAR(500),
    recorded_by            UUID        NOT NULL,
    recorded_at            TIMESTAMPTZ NOT NULL,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by             VARCHAR(100),

    CONSTRAINT pk_return_request_discrepancy PRIMARY KEY (id),
    CONSTRAINT fk_return_request_discrepancy_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_discrepancy_line FOREIGN KEY (return_request_line_id)
        REFERENCES ordering.return_request_line (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_discrepancy_recorded_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_return_request_discrepancy_kind CHECK (kind IN ('SHORT', 'EXCESS', 'WRONG_ITEM', 'UNEXPECTED_ITEM')),
    CONSTRAINT ck_return_request_discrepancy_line CHECK ((kind = 'UNEXPECTED_ITEM') = (return_request_line_id IS NULL)),
    CONSTRAINT ck_return_request_discrepancy_unexpected CHECK (kind <> 'UNEXPECTED_ITEM' OR sku IS NOT NULL
        OR note IS NOT NULL),
    CONSTRAINT ck_return_request_discrepancy_quantity CHECK (quantity > 0)
);

CREATE INDEX ix_return_request_discrepancy_request ON ordering.return_request_discrepancy (return_request_id);

CREATE TRIGGER tg_return_request_discrepancy_append_only
    BEFORE UPDATE OR DELETE ON ordering.return_request_discrepancy
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.return_request', 'return_request_id');

CREATE TABLE ordering.return_request_status_history
(
    id                UUID        NOT NULL,
    return_request_id UUID        NOT NULL,
    from_status       VARCHAR(24),
    to_status         VARCHAR(24) NOT NULL,
    reason            VARCHAR(500),
    actor_id          UUID,
    occurred_at       TIMESTAMPTZ NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),

    CONSTRAINT pk_return_request_status_history PRIMARY KEY (id),
    CONSTRAINT fk_return_request_status_history_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_status_history_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_return_request_status_history_status CHECK (to_status ~ '^[A-Z_]{1,24}$'
        AND (from_status IS NULL OR from_status ~ '^[A-Z_]{1,24}$')),
    CONSTRAINT ck_return_request_status_history_change CHECK (from_status IS DISTINCT FROM to_status)
);

CREATE INDEX ix_return_request_status_history_request
    ON ordering.return_request_status_history (return_request_id, occurred_at);

CREATE TRIGGER tg_return_request_status_history_append_only
    BEFORE UPDATE OR DELETE ON ordering.return_request_status_history
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.return_request', 'return_request_id');

-- ---- 5. RMA numbers ---------------------------------------------------------------------------------------------------
CREATE TABLE ordering.return_number_sequence
(
    sequence_date DATE   NOT NULL,
    last_value    BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_return_number_sequence PRIMARY KEY (sequence_date),
    CONSTRAINT ck_return_number_sequence_value CHECK (last_value >= 0)
);

-- ---- 6. the replacement order ------------------------------------------------------------------------------------------
-- Accepted units a return makes good with new goods are shipped as a new order of the same customer, at no charge:
-- it is released, printed and delivered as any order is, and names the return it replaces goods for. One per return.
ALTER TABLE ordering.customer_order
    ADD COLUMN replaces_return_request_id UUID,
    ADD CONSTRAINT fk_order_replaces_return FOREIGN KEY (replaces_return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_order_replacement_free CHECK (replaces_return_request_id IS NULL OR total_amount = 0);

CREATE UNIQUE INDEX uk_order_replaces_return ON ordering.customer_order (replaces_return_request_id)
    WHERE replaces_return_request_id IS NOT NULL;

-- A further claim after a return gave money back (03 §12: Sales open it) and a late claim Sales accepted after the
-- order completed put the order back to RETURN_REQUESTED, so it shows the open claim and is settled again by it
-- (OrderStatus.canTransitionTo holds the same edges; a CLOSED order changes no more, BR-05).
INSERT INTO ordering.order_status_transition (from_status, to_status) VALUES
    ('COMPLETED', 'RETURN_REQUESTED'),
    ('REFUNDED', 'RETURN_REQUESTED'),
    ('PARTIALLY_REFUNDED', 'RETURN_REQUESTED')
ON CONFLICT DO NOTHING;

-- A replacement order is cancelled only by withdrawing it through its return, which credits the units instead; and
-- that reason is for replacement orders only.
ALTER TABLE ordering.customer_order
    DROP CONSTRAINT ck_order_cancellation_code_values,
    ADD CONSTRAINT ck_order_cancellation_code_values CHECK (cancellation_reason_code IN
        ('CUSTOMER_REQUEST', 'PAYMENT_NOT_RECEIVED', 'PAYMENT_FAILED', 'OUT_OF_STOCK', 'SUSPECTED_FRAUD',
         'CREDIT_REJECTED', 'DUPLICATE_ORDER', 'PRODUCTION_ISSUE', 'REPLACEMENT_WITHDRAWN', 'OTHER')),
    ADD CONSTRAINT ck_order_replacement_cancellation CHECK (status <> 'CANCELLED'
        OR (replaces_return_request_id IS NOT NULL) = (cancellation_reason_code = 'REPLACEMENT_WITHDRAWN'));

-- ---- 7. grants ------------------------------------------------------------------------------------------------------------
-- sales-rmas (V20260903000100) already has VIEW_PAGE, READ, CREATE, APPROVE: the customer and Sales open returns, the
-- coordinator decides them. Added:
--   sales-rmas:UPDATE              cancel a return (the customer their own, before the goods are back; staff any)
--   sales-rma-receipts:UPDATE      count a returned parcel into the warehouse (warehouse staff and manager)
--   sales-rma-inspections:UPDATE   inspect returned goods and decide where they go (QC, warehouse manager)
--   sales-rma-resolutions:UPDATE   propose how accepted units are made good (Sales, coordinator)
--   sales-rma-resolutions:APPROVE  approve a resolution that refunds or credits money - never one's own (Accountant)
-- and sales-rmas:APPROVE for Sales (03 §12: Sales approves a return), sales-rmas:VIEW_PAGE / READ for the warehouse, QC
-- and the Accountant, who find the returns they work on in the same list. SYSTEM_ADMIN holds every permission.
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), p.code, split_part(p.code, ':', 1), split_part(p.code, ':', 2), 0, NOW(), 'flyway'
FROM (VALUES ('sales-rmas:UPDATE'), ('sales-rma-receipts:UPDATE'), ('sales-rma-inspections:UPDATE'),
             ('sales-rma-resolutions:UPDATE'), ('sales-rma-resolutions:APPROVE')) AS p(code)
ON CONFLICT (code) DO NOTHING;

DO $$
BEGIN
    IF (SELECT count(*) FROM identity.permission WHERE code IN ('sales-rmas:VIEW_PAGE', 'sales-rmas:READ',
            'sales-rmas:CREATE', 'sales-rmas:UPDATE', 'sales-rmas:APPROVE', 'sales-rma-receipts:UPDATE',
            'sales-rma-inspections:UPDATE', 'sales-rma-resolutions:UPDATE', 'sales-rma-resolutions:APPROVE')) <> 9 THEN
        RAISE EXCEPTION 'V20261025020000: a permission it grants is missing';
    END IF;
END $$;

WITH wanted (role_code, permission_code) AS (
    VALUES ('CUSTOMER', 'sales-rmas:UPDATE'),
           ('SALES_STAFF', 'sales-rmas:UPDATE'),
           ('SALES_STAFF', 'sales-rmas:APPROVE'),
           ('SALES_STAFF', 'sales-rma-resolutions:UPDATE'),
           ('ORDER_COORDINATOR', 'sales-rmas:UPDATE'),
           ('ORDER_COORDINATOR', 'sales-rma-resolutions:UPDATE'),
           ('WAREHOUSE_STAFF', 'sales-rmas:VIEW_PAGE'),
           ('WAREHOUSE_STAFF', 'sales-rmas:READ'),
           ('WAREHOUSE_STAFF', 'sales-rma-receipts:UPDATE'),
           ('WAREHOUSE_MANAGER', 'sales-rmas:VIEW_PAGE'),
           ('WAREHOUSE_MANAGER', 'sales-rmas:READ'),
           ('WAREHOUSE_MANAGER', 'sales-rma-receipts:UPDATE'),
           ('WAREHOUSE_MANAGER', 'sales-rma-inspections:UPDATE'),
           ('QC_STAFF', 'sales-rmas:VIEW_PAGE'),
           ('QC_STAFF', 'sales-rmas:READ'),
           ('QC_STAFF', 'sales-rma-inspections:UPDATE'),
           ('ACCOUNTANT', 'sales-rmas:VIEW_PAGE'),
           ('ACCOUNTANT', 'sales-rmas:READ'),
           ('ACCOUNTANT', 'sales-rma-resolutions:APPROVE'),
           ('SYSTEM_ADMIN', 'sales-rmas:UPDATE'),
           ('SYSTEM_ADMIN', 'sales-rma-receipts:UPDATE'),
           ('SYSTEM_ADMIN', 'sales-rma-inspections:UPDATE'),
           ('SYSTEM_ADMIN', 'sales-rma-resolutions:UPDATE'),
           ('SYSTEM_ADMIN', 'sales-rma-resolutions:APPROVE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
    FROM wanted
    JOIN identity.app_role role ON role.code = wanted.role_code
    JOIN identity.permission permission ON permission.code = wanted.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- ADR-0008: a role's cached permissions are re-read when its version moves; only roles that gained a row move.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM granted);
