-- =============================================================================
-- Adversarial checks: the Sales owner of a customer (sales-owner plan 2026-11-28; V20270128000100..000145).
--   SO  customer.customer: the state agrees with the owner and the date; an owner being assigned is an ACTIVE account
--       holding SALES_STAFF (BR-S04, D42: never a manager or a portal customer; tg_customer_sales_owner_assignable)
--   SH  customer.customer_sales_owner_history: append-only; a change gives an owner or marks one as gone; OTHER says why
--   SC  identity.staff_cover: from <= to, at most 60 days, not oneself, one ACTIVE cover per absent person and day; the
--       stand-in is an ACTIVE SALES_STAFF account (D42, tg_staff_cover_stand_in)
--   LH  customer.lead_history: append-only; a row records a change (D44)
--   TB  customer.sales_owner_transfer_batch / _item: one batch per request id; temporary <-> TEMPORARY_LEAVE; a return
--       names the batch it returns, once; an item MOVED names its target, SKIPPED says why
--   AS  customer.sales_assignment_setting: capacity within bounds
--   LD  customer.lead: a Sales from ASSIGNED on, none while NEW; CONVERTED names its customer, DISQUALIFIED says why
--   CR  customer.sales_owner_change_request: one PENDING per customer; a decision says who and what
--   GR  the grants of the plan; BF the backfill over the demo seed
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

CREATE FUNCTION pg_temp.id(key TEXT) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5('qa:sales-owner:' || key)::uuid $$;

-- Fixtures: two active salespeople, a locked one, a portal account, a Sales Manager, two customers.
INSERT INTO identity.app_user (id, username, email, status, version, created_at)
VALUES (pg_temp.id('manager'), 'qa-so-manager', 'qa-so-manager@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('a'), 'qa-so-a', 'qa-so-a@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('b'), 'qa-so-b', 'qa-so-b@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('locked'), 'qa-so-locked', 'qa-so-locked@example.test', 'LOCKED', 0, NOW()),
       (pg_temp.id('portal'), 'qa-so-portal', 'qa-so-portal@example.test', 'ACTIVE', 0, NOW());
INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
SELECT gen_random_uuid(), u.id, r.id, 0, NOW()
  FROM (VALUES (pg_temp.id('a'), 'SALES_STAFF'), (pg_temp.id('b'), 'SALES_STAFF'),
               (pg_temp.id('locked'), 'SALES_STAFF'), (pg_temp.id('portal'), 'CUSTOMER'),
               (pg_temp.id('manager'), 'SALES_MANAGER')) AS u(id, role)
  JOIN identity.app_role r ON r.code = u.role;
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (pg_temp.id('c1'), 'QA owner customer', 'qa-so-c1@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('c2'), 'QA owner customer 2', 'qa-so-c2@example.test', 'ACTIVE', 0, NOW());

\echo '--- SO: the owner on the customer'
SELECT pg_temp.expect_fail_like('SO1 UNASSIGNED with an owner', 'ck_customer_sales_owner_consistent',
    format('UPDATE customer.customer SET sales_owner_id = %L WHERE id = %L', pg_temp.id('a'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO2 NEEDS_ASSIGNMENT without the owner who left', 'ck_customer_sales_owner_consistent',
    format('UPDATE customer.customer SET sales_owner_state = ''NEEDS_ASSIGNMENT'', sales_owner_since = NOW() '
           'WHERE id = %L', pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO2b ASSIGNED without an owner', 'must be an active salesperson',
    format('UPDATE customer.customer SET sales_owner_state = ''ASSIGNED'', sales_owner_since = NOW() WHERE id = %L',
           pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO3 NEEDS_ASSIGNMENT without a date', 'ck_customer_sales_owner_consistent',
    format('UPDATE customer.customer SET sales_owner_state = ''NEEDS_ASSIGNMENT'', sales_owner_id = %L WHERE id = %L',
           pg_temp.id('a'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO4 a state outside the three', 'ck_customer_sales_owner_state',
    format('UPDATE customer.customer SET sales_owner_state = ''ORPHANED'', sales_owner_id = %L, '
           'sales_owner_since = NOW() WHERE id = %L', pg_temp.id('b'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO5 BR-S04: a locked account is not assigned', 'must be an active salesperson',
    format('UPDATE customer.customer SET sales_owner_state = ''ASSIGNED'', sales_owner_id = %L, '
           'sales_owner_since = NOW() WHERE id = %L', pg_temp.id('locked'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO6 BR-S04: a portal customer is not assigned', 'must be an active salesperson',
    format('UPDATE customer.customer SET sales_owner_state = ''ASSIGNED'', sales_owner_id = %L, '
           'sales_owner_since = NOW() WHERE id = %L', pg_temp.id('portal'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO6b D42: a Sales Manager is not assigned', 'must be an active salesperson',
    format('UPDATE customer.customer SET sales_owner_state = ''ASSIGNED'', sales_owner_id = %L, '
           'sales_owner_since = NOW() WHERE id = %L', pg_temp.id('manager'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO7 an owner that is no account', 'fk_customer_sales_owner',
    format('UPDATE customer.customer SET sales_owner_state = ''NEEDS_ASSIGNMENT'', sales_owner_id = %L, '
           'sales_owner_since = NOW() WHERE id = %L', gen_random_uuid(), pg_temp.id('c1')));
SELECT pg_temp.expect_ok('SO8 an active salesperson is assigned',
    format('UPDATE customer.customer SET sales_owner_state = ''ASSIGNED'', sales_owner_id = %L, '
           'sales_owner_since = NOW() WHERE id = %L', pg_temp.id('a'), pg_temp.id('c1')));
UPDATE identity.app_user SET status = 'LOCKED' WHERE id = pg_temp.id('a');
SELECT pg_temp.expect_ok('SO9 an owner locked later does not block other edits of the customer',
    format('UPDATE customer.customer SET full_name = ''QA renamed'' WHERE id = %L', pg_temp.id('c1')));
SELECT pg_temp.expect_ok('SO10 BR-S08: the customer of a locked owner waits for one, the owner kept',
    format('UPDATE customer.customer SET sales_owner_state = ''NEEDS_ASSIGNMENT'' WHERE id = %L', pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SO11 BR-S10: the locked former owner is not re-assigned', 'must be an active salesperson',
    format('UPDATE customer.customer SET sales_owner_state = ''ASSIGNED'' WHERE id = %L', pg_temp.id('c1')));
UPDATE identity.app_user SET status = 'ACTIVE' WHERE id = pg_temp.id('a');

\echo '--- SH: the history'
SELECT pg_temp.expect_ok('SH1 a change to an owner',
    format('INSERT INTO customer.customer_sales_owner_history (id, customer_id, from_user_id, to_user_id, to_state, '
           'reason, changed_at, created_at) VALUES (%L, %L, NULL, %L, ''ASSIGNED'', ''CREATED_BY_SALES'', NOW(), NOW())',
           pg_temp.id('h1'), pg_temp.id('c1'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('SH2 append-only: no UPDATE', 'append-only',
    format('UPDATE customer.customer_sales_owner_history SET note = ''edited'' WHERE id = %L', pg_temp.id('h1')));
SELECT pg_temp.expect_fail_like('SH3 append-only: no DELETE', 'append-only',
    format('DELETE FROM customer.customer_sales_owner_history WHERE id = %L', pg_temp.id('h1')));
SELECT pg_temp.expect_fail_like('SH4 ASSIGNED names the new owner', 'ck_customer_sales_owner_history_target',
    format('INSERT INTO customer.customer_sales_owner_history (id, customer_id, to_state, reason, changed_at, '
           'created_at) VALUES (gen_random_uuid(), %L, ''ASSIGNED'', ''WORKLOAD'', NOW(), NOW())', pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SH5 NEEDS_ASSIGNMENT names who left and nobody new',
    'ck_customer_sales_owner_history_target',
    format('INSERT INTO customer.customer_sales_owner_history (id, customer_id, from_user_id, to_user_id, to_state, '
           'reason, changed_at, created_at) VALUES (gen_random_uuid(), %L, %L, %L, ''NEEDS_ASSIGNMENT'', '
           '''STAFF_LEFT'', NOW(), NOW())', pg_temp.id('c1'), pg_temp.id('a'), pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('SH6 a change never makes a customer UNASSIGNED', 'ck_customer_sales_owner_history_target',
    format('INSERT INTO customer.customer_sales_owner_history (id, customer_id, from_user_id, to_state, reason, '
           'changed_at, created_at) VALUES (gen_random_uuid(), %L, %L, ''UNASSIGNED'', ''WORKLOAD'', NOW(), NOW())',
           pg_temp.id('c1'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('SH7 BR-S03: OTHER needs a note', 'ck_customer_sales_owner_history_note',
    format('INSERT INTO customer.customer_sales_owner_history (id, customer_id, from_user_id, to_user_id, to_state, '
           'reason, note, changed_at, created_at) VALUES (gen_random_uuid(), %L, %L, %L, ''ASSIGNED'', ''OTHER'', '
           ''' '', NOW(), NOW())', pg_temp.id('c1'), pg_temp.id('a'), pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('SH8 a reason outside the list', 'ck_customer_sales_owner_history_reason',
    format('INSERT INTO customer.customer_sales_owner_history (id, customer_id, to_user_id, to_state, reason, '
           'changed_at, created_at) VALUES (gen_random_uuid(), %L, %L, ''ASSIGNED'', ''FAVOURITE'', NOW(), NOW())',
           pg_temp.id('c1'), pg_temp.id('a')));
INSERT INTO customer.customer_sales_owner_history (id, customer_id, to_user_id, to_state, reason, changed_at, created_at)
VALUES (gen_random_uuid(), pg_temp.id('c2'), pg_temp.id('b'), 'ASSIGNED', 'CREATED_BY_SALES', NOW(), NOW());
SELECT pg_temp.expect_ok('SH9 rows leave with their customer (cascade)',
    format('DELETE FROM customer.customer WHERE id = %L', pg_temp.id('c2')));
SELECT pg_temp.expect_true('SH10 ... and only then',
    NOT EXISTS (SELECT 1 FROM customer.customer_sales_owner_history WHERE customer_id = pg_temp.id('c2')));

\echo '--- SC: covers'
SELECT pg_temp.expect_ok('SC1 a cover of 60 days counted inclusively',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(%L, %L, %L, DATE ''2027-03-01'', DATE ''2027-03-01'' + 59, NOW())',
           pg_temp.id('cover1'), pg_temp.id('a'), pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('SC2 D7: 61 days', 'ck_staff_cover_length',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-06-01'', DATE ''2027-06-01'' + 60, NOW())',
           pg_temp.id('b'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('SC3 the last day before the first', 'ck_staff_cover_days',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-06-05'', DATE ''2027-06-01'', NOW())',
           pg_temp.id('b'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('SC4 nobody covers for themselves', 'ck_staff_cover_not_self',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-06-01'', DATE ''2027-06-01'', NOW())',
           pg_temp.id('b'), pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('SC5 BR-S07: two covers of one person sharing a day', 'ex_staff_cover_overlap',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-04-29'', DATE ''2027-05-03'', NOW())',
           pg_temp.id('a'), pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('SC5b D42: a Sales Manager does not stand in', 'stand-in must be an active salesperson',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-08-01'', DATE ''2027-08-02'', NOW())',
           pg_temp.id('b'), pg_temp.id('manager')));
SELECT pg_temp.expect_fail_like('SC5c BR-S07: a locked account does not stand in', 'stand-in must be an active salesperson',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-08-01'', DATE ''2027-08-02'', NOW())',
           pg_temp.id('b'), pg_temp.id('locked')));
SELECT pg_temp.expect_fail_like('SC6 a cancelled cover says when and why', 'ck_staff_cover_cancelled',
    format('UPDATE identity.staff_cover SET status = ''CANCELLED'' WHERE id = %L', pg_temp.id('cover1')));
SELECT pg_temp.expect_fail_like('SC7 a cancel reason outside the list', 'ck_staff_cover_cancelled',
    format('UPDATE identity.staff_cover SET status = ''CANCELLED'', cancelled_at = NOW(), cancel_reason = ''BORED'' '
           'WHERE id = %L', pg_temp.id('cover1')));
SELECT pg_temp.expect_ok('SC8 cancelled ...',
    format('UPDATE identity.staff_cover SET status = ''CANCELLED'', cancelled_at = NOW(), cancel_reason = ''MANUAL'' '
           'WHERE id = %L', pg_temp.id('cover1')));
SELECT pg_temp.expect_ok('SC9 ... its days are free for another cover',
    format('INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, DATE ''2027-04-29'', DATE ''2027-05-03'', NOW())',
           pg_temp.id('a'), pg_temp.id('b')));

\echo '--- TB: transfer batches'
SELECT pg_temp.expect_ok('TB1 a temporary transfer',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, temporary, '
           'requested_at, created_at) VALUES (%L, %L, %L, ''TEMPORARY_LEAVE'', TRUE, NOW(), NOW())',
           pg_temp.id('batch'), pg_temp.id('request'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('TB2 BR-S09: one batch per request id', 'uk_sales_owner_transfer_batch_request',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, requested_at, '
           'created_at) VALUES (gen_random_uuid(), %L, %L, ''WORKLOAD'', NOW(), NOW())',
           pg_temp.id('request'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('TB3 temporary is TEMPORARY_LEAVE and only that', 'ck_sales_owner_transfer_batch_temporary',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, temporary, '
           'requested_at, created_at) VALUES (gen_random_uuid(), gen_random_uuid(), %L, ''WORKLOAD'', TRUE, NOW(), '
           'NOW())', pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('TB4 a return names the batch it returns', 'ck_sales_owner_transfer_batch_return',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, requested_at, '
           'created_at) VALUES (gen_random_uuid(), gen_random_uuid(), %L, ''RETURN_FROM_LEAVE'', NOW(), NOW())',
           pg_temp.id('b')));
SELECT pg_temp.expect_ok('TB5 the return of the temporary batch',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, returns_batch_id, '
           'requested_at, created_at) VALUES (gen_random_uuid(), gen_random_uuid(), %L, ''RETURN_FROM_LEAVE'', %L, '
           'NOW(), NOW())', pg_temp.id('b'), pg_temp.id('batch')));
SELECT pg_temp.expect_fail_like('TB6 §10.1: a temporary batch is returned once', 'uk_sales_owner_transfer_batch_returns',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, returns_batch_id, '
           'requested_at, created_at) VALUES (gen_random_uuid(), gen_random_uuid(), %L, ''RETURN_FROM_LEAVE'', %L, '
           'NOW(), NOW())', pg_temp.id('b'), pg_temp.id('batch')));
SELECT pg_temp.expect_fail_like('TB7 BR-S03: OTHER needs a note', 'ck_sales_owner_transfer_batch_note',
    format('INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, requested_at, '
           'created_at) VALUES (gen_random_uuid(), gen_random_uuid(), %L, ''OTHER'', NOW(), NOW())', pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('TB8 a moved item names its target', 'ck_sales_owner_transfer_item_outcome',
    format('INSERT INTO customer.sales_owner_transfer_item (id, batch_id, customer_id, outcome, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, ''MOVED'', NOW())', pg_temp.id('batch'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('TB9 a skipped item says why', 'ck_sales_owner_transfer_item_outcome',
    format('INSERT INTO customer.sales_owner_transfer_item (id, batch_id, customer_id, outcome, created_at) VALUES '
           '(gen_random_uuid(), %L, %L, ''SKIPPED'', NOW())', pg_temp.id('batch'), pg_temp.id('c1')));
SELECT pg_temp.expect_ok('TB10 an item moved',
    format('INSERT INTO customer.sales_owner_transfer_item (id, batch_id, customer_id, outcome, to_user_id, created_at) '
           'VALUES (gen_random_uuid(), %L, %L, ''MOVED'', %L, NOW())', pg_temp.id('batch'), pg_temp.id('c1'),
           pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('TB11 a customer once per batch', 'uk_sales_owner_transfer_item_customer',
    format('INSERT INTO customer.sales_owner_transfer_item (id, batch_id, customer_id, outcome, skip_reason, created_at) '
           'VALUES (gen_random_uuid(), %L, %L, ''SKIPPED'', ''OWNER_CHANGED'', NOW())', pg_temp.id('batch'),
           pg_temp.id('c1')));

\echo '--- AS: assignment settings'
SELECT pg_temp.expect_fail_like('AS1 D13: a capacity below zero', 'ck_sales_assignment_setting_capacity',
    format('INSERT INTO customer.sales_assignment_setting (user_id, capacity_per_month, created_at) VALUES (%L, -1, '
           'NOW())', pg_temp.id('a')));
SELECT pg_temp.expect_ok('AS2 paused, no limit',
    format('INSERT INTO customer.sales_assignment_setting (user_id, paused, created_at) VALUES (%L, TRUE, NOW())',
           pg_temp.id('a')));

\echo '--- LD: leads'
SELECT pg_temp.expect_fail_like('LD1 a NEW lead has no Sales yet', 'ck_lead_assignee',
    format('INSERT INTO customer.lead (id, company_name, contact_name, email, consent_at, submitted_at, assigned_to, '
           'assigned_at, created_at) VALUES (gen_random_uuid(), ''Co'', ''A'', ''a@example.test'', NOW(), NOW(), %L, '
           'NOW(), NOW())', pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('LD2 an ASSIGNED lead has its Sales', 'ck_lead_assignee',
    'INSERT INTO customer.lead (id, company_name, contact_name, email, consent_at, submitted_at, status, created_at) '
    'VALUES (gen_random_uuid(), ''Co'', ''A'', ''a@example.test'', NOW(), NOW(), ''ASSIGNED'', NOW())');
SELECT pg_temp.expect_fail_like('LD3 CONVERTED names its customer', 'ck_lead_converted',
    format('INSERT INTO customer.lead (id, company_name, contact_name, email, consent_at, submitted_at, status, '
           'assigned_to, assigned_at, created_at) VALUES (gen_random_uuid(), ''Co'', ''A'', ''a@example.test'', NOW(), '
           'NOW(), ''CONVERTED'', %L, NOW(), NOW())', pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('LD4 DISQUALIFIED says why', 'ck_lead_disqualified',
    'INSERT INTO customer.lead (id, company_name, contact_name, email, consent_at, submitted_at, status, '
    'disqualified_at, created_at) VALUES (gen_random_uuid(), ''Co'', ''A'', ''a@example.test'', NOW(), NOW(), '
    '''DISQUALIFIED'', NOW(), NOW())');
SELECT pg_temp.expect_fail_like('LD5 a lead can be reached', 'ck_lead_reachable',
    'INSERT INTO customer.lead (id, company_name, contact_name, consent_at, submitted_at, created_at) VALUES '
    '(gen_random_uuid(), ''Co'', ''A'', NOW(), NOW(), NOW())');
SELECT pg_temp.expect_fail_like('LD6 a malformed tax code', 'ck_lead_tax_code',
    'INSERT INTO customer.lead (id, company_name, tax_code, contact_name, phone, consent_at, submitted_at, created_at) '
    'VALUES (gen_random_uuid(), ''Co'', ''12AB'', ''A'', ''+84901234567'', NOW(), NOW(), NOW())');
SELECT pg_temp.expect_fail_like('LD7 a lead needs consent', 'consent_at',
    'INSERT INTO customer.lead (id, company_name, contact_name, phone, submitted_at, created_at) VALUES '
    '(gen_random_uuid(), ''Co'', ''A'', ''+84901234567'', NOW(), NOW())');
SELECT pg_temp.expect_ok('LD8 a lead converted into a customer',
    format('INSERT INTO customer.lead (id, company_name, contact_name, phone, consent_at, submitted_at, status, '
           'assigned_to, assigned_at, converted_customer_id, converted_at, created_at) VALUES (gen_random_uuid(), '
           '''Co'', ''A'', ''+84901234567'', NOW(), NOW(), ''CONVERTED'', %L, NOW(), %L, NOW(), NOW())',
           pg_temp.id('a'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('LD9 a customer comes from one lead', 'uk_lead_converted_customer',
    format('INSERT INTO customer.lead (id, company_name, contact_name, phone, consent_at, submitted_at, status, '
           'assigned_to, assigned_at, converted_customer_id, converted_at, created_at) VALUES (gen_random_uuid(), '
           '''Co'', ''A'', ''+84901234567'', NOW(), NOW(), ''CONVERTED'', %L, NOW(), %L, NOW(), NOW())',
           pg_temp.id('a'), pg_temp.id('c1')));

\echo '--- LH: lead history'
INSERT INTO customer.lead (id, company_name, contact_name, phone, consent_at, submitted_at, created_at)
VALUES (pg_temp.id('lead'), 'QA lead', 'A', '+84901234567', NOW(), NOW(), NOW());
SELECT pg_temp.expect_ok('LH1 D44: a lead back in the queue',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, from_user_id, note, changed_at, '
           'created_at) VALUES (%L, %L, ''ASSIGNED'', ''NEW'', %L, ''account locked'', NOW(), NOW())',
           pg_temp.id('lh1'), pg_temp.id('lead'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('LH2 append-only: no UPDATE', 'append-only',
    format('UPDATE customer.lead_history SET note = ''edited'' WHERE id = %L', pg_temp.id('lh1')));
SELECT pg_temp.expect_fail_like('LH3 append-only: no DELETE', 'append-only',
    format('DELETE FROM customer.lead_history WHERE id = %L', pg_temp.id('lh1')));
SELECT pg_temp.expect_fail_like('LH4 a row records a change', 'ck_lead_history_change',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, changed_at, created_at) VALUES '
           '(gen_random_uuid(), %L, ''NEW'', ''NEW'', NOW(), NOW())', pg_temp.id('lead')));
SELECT pg_temp.expect_fail_like('LH5 a status outside the list', 'ck_lead_history_to_status',
    format('INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, changed_at, created_at) VALUES '
           '(gen_random_uuid(), %L, ''NEW'', ''LOST'', NOW(), NOW())', pg_temp.id('lead')));
SELECT pg_temp.expect_ok('LH6 rows leave with their lead',
    format('DELETE FROM customer.lead WHERE id = %L', pg_temp.id('lead')));

\echo '--- CR: requests to change salesperson'
SELECT pg_temp.expect_ok('CR1 a pending request',
    format('INSERT INTO customer.sales_owner_change_request (id, customer_id, requested_at, reason, created_at) VALUES '
           '(%L, %L, NOW(), ''SLOW_RESPONSE'', NOW())', pg_temp.id('cr1'), pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('CR2 §10.5: one pending request per customer', 'uk_sales_owner_change_request_pending',
    format('INSERT INTO customer.sales_owner_change_request (id, customer_id, requested_at, reason, created_at) VALUES '
           '(gen_random_uuid(), %L, NOW(), ''OTHER'', NOW())', pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('CR3 a reason outside the list', 'ck_sales_owner_change_request_reason',
    format('INSERT INTO customer.sales_owner_change_request (id, customer_id, requested_at, reason, status, decided_at, '
           'created_at) VALUES (gen_random_uuid(), %L, NOW(), ''BORED'', ''WITHDRAWN'', NOW(), NOW())', pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('CR4 an approval names the new owner', 'ck_sales_owner_change_request_decision',
    format('UPDATE customer.sales_owner_change_request SET status = ''APPROVED'', decided_at = NOW(), decided_by = %L '
           'WHERE id = %L', pg_temp.id('b'), pg_temp.id('cr1')));
SELECT pg_temp.expect_fail_like('CR5 a rejection says why', 'ck_sales_owner_change_request_decision',
    format('UPDATE customer.sales_owner_change_request SET status = ''REJECTED'', decided_at = NOW(), decided_by = %L '
           'WHERE id = %L', pg_temp.id('b'), pg_temp.id('cr1')));
SELECT pg_temp.expect_ok('CR6 rejected with a note ...',
    format('UPDATE customer.sales_owner_change_request SET status = ''REJECTED'', decided_at = NOW(), decided_by = %L, '
           'decision_note = ''Đang xử lý đơn lớn'' WHERE id = %L', pg_temp.id('b'), pg_temp.id('cr1')));
SELECT pg_temp.expect_ok('CR7 ... and a new pending request is possible (the 30 days are the service''s)',
    format('INSERT INTO customer.sales_owner_change_request (id, customer_id, requested_at, reason, created_at) VALUES '
           '(gen_random_uuid(), %L, NOW(), ''OTHER'', NOW())', pg_temp.id('c1')));

\echo '--- GR: grants'
SELECT pg_temp.expect_true('GR1 BR-S01: only a manager and the admin change owners',
    (SELECT array_agg(r.code ORDER BY r.code) FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.code = 'customer-sales-owners:UPDATE') = ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']::varchar[]);
SELECT pg_temp.expect_true('GR2 a portal customer asks to change and reads its requests, nothing else of the plan',
    (SELECT array_agg(p.code ORDER BY p.code) FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'CUSTOMER' AND p.resource IN ('customer-sales-owners', 'staff-covers', 'sales-leads',
                                                   'sales-workload', 'sales-owner-change-requests'))
    = ARRAY['sales-owner-change-requests:CREATE', 'sales-owner-change-requests:READ']::varchar[]);
SELECT pg_temp.expect_true('GR3 the System Admin holds every permission of the plan',
    (SELECT count(*) FROM identity.permission p
      WHERE p.resource IN ('customer-sales-owners', 'staff-covers', 'sales-leads', 'sales-workload',
                           'sales-owner-change-requests')
        AND NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                         WHERE r.code = 'SYSTEM_ADMIN' AND rp.permission_id = p.id)) = 0);

\echo '--- BF: the backfill over the demo seed'
SELECT pg_temp.expect_true('BF1 every demo customer given an owner has its BACKFILL row, and only those',
    NOT EXISTS (SELECT 1 FROM customer.customer c
                 WHERE c.id <> pg_temp.id('c1')
                   AND (c.sales_owner_state <> 'UNASSIGNED') <> EXISTS (
                       SELECT 1 FROM customer.customer_sales_owner_history h
                        WHERE h.customer_id = c.id AND h.reason = 'BACKFILL')));
SELECT pg_temp.expect_true('BF2 no backfilled owner is a portal account',
    NOT EXISTS (SELECT 1 FROM customer.customer c JOIN identity.user_role ur ON ur.user_id = c.sales_owner_id
                  JOIN identity.app_role r ON r.id = ur.role_id WHERE r.code = 'CUSTOMER'));

ROLLBACK;
