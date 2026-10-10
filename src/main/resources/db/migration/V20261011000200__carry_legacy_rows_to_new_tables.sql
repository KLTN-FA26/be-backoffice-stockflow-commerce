-- =============================================================================
-- Carry every row of the legacy product, procurement and warehouse tables to the new model, before
-- contracts C1-C4 (the next four migrations) drop those tables. Same ids wherever a row maps one to
-- one, so foreign keys, audit history and links already handed out keep pointing at the same thing.
--
-- Nothing is discarded. Every legacy row is first archived, whole, as JSON in
-- platform.legacy_archive; what the new model can hold is then copied into it. What it cannot hold
-- - a legacy goods receipt has no lines, a variant gallery override would share the storage key of
-- the product image it points at, a print config has no counterpart - stays in the archive only.
--
-- Rules followed, in the order of the sections below:
--   1. product: products and categories the #68 bridge could not copy (a code the PIM refuses) are
--      copied with a code that fits; every legacy SKU becomes a variant (same id as the sku row);
--      every product has a default variant, SKU = its code; logistics go to the inventory items of
--      its variants (docs 01 BR-08); images go to product.media on the default variant (D5), published
--      when the approved gallery showed them.
--   2. SKU references: order lines, catalog entries, prices, sales summaries, stock, putaway tasks
--      and PO lines must name a variant (C1/C3). A lower-case SKU is upper-cased where its upper-case
--      form is a variant; any SKU still unknown becomes a variant of one placeholder product,
--      LEGACY-UNMAPPED (DISCONTINUED), so history keeps its SKU and the keys can be added.
--   3. suppliers -> suppliers + the primary supplier_contacts row.
--   4. purchase orders -> purchase_orders with lines on inventory items, statuses per D4 (SENT ->
--      CONFIRMED, CLOSED_SHORT -> CLOSED/SHORT_CLOSE), received into the oldest active warehouse.
--      A non-draft order gets the revision the new model requires. The legacy model never recorded
--      who submitted or approved; the creator stands as submitter when the username is known, and a
--      disabled technical user stands for whoever approved, confirmed or closed, so the four-eyes
--      CHECK holds without inventing a person. Their events say so.
--   5. supplier invoices -> supplier_invoices.
--   6. putaway tasks with no source line, and empty stock rows at a location missing from the map,
--      are archived and removed; every warehouse gets its map frame (C2 makes it mandatory).
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 0. The archive
-- ---------------------------------------------------------------------------------------------
CREATE TABLE platform.legacy_archive
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    source_table VARCHAR(100) NOT NULL,
    row_id       UUID,
    row_data     JSONB        NOT NULL,
    archived_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_legacy_archive PRIMARY KEY (id),
    CONSTRAINT ck_legacy_archive_row CHECK (jsonb_typeof(row_data) = 'object')
);
CREATE INDEX ix_legacy_archive_source ON platform.legacy_archive (source_table, row_id);
COMMENT ON TABLE platform.legacy_archive IS
    'Every row of the tables dropped by contracts C1-C4, as it was, archived by V20261011000200. Read-only history.';

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'product.category', t.id, to_jsonb(t) FROM product.category t
UNION ALL SELECT 'product.product', t.id, to_jsonb(t) FROM product.product t
UNION ALL SELECT 'product.variant', t.id, to_jsonb(t) FROM product.variant t
UNION ALL SELECT 'product.sku', t.id, to_jsonb(t) FROM product.sku t
UNION ALL SELECT 'product.print_config', t.id, to_jsonb(t) FROM product.print_config t
UNION ALL SELECT 'product.product_image', t.id, to_jsonb(t) FROM product.product_image t
UNION ALL SELECT 'product.product_gallery', t.id, to_jsonb(t) FROM product.product_gallery t
UNION ALL SELECT 'product.variant_gallery', t.id, to_jsonb(t) FROM product.variant_gallery t
UNION ALL SELECT 'procurement.supplier', t.id, to_jsonb(t) FROM procurement.supplier t
UNION ALL SELECT 'procurement.purchase_order', t.id, to_jsonb(t) FROM procurement.purchase_order t
UNION ALL SELECT 'procurement.po_line', t.id, to_jsonb(t) FROM procurement.po_line t
UNION ALL SELECT 'procurement.goods_receipt', t.id, to_jsonb(t) FROM procurement.goods_receipt t
UNION ALL SELECT 'procurement.qc_result', t.id, to_jsonb(t) FROM procurement.qc_result t
UNION ALL SELECT 'procurement.supplier_invoice', t.id, to_jsonb(t) FROM procurement.supplier_invoice t
UNION ALL SELECT 'warehouse.location', t.id, to_jsonb(t) FROM warehouse.location t;


-- A code the new tables accept: upper case, other characters as '-', starting with a letter or a
-- digit, at most max_len characters. Uniqueness is the caller's; see fit_unique_code.
CREATE FUNCTION platform.legacy_fit_code(raw TEXT, max_len INTEGER)
    RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS
$$
SELECT left(COALESCE(NULLIF(regexp_replace(regexp_replace(upper(COALESCE(raw, '')), '[^A-Z0-9_-]+', '-', 'g'),
                                           '^[^A-Z0-9]+', ''), ''), 'X'), max_len)
$$;

-- The same, made unique with a piece of the row id when the plain form is taken.
CREATE FUNCTION platform.legacy_unique_code(fitted TEXT, max_len INTEGER, row_id UUID, taken BOOLEAN)
    RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE WHEN NOT taken THEN fitted
            ELSE left(fitted, max_len - 9) || '-' || upper(left(replace(row_id::text, '-', ''), 8)) END
$$;


-- ---------------------------------------------------------------------------------------------
-- 1. Product
-- ---------------------------------------------------------------------------------------------

-- 1a. Categories the bridge did not copy, parents first.
DO
$$
DECLARE
    r       RECORD;
    fitted  TEXT;
    parent  product.categories%ROWTYPE;
    the_slug TEXT;
BEGIN
    FOR r IN WITH RECURSIVE tree AS (
                 SELECT id, 0 AS level FROM product.category WHERE parent_id IS NULL
                 UNION ALL
                 SELECT c.id, t.level + 1 FROM product.category c JOIN tree t ON c.parent_id = t.id)
             SELECT c.* FROM tree JOIN product.category c ON c.id = tree.id
              WHERE NOT EXISTS (SELECT 1 FROM product.categories n WHERE n.id = c.id)
              ORDER BY tree.level LOOP
        fitted := platform.legacy_fit_code(r.code, 50);
        fitted := platform.legacy_unique_code(fitted, 50, r.id,
                EXISTS (SELECT 1 FROM product.categories WHERE code = fitted));
        the_slug := product.slug_of(fitted);
        IF EXISTS (SELECT 1 FROM product.categories WHERE slug = the_slug) THEN
            the_slug := the_slug || '-' || left(replace(r.id::text, '-', ''), 8);
        END IF;
        parent := NULL;
        IF r.parent_id IS NOT NULL THEN
            SELECT * INTO parent FROM product.categories WHERE id = r.parent_id;
        END IF;
        INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, created_at, created_by)
        VALUES (r.id, CASE WHEN parent.id IS NULL THEN NULL ELSE r.parent_id END, fitted, r.name, the_slug,
                CASE WHEN parent.id IS NULL THEN '/' || fitted ELSE parent.path || '/' || fitted END,
                CASE WHEN parent.id IS NULL THEN 0 ELSE parent.depth + 1 END,
                COALESCE(r.created_at, NOW()), 'legacy:' || COALESCE(r.created_by, 'import'));
    END LOOP;
END
$$;

-- 1b. Products the bridge did not copy.
DO
$$
DECLARE
    r        RECORD;
    fitted   TEXT;
    the_slug TEXT;
BEGIN
    FOR r IN SELECT p.* FROM product.product p
              WHERE NOT EXISTS (SELECT 1 FROM product.products n WHERE n.id = p.id)
              ORDER BY p.created_at LOOP
        fitted := platform.legacy_fit_code(r.code, 50);
        fitted := platform.legacy_unique_code(fitted, 50, r.id,
                EXISTS (SELECT 1 FROM product.products WHERE code = fitted));
        the_slug := product.slug_of(fitted);
        IF EXISTS (SELECT 1 FROM product.products WHERE slug = the_slug) THEN
            the_slug := the_slug || '-' || left(replace(r.id::text, '-', ''), 8);
        END IF;
        INSERT INTO product.products (id, code, name, name_en, slug, description, description_en, tax_class, kind,
                                      brand_id, status, rejection_reason, submitted_by, submitted_at, approved_by,
                                      approved_at, published_at, discontinued_at, created_at, created_by)
        VALUES (r.id, fitted, r.name, r.name_en, the_slug, r.description, r.description_en, r.tax_class,
                CASE WHEN r.customizable THEN 'CUSTOMIZABLE' ELSE 'STANDARD' END,
                (SELECT b.id FROM product.brands b WHERE lower(b.name) = lower(r.brand) LIMIT 1),
                r.status, r.rejection_reason, r.submitted_by, r.submitted_at, r.approved_by, r.approved_at,
                CASE WHEN r.status = 'PUBLISHED' THEN COALESCE(r.last_modified_at, NOW()) END,
                CASE WHEN r.status = 'DISCONTINUED' THEN COALESCE(r.last_modified_at, NOW()) END,
                COALESCE(r.created_at, NOW()), 'legacy:' || COALESCE(r.created_by, 'import'));
    END LOOP;
END
$$;

-- Their primary category, where the PIM side has it and the product has none yet.
INSERT INTO product.product_categories (id, product_id, category_id, is_primary, created_at, created_by)
SELECT gen_random_uuid(), p.id, p.category_id, TRUE, NOW(), 'legacy:import'
  FROM product.product p
 WHERE p.category_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM product.categories c WHERE c.id = p.category_id)
   AND NOT EXISTS (SELECT 1 FROM product.product_categories pc WHERE pc.product_id = p.id AND pc.is_primary)
ON CONFLICT (product_id, category_id) DO UPDATE SET is_primary = TRUE;

-- 1c. Every legacy SKU is a variant: same id as the sku row, the legacy variant's name.
DO
$$
DECLARE
    r      RECORD;
    fitted TEXT;
    pos    INTEGER;
BEGIN
    FOR r IN SELECT s.id, s.code, s.created_at, s.created_by, v.name, v.product_id, p.status AS product_status
               FROM product.sku s
               JOIN product.variant v ON v.id = s.variant_id
               JOIN product.products p ON p.id = v.product_id
              WHERE NOT EXISTS (SELECT 1 FROM product.variants n WHERE n.id = s.id)
              ORDER BY v.product_id, s.created_at, s.id LOOP
        fitted := left(regexp_replace(regexp_replace(upper(r.code), '[^A-Z0-9._-]+', '-', 'g'), '^[^A-Z0-9]+', ''), 64);
        IF fitted = '' THEN fitted := 'X'; END IF;
        IF EXISTS (SELECT 1 FROM product.variants WHERE sku = fitted) THEN
            CONTINUE; -- the SKU is already a variant (the PIM side had it first): the archive keeps the row
        END IF;
        SELECT COALESCE(MAX(position) + 1, 0) INTO pos FROM product.variants WHERE product_id = r.product_id;
        INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                                      created_at, created_by)
        VALUES (r.id, r.product_id, fitted, left(r.name, 255),
                CASE r.product_status WHEN 'APPROVED' THEN 'ACTIVE' WHEN 'PUBLISHED' THEN 'ACTIVE'
                                      WHEN 'DISCONTINUED' THEN 'BLOCKED' ELSE 'DRAFT' END,
                FALSE, left('SKU=' || fitted, 512), pos, COALESCE(r.created_at, NOW()),
                'legacy:' || COALESCE(r.created_by, 'import'));
    END LOOP;
END
$$;

-- 1d. Every product has a default variant. Its SKU is the product code (what inventory and order
-- lines used before variants existed); when another product's variant already has that SKU, the
-- product's first variant becomes the default instead.
UPDATE product.variants v SET is_default = TRUE, last_modified_at = NOW(), last_modified_by = 'legacy:import'
  FROM product.products p
 WHERE v.product_id = p.id AND v.sku = p.code
   AND NOT EXISTS (SELECT 1 FROM product.variants d WHERE d.product_id = p.id AND d.is_default);

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              created_at, created_by)
SELECT gen_random_uuid(), p.id, p.code, left(p.name, 255),
       CASE p.status WHEN 'APPROVED' THEN 'ACTIVE' WHEN 'PUBLISHED' THEN 'ACTIVE' WHEN 'ACTIVE' THEN 'ACTIVE'
                     WHEN 'DISCONTINUED' THEN 'BLOCKED' ELSE 'DRAFT' END,
       TRUE, 'DEFAULT', COALESCE((SELECT MAX(position) + 1 FROM product.variants x WHERE x.product_id = p.id), 0),
       NOW(), 'legacy:import'
  FROM product.products p
 WHERE NOT EXISTS (SELECT 1 FROM product.variants d WHERE d.product_id = p.id AND d.is_default)
   AND NOT EXISTS (SELECT 1 FROM product.variants d WHERE d.sku = p.code)
   AND NOT EXISTS (SELECT 1 FROM product.variants d WHERE d.product_id = p.id AND d.attribute_signature = 'DEFAULT');

UPDATE product.variants v SET is_default = TRUE, last_modified_at = NOW(), last_modified_by = 'legacy:import'
 WHERE v.id IN (SELECT DISTINCT ON (x.product_id) x.id FROM product.variants x
                 WHERE NOT EXISTS (SELECT 1 FROM product.variants d WHERE d.product_id = x.product_id AND d.is_default)
                 ORDER BY x.product_id, x.position, x.id);

-- 1e. Inventory items: every variant has one (the trigger creates it for new rows; older rows too).
INSERT INTO inventory.inventory_items (id, sku, created_at, created_by)
SELECT gen_random_uuid(), v.sku, NOW(), 'legacy:import'
  FROM product.variants v
 WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = v.sku);

-- The legacy sku row's own data: unit, barcode, lot tracking.
UPDATE inventory.inventory_items i
   SET unit_of_measure = CASE WHEN upper(s.unit_of_measure) ~ '^[A-Z0-9_]{1,16}$' THEN upper(s.unit_of_measure)
                              ELSE i.unit_of_measure END,
       barcode = CASE WHEN s.barcode IS NOT NULL
                       AND NOT EXISTS (SELECT 1 FROM inventory.inventory_items o WHERE o.barcode = s.barcode)
                      THEN s.barcode ELSE i.barcode END,
       lot_tracked = i.lot_tracked OR s.lot_tracked,
       version = i.version + 1, last_modified_at = NOW(), last_modified_by = 'legacy:import'
  FROM product.sku s
  JOIN product.variants v ON v.id = s.id
 WHERE i.sku = v.sku AND NOT i.policy_configured;

-- The product's logistics, on every variant's item that has none yet. The carrier flags fold into
-- the storage class: hazmat goods are stored HAZMAT, oversized ones OVERSIZE.
UPDATE inventory.inventory_items i
   SET weight_kg = p.weight_kg, length_cm = p.length_cm, width_cm = p.width_cm, height_cm = p.height_cm,
       package_weight_kg = p.package_weight_kg, package_length_cm = p.package_length_cm,
       package_width_cm = p.package_width_cm, package_height_cm = p.package_height_cm,
       package_count = COALESCE(p.package_count, 1),
       storage_class = CASE WHEN p.storage_class <> 'NORMAL' THEN p.storage_class
                            WHEN p.hazmat THEN 'HAZMAT' WHEN p.oversized THEN 'OVERSIZE' ELSE 'NORMAL' END,
       requires_adult_signature = p.requires_adult_signature,
       shipping_restriction_note = p.shipping_restriction_note,
       version = i.version + 1, last_modified_at = NOW(), last_modified_by = 'legacy:import'
  FROM product.product p
  JOIN product.variants v ON v.product_id = p.id
 WHERE i.sku = v.sku
   AND i.weight_kg IS NULL AND i.length_cm IS NULL AND i.package_weight_kg IS NULL
   AND (p.weight_kg IS NOT NULL OR p.length_cm IS NOT NULL OR p.package_weight_kg IS NOT NULL
        OR p.storage_class <> 'NORMAL' OR p.hazmat OR p.oversized OR p.requires_adult_signature
        OR p.shipping_restriction_note IS NOT NULL);

-- 1f. Images -> media of the default variant. Published when the approved gallery shows it, with
-- its caption as the alt text; the gallery's cover is the primary image.
WITH gallery_items AS (
    SELECT g.id AS product_id, (item ->> 'imageId')::uuid AS image_id, item ->> 'caption' AS caption,
           ord AS position, TRUE AS published, g.approved_by, g.last_modified_at
      FROM product.product_gallery g, jsonb_array_elements(g.published_items) WITH ORDINALITY AS e(item, ord)
    UNION ALL
    SELECT g.id, (item ->> 'imageId')::uuid, item ->> 'caption', ord, FALSE, NULL, NULL
      FROM product.product_gallery g, jsonb_array_elements(g.working_items) WITH ORDINALITY AS e(item, ord)
), best AS (
    SELECT DISTINCT ON (image_id) * FROM gallery_items ORDER BY image_id, published DESC, position
)
INSERT INTO product.media (id, variant_id, kind, url, storage_key, original_name, content_type, size_bytes, checksum,
                           stored_at, renditions, upload_key, alt_text, sort_order, is_primary, is_published,
                           published_at, published_by, created_at, created_by)
SELECT i.id, v.id, 'IMAGE',
       CASE WHEN i.storage_key IS NULL THEN i.url END,
       i.storage_key, i.original_name, i.content_type, i.size_bytes,
       CASE WHEN i.checksum ~ '^[0-9a-f]{64}$' THEN i.checksum END,
       i.stored_at, COALESCE(i.renditions, '[]'::jsonb),
       CASE WHEN NOT EXISTS (SELECT 1 FROM product.media m WHERE m.upload_key = i.upload_key) THEN i.upload_key END,
       left(b.caption, 255), i.sort_order,
       FALSE,
       COALESCE(b.published AND b.approved_by IS NOT NULL, FALSE),
       CASE WHEN b.published AND b.approved_by IS NOT NULL THEN COALESCE(b.last_modified_at, NOW()) END,
       CASE WHEN b.published THEN b.approved_by END,
       -- product_image kept no uploader; the gallery's last editor is the closest record of who put
       -- it there, and keeps the four-eyes filter of the publication path meaningful.
       COALESCE(i.stored_at, lp.created_at, NOW()),
       COALESCE(pg.edited_by::text, lp.created_by, 'legacy:import')
  FROM product.product_image i
  JOIN product.product lp ON lp.id = i.product_id
  LEFT JOIN product.product_gallery pg ON pg.id = i.product_id
  JOIN product.variants v ON v.product_id = i.product_id AND v.is_default
  LEFT JOIN best b ON b.image_id = i.id
 WHERE NOT EXISTS (SELECT 1 FROM product.media m WHERE m.id = i.id)
   AND (i.storage_key IS NULL OR NOT EXISTS (SELECT 1 FROM product.media m WHERE m.storage_key = i.storage_key))
   AND (i.storage_key IS NOT NULL OR i.url IS NOT NULL);

UPDATE product.media m SET is_primary = TRUE
 WHERE m.id IN (
        SELECT DISTINCT ON (x.variant_id) x.id
          FROM product.media x
          LEFT JOIN product.variants v ON v.id = x.variant_id
          LEFT JOIN product.product_gallery g ON g.id = v.product_id
         WHERE NOT EXISTS (SELECT 1 FROM product.media p WHERE p.variant_id = x.variant_id AND p.is_primary)
         ORDER BY x.variant_id,
                  (g.published_items -> 0 ->> 'imageId')::uuid IS DISTINCT FROM x.id,
                  x.sort_order, x.id);


-- ---------------------------------------------------------------------------------------------
-- 2. SKU references
-- ---------------------------------------------------------------------------------------------

-- A stock row's SKU is part of its receipt identity, which the policy trigger guards against a change.
-- This is not a stock movement but the same SKU written the way the variant writes it.
ALTER TABLE inventory.stock_item DISABLE TRIGGER trg_stock_sku_policy;

UPDATE ordering.order_line t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku));
UPDATE catalog.catalog_entry t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku))
   AND NOT EXISTS (SELECT 1 FROM catalog.catalog_entry o WHERE o.sku = upper(t.sku));
UPDATE catalog.pricing_rule t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku));
UPDATE reporting.product_sales_summary t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku))
   AND NOT EXISTS (SELECT 1 FROM reporting.product_sales_summary o WHERE o.sku = upper(t.sku) AND o.period = t.period);
UPDATE inventory.stock_item t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku));
UPDATE warehouse.putaway_task t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku));
UPDATE procurement.po_line t SET sku = upper(t.sku)
 WHERE t.sku <> upper(t.sku) AND EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = upper(t.sku));

ALTER TABLE inventory.stock_item ENABLE TRIGGER trg_stock_sku_policy;

-- Whatever is still unknown becomes a variant of one placeholder product, so its history keeps its
-- SKU and the foreign keys can be added. A SKU the variant pattern refuses is left: the contracts'
-- orphan checks then stop with its table and count, for a person to decide.
CREATE TEMPORARY TABLE legacy_unmapped_sku ON COMMIT DROP AS
SELECT DISTINCT sku FROM (
    SELECT sku FROM ordering.order_line
    UNION SELECT sku FROM catalog.catalog_entry
    UNION SELECT sku FROM catalog.pricing_rule WHERE sku IS NOT NULL
    UNION SELECT sku FROM reporting.product_sales_summary
    UNION SELECT sku FROM inventory.stock_item
    UNION SELECT sku FROM warehouse.putaway_task
    UNION SELECT sku FROM procurement.po_line
) refs
 WHERE NOT EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = refs.sku)
   AND sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$';

INSERT INTO product.products (id, code, name, slug, kind, status, discontinued_at, created_at, created_by)
SELECT gen_random_uuid(), 'LEGACY-UNMAPPED', 'SKU cũ không gắn sản phẩm', 'legacy-unmapped', 'STANDARD',
       'DISCONTINUED', NOW(), NOW(), 'legacy:import'
 WHERE EXISTS (SELECT 1 FROM legacy_unmapped_sku)
   AND NOT EXISTS (SELECT 1 FROM product.products WHERE code = 'LEGACY-UNMAPPED');

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              created_at, created_by)
SELECT gen_random_uuid(), p.id, u.sku, u.sku, 'BLOCKED', FALSE, left('SKU=' || u.sku, 512),
       ROW_NUMBER() OVER (ORDER BY u.sku), NOW(), 'legacy:import'
  FROM legacy_unmapped_sku u
  JOIN product.products p ON p.code = 'LEGACY-UNMAPPED';

INSERT INTO inventory.inventory_items (id, sku, created_at, created_by)
SELECT gen_random_uuid(), v.sku, NOW(), 'legacy:import'
  FROM product.variants v
 WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = v.sku);


-- ---------------------------------------------------------------------------------------------
-- 3. Suppliers
-- ---------------------------------------------------------------------------------------------
DO
$$
DECLARE
    r      RECORD;
    fitted TEXT;
BEGIN
    FOR r IN SELECT s.* FROM procurement.supplier s
              WHERE NOT EXISTS (SELECT 1 FROM procurement.suppliers n WHERE n.id = s.id)
              ORDER BY s.created_at LOOP
        fitted := platform.legacy_fit_code(r.code, 30);
        fitted := platform.legacy_unique_code(fitted, 30, r.id,
                EXISTS (SELECT 1 FROM procurement.suppliers WHERE code = fitted));
        INSERT INTO procurement.suppliers (id, code, name, tax_id, status, currency, payment_terms, lead_time_days,
                                           payment_term_days, communication_channel, api_endpoint, note, created_at,
                                           created_by)
        VALUES (r.id, fitted, r.name,
                CASE WHEN length(r.tax_code) <= 30
                      AND NOT EXISTS (SELECT 1 FROM procurement.suppliers WHERE tax_id = r.tax_code)
                     THEN r.tax_code END,
                r.status, 'VND', 'Net ' || r.payment_term_days, r.lead_time_days, r.payment_term_days,
                r.communication_channel,
                CASE WHEN r.api_endpoint ~ '^https://[^/\s]+' THEN r.api_endpoint END,
                CASE WHEN fitted <> r.code OR length(r.tax_code) > 30
                     THEN 'Legacy code ' || r.code || COALESCE(', tax code ' || r.tax_code, '') END,
                COALESCE(r.created_at, NOW()), 'legacy:' || COALESCE(r.created_by, 'import'));
        IF NULLIF(btrim(r.email), '') IS NOT NULL OR NULLIF(btrim(r.phone), '') IS NOT NULL THEN
            INSERT INTO procurement.supplier_contacts (id, supplier_id, full_name, phone, email, is_primary, created_at,
                                                       created_by)
            VALUES (gen_random_uuid(), r.id, left(COALESCE(NULLIF(btrim(r.contact_name), ''), r.name), 150),
                    left(NULLIF(btrim(r.phone), ''), 30), left(NULLIF(btrim(r.email), ''), 150), TRUE, NOW(),
                    'legacy:import');
        END IF;
    END LOOP;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 4. Purchase orders
-- ---------------------------------------------------------------------------------------------

-- The technical stand-ins, only when an order needs them. Disabled, and the hash is no hash: nobody
-- can sign in as them.
INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at, created_by)
SELECT md5('legacy:user:import')::uuid, 'system.legacy-import', 'legacy-import@system.invalid', '!no-login',
       'DISABLED', 0, NOW(), 'legacy:import'
 WHERE EXISTS (SELECT 1 FROM procurement.purchase_order WHERE status NOT IN ('DRAFT'))
ON CONFLICT DO NOTHING;
INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at, created_by)
SELECT md5('legacy:user:approval')::uuid, 'system.legacy-approval', 'legacy-approval@system.invalid', '!no-login',
       'DISABLED', 0, NOW(), 'legacy:import'
 WHERE EXISTS (SELECT 1 FROM procurement.purchase_order WHERE status NOT IN ('DRAFT', 'CANCELLED'))
ON CONFLICT DO NOTHING;

DO
$$
DECLARE
    po          RECORD;
    wh          UUID;
    importer    UUID := md5('legacy:user:import')::uuid;
    approver    UUID := md5('legacy:user:approval')::uuid;
    submitter   UUID;
    new_status  TEXT;
    revision    UUID;
    subtotal    NUMERIC(18, 2);
    copied      INTEGER := 0;
BEGIN
    SELECT id INTO wh FROM warehouse.warehouse WHERE status = 'ACTIVE' ORDER BY created_at, id LIMIT 1;
    IF wh IS NULL THEN
        SELECT id INTO wh FROM warehouse.warehouse ORDER BY created_at, id LIMIT 1;
    END IF;

    FOR po IN SELECT p.* FROM procurement.purchase_order p
               WHERE NOT EXISTS (SELECT 1 FROM procurement.purchase_orders n WHERE n.id = p.id)
                 AND EXISTS (SELECT 1 FROM procurement.suppliers s WHERE s.id = p.supplier_id)
                 AND EXISTS (SELECT 1 FROM procurement.po_line l WHERE l.purchase_order_id = p.id)
                 -- every line must order a known item at a positive price: the new lines require both
                 AND NOT EXISTS (SELECT 1 FROM procurement.po_line l
                                  WHERE l.purchase_order_id = p.id
                                    AND (l.unit_price <= 0
                                         OR NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = l.sku)))
               ORDER BY p.created_at LOOP
        EXIT WHEN wh IS NULL;
        new_status := CASE po.status WHEN 'SENT' THEN 'CONFIRMED' WHEN 'CLOSED_SHORT' THEN 'CLOSED' ELSE po.status END;
        SELECT u.id INTO submitter FROM identity.app_user u WHERE u.username = po.created_by;
        IF submitter IS NULL OR submitter = approver THEN
            submitter := importer;
        END IF;
        SELECT COALESCE(SUM(round(l.unit_price * l.quantity_ordered, 2)), 0) INTO subtotal
          FROM procurement.po_line l WHERE l.purchase_order_id = po.id;
        -- Every order out of DRAFT carries a revision (ck_purchase_orders_revision, checked on insert;
        -- the pointer's foreign key is deferred, so the revision row may follow).
        revision := CASE WHEN new_status <> 'DRAFT' THEN gen_random_uuid() END;

        INSERT INTO procurement.purchase_orders
               (id, po_number, revision_no, status, supplier_id, warehouse_id, currency, order_date, expected_date,
                payment_terms, subtotal, tax_total, total_amount, close_kind, close_reason, cancel_reason,
                submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by, closed_at, closed_by,
                payment_term_days, lead_time_days, supplier_confirmation_status, supplier_responded_at,
                supplier_reference, supplier_response_note, note, active_revision_id, created_at, created_by)
        VALUES (po.id, left(po.po_number, 30), 0, new_status, po.supplier_id, wh, po.currency,
                LEAST((COALESCE(po.created_at, NOW()) AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                      COALESCE(po.expected_at, 'infinity'::date)),
                po.expected_at, 'Net ' || po.payment_term_days, subtotal, 0, subtotal,
                CASE po.status WHEN 'CLOSED' THEN 'NORMAL' WHEN 'CLOSED_SHORT' THEN 'SHORT_CLOSE' END,
                CASE WHEN po.status = 'CLOSED_SHORT' THEN left(COALESCE(po.close_short_reason, 'Closed short'), 255) END,
                CASE WHEN po.status = 'CANCELLED' THEN left(COALESCE(po.cancellation_reason, 'Cancelled'), 255) END,
                CASE WHEN new_status NOT IN ('DRAFT', 'CANCELLED') THEN COALESCE(po.created_at, NOW()) END,
                CASE WHEN new_status NOT IN ('DRAFT', 'CANCELLED') THEN submitter END,
                CASE WHEN new_status NOT IN ('DRAFT', 'CANCELLED') THEN COALESCE(po.sent_at, po.last_modified_at, NOW()) END,
                CASE WHEN new_status NOT IN ('DRAFT', 'CANCELLED') THEN approver END,
                CASE WHEN new_status IN ('CONFIRMED', 'PARTIALLY_RECEIVED', 'CLOSED')
                     THEN COALESCE(po.sent_at, po.last_modified_at, NOW()) END,
                CASE WHEN new_status IN ('CONFIRMED', 'PARTIALLY_RECEIVED', 'CLOSED') THEN importer END,
                CASE WHEN new_status = 'CLOSED' THEN COALESCE(po.receipt_completed_at, po.last_modified_at, NOW()) END,
                CASE WHEN new_status = 'CLOSED' THEN importer END,
                po.payment_term_days, po.lead_time_days, po.supplier_confirmation_status,
                CASE WHEN po.supplier_confirmation_status IN ('CONFIRMED', 'REJECTED')
                     THEN COALESCE(po.supplier_responded_at, po.last_modified_at, NOW()) END,
                po.supplier_reference, po.supplier_response_note,
                'Carried over from the legacy purchase order table', revision,
                COALESCE(po.created_at, NOW()), 'legacy:' || COALESCE(po.created_by, 'import'));

        INSERT INTO procurement.purchase_order_lines
               (id, po_id, line_no, inventory_item_id, uom, ordered_qty, unit_price, tax_rate, discount_rate,
                line_subtotal, line_discount_amount, line_net, line_tax, line_total, status, note, created_at, created_by)
        SELECT l.id, po.id, ROW_NUMBER() OVER (ORDER BY l.created_at, l.id), i.id, i.unit_of_measure,
               l.quantity_ordered, l.unit_price, 0, 0,
               round(l.unit_price * l.quantity_ordered, 2), 0, round(l.unit_price * l.quantity_ordered, 2), 0,
               round(l.unit_price * l.quantity_ordered, 2),
               CASE WHEN po.status = 'CANCELLED' THEN 'CANCELLED'
                    WHEN po.status IN ('CLOSED', 'CLOSED_SHORT') THEN 'CLOSED'
                    WHEN l.quantity_received >= l.quantity_ordered THEN 'RECEIVED'
                    WHEN l.quantity_received > 0 THEN 'PARTIALLY_RECEIVED'
                    ELSE 'OPEN' END,
               left(l.description, 255), COALESCE(l.created_at, NOW()), 'legacy:import'
          FROM procurement.po_line l
          JOIN inventory.inventory_items i ON i.sku = l.sku
         WHERE l.purchase_order_id = po.id;

        IF revision IS NOT NULL THEN
            INSERT INTO procurement.purchase_order_revisions
                   (id, po_id, revision_no, kind, snapshot_header, changed_at, changed_by, created_at, created_by)
            VALUES (revision, po.id, 0, 'INITIAL',
                    jsonb_build_object('supplierId', po.supplier_id, 'warehouseId', wh, 'currency', po.currency,
                                       'expectedDate', po.expected_at, 'subtotal', subtotal, 'totalAmount', subtotal,
                                       'paymentTermDays', po.payment_term_days, 'legacyStatus', po.status),
                    COALESCE(po.created_at, NOW()), submitter, NOW(), 'legacy:import');
            INSERT INTO procurement.purchase_order_line_revisions
                   (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty, unit_price, tax_rate,
                    discount_rate, line_subtotal, line_discount_amount, line_net, line_tax, line_total, note)
            SELECT gen_random_uuid(), l.id, revision, l.inventory_item_id, l.uom, l.ordered_qty, l.unit_price, 0, 0,
                   l.line_subtotal, 0, l.line_net, 0, l.line_total, l.note
              FROM procurement.purchase_order_lines l WHERE l.po_id = po.id;
            UPDATE procurement.purchase_order_lines SET po_revision_id = revision WHERE po_id = po.id;
            IF new_status NOT IN ('DRAFT', 'CANCELLED') THEN
                INSERT INTO procurement.purchase_order_approvals
                       (id, po_revision_id, step_no, approver_id, decision, decision_at, reason, created_at, created_by)
                VALUES (gen_random_uuid(), revision, 1, approver, 'APPROVED',
                        COALESCE(po.sent_at, po.last_modified_at, NOW()),
                        'Approved before the move to the new purchasing tables; approver not recorded',
                        NOW(), 'legacy:import');
            END IF;
        END IF;

        INSERT INTO procurement.purchase_order_events
               (id, po_id, po_revision_id, action, actor_id, from_status, to_status, reason, payload, created_at,
                created_by)
        VALUES (gen_random_uuid(), po.id, revision, 'CREATED', submitter, NULL, new_status,
                'Carried over from the legacy purchase order table',
                jsonb_build_object('legacyStatus', po.status,
                                   'legacyReceived', (SELECT jsonb_object_agg(l.id, l.quantity_received)
                                                        FROM procurement.po_line l WHERE l.purchase_order_id = po.id)),
                NOW(), 'legacy:import');
        revision := NULL;
        copied := copied + 1;
    END LOOP;
    RAISE NOTICE 'legacy purchase orders carried over: %', copied;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 5. Supplier invoices
-- ---------------------------------------------------------------------------------------------
INSERT INTO procurement.supplier_invoices (id, invoice_number, supplier_id, po_id, status, currency, invoice_date,
                                           subtotal, tax_total, total_amount, created_at, created_by)
SELECT si.id, si.invoice_number, si.supplier_id,
       CASE WHEN EXISTS (SELECT 1 FROM procurement.purchase_orders p
                          WHERE p.id = si.purchase_order_id AND p.supplier_id = si.supplier_id)
            THEN si.purchase_order_id END,
       si.status, si.currency, (COALESCE(si.created_at, NOW()) AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
       si.amount, 0, si.amount, COALESCE(si.created_at, NOW()), 'legacy:' || COALESCE(si.created_by, 'import')
  FROM procurement.supplier_invoice si
 WHERE EXISTS (SELECT 1 FROM procurement.suppliers s WHERE s.id = si.supplier_id)
   AND NOT EXISTS (SELECT 1 FROM procurement.supplier_invoices n WHERE n.id = si.id)
   AND NOT EXISTS (SELECT 1 FROM procurement.supplier_invoices n
                    WHERE n.supplier_id = si.supplier_id AND n.invoice_number = si.invoice_number);


-- ---------------------------------------------------------------------------------------------
-- 6. Warehouse
-- ---------------------------------------------------------------------------------------------

-- A putaway task the new model cannot place: no source line (C4), or a target in the old location
-- table (C2). Archived above with the location; archived again here as a task, then removed or cut
-- loose from the old target.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'warehouse.putaway_task', t.id, to_jsonb(t) FROM warehouse.putaway_task t
 WHERE num_nonnulls(t.goods_receipt_line_id, t.transfer_order_line_id, t.return_request_line_id) <> 1
    OR (t.target_location_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location s WHERE s.id = t.target_location_id));
DELETE FROM warehouse.putaway_task
 WHERE num_nonnulls(goods_receipt_line_id, transfer_order_line_id, return_request_line_id) <> 1;
UPDATE warehouse.putaway_task t SET target_location_id = NULL
 WHERE t.target_location_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location s WHERE s.id = t.target_location_id);

-- An empty stock row at a location the map does not have holds nothing; it goes to the archive.
-- A row that still holds stock there is left for the C2 orphan check: those goods need a person.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_item', s.id, to_jsonb(s) FROM inventory.stock_item s
 WHERE s.on_hand = 0 AND s.reserved = 0
   AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location l WHERE l.location_code = s.location_code);
DELETE FROM inventory.stock_item s
 WHERE s.on_hand = 0 AND s.reserved = 0
   AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location l WHERE l.location_code = s.location_code)
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation r WHERE r.stock_item_id = s.id);

-- The map frame C2 makes mandatory, for a warehouse created before the map existed: the prefix from
-- its old code, the address from its old address line and city, a 100 m square map to draw on.
UPDATE warehouse.warehouse w
   SET prefix = CASE WHEN EXISTS (SELECT 1 FROM warehouse.warehouse o
                                   WHERE o.id <> w.id
                                     AND o.prefix = left(COALESCE(NULLIF(regexp_replace(upper(w.code), '[^A-Z0-9]', '', 'g'), ''), 'W'), 10))
                     THEN left(COALESCE(NULLIF(regexp_replace(upper(w.code), '[^A-Z0-9]', '', 'g'), ''), 'W'), 6)
                          || upper(left(replace(w.id::text, '-', ''), 4))
                     ELSE left(COALESCE(NULLIF(regexp_replace(upper(w.code), '[^A-Z0-9]', '', 'g'), ''), 'W'), 10) END
 WHERE w.prefix IS NULL;
UPDATE warehouse.warehouse
   SET address = COALESCE(NULLIF(concat_ws(', ', NULLIF(btrim(address_line), ''), NULLIF(btrim(city), '')), ''),
                          name)
 WHERE address IS NULL;
UPDATE warehouse.warehouse
   SET map_unit = 'M', map_width = 100, map_height = 100
 WHERE map_unit IS NULL;

DROP FUNCTION platform.legacy_unique_code(TEXT, INTEGER, UUID, BOOLEAN);
DROP FUNCTION platform.legacy_fit_code(TEXT, INTEGER);
