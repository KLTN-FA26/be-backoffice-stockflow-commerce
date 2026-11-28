-- =============================================================================
-- Operational work done for another salesperson's customer: "làm hộ" (kltn-docs 17 BR-32; 14 §4.4 step 18; 15 BR-22;
-- sales-owner plan BR-S13). Module: order.
--
-- Strict mode blocks quotes, contracts and chat only. The work after signing - choosing the shipping warehouse, holding
-- an order and lifting the hold, holding its completion, deciding a cancellation request, cancelling it from the back
-- office, closing the rest, allowing partial shipment, cancelling what a failed delivery left, changing the delivery
-- address - stays open to everyone who holds the permission, so the work does not stop while the owner is away. When
-- the person doing it is neither the customer's owner nor the owner's cover of the day, the order's history records
-- it here and the one who looks after the customer is told in-app (00 §6).
--
--   acting_for_user_id   whom it was done for, and who was told: the owner, or the cover in force; null when the
--                        customer had no active owner - nobody to tell, the row still says who did it (17 BR-32).
--
-- Append-only (rows leave only with their order, platform.append_only). Listed by GET /orders/{id}/history as
-- ON_BEHALF. Nobody acts on their own behalf: ck_order_on_behalf_action_other.
-- =============================================================================

CREATE TABLE ordering.order_on_behalf_action (
    id                 UUID          NOT NULL,
    order_id           UUID          NOT NULL,
    action             VARCHAR(40)   NOT NULL,
    actor_id           UUID          NOT NULL,
    acting_for_user_id UUID,
    acted_at           TIMESTAMPTZ   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_order_on_behalf_action PRIMARY KEY (id),
    CONSTRAINT fk_order_on_behalf_action_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_order_on_behalf_action_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_order_on_behalf_action_for FOREIGN KEY (acting_for_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_on_behalf_action_action CHECK (action IN (
        'CHOOSE_SHIPPING_WAREHOUSE', 'HOLD', 'RESOLVE_HOLD', 'HOLD_COMPLETION', 'RELEASE_COMPLETION_HOLD',
        'APPROVE_CANCELLATION_REQUEST', 'REJECT_CANCELLATION_REQUEST', 'CANCEL_ORDER', 'CLOSE_REMAINDER',
        'DECIDE_PARTIAL_SHIPMENT', 'CANCEL_UNDELIVERED', 'CHANGE_SHIPPING_ADDRESS')),
    CONSTRAINT ck_order_on_behalf_action_other CHECK (acting_for_user_id IS NULL OR acting_for_user_id <> actor_id)
);

CREATE INDEX ix_order_on_behalf_action_order ON ordering.order_on_behalf_action (order_id, acted_at);

CREATE TRIGGER tg_order_on_behalf_action_append_only BEFORE DELETE OR UPDATE ON ordering.order_on_behalf_action
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

COMMENT ON TABLE ordering.order_on_behalf_action IS
    'Operational work on an order done by someone other than the customer''s owner or their cover (17 BR-32, '
    '"làm hộ"); append-only, listed in the order''s history as ON_BEHALF.';
