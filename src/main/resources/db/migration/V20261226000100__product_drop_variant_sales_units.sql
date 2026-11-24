-- kltn-docs 01 §3 "Đơn vị bán", BR-11 (decided 2026-11-05): a SKU is sold by the carton of its carton specification,
-- which the system assigns; quotes, orders and contracts count cartons, priced per piece. product.variant_sales_units
-- (V20261019000400) listed "the units a variant sells in", from when a variant could sell in several. Since an item has
-- one active packing unit (01 BR-03, uk_uom_conversions_one_carton) the table could only say that same fact again:
--   - nothing in the system wrote it for a SKU made by a design's approval, so it was right only where a person kept it;
--   - no quote, order or contract read it - a line takes the item's active carton (ordering.check_line_carton_unit);
--   - a carton listed in it could not be retired, so replacing a carton specification (BR-10: declare the new unit,
--     retire the old one) first needed its sales unit deleted by hand.
-- The sales unit is now read: the item's active carton. The rows go with the table (no archive, QD-1); the history of
-- who listed what stays in product.product_change_log (change type SALES_UNIT, kept as a historical constant).

DROP TABLE product.variant_sales_units;
DROP FUNCTION product.check_sales_unit_of_item();
