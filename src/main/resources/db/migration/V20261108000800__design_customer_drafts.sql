-- =============================================================================
-- A customer designs on their own (kltn-docs 12 §4.1-§4.2, §4.7, §5.1; decided 2026-11-05): a draft is started by the
-- customer on one variant's customization template, its layout is saved as a document, and it is renamed, deleted
-- while still a draft, or revised from an approved design.
--
-- design_draft gains:
--   * the variant, its SKU, the customization template and its code it is drawn on (all four or none: a draft a staff
--     designer started before this has none, and can only be read);
--   * review_requested_at, reviewed_at - when it was last submitted for review and last decided; written by the
--     submission and the review of a customer's design (2F-5, 2F-6), null before;
--   * archived_at - a deleted draft (BR-16) is archived, never removed: its history stays;
-- and its statuses become DRAFT, PENDING_REVIEW, APPROVED, ARCHIVED (decision D1 of the Design Studio plan: one word
-- per concept, as the business documents name them). SUBMITTED - a draft waiting for the customer's confirmation in
-- the flow being replaced - is PENDING_REVIEW; REJECTED was never written and goes.
--
-- design_document holds a draft's layout as canonical JSON (RFC 8785) and its SHA-256, the checksum the files of a
-- submission are bound to (BR-08, BR-10): the checksum is stated twice, computed by the application and checked here.
-- design_asset holds the images a customer uploads into a draft (BR-04): append-only, a revision copies the rows.
--
-- Data: drafts SUBMITTED become PENDING_REVIEW, REJECTED ones DRAFT; the old values are archived first.
-- =============================================================================

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'design.design_draft.status', d.id, jsonb_build_object('id', d.id, 'status', d.status)
FROM design.design_draft d WHERE d.status IN ('SUBMITTED', 'REJECTED');

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), and design statuses renamed when customers began designing (V20261108000800). Read-only '
        'history.';

-- The old CHECK knows only the old words: it goes before the rows change, the new one comes after.
ALTER TABLE design.design_draft DROP CONSTRAINT ck_design_draft_status;
UPDATE design.design_draft SET status = 'PENDING_REVIEW' WHERE status = 'SUBMITTED';
UPDATE design.design_draft SET status = 'DRAFT' WHERE status = 'REJECTED';

ALTER TABLE design.design_draft
    ADD COLUMN variant_id          UUID,
    ADD COLUMN sku                 VARCHAR(64),
    ADD COLUMN template_id         UUID,
    ADD COLUMN template_code       VARCHAR(50),
    ADD COLUMN review_requested_at TIMESTAMPTZ,
    ADD COLUMN reviewed_at         TIMESTAMPTZ,
    ADD COLUMN archived_at         TIMESTAMPTZ,
    ADD CONSTRAINT ck_design_draft_status CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'ARCHIVED')),
    ADD CONSTRAINT ck_design_draft_template
        CHECK ((variant_id IS NULL) = (sku IS NULL)
            AND (variant_id IS NULL) = (template_id IS NULL)
            AND (variant_id IS NULL) = (template_code IS NULL)),
    ADD CONSTRAINT ck_design_draft_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    ADD CONSTRAINT fk_design_draft_variant FOREIGN KEY (variant_id) REFERENCES product.variants (id),
    ADD CONSTRAINT fk_design_draft_template FOREIGN KEY (template_id) REFERENCES product.customization_templates (id);

-- What the 50-draft limit counts (B37), per customer.
CREATE INDEX ix_design_draft_customer_status ON design.design_draft (customer_id, status);


CREATE TABLE design.design_document
(
    draft_id   UUID        NOT NULL,
    document   TEXT        NOT NULL,
    checksum   VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_design_document PRIMARY KEY (draft_id),
    CONSTRAINT fk_design_document_draft FOREIGN KEY (draft_id) REFERENCES design.design_draft (id),
    CONSTRAINT ck_design_document_size CHECK (octet_length(document) <= 1000000),
    CONSTRAINT ck_design_document_checksum
        CHECK (checksum = encode(sha256(convert_to(document, 'UTF8')), 'hex'))
);


CREATE TABLE design.design_asset
(
    draft_id      UUID         NOT NULL,
    id            UUID         NOT NULL,
    storage_key   VARCHAR(500) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    checksum      VARCHAR(64)  NOT NULL,
    width_px      INTEGER      NOT NULL,
    height_px     INTEGER      NOT NULL,
    upload_key    VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(100),

    CONSTRAINT pk_design_asset PRIMARY KEY (draft_id, id),
    CONSTRAINT fk_design_asset_draft FOREIGN KEY (draft_id) REFERENCES design.design_draft (id),
    CONSTRAINT ck_design_asset_key CHECK (storage_key LIKE 'design-assets/%'),
    CONSTRAINT ck_design_asset_type CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    CONSTRAINT ck_design_asset_size CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    CONSTRAINT ck_design_asset_pixels CHECK (width_px > 0 AND height_px > 0
        AND width_px::BIGINT * height_px <= 40000000),
    CONSTRAINT ck_design_asset_checksum CHECK (checksum ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_design_asset_upload_key ON design.design_asset (draft_id, upload_key)
    WHERE upload_key IS NOT NULL;
CREATE INDEX ix_design_asset_storage_key ON design.design_asset (storage_key);

-- An image a draft's layout may name is never changed or taken away: an approved design's snapshot names it too.
CREATE FUNCTION design.reject_design_asset_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'design assets are append-only (draft %, asset %)', OLD.draft_id, OLD.id USING ERRCODE = '23514';
END $$;

CREATE TRIGGER tg_design_asset_append_only BEFORE UPDATE OR DELETE ON design.design_asset
    FOR EACH ROW EXECUTE FUNCTION design.reject_design_asset_mutation();
