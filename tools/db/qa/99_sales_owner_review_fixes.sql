-- =============================================================================
-- Adversarial checks: the fixes of the sales-owner review (2026-11-28; V20270128000450..000479).
--   LR  customer.lead_history.kind (P5): a RESUBMITTED entry changes neither status nor Sales, says what was sent and has
--       no actor; a CHANGE still changes something
--   LF  customer.lead_form_existing_customer (S4): one row per customer, gone with it
--   TR  the assignability triggers, rewritten to take the account FOR SHARE (C2), still refuse and accept as before
-- Needs the demo seed. Fixture rows are made here and everything is rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_like(label TEXT, fragment TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF position(fragment IN SQLERRM) = 0 THEN
            RAISE EXCEPTION 'FAIL  %: refused, but for another reason: %', label, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

CREATE FUNCTION pg_temp.id(key TEXT) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5('qa:so-fixes:' || key)::uuid $$;

-- Fixtures: a salesperson, a Sales Manager, a customer, a lead.
INSERT INTO identity.app_user (id, username, email, status, version, created_at)
VALUES (pg_temp.id('a'), 'qa-sof-a', 'qa-sof-a@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('manager'), 'qa-sof-manager', 'qa-sof-manager@example.test', 'ACTIVE', 0, NOW());
INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
SELECT gen_random_uuid(), u.id, r.id, 0, NOW()
  FROM (VALUES (pg_temp.id('a'), 'SALES_STAFF'), (pg_temp.id('manager'), 'SALES_MANAGER')) AS u(id, role)
  JOIN identity.app_role r ON r.code = u.role;
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (pg_temp.id('c1'), 'QA fixes customer', 'qa-sof-c1@example.test', 'ACTIVE', 0, NOW());
INSERT INTO customer.lead (id, company_name, contact_name, phone, consent_at, submitted_at, created_at)
VALUES (pg_temp.id('lead'), 'QA lead', 'A', '+84901234567', NOW(), NOW(), NOW());

\echo '--- LR: a repeat of the form on the lead''s history'
SELECT pg_temp.expect_ok('LR1 P5: a repeat kept on the lead, nothing changed, the form in the note',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, note, changed_at, kind, '
           'created_at) VALUES (gen_random_uuid(), %L, ''NEW'', ''NEW'', ''sent again: B'', NOW(), ''RESUBMITTED'', '
           'NOW())', pg_temp.id('lead')));
SELECT pg_temp.expect_fail_like('LR2 a repeat changes no status', 'ck_lead_history_change',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, note, changed_at, kind, '
           'created_at) VALUES (gen_random_uuid(), %L, ''NEW'', ''ASSIGNED'', ''x'', NOW(), ''RESUBMITTED'', NOW())',
           pg_temp.id('lead')));
SELECT pg_temp.expect_fail_like('LR3 a repeat changes no Sales', 'ck_lead_history_change',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, to_user_id, note, changed_at, '
           'kind, created_at) VALUES (gen_random_uuid(), %L, ''ASSIGNED'', ''ASSIGNED'', %L, ''x'', NOW(), '
           '''RESUBMITTED'', NOW())', pg_temp.id('lead'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('LR4 a repeat says what was sent', 'ck_lead_history_change',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, note, changed_at, kind, '
           'created_at) VALUES (gen_random_uuid(), %L, ''NEW'', ''NEW'', '' '', NOW(), ''RESUBMITTED'', NOW())',
           pg_temp.id('lead')));
SELECT pg_temp.expect_fail_like('LR5 a repeat comes from the public form, not a person', 'ck_lead_history_change',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, note, changed_by, changed_at, '
           'kind, created_at) VALUES (gen_random_uuid(), %L, ''NEW'', ''NEW'', ''x'', %L, NOW(), ''RESUBMITTED'', '
           'NOW())', pg_temp.id('lead'), pg_temp.id('manager')));
SELECT pg_temp.expect_fail_like('LR6 a CHANGE still changes something', 'ck_lead_history_change',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, note, changed_at, created_at) '
           'VALUES (gen_random_uuid(), %L, ''NEW'', ''NEW'', ''x'', NOW(), NOW())', pg_temp.id('lead')));
SELECT pg_temp.expect_fail_like('LR7 a kind outside the list', 'ck_lead_history_kind',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, note, changed_at, kind, '
           'created_at) VALUES (gen_random_uuid(), %L, ''NEW'', ''ASSIGNED'', ''x'', NOW(), ''EDITED'', NOW())',
           pg_temp.id('lead')));
SELECT pg_temp.expect_fail_like('LR8 a repeat is append-only like the rest', 'append-only',
    format('UPDATE customer.lead_history SET note = ''edited'' WHERE lead_id = %L', pg_temp.id('lead')));

\echo '--- LF: when an existing customer''s salesperson was last told about the form'
SELECT pg_temp.expect_ok('LF1 S4: one row per customer',
    format('INSERT INTO customer.lead_form_existing_customer (customer_id, last_at) VALUES (%L, NOW())',
           pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('LF2 not two', 'pk_lead_form_existing_customer',
    format('INSERT INTO customer.lead_form_existing_customer (customer_id, last_at) VALUES (%L, NOW())',
           pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('LF3 only a customer', 'fk_lead_form_existing_customer_customer',
    'INSERT INTO customer.lead_form_existing_customer (customer_id, last_at) VALUES (gen_random_uuid(), NOW())');
UPDATE customer.lead_form_existing_customer SET last_at = NOW() - INTERVAL '1 hour' WHERE customer_id = pg_temp.id('c1');
INSERT INTO customer.lead_form_existing_customer AS seen (customer_id, last_at)
VALUES (pg_temp.id('c1'), NOW())
ON CONFLICT (customer_id) DO UPDATE SET last_at = EXCLUDED.last_at WHERE seen.last_at < NOW() - INTERVAL '24 hours';
SELECT pg_temp.expect_true('LF4 the window: a claim within 24 hours of the last changes nothing',
    (SELECT last_at = NOW() - INTERVAL '1 hour' FROM customer.lead_form_existing_customer
      WHERE customer_id = pg_temp.id('c1')));

\echo '--- TR: the assignability triggers after C2'
SELECT pg_temp.expect_ok('TR1 an active salesperson is assigned',
    format('UPDATE customer.customer SET sales_owner_id = %L, sales_owner_state = ''ASSIGNED'', '
           'sales_owner_since = NOW() WHERE id = %L', pg_temp.id('a'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('TR2 a Sales Manager is no owner', 'must be an active salesperson',
    format('UPDATE customer.customer SET sales_owner_id = %L WHERE id = %L', pg_temp.id('manager'), pg_temp.id('c1')));
UPDATE identity.app_user SET status = 'LOCKED' WHERE id = pg_temp.id('a');
SELECT pg_temp.expect_fail_like('TR3 a locked salesperson stands in for nobody', 'must be an active salesperson',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, CURRENT_DATE, CURRENT_DATE, NOW())', pg_temp.id('manager'), pg_temp.id('a')));
SELECT pg_temp.expect_true('TR4 the functions lock the account they read',
    (SELECT bool_and(prosrc LIKE '%FOR SHARE%') FROM pg_proc
      WHERE proname IN ('check_sales_owner_assignable', 'check_staff_cover_stand_in')));

ROLLBACK;
