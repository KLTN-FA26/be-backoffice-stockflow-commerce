ALTER TABLE design.design_draft
    ADD COLUMN reviewer_user_id UUID,
    ADD COLUMN last_editor_user_id UUID,
    ADD COLUMN reviewed_by UUID,
    ADD COLUMN review_notes VARCHAR(2000);

ALTER TABLE design.design_snapshot DROP CONSTRAINT uk_design_snapshot_checksum;
ALTER TABLE design.design_snapshot
    ADD COLUMN artifact_id UUID,
    ADD COLUMN confirmed_by UUID,
    ADD COLUMN spec VARCHAR(4000),
    ADD COLUMN reviewed_by UUID,
    ADD COLUMN review_notes VARCHAR(2000),
    ADD CONSTRAINT fk_snapshot_artifact FOREIGN KEY (draft_id, artifact_id)
        REFERENCES design.design_artifact(draft_id, id),
    ADD CONSTRAINT ck_snapshot_confirmation CHECK (artifact_id IS NULL OR
        (confirmed_by IS NOT NULL AND reviewed_by IS NOT NULL AND spec IS NOT NULL
         AND checksum ~ '^[0-9a-f]{64}$'));
CREATE UNIQUE INDEX uk_design_confirmed_draft ON design.design_snapshot(draft_id) WHERE artifact_id IS NOT NULL;
CREATE INDEX ix_design_draft_owner ON design.design_draft(owner_user_id);
CREATE INDEX ix_design_draft_assignee ON design.design_draft(assigned_user_id);
CREATE INDEX ix_design_draft_reviewer ON design.design_draft(reviewer_user_id);

-- Legacy rows are retained but cannot pass the new confirmation guard without a new review.
UPDATE design.design_draft SET preflight_passed = FALSE;
ALTER TABLE design.design_draft ADD CONSTRAINT ck_design_review_evidence CHECK
    (NOT preflight_passed OR (current_artifact_id IS NOT NULL AND reviewed_by IS NOT NULL
        AND review_notes IS NOT NULL AND reviewer_user_id = reviewed_by
        AND reviewed_by IS DISTINCT FROM owner_user_id
        AND reviewed_by IS DISTINCT FROM last_editor_user_id));

-- Application accounts must not be able to rewrite the evidence an order references.
CREATE FUNCTION design.reject_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Confirmed design snapshots are immutable';
END;
$$;
CREATE TRIGGER immutable_design_snapshot BEFORE UPDATE OR DELETE ON design.design_snapshot
    FOR EACH ROW EXECUTE FUNCTION design.reject_snapshot_mutation();
