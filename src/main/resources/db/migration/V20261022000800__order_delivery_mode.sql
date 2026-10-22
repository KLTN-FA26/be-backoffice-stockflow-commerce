-- =============================================================================
-- LP-1 / LP-2 (lead's decisions 2026-10-22): how an order travels. Module: ordering (+ identity grants).
-- Docs: kltn-docs 09 §1 and §3 ("Hình thức giao: Gửi kiện qua hãng (mặc định) hoặc giao nguyên chuyến (Order
-- Coordinator chỉ định khi release)"), 17 §4.2 step 4, 15 BR-08. Design: SCRUM-287 load planning v2, PR A.
--
-- 1. customer_order.delivery_mode: PARCEL (parcels through a carrier, the default) or FULL_TRUCKLOAD (a whole truck or
--    container planned in 3D, 09 §4.2) - the shipment's names (ck_shipment_delivery_mode). Every existing order is
--    PARCEL, as every shipment made so far is. The Order Coordinator chooses it at release or since (LP-1); packing
--    copies it onto every shipment of the order.
-- 2. customer_order.packing_started_at: when fulfillment opened the order's first package, written in that
--    transaction under the order's row lock. The mode is locked from then on (LP-1), and on every order past PICKING.
--    Backfilled from the packages already opened.
-- 3. ck_order_ftl_no_cod (LP-2): a full truckload never has its balance collected on delivery - the order-side twin of
--    ck_shipment_ftl_no_cod (V20261021000400). Refused with 409 DELIVERY_MODE_FTL_NO_COD in both directions: choosing
--    FULL_TRUCKLOAD for an ON_DELIVERY order, and an ON_DELIVERY balance for a full-truckload order.
-- 4. ordering.order_delivery_mode_change: every change, from what to what, who, when, at what status, why.
--    Append-only; staff read it in the order's history (DELIVERY_MODE_CHANGE).
-- 5. Grant: sales-order-delivery-modes:UPDATE (PUT /orders/{id}/delivery-mode, and a mode other than the order's at
--    release) to ORDER_COORDINATOR - who releases (17 §2, 09 §3) - and SYSTEM_ADMIN. Not sales-orders:APPROVE alone:
--    the matrix screen (ADR-0008) can then give one without the other. Reading the mode comes with sales-orders:READ.
-- =============================================================================

-- ---- 1, 2, 3 -------------------------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order
    ADD COLUMN delivery_mode      VARCHAR(16) NOT NULL DEFAULT 'PARCEL',
    ADD COLUMN packing_started_at TIMESTAMPTZ;

UPDATE ordering.customer_order o
   SET packing_started_at = p.opened_at
  FROM (SELECT pk.order_id, MIN(pg.created_at) AS opened_at
          FROM fulfillment.package pg
          JOIN fulfillment.pack pa ON pa.id = pg.pack_id
          JOIN fulfillment.pick pk ON pk.id = pa.pick_id
         GROUP BY pk.order_id) p
 WHERE p.order_id = o.id;

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_delivery_mode CHECK (delivery_mode IN ('PARCEL', 'FULL_TRUCKLOAD')),
    ADD CONSTRAINT ck_order_ftl_no_cod CHECK (delivery_mode = 'PARCEL' OR balance_policy IS DISTINCT FROM 'ON_DELIVERY');

COMMENT ON COLUMN ordering.customer_order.delivery_mode IS
    'How the order travels (LP-1): PARCEL or FULL_TRUCKLOAD; chosen by the Order Coordinator until packing starts.';
COMMENT ON COLUMN ordering.customer_order.packing_started_at IS
    'When its first package was opened (LP-1): the delivery mode is locked from then on.';

-- ---- 4 -------------------------------------------------------------------------------------------------------------
CREATE TABLE ordering.order_delivery_mode_change
(
    id               UUID         NOT NULL,
    order_id         UUID         NOT NULL,
    delivery_mode    VARCHAR(16)  NOT NULL,
    previous_mode    VARCHAR(16)  NOT NULL,
    order_status     VARCHAR(32)  NOT NULL,
    note             VARCHAR(500),
    changed_by       UUID,
    changed_at       TIMESTAMPTZ  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_delivery_mode_change PRIMARY KEY (id),
    CONSTRAINT fk_order_delivery_mode_change_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_delivery_mode_change_actor FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_delivery_mode_change_modes CHECK (delivery_mode IN ('PARCEL', 'FULL_TRUCKLOAD')
        AND previous_mode IN ('PARCEL', 'FULL_TRUCKLOAD') AND delivery_mode <> previous_mode),
    CONSTRAINT ck_order_delivery_mode_change_status CHECK (order_status ~ '^[A-Z_]{1,32}$')
);

CREATE INDEX ix_order_delivery_mode_change_order
    ON ordering.order_delivery_mode_change (order_id, changed_at);

CREATE TRIGGER tg_order_delivery_mode_change_append_only
    BEFORE UPDATE OR DELETE ON ordering.order_delivery_mode_change
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

-- ---- 5 -------------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-delivery-modes:UPDATE', 'sales-order-delivery-modes', 'UPDATE', 0, NOW(),
        'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.code = 'sales-order-delivery-modes:UPDATE'
WHERE role.code IN ('ORDER_COORDINATOR', 'SYSTEM_ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: a role's cached permissions are re-read when its version moves.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ORDER_COORDINATOR', 'SYSTEM_ADMIN');
