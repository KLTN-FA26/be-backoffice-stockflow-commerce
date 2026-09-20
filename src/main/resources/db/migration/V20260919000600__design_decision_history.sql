CREATE TABLE design.design_decision (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES design.design_draft(id),
    artifact_id UUID,
    actor_id UUID NOT NULL,
    decision VARCHAR(40) NOT NULL,
    notes VARCHAR(4000) NOT NULL,
    draft_version BIGINT NOT NULL CHECK (draft_version >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),
    FOREIGN KEY (draft_id, artifact_id) REFERENCES design.design_artifact(draft_id, id)
);
CREATE INDEX ix_design_decision_history ON design.design_decision(draft_id, created_at DESC, id DESC);
CREATE TRIGGER immutable_design_decision BEFORE UPDATE OR DELETE ON design.design_decision
    FOR EACH ROW EXECUTE FUNCTION design.reject_snapshot_mutation();
