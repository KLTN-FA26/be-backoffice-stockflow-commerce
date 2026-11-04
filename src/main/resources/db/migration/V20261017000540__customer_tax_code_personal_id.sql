-- =============================================================================
-- A household business's tax identifier is its representative's personal identification number
-- (question T1, settled from the official texts, 2026-10-12).
--
-- Since 01/07/2025 the 12-digit personal identification number (Luật Căn cước 26/2023/QH15 art. 12(1))
-- is used in place of the tax code of a household, a household business and an individual business
-- (Thông tư 86/2024/TT-BTC art. 5(5) and art. 38(2); Luật Quản lý thuế 38/2019/QH14 art. 35(7)). The
-- company rule is unchanged: 10 digits, or 10-3 for a dependent unit. Only the CHECK of
-- V20261017000100 is widened; every stored value already satisfies the new one.
-- =============================================================================

ALTER TABLE customer.customer DROP CONSTRAINT ck_customer_tax_code;
ALTER TABLE customer.customer ADD CONSTRAINT ck_customer_tax_code
    CHECK (tax_code IS NULL OR tax_code ~ '^([0-9]{10}(-[0-9]{3})?|[0-9]{12})$');
