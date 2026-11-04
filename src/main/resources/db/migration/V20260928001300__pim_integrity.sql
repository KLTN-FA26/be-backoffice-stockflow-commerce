-- =============================================================================
-- Product information management: cross-row rules, as triggers.
-- Module: product   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase P
--
-- The PIM tables are linked by single-column foreign keys only (team decision, QA notes §1). A
-- single-column key proves a row exists, not that it belongs to the right parent: an option of
-- product A could be attached to a variant of product B, a "colour" value could be offered on a
-- "size" axis. Each trigger below closes one such hole that an adversarial INSERT opened during the
-- catalog QA round. The aggregate enforces the same rules first; these are the backstop.
-- =============================================================================


-- Codes are inside URLs, paths and SKUs; a product, category or variant never changes its owner.
CREATE TRIGGER tg_categories_immutable BEFORE UPDATE ON product.categories
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('code');
CREATE TRIGGER tg_products_immutable BEFORE UPDATE ON product.products
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('code');
CREATE TRIGGER tg_product_attributes_immutable BEFORE UPDATE ON product.product_attributes
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('product_id', 'attribute_id', 'role');
CREATE TRIGGER tg_variants_immutable BEFORE UPDATE ON product.variants
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('product_id');


-- -----------------------------------------------------------------------------
-- 1. Category tree: depth and path follow the parent, and a category is never moved under its own
--    subtree (which would make a cycle the foreign key cannot see).
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_category_tree()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent product.categories%ROWTYPE;
BEGIN
    IF NEW.parent_id IS NULL THEN
        IF NEW.path <> '/' || NEW.code THEN
            RAISE EXCEPTION 'root category path must be /%', NEW.code USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO parent FROM product.categories WHERE id = NEW.parent_id;
    IF TG_OP = 'UPDATE' AND parent.path LIKE OLD.path || '/%' THEN
        RAISE EXCEPTION 'category % cannot move under its own subtree', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.depth <> parent.depth + 1 OR NEW.path <> parent.path || '/' || NEW.code THEN
        RAISE EXCEPTION 'category % must have depth % and path %/%',
            NEW.code, parent.depth + 1, parent.path, NEW.code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_categories_tree BEFORE INSERT OR UPDATE OF parent_id, path, depth, code
    ON product.categories FOR EACH ROW EXECUTE FUNCTION product.check_category_tree();


-- -----------------------------------------------------------------------------
-- 2. A variant axis must be an attribute with discrete values (SELECT or COLOR). A variant picks
--    exactly one option per axis, which is meaningless for free text or a MULTISELECT.
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_product_attribute()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.role = 'VARIANT_AXIS' AND NOT EXISTS (
        SELECT 1 FROM product.attributes a
         WHERE a.id = NEW.attribute_id AND a.data_type IN ('SELECT', 'COLOR')) THEN
        RAISE EXCEPTION 'a VARIANT_AXIS attribute must be of type SELECT or COLOR'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_product_attributes_axis BEFORE INSERT ON product.product_attributes
    FOR EACH ROW EXECUTE FUNCTION product.check_product_attribute();


-- -----------------------------------------------------------------------------
-- 3. An axis option is a value of that same attribute, on an attribute used as VARIANT_AXIS.
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_product_attribute_option()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pa product.product_attributes%ROWTYPE;
BEGIN
    SELECT * INTO pa FROM product.product_attributes WHERE id = NEW.product_attribute_id;
    IF pa.role <> 'VARIANT_AXIS' THEN
        RAISE EXCEPTION 'options exist only for VARIANT_AXIS attributes; use product_descriptive_values'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM product.attribute_values v
                    WHERE v.id = NEW.attribute_value_id AND v.attribute_id = pa.attribute_id) THEN
        RAISE EXCEPTION 'attribute value % does not belong to the attribute of this axis', NEW.attribute_value_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_product_attribute_options_check BEFORE INSERT OR UPDATE
    ON product.product_attribute_options
    FOR EACH ROW EXECUTE FUNCTION product.check_product_attribute_option();


-- -----------------------------------------------------------------------------
-- 4. A descriptive value matches the attribute's type: a dictionary value of the same attribute
--    for SELECT/MULTISELECT/COLOR, the matching scalar column otherwise; and only MULTISELECT may
--    hold several values.
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_descriptive_value()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pa        product.product_attributes%ROWTYPE;
    data_type TEXT;
BEGIN
    SELECT * INTO pa FROM product.product_attributes WHERE id = NEW.product_attribute_id;
    SELECT a.data_type INTO data_type FROM product.attributes a WHERE a.id = pa.attribute_id;

    IF pa.role <> 'DESCRIPTIVE' THEN
        RAISE EXCEPTION 'descriptive values exist only for DESCRIPTIVE attributes'
            USING ERRCODE = 'check_violation';
    END IF;

    IF data_type IN ('SELECT', 'MULTISELECT', 'COLOR') THEN
        IF NEW.attribute_value_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM product.attribute_values v
             WHERE v.id = NEW.attribute_value_id AND v.attribute_id = pa.attribute_id) THEN
            RAISE EXCEPTION 'a % attribute takes a value of its own dictionary', data_type
                USING ERRCODE = 'check_violation';
        END IF;
        IF data_type <> 'MULTISELECT' AND EXISTS (
            SELECT 1 FROM product.product_descriptive_values d
             WHERE d.product_attribute_id = NEW.product_attribute_id AND d.id <> NEW.id) THEN
            RAISE EXCEPTION 'a % attribute holds one value; only MULTISELECT holds several', data_type
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF (data_type = 'TEXT' AND NEW.value_text IS NULL)
       OR (data_type = 'NUMBER' AND NEW.value_number IS NULL)
       OR (data_type = 'BOOLEAN' AND NEW.value_boolean IS NULL) THEN
        RAISE EXCEPTION 'a % attribute takes its value in the matching value_* column', data_type
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_product_descriptive_values_check BEFORE INSERT OR UPDATE
    ON product.product_descriptive_values
    FOR EACH ROW EXECUTE FUNCTION product.check_descriptive_value();


-- -----------------------------------------------------------------------------
-- 5. A variant's option belongs to the variant's own product, and a variant picks at most one
--    option per axis. This is the rule the dropped composite key (QA case A1) used to carry.
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_variant_attribute_value()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    variant_product UUID;
    option_product  UUID;
    option_axis     UUID;
BEGIN
    SELECT v.product_id INTO variant_product FROM product.variants v WHERE v.id = NEW.variant_id;
    SELECT pa.product_id, pa.id INTO option_product, option_axis
      FROM product.product_attribute_options o
      JOIN product.product_attributes pa ON pa.id = o.product_attribute_id
     WHERE o.id = NEW.product_attribute_option_id;

    IF option_product IS DISTINCT FROM variant_product THEN
        RAISE EXCEPTION 'option % belongs to another product than variant %',
            NEW.product_attribute_option_id, NEW.variant_id USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM product.variant_attribute_values x
          JOIN product.product_attribute_options o ON o.id = x.product_attribute_option_id
         WHERE x.variant_id = NEW.variant_id AND x.id <> NEW.id
           AND o.product_attribute_id = option_axis) THEN
        RAISE EXCEPTION 'variant % already has an option on this axis', NEW.variant_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_variant_attribute_values_check BEFORE INSERT OR UPDATE
    ON product.variant_attribute_values
    FOR EACH ROW EXECUTE FUNCTION product.check_variant_attribute_value();


-- -----------------------------------------------------------------------------
-- 6. A SKU is frozen once the variant leaves DRAFT (docs 01, BR-01): stock, orders, purchase
--    orders and the warehouse all key on it.
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_variant_sku_frozen()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status <> 'DRAFT' AND NEW.sku IS DISTINCT FROM OLD.sku THEN
        RAISE EXCEPTION 'sku % cannot change after the variant left DRAFT', OLD.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_variants_sku_frozen BEFORE UPDATE OF sku ON product.variants
    FOR EACH ROW EXECUTE FUNCTION product.check_variant_sku_frozen();


-- -----------------------------------------------------------------------------
-- 7. Only a CUSTOMIZABLE product's variant can carry a print template.
-- -----------------------------------------------------------------------------
CREATE FUNCTION product.check_template_customizable()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM product.variants v JOIN product.products p ON p.id = v.product_id
         WHERE v.id = NEW.variant_id AND p.kind = 'CUSTOMIZABLE') THEN
        RAISE EXCEPTION 'customization templates belong to variants of CUSTOMIZABLE products only'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_customization_templates_kind BEFORE INSERT OR UPDATE OF variant_id
    ON product.customization_templates
    FOR EACH ROW EXECUTE FUNCTION product.check_template_customizable();
