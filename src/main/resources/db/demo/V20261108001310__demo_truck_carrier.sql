-- =============================================================================
-- Demo: a truck hirer to book load-plan vehicles with (kltn-docs 09 §4.2 step 6). V20261108001300 retired the four
-- parcel carriers the dispatch migration seeded, which left a demo database with no carrier to book a vehicle with.
-- Connected MANUAL: waybill numbers are typed and delivery outcomes recorded by hand. An API_BOOKING carrier (Ahamove)
-- needs keys sealed with the deployment's own secrets key, so it is declared on the screen, never seeded.
-- =============================================================================

INSERT INTO fulfillment.carrier (id, code, name, contact_name, contact_phone, status, created_at, created_by)
VALUES (md5('demo:carrier:XT-MINHPHAT')::uuid, 'XT-MINHPHAT', 'Xe tải Minh Phát', 'Điều phối Minh Phát', '0909000111',
        'ACTIVE', NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;
