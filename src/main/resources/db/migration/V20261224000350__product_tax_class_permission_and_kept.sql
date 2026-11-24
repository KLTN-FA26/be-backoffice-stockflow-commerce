-- =============================================================================
-- WF-05 demo D-8 (decided 24/11; Q-21, decision file §4.4 HĐ-7 step 2): a product's VAT group is set or changed on its
-- own, in any status - PUT /api/v1/products/{id}/tax-class. Before, only the edit of a DRAFT product set it, and the
-- invoice draft told the accountant to assign a class to a published product nobody could change.
--
--   product-tax-class:UPDATE  read a SKU's product with its class (GET /products/tax-classes) and change the class:
--                             the accountant, who meets a product without one on an invoice draft (TAX_CLASS_UNKNOWN),
--                             and the Sales Manager, who owns the catalogue. SYSTEM_ADMIN holds every permission.
--
-- Every role that gained a grant advances app_role.version in the same transaction (ADR-0008, CLAUDE.md §5).
--
-- tg_products_tax_class_kept: a class once set is never cleared - the database's half of Product.changeTaxClass, which
-- refuses a null class. A product without one is invoiced at the highest rate and held back from the automatic issuing
-- (TAX_CLASS_UNKNOWN, Q-21); clearing it would put that back silently. Products seeded without one stay as they are.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'product-tax-class:UPDATE', 'product-tax-class', 'UPDATE',
        'Set or change a product''s VAT group (STANDARD, REDUCED, EXEMPT) in any status, with a reason (WF-05 D-8)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code, permission_code) AS (
    VALUES ('SYSTEM_ADMIN', 'product-tax-class:UPDATE'),
           ('ACCOUNTANT', 'product-tax-class:UPDATE'),
           ('SALES_MANAGER', 'product-tax-class:UPDATE')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = g.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);

CREATE OR REPLACE FUNCTION product.check_products_tax_class_kept() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.tax_class IS NOT NULL AND NEW.tax_class IS NULL THEN
        RAISE EXCEPTION 'product %: its tax class % is never cleared, only changed', NEW.code, OLD.tax_class
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_products_tax_class_kept BEFORE UPDATE OF tax_class ON product.products
    FOR EACH ROW EXECUTE FUNCTION product.check_products_tax_class_kept();
