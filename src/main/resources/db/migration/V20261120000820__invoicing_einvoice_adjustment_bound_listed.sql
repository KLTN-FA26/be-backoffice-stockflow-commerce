-- =============================================================================
-- E-invoices: the bound on reductions counts adjustments reached through a trade discount's list (legal review
-- WF05-E0, finding L3; TT 91 Điều 10 khoản 1 điểm b, khoản 5 điểm b).
--
--   * einvoice_adjustment_bound_check: every adjustment of the group the new one belongs to is measured against the
--     invoices it touches - the one it names and the ones it lists - together with every other adjustment that
--     touches nothing else.
-- =============================================================================

-- An invoice is not reduced below nothing (TT 91 Điều 10 khoản 1 điểm b). V20261120000500 measured a new adjustment
-- against the invoice it names and the ones it lists, but counted only the adjustments naming the same invoice: a
-- trade discount that listed an invoice was not counted when that invoice was adjusted again, nor was an adjustment of
-- a listed invoice counted against the trade discount, and the two together could take an invoice below zero.
--
-- An adjustment touches the invoice it names and every invoice its list names. A trade discount spread over several
-- invoices does not say how much falls on each, so the bound is stated over sets: for an adjustment X, the invoices X
-- touches must hold at least what X and every other adjustment touching only those invoices take off them. That is
-- checked for every adjustment of the group the new one is connected to (through the invoices they touch, in turn),
-- and for the group as a whole. Each of those is a condition no lawful set of adjustments breaks, so nothing lawful is
-- refused; without a list, it is exactly the old check. Checked when the transaction commits, once the list is
-- written. A list is written only with its draft (tg_einvoice_adjustment_listing_draft).
CREATE OR REPLACE FUNCTION invoicing.einvoice_adjustment_bound_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    adjustment invoicing.einvoice%ROWTYPE;
    breach     RECORD;
BEGIN
    SELECT * INTO adjustment FROM invoicing.einvoice WHERE id = NEW.id;
    IF NOT FOUND OR adjustment.type <> 'ADJUSTMENT' OR adjustment.status = 'VOIDED_DRAFT' THEN
        RETURN NULL;
    END IF;
    WITH RECURSIVE touches(adjustment_id, invoice_id) AS (
        SELECT a.id, a.original_einvoice_id FROM invoicing.einvoice a
         WHERE a.type = 'ADJUSTMENT' AND a.status <> 'VOIDED_DRAFT'
        UNION
        SELECT l.einvoice_id, l.listed_einvoice_id
          FROM invoicing.einvoice_adjustment_listing l
          JOIN invoicing.einvoice a ON a.id = l.einvoice_id
         WHERE a.status <> 'VOIDED_DRAFT'
    ), grouped(adjustment_id, invoice_id) AS (
        SELECT adjustment_id, invoice_id FROM touches WHERE adjustment_id = adjustment.id
        UNION
        SELECT t.adjustment_id, t.invoice_id
          FROM touches t JOIN grouped g ON t.adjustment_id = g.adjustment_id OR t.invoice_id = g.invoice_id
    ), members AS (
        SELECT DISTINCT adjustment_id FROM grouped
    ), sets(anchor, invoice_id) AS (
        -- one set per adjustment of the group, and the whole group under a NULL anchor
        SELECT adjustment_id, invoice_id FROM grouped
        UNION
        SELECT NULL::uuid, invoice_id FROM grouped
    ), anchors AS (
        SELECT DISTINCT anchor FROM sets
    ), inside(anchor, adjustment_id) AS (
        -- the adjustments that touch no invoice outside the anchor's set
        SELECT x.anchor, m.adjustment_id
          FROM anchors x CROSS JOIN members m
         WHERE NOT EXISTS (SELECT 1 FROM grouped t
                            WHERE t.adjustment_id = m.adjustment_id
                              AND NOT EXISTS (SELECT 1 FROM sets s
                                               WHERE s.anchor IS NOT DISTINCT FROM x.anchor
                                                 AND s.invoice_id = t.invoice_id))
    ), measured AS (
        SELECT x.anchor,
               (SELECT COALESCE(sum(e.grand_total), 0) FROM invoicing.einvoice e
                 WHERE e.id IN (SELECT s.invoice_id FROM sets s WHERE s.anchor IS NOT DISTINCT FROM x.anchor))
                   AS covered,
               (SELECT COALESCE(sum(e.grand_total), 0) FROM invoicing.einvoice e
                 WHERE e.id IN (SELECT i.adjustment_id FROM inside i WHERE i.anchor IS NOT DISTINCT FROM x.anchor))
                   AS adjusted
          FROM anchors x
    )
    SELECT covered, adjusted INTO breach FROM measured WHERE covered + adjusted < 0 LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'e-invoice % reduces the invoices it adjusts by more than they hold (% against %)',
            adjustment.code, breach.adjusted, breach.covered
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_adjustment_bound';
    END IF;
    RETURN NULL;
END
$$;
