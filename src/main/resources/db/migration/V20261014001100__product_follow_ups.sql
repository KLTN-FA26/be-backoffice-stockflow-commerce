-- =============================================================================
-- Product follow-ups (SCRUM-461). Docs: kltn-docs 01 §3 (brand), §4 step 11-12 (history, discontinue),
-- §5.1 (Inactive), §5.2 (variant Obsolete), BR-02 (only a draft is deleted); 02 BR-01 (a PO holds
-- only active SKUs).
--
-- 1. A discontinued product carries WHY (mandatory); a suspended (INACTIVE) one carries when and why.
-- 2. A product waiting for approval has a brand. Only that status is constrained: every later status
--    passed through it, and an old APPROVED/PUBLISHED row without a brand must stay updatable.
-- 3. product.product_change_log: the product's own history, written in the transaction of the change
--    (platform.audit_log is written after commit and carries no from/to status or reason). Append-only.
-- 4. Only a DRAFT product is deleted, in the database too; 5. product-products:DELETE (who may delete one).
-- =============================================================================

-- ---- 1. discontinue / suspend facts ------------------------------------------
ALTER TABLE product.products
    ADD COLUMN discontinue_reason VARCHAR(500),
    ADD COLUMN suspended_at       TIMESTAMPTZ,
    ADD COLUMN suspend_reason     VARCHAR(500);

-- What the docs require from now on cannot be reconstructed for rows discontinued before it.
UPDATE product.products
   SET discontinue_reason = '(legacy) không ghi lý do'
 WHERE status = 'DISCONTINUED' AND discontinue_reason IS NULL;

ALTER TABLE product.products
    ADD CONSTRAINT ck_products_discontinue_reason
        CHECK (status <> 'DISCONTINUED' OR (discontinue_reason IS NOT NULL AND btrim(discontinue_reason) <> '')),
    ADD CONSTRAINT ck_products_suspended_pair
        CHECK ((suspended_at IS NULL) = (suspend_reason IS NULL)),
    ADD CONSTRAINT ck_products_inactive
        CHECK (status <> 'INACTIVE' OR (suspended_at IS NOT NULL AND suspend_reason IS NOT NULL AND btrim(suspend_reason) <> ''));

-- INACTIVE is reachable only from an approved product, so it needs the approval evidence too.
ALTER TABLE product.products DROP CONSTRAINT ck_products_approved;
ALTER TABLE product.products
    ADD CONSTRAINT ck_products_approved CHECK (status NOT IN ('APPROVED', 'ACTIVE', 'PUBLISHED', 'INACTIVE')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL));

-- ---- 2. brand before approval ---------------------------------------------------
-- A row waiting for approval without a brand would make the constraint fail the whole migration.
-- The approver rejects it back to DRAFT (or the editor sets the brand) and the migration is run again.
DO
$$
DECLARE
    pending INTEGER;
BEGIN
    SELECT count(*) INTO pending FROM product.products WHERE status = 'PENDING_APPROVAL' AND brand_id IS NULL;
    IF pending > 0 THEN
        RAISE EXCEPTION 'SCRUM-461: % product(s) are PENDING_APPROVAL without a brand; reject them back to DRAFT or set the brand, then migrate again',
            pending USING ERRCODE = 'check_violation';
    END IF;
END
$$;

ALTER TABLE product.products
    ADD CONSTRAINT ck_products_brand_on_submit CHECK (status <> 'PENDING_APPROVAL' OR brand_id IS NOT NULL);

-- ---- 3. the product's own history ---------------------------------------------
CREATE TABLE product.product_change_log
(
    id             UUID         NOT NULL,
    product_id     UUID         NOT NULL,
    change_type    VARCHAR(32)  NOT NULL,
    -- What kind of thing changed, and which one: the product itself, one of its variants, an image of a
    -- variant, the logistics or the stock policy of a variant's SKU (subject_id = the variant's id).
    subject_type   VARCHAR(20)  NOT NULL DEFAULT 'PRODUCT',
    subject_id     UUID,
    -- Status of the subject before and after, when the change moved it. NULL for rows backfilled from
    -- platform.audit_log, which never recorded them.
    from_status    VARCHAR(32),
    to_status      VARCHAR(32),
    reason         VARCHAR(500),
    changed_fields TEXT[]       NOT NULL DEFAULT '{}',
    actor_id       UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_product_change_log PRIMARY KEY (id),
    CONSTRAINT fk_product_change_log_product FOREIGN KEY (product_id)
        REFERENCES product.products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_change_log_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_product_change_log_type CHECK (
        (subject_type = 'PRODUCT' AND change_type IN
            ('CREATED', 'UPDATED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'SUSPENDED', 'RESUMED', 'DISCONTINUED'))
        OR (subject_type = 'VARIANT' AND change_type IN ('VARIANT_ADDED', 'VARIANT_UPDATED', 'VARIANT_STATUS'))
        OR (subject_type = 'MEDIA' AND change_type = 'MEDIA')
        OR (subject_type = 'LOGISTICS' AND change_type = 'LOGISTICS')
        OR (subject_type = 'INVENTORY_CONTROL' AND change_type = 'INVENTORY_CONTROL')),
    CONSTRAINT ck_product_change_log_status CHECK (
        (subject_type = 'PRODUCT'
            AND (from_status IS NULL OR from_status IN
                 ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'INACTIVE', 'DISCONTINUED'))
            AND (to_status IS NULL OR to_status IN
                 ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'INACTIVE', 'DISCONTINUED')))
        OR (subject_type = 'VARIANT'
            AND (from_status IS NULL OR from_status IN ('DRAFT', 'ACTIVE', 'BLOCKED', 'OBSOLETE'))
            AND (to_status IS NULL OR to_status IN ('DRAFT', 'ACTIVE', 'BLOCKED', 'OBSOLETE')))
        OR (subject_type NOT IN ('PRODUCT', 'VARIANT') AND from_status IS NULL AND to_status IS NULL)),
    CONSTRAINT ck_product_change_log_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    -- Suspending and discontinuing are never anonymous (docs 01 §5.1).
    CONSTRAINT ck_product_change_log_reason_required
        CHECK (change_type NOT IN ('SUSPENDED', 'DISCONTINUED') OR reason IS NOT NULL)
);

CREATE INDEX ix_product_change_log_product ON product.product_change_log (product_id, occurred_at DESC, id DESC);

-- UPDATE is refused; a row leaves only with its product (deleting a draft product takes its history).
CREATE TRIGGER tg_product_change_log_append_only
    BEFORE UPDATE OR DELETE ON product.product_change_log
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('product.products', 'product_id');

COMMENT ON TABLE product.product_change_log IS
    'Append-only history of a product: its status moves with reason, and its variants, images, logistics and stock policy.';

-- Backfill from platform.audit_log: successful entries on the product, its variants and its images.
-- Failed calls changed nothing and are not history. The status columns stay NULL: the audit trail never
-- had them. The audit trail calls both submit and discontinue a TRANSITION. A product is discontinued
-- once and nothing follows it, so the latest TRANSITION of a DISCONTINUED product, when it falls within
-- minutes of discontinued_at, is the discontinuation; the ones before it are submissions. A latest
-- TRANSITION that does not line up (its audit write was lost) is left out rather than guessed.
INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, reason, actor_id, occurred_at)
SELECT gen_random_uuid(), c.product_id, c.change_type, 'PRODUCT', c.reason, c.actor_id, c.occurred_at
  FROM (SELECT p.id AS product_id,
               CASE a.action
                   WHEN 'CREATE' THEN 'CREATED'
                   WHEN 'UPDATE' THEN 'UPDATED'
                   WHEN 'APPROVE' THEN 'APPROVED'
                   WHEN 'REJECT' THEN 'REJECTED'
                   ELSE CASE WHEN p.status <> 'DISCONTINUED' OR a.latest > 1 THEN 'SUBMITTED'
                             WHEN abs(extract(epoch FROM (a.occurred_at - p.discontinued_at))) < 600 THEN 'DISCONTINUED'
                        END
               END AS change_type,
               CASE WHEN a.action = 'TRANSITION' AND p.status = 'DISCONTINUED' AND a.latest = 1
                         THEN p.discontinue_reason END AS reason,
               (SELECT u.id FROM identity.app_user u WHERE u.id = a.actor_id) AS actor_id,
               a.occurred_at
          FROM (SELECT x.*, row_number() OVER (PARTITION BY x.resource_id, x.action
                                               ORDER BY x.occurred_at DESC) AS latest
                  FROM platform.audit_log x
                 WHERE x.resource_type = 'product' AND x.outcome = 'SUCCESS'
                   AND x.action IN ('CREATE', 'UPDATE', 'TRANSITION', 'APPROVE', 'REJECT')) a
          JOIN product.products p ON p.id::text = a.resource_id) c
 WHERE c.change_type IS NOT NULL;

INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, subject_id, actor_id, occurred_at)
SELECT gen_random_uuid(), v.product_id,
       CASE a.action WHEN 'CREATE' THEN 'VARIANT_ADDED' WHEN 'UPDATE' THEN 'VARIANT_UPDATED' ELSE 'VARIANT_STATUS' END,
       'VARIANT', v.id,
       (SELECT u.id FROM identity.app_user u WHERE u.id = a.actor_id),
       a.occurred_at
  FROM platform.audit_log a
  JOIN product.variants v ON v.id::text = a.resource_id
 WHERE a.resource_type = 'product-variant' AND a.outcome = 'SUCCESS'
   AND a.action IN ('CREATE', 'UPDATE', 'TRANSITION');

-- An image is audited by its own id (describe, withdraw, delete) or by its variant's (upload, publish).
-- A deleted image is gone and cannot be placed on a product, and the variant-level entries name no image.
INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, subject_id, actor_id, occurred_at)
SELECT gen_random_uuid(), v.product_id, 'MEDIA', 'MEDIA', m.id,
       (SELECT u.id FROM identity.app_user u WHERE u.id = a.actor_id),
       a.occurred_at
  FROM platform.audit_log a
  JOIN product.media m ON m.id::text = a.resource_id
  JOIN product.variants v ON v.id = m.variant_id
 WHERE a.resource_type = 'product-media' AND a.outcome = 'SUCCESS';

INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, subject_id, actor_id, occurred_at)
SELECT gen_random_uuid(), v.product_id, 'MEDIA', 'MEDIA', NULL,
       (SELECT u.id FROM identity.app_user u WHERE u.id = a.actor_id),
       a.occurred_at
  FROM platform.audit_log a
  JOIN product.variants v ON v.id::text = a.resource_id
 WHERE a.resource_type = 'product-media' AND a.outcome = 'SUCCESS';

-- ---- 4. only a draft is deleted ---------------------------------------------------------------
-- The aggregate refuses it first (Product.requireDeletable, PRODUCT_NOT_DELETABLE); this is the second statement
-- (kltn-docs 01 BR-02), for whoever reaches the table another way. What a draft's SKUs are used by is still the
-- foreign keys' to refuse.
CREATE FUNCTION product.refuse_delete_unless_draft()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'product % is %: only a DRAFT product is deleted (kltn-docs 01 BR-02)', OLD.code, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER tg_products_delete_draft_only
    BEFORE DELETE ON product.products
    FOR EACH ROW EXECUTE FUNCTION product.refuse_delete_unless_draft();

-- ---- 5. permission ------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'product-products:DELETE', 'product-products', 'DELETE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM identity.app_role role
  JOIN identity.permission permission ON permission.code = 'product-products:DELETE'
 WHERE role.code IN ('ECOMMERCE_ADMIN', 'SYSTEM_ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached per role version (ADR-0008): advance every role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code IN ('ECOMMERCE_ADMIN', 'SYSTEM_ADMIN');
