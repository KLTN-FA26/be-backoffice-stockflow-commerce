-- Rich-text descriptions (owner decision 2026-11-27, ADR-0013). The back office writes descriptions with Tiptap, and the
-- application stores its HTML only once RichTextPolicy has cleaned it to a fixed allow-list.
--
--   1. product.products.description now holds that HTML (no change of type: it was already text).
--   2. product.variants.description: the variant's own description, the same HTML; null means "the product's".
--   The catalog keeps only the TEXT of the product's description in its listing (ProductPublication.read gives it
--   with the tags gone), because search_document indexes it: indexed HTML would make every product match "strong" or
--   "li". The storefront page reads the HTML from the product (ProductPublication.storefront): a published product's
--   description cannot change (it is edited only while DRAFT), so the live HTML is the published one.
--
-- A description is at most 100 000 characters, stated again here (RichTextPolicy.MAX_LENGTH).

ALTER TABLE product.variants
    ADD COLUMN description text,
    ADD CONSTRAINT ck_variants_description CHECK (description IS NULL OR length(description) BETWEEN 1 AND 100000);

COMMENT ON COLUMN product.variants.description IS
    'The variant''s own description: HTML cleaned by RichTextPolicy; null shows the product''s description';

