-- =============================================================================
-- Adversarial checks + happy paths: the shortage choice card (V20270129000500; kltn-docs 17 §4.14, §5.6, BR-33; 16
-- BR-15) - every CHECK of ordering.shortage_card and ordering.shortage_card_line, the one live card per batch, the
-- deferred supersede link, and the new chat card kind. Needs the demo seed (demo.editor). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints fire here, still inside the sub-transaction.
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
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
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- A card as a statement: key, batch key, status and the columns that vary; the rest is a valid SENT card.
CREATE FUNCTION pg_temp.card(card_key TEXT, batch_key TEXT DEFAULT 'qa99:b1', status TEXT DEFAULT 'SENT',
                             extra_cols TEXT DEFAULT '', extra_vals TEXT DEFAULT '') RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO ordering.shortage_card (id, order_id, delivery_batch_id, customer_id, status, options_offered,
                                            missing_cartons, amounts, expected_delivery_date, expected_date_source,
                                            sent_by, sent_at %s)
        VALUES (md5(%L)::uuid, md5('qa99:o')::uuid, md5(%L)::uuid, 'c0000000-0000-4000-8000-000000000001', %L,
                ARRAY['WAIT_FOR_PRODUCTION', 'REDUCE_CARTONS', 'SHIP_PART_CLOSE_REST', 'CANCEL_ORDER'], 2,
                '[{"option":"WAIT_FOR_PRODUCTION"}]'::jsonb, CURRENT_DATE + 14, 'STANDARD_LEAD_TIME',
                md5('demo:user:editor')::uuid, NOW() %s)$f$, extra_cols, card_key, batch_key, status, extra_vals)
$$;

-- Fixtures, written with triggers and foreign keys off: an order, a contract's two batches and a line of the order.
SET session_replication_role = replica;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at)
VALUES (md5('qa99:o')::uuid, 'SO-QA99-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        1000, 'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa99:o:l1')::uuid, md5('qa99:o')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 8, 100, 'VND');
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa99:b1')::uuid, md5('qa99:contract')::uuid, 1, CURRENT_DATE + 3, CURRENT_DATE + 8),
       (md5('qa99:b2')::uuid, md5('qa99:contract')::uuid, 2, CURRENT_DATE + 20, CURRENT_DATE + 25);
SET session_replication_role = origin;

-- The happy card, and what the lifecycle needs.
SELECT pg_temp.expect_ok('SC01 a SENT card offering the four options with the wait date', pg_temp.card('qa99:c1'));
SELECT pg_temp.expect_ok('SC02 its line: 10 cartons in the batch, 8 available, 2 missing', $q$
    INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, sku_name, batch_cartons,
                                             available_cartons, missing_cartons, units_per_carton)
    VALUES (md5('qa99:c1:l1')::uuid, md5('qa99:c1')::uuid, md5('qa99:o:l1')::uuid, 'LY-GIAY-DEMO-12OZ-XANH',
            'Ly giấy 12oz xanh', 10, 8, 2, 1000) $q$);

-- One live card per batch (16 BR-15).
SELECT pg_temp.expect_fail('SC03 a second live card for the same batch', pg_temp.card('qa99:c2'));
SELECT pg_temp.expect_ok('SC04 a live card for another batch', pg_temp.card('qa99:c3', 'qa99:b2'));

-- Value checks.
SELECT pg_temp.expect_fail('SC05 an unknown status', pg_temp.card('qa99:x1', 'qa99:b2', 'SENDING'));
SELECT pg_temp.expect_fail('SC06 no option offered', $q$
    UPDATE ordering.shortage_card SET options_offered = ARRAY[]::VARCHAR[] WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC07 an unknown option', $q$
    UPDATE ordering.shortage_card SET options_offered = ARRAY['SWAP_WAREHOUSE']::VARCHAR[]
     WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC08 no cartons missing', $q$
    UPDATE ordering.shortage_card SET missing_cartons = 0 WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC09 figures that are not a list', $q$
    UPDATE ordering.shortage_card SET amounts = '{}'::jsonb WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC10 a blank note from Sales', $q$
    UPDATE ordering.shortage_card SET sales_note = '   ' WHERE id = md5('qa99:c3')::uuid $q$);

-- D48: the wait option carries its expected date and where it came from, and only it.
SELECT pg_temp.expect_fail('SC11 wait offered without its date', $q$
    UPDATE ordering.shortage_card SET expected_delivery_date = NULL, expected_date_source = NULL
     WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC12 a date without the wait option', $q$
    UPDATE ordering.shortage_card SET options_offered = ARRAY['REDUCE_CARTONS']::VARCHAR[]
     WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC13 a date with no source', $q$
    UPDATE ordering.shortage_card SET expected_date_source = NULL WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC14 an unknown date source', $q$
    UPDATE ordering.shortage_card SET expected_date_source = 'GUESS' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_ok('SC15 a card without the wait option has no date', $q$
    INSERT INTO ordering.shortage_card (id, order_id, delivery_batch_id, customer_id, status, options_offered,
                                        missing_cartons, amounts, ended_reason, sent_by, sent_at)
    VALUES (md5('qa99:c4')::uuid, md5('qa99:o')::uuid, md5('qa99:b2')::uuid, 'c0000000-0000-4000-8000-000000000001',
            'EXPIRED', ARRAY['REDUCE_CARTONS'], 1, '[]'::jsonb, 'REALLOCATED', md5('demo:user:editor')::uuid, NOW()) $q$);

-- The customer's choice: who, when, which of the offered options.
SELECT pg_temp.expect_fail('SC16 ANSWERED with no choice', $q$
    UPDATE ordering.shortage_card SET status = 'ANSWERED' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC17 a choice with no time', $q$
    UPDATE ordering.shortage_card SET status = 'ANSWERED', chosen_option = 'REDUCE_CARTONS',
           chosen_by = md5('demo:user:editor')::uuid WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC19 SENT with a choice', $q$
    UPDATE ordering.shortage_card SET chosen_option = 'REDUCE_CARTONS', chosen_by = md5('demo:user:editor')::uuid,
           chosen_at = NOW() WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC20 a customer note without a choice', $q$
    UPDATE ordering.shortage_card SET customer_note = 'Giao sau nhé' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_ok('SC21 the customer chooses', $q$
    UPDATE ordering.shortage_card SET status = 'ANSWERED', chosen_option = 'WAIT_FOR_PRODUCTION',
           chosen_by = md5('demo:user:editor')::uuid, chosen_at = NOW(), customer_note = 'Chờ hàng được'
     WHERE id = md5('qa99:c1')::uuid $q$);

-- Execution and ending.
SELECT pg_temp.expect_fail('SC22 EXECUTED with no executor', $q$
    UPDATE ordering.shortage_card SET status = 'EXECUTED' WHERE id = md5('qa99:c1')::uuid $q$);
SELECT pg_temp.expect_fail('SC23 an execution time on a card not EXECUTED', $q$
    UPDATE ordering.shortage_card SET executed_at = NOW(), executed_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa99:c1')::uuid $q$);
SELECT pg_temp.expect_fail('SC24 an execution started on a card nobody chose on', $q$
    UPDATE ordering.shortage_card SET execution_started_at = NOW() WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC25 EXPIRED with no reason', $q$
    UPDATE ordering.shortage_card SET status = 'EXPIRED' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC26 an unknown ending reason', $q$
    UPDATE ordering.shortage_card SET status = 'EXPIRED', ended_reason = 'BORED' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC27 an ending reason on a live card', $q$
    UPDATE ordering.shortage_card SET ended_reason = 'REALLOCATED' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC28 SUPERSEDED with no new card', $q$
    UPDATE ordering.shortage_card SET status = 'SUPERSEDED', ended_reason = 'REPLACED' WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC29 SUPERSEDED by itself', $q$
    UPDATE ordering.shortage_card SET status = 'SUPERSEDED', ended_reason = 'REPLACED',
           superseded_by = md5('qa99:c3')::uuid WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC30 SUPERSEDED for a reason that is not "replaced"', $q$
    UPDATE ordering.shortage_card SET status = 'SUPERSEDED', ended_reason = 'REALLOCATED',
           superseded_by = md5('qa99:c1')::uuid WHERE id = md5('qa99:c3')::uuid $q$);
SELECT pg_temp.expect_fail('SC31 a card superseded by one that does not exist', $q$
    UPDATE ordering.shortage_card SET status = 'SUPERSEDED', ended_reason = 'REPLACED',
           superseded_by = md5('qa99:nothing')::uuid WHERE id = md5('qa99:c3')::uuid $q$);

-- Replacing the live card of a batch: the old one ends first, the new one follows in the same transaction.
SELECT pg_temp.expect_ok('SC32 a live card replaced by a newer one in one transaction', $q$
    UPDATE ordering.shortage_card SET status = 'SUPERSEDED', ended_reason = 'REPLACED',
           superseded_by = md5('qa99:c5')::uuid WHERE id = md5('qa99:c3')::uuid;
    INSERT INTO ordering.shortage_card (id, order_id, delivery_batch_id, customer_id, status, options_offered,
                                        missing_cartons, amounts, sent_by, sent_at)
    VALUES (md5('qa99:c5')::uuid, md5('qa99:o')::uuid, md5('qa99:b2')::uuid, 'c0000000-0000-4000-8000-000000000001',
            'SENT', ARRAY['REDUCE_CARTONS'], 2, '[]'::jsonb, md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('SC32b a choice of an option the card did not offer (it offers only REDUCE_CARTONS)', $q$
    UPDATE ordering.shortage_card SET status = 'ANSWERED', chosen_option = 'CANCEL_ORDER',
           chosen_by = md5('demo:user:editor')::uuid, chosen_at = NOW() WHERE id = md5('qa99:c5')::uuid $q$);
SELECT pg_temp.expect_ok('SC33 a card answered keeps its choice when it expires', $q$
    UPDATE ordering.shortage_card SET status = 'EXPIRED', ended_reason = 'REALLOCATED' WHERE id = md5('qa99:c1')::uuid $q$);
SELECT pg_temp.expect_ok('SC34 once the live card ended, the batch takes a new one', pg_temp.card('qa99:c6'));
SELECT pg_temp.expect_ok('SC35 and it is executed by someone', $q$
    UPDATE ordering.shortage_card SET status = 'ANSWERED', chosen_option = 'CANCEL_ORDER',
           chosen_by = md5('demo:user:editor')::uuid, chosen_at = NOW() WHERE id = md5('qa99:c6')::uuid;
    UPDATE ordering.shortage_card SET status = 'EXECUTED', executed_by = md5('demo:user:editor')::uuid,
           executed_at = NOW() WHERE id = md5('qa99:c6')::uuid $q$);

-- Lines.
SELECT pg_temp.expect_fail('SC36 cartons that do not add up (7 + 2 <> 10)', $q$
    INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, batch_cartons, available_cartons,
                                             missing_cartons, units_per_carton)
    VALUES (md5('qa99:c5:l1')::uuid, md5('qa99:c5')::uuid, md5('qa99:o:l1')::uuid, 'X', 10, 7, 2, 1000) $q$);
SELECT pg_temp.expect_fail('SC37 a line with nothing missing', $q$
    INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, batch_cartons, available_cartons,
                                             missing_cartons, units_per_carton)
    VALUES (md5('qa99:c5:l1')::uuid, md5('qa99:c5')::uuid, md5('qa99:o:l1')::uuid, 'X', 10, 10, 0, 1000) $q$);
SELECT pg_temp.expect_fail('SC38 the same order line twice on a card', $q$
    INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, batch_cartons, available_cartons,
                                             missing_cartons, units_per_carton)
    VALUES (md5('qa99:c1:l2')::uuid, md5('qa99:c1')::uuid, md5('qa99:o:l1')::uuid, 'X', 10, 8, 2, 1000) $q$);
SELECT pg_temp.expect_fail('SC39 a line of a card that does not exist', $q$
    INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, batch_cartons, available_cartons,
                                             missing_cartons, units_per_carton)
    VALUES (md5('qa99:nothing:l1')::uuid, md5('qa99:nothing')::uuid, md5('qa99:o:l1')::uuid, 'X', 10, 8, 2, 1000) $q$);

-- The chat shows the card as a message of its own kind.
SELECT pg_temp.expect_true('SC40 chat.message accepts the SHORTAGE_CHOICE card kind', EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_message_card_kind'
       AND pg_get_constraintdef(oid) LIKE '%SHORTAGE_CHOICE%' AND pg_get_constraintdef(oid) LIKE '%SAMPLE_APPROVAL%'));
ROLLBACK;
