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
