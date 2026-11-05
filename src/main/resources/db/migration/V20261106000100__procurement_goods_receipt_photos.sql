-- Photos taken while goods are received and inspected (04b F-RCV steps 05 and 07): the warehouse's of what arrived,
-- counted on a DRAFT receipt; QC's of a line it inspects. Evidence for a dispute with the supplier, so append-only and
-- private: the files live in the object store under qc-evidence/ (FileCategory.QC_EVIDENCE), this table names them.

CREATE TABLE procurement.goods_receipt_photos
(
    id               UUID         NOT NULL,
    receipt_id       UUID         NOT NULL,
    receipt_line_id  UUID,
    stage            VARCHAR(16)  NOT NULL,
    storage_key      VARCHAR(512) NOT NULL,
    original_name    VARCHAR(255) NOT NULL,
    content_type     VARCHAR(32)  NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    checksum         VARCHAR(64)  NOT NULL,
    taken_by         UUID         NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_goods_receipt_photos PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_photos_receipt FOREIGN KEY (receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    -- A line dropped from a count that is still open (a corrected lot, excess rejected) takes its photos' link with it,
    -- not the photos: they stay with the receipt as photos of the delivery (tg_goods_receipt_photos_update).
    CONSTRAINT fk_goods_receipt_photos_line FOREIGN KEY (receipt_line_id)
        REFERENCES procurement.goods_receipt_lines (id) ON DELETE SET NULL,
    CONSTRAINT fk_goods_receipt_photos_taken_by FOREIGN KEY (taken_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT uk_goods_receipt_photos_key UNIQUE (storage_key),
    CONSTRAINT ck_goods_receipt_photos_stage CHECK (stage IN ('RECEIPT', 'QC')),
    -- A QC photo is of the line QC inspects; a receipt photo may be of the delivery as a whole.
    CONSTRAINT ck_goods_receipt_photos_qc_line CHECK (stage <> 'QC' OR receipt_line_id IS NOT NULL),
    -- FileCategory.QC_EVIDENCE: photographs, checked by their first bytes before they were stored.
    CONSTRAINT ck_goods_receipt_photos_key CHECK (storage_key LIKE 'qc-evidence/%'),
    CONSTRAINT ck_goods_receipt_photos_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_goods_receipt_photos_size CHECK (size_bytes > 0),
    CONSTRAINT ck_goods_receipt_photos_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_goods_receipt_photos_name CHECK (length(btrim(original_name)) > 0)
);

CREATE INDEX ix_goods_receipt_photos_receipt ON procurement.goods_receipt_photos (receipt_id, stage);
CREATE INDEX ix_goods_receipt_photos_line ON procurement.goods_receipt_photos (receipt_line_id)
    WHERE receipt_line_id IS NOT NULL;

-- Evidence is never edited or removed.
CREATE TRIGGER tg_goods_receipt_photos_append_only BEFORE DELETE ON procurement.goods_receipt_photos
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- The one change a photo takes: a receiving photo's line is dropped from the count (fk_goods_receipt_photos_line SET
-- NULL) and the photo becomes one of the delivery. Every other column, and a QC photo's line, stay as written.
CREATE FUNCTION procurement.goods_receipt_photo_unlink_only()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.receipt_line_id IS NOT NULL AND NEW.receipt_line_id IS NULL AND OLD.stage = 'RECEIPT'
       AND (to_jsonb(NEW) - 'receipt_line_id') = (to_jsonb(OLD) - 'receipt_line_id') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '%.% is append-only', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER tg_goods_receipt_photos_update BEFORE UPDATE ON procurement.goods_receipt_photos
    FOR EACH ROW EXECUTE FUNCTION procurement.goods_receipt_photo_unlink_only();

-- The line named is a line of the receipt named, and a QC photo's line is one that goes through QC: stated a second
-- time after the service.
CREATE FUNCTION procurement.check_goods_receipt_photo_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    line_qc BOOLEAN;
BEGIN
    IF NEW.receipt_line_id IS NOT NULL THEN
        SELECT qc_required INTO line_qc FROM procurement.goods_receipt_lines
         WHERE id = NEW.receipt_line_id AND receipt_id = NEW.receipt_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'receipt line % is not a line of receipt %', NEW.receipt_line_id, NEW.receipt_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.stage = 'QC' AND NOT line_qc THEN
            RAISE EXCEPTION 'receipt line % goes through no QC', NEW.receipt_line_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_photos_line BEFORE INSERT ON procurement.goods_receipt_photos
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt_photo_line();
