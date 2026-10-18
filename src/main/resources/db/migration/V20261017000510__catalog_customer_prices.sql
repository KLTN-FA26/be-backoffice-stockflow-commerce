-- =============================================================================
-- Customer-specific prices by SKU and quantity tier (SCRUM-428).
-- Docs: kltn-docs 13 §2-3 (price list per customer and quantity tier; no own list -> customer-group
-- price), 13 BR-02/BR-05 (the logged-in customer's price; MOQ and tiers shown, nothing below MOQ),
-- 14 step 2 (a quote starts from the customer's list), 14 BR-03 (the price is locked into the order).
--
-- catalog.customer_price       one row per (customer, SKU): the customer's own price for that SKU.
-- catalog.customer_price_tier  its quantity tiers: from min_quantity units on, unit_price applies.
--   The first tier is that customer's minimum order quantity for the SKU; below it there is no price.
--   Prices are whole, positive VND like every other selling price in the catalog (ck_catalog_base_vnd).
--   No validity dates and no ordering rule between tiers: the documents give none (open questions P3 in
--   docs/handoffs/SPRINT2-PRICING-PLAN.md).
--
-- Resolution order for a customer, SKU and quantity: own tier -> active rule of the customer's segment
-- (catalog.pricing_rule.segment_id, already in the schema) -> base price (BASE:<sku>).
--
-- Permissions: catalog-customer-prices. E-commerce Admin configures price lists (kltn-docs 13 §2, 14 §2);
-- Sales and the Order Coordinator read them to quote and to place assisted orders. Who else may edit is
-- open question P1. SYSTEM_ADMIN holds every permission. Role versions advance with the grants.
-- =============================================================================

CREATE TABLE catalog.customer_price
(
    id               UUID         NOT NULL,
    customer_id      UUID         NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    currency         VARCHAR(3)   NOT NULL DEFAULT 'VND',
    note             VARCHAR(500),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_customer_price PRIMARY KEY (id),
    CONSTRAINT uk_customer_price UNIQUE (customer_id, sku),
    CONSTRAINT fk_customer_price_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_price_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_price_currency CHECK (currency = 'VND')
);

CREATE INDEX ix_customer_price_sku ON catalog.customer_price (sku);

CREATE TABLE catalog.customer_price_tier
(
    id                UUID           NOT NULL,
    customer_price_id UUID           NOT NULL,
    min_quantity      INTEGER        NOT NULL,
    unit_price        NUMERIC(18, 2) NOT NULL,

    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL,
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_customer_price_tier PRIMARY KEY (id),
    CONSTRAINT uk_customer_price_tier UNIQUE (customer_price_id, min_quantity),
    CONSTRAINT fk_customer_price_tier_price FOREIGN KEY (customer_price_id)
        REFERENCES catalog.customer_price (id) ON DELETE CASCADE,
    CONSTRAINT ck_customer_price_tier_quantity CHECK (min_quantity >= 1),
    CONSTRAINT ck_customer_price_tier_price CHECK (unit_price > 0 AND unit_price = trunc(unit_price))
);

COMMENT ON TABLE catalog.customer_price IS
    'A customer''s own price for one SKU (SCRUM-428); its tiers are in customer_price_tier.';
COMMENT ON TABLE catalog.customer_price_tier IS
    'From min_quantity units on, unit_price (whole VND) applies. The first tier is the customer''s MOQ.';

-- ---- permissions ------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'catalog-customer-prices:' || a.action, 'catalog-customer-prices', a.action, 0, NOW(), 'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE', 'DELETE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('ECOMMERCE_ADMIN',   'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('SALES_STAFF',       'VIEW_PAGE,READ'),
          ('ORDER_COORDINATOR', 'READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'catalog-customer-prices'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ECOMMERCE_ADMIN', 'SALES_STAFF', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
