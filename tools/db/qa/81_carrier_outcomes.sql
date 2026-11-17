-- =============================================================================
-- Adversarial checks + happy paths for V20261122000100 (WF-05 fix plan items 5 and 6): a file of delivery outcomes and
-- its lines; a carrier event's source (WEBHOOK / FILE) and the result UNVERIFIED; a carrier's second webhook
-- credential. Each rule is attacked by the statement that breaks only it (expect_fail_by names the rule).
-- Needs the demo seed (the HCM warehouse, the editor, the GHN carrier). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
-- Refused by the named rule: a statement that breaks something else proves nothing.
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM NOT LIKE '%' || rule || '%' THEN
            RAISE EXCEPTION 'FAIL  %: refused, but not by %: %', label, rule, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, rule;
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

-- Fixtures: a closed vehicle of the demo carrier (an open one must be a load plan's), a carrier booked through an API,
-- and a webhook call kept the way calls were before the migration - with no source named.
INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, status, cancelled_at,
                                       cancel_reason, created_at)
VALUES (md5('qa81:load')::uuid, 'LD-QA-81', gen_random_uuid(), md5('demo:wh:HCM')::uuid, md5('carrier:GHN')::uuid,
        'CANCELLED', NOW(), 'qa', NOW());
INSERT INTO fulfillment.carrier (id, code, name, connection_type, webhook_enabled, webhook_auth_header,
                                 webhook_auth_value_sealed, api_environment, api_account, api_key_sealed)
VALUES (md5('qa81:aha')::uuid, 'QA81-AHA', 'Ahamove QA', 'API_BOOKING', TRUE, 'apikey', 'v1:AAAAAAAAAAAAAAAAAAAAAA==',
        'STAGING', '84908000081', 'v1:AAAAAAAAAAAAAAAAAAAAAA==');
INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result)
VALUES (md5('qa81:hook')::uuid, md5('qa81:aha')::uuid, 'QA81-AHA', NOW(), '{}', TRUE, 'RECEIVED');

\echo '--- a file of delivery outcomes and its lines (item 5)'
SELECT pg_temp.expect_fail_by('A1 an import whose lines do not add up', 'ck_delivery_outcome_import_rows', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (gen_random_uuid(), 'x.csv', repeat('a', 64), md5('demo:user:editor')::uuid, NOW(), 3, 1, 1) $q$);
SELECT pg_temp.expect_fail_by('A2 an import of no line', 'ck_delivery_outcome_import_rows', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (gen_random_uuid(), 'x.csv', repeat('a', 64), md5('demo:user:editor')::uuid, NOW(), 0, 0, 0) $q$);
SELECT pg_temp.expect_fail_by('A3 an import of more than 1,000 lines', 'ck_delivery_outcome_import_rows', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (gen_random_uuid(), 'x.csv', repeat('a', 64), md5('demo:user:editor')::uuid, NOW(), 1001, 1001, 0) $q$);
SELECT pg_temp.expect_fail_by('A4 a digest that is not lower-case SHA-256', 'ck_delivery_outcome_import_digest', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (gen_random_uuid(), 'x.csv', repeat('A', 64), md5('demo:user:editor')::uuid, NOW(), 2, 1, 1) $q$);
SELECT pg_temp.expect_fail_by('A5 a file with no name', 'ck_delivery_outcome_import_name', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (gen_random_uuid(), '  ', repeat('a', 64), md5('demo:user:editor')::uuid, NOW(), 2, 1, 1) $q$);
SELECT pg_temp.expect_fail_by('A6 an import by nobody the system knows', 'fk_delivery_outcome_import_uploaded_by', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (gen_random_uuid(), 'x.csv', repeat('a', 64), gen_random_uuid(), NOW(), 2, 1, 1) $q$);
SELECT pg_temp.expect_ok('A7 an import of two lines, one accepted and one refused', $q$
    INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                     rows_accepted, rows_rejected)
    VALUES (md5('qa81:import')::uuid, 'ket-qua.csv', repeat('a', 64), md5('demo:user:editor')::uuid, NOW(), 2, 1, 1) $q$);

SELECT pg_temp.expect_fail_by('B1 a refused line that does not say why', 'ck_delivery_outcome_import_row_problem', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, verdict)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 2, 'WB-1', 'ERROR') $q$);
SELECT pg_temp.expect_fail_by('B2 an accepted line with a problem', 'ck_delivery_outcome_import_row_problem', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, load_id, verdict, problem)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 2, 'WB-1', md5('qa81:load')::uuid, 'ACCEPTED',
            'WAYBILL_NOT_FOUND') $q$);
SELECT pg_temp.expect_fail_by('B3 a problem the check does not have', 'ck_delivery_outcome_import_row_problem', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, verdict, problem)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 2, 'WB-1', 'ERROR', 'BAD_LUCK') $q$);
SELECT pg_temp.expect_fail_by('B4 a verdict that is neither', 'ck_delivery_outcome_import_row_verdict', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, load_id, verdict)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 2, 'WB-1', md5('qa81:load')::uuid, 'APPLIED') $q$);
SELECT pg_temp.expect_fail_by('B5 an accepted line that found no vehicle', 'ck_delivery_outcome_import_row_load', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, verdict)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 2, 'WB-1', 'ACCEPTED') $q$);
SELECT pg_temp.expect_fail_by('B6 the heading line as a data line', 'ck_delivery_outcome_import_row_line', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, load_id, verdict)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 1, 'WB-1', md5('qa81:load')::uuid, 'ACCEPTED') $q$);
SELECT pg_temp.expect_fail_by('B7 a line of an import that is not there', 'fk_delivery_outcome_import_row_import', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, load_id, verdict)
    VALUES (gen_random_uuid(), gen_random_uuid(), 2, 'WB-1', md5('qa81:load')::uuid, 'ACCEPTED') $q$);
SELECT pg_temp.expect_ok('B8 an accepted line and a refused one', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, outcome, reported_at,
                                                         recipient, load_id, verdict, problem)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 2, 'WB-1', 'DELIVERED', '2026-11-09 10:15', 'Chị Lan',
            md5('qa81:load')::uuid, 'ACCEPTED', NULL),
           (gen_random_uuid(), md5('qa81:import')::uuid, 3, 'WB-9', 'DELIVERED', '2026-11-09 10:15', NULL, NULL,
            'ERROR', 'WAYBILL_NOT_FOUND') $q$);
SELECT pg_temp.expect_fail_by('B9 the same line twice', 'uk_delivery_outcome_import_row_line', $q$
    INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, load_id, verdict)
    VALUES (gen_random_uuid(), md5('qa81:import')::uuid, 3, 'WB-1', md5('qa81:load')::uuid, 'ACCEPTED') $q$);
SELECT pg_temp.expect_fail_by('B10 an import rewritten', 'append-only', $q$
    UPDATE fulfillment.delivery_outcome_import SET rows_accepted = 2, rows_rejected = 0
     WHERE id = md5('qa81:import')::uuid $q$);
SELECT pg_temp.expect_fail_by('B11 a line rewritten', 'append-only', $q$
    UPDATE fulfillment.delivery_outcome_import_row SET verdict = 'ERROR', problem = 'INVALID_TIME'
     WHERE import_id = md5('qa81:import')::uuid AND line_no = 2 $q$);
SELECT pg_temp.expect_fail_by('B12 a line deleted', 'append-only', $q$
    DELETE FROM fulfillment.delivery_outcome_import_row WHERE import_id = md5('qa81:import')::uuid $q$);

\echo '--- a carrier event''s source, and a line of a file as an event (item 5)'
SELECT pg_temp.expect_true('C1 a call kept with no source named is a webhook''s', (
    SELECT source = 'WEBHOOK' AND outcome_import_id IS NULL AND outcome_import_line IS NULL
      FROM fulfillment.carrier_event WHERE id = md5('qa81:hook')::uuid));
SELECT pg_temp.expect_fail_by('C2 a source the system does not have', 'ck_carrier_event_source', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           source)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', 'EMAIL') $q$);
SELECT pg_temp.expect_fail_by('C3 a file''s line naming no import', 'ck_carrier_event_import', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           load_id, warehouse_id, source)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', md5('qa81:load')::uuid,
            md5('demo:wh:HCM')::uuid, 'FILE') $q$);
SELECT pg_temp.expect_fail_by('C4 a webhook call naming an import', 'ck_carrier_event_import', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           source, outcome_import_id, outcome_import_line)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', 'WEBHOOK',
            md5('qa81:import')::uuid, 2) $q$);
SELECT pg_temp.expect_fail_by('C5 a file''s line naming its import and no line', 'ck_carrier_event_import', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           load_id, warehouse_id, source, outcome_import_id)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', md5('qa81:load')::uuid,
            md5('demo:wh:HCM')::uuid, 'FILE', md5('qa81:import')::uuid) $q$);
SELECT pg_temp.expect_fail_by('C6 a file''s line about no vehicle', 'ck_carrier_event_file', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           source, outcome_import_id, outcome_import_line)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', 'FILE',
            md5('qa81:import')::uuid, 2) $q$);
SELECT pg_temp.expect_fail_by('C7 a file''s line of an import that is not there', 'fk_carrier_event_outcome_import', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           load_id, warehouse_id, source, outcome_import_id, outcome_import_line)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', md5('qa81:load')::uuid,
            md5('demo:wh:HCM')::uuid, 'FILE', gen_random_uuid(), 2) $q$);
SELECT pg_temp.expect_ok('C8 a file''s line as an event', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           load_id, warehouse_id, source, outcome_import_id, outcome_import_line)
    VALUES (md5('qa81:file')::uuid, md5('carrier:GHN')::uuid, 'GHN', NOW(), '{"source":"FILE"}', TRUE, 'RECEIVED',
            md5('qa81:load')::uuid, md5('demo:wh:HCM')::uuid, 'FILE', md5('qa81:import')::uuid, 2) $q$);
SELECT pg_temp.expect_fail_by('C9 a second event of the same line', 'uk_carrier_event_import_line', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           load_id, warehouse_id, source, outcome_import_id, outcome_import_line)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'RECEIVED', md5('qa81:load')::uuid,
            md5('demo:wh:HCM')::uuid, 'FILE', md5('qa81:import')::uuid, 2) $q$);
SELECT pg_temp.expect_fail_by('C10 a file''s line turned into a webhook call', 'kept as it came', $q$
    UPDATE fulfillment.carrier_event SET source = 'WEBHOOK', outcome_import_id = NULL, outcome_import_line = NULL
     WHERE id = md5('qa81:file')::uuid $q$);
SELECT pg_temp.expect_fail_by('C11 an event moved to another line', 'kept as it came', $q$
    UPDATE fulfillment.carrier_event SET outcome_import_line = 3 WHERE id = md5('qa81:file')::uuid $q$);
SELECT pg_temp.expect_ok('C12 a file''s line decided as a repeat', $q$
    UPDATE fulfillment.carrier_event SET result = 'DUPLICATE', result_note = 'The same report was handled already',
           decided_at = NOW() WHERE id = md5('qa81:file')::uuid $q$);
SELECT pg_temp.expect_fail_by('C13 a decided line decided again', 'decided once', $q$
    UPDATE fulfillment.carrier_event SET result = 'APPLIED' WHERE id = md5('qa81:file')::uuid $q$);
SELECT pg_temp.expect_fail_by('C14 a repeat resolved by hand', 'ck_carrier_event_resolvable', $q$
    UPDATE fulfillment.carrier_event SET resolved_at = NOW(), resolved_by = md5('demo:user:editor')::uuid,
           resolution_note = 'x' WHERE id = md5('qa81:file')::uuid $q$);

\echo '--- UNVERIFIED: a final outcome the carrier''s API did not confirm (item 6)'
SELECT pg_temp.expect_fail_by('D1 a result the system does not have', 'ck_carrier_event_result', $q$
    UPDATE fulfillment.carrier_event SET result = 'VERIFIED', decided_at = NOW() WHERE id = md5('qa81:hook')::uuid $q$);
SELECT pg_temp.expect_fail_by('D2 unverified about no vehicle', 'ck_carrier_event_unverified', $q$
    UPDATE fulfillment.carrier_event SET result = 'UNVERIFIED', result_note = 'x', decided_at = NOW()
     WHERE id = md5('qa81:hook')::uuid $q$);
SELECT pg_temp.expect_fail_by('D3 unverified that does not say what disagreed', 'ck_carrier_event_unverified', $q$
    UPDATE fulfillment.carrier_event SET result = 'UNVERIFIED', decided_at = NOW(), load_id = md5('qa81:load')::uuid,
           warehouse_id = md5('demo:wh:HCM')::uuid WHERE id = md5('qa81:hook')::uuid $q$);
SELECT pg_temp.expect_fail_by('D4 a file''s line unverified: nobody is asked back about a file', 'ck_carrier_event_unverified', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           result_note, decided_at, load_id, warehouse_id, source, outcome_import_id,
                                           outcome_import_line)
    VALUES (gen_random_uuid(), md5('carrier:GHN')::uuid, 'GHN', NOW(), '{}', TRUE, 'UNVERIFIED', 'x', NOW(),
            md5('qa81:load')::uuid, md5('demo:wh:HCM')::uuid, 'FILE', md5('qa81:import')::uuid, 3) $q$);
SELECT pg_temp.expect_ok('D5 a webhook call decided unverified', $q$
    UPDATE fulfillment.carrier_event SET result = 'UNVERIFIED', result_note = 'The carrier''s API says IN PROCESS',
           decided_at = NOW(), load_id = md5('qa81:load')::uuid, warehouse_id = md5('demo:wh:HCM')::uuid
     WHERE id = md5('qa81:hook')::uuid $q$);
SELECT pg_temp.expect_fail_by('D6 an unverified call applied after all', 'decided once', $q$
    UPDATE fulfillment.carrier_event SET result = 'APPLIED' WHERE id = md5('qa81:hook')::uuid $q$);
SELECT pg_temp.expect_true('D7 an open unverified call is on the error list''s index', (
    SELECT pg_get_indexdef(indexrelid) LIKE '%UNVERIFIED%' FROM pg_index
     WHERE indexrelid = 'fulfillment.ix_carrier_event_errors'::regclass));
SELECT pg_temp.expect_ok('D8 the warehouse manager resolves it by hand', $q$
    UPDATE fulfillment.carrier_event SET resolved_at = NOW(), resolved_by = md5('demo:user:editor')::uuid,
           resolution_note = 'asked the carrier by phone' WHERE id = md5('qa81:hook')::uuid $q$);
SELECT pg_temp.expect_fail_by('D9 a resolution rewritten', 'stays resolved', $q$
    UPDATE fulfillment.carrier_event SET resolution_note = 'later' WHERE id = md5('qa81:hook')::uuid $q$);

\echo '--- a carrier''s second webhook credential (item 6)'
SELECT pg_temp.expect_fail_by('E1 a second credential on a carrier with no webhook header', 'ck_carrier_webhook_auth_next', $q$
    UPDATE fulfillment.carrier SET webhook_auth_value_next_sealed = 'v1:AAAAAAAAAAAAAAAAAAAAAA=='
     WHERE id = md5('carrier:GHN')::uuid $q$);
SELECT pg_temp.expect_fail_by('E2 a second credential in clear', 'ck_carrier_webhook_auth_next', $q$
    UPDATE fulfillment.carrier SET webhook_auth_value_next_sealed = 'webhook-credential-next-0123456789'
     WHERE id = md5('qa81:aha')::uuid $q$);
SELECT pg_temp.expect_ok('E3 a second credential beside the first', $q$
    UPDATE fulfillment.carrier SET webhook_auth_value_next_sealed = 'v1:BBBBBBBBBBBBBBBBBBBBBB=='
     WHERE id = md5('qa81:aha')::uuid $q$);
SELECT pg_temp.expect_fail_by('E4 the second credential outliving the first', 'ck_carrier_', $q$
    UPDATE fulfillment.carrier SET webhook_auth_value_sealed = NULL WHERE id = md5('qa81:aha')::uuid $q$);
SELECT pg_temp.expect_ok('E5 the second credential promoted: it is the only one', $q$
    UPDATE fulfillment.carrier SET webhook_auth_value_sealed = webhook_auth_value_next_sealed,
           webhook_auth_value_next_sealed = NULL WHERE id = md5('qa81:aha')::uuid $q$);

\echo '--- who applies a file of outcomes'
SELECT pg_temp.expect_true('F1 the warehouse manager and the administrator, nobody else', (
    SELECT string_agg(r.code || ':' || p.action, ',' ORDER BY r.code, p.action)
      FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.resource = 'fulfillment-delivery-outcome-imports')
    = 'SYSTEM_ADMIN:CREATE,SYSTEM_ADMIN:READ,WAREHOUSE_MANAGER:CREATE,WAREHOUSE_MANAGER:READ');

ROLLBACK;
\echo 'PASS 81_carrier_outcomes'
