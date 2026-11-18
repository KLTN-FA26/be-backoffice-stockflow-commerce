-- HĐ-7, owner's decision: the VAT reduction of Nghị định 174/2025/NĐ-CP. The goods sold here - paper and
-- plastic cups, packaging - are taxed 8% instead of 10% from 01/07/2025 to 31/12/2026, then 10% again.
--
-- One row of ordering.tax_rate_period (V20261120000200): STANDARD at 8.00 over those days, both included. REDUCED (5%)
-- is not reduced, and EXEMPT is not a rate anyone issues (an e-invoice line at 0% is refused, see
-- EInvoice.Warning.TAX_EXEMPT_NOT_SUPPORTED). ordering.tax_rate keeps the class's own rate, 10%: it is what holds outside
-- every period, and so what a quote dated from 2027 takes.
--
-- What reads the period, now that it holds a row: a quote copies onto its lines and fees the rate in force on the day
-- it is drawn up (TaxRates.onDay; until this change it read ordering.tax_rate alone, which V20261120000200's header
-- still says), and the order and the contract copy the quote's. An e-invoice takes the rate of its day - the day of
-- delivery, or the day the order's rest was closed for the shipping fee's rest - so a 2026 contract and its 2026
-- invoices agree at 8%, and goods of that contract delivered in 2027 are invoiced at 10% with the warning
-- TAX_RATE_DIFFERS_FROM_CONTRACT, for the accountant to look at.
--
-- Rows already written keep their rates: quotes, orders and contracts drawn up before this migration at 10% stay at 10%
-- (the customer agreed to them; a quote revised from now on takes 8%), and their 2026 invoices carry the warning above.
--
-- Idempotent and gentle: nothing is written when a STANDARD period already covers any of those days
-- (ex_tax_rate_period_overlap would refuse the row anyway) - a database where somebody entered the reduction by hand
-- keeps what they entered.

INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to, note, created_by)
SELECT gen_random_uuid(), 'STANDARD', 8.00, DATE '2025-07-01', DATE '2026-12-31',
       'Nghị định 174/2025/NĐ-CP: VAT reduced from 10% to 8%, 01/07/2025 - 31/12/2026', 'flyway'
 WHERE NOT EXISTS (SELECT 1 FROM ordering.tax_rate_period p
                    WHERE p.tax_class = 'STANDARD'
                      AND daterange(p.valid_from, p.valid_to, '[]') && daterange(DATE '2025-07-01', DATE '2026-12-31', '[]'));
