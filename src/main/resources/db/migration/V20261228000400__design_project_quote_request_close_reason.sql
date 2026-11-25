-- =============================================================================
-- A project quote request waits for its whole set to be approved (kltn-docs 12 BR-25, 2026-11-25): a returned design
-- the customer never fixes would leave it open for ever, so the sales staff close it - and say why (BR-25.3). The
-- customer reads the reason on the request and on its chat card.
--
-- close_reason: only on a request a salesperson closed (closed_by set): one the customer emptied, or one whose
-- designs were all deleted, closes with no reason, by nobody (BR-25.4, BR-25.5); one answered by a quote closes with
-- none either - the quote is the answer. Never blank.
--
-- The lines (design_project_quote_request_line) are unchanged: a line may now name a design waiting for review or
-- returned, not only an approved one; that is the service's rule, not the table's.
-- =============================================================================

ALTER TABLE design.design_project_quote_request
    ADD COLUMN close_reason character varying(1000);

ALTER TABLE design.design_project_quote_request
    ADD CONSTRAINT ck_design_project_quote_request_close_reason
        CHECK (close_reason IS NULL
               OR ((status)::text = 'CLOSED' AND closed_by IS NOT NULL AND btrim((close_reason)::text) <> ''));

COMMENT ON COLUMN design.design_project_quote_request.close_reason IS
    'Why a salesperson closed it without a quote (12 BR-25.3); null otherwise. Set only on a CLOSED request with '
    'closed_by (ck_design_project_quote_request_close_reason).';
