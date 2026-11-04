-- =============================================================================
-- product-lifecycle:APPROVE - suspend, resume and discontinue a product: the System Admin's alone.
-- Module: identity (grants) for product
--
-- kltn-docs 01 §5.1 "Ai thực hiện": Active -> Inactive, Inactive -> Active and Discontinued are done by the
-- System Admin. The three endpoints were guarded by product-products:APPROVE, which ECOMMERCE_ADMIN holds to
-- approve and publish, so the E-commerce Admin could also suspend or end a product. The lead's ruling
-- (2026-10-19): follow the docs. The endpoints get a resource of their own (ProductLifecycleController);
-- product-products:APPROVE keeps approve / reject / publish / unpublish and is not touched here.
--
-- A deployment may still grant product-lifecycle:APPROVE to another role at run time (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'product-lifecycle:APPROVE', 'product-lifecycle', 'APPROVE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM identity.app_role role
  JOIN identity.permission permission ON permission.code = 'product-lifecycle:APPROVE'
 WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached per role version (ADR-0008): advance the role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'SYSTEM_ADMIN';
