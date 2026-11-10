-- =============================================================================
-- 5-9: every product is CUSTOMIZABLE (kltn-docs 01 BR-19, decided 2026-11-04).
--
-- Why. The warehouse holds only finished goods printed to a customer's design; nothing is sold to every customer as it
-- is. A product is a cup or a packaging that takes the customer's print, its variants are the molds a design is printed
-- on, and what is stocked, quoted and ordered is the customer's own SKU (01 §4.8, 13 BR-02). The kind is set by the
-- system, never chosen (01 §3 "Loại luôn là CUSTOMIZABLE"): the application stops accepting it from clients and always
-- writes CUSTOMIZABLE (Product.requireCustomizable); this states the same rule in the database.
--
-- Kept on purpose:
--   * the column product.products.kind and the API fields that show it (always CUSTOMIZABLE): a client reading it keeps
--     working, and the catalog and design checks that ask for a CUSTOMIZABLE product keep their meaning;
--   * the enum constant ProductKind.STANDARD, read-only, for history and old events.
--
-- Data: every STANDARD product becomes CUSTOMIZABLE, its version moved so an edit made from a page read before this
-- answers OPTIMISTIC_LOCK rather than passing over the change. That includes the two demo products (SOFA-3S,
-- TABLE-OAK), which the demo migrations insert earlier in the same run: no separate demo migration is needed. Their
-- variants stay as they are; what a variant of a CUSTOMIZABLE product may still carry (stock, prices) is the business
-- of 5-3 and of the regenerated seed (5-10).
--
-- Order: convert, then the default, then the CHECK, so no row is ever outside it.
-- =============================================================================

UPDATE product.products
SET kind = 'CUSTOMIZABLE', version = version + 1
WHERE kind <> 'CUSTOMIZABLE';

ALTER TABLE product.products ALTER COLUMN kind SET DEFAULT 'CUSTOMIZABLE';

ALTER TABLE product.products DROP CONSTRAINT ck_products_kind;
ALTER TABLE product.products ADD CONSTRAINT ck_products_kind CHECK (kind = 'CUSTOMIZABLE');
