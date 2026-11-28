-- =============================================================================
-- A delivery window moved on the customer's choice on a shortage card (kltn-docs 17 §4.14 step 58, BR-28; owner decision
-- D48, 2026-11-28). Module: order.
--
-- Why. The wait option of the shortage choice card "Đơn thiếu N thùng" carries an expected delivery date. When that date
-- is after the batch's window, the customer's press on "chờ sản xuất" is the consent BR-28 asks for to move the window
-- to exactly that date; Sales confirm it when they carry the choice out, and the window's history names the card as the
-- evidence instead of a file or a chat message. A move to any other date still needs a file or a message.
--
-- What changes:
--   * ordering.delivery_batch_window_change.evidence_shortage_card_id - the card, kept (ON DELETE RESTRICT).
--   * ck_delivery_batch_window_change_evidence - exactly one of the file, the message and the card; the file's key and
--     name still go together.
--   * tg_delivery_batch_window_change_card - the card is the order's, of the batch moved, the customer chose to wait on
--     it, the window starts on the card's expected date and the reason is the seller's delay (the rules
--     ShortageCardExecutionService checks first; BE rule "every invariant twice"). The table is append-only, so an
--     insert is the only write to check.
-- Rows already there carry a file or a message: the new CHECK holds for them as it is.
-- =============================================================================

ALTER TABLE ordering.delivery_batch_window_change
    ADD COLUMN evidence_shortage_card_id UUID,
    ADD CONSTRAINT fk_delivery_batch_window_change_shortage_card FOREIGN KEY (evidence_shortage_card_id)
        REFERENCES ordering.shortage_card (id) ON DELETE RESTRICT;

ALTER TABLE ordering.delivery_batch_window_change DROP CONSTRAINT ck_delivery_batch_window_change_evidence;
ALTER TABLE ordering.delivery_batch_window_change ADD CONSTRAINT ck_delivery_batch_window_change_evidence CHECK (
    (evidence_file_key IS NULL) = (evidence_file_name IS NULL)
    AND num_nonnulls(evidence_file_key, evidence_message_id, evidence_shortage_card_id) = 1);

CREATE INDEX ix_delivery_batch_window_change_shortage_card ON ordering.delivery_batch_window_change
    (evidence_shortage_card_id) WHERE evidence_shortage_card_id IS NOT NULL;

COMMENT ON COLUMN ordering.delivery_batch_window_change.evidence_shortage_card_id IS
    '17 §4.14 step 58, D48: the shortage card whose wait option the customer chose - the consent to move the window to '
    'the card''s expected delivery date. Exactly one of file, message and card is the evidence.';

CREATE FUNCTION ordering.check_window_change_card_evidence() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    card RECORD;
BEGIN
    SELECT c.order_id, c.delivery_batch_id, c.chosen_option, c.expected_delivery_date
      INTO card
      FROM ordering.shortage_card c WHERE c.id = NEW.evidence_shortage_card_id;
    IF NOT FOUND OR card.order_id <> NEW.order_id OR card.delivery_batch_id <> NEW.delivery_batch_id THEN
        RAISE EXCEPTION 'order %: shortage card % is not a card of this order''s batch (17 BR-28)', NEW.order_id,
            NEW.evidence_shortage_card_id USING ERRCODE = 'check_violation';
    END IF;
    IF card.chosen_option IS DISTINCT FROM 'WAIT_FOR_PRODUCTION' THEN
        RAISE EXCEPTION 'order %: on shortage card % the customer did not choose to wait for production (17 §4.14, D48)',
            NEW.order_id, NEW.evidence_shortage_card_id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.new_window_start IS DISTINCT FROM card.expected_delivery_date THEN
        RAISE EXCEPTION 'order %: shortage card % is consent to start the window on %, not on % (D48)', NEW.order_id,
            NEW.evidence_shortage_card_id, card.expected_delivery_date, NEW.new_window_start
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.reason_code <> 'SELLER_DELAY' THEN
        RAISE EXCEPTION 'order %: a window moved on a shortage card is the seller''s delay (17 §4.14 step 58)',
            NEW.order_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_delivery_batch_window_change_card BEFORE INSERT ON ordering.delivery_batch_window_change
    FOR EACH ROW WHEN (NEW.evidence_shortage_card_id IS NOT NULL)
    EXECUTE FUNCTION ordering.check_window_change_card_evidence();
