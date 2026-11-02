-- =============================================================================
-- SCRUM-308 / SCRUM-309: supplier invoices are entered and matched three ways - PO, goods receipt, invoice
-- (kltn-docs 04, docs 03 §4, BR-INV-001/002). Module: procurement.
--
-- supplier_invoices / supplier_invoice_lines already exist (V20260928002300) with the PO, PO line and receipt line
-- references and their triggers. This adds what entering and matching need:
--
--   statuses           named as kltn-docs 04 §5 names them (decision 2026-11-02): DRAFT (was RECEIVED) is the entered,
--                      not yet matched invoice, CANCELLED (was VOID) one entered by mistake or withdrawn by the
--                      supplier, EXCEPTION the outcome of a match that found variances. DISPUTED, APPROVED_FOR_PAYMENT
--                      and PAID stay for the resolution and payment story (SCRUM-310), which starts from EXCEPTION
--                      and MATCHED. Rows already carrying the old names are renamed.
--   received_by        who entered it (created_by is the audit's text, not a user reference)
--   note, file_*       the accountant's note; the scanned invoice in object storage (file_url holds its storage key)
--   matched_*          when and by whom the last match ran, and the price tolerance it used, so a result can be
--                      explained after the configured tolerance changes
--   voided_*           who cancelled an invoice entered by mistake, and why (kltn-docs 04 "Cancelled")
--   supplier_invoice_variances
--                      what the last match found, one row per variance: the review queue of SCRUM-310 reads it, and an
--                      override (BR-INV-003) compares its amount with the approver's limit
--
-- Stated twice (CLAUDE.md §7): the aggregate (SupplierInvoice) and the checks and the deferred trigger below. The
-- constraints bind the statuses this story writes; rows written before it (the demo seed's MATCHED and PAID invoices,
-- which have no match record) are left as they are.
-- =============================================================================

ALTER TABLE procurement.supplier_invoices
    DROP CONSTRAINT ck_supplier_invoices_status,
    ALTER COLUMN status TYPE VARCHAR(32),   -- APPROVED_FOR_PAYMENT does not fit 16
    ALTER COLUMN status SET DEFAULT 'DRAFT';

UPDATE procurement.supplier_invoices
   SET status = CASE status WHEN 'RECEIVED' THEN 'DRAFT' ELSE 'CANCELLED' END
 WHERE status IN ('RECEIVED', 'VOID');

ALTER TABLE procurement.supplier_invoices
    ADD CONSTRAINT ck_supplier_invoices_status
        CHECK (status IN ('DRAFT', 'MATCHED', 'EXCEPTION', 'DISPUTED', 'APPROVED_FOR_PAYMENT', 'PAID', 'CANCELLED')),
    ADD COLUMN received_by             UUID,
    ADD COLUMN note                    VARCHAR(500),
    ADD COLUMN file_name               VARCHAR(255),
    ADD COLUMN file_content_type       VARCHAR(100),
    ADD COLUMN matched_at              TIMESTAMPTZ,
    ADD COLUMN matched_by              UUID,
    ADD COLUMN price_tolerance_percent NUMERIC(5, 2),
    ADD COLUMN voided_at               TIMESTAMPTZ,
    ADD COLUMN voided_by               UUID,
    ADD COLUMN void_reason             VARCHAR(500),
    ADD CONSTRAINT fk_supplier_invoices_received_by FOREIGN KEY (received_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_supplier_invoices_matched_by FOREIGN KEY (matched_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_supplier_invoices_voided_by FOREIGN KEY (voided_by) REFERENCES identity.app_user (id),
    -- An unmatched invoice carries no match; an EXCEPTION is a match's outcome, with the tolerance it used.
    ADD CONSTRAINT ck_supplier_invoices_unmatched CHECK (status <> 'DRAFT'
        OR (matched_at IS NULL AND matched_by IS NULL AND price_tolerance_percent IS NULL)),
    ADD CONSTRAINT ck_supplier_invoices_exception CHECK (status <> 'EXCEPTION'
        OR (matched_at IS NOT NULL AND matched_by IS NOT NULL AND price_tolerance_percent IS NOT NULL)),
    ADD CONSTRAINT ck_supplier_invoices_tolerance CHECK (price_tolerance_percent IS NULL
        OR price_tolerance_percent BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_supplier_invoices_file CHECK (file_url IS NOT NULL OR (file_name IS NULL AND file_content_type IS NULL));

-- Legacy VOID invoices (carried over by V20261011000200) became CANCELLED above without saying who or why.
-- The disabled legacy stand-in (no login) stands for whoever voided them, as for other carried-over rows.
INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at, created_by)
SELECT md5('legacy:user:import')::uuid, 'system.legacy-import', 'legacy-import@system.invalid', '!no-login',
       'DISABLED', 0, NOW(), 'legacy:import'
 WHERE EXISTS (SELECT 1 FROM procurement.supplier_invoices WHERE status = 'CANCELLED' AND voided_at IS NULL)
ON CONFLICT DO NOTHING;

UPDATE procurement.supplier_invoices
   SET voided_at = created_at, voided_by = md5('legacy:user:import')::uuid,
       void_reason = 'Huỷ trước khi có module hoá đơn (dữ liệu cũ VOID)'
 WHERE status = 'CANCELLED' AND voided_at IS NULL;

-- A cancellation says who and why; only a CANCELLED invoice says so.
ALTER TABLE procurement.supplier_invoices
    ADD CONSTRAINT ck_supplier_invoices_void CHECK ((status = 'CANCELLED')
        = (voided_at IS NOT NULL AND voided_by IS NOT NULL AND void_reason IS NOT NULL));

CREATE INDEX ix_supplier_invoices_supplier ON procurement.supplier_invoices (supplier_id, invoice_date DESC);

COMMENT ON COLUMN procurement.supplier_invoices.file_url IS
    'Storage key of the scanned invoice (PDF, JPEG or PNG), served by a short-lived download link.';
COMMENT ON COLUMN procurement.supplier_invoices.price_tolerance_percent IS
    'The price tolerance the last match used (stockflow.procurement.invoice-match.price-tolerance-percent then).';


CREATE TABLE procurement.supplier_invoice_variances
(
    id               UUID           NOT NULL,
    invoice_id       UUID           NOT NULL,
    invoice_line_id  UUID,
    po_id            UUID,
    kind             VARCHAR(32)    NOT NULL,
    expected_value   NUMERIC(18, 3) NOT NULL,
    invoiced_value   NUMERIC(18, 3) NOT NULL,
    variance_amount  NUMERIC(18, 2) NOT NULL,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier_invoice_variances PRIMARY KEY (id),
    CONSTRAINT fk_supplier_invoice_variances_invoice FOREIGN KEY (invoice_id)
        REFERENCES procurement.supplier_invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_supplier_invoice_variances_line FOREIGN KEY (invoice_line_id)
        REFERENCES procurement.supplier_invoice_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_supplier_invoice_variances_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    CONSTRAINT ck_supplier_invoice_variances_kind CHECK (kind IN ('OVER_INVOICED', 'OVER_INVOICED_RECEIPT',
        'AWAITING_QC', 'SETTLEMENT_PENDING', 'PRICE', 'TAX_RATE', 'ABOVE_SETTLEMENT')),
    -- Every variance is of one invoice line, except the settlement total of a subcontract order (BR-PRD-17), which is
    -- of the order.
    CONSTRAINT ck_supplier_invoice_variances_subject CHECK (CASE kind
        WHEN 'ABOVE_SETTLEMENT' THEN invoice_line_id IS NULL AND po_id IS NOT NULL
        ELSE invoice_line_id IS NOT NULL END),
    CONSTRAINT ck_supplier_invoice_variances_values CHECK (expected_value >= 0 AND invoiced_value >= 0)
);

CREATE INDEX ix_supplier_invoice_variances_invoice ON procurement.supplier_invoice_variances (invoice_id);
CREATE INDEX ix_supplier_invoice_variances_line ON procurement.supplier_invoice_variances (invoice_line_id)
    WHERE invoice_line_id IS NOT NULL;
CREATE INDEX ix_supplier_invoice_variances_po ON procurement.supplier_invoice_variances (po_id)
    WHERE po_id IS NOT NULL;

COMMENT ON TABLE procurement.supplier_invoice_variances IS
    'What the last three-way match of an invoice found (SCRUM-309); replaced by every match, removed by an edit.';


-- BR-INV-002 a second time: a MATCHED invoice has no variance, an EXCEPTION at least one. Deferred to the end of the
-- transaction, which writes the invoice and replaces its variances in one go, in whatever order the ORM flushes them.
CREATE FUNCTION procurement.check_supplier_invoice_match()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    found BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM procurement.supplier_invoice_variances v WHERE v.invoice_id = NEW.id) INTO found;
    IF NEW.status = 'MATCHED' AND found THEN
        RAISE EXCEPTION 'invoice % is MATCHED but its match found variances', NEW.invoice_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'EXCEPTION' AND NOT found THEN
        RAISE EXCEPTION 'invoice % is an EXCEPTION but its match found no variance', NEW.invoice_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_supplier_invoices_match
    AFTER INSERT OR UPDATE OF status, matched_at ON procurement.supplier_invoices
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION procurement.check_supplier_invoice_match();


-- Who enters and matches invoices (decision 2026-11-02, kltn-docs 04 §2): the accountant. Procurement staff only read
-- them, to follow a variance of their order. V20260903000100 had seeded procurement-supplier-invoices:CREATE and
-- :UPDATE (and APPROVE, never granted to them) to PROCUREMENT_STAFF; the two are taken back. VIEW_PAGE and READ stay,
-- and ACCOUNTANT keeps its own grants. Only the seeded grant is removed: an administrator can still grant a permission
-- to any role at run time.
DELETE FROM identity.role_permission rp
USING identity.app_role role, identity.permission permission
WHERE rp.role_id = role.id
  AND rp.permission_id = permission.id
  AND role.code = 'PROCUREMENT_STAFF'
  AND permission.resource = 'procurement-supplier-invoices'
  AND permission.action IN ('CREATE', 'UPDATE');

-- ADR-0008: the role's permission cache must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code = 'PROCUREMENT_STAFF';
