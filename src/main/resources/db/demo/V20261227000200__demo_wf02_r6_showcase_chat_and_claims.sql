-- =============================================================================
-- Demo: WF-02 fix round 6 (MIG-1, MIG-2; owner decision D-3 of 2026-11-24). Demo only (db/demo).
--
-- 1. MIG-1. V20261124000500's showcase conversation "Đặt in cốc giấy 12oz" draws every card kind with its data in the
--    payload and no document behind it, so every action on it answers "not found". V20261210000100 handed it to
--    demo.sales, so it sits next to the real WF-02 conversations of the same customer, still ACTIVE, with five cards
--    OPEN. Its payment reminder also said 35.000.000 for an installment its own contract card puts at 25.500.000, due
--    2026-11-10 although it is due 30 days after the delivery of 2026-11-12. It is kept as the gallery of cards it is,
--    but closed: the conversation is resolved and closed (16 §5 allows ACTIVE -> RESOLVED -> CLOSED, one step each), its
--    open cards are CLOSED (tg_message_immutable lets a card's state and payload change), and the reminder says what
--    the contract card says, with its VietQR payload recomputed for that amount (field 54, then CRC-16/CCITT-FALSE as
--    payment.api.VietQr writes it; the BIN stays 970000, which is no bank's).
-- 2. MIG-2. Contracts drawn up before V20261201000300 have no complaint period (return_window_days NULL); the return
--    flow reads NULL as the configured default (stockflow.order.return-window-days, 7). Every such row here is one a
--    seed inserted (the DS-02 contract of V20261124000600, the signed contracts of V20261128000200), so it is given
--    those 7 days, and the contract PDF, the return flow and the order page read one number.
-- =============================================================================

-- ---------------------------------------------------------------------------------------------------------- 1

UPDATE chat.conversation
SET status            = 'RESOLVED',
    status_changed_at = NOW(),
    resolved_at       = NOW(),
    resolved_by       = assigned_agent_id,
    reopen_until      = NOW(),
    version           = version + 1,
    last_modified_at  = NOW(),
    last_modified_by  = 'flyway'
WHERE id = 'c4a70000-0000-4000-8000-000000000101'
  AND status = 'ACTIVE';

UPDATE chat.conversation
SET status            = 'CLOSED',
    status_changed_at = NOW(),
    closed_at         = NOW(),
    version           = version + 1,
    last_modified_at  = NOW(),
    last_modified_by  = 'flyway'
WHERE id = 'c4a70000-0000-4000-8000-000000000101'
  AND status = 'RESOLVED';

UPDATE chat.message
SET card_payload = card_payload
                       || '{"amount":25500000,"installmentAmount":25500000,"dueDate":"2026-12-12","overdue":false}'::jsonb
                       || jsonb_build_object('vietQr',
        '00020101021238570010A00000072701270006970000011300710012345670208QRIBFTTA53037045408255000005802VN62230819HD-DEMO-0001-1 DOT263047CAD'),
    version      = version + 1
WHERE id = 'c4a70000-0000-4000-8000-000000000208'
  AND (card_payload ->> 'amount')::numeric = 35000000;

UPDATE chat.message
SET card_state      = 'CLOSED',
    card_updated_at = NOW(),
    version         = version + 1
WHERE conversation_id = 'c4a70000-0000-4000-8000-000000000101'
  AND card_state = 'OPEN'
  AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------------------------------------- 2

-- tg_sales_contract_transition refuses any change to a contract that is no longer a draft (14 BR-14), the complaint
-- period included. The rule is about contracts people sent and signed; it is lifted for this one statement over rows
-- a seed made, as V20261221000110 did, and put back at once.
ALTER TABLE ordering.sales_contract DISABLE TRIGGER tg_sales_contract_transition;

UPDATE ordering.sales_contract
SET return_window_days = 7,
    version            = version + 1,
    last_modified_at   = NOW(),
    last_modified_by   = 'flyway'
WHERE return_window_days IS NULL;

-- The deferred constraint triggers queued for this update run now, so nothing is pending on the table when its
-- trigger is put back (PostgreSQL refuses ALTER TABLE otherwise).
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE ordering.sales_contract ENABLE TRIGGER tg_sales_contract_transition;
