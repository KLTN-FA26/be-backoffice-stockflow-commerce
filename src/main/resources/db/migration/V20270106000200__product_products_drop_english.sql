-- The product is described in Vietnamese only (owner decision 2026-11-27): its English name and English description
-- go. The English name was required of every new or edited draft and of every submission (SCRUM-87); that rule goes
-- with it, and PRODUCT_DETAILS_REQUIRED now names the tax class alone.
--
-- Nothing else reads the columns: no view, trigger, SQL function or index names them, and the storefront never
-- showed either (its listing copies product.products.description, the Vietnamese one). The values are dropped with
-- the columns, no archive (QD-1: the VPS database is rebuilt on deploy).

ALTER TABLE product.products
    DROP COLUMN name_en,
    DROP COLUMN description_en;
