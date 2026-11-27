-- =============================================================================
-- Standard price of a variant (kltn-docs 13 BR-04 as changed on 2026-11-26; 14 BR-10).
--
-- Until now a quote line's only starting price was the customer's own price for its SKU, which the Sales Manager keys in
-- by hand. A new design is a new SKU, so the first quote of every design started from nothing: Sales priced it from
-- memory and the 10% alert (QuoteDiscountAlerts) had nothing to compare with. The owner decided on 2026-11-26 that the
-- Sales Manager keeps a standard price per variant (the plain cup, "quy cách"): price breaks by cartons, per piece, for a
-- usual print. A quote line starts from the customer's own price for its SKU, else from the standard price of the
-- variant the SKU was drawn on; a quote priced more than the threshold below that reference, or with no reference at
-- all, waits for the Sales Manager's approval before it is sent (V20261230000200).
--
-- The same shape as catalog.customer_price: a header per variant and 1-20 tiers, whole positive VND a piece from a
-- number of cartons on. Only a variant that is not a customer's own SKU has one (tg_standard_price_variant): a customer's
-- SKU is priced by the customer's own price. Deleting a draft product's variant deletes its standard price with it: the
-- price is the variant's own master data, nothing else points at it.
--
-- Permissions mirror catalog-customer-prices: Sales Staff read (to quote), the Sales Manager edits, the System Admin
-- holds everything. Every role that gained a grant advances app_role.version in the same transaction (ADR-0008,
-- CLAUDE.md §5).
-- =============================================================================

CREATE TABLE catalog.standard_price (
    id uuid NOT NULL,
    sku character varying(64) NOT NULL,
    currency character varying(3) DEFAULT 'VND'::character varying NOT NULL,
    note character varying(500),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_standard_price PRIMARY KEY (id),
    CONSTRAINT uk_standard_price_sku UNIQUE (sku),
    CONSTRAINT ck_standard_price_currency CHECK (currency = 'VND'),
    CONSTRAINT fk_standard_price_variant FOREIGN KEY (sku) REFERENCES product.variants (sku)
        ON UPDATE CASCADE ON DELETE CASCADE
);

COMMENT ON TABLE catalog.standard_price IS
    'The standard price of a variant (13 BR-04, 2026-11-26): what a quote line starts from when the customer has no '
    'price of its own for the SKU, and what a quote''s discount is measured against (14 BR-10). Tiers in standard_price_tier.';

CREATE TABLE catalog.standard_price_tier (
    id uuid NOT NULL,
    standard_price_id uuid NOT NULL,
    min_quantity integer NOT NULL,
    unit_price numeric(18,2) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_standard_price_tier PRIMARY KEY (id),
    CONSTRAINT uk_standard_price_tier UNIQUE (standard_price_id, min_quantity),
    CONSTRAINT ck_standard_price_tier_price CHECK (unit_price > 0 AND unit_price = trunc(unit_price)),
    CONSTRAINT ck_standard_price_tier_quantity CHECK (min_quantity >= 1),
    CONSTRAINT fk_standard_price_tier_price FOREIGN KEY (standard_price_id) REFERENCES catalog.standard_price (id)
        ON DELETE CASCADE
);

COMMENT ON TABLE catalog.standard_price_tier IS
    'From min_quantity cartons on, unit_price (whole VND a piece) applies. The first tier is where the standard price starts.';

-- A customer's own SKU is priced by the customer's own price (13 BR-05): a standard price is a variant's that no customer owns.
CREATE FUNCTION catalog.check_standard_price_variant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = NEW.sku AND v.owner_customer_id IS NULL) THEN
        RAISE EXCEPTION 'standard price: % is not a variant of the catalogue (a customer''s own SKU has the customer''s price)',
            NEW.sku USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_standard_price_variant BEFORE INSERT OR UPDATE OF sku ON catalog.standard_price
    FOR EACH ROW EXECUTE FUNCTION catalog.check_standard_price_variant();

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'catalog-standard-prices:VIEW_PAGE', 'catalog-standard-prices', 'VIEW_PAGE',
        'Open the standard prices page', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'catalog-standard-prices:READ', 'catalog-standard-prices', 'READ',
        'Read the standard prices of variants', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'catalog-standard-prices:CREATE', 'catalog-standard-prices', 'CREATE',
        'Give a variant its standard price', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'catalog-standard-prices:UPDATE', 'catalog-standard-prices', 'UPDATE',
        'Replace the standard price of a variant', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'catalog-standard-prices:DELETE', 'catalog-standard-prices', 'DELETE',
        'Remove the standard price of a variant', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code, permission_code) AS (
    VALUES ('SYSTEM_ADMIN', 'catalog-standard-prices:VIEW_PAGE'),
           ('SYSTEM_ADMIN', 'catalog-standard-prices:READ'),
           ('SYSTEM_ADMIN', 'catalog-standard-prices:CREATE'),
           ('SYSTEM_ADMIN', 'catalog-standard-prices:UPDATE'),
           ('SYSTEM_ADMIN', 'catalog-standard-prices:DELETE'),
           ('SALES_MANAGER', 'catalog-standard-prices:VIEW_PAGE'),
           ('SALES_MANAGER', 'catalog-standard-prices:READ'),
           ('SALES_MANAGER', 'catalog-standard-prices:CREATE'),
           ('SALES_MANAGER', 'catalog-standard-prices:UPDATE'),
           ('SALES_MANAGER', 'catalog-standard-prices:DELETE'),
           ('SALES_STAFF', 'catalog-standard-prices:VIEW_PAGE'),
           ('SALES_STAFF', 'catalog-standard-prices:READ')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = g.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
