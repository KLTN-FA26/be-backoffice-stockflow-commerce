-- =============================================================================
-- Demo data, applied only under the "demo" Flyway location (application-local.yml, application-test.yml).
--
-- V20261111000110 puts every unit in stock into a whole carton of its SKU's carton unit, and stops on stock whose SKU
-- has none (kltn-docs 01 BR-03). The demo sofa and oak table (V20260901000500) are counted in pieces only, so they get
-- a carton unit here first: one piece to a carton, the way furniture leaves the maker, so none of their demo stock is
-- written off as a remainder.
--
-- The unit declares no size or weight on purpose: PackagingRules skips a packing unit with no size, so the sofa keeps
-- shipping as its two-box kit and every load estimate made of it is unchanged. Kits go in team plan 4C-7; the carton
-- gets its size when the outbound label needs one (4C-6).
-- =============================================================================

INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase,
                                       active, version, created_at, created_by, default_shipping)
SELECT md5('demo:unit:' || i.sku || ':CARTON')::uuid, i.id, 'CARTON', 1, FALSE, FALSE, TRUE, 0, NOW(), 'flyway', FALSE
  FROM inventory.inventory_items i
 WHERE i.sku IN ('SOFA-3S-GREY', 'TABLE-OAK-160')
ON CONFLICT (inventory_item_id, unit_of_measure) DO NOTHING;
