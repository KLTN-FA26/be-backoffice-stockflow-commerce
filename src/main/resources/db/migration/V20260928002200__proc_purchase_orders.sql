-- =============================================================================
-- Procurement: purchase orders with revisions, approvals and an event log - EXPAND.
-- Module: procurement   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase R
--
-- Status set follows the po-schema the team agreed (CONFIRMED, no SENT - decision D4); the old
-- procurement.purchase_order and PR #36's SENT stay with the old table until contract C4.
--
-- Revisions: an approved PO is never edited in place. A change creates a pending revision, which
-- is approved like the original; the header then points at it as the active one. Revisions, line
-- revisions, approvals and events are append-only (V20260928002400).
-- =============================================================================


CREATE TABLE procurement.purchase_orders
(
    id                  UUID           NOT NULL,
    po_number           VARCHAR(30)    NOT NULL,
    revision_no         BIGINT         NOT NULL DEFAULT 0,
    status              VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    supplier_id         UUID           NOT NULL,
    warehouse_id        UUID           NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    order_date          DATE           NOT NULL,
    expected_date       DATE,
    payment_terms       VARCHAR(50),
    incoterms           VARCHAR(10),
    subtotal            NUMERIC(18, 2) NOT NULL DEFAULT 0,
    discount_total      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_total           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    shipping_fee        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    active_revision_id  UUID,
    pending_revision_id UUID,
    source_proposal_id  UUID,
    note                TEXT,
    close_kind          VARCHAR(16),
    close_reason        VARCHAR(255),
    cancel_reason       VARCHAR(255),
    submitted_at        TIMESTAMPTZ,
    submitted_by        UUID,
    approved_at         TIMESTAMPTZ,
    approved_by         UUID,
    confirmed_at        TIMESTAMPTZ,
    confirmed_by        UUID,
    closed_at           TIMESTAMPTZ,
    closed_by           UUID,

    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_purchase_orders PRIMARY KEY (id),
    CONSTRAINT uk_purchase_orders_number UNIQUE (po_number),
    CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_purchase_orders_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_purchase_orders_proposal FOREIGN KEY (source_proposal_id)
        REFERENCES procurement.replenishment_proposals (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchase_orders_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_purchase_orders_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_purchase_orders_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_purchase_orders_closed_by FOREIGN KEY (closed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_purchase_orders_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED',
        'CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_purchase_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_purchase_orders_revision_no CHECK (revision_no >= 0),
    CONSTRAINT ck_purchase_orders_dates CHECK (expected_date IS NULL OR expected_date >= order_date),
    CONSTRAINT ck_purchase_orders_amounts CHECK (subtotal >= 0 AND discount_total >= 0
        AND tax_total >= 0 AND shipping_fee >= 0 AND total_amount >= 0),
    CONSTRAINT ck_purchase_orders_total
        CHECK (total_amount = subtotal - discount_total + tax_total + shipping_fee),
    CONSTRAINT ck_purchase_orders_close_kind
        CHECK (close_kind IS NULL OR close_kind IN ('NORMAL', 'SHORT_CLOSE', 'FORCE_CLOSE')),
    -- Once a PO leaves DRAFT it always has an approved-or-pending revision to point at.
    CONSTRAINT ck_purchase_orders_revision
        CHECK (status = 'DRAFT' OR active_revision_id IS NOT NULL OR pending_revision_id IS NOT NULL),
    CONSTRAINT ck_purchase_orders_submitted CHECK (status IN ('DRAFT', 'CANCELLED')
        OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_purchase_orders_approved CHECK (status NOT IN
        ('APPROVED', 'CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    -- Separation of duties: whoever submits a PO does not approve it.
    CONSTRAINT ck_purchase_orders_four_eyes
        CHECK (approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by),
    CONSTRAINT ck_purchase_orders_confirmed CHECK (status NOT IN
        ('CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_purchase_orders_closed CHECK (status <> 'CLOSED'
        OR (close_kind IS NOT NULL AND closed_by IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_purchase_orders_close_reason
        CHECK (close_kind IS NULL OR close_kind = 'NORMAL' OR close_reason IS NOT NULL),
    CONSTRAINT ck_purchase_orders_cancel_reason
        CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);

CREATE INDEX ix_purchase_orders_supplier_status ON procurement.purchase_orders (supplier_id, status);
CREATE INDEX ix_purchase_orders_warehouse ON procurement.purchase_orders (warehouse_id);
CREATE INDEX ix_purchase_orders_status ON procurement.purchase_orders (status);
CREATE INDEX ix_purchase_orders_expected ON procurement.purchase_orders (expected_date);
CREATE INDEX ix_purchase_orders_proposal ON procurement.purchase_orders (source_proposal_id);


CREATE TABLE procurement.purchase_order_revisions
(
    id               UUID         NOT NULL,
    po_id            UUID         NOT NULL,
    revision_no      BIGINT       NOT NULL,
    kind             VARCHAR(16)  NOT NULL DEFAULT 'INITIAL',
    snapshot_header  JSONB        NOT NULL,
    change_summary   VARCHAR(500),
    changed_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    changed_by       UUID         NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_purchase_order_revisions PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_revisions_no UNIQUE (po_id, revision_no),
    CONSTRAINT fk_purchase_order_revisions_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_revisions_changed_by FOREIGN KEY (changed_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_purchase_order_revisions_kind CHECK (kind IN ('INITIAL', 'AMENDMENT')),
    CONSTRAINT ck_purchase_order_revisions_no CHECK ((kind = 'INITIAL') = (revision_no = 0)),
    CONSTRAINT ck_purchase_order_revisions_snapshot CHECK (jsonb_typeof(snapshot_header) = 'object'),
    CONSTRAINT ck_purchase_order_revisions_summary
        CHECK (kind = 'INITIAL' OR change_summary IS NOT NULL)
);

CREATE INDEX ix_purchase_order_revisions_changed_by ON procurement.purchase_order_revisions (changed_by);

-- Header <-> revision point at each other, so one side has to be checked at commit: a revision is
-- inserted after its PO, and the PO is then updated to point at it. DEFERRABLE keeps that order
-- legal inside one transaction without making a dangling pointer legal at commit.
ALTER TABLE procurement.purchase_orders
    ADD CONSTRAINT fk_purchase_orders_active_revision FOREIGN KEY (active_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_purchase_orders_pending_revision FOREIGN KEY (pending_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT ck_purchase_orders_revisions_differ
        CHECK (active_revision_id IS NULL OR pending_revision_id IS NULL
            OR active_revision_id <> pending_revision_id);


CREATE TABLE procurement.purchase_order_lines
(
    id                   UUID           NOT NULL,
    po_id                UUID           NOT NULL,
    po_revision_id       UUID,
    line_no              INTEGER        NOT NULL,
    inventory_item_id    UUID           NOT NULL,
    uom                  VARCHAR(20)    NOT NULL DEFAULT 'EACH',
    ordered_qty          NUMERIC(18, 3) NOT NULL,
    unit_price           NUMERIC(18, 2) NOT NULL,
    tax_rate             NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    discount_rate        NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    line_subtotal        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_discount_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_net             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_tax             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_total           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status               VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    note                 VARCHAR(255),

    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_purchase_order_lines PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_lines_no UNIQUE (po_id, line_no),
    CONSTRAINT fk_purchase_order_lines_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_lines_revision FOREIGN KEY (po_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_purchase_order_lines_line_no CHECK (line_no > 0),
    CONSTRAINT ck_purchase_order_lines_qty CHECK (ordered_qty > 0),
    CONSTRAINT ck_purchase_order_lines_price CHECK (unit_price > 0),
    CONSTRAINT ck_purchase_order_lines_rates
        CHECK (tax_rate BETWEEN 0 AND 100 AND discount_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_purchase_order_lines_amounts CHECK (line_subtotal >= 0
        AND line_discount_amount >= 0 AND line_tax >= 0
        AND line_net = line_subtotal - line_discount_amount
        AND line_total = line_net + line_tax),
    CONSTRAINT ck_purchase_order_lines_status
        CHECK (status IN ('OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'))
);

CREATE INDEX ix_purchase_order_lines_item ON procurement.purchase_order_lines (inventory_item_id);
CREATE INDEX ix_purchase_order_lines_revision ON procurement.purchase_order_lines (po_revision_id);


-- The value of each line in each revision: what was approved, frozen.
CREATE TABLE procurement.purchase_order_line_revisions
(
    id                   UUID           NOT NULL,
    po_line_id           UUID           NOT NULL,
    po_revision_id       UUID           NOT NULL,
    inventory_item_id    UUID           NOT NULL,
    uom                  VARCHAR(20)    NOT NULL,
    ordered_qty          NUMERIC(18, 3) NOT NULL,
    unit_price           NUMERIC(18, 2) NOT NULL,
    tax_rate             NUMERIC(5, 2)  NOT NULL,
    discount_rate        NUMERIC(5, 2)  NOT NULL,
    line_subtotal        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_discount_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_net             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_tax             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_total           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    note                 VARCHAR(255),

    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_purchase_order_line_revisions PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_line_revisions_pair UNIQUE (po_line_id, po_revision_id),
    CONSTRAINT fk_purchase_order_line_revisions_line FOREIGN KEY (po_line_id)
        REFERENCES procurement.purchase_order_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_line_revisions_revision FOREIGN KEY (po_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE CASCADE,
    CONSTRAINT ck_purchase_order_line_revisions_qty CHECK (ordered_qty > 0),
    CONSTRAINT ck_purchase_order_line_revisions_price CHECK (unit_price > 0),
    CONSTRAINT ck_purchase_order_line_revisions_rates
        CHECK (tax_rate BETWEEN 0 AND 100 AND discount_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_purchase_order_line_revisions_amounts CHECK (line_subtotal >= 0
        AND line_discount_amount >= 0 AND line_tax >= 0
        AND line_net = line_subtotal - line_discount_amount
        AND line_total = line_net + line_tax)
);

CREATE INDEX ix_purchase_order_line_revisions_revision ON procurement.purchase_order_line_revisions (po_revision_id);
CREATE INDEX ix_purchase_order_line_revisions_item ON procurement.purchase_order_line_revisions (inventory_item_id);


CREATE TABLE procurement.purchase_order_approvals
(
    id               UUID         NOT NULL,
    po_revision_id   UUID         NOT NULL,
    step_no          INTEGER      NOT NULL DEFAULT 1,
    approver_id      UUID         NOT NULL,
    decision         VARCHAR(16)  NOT NULL,
    decision_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reason           VARCHAR(500),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_purchase_order_approvals PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_approvals_step UNIQUE (po_revision_id, step_no),
    CONSTRAINT fk_purchase_order_approvals_revision FOREIGN KEY (po_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_approvals_approver FOREIGN KEY (approver_id)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_purchase_order_approvals_step CHECK (step_no > 0),
    CONSTRAINT ck_purchase_order_approvals_decision
        CHECK (decision IN ('APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_purchase_order_approvals_reason CHECK (decision = 'APPROVED' OR reason IS NOT NULL)
);

CREATE INDEX ix_purchase_order_approvals_approver ON procurement.purchase_order_approvals (approver_id);


-- The PO's timeline: every transition, who, why. Append-only.
CREATE TABLE procurement.purchase_order_events
(
    id               UUID         NOT NULL,
    po_id            UUID         NOT NULL,
    po_revision_id   UUID,
    action           VARCHAR(24)  NOT NULL,
    actor_id         UUID,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20),
    reason           VARCHAR(500),
    payload          JSONB,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_purchase_order_events PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_events_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_events_revision FOREIGN KEY (po_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_events_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_purchase_order_events_action CHECK (action IN ('CREATED', 'SUBMITTED', 'APPROVED',
        'REJECTED', 'WITHDRAWN', 'CONFIRMED', 'REVISED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED',
        'SHORT_CLOSED', 'FORCE_CLOSED', 'CANCELLED')),
    CONSTRAINT ck_purchase_order_events_status CHECK (
        (from_status IS NULL OR from_status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED',
            'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'))
        AND (to_status IS NULL OR to_status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED',
            'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'))),
    CONSTRAINT ck_purchase_order_events_payload CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object')
);

CREATE INDEX ix_purchase_order_events_po ON procurement.purchase_order_events (po_id, created_at);
CREATE INDEX ix_purchase_order_events_revision ON procurement.purchase_order_events (po_revision_id);


CREATE TABLE procurement.purchase_order_attachments
(
    id               UUID         NOT NULL,
    po_id            UUID         NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_url         TEXT         NOT NULL,
    file_type        VARCHAR(20),
    category         VARCHAR(30)  NOT NULL DEFAULT 'OTHER',
    file_size        INTEGER,
    uploaded_by      UUID         NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_purchase_order_attachments PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_attachments_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_attachments_uploaded_by FOREIGN KEY (uploaded_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_purchase_order_attachments_category
        CHECK (category IN ('QUOTATION', 'CONTRACT', 'SUPPLIER_CONFIRMATION', 'DELIVERY_NOTE', 'OTHER')),
    CONSTRAINT ck_purchase_order_attachments_size CHECK (file_size IS NULL OR file_size > 0)
);

CREATE INDEX ix_purchase_order_attachments_po ON procurement.purchase_order_attachments (po_id, category);


-- Who may approve how much: a PO above the limit of every role the approver holds cannot be
-- approved by them. Periods of one (role, currency) never overlap.
CREATE TABLE procurement.approval_limits
(
    id               UUID           NOT NULL,
    role_id          UUID           NOT NULL,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'VND',
    max_po_amount    NUMERIC(18, 2) NOT NULL,
    effective_from   DATE           NOT NULL,
    effective_to     DATE,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_approval_limits PRIMARY KEY (id),
    CONSTRAINT fk_approval_limits_role FOREIGN KEY (role_id)
        REFERENCES identity.app_role (id) ON DELETE CASCADE,
    CONSTRAINT ck_approval_limits_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_approval_limits_amount CHECK (max_po_amount > 0),
    CONSTRAINT ck_approval_limits_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_approval_limits_overlap EXCLUDE USING gist (
        role_id WITH =,
        currency WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&)
);


-- Proposal lines point at the PO they became (declared in V20260928002100). RESTRICT, not SET NULL:
-- nulling the link would leave converted = TRUE and fail ck_replenishment_proposal_lines_converted,
-- so the service un-converts the line first, explicitly, before a draft PO can be deleted.
ALTER TABLE procurement.replenishment_proposal_lines
    ADD CONSTRAINT fk_replenishment_proposal_lines_po FOREIGN KEY (converted_po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_replenishment_proposal_lines_po_line FOREIGN KEY (converted_po_line_id)
        REFERENCES procurement.purchase_order_lines (id) ON DELETE RESTRICT;
