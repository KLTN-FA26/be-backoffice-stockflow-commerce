-- =============================================================================
-- WF-05 Q-1 (the owner's fallback): an original is issued on the day of its delivery, by the system, and an invoice
-- issued later carries the day it was issued and says how late it was.
--
-- Why. SePay takes only today's date, for every kind of invoice (sandbox T-1: an original dated yesterday is refused
-- at once with ISSUED_DATE_INVALID and nothing is created; T-6 the same for an adjustment), and the law gives this
-- system no next working day either (NĐ 254/2026 Điều 9 khoản 5 / Phụ lục điểm 7 is for night sales without automatic
-- issuing; owner, 23/11/2026), so the original must leave on the delivery day - the automatic issuing, which was off - and one that leaves later is dated the day it leaves (EInvoice
-- re-dates it at submit) and flagged ISSUED_LATE, never re-dated by changing the delivery (Luật Kế toán Điều 13).
--
-- What this does:
--   1. einvoice.delivered_on: the day of the delivery an original invoices (for the rest of a shipping fee, the day the
--      order's rest was closed), copied when it is drawn up and never changed (tg_einvoice_guard). Its issue_date is
--      that day until it is submitted; re-dated then, the two differ by the days it was late. Null on corrections, and
--      on rows from before (read as "not late"; Q-13 rebuilds the database anyway).
--   2. ck_einvoice_issued_late: an original's ISSUED_LATE warning says exactly that its issue date is after its
--      delivery day - the aggregate's rule stated again for a manual UPDATE.
--   3. SePay's automatic issuing is on by default (decision Q-22, BR-02 row): the administrator still turns it off
--      (PUT /api/v1/einvoice-settings/providers/SEPAY_EINVOICE/auto-issue) when something is wrong. A value written
--      here outlives every rebuild of the database, which a click on a screen does not.
-- =============================================================================

ALTER TABLE invoicing.einvoice ADD COLUMN delivered_on DATE;

ALTER TABLE invoicing.einvoice
    ADD CONSTRAINT ck_einvoice_delivered_on CHECK (
        delivered_on IS NULL OR (type = 'ORIGINAL' AND delivered_on <= issue_date)),
    ADD CONSTRAINT ck_einvoice_issued_late CHECK (
        delivered_on IS NULL
        OR (delivered_on < issue_date) = (COALESCE(warnings, '') LIKE '%ISSUED_LATE%'));

UPDATE invoicing.einvoice_provider_config SET auto_issue = TRUE WHERE provider = 'SEPAY_EINVOICE';

COMMENT ON COLUMN invoicing.einvoice.delivered_on IS
    'WF-05 Q-1: the day the delivery this original invoices was made (for a shipping fee rest, the day the order''s rest was closed). issue_date is the day it was issued: later than this when it was issued late (warning ISSUED_LATE). Null on corrections and on rows from before.';
