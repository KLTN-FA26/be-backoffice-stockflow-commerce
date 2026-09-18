ALTER TABLE procurement.supplier
    ADD COLUMN contact_name VARCHAR(200),
    ADD COLUMN payment_term_days INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN lead_time_days INTEGER NOT NULL DEFAULT 7,
    ADD COLUMN communication_channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN api_endpoint VARCHAR(500),
    ADD CONSTRAINT ck_supplier_payment_term_days CHECK (payment_term_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_supplier_lead_time_days CHECK (lead_time_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_supplier_communication_channel CHECK (communication_channel IN ('EMAIL', 'API'));

CREATE UNIQUE INDEX uk_supplier_code_ci ON procurement.supplier (lower(code));
CREATE UNIQUE INDEX uk_supplier_tax_code ON procurement.supplier (tax_code) WHERE tax_code IS NOT NULL;

ALTER TABLE procurement.purchase_order
    ADD COLUMN payment_term_days INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN lead_time_days INTEGER NOT NULL DEFAULT 7,
    ADD COLUMN sent_at TIMESTAMPTZ,
    ADD COLUMN supplier_confirmation_status VARCHAR(16) NOT NULL DEFAULT 'NOT_SENT',
    ADD COLUMN supplier_responded_at TIMESTAMPTZ,
    ADD COLUMN supplier_reference VARCHAR(100),
    ADD COLUMN supplier_response_note VARCHAR(1000),
    ADD CONSTRAINT ck_po_payment_term_days CHECK (payment_term_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_po_lead_time_days CHECK (lead_time_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_po_supplier_confirmation CHECK (
        supplier_confirmation_status IN ('NOT_SENT', 'PENDING', 'CONFIRMED', 'REJECTED'));

ALTER TABLE notification.delivery_log ADD COLUMN external_reference VARCHAR(100);
ALTER TABLE notification.delivery_log DROP CONSTRAINT ck_delivery_log_channel;
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_channel
    CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'API'));
CREATE UNIQUE INDEX uk_delivery_log_external_reference
    ON notification.delivery_log (external_reference) WHERE external_reference IS NOT NULL;

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
SELECT gen_random_uuid(), 'procurement-suppliers:' || action, 'procurement-suppliers', action,
       'Manage supplier master data', 0, NOW(), 'flyway'
FROM unnest(ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','DELETE']) AS actions(action);

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.resource = 'procurement-suppliers'
WHERE role.code = 'PROCUREMENT_STAFF';
