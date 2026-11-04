-- C4 plan P0 (docs/business-design/db-design/2026-10-02-procurement-c4-plan.md §2.7): the new
-- procurement tables gain the columns the supplier-communication feature (PR #36) stores on the
-- old ones, so suppliers and purchase orders can move onto the new tables with that feature intact.
-- Expand only: nothing old is touched here; the copy and the contract follow in later migrations.
--
-- Deliberately NOT added:
--   * purchase_orders.sent_at - "CONFIRMED = locked and sent" (plan Q1): confirmed_at is the send
--     time.
--   * a CHECK tying supplier_confirmation_status to the PO status - the supplier's answer is
--     recorded beside the status, it does not move it (plan Q1).
--   * suppliers.email / contact_name - the primary contact lives in supplier_contacts (P3). So
--     "an EMAIL-channel supplier has an address" spans two tables and is the service's check,
--     not a CHECK here.

-- ---------------------------------------------------------------------------------------------
-- Suppliers: how a purchase order reaches them, and the payment term as a number of days.
-- payment_terms (free text, "Net 30") stays: it is what is printed; payment_term_days is what the
-- due-date arithmetic and the PO snapshot below use.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE procurement.suppliers
    ADD COLUMN payment_term_days     INTEGER      NOT NULL DEFAULT 30,
    ADD COLUMN communication_channel VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN api_endpoint          VARCHAR(500),
    ADD CONSTRAINT ck_suppliers_payment_term_days CHECK (payment_term_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_suppliers_channel CHECK (communication_channel IN ('EMAIL', 'API')),
    -- An API supplier needs an HTTPS endpoint; an EMAIL supplier may keep a stale one harmlessly.
    ADD CONSTRAINT ck_suppliers_api_endpoint CHECK (
        communication_channel <> 'API'
        OR (api_endpoint IS NOT NULL AND api_endpoint ~ '^https://[^/\s]+'));

-- ---------------------------------------------------------------------------------------------
-- Purchase orders: the commercial terms as they were when the order was raised, and the
-- supplier's answer to it.
--
-- The terms are a snapshot, not a join: a supplier renegotiating to 45 days must not move the due
-- date of an order already placed at 30. Existing (demo) rows take the same defaults #36 used.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE procurement.purchase_orders
    ADD COLUMN payment_term_days            INTEGER       NOT NULL DEFAULT 30,
    ADD COLUMN lead_time_days               INTEGER       NOT NULL DEFAULT 7,
    ADD COLUMN supplier_confirmation_status VARCHAR(16)   NOT NULL DEFAULT 'NOT_SENT',
    ADD COLUMN supplier_responded_at        TIMESTAMPTZ,
    ADD COLUMN supplier_reference           VARCHAR(100),
    ADD COLUMN supplier_response_note       VARCHAR(1000),
    ADD CONSTRAINT ck_purchase_orders_payment_term_days CHECK (payment_term_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_purchase_orders_lead_time_days CHECK (lead_time_days BETWEEN 0 AND 365),
    ADD CONSTRAINT ck_purchase_orders_supplier_confirmation CHECK (
        supplier_confirmation_status IN ('NOT_SENT', 'PENDING', 'CONFIRMED', 'REJECTED')),
    -- An answer has a time; same rule as #36's ck_po_response_evidence, minus the sent_at half.
    ADD CONSTRAINT ck_purchase_orders_response_evidence CHECK (
        supplier_confirmation_status NOT IN ('CONFIRMED', 'REJECTED')
        OR supplier_responded_at IS NOT NULL),
    -- A refusal says why (#36's ck_po_rejection_reason).
    ADD CONSTRAINT ck_purchase_orders_rejection_reason CHECK (
        supplier_confirmation_status <> 'REJECTED'
        OR NULLIF(btrim(supplier_response_note), '') IS NOT NULL);
