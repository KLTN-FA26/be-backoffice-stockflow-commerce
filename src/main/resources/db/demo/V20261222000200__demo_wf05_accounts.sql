-- =============================================================================
-- Demo WF-05 (seed gaps G-1, G-4): the accounts the WF-05 demo signs in with. Demo only (db/demo). Ids are
-- md5('demo:wf05:...')::uuid; days are counted back from the Vietnam business day the database is built on, as
-- V20261128000200 does, because the database is rebuilt right before the demo (WF-05 Q-13).
--
--   * demo.admin (SYSTEM_ADMIN): only for configuration off screen - the e-invoice settings are SYSTEM_ADMIN's alone
--     (einvoice-settings:UPDATE, V20261120000300), and the WF-05 decision file §4.1 forbids the admin on screen, not
--     off it.
--   * two customers with a portal account each (CUSTOMER):
--       - "Quán cà phê Mộc" (demo.kh.moc), the main flow's buyer: tax code 0123456789 (the one the SePay sandbox
--         accepted in T-1..T-7), an invoice e-mail of its own (WF-05 Q-14), credit 500M for 30 days so it pays 70%
--         after delivery (15 BR-11);
--       - "Quán trà Lá" (demo.kh.la), HĐ-8's buyer: the same, but no invoice e-mail - the invoice is still issued and
--         flagged for the missing address.
-- Sign-in password: the published demo one (Stockflow@2026), the hash V20261124000600 and V20261128000200 use.
-- =============================================================================

CREATE FUNCTION pg_temp.wid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:wf05:' || key)::uuid $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

-- ---------------------------------------------------------------------------------------------
-- G-1. The administrator, for configuration only
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES (pg_temp.wid('user:demo.admin'), 'demo.admin', 'demo.admin@stockflow.local',
        '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', 'Quản trị hệ thống (demo)', 'ACTIVE', 0,
        pg_temp.at(140, '08:00'), 'flyway');

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.wid('user-role:demo.admin'), pg_temp.wid('user:demo.admin'), r.id, 0, pg_temp.at(140, '08:00'), 'flyway'
  FROM identity.app_role r WHERE r.code = 'SYSTEM_ADMIN';

-- ---------------------------------------------------------------------------------------------
-- G-4. The two customers: portal account, legal profile, addresses, credit
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE w_customer (code TEXT PRIMARY KEY, name TEXT, tax_code TEXT, username TEXT, contact TEXT,
                              phone TEXT, line1 TEXT, ward TEXT, ward_code TEXT, invoice_email TEXT) ON COMMIT DROP;
INSERT INTO w_customer VALUES
    ('MOC', 'Quán cà phê Mộc', '0123456789', 'demo.kh.moc', 'Trần Văn Mộc', '0901220031', '25 Nguyễn Huệ',
     'Phường Sài Gòn', '26740', 'hoadon.moc@stockflow.local'),
    ('LA', 'Quán trà Lá', '0399990021', 'demo.kh.la', 'Ngô Thị Lá', '0901220032', '9 Pasteur', 'Phường Bến Thành',
     '26743', NULL);

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
SELECT pg_temp.wid('user:' || username), username, username || '@stockflow.local',
       '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', contact || ' (demo)', 'ACTIVE', 0,
       pg_temp.at(90, '09:00'), 'flyway'
  FROM w_customer;

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.wid('user-role:' || c.username), pg_temp.wid('user:' || c.username), r.id, 0, pg_temp.at(90, '09:00'),
       'flyway'
  FROM w_customer c JOIN identity.app_role r ON r.code = 'CUSTOMER';

INSERT INTO customer.customer (id, full_name, email, phone, status, version, created_at, created_by, user_id, tax_code)
SELECT pg_temp.wid('customer:' || code), name, 'muahang.' || lower(code) || '@stockflow.local', phone, 'ACTIVE', 0,
       pg_temp.at(90, '09:00'), pg_temp.staff('demo.sales')::text, pg_temp.wid('user:' || username), tax_code
  FROM w_customer;

-- WF-05 Q-14: the invoice goes to the invoice e-mail, which BE sends; Lá has none (HĐ-8).
INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                             representative_title, invoice_email, version, created_at, created_by)
SELECT pg_temp.wid('legal:' || code), pg_temp.wid('customer:' || code),
       line1 || ', ' || ward || ', TP. Hồ Chí Minh', contact, 'Giám đốc', invoice_email, 0,
       pg_temp.at(90, '09:30'), pg_temp.staff('demo.sales')::text
  FROM w_customer;

INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, province, country, is_default,
                              ward_code, province_code, version, created_at, created_by)
SELECT pg_temp.wid('address:' || c.code || ':' || t.type), pg_temp.wid('customer:' || c.code), t.type,
       CASE t.type WHEN 'SHIPPING' THEN c.contact ELSE c.name END, c.phone, c.line1, c.ward, 'TP. Hồ Chí Minh', 'VN',
       TRUE, c.ward_code, '79', 0, pg_temp.at(90, '09:30'), pg_temp.staff('demo.sales')::text
  FROM w_customer c CROSS JOIN (VALUES ('SHIPPING'), ('BILLING')) AS t (type);

-- 15 BR-11: paying 70% after delivery is credit, approved by accounting.
INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, currency, approved_by, approved_at,
                                     note, version, created_at, created_by)
SELECT pg_temp.wid('credit:' || code), pg_temp.wid('customer:' || code), 500000000, 30, 'VND',
       pg_temp.staff('demo.ketoan'), pg_temp.at(89, '10:00'),
       'Hạn mức duyệt theo hồ sơ năng lực tài chính; thanh toán đợt sau giao hàng trong 30 ngày', 0,
       pg_temp.at(89, '10:00'), pg_temp.staff('demo.ketoan')::text
  FROM w_customer;

DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.staff(TEXT);
