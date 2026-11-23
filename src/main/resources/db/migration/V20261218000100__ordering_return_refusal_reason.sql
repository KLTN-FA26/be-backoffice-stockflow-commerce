-- WF-05 Q-4 (owner decision; Luật Thương mại 2005 Điều 318, 319; TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c), BE-12:
-- goods that came back usable but are not refunded - damaged by the customer, no defect found, outside the policy - are
-- refused with a reason from a fixed list. Sales propose nothing (every line at 0 units) and say why; the accountant
-- approves it with the ordinary refund approval (sales-rma-resolutions:APPROVE, someone other than who proposed it), and
-- the return ends RESOLVED with nothing settled. Before this a return whose goods came back usable could end only by
-- refunding at least one unit, so it stayed RECEIVED and its order RETURNED for good.
--
--  1. refusal_reason: the code from the fixed list (ReturnRefusalReason); the customer sees its label from the _vi
--     bundle. refusal_note: an optional public note shown with it (the internal note stays resolution_note). Both only
--     on a proposal or a resolution (SETTLEMENT_PENDING, RESOLVED); a refund sent back clears them. OTHER needs the note.
--  2. ck_return_request_four_eyes: a settlement of 0 is still closed by one person when nothing approved came back usable
--     (closeWithoutRefund, WF-05 TH-4) - but a refusal, which withholds goods that came back usable, never is: it decides
--     money, so it is approved by a second person like any refund.
--  The rule "every refundable unit withheld => a refusal reason" is checked again in check_return_request_refund
--  (V20261218000700), which already reads the lines.

ALTER TABLE ordering.return_request ADD COLUMN refusal_reason varchar(24);
ALTER TABLE ordering.return_request ADD COLUMN refusal_note varchar(500);

ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_refusal_reason CHECK (refusal_reason IS NULL
        OR refusal_reason IN ('CUSTOMER_DAMAGE', 'NOT_DEFECTIVE', 'OUT_OF_POLICY', 'OTHER'));

ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_refusal_stage CHECK (refusal_reason IS NULL
        OR status IN ('SETTLEMENT_PENDING', 'RESOLVED'));

ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_refusal_note CHECK ((refusal_note IS NULL OR (refusal_reason IS NOT NULL
        AND btrim(refusal_note) <> '')) AND (refusal_reason IS DISTINCT FROM 'OTHER' OR refusal_note IS NOT NULL));

ALTER TABLE ordering.return_request DROP CONSTRAINT ck_return_request_four_eyes;
ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_four_eyes CHECK (settled_by IS NULL OR settled_by <> proposed_by
        OR (status = 'RESOLVED' AND settlement_amount = 0 AND refusal_reason IS NULL));

COMMENT ON COLUMN ordering.return_request.refusal_reason IS 'Why goods that came back are not (all) refunded (WF-05 Q-4): CUSTOMER_DAMAGE, NOT_DEFECTIVE, OUT_OF_POLICY or OTHER. Required when every refundable unit is withheld; shown to the customer. Set by the proposal, approved by a second person.';
COMMENT ON COLUMN ordering.return_request.refusal_note IS 'The public note shown to the customer with the refusal reason (WF-05 Q-4); required for OTHER. Internal notes stay in resolution_note.';
