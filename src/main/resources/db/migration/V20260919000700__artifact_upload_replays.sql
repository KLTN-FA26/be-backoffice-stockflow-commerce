ALTER TABLE design.design_artifact ADD COLUMN upload_key VARCHAR(300);
CREATE UNIQUE INDEX uk_design_upload_key ON design.design_artifact(draft_id, upload_key) WHERE upload_key IS NOT NULL;
