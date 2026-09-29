-- =============================================================================
-- Foreign keys across module schemas, between tables the code already writes (ADR-0007).
-- Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase X
--
-- ADR-0005 kept every cross-module reference a plain UUID and left integrity to the application.
-- ADR-0007 reverses that for this one-database deployment: a dangling order_id or customer_id is
-- now refused by the database. Only references whose BOTH ends are live tables are added here;
-- references into tables that are still empty (product.variants, warehouse.storage_location,
-- inventory.inventory_items, procurement.goods_receipts) wait for their contract step, or the
-- running flows would break on the first write.
--
-- Two of them are DEFERRABLE INITIALLY DEFERRED, and must stay so:
--   OrderServiceImpl.placeOrder calls inventory.reserve() - which writes stock_reservation rows
--   carrying the new order's id - BEFORE repository.save(order) writes customer_order, all in one
--   transaction. An immediate foreign key would fail every checkout. Deferred, it is checked at
--   commit, when the order row exists; if the order never gets saved, the whole checkout rolls
--   back, which is exactly the atomicity ADR-0005 bought.
--
-- Existing rows. A developer database may hold test rows that point at nothing (a design draft
-- for a made-up customer id, found on a real local database while testing this migration). So
-- every key is added NOT VALID - enforced at once for every new or changed row - and then
-- validated against the existing rows by the block at the end. A key whose old rows do not pass
-- stays NOT VALID with a WARNING naming it, instead of stopping the application from booting;
-- tools/db/orphan_check.sql lists those rows, and db/pending/C0__validate_foreign_keys.sql
-- validates what is left once they are cleaned.
-- =============================================================================

-- ----------------------------------------------------------------------------- order <-> stock
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT fk_stock_reservation_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED NOT VALID;

-- Deferred as well: which of the two rows Hibernate flushes first is not something to depend on.
ALTER TABLE ordering.order_line_reservation
    ADD CONSTRAINT fk_order_line_reservation_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED NOT VALID;

-- ----------------------------------------------------------------------------- ordering
-- Nullable: a guest order has no customer row (ck_order_customer_or_guest).
ALTER TABLE ordering.customer_order
    ADD CONSTRAINT fk_customer_order_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE ordering.order_line
    ADD CONSTRAINT fk_order_line_design_snapshot FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE ordering.order_hold
    ADD CONSTRAINT fk_order_hold_resolved_by FOREIGN KEY (resolved_by)
        REFERENCES identity.app_user (id) NOT VALID;

-- ----------------------------------------------------------------------------- payment
ALTER TABLE payment.payment
    ADD CONSTRAINT fk_payment_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT NOT VALID;

-- ----------------------------------------------------------------------------- fulfillment
ALTER TABLE fulfillment.pick
    ADD CONSTRAINT fk_pick_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_pick_assigned_user FOREIGN KEY (assigned_user_id)
        REFERENCES identity.app_user (id) NOT VALID;
ALTER TABLE fulfillment.shipment
    ADD CONSTRAINT fk_shipment_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE fulfillment.design_verification
    ADD CONSTRAINT fk_design_verification_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_design_verification_order_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_design_verification_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_design_verification_verified_by FOREIGN KEY (verified_by)
        REFERENCES identity.app_user (id) NOT VALID;

-- ----------------------------------------------------------------------------- chat
ALTER TABLE chat.conversation
    ADD CONSTRAINT fk_conversation_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_conversation_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_conversation_agent FOREIGN KEY (assigned_agent_id)
        REFERENCES identity.app_user (id) NOT VALID;

-- ----------------------------------------------------------------------------- design
ALTER TABLE design.design_draft
    ADD CONSTRAINT fk_design_draft_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_design_draft_owner FOREIGN KEY (owner_user_id)
        REFERENCES identity.app_user (id) NOT VALID,
    ADD CONSTRAINT fk_design_draft_assigned FOREIGN KEY (assigned_user_id)
        REFERENCES identity.app_user (id) NOT VALID,
    ADD CONSTRAINT fk_design_draft_reviewer FOREIGN KEY (reviewer_user_id)
        REFERENCES identity.app_user (id) NOT VALID,
    ADD CONSTRAINT fk_design_draft_last_editor FOREIGN KEY (last_editor_user_id)
        REFERENCES identity.app_user (id) NOT VALID,
    ADD CONSTRAINT fk_design_draft_reviewed_by FOREIGN KEY (reviewed_by)
        REFERENCES identity.app_user (id) NOT VALID;
ALTER TABLE design.design_snapshot
    ADD CONSTRAINT fk_design_snapshot_confirmed_by FOREIGN KEY (confirmed_by)
        REFERENCES identity.app_user (id) NOT VALID,
    ADD CONSTRAINT fk_design_snapshot_reviewed_by FOREIGN KEY (reviewed_by)
        REFERENCES identity.app_user (id) NOT VALID;
ALTER TABLE design.design_decision
    ADD CONSTRAINT fk_design_decision_actor FOREIGN KEY (actor_id)
        REFERENCES identity.app_user (id) NOT VALID;

-- ----------------------------------------------------------------------------- customer / catalog
ALTER TABLE customer.customer
    ADD CONSTRAINT fk_customer_user FOREIGN KEY (user_id)
        REFERENCES identity.app_user (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE catalog.pricing_rule
    ADD CONSTRAINT fk_pricing_rule_segment FOREIGN KEY (segment_id)
        REFERENCES customer.segment (id) ON DELETE RESTRICT NOT VALID;

-- ----------------------------------------------------------------------------- notification
ALTER TABLE notification.preference
    ADD CONSTRAINT fk_preference_user FOREIGN KEY (user_id)
        REFERENCES identity.app_user (id) ON DELETE CASCADE NOT VALID;


-- Validate each key against the rows that already exist; leave it NOT VALID, loudly, if they fail.
DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
               WHERE contype = 'f' AND NOT convalidated AND conname = ANY (ARRAY[
                   'fk_stock_reservation_order',
                   'fk_order_line_reservation_reservation',
                   'fk_customer_order_customer',
                   'fk_order_line_design_snapshot',
                   'fk_order_hold_resolved_by',
                   'fk_payment_order',
                   'fk_pick_order',
                   'fk_pick_assigned_user',
                   'fk_shipment_order',
                   'fk_design_verification_order',
                   'fk_design_verification_order_line',
                   'fk_design_verification_snapshot',
                   'fk_design_verification_verified_by',
                   'fk_conversation_customer',
                   'fk_conversation_order',
                   'fk_conversation_agent',
                   'fk_design_draft_customer',
                   'fk_design_draft_owner',
                   'fk_design_draft_assigned',
                   'fk_design_draft_reviewer',
                   'fk_design_draft_last_editor',
                   'fk_design_draft_reviewed_by',
                   'fk_design_snapshot_confirmed_by',
                   'fk_design_snapshot_reviewed_by',
                   'fk_design_decision_actor',
                   'fk_customer_user',
                   'fk_pricing_rule_segment',
                   'fk_preference_user']) LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', fk.tbl, fk.conname);
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE WARNING 'V20260928004000: existing rows of % violate %, which stays NOT VALID (new rows are checked). List them with tools/db/orphan_check.sql, clean them, then apply db/pending/C0__validate_foreign_keys.sql.',
                fk.tbl, fk.conname;
        END;
    END LOOP;
END $$;


-- Foreign keys are not indexed automatically; every one above is looked up from its child side.
CREATE INDEX IF NOT EXISTS ix_stock_reservation_order ON inventory.stock_reservation (order_id);
CREATE INDEX IF NOT EXISTS ix_order_line_reservation_reservation ON ordering.order_line_reservation (reservation_id);
CREATE INDEX IF NOT EXISTS ix_customer_order_customer ON ordering.customer_order (customer_id);
CREATE INDEX IF NOT EXISTS ix_order_line_design_snapshot ON ordering.order_line (design_snapshot_id);
CREATE INDEX IF NOT EXISTS ix_payment_order ON payment.payment (order_id);
CREATE INDEX IF NOT EXISTS ix_pick_order ON fulfillment.pick (order_id);
CREATE INDEX IF NOT EXISTS ix_shipment_order ON fulfillment.shipment (order_id);
CREATE INDEX IF NOT EXISTS ix_design_verification_order_line ON fulfillment.design_verification (order_line_id);
CREATE INDEX IF NOT EXISTS ix_conversation_customer ON chat.conversation (customer_id);
CREATE INDEX IF NOT EXISTS ix_conversation_order ON chat.conversation (order_id);
CREATE INDEX IF NOT EXISTS ix_design_draft_customer ON design.design_draft (customer_id);
