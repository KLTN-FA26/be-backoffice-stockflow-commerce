-- =============================================================================
-- A customer's own SKU (kltn-docs 12 §4.5, 01 §4.8, glossary "Customer-exclusive SKU", open question B20): when the
-- Sales Manager approves a customer's design, the system issues a new variant of the design's product that belongs to
-- that customer and that design snapshot - the only kind of goods that is stocked, quoted and ordered. Its code is the
-- product's code, the customer's and a number; it is ACTIVE at once; its carton spec, logistics and stock policy are
-- copied from the variant the design was drawn on.
--
-- A variant now records:
--   * owner_customer_id  - the customer who alone may see, be quoted and order it (13 BR-02);
--   * design_snapshot_id - the approved design it is made to; one SKU per snapshot (a revision is a new snapshot and
--                          a new SKU, the old one stays ACTIVE - 12 BR-12);
--   * source_variant_id  - the variant (quy cách) of the same product the design was drawn on.
-- All three or none, never changed afterwards, never on the default variant, and the source is an ordinary variant of
-- the same product.
--
-- No data changes: no customer SKU exists yet.
-- =============================================================================

ALTER TABLE product.variants
    ADD COLUMN owner_customer_id  UUID,
    ADD COLUMN design_snapshot_id UUID,
    ADD COLUMN source_variant_id  UUID,
    ADD CONSTRAINT fk_variants_owner_customer FOREIGN KEY (owner_customer_id) REFERENCES customer.customer (id),
    ADD CONSTRAINT fk_variants_design_snapshot FOREIGN KEY (design_snapshot_id) REFERENCES design.design_snapshot (id),
    ADD CONSTRAINT fk_variants_source_variant FOREIGN KEY (source_variant_id) REFERENCES product.variants (id),
    ADD CONSTRAINT ck_variants_customer_sku
        CHECK ((owner_customer_id IS NULL) = (design_snapshot_id IS NULL)
            AND (owner_customer_id IS NULL) = (source_variant_id IS NULL)
            AND (owner_customer_id IS NULL OR NOT is_default)
            AND source_variant_id IS DISTINCT FROM id);

CREATE UNIQUE INDEX uk_variants_design_snapshot ON product.variants (design_snapshot_id)
    WHERE design_snapshot_id IS NOT NULL;
CREATE INDEX ix_variants_owner_customer ON product.variants (owner_customer_id, product_id)
    WHERE owner_customer_id IS NOT NULL;

-- Who owns a SKU and what it is made to never change: an order, a stock card and a production order name the SKU and
-- mean that customer's design. The source must be an ordinary variant of the same product.
CREATE FUNCTION product.check_customer_sku() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.owner_customer_id IS DISTINCT FROM OLD.owner_customer_id
        OR NEW.design_snapshot_id IS DISTINCT FROM OLD.design_snapshot_id
        OR NEW.source_variant_id IS DISTINCT FROM OLD.source_variant_id) THEN
        RAISE EXCEPTION 'a customer SKU keeps its owner, design snapshot and source variant (variant %)', NEW.id
            USING ERRCODE = '23514';
    END IF;
    IF NEW.source_variant_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM product.variants s
        WHERE s.id = NEW.source_variant_id AND s.product_id = NEW.product_id AND s.owner_customer_id IS NULL) THEN
        RAISE EXCEPTION 'a customer SKU is drawn on an ordinary variant of its own product (variant %)', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_variants_customer_sku BEFORE INSERT OR UPDATE OF owner_customer_id, design_snapshot_id,
    source_variant_id, product_id ON product.variants
    FOR EACH ROW EXECUTE FUNCTION product.check_customer_sku();
