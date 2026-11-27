-- WF-05 BE-49 (audit M-7): an installment overdue is announced to Sales once per due date. The nightly scan used to read
-- back only installments due in the last three days: one reached with a due date already further back (a delivery
-- recorded late, a milestone moved by a correction) was overdue at once and never announced, and one announced and then
-- given a new due date was never announced again. The scan now announces every installment overdue today that was not
-- announced for its current due date, and writes down the due date it announced.
--
-- Bookkeeping of the scan only: no money, no status. check_installment_transition does not list the column, so writing
-- it changes nothing else about the installment.

ALTER TABLE payment.installment ADD COLUMN overdue_announced_for DATE;

COMMENT ON COLUMN payment.installment.overdue_announced_for IS
    'The due date Sales were last told this installment was overdue for (WF-05 BE-49); null when never. The nightly '
    'scan announces an installment overdue today whose due_date differs from it.';

-- What the old scan would never announce again - due more than its three-day look-back ago - counts as announced, so the
-- first run after this does not tell Sales the whole history again. Only what still owes can be overdue; a CANCELLED
-- row is never written again (check_installment_transition).
UPDATE payment.installment
   SET overdue_announced_for = due_date
 WHERE due_date IS NOT NULL
   AND status IN ('DUE', 'PARTIALLY_PAID')
   AND due_date < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - 3;
