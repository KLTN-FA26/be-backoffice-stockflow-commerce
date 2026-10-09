-- =============================================================================
-- Production: sample requests and production orders (LSX) for printed cups and packaging.
-- Module: production   Docs: kltn-docs docs/warehouse/19-production, BE 03-state-machines §16,
-- 05-business-rules BR-PRD-01..17, plan docs/business-design/2026-10-06-b2b-production-plan.md
--
-- Two kinds of production share one print shop: SAMPLE (a few pieces of a new design for the
-- customer to approve, before any sales order exists) and ORDER (the quantity of one released
-- order line). A part of an ORDER can be split off to an outside print shop: that part is a child
-- production order with mode SUBCONTRACTED (§4.4). Every invariant the aggregate checks is stated
-- again here as a CHECK, so a manual UPDATE cannot produce a row the code would refuse to load.
--
-- Also, for the stock side of production:
--   * the PRODUCTION area type on the warehouse map (blanks are issued to it before printing);
--   * stock adjustment reasons SCRAP (blanks spoiled in printing, BR-PRD-04) and SAMPLE (blanks
--     used for a sample round), and the ledger reference type PRODUCTION_ORDER.
--
-- Document numbers (LSX-, SR-) use platform.document_sequence like ADJ-, TO- and GR-: no
-- production-specific counter table.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS production;


-- One sample request per round. A customer who asks for changes gets a new round (round_no + 1)
-- pointing at the previous one, so the history of what was shown and what they said survives.
CREATE TABLE production.sample_request
(
    id                 UUID          NOT NULL,
    request_number     VARCHAR(30)   NOT NULL,
    customer_id        UUID          NOT NULL,
    -- The quote the sample belongs to. No foreign key yet: the quote table arrives with SCRUM-448.
    quote_id           UUID,
    previous_round_id  UUID,
    round_no           INTEGER       NOT NULL DEFAULT 1,
    blank_sku          VARCHAR(64)   NOT NULL,
    design_snapshot_id UUID          NOT NULL,
    quantity           INTEGER       NOT NULL,
    due_date           DATE,
    status             VARCHAR(32)   NOT NULL,
    sent_at            TIMESTAMPTZ,
    responded_at       TIMESTAMPTZ,
    responded_by       UUID,
    customer_feedback  VARCHAR(2000),
    cancellation_reason VARCHAR(500),

    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_sample_request PRIMARY KEY (id),
    CONSTRAINT uk_sample_request_number UNIQUE (request_number),
    CONSTRAINT fk_sample_request_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sample_request_previous FOREIGN KEY (previous_round_id)
        REFERENCES production.sample_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sample_request_snapshot FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sample_request_responded_by FOREIGN KEY (responded_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_sample_request_status CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'IN_PRODUCTION', 'READY', 'SENT_TO_CUSTOMER', 'CHANGES_REQUESTED',
         'APPROVED', 'CANCELLED')),
    CONSTRAINT ck_sample_request_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT ck_sample_request_round CHECK (round_no >= 1),
    -- The first round has no predecessor and every later round has one.
    CONSTRAINT ck_sample_request_round_chain
        CHECK ((round_no = 1) = (previous_round_id IS NULL)),
    CONSTRAINT ck_sample_request_sent
        CHECK (status NOT IN ('SENT_TO_CUSTOMER', 'CHANGES_REQUESTED', 'APPROVED') OR sent_at IS NOT NULL),
    -- An answer records who gave it and when; asking for changes must say what to change.
    CONSTRAINT ck_sample_request_response CHECK (status NOT IN ('CHANGES_REQUESTED', 'APPROVED')
        OR (responded_at IS NOT NULL AND responded_by IS NOT NULL)),
    CONSTRAINT ck_sample_request_feedback
        CHECK (status <> 'CHANGES_REQUESTED' OR customer_feedback IS NOT NULL),
    CONSTRAINT ck_sample_request_cancelled
        CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);

CREATE INDEX ix_sample_request_customer ON production.sample_request (customer_id);
CREATE UNIQUE INDEX uk_sample_request_next_round
    ON production.sample_request (previous_round_id) WHERE previous_round_id IS NOT NULL;
-- BR-PRD-08 lookup: "is there an approved sample for this design on this blank?"
CREATE INDEX ix_sample_request_approved ON production.sample_request (design_snapshot_id, blank_sku)
    WHERE status = 'APPROVED';


-- The production order (LSX). ORDER: one per released order line (BR-01) plus subcontracted
-- children split off it; SAMPLE: one per sample request round.
CREATE TABLE production.production_order
(
    id                   UUID          NOT NULL,
    order_number         VARCHAR(30)   NOT NULL,
    type                 VARCHAR(16)   NOT NULL,
    mode                 VARCHAR(16)   NOT NULL DEFAULT 'IN_HOUSE',
    status               VARCHAR(32)   NOT NULL,
    -- The status to return to when an ON_HOLD order is resumed.
    status_before_hold   VARCHAR(32),
    hold_reason          VARCHAR(32),
    hold_note            VARCHAR(1000),

    sample_request_id    UUID,
    sales_order_id       UUID,
    sales_order_line_id  UUID,
    -- A subcontracted child points at the order it was split from.
    parent_id            UUID,
    approved_sample_id   UUID,

    warehouse_id         UUID          NOT NULL,
    blank_sku            VARCHAR(64)   NOT NULL,
    design_snapshot_id   UUID          NOT NULL,
    design_checksum      VARCHAR(128)  NOT NULL,
    due_date             DATE,

    -- Good units required. Reduced on the parent when a part is split off.
    planned_quantity     INTEGER       NOT NULL,
    issued_quantity      INTEGER       NOT NULL DEFAULT 0,
    printed_quantity     INTEGER       NOT NULL DEFAULT 0,
    good_quantity        INTEGER       NOT NULL DEFAULT 0,
    scrap_quantity       INTEGER       NOT NULL DEFAULT 0,

    -- Subcontracting (mode SUBCONTRACTED only).
    subcontract_method   VARCHAR(20),
    subcontractor_id     UUID,
    subcontract_unit_price NUMERIC(19, 4),
    subcontract_currency VARCHAR(3),
    -- The SUBCONTRACT purchase order (SCRUM-434), on the new purchase-order table. The foreign key
    -- is added by V20260929000500 together with the PO type, which points back at this row.
    purchase_order_id    UUID,

    prepress_checked_by  UUID,
    prepress_checked_at  TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    cancellation_reason  VARCHAR(500),

    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_production_order PRIMARY KEY (id),
    CONSTRAINT uk_production_order_number UNIQUE (order_number),
    CONSTRAINT fk_production_order_sample_request FOREIGN KEY (sample_request_id)
        REFERENCES production.sample_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_sales_order FOREIGN KEY (sales_order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_sales_order_line FOREIGN KEY (sales_order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_parent FOREIGN KEY (parent_id)
        REFERENCES production.production_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_approved_sample FOREIGN KEY (approved_sample_id)
        REFERENCES production.sample_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_snapshot FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_subcontractor FOREIGN KEY (subcontractor_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_prepress_by FOREIGN KEY (prepress_checked_by)
        REFERENCES identity.app_user (id),

    CONSTRAINT ck_production_order_type CHECK (type IN ('SAMPLE', 'ORDER')),
    CONSTRAINT ck_production_order_mode CHECK (mode IN ('IN_HOUSE', 'SUBCONTRACTED')),
    CONSTRAINT ck_production_order_status CHECK (status IN
        ('PENDING_PREPRESS', 'READY', 'MATERIAL_ISSUED', 'PRINTING', 'SUBCONTRACTED', 'QC',
         'ON_HOLD', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_production_order_hold CHECK ((status = 'ON_HOLD') =
        (status_before_hold IS NOT NULL AND hold_reason IS NOT NULL)),
    CONSTRAINT ck_production_order_status_before_hold CHECK (status_before_hold IS NULL OR
        status_before_hold IN ('PENDING_PREPRESS', 'READY', 'MATERIAL_ISSUED', 'PRINTING', 'SUBCONTRACTED')),
    CONSTRAINT ck_production_order_hold_reason CHECK (hold_reason IS NULL OR hold_reason IN
        ('FILE_REJECTED', 'BLANKS_SHORT', 'MACHINE_DOWN', 'WAITING_CUSTOMER', 'SUBCONTRACTOR_LATE', 'OTHER')),

    -- The source matches the type: a SAMPLE comes from a sample request, an ORDER from an order line
    -- and is printed against an approved sample (BR-05) unless it is a repeat design.
    CONSTRAINT ck_production_order_source CHECK (
        (type = 'SAMPLE' AND sample_request_id IS NOT NULL
             AND sales_order_id IS NULL AND sales_order_line_id IS NULL AND parent_id IS NULL)
     OR (type = 'ORDER' AND sample_request_id IS NULL
             AND sales_order_id IS NOT NULL AND sales_order_line_id IS NOT NULL)),
    -- BR-11: a sample is always printed in house. A child is always subcontracted (a whole order
    -- sent out changes its own mode and has no parent).
    CONSTRAINT ck_production_order_sample_in_house CHECK (type <> 'SAMPLE' OR mode = 'IN_HOUSE'),
    CONSTRAINT ck_production_order_child_subcontracted CHECK (parent_id IS NULL OR mode = 'SUBCONTRACTED'),
    CONSTRAINT ck_production_order_subcontract_fields CHECK (
        (mode = 'IN_HOUSE' AND subcontract_method IS NULL AND subcontractor_id IS NULL
             AND subcontract_unit_price IS NULL AND subcontract_currency IS NULL AND purchase_order_id IS NULL)
     OR (mode = 'SUBCONTRACTED' AND subcontract_method IN ('SUPPLIED_BLANKS', 'FULL_SERVICE')
             AND subcontractor_id IS NOT NULL AND subcontract_unit_price IS NOT NULL
             AND subcontract_unit_price >= 0 AND subcontract_currency ~ '^[A-Z]{3}$')),
    -- The two branches of the state machine do not cross.
    CONSTRAINT ck_production_order_branch CHECK (
        (mode = 'IN_HOUSE' AND status <> 'SUBCONTRACTED' AND COALESCE(status_before_hold, '') <> 'SUBCONTRACTED')
     OR (mode = 'SUBCONTRACTED' AND status NOT IN ('MATERIAL_ISSUED', 'PRINTING')
             AND COALESCE(status_before_hold, '') NOT IN ('MATERIAL_ISSUED', 'PRINTING'))),

    -- BR-03 / BR-04: quantities never go negative, good + scrap = printed. Issued may exceed planned
    -- only through reprint rounds, so it is bounded by what was printed plus what is still owed.
    CONSTRAINT ck_production_order_quantities CHECK (
        planned_quantity > 0 AND issued_quantity >= 0 AND printed_quantity >= 0
        AND good_quantity >= 0 AND scrap_quantity >= 0
        AND good_quantity + scrap_quantity = printed_quantity),
    CONSTRAINT ck_production_order_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_production_order_completed_quantity
        CHECK (status <> 'COMPLETED' OR good_quantity >= planned_quantity),
    CONSTRAINT ck_production_order_cancelled CHECK ((status = 'CANCELLED') =
        (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)),
    -- BR-02: nothing is printed (or sent out) before the file passed prepress.
    CONSTRAINT ck_production_order_prepress CHECK (
        status IN ('PENDING_PREPRESS', 'CANCELLED')
        OR (status = 'ON_HOLD' AND status_before_hold = 'PENDING_PREPRESS')
        OR (prepress_checked_at IS NOT NULL AND prepress_checked_by IS NOT NULL)),
    CONSTRAINT ck_production_order_currency
        CHECK (subcontract_currency IS NULL OR subcontract_currency ~ '^[A-Z]{3}$')
);

-- BR-01: one root production order per order line. Children split off it are not counted.
CREATE UNIQUE INDEX uk_production_order_line
    ON production.production_order (sales_order_line_id) WHERE parent_id IS NULL AND type = 'ORDER';
-- One production order per sample round.
CREATE UNIQUE INDEX uk_production_order_sample_request
    ON production.production_order (sample_request_id) WHERE sample_request_id IS NOT NULL;
CREATE INDEX ix_production_order_sales_order ON production.production_order (sales_order_id);
CREATE INDEX ix_production_order_parent ON production.production_order (parent_id);
-- The shop queue: open orders by due date, then release time (19 §4.3 assumption).
CREATE INDEX ix_production_order_queue ON production.production_order (status, due_date, created_at)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');


-- Every transition, for the order detail screen and the shop dashboard.
CREATE TABLE production.production_order_status_history
(
    id                  UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    from_status         VARCHAR(32),
    to_status           VARCHAR(32)   NOT NULL,
    reason              VARCHAR(1000),
    changed_at          TIMESTAMPTZ   NOT NULL,
    changed_by          UUID,

    CONSTRAINT pk_production_order_status_history PRIMARY KEY (id),
    CONSTRAINT fk_production_status_history_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_status_history_by FOREIGN KEY (changed_by)
        REFERENCES identity.app_user (id)
);

CREATE INDEX ix_production_status_history_order
    ON production.production_order_status_history (production_order_id, changed_at);


-- One QC round: how many passed and failed. Failed units are scrap (19 §4.3 step 4).
CREATE TABLE production.production_qc_result
(
    id                  UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    round_no            INTEGER       NOT NULL,
    passed_quantity     INTEGER       NOT NULL,
    failed_quantity     INTEGER       NOT NULL,
    note                VARCHAR(1000),
    inspected_at        TIMESTAMPTZ   NOT NULL,
    inspected_by        UUID          NOT NULL,

    CONSTRAINT pk_production_qc_result PRIMARY KEY (id),
    CONSTRAINT uk_production_qc_result_round UNIQUE (production_order_id, round_no),
    CONSTRAINT fk_production_qc_result_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_qc_result_by FOREIGN KEY (inspected_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_qc_result_round CHECK (round_no >= 1),
    CONSTRAINT ck_production_qc_result_quantities
        CHECK (passed_quantity >= 0 AND failed_quantity >= 0 AND passed_quantity + failed_quantity > 0)
);


-- BR-04: every scrapped unit has a reason. The stock side is an inventory adjustment with reason
-- SCRAP; this row says which production order and why.
CREATE TABLE production.production_scrap
(
    id                  UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    quantity            INTEGER       NOT NULL,
    reason              VARCHAR(32)   NOT NULL,
    note                VARCHAR(1000),
    -- The inventory adjustment that took the blanks off stock, when the scrap consumed blanks.
    stock_adjustment_id UUID,
    recorded_at         TIMESTAMPTZ   NOT NULL,
    recorded_by         UUID          NOT NULL,

    CONSTRAINT pk_production_scrap PRIMARY KEY (id),
    CONSTRAINT fk_production_scrap_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_scrap_adjustment FOREIGN KEY (stock_adjustment_id)
        REFERENCES inventory.stock_adjustment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_scrap_by FOREIGN KEY (recorded_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_scrap_quantity CHECK (quantity > 0),
    CONSTRAINT ck_production_scrap_reason CHECK (reason IN
        ('PRINT_DEFECT', 'DAMAGED_BLANK', 'QC_FAILED', 'SETUP', 'OTHER')),
    CONSTRAINT ck_production_scrap_note CHECK (reason <> 'OTHER' OR note IS NOT NULL)
);

CREATE INDEX ix_production_scrap_order ON production.production_scrap (production_order_id);


-- The warehouse map gains the area blanks are issued to before printing (19 §4.3 step 2).
ALTER TABLE warehouse.area DROP CONSTRAINT ck_area_type;
ALTER TABLE warehouse.area ADD CONSTRAINT ck_area_type CHECK (type IN
    ('RECEIVING', 'QUALITY_CONTROL', 'QUARANTINE', 'PACKING', 'DISPATCH', 'OVERFLOW', 'PRODUCTION',
     'NON_STORAGE'));


-- Stock taken by production. Scrapped blanks and blanks used for samples leave stock through an
-- approved adjustment (four eyes, SCRUM-145) with their own reason, so the loss report can tell
-- them from damage and shrinkage.
ALTER TABLE inventory.stock_adjustment DROP CONSTRAINT ck_stock_adjustment_reason;
ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_reason CHECK (reason_code IN
    ('DAMAGED', 'LOST', 'FOUND', 'COUNT_VARIANCE', 'EXPIRED', 'DATA_CORRECTION', 'SCRAP', 'SAMPLE', 'OTHER'));

-- Blanks issued to the PRODUCTION area and finished goods moved to PACKING name the production
-- order they were moved for.
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_reference;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN
    ('GOODS_RECEIPT_LINE', 'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK',
     'TRANSFER_ORDER_LINE', 'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER',
     'PRODUCTION_ORDER'));
