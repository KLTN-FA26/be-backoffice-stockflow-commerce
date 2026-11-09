-- 4A-2 (kltn-docs 17 §4.3 steps 6-7, 12-13, §5.3, BR-05, BR-06, BR-23, BR-24, BR-26; team plan QD-3, QD-6): production
-- orders. The Production Coordinator draws one up from a confirmed order of a contract, for what one delivery batch
-- lacks at the order's shipping warehouse; the Sales Manager - never the one who drew it up - approves it, and it is
-- sent to the producer (the sheet and the e-mail are 4A-3); it is cancelled before mass printing, closed short after.
-- Sample rounds (4A-4) and receiving by production order (4A-5) build on these tables.

CREATE TABLE ordering.production_order
(
    id                         UUID           NOT NULL,
    -- QD-6: PRD-yyyyMMdd-nnnn; also the lot of the goods received against it (03 BR-03).
    code                       VARCHAR(32)    NOT NULL,
    sales_order_id             UUID           NOT NULL,
    -- BR-05: exactly one delivery batch of the order's schedule (a batch may have several production orders).
    delivery_batch_id          UUID           NOT NULL,
    customer_id                UUID           NOT NULL,
    producer_id                UUID           NOT NULL,
    -- BR-05, 17 BR-22: the order's shipping warehouse.
    receiving_warehouse_id     UUID           NOT NULL,
    -- BR-26: the batch's window start by default; editable while DRAFT (B33).
    need_by_date               DATE           NOT NULL,
    status                     VARCHAR(24)    NOT NULL,
    -- BR-24: whether the customer approves a print sample before mass printing.
    sample_approval_required   BOOLEAN        NOT NULL,
    -- BR-24: on by default when a SKU is produced for the first time; turned off then, it says why.
    sample_required_by_default BOOLEAN        NOT NULL,
    sample_waiver_reason       VARCHAR(500),
    -- 17 §4.3 options: the producer's price per unit, and a note for the producer.
    unit_price                 NUMERIC(19, 4),
    currency                   VARCHAR(3)     NOT NULL DEFAULT 'VND',
    note                       VARCHAR(500),
    drawn_up_by                UUID           NOT NULL,
    submitted_at               TIMESTAMPTZ,
    approved_by                UUID,
    approved_at                TIMESTAMPTZ,
    returned_reason            VARCHAR(500),
    sent_at                    TIMESTAMPTZ,
    -- When mass printing started (4A-4 / 4A-5): a SKU counts as produced before from then on (BR-24).
    production_started_at      TIMESTAMPTZ,
    cancelled_by               UUID,
    cancelled_at               TIMESTAMPTZ,
    cancellation_reason        VARCHAR(500),
    closed_by                  UUID,
    closed_at                  TIMESTAMPTZ,
    closing_reason             VARCHAR(500),

    version                    BIGINT         NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by                 VARCHAR(100),
    last_modified_at           TIMESTAMPTZ,
    last_modified_by           VARCHAR(100),

    CONSTRAINT pk_production_order PRIMARY KEY (id),
    CONSTRAINT uk_production_order_code UNIQUE (code),
    CONSTRAINT fk_production_order_sales_order FOREIGN KEY (sales_order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_production_order_batch FOREIGN KEY (delivery_batch_id) REFERENCES ordering.delivery_batch (id),
    CONSTRAINT fk_production_order_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_production_order_producer FOREIGN KEY (producer_id) REFERENCES ordering.producer (id),
    CONSTRAINT fk_production_order_warehouse FOREIGN KEY (receiving_warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_production_order_drawn_up_by FOREIGN KEY (drawn_up_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_order_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_order_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_order_closed_by FOREIGN KEY (closed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_order_code CHECK (code ~ '^PRD-[0-9]{8}-[0-9]{4,}$'),
    CONSTRAINT ck_production_order_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'SENT', 'ACCEPTED',
                                                            'SAMPLE_PENDING', 'IN_PRODUCTION', 'PARTIALLY_RECEIVED',
                                                            'RECEIVED', 'CANCELLED', 'CLOSED')),
    -- BR-24: a sample required by default is waived only with a reason.
    CONSTRAINT ck_production_order_sample CHECK (sample_approval_required OR NOT sample_required_by_default
        OR sample_waiver_reason IS NOT NULL),
    CONSTRAINT ck_production_order_texts CHECK ((sample_waiver_reason IS NULL OR btrim(sample_waiver_reason) <> '')
        AND (note IS NULL OR btrim(note) <> '') AND (returned_reason IS NULL OR btrim(returned_reason) <> '')),
    CONSTRAINT ck_production_order_price CHECK (unit_price IS NULL OR unit_price >= 0),
    -- BR-23: approved by someone who did not draw it up, once it was submitted.
    CONSTRAINT ck_production_order_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL)
        AND (approved_by IS NULL OR approved_by <> drawn_up_by)
        AND (approved_at IS NULL OR submitted_at IS NOT NULL)),
    -- Sent exactly when approved: approval is what sends it (17 §4.3 step 7).
    CONSTRAINT ck_production_order_sent CHECK ((sent_at IS NULL) = (approved_at IS NULL)
        AND ((status IN ('DRAFT', 'PENDING_APPROVAL')) = (sent_at IS NULL) OR status = 'CANCELLED')),
    CONSTRAINT ck_production_order_submitted CHECK (status <> 'PENDING_APPROVAL' OR submitted_at IS NOT NULL),
    -- BR-23: a draft that was submitted before is one the approver returned, with a reason.
    CONSTRAINT ck_production_order_returned CHECK (status <> 'DRAFT' OR submitted_at IS NULL
        OR returned_reason IS NOT NULL),
    -- BR-06: cancelled and closed with who, when and why - and each only as its own status.
    CONSTRAINT ck_production_order_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL) AND (cancelled_at IS NULL) = (cancellation_reason IS NULL)
        AND (cancellation_reason IS NULL OR btrim(cancellation_reason) <> '')),
    CONSTRAINT ck_production_order_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)
        AND (closed_at IS NULL) = (closed_by IS NULL) AND (closed_at IS NULL) = (closing_reason IS NULL)
        AND (closing_reason IS NULL OR btrim(closing_reason) <> '')),
    -- Mass printing started exactly from IN_PRODUCTION on (a closed one may have stopped before it).
    CONSTRAINT ck_production_order_started CHECK (
        (status IN ('IN_PRODUCTION', 'PARTIALLY_RECEIVED', 'RECEIVED')) <= (production_started_at IS NOT NULL)
        AND (production_started_at IS NULL OR status IN ('IN_PRODUCTION', 'PARTIALLY_RECEIVED', 'RECEIVED',
                                                         'CLOSED')))
);

CREATE INDEX ix_production_order_sales_order ON ordering.production_order (sales_order_id, delivery_batch_id);
CREATE INDEX ix_production_order_status ON ordering.production_order (status, receiving_warehouse_id);
CREATE INDEX ix_production_order_producer ON ordering.production_order (producer_id);

COMMENT ON TABLE ordering.production_order IS
    'What a producer makes for one delivery batch of a confirmed order of a contract (kltn-docs 17 §4.3, §5.3): drawn '
    'up by the Production Coordinator, approved by someone else, sent, received into the order''s shipping warehouse.';

CREATE TABLE ordering.production_order_line
(
    id                   UUID           NOT NULL,
    production_order_id  UUID           NOT NULL,
    order_line_id        UUID           NOT NULL,
    -- The customer's own SKU of the order line (BR-05).
    sku                  VARCHAR(64)    NOT NULL,
    cartons              INTEGER        NOT NULL,
    units_per_carton     INTEGER        NOT NULL,
    total_units          INTEGER GENERATED ALWAYS AS (cartons * units_per_carton) STORED,
    -- The approved design the goods are printed to, and the checksum of its print file bundle (BR-05).
    design_snapshot_id   UUID           NOT NULL,
    print_file_checksum  VARCHAR(128)   NOT NULL,
    -- How the producer packs and labels the cartons.
    packing_requirements VARCHAR(500),
    -- Cartons received against it (4A-5); never more than ordered.
    received_cartons     INTEGER        NOT NULL DEFAULT 0,

    CONSTRAINT pk_production_order_line PRIMARY KEY (id),
    CONSTRAINT uk_production_order_line UNIQUE (production_order_id, order_line_id),
    CONSTRAINT fk_production_order_line_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_order_line_order_line FOREIGN KEY (order_line_id) REFERENCES ordering.order_line (id),
    CONSTRAINT fk_production_order_line_snapshot FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id),
    CONSTRAINT ck_production_order_line_cartons CHECK (cartons BETWEEN 1 AND 10000000
        AND units_per_carton BETWEEN 1 AND 1000000),
    CONSTRAINT ck_production_order_line_received CHECK (received_cartons BETWEEN 0 AND cartons),
    CONSTRAINT ck_production_order_line_packing CHECK (packing_requirements IS NULL
        OR btrim(packing_requirements) <> '')
);

CREATE INDEX ix_production_order_line_order_line ON ordering.production_order_line (order_line_id);
CREATE INDEX ix_production_order_line_sku ON ordering.production_order_line (sku);

-- Every status change, who, when and why - never rewritten.
CREATE TABLE ordering.production_order_status_history
(
    id                  UUID         NOT NULL,
    production_order_id UUID         NOT NULL,
    from_status         VARCHAR(24),
    to_status           VARCHAR(24)  NOT NULL,
    reason              VARCHAR(500),
    changed_by          UUID,
    changed_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_production_order_status_history PRIMARY KEY (id),
    CONSTRAINT fk_production_order_status_history_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id),
    CONSTRAINT fk_production_order_status_history_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_production_order_status_history ON ordering.production_order_status_history
    (production_order_id, changed_at);

CREATE FUNCTION ordering.forbid_production_order_history_change() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'production order history is kept as written' USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER tg_production_order_status_history_append_only
    BEFORE UPDATE OR DELETE ON ordering.production_order_status_history
    FOR EACH ROW EXECUTE FUNCTION ordering.forbid_production_order_history_change();

-- 17 §5.3: the edges, as data (ProductionOrderIntegrationTest holds the Java enum to it).
CREATE TABLE ordering.production_order_status_transition
(
    from_status VARCHAR(24) NOT NULL,
    to_status   VARCHAR(24) NOT NULL,
    CONSTRAINT pk_production_order_status_transition PRIMARY KEY (from_status, to_status)
);

INSERT INTO ordering.production_order_status_transition (from_status, to_status)
VALUES ('DRAFT', 'PENDING_APPROVAL'), ('DRAFT', 'CANCELLED'),
       ('PENDING_APPROVAL', 'SENT'), ('PENDING_APPROVAL', 'DRAFT'), ('PENDING_APPROVAL', 'CANCELLED'),
       ('SENT', 'ACCEPTED'), ('SENT', 'IN_PRODUCTION'), ('SENT', 'CANCELLED'),
       ('ACCEPTED', 'SAMPLE_PENDING'), ('ACCEPTED', 'IN_PRODUCTION'), ('ACCEPTED', 'CANCELLED'),
       ('SAMPLE_PENDING', 'IN_PRODUCTION'), ('SAMPLE_PENDING', 'CANCELLED'),
       ('IN_PRODUCTION', 'PARTIALLY_RECEIVED'), ('IN_PRODUCTION', 'RECEIVED'), ('IN_PRODUCTION', 'CLOSED'),
       ('PARTIALLY_RECEIVED', 'RECEIVED'), ('PARTIALLY_RECEIVED', 'CLOSED');

-- What a production order is about never changes; its status moves only along an edge; it is for a batch of its order's
-- contract, received at its order's shipping warehouse, for its order's customer.
CREATE FUNCTION ordering.check_production_order() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    sales_order RECORD;
    batch_order UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'production order %: production orders are kept', OLD.code USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'production order %: drawn up as a draft (17 §5.3)', NEW.code USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.code IS DISTINCT FROM OLD.code OR NEW.sales_order_id IS DISTINCT FROM OLD.sales_order_id
            OR NEW.delivery_batch_id IS DISTINCT FROM OLD.delivery_batch_id
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.drawn_up_by IS DISTINCT FROM OLD.drawn_up_by THEN
            RAISE EXCEPTION 'production order %: what it is for does not change', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status <> OLD.status AND NOT EXISTS (SELECT 1 FROM ordering.production_order_status_transition t
                                                     WHERE t.from_status = OLD.status AND t.to_status = NEW.status) THEN
            RAISE EXCEPTION 'production order %: % -> % is not a transition (17 §5.3)', OLD.code, OLD.status,
                NEW.status USING ERRCODE = 'check_violation';
        END IF;
        -- What the coordinator edits is settled once it is submitted (B33; the warehouse moves with the order's
        -- shipping warehouse while it is not yet sent).
        IF OLD.status NOT IN ('DRAFT') AND (NEW.producer_id IS DISTINCT FROM OLD.producer_id
            OR NEW.need_by_date IS DISTINCT FROM OLD.need_by_date
            OR NEW.sample_approval_required IS DISTINCT FROM OLD.sample_approval_required
            OR NEW.sample_waiver_reason IS DISTINCT FROM OLD.sample_waiver_reason
            OR NEW.unit_price IS DISTINCT FROM OLD.unit_price OR NEW.note IS DISTINCT FROM OLD.note) THEN
            RAISE EXCEPTION 'production order %: a % production order is not edited', OLD.code, OLD.status
                USING ERRCODE = 'check_violation';
        END IF;
        -- Who approved and sent it, and when, is written once (BR-23).
        IF OLD.approved_at IS NOT NULL AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.sent_at IS DISTINCT FROM OLD.sent_at
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN
            RAISE EXCEPTION 'production order %: its approval is kept as written', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.status NOT IN ('DRAFT', 'PENDING_APPROVAL')
            AND NEW.receiving_warehouse_id IS DISTINCT FROM OLD.receiving_warehouse_id THEN
            RAISE EXCEPTION 'production order %: sent, it is received where it was sent to', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    SELECT o.customer_id, o.shipping_warehouse_id, o.contract_signed_at INTO sales_order
      FROM ordering.customer_order o WHERE o.id = NEW.sales_order_id;
    SELECT c.order_id INTO batch_order
      FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
     WHERE b.id = NEW.delivery_batch_id;
    IF sales_order.contract_signed_at IS NULL OR batch_order IS DISTINCT FROM NEW.sales_order_id THEN
        RAISE EXCEPTION 'production order %: for a delivery batch of a signed order''s contract (17 BR-05)', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_id IS DISTINCT FROM sales_order.customer_id THEN
        RAISE EXCEPTION 'production order %: for its order''s customer', NEW.code USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status IN ('DRAFT', 'PENDING_APPROVAL')
        AND NEW.receiving_warehouse_id IS DISTINCT FROM sales_order.shipping_warehouse_id THEN
        RAISE EXCEPTION 'production order %: received at its order''s shipping warehouse (17 BR-22)', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_production_order
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.production_order
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order();

-- A line is a line of the production order's sales order scheduled in its delivery batch, with its SKU, never more
-- cartons than the batch delivers; changed only while the production order is a draft (what is received is counted by
-- 4A-5).
CREATE FUNCTION ordering.check_production_order_line() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    po          RECORD;
    line        RECORD;
BEGIN
    SELECT p.code, p.status, p.sales_order_id, p.delivery_batch_id INTO po FROM ordering.production_order p
     WHERE p.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.production_order_id ELSE NEW.production_order_id END;
    IF TG_OP = 'DELETE' THEN
        IF po.status IS NOT NULL AND po.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'production order %: a % production order keeps its lines', po.code, po.status
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND po.status <> 'DRAFT' AND (NEW.order_line_id IS DISTINCT FROM OLD.order_line_id
        OR NEW.sku IS DISTINCT FROM OLD.sku OR NEW.cartons IS DISTINCT FROM OLD.cartons
        OR NEW.units_per_carton IS DISTINCT FROM OLD.units_per_carton
        OR NEW.design_snapshot_id IS DISTINCT FROM OLD.design_snapshot_id
        OR NEW.print_file_checksum IS DISTINCT FROM OLD.print_file_checksum
        OR NEW.packing_requirements IS DISTINCT FROM OLD.packing_requirements) THEN
        RAISE EXCEPTION 'production order %: a % production order''s lines are not edited', po.code, po.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND po.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'production order %: lines are drawn up while it is a draft', po.code
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT l.order_id, l.sku, l.units_per_carton INTO line FROM ordering.order_line l WHERE l.id = NEW.order_line_id;
    IF line.order_id IS DISTINCT FROM po.sales_order_id OR line.sku IS DISTINCT FROM NEW.sku
        OR line.units_per_carton IS DISTINCT FROM NEW.units_per_carton THEN
        RAISE EXCEPTION 'production order %: a line is one of its order''s lines, with its SKU and carton (17 BR-05)',
            po.code USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_line b
                    WHERE b.delivery_batch_id = po.delivery_batch_id AND b.order_line_id = NEW.order_line_id
                      AND b.cartons >= NEW.cartons) THEN
        RAISE EXCEPTION 'production order %: a line is of its delivery batch, never above its cartons (17 BR-05)',
            po.code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_production_order_line
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.production_order_line
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_line();

-- At commit: a production order has at least one line.
CREATE FUNCTION ordering.check_production_order_has_lines() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    the_order UUID;
BEGIN
    -- Separate statements: each table's row is read only by the branch for that table (PL/pgSQL plans every operand of
    -- one expression, and the production order has no production_order_id).
    IF TG_TABLE_NAME = 'production_order' THEN
        the_order := NEW.id;
    ELSE
        the_order := OLD.production_order_id;
    END IF;
    IF EXISTS (SELECT 1 FROM ordering.production_order p WHERE p.id = the_order)
        AND NOT EXISTS (SELECT 1 FROM ordering.production_order_line l WHERE l.production_order_id = the_order) THEN
        RAISE EXCEPTION 'production order %: it makes at least one line', the_order USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_production_order_has_lines
    AFTER INSERT ON ordering.production_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_has_lines();

CREATE CONSTRAINT TRIGGER tg_production_order_line_removed
    AFTER DELETE ON ordering.production_order_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_has_lines();

-- Permissions --------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'production-orders:' || a.action, 'production-orders', a.action, 0, NOW(), 'flyway'
  FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE', 'APPROVE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, resource, actions) AS (
    VALUES
        -- 00 §2.1 (2026-11-07): the coordinator draws up, submits, cancels and closes production orders, from the
        -- orders' shortages - so it reads the orders, their contracts and what they hold.
        ('PRODUCTION_COORDINATOR', 'production-orders',               'VIEW_PAGE,READ,CREATE,UPDATE'),
        ('PRODUCTION_COORDINATOR', 'sales-orders',                    'VIEW_PAGE,READ'),
        ('PRODUCTION_COORDINATOR', 'sales-contracts',                 'READ'),
        ('PRODUCTION_COORDINATOR', 'sales-order-shipping-warehouses', 'READ'),
        -- 17 BR-23: the Sales Manager approves (never their own) and returns.
        ('SALES_MANAGER',          'production-orders',               'VIEW_PAGE,READ,APPROVE'),
        -- Sales follows them to tell the customer; the warehouse manager sees what comes to their warehouse.
        ('SALES_STAFF',            'production-orders',               'VIEW_PAGE,READ'),
        ('WAREHOUSE_MANAGER',      'production-orders',               'VIEW_PAGE,READ'),
        ('SYSTEM_ADMIN',           'production-orders',               'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = wanted.resource
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
