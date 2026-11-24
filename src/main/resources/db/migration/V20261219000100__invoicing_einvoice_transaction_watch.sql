-- =============================================================================
-- WF-05 BE-07 (decision Q-5): a transaction let go without the provider's word is watched for 30 days.
--
-- Why. An invoice taken back from the provider (withdrawn: the provider had no record of it, or SePay's support wrote
-- that its result cannot be confirmed) or recorded refused on the provider's processing report alone (SePay's tracking
-- "Failed") is reopened and issued again under a NEW transaction. Nothing ever asked about the OLD one again, so an
-- old transaction the provider issued after all - late, or because the refusal was its words and not its look-up -
-- was a second invoice in law for the same delivery that nobody knew of (NĐ 254/2026 Phụ lục điểm 2b: a number is
-- used once; SePay's error handling page: resending an unclear request may duplicate it).
--
-- What this does: one row per (invoice, transaction) let go that way, written in the transaction that records the
-- refusal (EInvoiceIssuanceSteps.rejected). A daily job (EInvoiceJobs.watchLetGoTransactions) asks the provider about
-- each open row until watch_until; one the provider shows ISSUED is recorded here - the number, series and tax
-- authority code it holds - and the accountants are told (EInvoiceNeedsAttention DUPLICATE_RISK), once.
-- No row is touched: Q-13 rebuilds every environment's database before the defence.
-- =============================================================================

CREATE TABLE invoicing.einvoice_transaction_watch
(
    einvoice_id              UUID        NOT NULL,
    transaction_id           VARCHAR(64) NOT NULL,
    provider                 VARCHAR(32) NOT NULL,
    -- The rejection code the transaction was let go under: a withdrawal, or a refusal the look-up did not stand behind.
    let_go_as                VARCHAR(64) NOT NULL,
    watch_until              TIMESTAMPTZ NOT NULL,
    last_asked_at            TIMESTAMPTZ,
    -- What the provider holds under the old transaction, once it showed it issued.
    found_at                 TIMESTAMPTZ,
    found_number             INTEGER,
    found_symbol             VARCHAR(16),
    found_template_code      VARCHAR(16),
    found_tax_authority_code VARCHAR(64),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_einvoice_transaction_watch PRIMARY KEY (einvoice_id, transaction_id),
    CONSTRAINT fk_einvoice_transaction_watch_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT ck_einvoice_transaction_watch_let_go CHECK (btrim(let_go_as) <> ''),
    CONSTRAINT ck_einvoice_transaction_watch_found CHECK (
        (found_at IS NULL AND found_number IS NULL)
        OR (found_at IS NOT NULL AND found_number BETWEEN 1 AND 99999999))
);

-- The daily sweep reads the rows still watched; a found or lapsed row is never asked about again.
CREATE INDEX ix_einvoice_transaction_watch_open ON invoicing.einvoice_transaction_watch (watch_until)
    WHERE found_at IS NULL;

COMMENT ON TABLE invoicing.einvoice_transaction_watch IS
    'WF-05 BE-07 (Q-5): a transaction of an e-invoice taken back from the provider, or refused on its processing report alone, asked about daily until watch_until; found_* is what the provider issued under it after all (accountants told: DUPLICATE_RISK).';
