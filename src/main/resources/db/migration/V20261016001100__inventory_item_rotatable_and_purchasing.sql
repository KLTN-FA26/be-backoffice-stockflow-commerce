-- =============================================================================
-- Inventory item: the rotatable flag and the purchasing information.
-- Module: inventory
--
-- kltn-docs 01 §3 Input:
--   * "Cho phép xoay khi xếp xe (rotatable) | Inventory Item | ... Là giá trị mặc định cho cờ xoay
--     của kiện ở 08, dùng cho Load Planning ở 09 | Không (mặc định: cho phép xoay)" (E13).
--   * "Thông tin mua hàng | Inventory Item | Nhà cung cấp mặc định, giá mua tham chiếu, lead time,
--     MOQ | Không (khuyến nghị)". BR-06: the reference purchase price belongs to the Inventory Item
--     and is independent of the selling price. default_supplier_id already exists (V20260928003000).
--
-- These are the item's own reference values, which 02 §7 says a PO inherits as defaults; they are
-- not the supplier catalog (procurement.supplier_items), which holds what each supplier offers.
-- The price has its own currency: suppliers, and so purchase prices, are not all in VND.
-- =============================================================================

ALTER TABLE inventory.inventory_items
    ADD COLUMN rotatable                 BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN reference_purchase_price  NUMERIC(18, 2),
    ADD COLUMN reference_purchase_currency VARCHAR(3),
    ADD COLUMN lead_time_days            INTEGER,
    ADD COLUMN moq                       INTEGER;

ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT ck_inventory_items_reference_price CHECK (
        (reference_purchase_price IS NULL AND reference_purchase_currency IS NULL)
        OR (reference_purchase_price > 0 AND reference_purchase_currency ~ '^[A-Z]{3}$')),
    ADD CONSTRAINT ck_inventory_items_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0),
    ADD CONSTRAINT ck_inventory_items_moq CHECK (moq IS NULL OR moq >= 1);
