-- A customer's own SKU is its own table, and has an inventory item only once it is ordered (owner decisions Q4, Q5 of
-- 2026-11-26; plan 2026-11-26-thay-doi-thung-va-ton-kho, phase B).
--
-- Until now a customer's SKU was a row of product.variants told apart by three columns (owner_customer_id,
-- design_snapshot_id, source_variant_id), and every variant row - the catalogue's blanks included - got an inventory
-- item from tg_variant_inventory_item, so a blank that is never stocked had one and a customer who drew ten designs and
-- ordered one had ten. Now:
--
--   1. product.customer_skus holds the customer's SKUs, with the ids and codes they had (a chat card, a quote or an
--      order naming one still names it). variant_id is the catalogue variant the design was drawn on (its carton is
--      the SKU's, Q1); variant_id, design_snapshot_id, customer_id, product_id and the code never change. A code is
--      never also a catalogue variant's (tg_customer_skus_code_free, tg_variants_code_free).
--   2. Every foreign key naming a customer's SKU points at it: quote and order lines, their fee lines, customer prices,
--      return request lines, loose return receipt lines, the sales summary, inventory items and the chat context of
--      kind CUSTOMER_SKU (its column renamed customer_sku_id). The customer's rows leave product.variants with their
--      child rows; the three columns go with their constraints, index and trigger.
--   3. product.variants is the catalogue alone, so what the three columns told apart is the table: a standard price
--      and a carton are a variant's by their foreign key (tg_standard_price_variant, tg_uom_conversions_catalogue_variant
--      go), a customer price, a quote or order line and a chat context name the customer's SKU by theirs.
--   4. An inventory item is a customer's SKU's, made when Sales converts a quote into an order
--      (InventoryService.openItems, in the order's transaction). tg_variant_inventory_item goes; the catalogue's items
--      go, and so do the customer's items that nothing ordered, stocked or recorded.
--
-- Every SQL function still reading the dropped columns is rewritten or dropped here (Postgres does not track function
-- bodies): ordering.check_line_carton_unit, catalog.check_customer_price_own_sku, catalog.check_standard_price_variant,
-- chat.check_conversation_context_owner, inventory.check_carton_on_catalogue_variant, inventory.create_variant_item,
-- product.check_customer_sku, inventory.guard_item_sku_rename, product.lock_inventory_sku_rename; and
-- inventory.check_packing_unit_code / check_item_unit_code, now that an item is opened after its variant's cartons.

-- 0. What the move cannot decide about --------------------------------------------------------------------------------

-- A customer's price on a catalogue variant (counted in units, kept read-only since team plan 4B-2) has no SKU to move
-- to and no order priced from it: it goes, as removed features' leftovers go (CLAUDE.md, QD-1).
DELETE FROM catalog.customer_price p
 USING product.variants v
 WHERE v.sku = p.sku AND v.owner_customer_id IS NULL;

-- Every other row that names a customer's SKU must name one, and nothing that names the catalogue may name one: a row
-- that breaks either is a fact this migration cannot decide about, so it stops and names the rows rather than failing
-- on a bare foreign key half way through.
DO $$
DECLARE
    stray text;
BEGIN
    SELECT string_agg(t || ': ' || s, '; ' ORDER BY t, s) INTO stray FROM (
        SELECT DISTINCT 'ordering.quote_line' AS t, l.sku AS s FROM ordering.quote_line l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'ordering.order_line', l.sku FROM ordering.order_line l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'ordering.quote_fee_line', l.sku FROM ordering.quote_fee_line l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'ordering.order_fee_line', l.sku FROM ordering.order_fee_line l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'ordering.return_request_line', l.sku FROM ordering.return_request_line l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'procurement.goods_receipt_return_loose_lines', l.sku
          FROM procurement.goods_receipt_return_loose_lines l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'reporting.product_sales_summary', l.sku FROM reporting.product_sales_summary l
          JOIN product.variants v ON v.sku = l.sku WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'chat.conversation_context', v.sku FROM chat.conversation_context c
          JOIN product.variants v ON v.id = c.variant_id WHERE v.owner_customer_id IS NULL
        UNION SELECT DISTINCT 'catalog.catalog_entry', e.sku FROM catalog.catalog_entry e
          JOIN product.variants v ON v.sku = e.sku WHERE v.owner_customer_id IS NOT NULL
        UNION SELECT DISTINCT 'catalog.standard_price', p.sku FROM catalog.standard_price p
          JOIN product.variants v ON v.sku = p.sku WHERE v.owner_customer_id IS NOT NULL
        UNION SELECT DISTINCT 'design.design_draft', v.sku FROM design.design_draft d
          JOIN product.variants v ON v.id = d.variant_id WHERE v.owner_customer_id IS NOT NULL
    ) x;
    IF stray IS NOT NULL THEN
        RAISE EXCEPTION 'V20270105000100: rows name the wrong kind of SKU (a customer''s SKU expected in the first '
            'eight tables, a catalogue variant in the last three): %', stray;
    END IF;
END
$$;

-- 1. The table ---------------------------------------------------------------------------------------------------------

CREATE TABLE product.customer_skus (
    id                 uuid                     NOT NULL,
    product_id         uuid                     NOT NULL,
    variant_id         uuid                     NOT NULL,
    customer_id        uuid                     NOT NULL,
    design_snapshot_id uuid                     NOT NULL,
    sku                character varying(64)    NOT NULL,
    name               character varying(255)   NOT NULL,
    status             character varying(16)    DEFAULT 'ACTIVE' NOT NULL,
    obsoleted_at       timestamp with time zone,
    version            bigint                   DEFAULT 0 NOT NULL,
    created_at         timestamp with time zone DEFAULT now() NOT NULL,
    created_by         character varying(100),
    last_modified_at   timestamp with time zone,
    last_modified_by   character varying(100),
    CONSTRAINT pk_customer_skus PRIMARY KEY (id),
    CONSTRAINT uk_customer_skus_sku UNIQUE (sku),
    CONSTRAINT uk_customer_skus_design_snapshot UNIQUE (design_snapshot_id),
    CONSTRAINT ck_customer_skus_sku CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    CONSTRAINT ck_customer_skus_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'OBSOLETE')),
    CONSTRAINT ck_customer_skus_obsoleted CHECK ((status = 'OBSOLETE') = (obsoleted_at IS NOT NULL)),
    CONSTRAINT fk_customer_skus_product FOREIGN KEY (product_id) REFERENCES product.products(id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_skus_variant FOREIGN KEY (variant_id) REFERENCES product.variants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_skus_customer FOREIGN KEY (customer_id) REFERENCES customer.customer(id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_skus_design_snapshot FOREIGN KEY (design_snapshot_id) REFERENCES design.design_snapshot(id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_customer_skus_customer ON product.customer_skus (customer_id, product_id);
CREATE INDEX ix_customer_skus_variant ON product.customer_skus (variant_id);
CREATE INDEX ix_customer_skus_product_status ON product.customer_skus (product_id, status);

COMMENT ON TABLE product.customer_skus IS 'A customer''s own SKU (kltn-docs 12 §4.5, 01 §4.8; owner decision Q4 of 2026-11-26): issued when the Sales Manager approves the customer''s design, the only goods quoted, ordered, priced for a customer and stocked, seen by their owner alone (13 BR-02). Packed in its catalogue variant''s carton (Q1). Its own table since V20270105000100, with the ids and codes it had as a variant row.';
COMMENT ON COLUMN product.customer_skus.variant_id IS 'The catalogue variant (quy cách) the design was drawn on: whose carton the SKU is packed in and whose standard price a quote of it starts from. Never changes.';
COMMENT ON COLUMN product.customer_skus.design_snapshot_id IS 'The approved design snapshot the SKU is made to; one SKU per snapshot. Never changes.';
COMMENT ON COLUMN product.customer_skus.sku IS 'Product code - customer code - number (CUP12-0312345678-01). Never a catalogue variant''s code, never changes.';

-- Who and what a SKU is never changes: a quote, an order, a stocked carton and a chat card all name it.
CREATE TRIGGER tg_customer_skus_immutable BEFORE UPDATE ON product.customer_skus
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('product_id', 'variant_id', 'customer_id',
                                                            'design_snapshot_id', 'sku');

CREATE FUNCTION product.check_customer_sku_drawn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM product.variants v WHERE v.id = NEW.variant_id AND v.product_id = NEW.product_id) THEN
        RAISE EXCEPTION 'customer SKU %: it is drawn on a variant of its own product', NEW.sku
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_customer_skus_drawn';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_customer_skus_drawn BEFORE INSERT ON product.customer_skus
    FOR EACH ROW EXECUTE FUNCTION product.check_customer_sku_drawn();

-- One code names one thing: a customer's SKU and a catalogue variant never share a code. Both sides take the code's
-- lock first, so two transactions writing the same code to the two tables are serialised and the second one sees the
-- first.
CREATE FUNCTION product.check_sku_code_free() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.sku IS NOT DISTINCT FROM OLD.sku THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('sku-code:' || NEW.sku, 0));
    IF TG_TABLE_NAME = 'customer_skus' THEN
        IF EXISTS (SELECT 1 FROM product.variants v WHERE v.sku = NEW.sku) THEN
            RAISE EXCEPTION 'customer SKU %: the code is a catalogue variant''s', NEW.sku
                USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_customer_skus_code_free';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM product.customer_skus c WHERE c.sku = NEW.sku) THEN
        RAISE EXCEPTION 'variant %: the code is a customer''s SKU', NEW.sku
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_variants_code_free';
    END IF;
    RETURN NEW;
END
$$;

-- 2. The rows, with the ids and codes they had ------------------------------------------------------------------------

INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name, status,
                                   obsoleted_at, version, created_at, created_by, last_modified_at, last_modified_by)
SELECT v.id, v.product_id, v.source_variant_id, v.owner_customer_id, v.design_snapshot_id, v.sku, v.name,
       CASE v.status WHEN 'DRAFT' THEN 'BLOCKED' ELSE v.status END, v.obsoleted_at, v.version, v.created_at,
       v.created_by, v.last_modified_at, v.last_modified_by
  FROM product.variants v
 WHERE v.owner_customer_id IS NOT NULL;

-- The code rule is on from here; the rows copied above were unique in one table already.
CREATE TRIGGER tg_customer_skus_code_free BEFORE INSERT OR UPDATE OF sku ON product.customer_skus
    FOR EACH ROW EXECUTE FUNCTION product.check_sku_code_free();

-- 3. The foreign keys that name a customer's SKU ------------------------------------------------------------------

ALTER TABLE ordering.quote_line DROP CONSTRAINT fk_quote_line_customer_sku;
ALTER TABLE ordering.quote_line
    ADD CONSTRAINT fk_quote_line_customer_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku) ON DELETE RESTRICT;

ALTER TABLE ordering.order_line DROP CONSTRAINT fk_order_line_variant;
ALTER TABLE ordering.order_line
    ADD CONSTRAINT fk_order_line_customer_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku) ON DELETE RESTRICT;

ALTER TABLE ordering.quote_fee_line DROP CONSTRAINT fk_quote_fee_line_sku;
ALTER TABLE ordering.quote_fee_line
    ADD CONSTRAINT fk_quote_fee_line_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku) ON DELETE RESTRICT;

ALTER TABLE ordering.order_fee_line DROP CONSTRAINT fk_order_fee_line_sku;
ALTER TABLE ordering.order_fee_line
    ADD CONSTRAINT fk_order_fee_line_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku) ON DELETE RESTRICT;

ALTER TABLE catalog.customer_price DROP CONSTRAINT fk_customer_price_variant;
ALTER TABLE catalog.customer_price
    ADD CONSTRAINT fk_customer_price_customer_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku)
        ON DELETE RESTRICT;

ALTER TABLE ordering.return_request_line DROP CONSTRAINT fk_return_request_line_variant;
ALTER TABLE ordering.return_request_line
    ADD CONSTRAINT fk_return_request_line_customer_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku)
        ON DELETE RESTRICT;

ALTER TABLE procurement.goods_receipt_return_loose_lines DROP CONSTRAINT fk_goods_receipt_return_loose_lines_variant;
ALTER TABLE procurement.goods_receipt_return_loose_lines
    ADD CONSTRAINT fk_goods_receipt_return_loose_lines_customer_sku FOREIGN KEY (sku)
        REFERENCES product.customer_skus(sku) ON DELETE RESTRICT;

ALTER TABLE reporting.product_sales_summary DROP CONSTRAINT fk_product_sales_summary_variant;
ALTER TABLE reporting.product_sales_summary
    ADD CONSTRAINT fk_product_sales_summary_customer_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku)
        ON DELETE RESTRICT;

ALTER TABLE chat.conversation_context DROP CONSTRAINT fk_conversation_context_variant;
ALTER TABLE chat.conversation_context RENAME COLUMN variant_id TO customer_sku_id;
ALTER INDEX chat.ix_conversation_context_variant RENAME TO ix_conversation_context_customer_sku;
ALTER TABLE chat.conversation_context
    ADD CONSTRAINT fk_conversation_context_customer_sku FOREIGN KEY (customer_sku_id)
        REFERENCES product.customer_skus(id) ON DELETE RESTRICT;
COMMENT ON COLUMN chat.conversation_context.customer_sku_id IS 'The customer''s SKU a context of kind CUSTOMER_SKU names (renamed from variant_id, V20270105000100).';

-- 4. Inventory items: a customer's SKU's, made when it is ordered ------------------------------------------------

DROP TRIGGER tg_variant_inventory_item ON product.variants;
DROP FUNCTION inventory.create_variant_item();

ALTER TABLE inventory.inventory_items DROP CONSTRAINT fk_inventory_items_variant;

-- Kept: an item something points at (its stock, ledger, holds, counts, adjustments, picks, putaway and receipts - every
-- foreign key to inventory_items), or whose SKU an order names (the item is made when the order is). Everything
-- else goes: the catalogue's items, which never held stock, and the customer's SKUs never ordered.
DELETE FROM inventory.inventory_items i
 WHERE NOT EXISTS (SELECT 1 FROM ordering.order_line l WHERE l.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_package x WHERE x.inventory_item_id = i.id)
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_lot x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_movement x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_adjustment x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM inventory.cycle_count_line x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM fulfillment.pick_line x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM warehouse.putaway_task x WHERE x.sku = i.sku)
   AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines x WHERE x.inventory_item_id = i.id);

-- What is left must be a customer's SKU: a catalogue variant's item with stock would be a fact this migration cannot
-- decide about, so it stops rather than guess.
DO $$
DECLARE
    stray text;
BEGIN
    SELECT string_agg(i.sku, ', ' ORDER BY i.sku) INTO stray
      FROM inventory.inventory_items i
     WHERE NOT EXISTS (SELECT 1 FROM product.customer_skus c WHERE c.sku = i.sku);
    IF stray IS NOT NULL THEN
        RAISE EXCEPTION 'V20270105000100: inventory items of catalogue variants are still in use: %', stray;
    END IF;
END
$$;

ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT fk_inventory_items_customer_sku FOREIGN KEY (sku) REFERENCES product.customer_skus(sku)
        ON DELETE RESTRICT;

COMMENT ON TABLE inventory.inventory_items IS 'The inventory record of a customer''s SKU (owner decision Q5 of 2026-11-26): made when Sales converts a quote into an order naming it (InventoryService.openItems), never for a catalogue variant. Its variant_id is the SKU''s catalogue variant, whose carton it is packed in (V20270104000100, V20270105000100).';

-- The item's variant is its SKU's: what the cartons of the item are looked up by.
CREATE FUNCTION inventory.check_item_of_customer_sku() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM product.customer_skus c WHERE c.sku = NEW.sku AND c.variant_id = NEW.variant_id) THEN
        RAISE EXCEPTION 'inventory item %: it is packed as its SKU''s catalogue variant', NEW.sku
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_inventory_items_customer_sku';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_inventory_items_customer_sku BEFORE INSERT ON inventory.inventory_items
    FOR EACH ROW EXECUTE FUNCTION inventory.check_item_of_customer_sku();

-- An item made when the variant already has cartons must not take a carton's code for its pieces: the unit check now
-- runs on insert too.
DROP TRIGGER tg_inventory_items_unit_code ON inventory.inventory_items;

CREATE OR REPLACE FUNCTION inventory.check_item_unit_code() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.unit_of_measure IS NOT DISTINCT FROM OLD.unit_of_measure THEN
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM inventory.uom_conversions c
                WHERE c.variant_id = NEW.variant_id AND c.unit_of_measure = NEW.unit_of_measure) THEN
        RAISE EXCEPTION 'item %: a carton of its variant is already called %', NEW.sku, NEW.unit_of_measure
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_inventory_items_unit_code';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_inventory_items_unit_code BEFORE INSERT OR UPDATE OF unit_of_measure ON inventory.inventory_items
    FOR EACH ROW EXECUTE FUNCTION inventory.check_item_unit_code();

-- The other side of the same rule. A carton code was refused when an item of the variant counted its pieces in it, and
-- every variant had an item counted in EACH (the default); now a variant has no item until an order names one of its
-- customers' SKUs, and that item is opened in EACH. A carton called EACH would then make the first order of every SKU
-- of the variant fail, so the default unit is never a carton's code.
CREATE OR REPLACE FUNCTION inventory.check_packing_unit_code() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.unit_of_measure = 'EACH'
       OR EXISTS (SELECT 1 FROM inventory.inventory_items i
                   WHERE i.variant_id = NEW.variant_id AND i.unit_of_measure = NEW.unit_of_measure) THEN
        RAISE EXCEPTION 'carton %: that is the unit the pieces of its variant''s SKUs are counted in', NEW.unit_of_measure
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_uom_conversions_unit_code';
    END IF;
    RETURN NEW;
END
$$;

-- A customer's SKU never changes its code, so neither does its item: the rename a DRAFT variant once passed on to its
-- item through ON UPDATE CASCADE has nothing left to follow.
CREATE OR REPLACE FUNCTION inventory.guard_item_sku_rename() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.sku IS DISTINCT FROM OLD.sku THEN
        RAISE EXCEPTION 'inventory item %: its SKU never changes', OLD.sku
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_inventory_items_immutable';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_variants_inventory_rename ON product.variants;
DROP FUNCTION product.lock_inventory_sku_rename();

-- 5. The customer's rows leave product.variants ---------------------------------------------------------------------

DROP TRIGGER tg_variants_customer_sku ON product.variants;
DROP FUNCTION product.check_customer_sku();

-- Their child rows: options, images, templates and cartons cascade; the chat, catalog and design rows were checked
-- above or never name a customer's SKU (catalog_entry and design_draft name the catalogue).
DELETE FROM product.variants v WHERE v.owner_customer_id IS NOT NULL;

ALTER TABLE product.variants DROP CONSTRAINT ck_variants_customer_sku;
DROP INDEX product.uk_variants_design_snapshot;
DROP INDEX product.ix_variants_owner_customer;
ALTER TABLE product.variants DROP CONSTRAINT fk_variants_source_variant;
ALTER TABLE product.variants DROP CONSTRAINT fk_variants_owner_customer;
ALTER TABLE product.variants DROP CONSTRAINT fk_variants_design_snapshot;
ALTER TABLE product.variants DROP COLUMN owner_customer_id;
ALTER TABLE product.variants DROP COLUMN design_snapshot_id;
ALTER TABLE product.variants DROP COLUMN source_variant_id;

COMMENT ON TABLE product.variants IS 'The catalogue''s variants (quy cách) of a product: blanks a customer''s design is drawn on, each with its carton (inventory.uom_conversions) and standard price. Never stocked or ordered as they are: a customer''s SKU (product.customer_skus) is. Catalogue only since V20270105000100.';

CREATE TRIGGER tg_variants_code_free BEFORE INSERT OR UPDATE OF sku ON product.variants
    FOR EACH ROW EXECUTE FUNCTION product.check_sku_code_free();

-- 6. The checks that told a customer's SKU from a variant -------------------------------------------------------------

-- A carton is a catalogue variant's by its foreign key: product.variants holds nothing else now.
DROP TRIGGER tg_uom_conversions_catalogue_variant ON inventory.uom_conversions;
DROP FUNCTION inventory.check_carton_on_catalogue_variant();

-- A standard price is a catalogue variant's by its foreign key, for the same reason.
DROP TRIGGER tg_standard_price_variant ON catalog.standard_price;
DROP FUNCTION catalog.check_standard_price_variant();

CREATE OR REPLACE FUNCTION catalog.check_customer_price_own_sku() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM product.customer_skus c WHERE c.sku = NEW.sku AND c.customer_id = NEW.customer_id) THEN
        RAISE EXCEPTION 'customer price: % is not a SKU of customer % (13 BR-02, BR-05)', NEW.sku, NEW.customer_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION ordering.check_line_carton_unit() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unit      inventory.uom_conversions%ROWTYPE;
    variant   UUID;
    new_unit  BOOLEAN;
    owner     UUID;
    customer  UUID;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    -- A customer's SKU is packed in its variant's carton (Q1).
    SELECT c.variant_id, c.customer_id INTO variant, owner FROM product.customer_skus c WHERE c.sku = NEW.sku;
    IF variant IS DISTINCT FROM unit.variant_id THEN
        RAISE EXCEPTION '% line: unit % is not the carton of %', TG_TABLE_NAME, unit.unit_of_measure, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.units_per_carton <> unit.factor THEN
        RAISE EXCEPTION '% line: % holds % pieces, not %', TG_TABLE_NAME, unit.unit_of_measure, unit.factor,
            NEW.units_per_carton USING ERRCODE = 'check_violation';
    END IF;
    -- 14 BR-04: the customer's own SKU.
    IF TG_TABLE_NAME = 'quote_line' THEN
        SELECT q.customer_id INTO customer FROM ordering.quote q WHERE q.id = NEW.quote_id;
    ELSE
        SELECT o.customer_id INTO customer FROM ordering.customer_order o WHERE o.id = NEW.order_id;
    END IF;
    IF owner IS DISTINCT FROM customer THEN
        RAISE EXCEPTION '% line: % is not a SKU of customer % (14 BR-04)', TG_TABLE_NAME, NEW.sku, customer
            USING ERRCODE = 'check_violation';
    END IF;
    -- A new quote line takes an active unit; an order line copies the unit of its accepted quote line.
    IF TG_TABLE_NAME = 'order_line' THEN
        new_unit := FALSE;
    ELSIF TG_OP = 'INSERT' THEN
        new_unit := TRUE;
    ELSE
        new_unit := NEW.uom_conversion_id IS DISTINCT FROM OLD.uom_conversion_id;
    END IF;
    IF new_unit AND NOT unit.active THEN
        RAISE EXCEPTION '% line: unit % of % is retired', TG_TABLE_NAME, unit.unit_of_measure, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION chat.check_conversation_context_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    conversation_customer UUID;
    owner                 UUID;
    reference             UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.conversation_id, NEW.kind, NEW.product_id, NEW.customer_sku_id, NEW.design_id,
            NEW.design_project_id, NEW.quote_id, NEW.order_id, NEW.contract_id, NEW.production_order_id,
            NEW.einvoice_id, NEW.attached_by, NEW.attached_at)
        IS DISTINCT FROM (OLD.conversation_id, OLD.kind, OLD.product_id, OLD.customer_sku_id, OLD.design_id,
            OLD.design_project_id, OLD.quote_id, OLD.order_id, OLD.contract_id, OLD.production_order_id,
            OLD.einvoice_id, OLD.attached_by, OLD.attached_at) THEN
        RAISE EXCEPTION 'conversation context %: a context names one document of one conversation for good', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    -- A row without the reference of its kind (or of a kind the chat does not know) is ck_conversation_context_*'s to
    -- refuse, with its own name.
    reference := CASE NEW.kind
           WHEN 'PRODUCT' THEN NEW.product_id WHEN 'CUSTOMER_SKU' THEN NEW.customer_sku_id
           WHEN 'DESIGN' THEN NEW.design_id WHEN 'DESIGN_PROJECT' THEN NEW.design_project_id
           WHEN 'QUOTE' THEN NEW.quote_id WHEN 'ORDER' THEN NEW.order_id WHEN 'CONTRACT' THEN NEW.contract_id
           WHEN 'PRODUCTION_ORDER' THEN NEW.production_order_id WHEN 'EINVOICE' THEN NEW.einvoice_id END;
    IF reference IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT c.customer_id INTO conversation_customer FROM chat.conversation c WHERE c.id = NEW.conversation_id;
    owner := CASE NEW.kind
        WHEN 'PRODUCT' THEN conversation_customer
        WHEN 'CUSTOMER_SKU' THEN (SELECT s.customer_id FROM product.customer_skus s WHERE s.id = NEW.customer_sku_id)
        WHEN 'DESIGN' THEN (SELECT d.customer_id FROM design.design_draft d WHERE d.id = NEW.design_id)
        WHEN 'DESIGN_PROJECT' THEN (SELECT p.customer_id FROM design.design_project p
                                     WHERE p.id = NEW.design_project_id)
        WHEN 'QUOTE' THEN (SELECT q.customer_id FROM ordering.quote q WHERE q.id = NEW.quote_id)
        WHEN 'ORDER' THEN (SELECT o.customer_id FROM ordering.customer_order o WHERE o.id = NEW.order_id)
        WHEN 'CONTRACT' THEN (SELECT o.customer_id FROM ordering.sales_contract k
                                JOIN ordering.customer_order o ON o.id = k.order_id WHERE k.id = NEW.contract_id)
        WHEN 'PRODUCTION_ORDER' THEN (SELECT p.customer_id FROM ordering.production_order p
                                       WHERE p.id = NEW.production_order_id)
        WHEN 'EINVOICE' THEN (SELECT e.customer_id FROM invoicing.einvoice e WHERE e.id = NEW.einvoice_id)
    END;
    IF owner IS DISTINCT FROM conversation_customer THEN
        RAISE EXCEPTION 'conversation %: the % is not the conversation''s customer''s (16 BR-02)', NEW.conversation_id,
            NEW.kind USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
