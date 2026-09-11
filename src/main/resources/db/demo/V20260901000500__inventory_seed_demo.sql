-- =============================================================================
-- Demo data, applied only under the "demo" Flyway location (see application-local.yml).
-- Never part of the production migration path: seeding customer-visible stock into a real
-- warehouse would be a very expensive mistake.
-- =============================================================================

INSERT INTO inventory.stock_item
    (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status,
     version, created_at, created_by)
VALUES
    ('11111111-1111-4111-8111-111111111111', 'SOFA-3S-GREY', 'HCM-A-01-02-B', NULL, NULL,
     25, 0, 'AVAILABLE', 0, NOW(), 'flyway'),
    ('22222222-2222-4222-8222-222222222222', 'SOFA-3S-GREY', 'HCM-A-02-01-A', NULL, NULL,
     8, 0, 'AVAILABLE', 0, NOW(), 'flyway'),
    ('33333333-3333-4333-8333-333333333333', 'TABLE-OAK-160', 'HCM-B-01-01-A', 'LOT-2026-03',
     '2027-03-31', 12, 0, 'AVAILABLE', 0, NOW(), 'flyway'),
    -- Deliberately quarantined, so the ATP query can be seen excluding it.
    ('44444444-4444-4444-8444-444444444444', 'TABLE-OAK-160', 'HCM-QC-01', 'LOT-2026-04',
     '2027-04-30', 30, 0, 'QUARANTINE', 0, NOW(), 'flyway');
