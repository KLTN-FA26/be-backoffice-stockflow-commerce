-- =============================================================================
-- A customer submits their design for review (kltn-docs 12 §4.3; BR-05, BR-07, BR-08, BR-10; Design Studio plan D2):
-- the browser renders the print file, the customer preview and the technical spec from the layout, uploads them
-- naming the layout's checksum, and the server's preflight decides whether the design goes to the Sales Manager.
--
--   * design_artifact.document_checksum and template_version - the SHA-256 of the canonical layout a file was
--     rendered from (as design_document.checksum) and the version of the template it was drawn on, both null for a
--     file of the staff workflow; a submission needs all three files rendered from the layout and the template as they
--     are now (a template is edited in place, and a draft follows it - B39).
--   * ck_design_draft_review_requested - a customer's design waiting for review says since when (stated twice: the
--     submission sets it).
--   * ix_design_draft_review_queue - the Sales Manager's queue, oldest first.
--
-- No data changes: no customer design has been submitted before this.
-- =============================================================================

ALTER TABLE design.design_artifact
    ADD COLUMN document_checksum VARCHAR(64),
    ADD COLUMN template_version  BIGINT,
    ADD CONSTRAINT ck_design_artifact_document_checksum
        CHECK (document_checksum IS NULL OR document_checksum ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_design_artifact_rendered_from
        CHECK ((document_checksum IS NULL) = (template_version IS NULL));

ALTER TABLE design.design_draft
    ADD CONSTRAINT ck_design_draft_review_requested
        CHECK (status <> 'PENDING_REVIEW' OR template_id IS NULL OR review_requested_at IS NOT NULL);

CREATE INDEX ix_design_draft_review_queue ON design.design_draft (review_requested_at, id)
    WHERE status = 'PENDING_REVIEW';
