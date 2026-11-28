-- =============================================================================
-- The Sales owner of a customer (sales-owner plan 2026-11-28, approved: strict mode, D2-D13; API contract).
-- Module: customer.
--
-- Until now nothing stored which salesperson looks after a customer: chat, the follow-up notices and the documents each
-- worked it out from a different document (plan §1). From here a customer has one stored owner, with history:
--
--   customer.customer.sales_owner_id / _since / _state   the owner, since when, and whether the customer has one:
--       ASSIGNED          an owner (sales_owner_id) looks after it;
--       NEEDS_ASSIGNMENT  its owner left (locked or disabled, BR-S08); the former owner is kept, a manager assigns;
--       UNASSIGNED        it never had one (no owner, no date).
--   customer.customer_sales_owner_history                 every change: from whom, to whom, why, by whom, in which
--                                                         transfer batch. Append-only (a trigger refuses UPDATE and
--                                                         DELETE), so the trail of who had a customer cannot be edited.
--   customer.sales_owner_transfer_batch / _item           a manager's bulk or temporary transfer (BR-S09, §10.1): one
--                                                         batch per request id (a retry finds it), one item per customer
--                                                         moved or skipped, and the return of a temporary batch.
--   customer.sales_assignment_setting                     per salesperson: paused for new customers, capacity a month
--                                                         (D13, Odoo's "pause" and "capacity").
--
-- Invariants are stated here and in the aggregate / service: the state agrees with the columns; an owner being
-- assigned is an ACTIVE SALES_STAFF account (BR-S04, owner decision D42); a change of reason OTHER carries a note
-- (BR-S03).
--
-- Every existing customer starts UNASSIGNED; V20270128000145 fills the owners from the documents (plan §4 case 28).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Transfer batches first: the history names the batch that moved a customer.
-- ---------------------------------------------------------------------------
CREATE TABLE customer.sales_owner_transfer_batch (
    id               UUID          NOT NULL,
    -- The client's request id: a retried bulk transfer finds its batch instead of moving anything twice (BR-S09).
    request_id       UUID          NOT NULL,
    from_user_id     UUID          NOT NULL,
    reason           VARCHAR(32)   NOT NULL,
    note             VARCHAR(1000),
    -- A temporary transfer (long leave, §10.1) can be returned in one step; returns_batch_id names the batch a return
    -- gives back, and a temporary batch is returned at most once.
    temporary        BOOLEAN       NOT NULL DEFAULT FALSE,
    returns_batch_id UUID,
    notify_customer  BOOLEAN       NOT NULL DEFAULT FALSE,
    moved            INTEGER       NOT NULL DEFAULT 0,
    skipped          INTEGER       NOT NULL DEFAULT 0,
    requested_by     UUID,
    requested_at     TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_sales_owner_transfer_batch PRIMARY KEY (id),
    CONSTRAINT uk_sales_owner_transfer_batch_request UNIQUE (request_id),
    CONSTRAINT uk_sales_owner_transfer_batch_returns UNIQUE (returns_batch_id),
    CONSTRAINT fk_sales_owner_transfer_batch_from FOREIGN KEY (from_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_owner_transfer_batch_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_owner_transfer_batch_returns FOREIGN KEY (returns_batch_id)
        REFERENCES customer.sales_owner_transfer_batch (id),
    CONSTRAINT ck_sales_owner_transfer_batch_reason CHECK (reason IN ('STAFF_LEFT', 'WORKLOAD', 'CUSTOMER_REQUEST',
                                                                      'REGION', 'TEMPORARY_LEAVE', 'RETURN_FROM_LEAVE',
                                                                      'OTHER')),
    CONSTRAINT ck_sales_owner_transfer_batch_temporary CHECK (temporary = (reason = 'TEMPORARY_LEAVE')),
    CONSTRAINT ck_sales_owner_transfer_batch_return CHECK ((reason = 'RETURN_FROM_LEAVE') = (returns_batch_id IS NOT NULL)),
    CONSTRAINT ck_sales_owner_transfer_batch_note CHECK (reason <> 'OTHER' OR (note IS NOT NULL AND btrim(note) <> '')),
    CONSTRAINT ck_sales_owner_transfer_batch_counts CHECK (moved >= 0 AND skipped >= 0)
);

COMMENT ON TABLE customer.sales_owner_transfer_batch IS
    'A Sales Manager''s bulk or temporary transfer of one salesperson''s customers (sales-owner plan BR-S09, §10.1); '
    'one row per request id, so a retry does not move anything twice.';

-- ---------------------------------------------------------------------------
-- The owner on the customer.
-- ---------------------------------------------------------------------------
ALTER TABLE customer.customer
    ADD COLUMN sales_owner_id    UUID,
    ADD COLUMN sales_owner_since TIMESTAMPTZ,
    ADD COLUMN sales_owner_state VARCHAR(32) NOT NULL DEFAULT 'UNASSIGNED',
    ADD CONSTRAINT fk_customer_sales_owner FOREIGN KEY (sales_owner_id) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_customer_sales_owner_state
        CHECK (sales_owner_state IN ('ASSIGNED', 'NEEDS_ASSIGNMENT', 'UNASSIGNED')),
    -- UNASSIGNED has neither owner nor date; ASSIGNED and NEEDS_ASSIGNMENT have both (the latter keeps the owner who
    -- left, for the history and the managers' screen).
    ADD CONSTRAINT ck_customer_sales_owner_consistent CHECK (
        (sales_owner_state = 'UNASSIGNED' AND sales_owner_id IS NULL AND sales_owner_since IS NULL)
        OR (sales_owner_state <> 'UNASSIGNED' AND sales_owner_id IS NOT NULL AND sales_owner_since IS NOT NULL));

COMMENT ON COLUMN customer.customer.sales_owner_id IS
    'The salesperson who looks after the customer (sales-owner plan §2.1); for NEEDS_ASSIGNMENT, the one who left.';
COMMENT ON COLUMN customer.customer.sales_owner_state IS
    'ASSIGNED (has an owner), NEEDS_ASSIGNMENT (owner left, a manager assigns), UNASSIGNED (never had one).';

-- "My customers", a salesperson's workload, a bulk transfer: all read a person's customers.
CREATE INDEX ix_customer_sales_owner ON customer.customer (sales_owner_id) WHERE sales_owner_id IS NOT NULL;
-- The managers' "needs an owner" list.
CREATE INDEX ix_customer_sales_owner_pending ON customer.customer (sales_owner_state)
    WHERE sales_owner_state <> 'ASSIGNED';

-- BR-S04 and owner decision D42, stated in the database too: a customer is ASSIGNED only to an ACTIVE account holding
-- SALES_STAFF (a Sales Manager or an admin coordinates, never owns; a portal account never holds SALES_STAFF). Checked
-- only when an owner is being assigned (the owner or the state changes into ASSIGNED): an owner locked later leaves the
-- row as it is, and the deactivation listener marks it NEEDS_ASSIGNMENT.
CREATE FUNCTION customer.check_sales_owner_assignable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sales_owner_state = 'ASSIGNED'
       AND (TG_OP = 'INSERT' OR OLD.sales_owner_state <> 'ASSIGNED'
            OR OLD.sales_owner_id IS DISTINCT FROM NEW.sales_owner_id)
       AND NOT EXISTS (SELECT 1 FROM identity.app_user u
                         JOIN identity.user_role ur ON ur.user_id = u.id
                         JOIN identity.app_role r ON r.id = ur.role_id
                        WHERE u.id = NEW.sales_owner_id AND u.status = 'ACTIVE' AND r.code = 'SALES_STAFF') THEN
        RAISE EXCEPTION 'A customer''s sales owner must be an active salesperson (customer %)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_customer_sales_owner_assignable
    BEFORE INSERT OR UPDATE OF sales_owner_id, sales_owner_state ON customer.customer
    FOR EACH ROW EXECUTE FUNCTION customer.check_sales_owner_assignable();

-- ---------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------
CREATE TABLE customer.customer_sales_owner_history (
    id               UUID          NOT NULL,
    customer_id      UUID          NOT NULL,
    -- The owner before the change (or the one who left); null when the customer had none.
    from_user_id     UUID,
    -- The owner after it; null when the customer was marked NEEDS_ASSIGNMENT.
    to_user_id       UUID,
    to_state         VARCHAR(32)   NOT NULL,
    reason           VARCHAR(32)   NOT NULL,
    note             VARCHAR(1000),
    -- Who did it; null for the system (the deactivation listener, the backfill).
    changed_by       UUID,
    changed_at       TIMESTAMPTZ   NOT NULL,
    batch_id         UUID,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_customer_sales_owner_history PRIMARY KEY (id),
    CONSTRAINT fk_customer_sales_owner_history_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_sales_owner_history_from FOREIGN KEY (from_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_customer_sales_owner_history_to FOREIGN KEY (to_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_customer_sales_owner_history_changed_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_customer_sales_owner_history_batch FOREIGN KEY (batch_id)
        REFERENCES customer.sales_owner_transfer_batch (id),
    CONSTRAINT ck_customer_sales_owner_history_reason CHECK (reason IN (
        'CREATED_BY_SALES', 'LEAD_CONVERTED', 'CLAIMED_FROM_QUEUE', 'STAFF_LEFT', 'WORKLOAD', 'CUSTOMER_REQUEST',
        'REGION', 'TEMPORARY_LEAVE', 'RETURN_FROM_LEAVE', 'BACKFILL', 'OTHER')),
    -- A change either gives the customer an owner or marks it as waiting for one after its owner left.
    CONSTRAINT ck_customer_sales_owner_history_target CHECK (
        (to_state = 'ASSIGNED' AND to_user_id IS NOT NULL)
        OR (to_state = 'NEEDS_ASSIGNMENT' AND to_user_id IS NULL AND from_user_id IS NOT NULL)),
    CONSTRAINT ck_customer_sales_owner_history_note CHECK (reason <> 'OTHER' OR (note IS NOT NULL AND btrim(note) <> ''))
);

COMMENT ON TABLE customer.customer_sales_owner_history IS
    'Every change of a customer''s Sales owner, with who, when, why and the batch (sales-owner plan BR-S03, BR-S06). '
    'Append-only: rows leave only with their customer.';

CREATE INDEX ix_customer_sales_owner_history_customer
    ON customer.customer_sales_owner_history (customer_id, changed_at DESC, id DESC);
CREATE INDEX ix_customer_sales_owner_history_batch
    ON customer.customer_sales_owner_history (batch_id) WHERE batch_id IS NOT NULL;

CREATE TRIGGER tg_customer_sales_owner_history_append_only
    BEFORE UPDATE OR DELETE ON customer.customer_sales_owner_history
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('customer.customer', 'customer_id');

-- ---------------------------------------------------------------------------
-- Transfer items.
-- ---------------------------------------------------------------------------
CREATE TABLE customer.sales_owner_transfer_item (
    id               UUID          NOT NULL,
    batch_id         UUID          NOT NULL,
    customer_id      UUID          NOT NULL,
    -- MOVED: the customer went to to_user_id. SKIPPED: it was no longer the batch's from_user's when the batch ran
    -- (changed meanwhile, BR-S09 / case 9), or the return found it moved on again (§10.1).
    outcome          VARCHAR(16)   NOT NULL,
    to_user_id       UUID,
    skip_reason      VARCHAR(32),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_sales_owner_transfer_item PRIMARY KEY (id),
    CONSTRAINT uk_sales_owner_transfer_item_customer UNIQUE (batch_id, customer_id),
    CONSTRAINT fk_sales_owner_transfer_item_batch FOREIGN KEY (batch_id)
        REFERENCES customer.sales_owner_transfer_batch (id) ON DELETE CASCADE,
    CONSTRAINT fk_sales_owner_transfer_item_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_sales_owner_transfer_item_to FOREIGN KEY (to_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_sales_owner_transfer_item_outcome CHECK (
        (outcome = 'MOVED' AND to_user_id IS NOT NULL AND skip_reason IS NULL)
        OR (outcome = 'SKIPPED' AND skip_reason IS NOT NULL)),
    CONSTRAINT ck_sales_owner_transfer_item_skip_reason CHECK (skip_reason IS NULL OR skip_reason IN (
        'OWNER_CHANGED', 'NOT_OWNED_BY_SOURCE', 'TARGET_NOT_ASSIGNABLE', 'MOVED_ON'))
);

CREATE INDEX ix_sales_owner_transfer_item_customer ON customer.sales_owner_transfer_item (customer_id);

-- ---------------------------------------------------------------------------
-- Assignment settings per salesperson (D13).
-- ---------------------------------------------------------------------------
CREATE TABLE customer.sales_assignment_setting (
    user_id            UUID          NOT NULL,
    -- Paused: suggested for no new customer or lead; the manager may still choose them.
    paused             BOOLEAN       NOT NULL DEFAULT FALSE,
    -- New customers a month the salesperson is meant to take; null for no limit.
    capacity_per_month INTEGER,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_sales_assignment_setting PRIMARY KEY (user_id),
    CONSTRAINT fk_sales_assignment_setting_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_sales_assignment_setting_capacity
        CHECK (capacity_per_month IS NULL OR capacity_per_month BETWEEN 0 AND 1000)
);

COMMENT ON TABLE customer.sales_assignment_setting IS
    'Per salesperson: paused for new customers, and how many new customers a month (sales-owner plan §10.4, D13).';
