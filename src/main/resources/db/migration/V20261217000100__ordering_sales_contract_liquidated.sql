-- =============================================================================
-- WF-05 BE-06, decision Q-6 (kltn-docs 14 §5.2): a signed contract whose order ended with nothing delivered - every
-- shipment brought back by the carrier, lost, or the order cancelled after signing - ends LIQUIDATED ("Thanh lý") once
-- its money is settled, never COMPLETED. COMPLETED means goods were delivered (L-I4).
--
-- Stated twice: SalesContract.liquidate / complete in the aggregate, and here:
--   * ck_sales_contract_status gains LIQUIDATED; ck_sales_contract_liquidated binds liquidated_at to it;
--   * check_sales_contract_transition gains SIGNED -> LIQUIDATED, keeps a LIQUIDATED contract as it is, and refuses
--     SIGNED -> COMPLETED for an order nothing of which was delivered, and SIGNED -> LIQUIDATED for one that delivered.
--     "Delivered" is a DELIVERED shipment of the order, or - an order delivered whole before shipments were recorded
--     (G4-Q5, historical) - a delivered_at on an order with no shipment.
--
-- No data change (Q-13 = a: every environment is rebuilt; no seeded contract is in either situation).
-- =============================================================================

ALTER TABLE ordering.sales_contract ADD COLUMN liquidated_at TIMESTAMPTZ;

ALTER TABLE ordering.sales_contract DROP CONSTRAINT ck_sales_contract_status;
ALTER TABLE ordering.sales_contract ADD CONSTRAINT ck_sales_contract_status CHECK (
    status IN ('DRAFT', 'SENT', 'SIGNED', 'COMPLETED', 'CANCELLED', 'LIQUIDATED'));
ALTER TABLE ordering.sales_contract ADD CONSTRAINT ck_sales_contract_liquidated CHECK (
    (status = 'LIQUIDATED') = (liquidated_at IS NOT NULL));

-- A liquidated contract was sent and signed, as a completed one was: it keeps both.
ALTER TABLE ordering.sales_contract DROP CONSTRAINT ck_sales_contract_signed;
ALTER TABLE ordering.sales_contract ADD CONSTRAINT ck_sales_contract_signed CHECK (
    (status IN ('SIGNED', 'COMPLETED', 'LIQUIDATED')) = (signed_at IS NOT NULL)
    AND (signed_at IS NULL) = (signed_by IS NULL)
    AND (signed_at IS NULL) = (signed_on IS NULL)
    AND (signed_at IS NULL) = (signed_file_key IS NULL)
    AND (signed_at IS NULL) = (signed_file_name IS NULL)
    AND (signed_on IS NULL OR signed_on <= (signed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date));
ALTER TABLE ordering.sales_contract DROP CONSTRAINT ck_sales_contract_sent;
ALTER TABLE ordering.sales_contract ADD CONSTRAINT ck_sales_contract_sent CHECK (
    (sent_at IS NULL) = (sent_by IS NULL)
    AND (sent_at IS NULL) = (sent_document_key IS NULL)
    AND (status <> 'DRAFT' OR sent_at IS NULL)
    AND (status NOT IN ('SENT', 'SIGNED', 'COMPLETED', 'LIQUIDATED') OR sent_at IS NOT NULL));

COMMENT ON COLUMN ordering.sales_contract.liquidated_at IS
    'WF-05 Q-6: when the contract was liquidated - its order ended with nothing delivered and its money settled.';

CREATE FUNCTION ordering.order_delivered_something(p_order_id UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS
$$
SELECT EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = p_order_id AND s.status = 'DELIVERED')
    OR (NOT EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = p_order_id)
        AND EXISTS (SELECT 1 FROM ordering.customer_order o WHERE o.id = p_order_id AND o.delivered_at IS NOT NULL))
$$;

CREATE OR REPLACE FUNCTION ordering.check_sales_contract_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.contract_number IS DISTINCT FROM OLD.contract_number THEN
        RAISE EXCEPTION 'sales contract %: a contract keeps its number and stays with its order', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.seller_name, NEW.seller_tax_code, NEW.seller_address, NEW.seller_representative,
            NEW.seller_representative_title, NEW.buyer_name, NEW.buyer_tax_code, NEW.buyer_address,
            NEW.buyer_representative, NEW.buyer_representative_title, NEW.free_sample_rounds, NEW.sent_at,
            NEW.sent_by, NEW.sent_document_key, NEW.return_window_days)
        IS DISTINCT FROM (OLD.seller_name, OLD.seller_tax_code, OLD.seller_address, OLD.seller_representative,
            OLD.seller_representative_title, OLD.buyer_name, OLD.buyer_tax_code, OLD.buyer_address,
            OLD.buyer_representative, OLD.buyer_representative_title, OLD.free_sample_rounds, OLD.sent_at,
            OLD.sent_by, OLD.sent_document_key, OLD.return_window_days)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('SIGNED', 'COMPLETED', 'LIQUIDATED') AND (NEW.signed_on, NEW.signed_file_key,
            NEW.signed_file_name, NEW.signed_at, NEW.signed_by)
        IS DISTINCT FROM (OLD.signed_on, OLD.signed_file_key, OLD.signed_file_name, OLD.signed_at, OLD.signed_by)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('CANCELLED', 'COMPLETED', 'LIQUIDATED') AND (NEW.cancelled_at, NEW.cancelled_by,
            NEW.cancellation_reason, NEW.completed_at, NEW.liquidated_at)
        IS DISTINCT FROM (OLD.cancelled_at, OLD.cancelled_by, OLD.cancellation_reason, OLD.completed_at,
            OLD.liquidated_at)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change', OLD.contract_number, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
         OR (OLD.status = 'SENT' AND NEW.status IN ('SIGNED', 'CANCELLED'))
         OR (OLD.status = 'SIGNED' AND NEW.status IN ('COMPLETED', 'LIQUIDATED'))) THEN
        RAISE EXCEPTION 'sales contract %: % -> % is not a transition (14 §5.2)', OLD.contract_number, OLD.status,
            NEW.status USING ERRCODE = 'check_violation';
    END IF;
    -- L-I4: COMPLETED says goods were delivered; LIQUIDATED that none were.
    IF NEW.status = 'COMPLETED' AND NOT ordering.order_delivered_something(NEW.order_id) THEN
        RAISE EXCEPTION 'sales contract %: its order delivered nothing - it is liquidated, not completed (Q-6)',
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'LIQUIDATED' AND ordering.order_delivered_something(NEW.order_id) THEN
        RAISE EXCEPTION 'sales contract %: its order delivered goods - it is completed, not liquidated (Q-6)',
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
