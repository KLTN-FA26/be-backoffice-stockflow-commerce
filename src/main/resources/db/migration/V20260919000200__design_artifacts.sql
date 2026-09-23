-- Existing drafts stay inaccessible until the studio supplies a verified identity owner.
-- CRM customer ids and identity user ids must not be conflated in a guessed backfill.
ALTER TABLE design.design_draft
    ADD COLUMN owner_user_id UUID,
    ADD COLUMN assigned_user_id UUID,
    ADD COLUMN current_artifact_id UUID,
    ADD COLUMN preflight_passed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE design.design_artifact (
    id UUID NOT NULL PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES design.design_draft(id),
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    stored_at TIMESTAMPTZ NOT NULL,
    checksum VARCHAR(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),
    CONSTRAINT uk_design_artifact_draft_id UNIQUE (draft_id, id)
);
CREATE INDEX ix_design_artifact_draft ON design.design_artifact(draft_id);
-- Hibernate may flush the draft UPDATE before the artifact INSERT.
ALTER TABLE design.design_draft ADD CONSTRAINT fk_design_current_artifact
    FOREIGN KEY (id, current_artifact_id) REFERENCES design.design_artifact(draft_id, id)
    DEFERRABLE INITIALLY DEFERRED;
