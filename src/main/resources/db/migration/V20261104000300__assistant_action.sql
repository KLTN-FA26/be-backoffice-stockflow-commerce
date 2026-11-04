-- What the assistant suggested and its user decided (ADR-0011, design §8.2): a purchase-order draft from
-- proposeReorder, a goods-receipt draft read from a delivery note's photo. The model writes nothing: a row is
-- SUGGESTED when the answer (or the reading) is stored, and only its owner turns it into a document, through an
-- endpoint that carries the guard of that document's own screen. CONFIRMED rows name the document they created, so a
-- second confirmation returns it instead of creating another.
CREATE TABLE assistant.action
(
    id               UUID          NOT NULL,
    -- Whose suggestion it is: only they may confirm or dismiss it, whatever their scope on the documents.
    owner_user_id    UUID          NOT NULL,
    -- The answer that suggested it; both null for a draft read from a photo, which belongs to no conversation.
    conversation_id  UUID,
    message_id       UUID,
    kind             VARCHAR(32)   NOT NULL,
    -- What it proposes: the proposal's type and supplier for a purchase order, the kind and purchase order for a
    -- receipt. A newer purchase-order suggestion of the same owner and key that orders all of an open one's SKUs
    -- supersedes it: asking again must not leave two documents to confirm for one need.
    proposal_key     VARCHAR(200)  NOT NULL,
    -- The suggestion as the user was shown it, and what they confirmed (their edits); JSON written by the application.
    suggested        JSONB         NOT NULL,
    submitted        JSONB,
    status           VARCHAR(16)   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    decided_at       TIMESTAMPTZ,
    -- The document created, one column per kind so each is a real reference (ADR-0007). Neither document is ever
    -- deleted (they are cancelled), so the references restrict.
    purchase_order_id UUID,
    goods_receipt_id UUID,
    result_number    VARCHAR(64),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_action PRIMARY KEY (id),
    CONSTRAINT fk_action_owner FOREIGN KEY (owner_user_id) REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    -- Deleted with its conversation, as its messages and tool calls are (the retention job, a user's deletion).
    CONSTRAINT fk_action_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant.conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_action_message FOREIGN KEY (message_id) REFERENCES assistant.message (id) ON DELETE CASCADE,
    CONSTRAINT fk_action_purchase_order FOREIGN KEY (purchase_order_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_action_goods_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT uk_action_proposal UNIQUE (message_id, proposal_key),
    CONSTRAINT ck_action_kind CHECK (kind IN ('PURCHASE_ORDER_DRAFT', 'GOODS_RECEIPT_DRAFT')),
    -- What it proposes, after its kind: PURCHASE_ORDER_DRAFT:<supplier>, GOODS_RECEIPT_DRAFT:<purchase order>.
    CONSTRAINT ck_action_key CHECK (proposal_key LIKE kind || ':_%'),
    CONSTRAINT ck_action_status CHECK (status IN ('SUGGESTED', 'CONFIRMED', 'DISMISSED', 'SUPERSEDED')),
    -- An answer's suggestion names its conversation and its answer; a photo's names neither.
    CONSTRAINT ck_action_source CHECK ((conversation_id IS NULL) = (message_id IS NULL)),
    -- Decided exactly when no longer SUGGESTED.
    CONSTRAINT ck_action_decided CHECK ((status = 'SUGGESTED') = (decided_at IS NULL)),
    -- A document exactly when CONFIRMED, of the suggestion's own kind, with its number and what was confirmed.
    CONSTRAINT ck_action_result CHECK (
        (kind = 'PURCHASE_ORDER_DRAFT' OR purchase_order_id IS NULL)
        AND (kind = 'GOODS_RECEIPT_DRAFT' OR goods_receipt_id IS NULL)
        AND (status = 'CONFIRMED') = (COALESCE(purchase_order_id, goods_receipt_id) IS NOT NULL)
        AND (status = 'CONFIRMED') = (result_number IS NOT NULL)
        AND (status = 'CONFIRMED') = (submitted IS NOT NULL))
);

-- A user's own suggestions, newest first (GET /api/v1/assistant/actions).
CREATE INDEX ix_action_owner ON assistant.action (owner_user_id, created_at DESC);
-- The open suggestions a newer one of the same owner and key may supersede; another confirmed for the same key.
CREATE INDEX ix_action_open ON assistant.action (owner_user_id, kind, proposal_key) WHERE status = 'SUGGESTED';
CREATE INDEX ix_action_conversation ON assistant.action (conversation_id) WHERE conversation_id IS NOT NULL;
-- The retention job's sweep of the suggestions no conversation carries away.
CREATE INDEX ix_action_created ON assistant.action (created_at) WHERE conversation_id IS NULL;
CREATE INDEX ix_action_purchase_order ON assistant.action (purchase_order_id) WHERE purchase_order_id IS NOT NULL;
CREATE INDEX ix_action_goods_receipt ON assistant.action (goods_receipt_id) WHERE goods_receipt_id IS NOT NULL;
