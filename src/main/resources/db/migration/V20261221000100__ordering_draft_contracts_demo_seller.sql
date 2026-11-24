-- =============================================================================
-- Draft contracts drawn up for the placeholder seller name the demo company that replaced it (2026-11-23, business
-- documents phase 1).
--
-- local, dev and deploy/docker-compose.yml no longer fall back to 'CÔNG TY TNHH STOCKFLOW', tax code '0000000000':
-- they carry a fictional company with a letterhead ('CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT'). A contract keeps the
-- seller it was drawn up with (ordering.sales_contract.seller_*), so on a server that ran with the old fallback a
-- quotation would now be headed by the new company while the draft contract of the same sale still named the old one.
--
-- Only DRAFT rows, and only rows that carry exactly the old placeholder: a draft has not been shown to the customer,
-- while a sent or signed contract keeps the seller it was sent with, as a real one would. No real company has the tax
-- code '0000000000', so a deployment that set its own SELLER_* matches nothing here. The same rule as
-- db/demo/V20261208000100, which did this for the seeded drafts when the placeholder last changed.
--
-- E-invoices are left alone: invoicing.einvoice freezes its seller once issued, and a draft invoice takes the seller
-- again when it is next drawn up.
-- =============================================================================

UPDATE ordering.sales_contract
SET seller_name                 = 'CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT',
    seller_tax_code             = '0312345678',
    seller_address              = 'Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ Chí Minh',
    seller_representative       = 'Trần Quốc An',
    seller_representative_title = 'Giám đốc',
    version                     = version + 1,
    last_modified_at            = NOW(),
    last_modified_by            = 'flyway'
WHERE status = 'DRAFT'
  AND seller_tax_code = '0000000000'
  AND seller_name IN ('CÔNG TY TNHH STOCKFLOW', 'DEMO STOCKFLOW CO LTD');
