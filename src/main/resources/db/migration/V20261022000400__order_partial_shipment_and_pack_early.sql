-- =============================================================================
-- PACK-PARTIAL and PACK-EARLY (lead's decisions 2026-10-21). Module: ordering (+ identity grants).
-- Docs: kltn-docs 17 §4.2 steps 5-6, §5, BR-06 ("cho phép fulfill từng phần ... trừ khi chính sách split-ship"),
-- 08 edge "partial ship / backorder theo chính sách", 09 BR-03 ("trừ khi chính sách cho phép giao lệch chuyến"),
-- open question D9 (split-shipment policy not settled in the docs: the lead's decisions rule here).
--
-- 1. customer_order.allow_partial_shipment: Sales decides on the order whether it may ship in parts - a packing
--    completion that leaves goods behind, a handover of part of it. Default FALSE: the order ships whole. Until now the
--    warehouse decided (a global switch, on): orders already shipped in parts - PARTIALLY_FULFILLED, held from there,
--    or with a partial shipment made - keep going as they started.
-- 2. customer_order.awaiting_production: a mixed order that allows partial shipments may ship its stock while its
--    print lines are printed (IN_PRODUCTION → PARTIALLY_FULFILLED); this says production still owes it, so its printed
--    goods are not at packing. Cleared when production completes or the print lines are closed. Only while
--    PARTIALLY_FULFILLED or held from there.
-- 3. Two order-status edges (OrderStatus.canTransitionTo, OrderLifecycleIntegrationTest compares):
--    IN_PRODUCTION → PARTIALLY_FULFILLED and READY_TO_FULFILL → PARTIALLY_FULFILLED (the stock packed while printing,
--    handed over before the prints).
-- 4. ordering.order_partial_shipment_decision: every decision, who, when, at what status, why. Append-only; staff read
--    it in the order's history.
-- 5. Grant: sales-order-partial-shipments:UPDATE (PUT /orders/{id}/partial-shipment) to SALES_STAFF - Sales decides
--    (17 §2) - and SYSTEM_ADMIN. Not sales-orders:UPDATE: the portal customer holds that one for their own orders.
-- =============================================================================

-- ---- 1, 2 ----------------------------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order
    ADD COLUMN allow_partial_shipment BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN awaiting_production    BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE ordering.customer_order o
   SET allow_partial_shipment = TRUE
 WHERE o.status = 'PARTIALLY_FULFILLED'
    OR (o.status = 'ON_HOLD' AND o.status_before_hold = 'PARTIALLY_FULFILLED')
    OR EXISTS (SELECT 1 FROM fulfillment.shipment s WHERE s.order_id = o.id AND s.partial);

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_awaiting_production CHECK (NOT awaiting_production
        OR (allow_partial_shipment AND status IN ('PARTIALLY_FULFILLED', 'ON_HOLD')));

COMMENT ON COLUMN ordering.customer_order.allow_partial_shipment IS
    'Sales allowed the order to ship in parts (PACK-PARTIAL); the warehouse ships it whole otherwise.';
COMMENT ON COLUMN ordering.customer_order.awaiting_production IS
    'Its stock shipped while it was printed (PACK-EARLY); production still owes its print lines.';

-- ---- 3 -------------------------------------------------------------------------------------------------------------
INSERT INTO ordering.order_status_transition (from_status, to_status) VALUES
    ('IN_PRODUCTION', 'PARTIALLY_FULFILLED'),
    ('READY_TO_FULFILL', 'PARTIALLY_FULFILLED')
ON CONFLICT DO NOTHING;

-- ---- 4 -------------------------------------------------------------------------------------------------------------
CREATE TABLE ordering.order_partial_shipment_decision
(
    id               UUID         NOT NULL,
    order_id         UUID         NOT NULL,
    allowed          BOOLEAN      NOT NULL,
    order_status     VARCHAR(32)  NOT NULL,
    note             VARCHAR(500),
    decided_by       UUID,
    decided_at       TIMESTAMPTZ  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_partial_shipment_decision PRIMARY KEY (id),
    CONSTRAINT fk_order_partial_shipment_decision_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_partial_shipment_decision_actor FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_partial_shipment_decision_status CHECK (order_status ~ '^[A-Z_]{1,32}$')
);

CREATE INDEX ix_order_partial_shipment_decision_order
    ON ordering.order_partial_shipment_decision (order_id, decided_at);

CREATE TRIGGER tg_order_partial_shipment_decision_append_only
    BEFORE UPDATE OR DELETE ON ordering.order_partial_shipment_decision
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

-- ---- 5 -------------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-partial-shipments:UPDATE', 'sales-order-partial-shipments', 'UPDATE', 0, NOW(),
        'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.code = 'sales-order-partial-shipments:UPDATE'
WHERE role.code IN ('SALES_STAFF', 'SYSTEM_ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('SALES_STAFF', 'SYSTEM_ADMIN');
