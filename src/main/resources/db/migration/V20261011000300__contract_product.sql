-- =============================================================================
-- CONTRACT C1 - product: the new PIM tables become the only product model.
-- Activated from db/pending by the legacy-tables removal: the code no longer maps the old tables,
-- and V20261011000200 carried their rows over (and archived them in platform.legacy_archive).
-- The #68 bridge triggers go with the legacy tables; their functions are dropped at the end.
-- The orphan check below stops with the table and the row count instead of failing half way.
-- =============================================================================

DO $$
DECLARE
    orphans BIGINT;
    check_sql TEXT;
BEGIN
    FOREACH check_sql IN ARRAY ARRAY[
        'ordering.order_line.sku|SELECT count(*) FROM ordering.order_line c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku)',
        'catalog.catalog_entry.sku|SELECT count(*) FROM catalog.catalog_entry c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku)',
        'catalog.pricing_rule.sku|SELECT count(*) FROM catalog.pricing_rule c WHERE c.sku IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku)',
        'reporting.product_sales_summary.sku|SELECT count(*) FROM reporting.product_sales_summary c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku)',
        'design.design_draft.product_id|SELECT count(*) FROM design.design_draft c WHERE NOT EXISTS (SELECT 1 FROM product.products p WHERE p.id = c.product_id)'
    ] LOOP
        EXECUTE split_part(check_sql, '|', 2) INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'C1: % row(s) in % point at no new product/variant. Create the missing variants (or re-point design drafts to the new product ids) first.',
                orphans, split_part(check_sql, '|', 1);
        END IF;
    END LOOP;
END $$;

ALTER TABLE ordering.order_line
    ADD CONSTRAINT fk_order_line_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT;
ALTER TABLE catalog.catalog_entry
    ADD CONSTRAINT fk_catalog_entry_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT;
ALTER TABLE catalog.pricing_rule
    ADD CONSTRAINT fk_pricing_rule_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT;
ALTER TABLE reporting.product_sales_summary
    ADD CONSTRAINT fk_product_sales_summary_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT;
ALTER TABLE design.design_draft
    ADD CONSTRAINT fk_design_draft_product FOREIGN KEY (product_id)
        REFERENCES product.products (id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS ix_order_line_sku ON ordering.order_line (sku);
CREATE INDEX IF NOT EXISTS ix_design_draft_product ON design.design_draft (product_id);

-- The old model. Children first; CASCADE is deliberately not used, so an unexpected dependant
-- stops the migration instead of disappearing with it.
DROP TABLE product.variant_gallery;
DROP TABLE product.product_gallery;
DROP TABLE product.product_image;
DROP TABLE product.print_config;
DROP TABLE product.sku;
DROP TABLE product.variant;
DROP TABLE product.product;
DROP TABLE product.category;

-- The #68 bridge (V20261009000100): its triggers went with product.product and product.category.
DROP FUNCTION product.bridge_product_to_pim();
DROP FUNCTION product.bridge_category_to_pim();
