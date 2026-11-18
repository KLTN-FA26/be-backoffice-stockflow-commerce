-- Demo seed: the seeded Ahamove carrier is booked through its API, so the whole API_BOOKING flow can be shown on a
-- laptop against the fake Ahamove (stockflow.fulfillment.ahamove.mode=fake, the local profile's default: no network and
-- no STOCKFLOW_SECRETS_KEY).
--
-- What it does, and what it leaves alone:
--  * the carrier AHAMOVE, when the database has one and it is still MANUAL, becomes API_BOOKING: STAGING, a demo
--    account, a callback header, and a failure-reason map. Without the carrier nothing is created (the tools/db/seed
--    data inserts it with a fixed id, and a second one would collide with it) and the rest finds no carrier to attach to.
--  * the secrets are PLACEHOLDERS that only look sealed (they satisfy ck_carrier_secrets_sealed): nothing opens them
--    in fake mode. In live mode they cannot be opened, so the carrier refuses to book instead of calling Ahamove
--    with them - enter the real key on the carrier before AHAMOVE_MODE=live.
--  * an AHAMOVE that is already API_BOOKING (a staging key sealed by tools/db/seed/seal_ahamove_key.py) keeps every
--    setting; only the missing service codes and pickup points are added.
--  * service codes per vehicle type for Ha Noi (province 01, Ahamove city HAN) and Ho Chi Minh City (79, SGN), and a
--    pickup point at each warehouse HAN, BDG and HCM that exists. A warehouse that is absent is skipped.
--
-- Idempotent: it may be run again with psql after the tools/db/seed data is loaded, which comes after the migrations
-- on a fresh database.

UPDATE fulfillment.carrier
   SET connection_type = 'API_BOOKING',
       webhook_enabled = true,
       webhook_auth_header = COALESCE(webhook_auth_header, 'X-Ahamove-Token'),
       webhook_auth_value_sealed = COALESCE(webhook_auth_value_sealed, 'v1:ZmFrZS1haGFtb3ZlLWRlbW8tbm8tc2VjcmV0'),
       api_environment = COALESCE(api_environment, 'STAGING'),
       api_account = COALESCE(api_account, '0900000000'),
       api_key_sealed = COALESCE(api_key_sealed, 'v1:ZmFrZS1haGFtb3ZlLWRlbW8tbm8tc2VjcmV0'),
       failure_reason_map = CASE WHEN failure_reason_map = '{}'::jsonb
           THEN '{"CUSTOMER_NOT_AVAILABLE": "CUSTOMER_ABSENT", "CUSTOMER_REFUSED": "REFUSED",
                  "WRONG_ADDRESS": "WRONG_ADDRESS"}'::jsonb
           ELSE failure_reason_map END,
       version = version + 1,
       last_modified_at = now(),
       last_modified_by = 'demo-fake-ahamove'
 WHERE code = 'AHAMOVE' AND connection_type = 'MANUAL';

INSERT INTO fulfillment.carrier_service_code (id, carrier_id, vehicle_type_id, province_code, carrier_city_code,
                                              service_code, created_by)
SELECT md5('demo:fake-ahamove:service:' || c.id || ':' || v.id || ':' || s.province)::uuid, c.id, v.id, s.province,
       s.city, s.city || '-TRUCK-' || m.capacity, 'demo-fake-ahamove'
  FROM fulfillment.carrier c
 CROSS JOIN (VALUES ('01', 'HAN'), ('79', 'SGN')) AS s(province, city)
  JOIN (VALUES ('PICKUP', '500'), ('TRUCK-1T25', '1000'), ('TRUCK-2T5', '2000')) AS m(vehicle_type, capacity) ON true
  JOIN fulfillment.vehicle_type v ON v.code = m.vehicle_type
 WHERE c.code = 'AHAMOVE' AND c.connection_type = 'API_BOOKING'
ON CONFLICT ON CONSTRAINT uk_carrier_service_code DO NOTHING;

INSERT INTO fulfillment.carrier_pickup_point (id, carrier_id, warehouse_id, province_code, address, contact_name,
                                              contact_phone, created_by)
SELECT md5('demo:fake-ahamove:pickup:' || c.id || ':' || w.id)::uuid, c.id, w.id, p.province, w.address, w.name,
       '0909000000', 'demo-fake-ahamove'
  FROM fulfillment.carrier c
  JOIN (VALUES ('HAN', '01'), ('BDG', '79'), ('HCM', '79')) AS p(prefix, province) ON true
  JOIN warehouse.warehouse w ON w.prefix = p.prefix
 WHERE c.code = 'AHAMOVE' AND c.connection_type = 'API_BOOKING'
ON CONFLICT ON CONSTRAINT uk_carrier_pickup_point DO NOTHING;
