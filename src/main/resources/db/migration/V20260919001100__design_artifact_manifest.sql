ALTER TABLE design.design_artifact
    ADD COLUMN artifact_role VARCHAR(40) NOT NULL DEFAULT 'CUSTOMER_PREVIEW',
    ADD CONSTRAINT ck_design_artifact_role CHECK (artifact_role IN
        ('CUSTOMER_PREVIEW','TECHNICAL_SPEC','PRINT_READY','PRODUCTION_INSTRUCTION'));
ALTER TABLE design.design_artifact ALTER COLUMN artifact_role DROP DEFAULT;

ALTER TABLE design.design_draft ADD COLUMN current_artifacts JSONB NOT NULL DEFAULT '{}'::jsonb;
UPDATE design.design_draft
SET current_artifacts = jsonb_build_object('CUSTOMER_PREVIEW', current_artifact_id)
WHERE current_artifact_id IS NOT NULL;
ALTER TABLE design.design_draft ADD CONSTRAINT ck_design_current_artifacts_object
    CHECK (jsonb_typeof(current_artifacts) = 'object');
ALTER TABLE design.design_draft ALTER COLUMN current_artifacts DROP DEFAULT;

ALTER TABLE design.design_snapshot ADD COLUMN artifact_manifest JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE design.design_snapshot ADD CONSTRAINT ck_design_snapshot_manifest_array
    CHECK (jsonb_typeof(artifact_manifest) = 'array');
ALTER TABLE design.design_snapshot ALTER COLUMN artifact_manifest DROP DEFAULT;
