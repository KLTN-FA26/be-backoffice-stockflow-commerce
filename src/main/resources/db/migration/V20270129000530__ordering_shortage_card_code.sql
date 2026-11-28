-- =============================================================================
-- A short human code for the shortage choice card (owner decision UNH-CODE-1128, 2026-11-28): "TT-yyyyMMdd-NNNN" -
-- "TT" for "thẻ thiếu", the Vietnam day the card was sent, a per-day number - shown on the card, in the notes its
-- execution writes and in the window-change history, instead of the card's id. Module: order.
--
-- Drawn as RMA numbers are (ReturnNumberSequence): one counter row per day, taken with an atomic UPDATE ... RETURNING in
-- its own transaction; a card refused after taking a code leaves a gap, never a duplicate.
--
-- Existing rows (local and test databases only - the card has not been deployed) get codes in the order they were sent,
-- per Vietnam day, and the counter continues after them.
--
-- Stated twice: the code's shape here (ck_shortage_card_code) and in ShortageCard; unique (uk_shortage_card_code).
-- =============================================================================

CREATE TABLE ordering.shortage_card_code_sequence (
    sequence_date DATE   NOT NULL,
    last_value    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_shortage_card_code_sequence PRIMARY KEY (sequence_date),
    CONSTRAINT ck_shortage_card_code_sequence_value CHECK (last_value >= 0)
);

COMMENT ON TABLE ordering.shortage_card_code_sequence IS
    'UNH-CODE-1128: the per-day counter behind shortage card codes TT-yyyyMMdd-NNNN (ShortageCardCodeSequence).';

ALTER TABLE ordering.shortage_card ADD COLUMN code VARCHAR(30);

WITH numbered AS (
    SELECT id, (sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
           row_number() OVER (PARTITION BY (sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date ORDER BY sent_at, id) AS n
      FROM ordering.shortage_card)
UPDATE ordering.shortage_card c
   SET code = 'TT-' || to_char(numbered.day, 'YYYYMMDD') || '-' || lpad(numbered.n::text, 4, '0')
  FROM numbered
 WHERE numbered.id = c.id;

INSERT INTO ordering.shortage_card_code_sequence (sequence_date, last_value)
SELECT (sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, count(*)
  FROM ordering.shortage_card
 GROUP BY 1;

ALTER TABLE ordering.shortage_card ALTER COLUMN code SET NOT NULL;
ALTER TABLE ordering.shortage_card ADD CONSTRAINT uk_shortage_card_code UNIQUE (code);
ALTER TABLE ordering.shortage_card ADD CONSTRAINT ck_shortage_card_code CHECK (code ~ '^TT-[0-9]{8}-[0-9]{4,}$');

COMMENT ON COLUMN ordering.shortage_card.code IS
    'UNH-CODE-1128: the card''s short code TT-yyyyMMdd-NNNN (Vietnam day sent, per-day number) - what people read and '
    'what the notes and the window history name.';
