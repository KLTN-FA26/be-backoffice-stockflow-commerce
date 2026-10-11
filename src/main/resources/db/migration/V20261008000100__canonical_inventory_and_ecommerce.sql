-- SCRUM-70/71 canonical cutover. Append-only: the original branch migrations keep their checksums.
-- No inferred legacy-product mapping and no overwrite of divergent canonical data. Reconcile the
-- named rows before upgrading a database that already used the old branch. Never repair history.
ALTER TABLE inventory.inventory_items
    ADD COLUMN safety_stock integer,
    ADD COLUMN removal_strategy varchar(16) NOT NULL DEFAULT 'FEFO',
    ADD COLUMN max_shelf_life_days integer,
    ADD COLUMN policy_configured boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_inventory_control_thresholds CHECK
        (safety_stock IS NULL OR (safety_stock >= 0 AND (reorder_point IS NULL OR safety_stock <= reorder_point))),
    ADD CONSTRAINT ck_inventory_control_removal CHECK (removal_strategy IN ('FIFO','FEFO')),
    ADD CONSTRAINT ck_inventory_control_expiry CHECK
        ((NOT expiry_tracked OR removal_strategy='FEFO') AND
         (max_shelf_life_days IS NULL OR (expiry_tracked AND max_shelf_life_days BETWEEN 1 AND 36500)));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM product.sku s LEFT JOIN inventory.sku_policy p ON p.sku=s.code
        WHERE (p.sku IS NULL AND (s.reorder_point IS NOT NULL OR s.safety_stock IS NOT NULL
               OR s.removal_strategy<>'FEFO' OR s.serial_tracked OR s.expiry_tracked
               OR s.max_shelf_life_days IS NOT NULL))
           OR (p.sku IS NOT NULL AND
               (s.reorder_point IS DISTINCT FROM p.reorder_point
                OR s.safety_stock IS DISTINCT FROM p.safety_stock
                OR s.removal_strategy IS DISTINCT FROM p.removal_strategy
                OR s.lot_tracked IS DISTINCT FROM (p.tracking_mode='LOT')
                OR s.serial_tracked IS DISTINCT FROM (p.tracking_mode='SERIAL')
                OR s.expiry_tracked IS DISTINCT FROM p.expiry_tracked
                OR s.max_shelf_life_days IS DISTINCT FROM p.max_shelf_life_days))) THEN
        RAISE EXCEPTION 'SCRUM-70 cutover: reconcile divergent legacy SKU and execution policies before removing duplicate fields';
    END IF;
    IF EXISTS (SELECT 1 FROM inventory.sku_policy p
               LEFT JOIN inventory.inventory_items i ON i.sku=p.sku WHERE i.id IS NULL) THEN
        RAISE EXCEPTION 'SCRUM-70 cutover: map every sku_policy SKU to its canonical variant and inventory item first';
    END IF;
    IF EXISTS (SELECT 1 FROM inventory.sku_policy p JOIN inventory.inventory_items i ON i.sku=p.sku
               WHERE (i.reorder_point IS NOT NULL AND i.reorder_point IS DISTINCT FROM p.reorder_point)
                  OR i.lot_tracked IS DISTINCT FROM (p.tracking_mode='LOT')
                  OR i.serial_tracked IS DISTINCT FROM (p.tracking_mode='SERIAL')
                  OR i.expiry_tracked IS DISTINCT FROM p.expiry_tracked
                  OR (p.expiry_tracked AND p.tracking_mode<>'LOT')) THEN
        RAISE EXCEPTION 'SCRUM-70 cutover: reconcile conflicting inventory flags/thresholds; expiry requires a real lot';
    END IF;
    IF EXISTS (SELECT 1 FROM catalog.product_listing l
               LEFT JOIN product.products p ON p.id=l.product_id WHERE p.id IS NULL) THEN
        RAISE EXCEPTION 'SCRUM-71 cutover: map every listing product_id to product.products first';
    END IF;
    IF EXISTS (SELECT 1 FROM catalog.product_listing l JOIN product.products p ON p.id=l.product_id
               WHERE p.slug IS DISTINCT FROM l.slug
                  OR (p.seo_title IS NOT NULL AND p.seo_title IS DISTINCT FROM l.seo_title)
                  OR (p.seo_description IS NOT NULL AND p.seo_description IS DISTINCT FROM l.seo_description)
                  OR length(l.seo_title)>255) THEN
        RAISE EXCEPTION 'SCRUM-71 cutover: reconcile conflicting slug/SEO before migration; content is not truncated';
    END IF;
END $$;

UPDATE inventory.inventory_items i
SET reorder_point=p.reorder_point, safety_stock=p.safety_stock, removal_strategy=p.removal_strategy,
    max_shelf_life_days=p.max_shelf_life_days, policy_configured=true,
    version=i.version+1, last_modified_at=now(), last_modified_by='flyway'
FROM inventory.sku_policy p WHERE p.sku=i.sku;
UPDATE inventory.inventory_items SET policy_configured=true
WHERE lot_tracked OR serial_tracked OR expiry_tracked OR reorder_point IS NOT NULL;

-- A canonical variant receives exactly one inventory item; defaults mean no lot/serial tracking.
INSERT INTO inventory.inventory_items(id,sku,created_by)
SELECT gen_random_uuid(),v.sku,'flyway' FROM product.variants v
WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku=v.sku);
CREATE FUNCTION inventory.create_variant_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO inventory.inventory_items(id,sku,created_by)
    VALUES (gen_random_uuid(),NEW.sku,NEW.created_by) ON CONFLICT (sku) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_variant_inventory_item AFTER INSERT ON product.variants
    FOR EACH ROW EXECUTE FUNCTION inventory.create_variant_item();

CREATE OR REPLACE FUNCTION inventory.enforce_sku_policy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p inventory.inventory_items%ROWTYPE; receipt_day date;
BEGIN
    -- Holds/consumption already lock individual rows in id order. Taking a SKU-wide lock here
    -- would invert that order when two reservations span different rows of the same SKU.
    IF TG_OP='UPDATE' AND NEW.sku IS NOT DISTINCT FROM OLD.sku
       AND NEW.received_at IS NOT DISTINCT FROM OLD.received_at
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.serial_number IS NOT DISTINCT FROM OLD.serial_number
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date
       AND NEW.on_hand <= OLD.on_hand THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT * INTO p FROM inventory.inventory_items WHERE sku=NEW.sku;
    IF NOT FOUND OR NOT p.policy_configured THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.received_at IS DISTINCT FROM OLD.received_at
           OR NEW.lot_number IS DISTINCT FROM OLD.lot_number OR NEW.serial_number IS DISTINCT FROM OLD.serial_number
           OR NEW.expiry_date IS DISTINCT FROM OLD.expiry_date THEN
            RAISE EXCEPTION 'Receipt identity is immutable; reconcile via a new stock layer' USING ERRCODE='23514';
        END IF;
        IF NEW.on_hand <= OLD.on_hand THEN RETURN NEW; END IF;
        -- Replenishment must be a new layer; otherwise FIFO loses the age of incoming units.
        IF p.removal_strategy='FIFO' THEN
            RAISE EXCEPTION 'FIFO replenishment requires a new receipt layer' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.on_hand = 0 THEN RETURN NEW; END IF;
    IF (p.lot_tracked AND nullif(trim(NEW.lot_number),'') IS NULL)
       OR (NOT p.lot_tracked AND NEW.lot_number IS NOT NULL)
       OR (p.serial_tracked AND (nullif(trim(NEW.serial_number),'') IS NULL OR NEW.on_hand > 1))
       OR (NOT p.serial_tracked AND NEW.serial_number IS NOT NULL)
       OR (p.expiry_tracked AND NEW.expiry_date IS NULL)
       OR (NOT p.expiry_tracked AND NEW.expiry_date IS NOT NULL)
       OR (p.removal_strategy='FIFO' AND NEW.received_at IS NULL) THEN
        RAISE EXCEPTION 'Stock does not satisfy configured SKU policy' USING ERRCODE='23514';
    END IF;
    receipt_day := COALESCE((NEW.received_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date);
    IF NEW.received_at > CURRENT_TIMESTAMP OR NEW.expiry_date < receipt_day
       OR NEW.expiry_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       OR (p.max_shelf_life_days IS NOT NULL AND (NEW.received_at IS NULL OR NEW.expiry_date > receipt_day + p.max_shelf_life_days)) THEN
        RAISE EXCEPTION 'Invalid receipt/expiry date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

DROP TABLE inventory.sku_policy;
ALTER TABLE product.sku DROP COLUMN reorder_point, DROP COLUMN safety_stock,
    DROP COLUMN removal_strategy, DROP COLUMN serial_tracked, DROP COLUMN expiry_tracked,
    DROP COLUMN max_shelf_life_days;

ALTER TABLE product.products ADD COLUMN ever_published boolean NOT NULL DEFAULT false;
UPDATE product.products SET ever_published=true WHERE published_at IS NOT NULL;
UPDATE product.products p SET seo_title=l.seo_title, seo_description=l.seo_description,
    ever_published=p.ever_published OR l.ever_published,
    version=GREATEST(p.version,l.revision)+1,last_modified_at=now(),last_modified_by='flyway'
FROM catalog.product_listing l WHERE p.id=l.product_id;
UPDATE catalog.product_listing l SET revision=p.version
FROM product.products p WHERE p.id=l.product_id;
CREATE FUNCTION product.preserve_commerce_slug() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ever_published AND (NEW.slug IS DISTINCT FROM OLD.slug OR NOT NEW.ever_published) THEN
        RAISE EXCEPTION 'A published canonical slug is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.status='PUBLISHED' OR NEW.published_at IS NOT NULL THEN NEW.ever_published := true; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_products_commerce_slug BEFORE INSERT OR UPDATE ON product.products
    FOR EACH ROW EXECUTE FUNCTION product.preserve_commerce_slug();

-- These fields are read snapshots, never a second editable SEO source. Their source is product.api.
ALTER TABLE catalog.product_listing
    ADD CONSTRAINT fk_listing_canonical_product FOREIGN KEY(product_id) REFERENCES product.products(id),
    ALTER COLUMN slug TYPE varchar(255), ALTER COLUMN seo_title TYPE varchar(255),
    ALTER COLUMN seo_description TYPE text, ALTER COLUMN published_seo_title TYPE varchar(255),
    ALTER COLUMN published_seo_description TYPE text, ALTER COLUMN published_description TYPE text;
COMMENT ON TABLE catalog.product_listing IS 'Read snapshot and publication projection progress; canonical slug/SEO live in product.products';
ALTER TABLE catalog.catalog_entry ALTER COLUMN seo_description TYPE text, ALTER COLUMN description TYPE text;

-- Covers the inherited supplier permission migration as well. Invalidate existing admin caches.
WITH grants AS (
    INSERT INTO identity.role_permission(id,role_id,permission_id,version,created_at,created_by)
    SELECT gen_random_uuid(),r.id,p.id,0,now(),'flyway'
    FROM identity.app_role r CROSS JOIN identity.permission p WHERE r.code='SYSTEM_ADMIN'
    ON CONFLICT(role_id,permission_id) DO NOTHING RETURNING role_id
)
UPDATE identity.app_role SET version=version+1 WHERE id IN (SELECT role_id FROM grants);
