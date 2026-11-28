-- =============================================================================
-- What the endpoints of the sales-owner plan need beyond the foundation (V20270128000100..000145; API contract
-- "Additions from back office BO-1", owner decision D41, plan §10.5). Module: customer.
--
--   customer.sales_owner_transfer_item.skip_reason  gains REASSIGNED_SINCE, the contract's name for a customer of a
--       temporary batch that someone moved on again before the return (§10.1). MOVED_ON and TARGET_NOT_ASSIGNABLE stay
--       allowed (no row uses them; a CHECK only ever widens here).
--   customer.sales_owner_transfer_batch.not_found_customer_ids  the ids a bulk transfer named that are no customer: they
--       cannot be items (the item's foreign key), and a retried request must answer them again (idempotency, BR-S09).
--   customer.customer_tax_code_exemption  BR-S14 / D4: a new business customer is created with its tax code, unless a
--       Sales Manager allows it without one and says why. One row per exempted customer: who allowed it, when, why.
--       That "a new customer has a tax code or an exemption" is the service's and the aggregate's rule only: customers
--       made before it have neither and keep it so (D41, the "tax code missing" flag), so no CHECK can say it.
--   customer.customer_sales_contact  plan §10.5 step 3: the warning that the salesperson a customer asks for has been in
--       touch with them lately. Fed by customer's own listeners from the events order publishes when a quote or a
--       contract is sent (customer may not depend on order: order depends on customer). One row per customer and
--       salesperson: the last time.
-- =============================================================================

ALTER TABLE customer.sales_owner_transfer_item
    DROP CONSTRAINT ck_sales_owner_transfer_item_skip_reason,
    ADD CONSTRAINT ck_sales_owner_transfer_item_skip_reason CHECK (skip_reason IS NULL OR skip_reason IN (
        'OWNER_CHANGED', 'NOT_OWNED_BY_SOURCE', 'REASSIGNED_SINCE', 'TARGET_NOT_ASSIGNABLE', 'MOVED_ON'));

ALTER TABLE customer.sales_owner_transfer_batch
    ADD COLUMN not_found_customer_ids UUID[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN customer.sales_owner_transfer_batch.not_found_customer_ids IS
    'Ids the request named that are no customer (skipped CUSTOMER_NOT_FOUND), kept so a retry answers the same.';

CREATE TABLE customer.customer_tax_code_exemption (
    customer_id      UUID          NOT NULL,
    reason           VARCHAR(500)  NOT NULL,
    granted_by       UUID          NOT NULL,
    granted_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_customer_tax_code_exemption PRIMARY KEY (customer_id),
    CONSTRAINT fk_customer_tax_code_exemption_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_tax_code_exemption_granted_by FOREIGN KEY (granted_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_tax_code_exemption_reason CHECK (btrim(reason) <> '')
);

COMMENT ON TABLE customer.customer_tax_code_exemption IS
    'A Sales Manager allowed a business customer to be created without a tax code (sales-owner plan BR-S14, D4): who, '
    'when and why.';

CREATE TABLE customer.customer_sales_contact (
    customer_id     UUID          NOT NULL,
    user_id         UUID          NOT NULL,
    last_contact_at TIMESTAMPTZ   NOT NULL,
    -- What the last contact was: a quote or a contract sent to the customer.
    last_source     VARCHAR(16)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_customer_sales_contact PRIMARY KEY (customer_id, user_id),
    CONSTRAINT fk_customer_sales_contact_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_sales_contact_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_sales_contact_source CHECK (last_source IN ('QUOTE', 'CONTRACT'))
);

COMMENT ON TABLE customer.customer_sales_contact IS
    'The last time each salesperson sent a customer a quote or a contract (sales-owner plan §10.5): the managers'' '
    'warning on a request to change salesperson. Written from the order module''s events, after their commit.';
