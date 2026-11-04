-- =============================================================================
-- Hotfix for issue #68: the admin product API writes the legacy tables (product.product,
-- product.category) while publication, the catalog projection, inventory control and variants live
-- on the PIM tables (product.products, product.categories, product.product_categories). A product
-- created through POST /api/v1/products was therefore invisible to everything after it.
--
-- Until the product module itself moves onto the PIM tables (issue #68, SCRUM-44), every write to a
-- legacy row is copied to the PIM row with the SAME id, in the same transaction, by the triggers
-- below; existing rows are copied once at the end. Nothing in the application changes.
--
--   * legacy category  -> product.categories (path and depth from the parent, slug from the code)
--   * legacy product   -> product.products, and its category -> the primary product_categories row
--
-- What the copy does NOT do, on purpose:
--   * status: PIM decides publication. A legacy APPROVED never takes a PIM PUBLISHED back to APPROVED;
--     DISCONTINUED, DRAFT, PENDING_APPROVAL and APPROVED are copied as they are.
--   * logistics (weight, dimensions, storage class...) belong to inventory.inventory_items (docs 01
--     BR-08), images and galleries to product.media on a variant (decision D5): not copied.
--   * a legacy code the PIM rules refuse (lower case is upper-cased; longer than 50 characters or
--     other characters) is not copied: the row stays legacy-only, with a WARNING, rather than
--     failing the admin request.
--
-- Contract C1 drops the legacy tables, and these triggers with them.
-- =============================================================================


-- A slug from a code: lower case, every run of other characters one hyphen, no hyphen at either end.
CREATE FUNCTION product.slug_of(code TEXT)
    RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS
$$
SELECT trim(BOTH '-' FROM regexp_replace(lower(code), '[^a-z0-9]+', '-', 'g'))
$$;


CREATE FUNCTION product.bridge_category_to_pim()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    code_upper TEXT := upper(NEW.code);
    parent     product.categories%ROWTYPE;
    the_slug   TEXT := product.slug_of(NEW.code);
BEGIN
    IF code_upper !~ '^[A-Z0-9][A-Z0-9_-]{0,49}$' THEN
        RAISE WARNING 'category % not copied to product.categories: code does not fit the PIM rules', NEW.code;
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM product.categories c WHERE c.code = code_upper AND c.id <> NEW.id) THEN
        RAISE WARNING 'category % not copied: product.categories already has that code', NEW.code;
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM product.categories c WHERE c.slug = the_slug AND c.id <> NEW.id) THEN
        the_slug := the_slug || '-' || left(replace(NEW.id::text, '-', ''), 8);
    END IF;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT * INTO parent FROM product.categories WHERE id = NEW.parent_id;
        IF NOT FOUND THEN
            RAISE WARNING 'category % not copied: its parent is not in product.categories', NEW.code;
            RETURN NEW;
        END IF;
    END IF;

    INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, created_at, created_by)
    VALUES (NEW.id, NEW.parent_id, code_upper, NEW.name, the_slug,
            CASE WHEN NEW.parent_id IS NULL THEN '/' || code_upper ELSE parent.path || '/' || code_upper END,
            CASE WHEN NEW.parent_id IS NULL THEN 0 ELSE parent.depth + 1 END,
            COALESCE(NEW.created_at, NOW()), 'bridge:' || COALESCE(NEW.created_by, 'legacy'))
    ON CONFLICT (id) DO UPDATE
        SET name = EXCLUDED.name,
            last_modified_at = NOW(),
            last_modified_by = 'bridge:' || COALESCE(NEW.last_modified_by, 'legacy');
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_category_bridge_to_pim AFTER INSERT OR UPDATE ON product.category
    FOR EACH ROW EXECUTE FUNCTION product.bridge_category_to_pim();


CREATE FUNCTION product.bridge_product_to_pim()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    code_upper TEXT := upper(NEW.code);
    the_slug   TEXT := product.slug_of(NEW.code);
    current    product.products%ROWTYPE;
    new_status TEXT := NEW.status;
BEGIN
    IF code_upper !~ '^[A-Z0-9][A-Z0-9_-]{0,49}$' THEN
        RAISE WARNING 'product % not copied to product.products: code does not fit the PIM rules', NEW.code;
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM product.products p WHERE p.code = code_upper AND p.id <> NEW.id) THEN
        RAISE WARNING 'product % not copied: product.products already has that code', NEW.code;
        RETURN NEW;
    END IF;

    SELECT * INTO current FROM product.products WHERE id = NEW.id;
    IF FOUND THEN
        -- Publication is decided on the PIM side; the legacy approval never takes it back.
        IF current.status = 'PUBLISHED' AND NEW.status = 'APPROVED' THEN
            new_status := 'PUBLISHED';
        END IF;
        UPDATE product.products
           SET name = NEW.name, name_en = NEW.name_en, description = NEW.description,
               description_en = NEW.description_en, tax_class = NEW.tax_class,
               kind = CASE WHEN NEW.customizable THEN 'CUSTOMIZABLE' ELSE 'STANDARD' END,
               brand_id = (SELECT b.id FROM product.brands b WHERE lower(b.name) = lower(NEW.brand) LIMIT 1),
               status = new_status, rejection_reason = NEW.rejection_reason,
               submitted_by = NEW.submitted_by, submitted_at = NEW.submitted_at,
               approved_by = NEW.approved_by, approved_at = NEW.approved_at,
               published_at = CASE WHEN new_status = 'PUBLISHED' THEN COALESCE(current.published_at, NOW())
                                   ELSE current.published_at END,
               discontinued_at = CASE WHEN new_status = 'DISCONTINUED' THEN COALESCE(current.discontinued_at, NOW())
                                      ELSE NULL END,
               last_modified_at = NOW(),
               last_modified_by = 'bridge:' || COALESCE(NEW.last_modified_by, 'legacy')
         WHERE id = NEW.id;
    ELSE
        IF EXISTS (SELECT 1 FROM product.products p WHERE p.slug = the_slug) THEN
            the_slug := the_slug || '-' || left(replace(NEW.id::text, '-', ''), 8);
        END IF;
        INSERT INTO product.products (id, code, name, name_en, slug, description, description_en, tax_class, kind,
                                      brand_id, status, rejection_reason, submitted_by, submitted_at, approved_by,
                                      approved_at, published_at, discontinued_at, created_at, created_by)
        VALUES (NEW.id, code_upper, NEW.name, NEW.name_en, the_slug, NEW.description, NEW.description_en,
                NEW.tax_class, CASE WHEN NEW.customizable THEN 'CUSTOMIZABLE' ELSE 'STANDARD' END,
                (SELECT b.id FROM product.brands b WHERE lower(b.name) = lower(NEW.brand) LIMIT 1),
                NEW.status, NEW.rejection_reason, NEW.submitted_by, NEW.submitted_at, NEW.approved_by,
                NEW.approved_at,
                CASE WHEN NEW.status = 'PUBLISHED' THEN NOW() END,
                CASE WHEN NEW.status = 'DISCONTINUED' THEN NOW() END,
                COALESCE(NEW.created_at, NOW()), 'bridge:' || COALESCE(NEW.created_by, 'legacy'));
    END IF;

    -- The category, as the primary one; only when the PIM side knows it.
    IF NEW.category_id IS NULL THEN
        DELETE FROM product.product_categories WHERE product_id = NEW.id AND is_primary;
    ELSIF EXISTS (SELECT 1 FROM product.categories c WHERE c.id = NEW.category_id) THEN
        DELETE FROM product.product_categories
         WHERE product_id = NEW.id AND is_primary AND category_id <> NEW.category_id;
        INSERT INTO product.product_categories (id, product_id, category_id, is_primary, created_at, created_by)
        VALUES (gen_random_uuid(), NEW.id, NEW.category_id, TRUE, NOW(), 'bridge')
        ON CONFLICT (product_id, category_id) DO UPDATE SET is_primary = TRUE;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_product_bridge_to_pim AFTER INSERT OR UPDATE ON product.product
    FOR EACH ROW EXECUTE FUNCTION product.bridge_product_to_pim();


-- Copy what exists already: categories parents first, then products. A no-op UPDATE runs each row's
-- trigger; the legacy row itself does not change.
DO
$$
DECLARE
    r RECORD;
BEGIN
    FOR r IN WITH RECURSIVE tree AS (
                 SELECT id, 0 AS level FROM product.category WHERE parent_id IS NULL
                 UNION ALL
                 SELECT c.id, t.level + 1 FROM product.category c JOIN tree t ON c.parent_id = t.id)
             SELECT id FROM tree ORDER BY level LOOP
        UPDATE product.category SET name = name WHERE id = r.id;
    END LOOP;
    FOR r IN SELECT id FROM product.product ORDER BY created_at LOOP
        UPDATE product.product SET name = name WHERE id = r.id;
    END LOOP;
END
$$;
