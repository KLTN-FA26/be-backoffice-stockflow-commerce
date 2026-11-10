-- =============================================================================
-- What the money ledger removal leaves behind (V20261116000100, team plan 4B-11): tables, function, columns and permissions gone, what 4B-6 and 4B-9 use kept. Catalogue checks; needs the demo seed.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
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

SELECT pg_temp.expect_true('MD1 the ledger tables are gone', NOT EXISTS (SELECT 1 FROM information_schema.tables
    WHERE (table_schema, table_name) IN (('payment', 'payment'), ('payment', 'refund'), ('payment', 'receivable'),
        ('payment', 'customer_transfer'), ('payment', 'transfer_allocation'), ('payment', 'cod_remittance'),
        ('payment', 'cod_remittance_line'), ('ordering', 'order_payment'))));
SELECT pg_temp.expect_true('MD2 what 4B-6 and 4B-9 keep stays', (SELECT count(*) FROM information_schema.tables
    WHERE table_schema = 'payment' AND table_name IN ('installment', 'installment_reduction', 'order_settlement',
        'refund_record', 'payment_receipt', 'payment_receipt_line')) = 6);
SELECT pg_temp.expect_true('MD3 the refund-outcome function is gone',
    NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'credit_refund_outcome'));
SELECT pg_temp.expect_true('MD4 the per-order payment columns are gone', NOT EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'ordering' AND table_name = 'customer_order' AND column_name IN ('payment_term',
        'deposit_required', 'deposit_received_at', 'balance_policy', 'credit_term_days', 'paid_amount', 'paid_in_full_at',
        'billed_amount', 'receivable_paid_amount', 'remainder_refund_amount', 'remainder_fee_amount',
        'remainder_fee_retained_amount', 'remainder_fee_paid_amount')));
SELECT pg_temp.expect_true('MD5 the quote has no payment terms', NOT EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'ordering' AND table_name = 'quote' AND column_name IN ('payment_term', 'deposit_percent')));
SELECT pg_temp.expect_true('MD6 the shipment has no billed amount', NOT EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'ordering' AND table_name = 'order_shipment' AND column_name = 'billed_amount'));
SELECT pg_temp.expect_true('MD7 credit is a limit and its days: the old term columns are gone',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'customer' AND table_name = 'credit_profile'
        AND column_name IN ('allow_prepaid', 'allow_deposit', 'allow_credit', 'default_payment_term', 'deposit_percent',
                            'deposit_balance_policy'))
    AND (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'customer' AND table_name = 'credit_profile'
         AND column_name IN ('credit_limit', 'credit_term_days')) = 2);
SELECT pg_temp.expect_true('MD8 the six ledger permission resources are gone', NOT EXISTS (SELECT 1 FROM identity.permission
    WHERE resource IN ('payment-customer-transfers', 'payment-cod-remittances', 'payment-payments', 'payment-settlements',
                       'payment-receivables', 'payment-order-payments')));
SELECT pg_temp.expect_true('MD9 the permissions 4B-6 and 4B-9 use stay', (SELECT count(DISTINCT resource) FROM
    identity.permission WHERE resource IN ('payment-installments', 'payment-receipts', 'payment-refunds')) = 3);
SELECT pg_temp.expect_true('MD10 no grant without its permission', NOT EXISTS (SELECT 1 FROM identity.role_permission rp
    LEFT JOIN identity.permission p ON p.id = rp.permission_id WHERE p.id IS NULL));
SELECT pg_temp.expect_true('MD11 no publication of an event or a listener that went with the ledger',
    NOT EXISTS (SELECT 1 FROM event_publication WHERE event_type IN ('com.stockflow.contracts.PaymentCaptured',
        'com.stockflow.contracts.PaymentReversed')
        OR listener_id LIKE 'com.stockflow.payment.internal.service.MoneyLedgerService.%'
        OR listener_id LIKE 'com.stockflow.payment.internal.service.ReceivableService.%'));

ROLLBACK;
\echo 'PASS 27_money_ledger_removal_objects'
