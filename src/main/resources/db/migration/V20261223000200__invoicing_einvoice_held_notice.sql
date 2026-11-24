-- =============================================================================
-- WF-05 audit r2 M-2: the accountants are told AT ONCE of an original the automatic issuing holds back.
--
-- Why. An original must be issued on its delivery day (decision Q-1; NĐ 254/2026/NĐ-CP Điều 9 khoản 1), and the
-- system issues it by itself (auto_issue, V20261219000400) - except a draft with a warning that needs a person (a VAT
-- rate other than the contract's, a product without a tax class: decision §1.5 step 5) or one the automatic issuing
-- tried and was refused for (no series for the year, ...). Until now the first word of either was the deadline warning
-- at issue_due_at - 4h, i.e. 20:00; issued the next morning it is "Lập trễ" and needs a reason. kltn-docs 21 §4.2
-- step 3: "Nháp không tự phát hành được -> hệ thống báo Kế toán ngay, nhắc lại trước hạn 4 giờ".
--
-- invoicing.einvoice_held_notice - "the accountants were told that this draft is held back for this reason". One row
-- per invoice and reason (the draft's blocking warnings as stored, or the error code the automatic issuing met),
-- written in the transaction that publishes EInvoiceNeedsAttention(AUTO_ISSUE_HELD): told once however often the
-- 30-second job finds the draft, and told again only for a new reason. Bookkeeping about a message, like
-- einvoice_stuck_notice (V20261120000600), not a column on the invoice: the invoice's row is the legal record.
-- =============================================================================

CREATE TABLE invoicing.einvoice_held_notice
(
    einvoice_id UUID         NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    notified_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_einvoice_held_notice PRIMARY KEY (einvoice_id, reason),
    CONSTRAINT fk_einvoice_held_notice_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT ck_einvoice_held_notice_reason CHECK (reason ~ '^[A-Z0-9_,]{1,200}$')
);

COMMENT ON TABLE invoicing.einvoice_held_notice IS
    'WF-05 audit r2 M-2: the accountants were told that the automatic issuing holds this draft back for this reason (its blocking warnings, or the error code it was refused for) - once per invoice and reason.';
