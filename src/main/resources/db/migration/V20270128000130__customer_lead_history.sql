-- =============================================================================
-- The history of a lead (sales-owner plan §10.7; owner decision D44 of 2026-11-28). Module: customer.
--
-- One row per change of a lead's status or Sales, with who, when and a note. Append-only (a trigger refuses UPDATE and
-- DELETE; rows leave only with their lead). D44: when a salesperson's account is locked or disabled, their open leads
-- (ASSIGNED, CONTACTED) go back to NEW, unassigned, into the managers' queue - each with a row here saying so.
-- =============================================================================

CREATE TABLE customer.lead_history (
    id               UUID          NOT NULL,
    lead_id          UUID          NOT NULL,
    from_status      VARCHAR(32),
    to_status        VARCHAR(32)   NOT NULL,
    -- The lead's Sales before and after.
    from_user_id     UUID,
    to_user_id       UUID,
    note             VARCHAR(1000),
    -- Who did it; null for the system (the public form, the deactivation listener).
    changed_by       UUID,
    changed_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_lead_history PRIMARY KEY (id),
    CONSTRAINT fk_lead_history_lead FOREIGN KEY (lead_id) REFERENCES customer.lead (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_history_from_user FOREIGN KEY (from_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_lead_history_to_user FOREIGN KEY (to_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_lead_history_changed_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_lead_history_from_status CHECK (from_status IS NULL OR from_status IN (
        'NEW', 'ASSIGNED', 'CONTACTED', 'CONVERTED', 'DISQUALIFIED')),
    CONSTRAINT ck_lead_history_to_status CHECK (to_status IN (
        'NEW', 'ASSIGNED', 'CONTACTED', 'CONVERTED', 'DISQUALIFIED')),
    -- A row records a change: of status, or of Sales.
    CONSTRAINT ck_lead_history_change CHECK (from_status IS DISTINCT FROM to_status
                                             OR from_user_id IS DISTINCT FROM to_user_id)
);

COMMENT ON TABLE customer.lead_history IS
    'Every change of a lead''s status or Sales, with who, when and a note (sales-owner plan §10.7, D44). Append-only.';

CREATE INDEX ix_lead_history_lead ON customer.lead_history (lead_id, changed_at, id);

CREATE TRIGGER tg_lead_history_append_only
    BEFORE UPDATE OR DELETE ON customer.lead_history
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('customer.lead', 'lead_id');
