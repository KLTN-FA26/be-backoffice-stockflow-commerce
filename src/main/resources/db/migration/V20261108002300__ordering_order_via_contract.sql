-- =============================================================================
-- An order lives through its sales contract (kltn-docs 14 §4.2, §4.3 steps 13-16, §5.3, BR-15; 15 BR-07, BR-11;
-- 17 §5.1, §4.8; team plan 4B-5).
--
--   * PENDING_CONTRACT: the contract was sent and waits to be signed. DRAFT -> PENDING_CONTRACT when it is sent;
--     back to DRAFT when it is cancelled to be drawn up again; PENDING_PAYMENT / CONFIRMED / ON_HOLD (credit) when it
--     is signed; CANCELLED. An accepted quote now becomes a DRAFT order - no stock held, no terms, no credit check -
--     and its contract takes it on from there. Checkout keeps its DRAFT -> PENDING_PAYMENT / CONFIRMED / ON_HOLD
--     edges until it is removed (4B-10).
--   * ordering.check_order_contract(): the order's status and its live contract's agree, at commit - an order
--     PENDING_CONTRACT has a SENT contract and a SENT contract's order is PENDING_CONTRACT; an order with a signed
--     contract is past DRAFT / PENDING_CONTRACT. The application moves both in one transaction.
--
--   * ordering.customer_order.contract_signed_at: when the order's contract was signed. A signed contract is what
--     confirms an order now (14 BR-15), not a payment its own payment term asks for: ck_order_confirmed_basis takes
--     it as a basis. The installment on signing, when there is one, keeps the order PENDING_PAYMENT until it is paid
--     - the application's to decide until the installments exist (4B-6).
--
-- Nothing is back-filled: no order is PENDING_CONTRACT yet, and no contract was ever sent.
-- =============================================================================

ALTER TABLE ordering.customer_order
    ADD COLUMN contract_signed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_order_contract_signed CHECK (contract_signed_at IS NULL
        OR status NOT IN ('DRAFT', 'PENDING_CONTRACT')),
    DROP CONSTRAINT ck_order_confirmed_basis,
    -- 15 BR-02, 14 BR-15: confirmed only on the money its terms require, or on its signed contract.
    ADD CONSTRAINT ck_order_confirmed_basis CHECK (status <> 'CONFIRMED'
        OR contract_signed_at IS NOT NULL
        OR (payment_term = 'PREPAID' AND paid_in_full_at IS NOT NULL)
        OR (payment_term = 'DEPOSIT' AND deposit_received_at IS NOT NULL)
        OR payment_term = 'CREDIT');

COMMENT ON COLUMN ordering.customer_order.contract_signed_at IS
    '4B-5 (14 §4.3 step 15, BR-15): when the order''s sales contract was signed; null for an order placed at checkout.';

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_status;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
    ('DRAFT', 'PENDING_CONTRACT', 'PENDING_PAYMENT', 'CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'PICKING',
     'PARTIALLY_FULFILLED', 'PACKED', 'ON_HOLD', 'SHIPPED', 'DELIVERY_FAILED', 'DELIVERED', 'RETURN_REQUESTED',
     'RETURNED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'COMPLETED', 'CANCELLED', 'CLOSED'));

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('DRAFT', 'PENDING_CONTRACT'),
       ('PENDING_CONTRACT', 'DRAFT'),
       ('PENDING_CONTRACT', 'PENDING_PAYMENT'),
       ('PENDING_CONTRACT', 'CONFIRMED'),
       ('PENDING_CONTRACT', 'ON_HOLD'),
       ('PENDING_CONTRACT', 'CANCELLED')
ON CONFLICT DO NOTHING;

-- The order's status and its live contract's agree (14 §5.2, §5.3), checked when the transaction commits.
CREATE FUNCTION ordering.check_order_contract()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    the_order    UUID;
    order_status VARCHAR(32);
    order_number VARCHAR(32);
    contract     VARCHAR(16);
BEGIN
    -- IF, not CASE: PL/pgSQL resolves every field a CASE names on NEW, and each table has only one of the two.
    IF TG_TABLE_NAME = 'customer_order' THEN
        the_order := NEW.id;
    ELSE
        the_order := NEW.order_id;
    END IF;
    SELECT o.status, o.order_number INTO order_status, order_number
      FROM ordering.customer_order o WHERE o.id = the_order;
    SELECT c.status INTO contract
      FROM ordering.sales_contract c WHERE c.order_id = the_order AND c.status <> 'CANCELLED';
    IF contract IN ('SIGNED', 'COMPLETED') AND order_status IN ('DRAFT', 'PENDING_CONTRACT') THEN
        RAISE EXCEPTION 'Order % has a signed contract, but is still % (14 §4.3 step 15)', order_number,
            order_status USING ERRCODE = 'check_violation';
    END IF;
    IF order_status = 'PENDING_CONTRACT' AND contract IS DISTINCT FROM 'SENT' THEN
        RAISE EXCEPTION 'Order % waits for its contract to be signed, but has no contract sent (14 §5.3)',
            order_number USING ERRCODE = 'check_violation';
    END IF;
    IF contract = 'SENT' AND order_status <> 'PENDING_CONTRACT' THEN
        RAISE EXCEPTION 'Order % has a contract sent for signing, but is % (14 §5.3)', order_number, order_status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_order_contract_status
    AFTER UPDATE OF status ON ordering.customer_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_order_contract();

CREATE CONSTRAINT TRIGGER tg_contract_order_status
    AFTER INSERT OR UPDATE OF status ON ordering.sales_contract
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_order_contract();
