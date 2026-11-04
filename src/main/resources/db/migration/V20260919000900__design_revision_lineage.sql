ALTER TABLE design.design_draft ADD COLUMN parent_snapshot_id UUID REFERENCES design.design_snapshot(id);
CREATE INDEX ix_design_draft_parent_snapshot ON design.design_draft(parent_snapshot_id);
