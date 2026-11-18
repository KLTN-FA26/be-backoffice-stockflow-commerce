-- HĐ-2, owner's decision: an e-invoice still not issued past its deadline is announced again, once a day.
--
-- Until now the accountants were told once, a day ahead of the deadline (due_warned_at, EInvoiceIssueDue), and an
-- invoice left in draft, refused, or stuck at the provider was never mentioned again - while every day it stays so it
-- is later in law (NĐ 254 Phụ lục điểm 7: signed by the working day after its date). overdue_warned_at is when the
-- accountants were last reminded of it past its deadline; the sweep (EInvoiceJobs.warnOfDeadlines) reminds again once
-- the last message - the warning or a reminder - is a day old, and stops when the invoice is issued or voided.
--
-- Bookkeeping, not content: tg_einvoice_guard lets it change on any invoice that is not issued, and it is written with
-- the check in one UPDATE (EInvoiceRepositoryAdapter.markOverdueReminded), outside the aggregate and its version - a
-- reminder changes nothing the invoice says, and must not make the accountant's open screen stale. No backfill: an
-- invoice overdue today is reminded at the next sweep, which is what the column is for.

ALTER TABLE invoicing.einvoice ADD COLUMN overdue_warned_at TIMESTAMPTZ;

-- A reminder comes only after the warning ahead of the deadline, never before it.
ALTER TABLE invoicing.einvoice ADD CONSTRAINT ck_einvoice_overdue_warned
    CHECK (overdue_warned_at IS NULL OR due_warned_at IS NOT NULL);

COMMENT ON COLUMN invoicing.einvoice.overdue_warned_at IS
    'HĐ-2: when the accountants were last reminded that the invoice is past its deadline and not issued; once a day.';
