-- =============================================================================
-- Demo WF-05 (seed gap G-6): the carrier the demo's webhooks speak for, "Vận tải Demo (webhook)" (DEMO-WEBHOOK).
-- Demo only (db/demo).
--
-- Seeded MANUAL: a WEBHOOK_LOAD carrier must hold its webhook secret sealed with STOCKFLOW_SECRETS_KEY
-- (ck_carrier_connection_webhook_load), which a migration neither has nor may hold. After every reset the warehouse
-- manager switches it to WEBHOOK_LOAD on the back office and types the secret (demo/carrier-webhooks/README.md §3).
-- Its failure-reason table is seeded already; a connection change that sends none keeps it
-- (CarrierConnectionRequest: "Left out, the table saved is kept"), so only the secret is typed.
-- =============================================================================

INSERT INTO fulfillment.carrier (id, code, name, contact_name, contact_phone, status, connection_type, webhook_enabled,
                                 failure_reason_map, version, created_at, created_by)
VALUES (md5('demo:wf05:carrier:DEMO-WEBHOOK')::uuid, 'DEMO-WEBHOOK', 'Vận tải Demo (webhook)', 'Điều phối Vận tải Demo',
        '0909000555', 'ACTIVE', 'MANUAL', FALSE,
        '{"Khách từ chối nhận": "REFUSED", "Thất lạc hàng": "LOST", "Khách vắng nhà": "CUSTOMER_ABSENT"}'::jsonb, 0,
        ((((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) - 60) + TIME '09:00') AT TIME ZONE 'Asia/Ho_Chi_Minh',
        'flyway');
