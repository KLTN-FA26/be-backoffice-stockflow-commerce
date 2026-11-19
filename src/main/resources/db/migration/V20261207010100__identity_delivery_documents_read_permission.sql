-- =============================================================================
-- WF4 09B G13 / 09A-40: Sales read an order's delivery documents (kltn-docs 09 §2 "gửi khách file vận đơn / POD khi
-- khách xin qua chat", §7 row 16; BR-28).
--
-- The proofs of delivery and the waybill files are read through the shipment and the load with
-- fulfillment-shipments:READ, which only the warehouse holds - and Sales must not: it opens every shipment and load of
-- the warehouse. So the documents of one order get a code of their own, served by
-- GET /fulfillment/orders/{orderId}/delivery-documents and its two download links:
-- fulfillment-delivery-documents:READ. Granted to Sales Staff and the Sales Manager, who answer the customer in the chat
-- (16), and to the System Admin, who holds every permission. Never to the customer: the customer does not see waybill
-- files or PODs (BR-05, 17 BR-27).
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-delivery-documents:READ', 'fulfillment-delivery-documents', 'READ',
        'Read an order''s proofs of delivery and the waybill files of the vehicles that carried it, to send the '
            || 'customer who asks (kltn-docs 09 §2, §7)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, documents.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission documents ON documents.code = 'fulfillment-delivery-documents:READ'
     WHERE role.code IN ('SALES_STAFF', 'SALES_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
