-- =============================================================================
-- Demo: every seeded paper names the demo company An Phát (2026-11-23, business documents phase 1).
-- Demo only (db/demo).
--
-- The seeds drew their contracts up with 'DEMO STOCKFLOW CO LTD', '1 Demo Street' and their transfer cards with
-- 'DEMO BANK (not a real bank)'. The documents are now laid out as a real company's are - letterhead, logo, party
-- blocks - and the demo profiles carry a fictional company to fill them (application-local.yml, application-dev.yml):
-- CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT. A demo in which the quotation is headed by An Phát, the signed contract next
-- to it by "DEMO STOCKFLOW CO LTD" and the payment card by "DEMO BANK" reads as three companies.
--
-- 1. Seeded contracts, whatever their status. V20261208000100 rewrote only the drafts, on the ground that a sent
--    contract keeps the seller it was sent with - true of a real one, but these were never sent by anybody: they are
--    rows a seed inserted, with no stored file behind them. The draft ones that still carry the placeholder are
--    covered by db/migration/V20261221000100; this takes the rest, by the two names the seeds and that migration used.
-- 2. The two transfer cards of the demo conversation: the account An Phát's contracts name, with the VietQR payload
--    recomputed for it (EMVCo fields and CRC-16/CCITT-FALSE, as payment.api.VietQr writes them). The BIN stays
--    970000, which is no bank's: the code must not be payable by anyone who scans a demo screen.
--
-- All of it fictional: the tax code has only the shape of one, and the account number is made up.
-- =============================================================================

-- tg_sales_contract_transition refuses any change to a contract that is no longer a draft (14 BR-14). The rule is
-- about contracts people sent and signed; it is lifted for this one statement over rows a seed made, as
-- V20261203000500 and V20261206000100 lift append-only triggers over the rows they correct, and put back at once.
ALTER TABLE ordering.sales_contract DISABLE TRIGGER tg_sales_contract_transition;

UPDATE ordering.sales_contract
SET seller_name                 = 'CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT',
    seller_tax_code             = '0312345678',
    seller_address              = 'Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ Chí Minh',
    seller_representative       = 'Trần Quốc An',
    seller_representative_title = 'Giám đốc',
    version                     = version + 1,
    last_modified_at            = NOW(),
    last_modified_by            = 'flyway'
WHERE seller_tax_code = '0000000000'
  AND seller_name IN ('CÔNG TY TNHH STOCKFLOW', 'DEMO STOCKFLOW CO LTD');

-- The table's deferred constraint triggers have queued their checks for this update; they run now, so that nothing
-- is pending on the table when its trigger is put back (PostgreSQL refuses ALTER TABLE otherwise).
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE ordering.sales_contract ENABLE TRIGGER tg_sales_contract_transition;

UPDATE chat.message
SET card_payload = jsonb_set(jsonb_set(card_payload, '{bank}',
        '{"bankName":"Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank)","bankBin":"970000","accountNumber":"0071001234567","accountName":"CONG TY TNHH SAN XUAT BAO BI AN PHAT"}'::jsonb),
        '{vietQr}',
        to_jsonb('00020101021238570010A00000072701270006970000011300710012345670208QRIBFTTA53037045408150000005802VN62230819HD-DEMO-0001-1 DOT163042EB8'::text)),
    version      = version + 1
WHERE id = 'c4a70000-0000-4000-8000-000000000207'
  AND card_payload -> 'bank' ->> 'accountNumber' = '0000000000';

UPDATE chat.message
SET card_payload = jsonb_set(jsonb_set(card_payload, '{bank}',
        '{"bankName":"Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank)","bankBin":"970000","accountNumber":"0071001234567","accountName":"CONG TY TNHH SAN XUAT BAO BI AN PHAT"}'::jsonb),
        '{vietQr}',
        to_jsonb('00020101021238570010A00000072701270006970000011300710012345670208QRIBFTTA53037045408350000005802VN62230819HD-DEMO-0001-1 DOT26304780D'::text)),
    version      = version + 1
WHERE id = 'c4a70000-0000-4000-8000-000000000208'
  AND card_payload -> 'bank' ->> 'accountNumber' = '0000000000';
