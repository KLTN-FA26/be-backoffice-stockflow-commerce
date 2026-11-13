-- WF-02 completion (quote -> order -> contract -> instalments), the schema half of the review of 2026-11-13.
--
-- 1. payment.payment_receipt.reference_normalized (15 BR-03): one bank statement line is one sum of money, however the
--    accountant typed its reference. "FT26111ABC", "ft26111abc" and "FT 26111 ABC" are the same line; the unique index
--    compared the raw text, so all three could be recorded and the money counted three times. The column is generated
--    from the reference (upper case, every whitespace character removed) - which also fills it for every existing row -
--    and the unique index among RECORDED receipts moves onto it. PaymentReceipt.normalize states the same rule in the
--    aggregate, so the service refuses the duplicate with PAYMENT_RECEIPT_REFERENCE_IN_USE before the index has to.
--    Live receipts that already collide under the rule stop the migration by name: which one is wrong is the
--    accountant's call (void it), not a migration's.
-- 2. ordering.order_status_transition loses DRAFT -> PENDING_PAYMENT / CONFIRMED / ON_HOLD (14 §5.3): the edges of the
--    removed checkout (4B-10). A draft order reaches those only through its signed contract, from PENDING_CONTRACT.
--    OrderStatus.canTransitionTo drops them in the same change; OrderLifecycleIntegrationTest#triggerRefusesJumps keeps
--    the two equal.
-- 3. design.design_project_quote_request gets OPEN / CLOSED (12 §4.11; the per-design request's B40 rule): one open
--    request per project. A customer who clicks "ask for a quote" twice gets the request already open, not a second one
--    and a second notice to the sales staff. The sales staff close a request they answered; the project then asks anew.
--    Existing rows: the newest of each project stays open, the older ones are closed now, by nobody (the system).
-- 4. Two partial indexes for the follow-up reminders (14 §6 "Khách đã ký nhưng chậm thanh toán"): contracts sent and
--    not signed, by when they were sent; orders waiting for the installment on signing, by when they were signed.

-- ---- 1. statement references ------------------------------------------------------------------------------------------

DO $$
DECLARE
    colliding TEXT;
BEGIN
    SELECT string_agg(normalized || ' (' || refs || ')', '; ') INTO colliding
      FROM (SELECT upper(regexp_replace(reference, '\s', '', 'g')) AS normalized,
                   string_agg(reference, ', ' ORDER BY recorded_at) AS refs
              FROM payment.payment_receipt
             WHERE status = 'RECORDED'
             GROUP BY 1
            HAVING count(*) > 1) AS twice;
    IF colliding IS NOT NULL THEN
        RAISE EXCEPTION 'Live receipts record the same statement line under different spellings: %. Void the wrong ones '
            'first (15 BR-03, BR-04)', colliding USING ERRCODE = 'unique_violation';
    END IF;
END
$$;

ALTER TABLE payment.payment_receipt
    ADD COLUMN reference_normalized character varying(100)
        GENERATED ALWAYS AS (upper(regexp_replace(reference, '\s', '', 'g'))) STORED;

COMMENT ON COLUMN payment.payment_receipt.reference_normalized IS
    'The statement reference upper-cased with every whitespace character removed (15 BR-03): what two receipts are '
    'compared by. Generated; PaymentReceipt.normalize is the same rule.';

DROP INDEX payment.uk_payment_receipt_reference_recorded;
CREATE UNIQUE INDEX uk_payment_receipt_reference_recorded ON payment.payment_receipt (reference_normalized)
    WHERE status = 'RECORDED';

-- ---- 2. the removed checkout's order edges ----------------------------------------------------------------------------

DELETE FROM ordering.order_status_transition
 WHERE from_status = 'DRAFT' AND to_status IN ('PENDING_PAYMENT', 'CONFIRMED', 'ON_HOLD');

-- ---- 3. one open quote request per design project ---------------------------------------------------------------------

ALTER TABLE design.design_project_quote_request
    ADD COLUMN status character varying(20) DEFAULT 'OPEN' NOT NULL,
    ADD COLUMN closed_at timestamp with time zone,
    ADD COLUMN closed_by uuid;

UPDATE design.design_project_quote_request r
   SET status = 'CLOSED', closed_at = NOW()
 WHERE EXISTS (SELECT 1 FROM design.design_project_quote_request newer
                WHERE newer.project_id = r.project_id
                  AND (newer.created_at, newer.id) > (r.created_at, r.id));

ALTER TABLE design.design_project_quote_request
    ADD CONSTRAINT ck_design_project_quote_request_status CHECK (status IN ('OPEN', 'CLOSED')),
    ADD CONSTRAINT ck_design_project_quote_request_closed
        CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL) AND (closed_by IS NULL OR status = 'CLOSED')),
    ADD CONSTRAINT fk_design_project_quote_request_closed_by FOREIGN KEY (closed_by) REFERENCES identity.app_user (id);

CREATE UNIQUE INDEX uk_design_project_quote_request_open ON design.design_project_quote_request (project_id)
    WHERE status = 'OPEN';

COMMENT ON COLUMN design.design_project_quote_request.status IS
    'OPEN until the sales staff close it as answered; one OPEN request per project (uk_design_project_quote_request_open)'
    ': asking again while it is open answers with it, or changes what it asks.';

-- ---- 4. follow-up reminders -------------------------------------------------------------------------------------------

CREATE INDEX ix_sales_contract_sent_unsigned ON ordering.sales_contract (sent_at) WHERE status = 'SENT';
CREATE INDEX ix_customer_order_awaiting_on_signing ON ordering.customer_order (contract_signed_at)
    WHERE status = 'PENDING_PAYMENT';
