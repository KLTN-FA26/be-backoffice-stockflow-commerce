-- =============================================================================
-- The Sales Manager approves or returns a customer's design (kltn-docs 12 §4.4, §4.5; BR-06, BR-08, BR-09, BR-13), and
-- the staff workflow it replaces goes (Design Studio plan §2 "Bỏ đi").
--
--   1. design_snapshot keeps what was approved besides the files: the layout and its checksum, the images it places
--      (by id, key and checksum) and the template as it was - all four or none: a snapshot of the staff workflow has
--      none. The layout's checksum is stated twice. The customer's SKU issued for it names the snapshot itself
--      (product.variants.design_snapshot_id, unique), so the immutable snapshot needs no column for it.
--   2. design_decision says only the words of kltn-docs 12 §5.3 from now on (NOT VALID: rows of the staff workflow
--      keep their own words and are read as they are).
--   3. The staff workflow's columns of design_draft - the assigned designer and reviewer, the technical review flag and
--      the last editor - are archived and dropped: a design is the customer's alone (BR-01), and a Sales Manager holding
--      design-designs:APPROVE decides it, not a person assigned to it. A design that workflow left waiting for the
--      customer's confirmation (PENDING_REVIEW with no template since V20261108000800) can be neither confirmed nor
--      reviewed any more: it is a draft again, read-only as every design of that workflow (it has no template to be
--      submitted on). Its status is archived first.
--   4. The permission of that workflow, design-administration, goes; the roles that held it move their version so the
--      grant is gone at once (ADR-0008).
-- =============================================================================

-- 1. ---------------------------------------------------------------------------------------------
ALTER TABLE design.design_snapshot
    ADD COLUMN document          TEXT,
    ADD COLUMN document_checksum VARCHAR(64),
    ADD COLUMN assets            JSONB,
    ADD COLUMN template          JSONB,
    ADD CONSTRAINT ck_design_snapshot_layout
        CHECK ((document IS NULL) = (document_checksum IS NULL)
            AND (document IS NULL) = (assets IS NULL)
            AND (document IS NULL) = (template IS NULL)),
    ADD CONSTRAINT ck_design_snapshot_document_checksum
        CHECK (document IS NULL OR document_checksum = encode(sha256(convert_to(document, 'UTF8')), 'hex')),
    ADD CONSTRAINT ck_design_snapshot_assets CHECK (assets IS NULL OR jsonb_typeof(assets) = 'array'),
    ADD CONSTRAINT ck_design_snapshot_template CHECK (template IS NULL OR jsonb_typeof(template) = 'object');

-- 2. ---------------------------------------------------------------------------------------------
ALTER TABLE design.design_decision
    ADD CONSTRAINT ck_design_decision_type
        CHECK (decision IN ('CREATED', 'DERIVED_FROM_SNAPSHOT', 'PREFLIGHT_FAILED', 'SUBMITTED_FOR_REVIEW',
                            'RETURNED', 'APPROVED', 'ARCHIVED', 'QUOTE_REQUESTED', 'ADDED_TO_PROJECT',
                            'REMOVED_FROM_PROJECT'))
        NOT VALID;

-- 3. ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'design.design_draft.staff_workflow', d.id,
       jsonb_build_object('id', d.id, 'assigned_user_id', d.assigned_user_id, 'reviewer_user_id', d.reviewer_user_id,
                          'preflight_passed', d.preflight_passed, 'last_editor_user_id', d.last_editor_user_id)
FROM design.design_draft d
WHERE d.assigned_user_id IS NOT NULL OR d.reviewer_user_id IS NOT NULL OR d.preflight_passed
   OR d.last_editor_user_id IS NOT NULL;

ALTER TABLE design.design_draft
    DROP COLUMN assigned_user_id,
    DROP COLUMN reviewer_user_id,
    DROP COLUMN preflight_passed,
    DROP COLUMN last_editor_user_id;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'design.design_draft.status', d.id, jsonb_build_object('id', d.id, 'status', d.status)
FROM design.design_draft d
WHERE d.status = 'PENDING_REVIEW' AND d.template_id IS NULL;

UPDATE design.design_draft
SET status = 'DRAFT', version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE status = 'PENDING_REVIEW' AND template_id IS NULL;

-- 4. ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'identity.permission', p.id, to_jsonb(p) FROM identity.permission p
WHERE p.code LIKE 'design-administration:%';
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'identity.role_permission', rp.id, to_jsonb(rp) FROM identity.role_permission rp
WHERE rp.permission_id IN (SELECT id FROM identity.permission WHERE code LIKE 'design-administration:%');

WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id AND p.code LIKE 'design-administration:%'
    RETURNING rp.role_id
)
UPDATE identity.app_role r SET version = version + 1
WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE code LIKE 'design-administration:%';

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), and the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000). Read-only history.';
