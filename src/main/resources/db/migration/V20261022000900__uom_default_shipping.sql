-- =============================================================================
-- The default shipping unit of an inventory item.
-- Module: inventory
--
-- Load planning (SCRUM-287, design v2 section 3, decision LP2-2) needs one carton size per SKU to
-- estimate how a sales order loads on a truck before it is packed. The unit an item ships in is not
-- always the largest one it can be packed in (a pallet may be declared for storage only), so the item
-- can name it: inventory.uom_conversions.default_shipping, at most one per item. Without a mark the
-- packaging-spec resolver falls back to the largest active packing unit that has its full size and
-- weight, then to the item's own box, then to the item's unit.
--
-- Like default_purchase (V20261019000300) the mark may sit on the base unit too: "ship loose, as the
-- item's own box". A retired unit (active = false) cannot carry it, so a unit never leaves the planner
-- with a spec nobody will pack in. Existing rows get FALSE: nothing changes for them until someone marks
-- a unit, and the resolver's fallback chain then applies.
--
-- Size and weight stay as uom_conversions already stores them: centimetres (NUMERIC(10,2)) and kilograms
-- (NUMERIC(10,3)); the resolver converts to millimetres for the solver.
-- =============================================================================

ALTER TABLE inventory.uom_conversions
    ADD COLUMN default_shipping BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE inventory.uom_conversions
    ADD CONSTRAINT ck_uom_conversions_default_shipping CHECK (NOT default_shipping OR active);

CREATE UNIQUE INDEX uk_uom_conversions_default_shipping ON inventory.uom_conversions (inventory_item_id)
    WHERE default_shipping;
