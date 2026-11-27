-- =============================================================================
-- The Sales owner at drawing up, on quotes and sales contracts (sales-owner plan 2026-11-28; kltn-docs 14 BR-26,
-- §4.4 step 19). Module: order.
--
-- A quote and a contract record who looked after the customer when they were drawn up: the customer's owner at that
-- moment (customer.customer.sales_owner_id while ASSIGNED), null when the customer had none. It is what sales per
-- salesperson is counted by, so it never follows the customer when the customer is moved to another salesperson
-- (BR-S06: sales already made stay with the former owner). It is not the sender (sent_by stays, for the trail), and
-- neither column picks who is told anything: notices follow the customer's owner of today (18 BR-20).
--
-- Documents drawn up before 2026-11-28 are filled from their sender when that account holds SALES_STAFF (14 §4.4
-- step 19; owner decision D42: a Sales Manager or an admin coordinates and never owns a customer, so a document a
-- manager sent stays without one). Pinned by DocumentSalesOwnerMigrationUpgradeTest.
--
-- The column is written once: set when the document is drawn up (or by this backfill), never changed after -
-- tg_quote_sales_owner_fixed / tg_sales_contract_sales_owner_fixed (platform.forbid_update_of), as the entities map it
-- updatable = false.
-- =============================================================================

ALTER TABLE ordering.quote
    ADD COLUMN sales_owner_id UUID,
    ADD CONSTRAINT fk_quote_sales_owner FOREIGN KEY (sales_owner_id) REFERENCES identity.app_user (id);

ALTER TABLE ordering.sales_contract
    ADD COLUMN sales_owner_id UUID,
    ADD CONSTRAINT fk_sales_contract_sales_owner FOREIGN KEY (sales_owner_id) REFERENCES identity.app_user (id);

COMMENT ON COLUMN ordering.quote.sales_owner_id IS
    'The customer''s Sales owner when the quote was drawn up (14 BR-26): sales are counted by it; never changes.';
COMMENT ON COLUMN ordering.sales_contract.sales_owner_id IS
    'The customer''s Sales owner when the contract was drawn up (14 BR-26): sales are counted by it; never changes.';

-- Sales by salesperson (the managers' workload board, revenue of the last 30 days): contracts by their owner and when
-- they were signed.
CREATE INDEX ix_sales_contract_sales_owner ON ordering.sales_contract (sales_owner_id, signed_at)
    WHERE sales_owner_id IS NOT NULL;
CREATE INDEX ix_quote_sales_owner ON ordering.quote (sales_owner_id) WHERE sales_owner_id IS NOT NULL;

-- The contract's terms (14 BR-13, BR-23, 15 BR-12) are judged by the deferred tg_sales_contract_terms on every UPDATE
-- of the row. Writing the owner changes no term, and the backfill below writes it into every sent, signed, completed
-- and liquidated contract: a contract drawn up before a rule existed would fail it now and abort this migration, on a
-- database that is not rebuilt. The trigger is split so that an UPDATE changing nothing but sales_owner_id is not
-- judged again; every other INSERT or UPDATE still is, exactly as before. (A WHEN condition cannot name OLD on INSERT,
-- hence two triggers on the same function.) The quote has no trigger an UPDATE fires and its CHECKs all hold (none is
-- NOT VALID), so its backfill needs nothing of the kind; the contract's other triggers do not fire here either:
-- tg_contract_order_status is UPDATE OF status, and tg_sales_contract_transition compares named columns only.
DROP TRIGGER tg_sales_contract_terms ON ordering.sales_contract;
CREATE CONSTRAINT TRIGGER tg_sales_contract_terms AFTER INSERT ON ordering.sales_contract
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_terms();
CREATE CONSTRAINT TRIGGER tg_sales_contract_terms_update AFTER UPDATE ON ordering.sales_contract
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN ((to_jsonb(OLD) - 'sales_owner_id') IS DISTINCT FROM (to_jsonb(NEW) - 'sales_owner_id'))
    EXECUTE FUNCTION ordering.check_sales_contract_terms();

-- The backfill: the sender, when a salesperson sent it.
UPDATE ordering.quote q
   SET sales_owner_id = q.sent_by
 WHERE q.sales_owner_id IS NULL
   AND q.sent_by IS NOT NULL
   AND EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                WHERE ur.user_id = q.sent_by AND r.code = 'SALES_STAFF');

UPDATE ordering.sales_contract k
   SET sales_owner_id = k.sent_by
 WHERE k.sales_owner_id IS NULL
   AND k.sent_by IS NOT NULL
   AND EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                WHERE ur.user_id = k.sent_by AND r.code = 'SALES_STAFF');

-- The rows' deferred checks run now: a table with trigger events still pending takes no new trigger.
SET CONSTRAINTS ALL IMMEDIATE;

-- Written once, after the backfill: a value never changes (NULL to a value stays possible, as forbid_update_of says).
CREATE TRIGGER tg_quote_sales_owner_fixed BEFORE UPDATE OF sales_owner_id ON ordering.quote
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('sales_owner_id');
CREATE TRIGGER tg_sales_contract_sales_owner_fixed BEFORE UPDATE OF sales_owner_id ON ordering.sales_contract
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('sales_owner_id');
