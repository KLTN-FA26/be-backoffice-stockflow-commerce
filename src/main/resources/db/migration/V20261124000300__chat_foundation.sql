-- =============================================================================
-- Sales chat foundation (kltn-docs 16 §4, §5, BR-01..BR-11; feature F-13, package 2). Module: chat
--
-- The baseline left chat a skeleton: a conversation with three statuses and a text-only message. This is the whole
-- schema the chat needs, including what package 3 (system cards and their actions, routing, SLA timers and alerts,
-- auto-close and reopen jobs, CSAT, canned responses, search, masking, notifications) builds on, so that package writes
-- no migration:
--
--   chat.conversation          the eleven states of 16 §5 and the allowed moves between them (tg_conversation_lifecycle);
--                              the agent, the first-response and resolution clocks (BR-09), the reopen window, CSAT,
--                              the conversation a new one continues, the one it was merged into
--   chat.conversation_context  what the conversation is about: a product, the customer's own SKU, a design or design
--                              project, a quote, an order, a contract, a production order, an e-invoice - each a real
--                              foreign key (ADR-0007) and always the conversation's customer's (BR-02, twice: the service
--                              answers 404 first, tg_conversation_context_owner is the last line)
--   chat.message               text, attachment, card (a kind + jsonb payload + state, for package 3's cards), system
--                              line, internal note; visibility CUSTOMER or STAFF (an internal note is always STAFF,
--                              BR-07); a per-conversation sequence (cursor paging, read markers); the client's own id
--                              of the message, unique in its conversation (a retried send is the same message)
--   chat.message_mention       the staff an internal note names (@mentions)
--   chat.message_attachment    images and PDFs (common storage, virus-scanned, FileCategory.CHAT_ATTACHMENT, 20 MB)
--   chat.participant_read      how far each participant has read, as a sequence that only moves forward
--   chat.conversation_event    append-only log of every consequential action: who, what, on which context (BR-01)
--   chat.canned_response       Sales' saved replies with {variables}, personal or shared
--
-- The legacy chat.conversation.order_id stays: the delivery-window consent trigger (ordering, 17 BR-28) reads it. It
-- is the order a conversation was opened about, mirrored from its primary ORDER context.
--
-- Permissions: chat-conversations (existing: CUSTOMER, scope OWN on the portal endpoints; SALES_STAFF; now also
-- SALES_MANAGER), chat-supervision (new: SALES_MANAGER, SYSTEM_ADMIN) and chat-canned-responses (new: Sales).
-- =============================================================================

-- ---------------------------------------------------------------------------------------------
-- chat.conversation
-- ---------------------------------------------------------------------------------------------
ALTER TABLE chat.conversation DROP CONSTRAINT ck_conversation_status;

ALTER TABLE chat.conversation
    ADD COLUMN opened_by_type            VARCHAR(16),
    ADD COLUMN opened_by                 UUID,
    ADD COLUMN assigned_at               TIMESTAMPTZ,
    ADD COLUMN status_changed_at         TIMESTAMPTZ,
    ADD COLUMN first_response_due_at     TIMESTAMPTZ,
    ADD COLUMN first_responded_at        TIMESTAMPTZ,
    ADD COLUMN first_response_alerted_at TIMESTAMPTZ,
    ADD COLUMN resolution_due_at         TIMESTAMPTZ,
    ADD COLUMN resolution_alerted_at     TIMESTAMPTZ,
    ADD COLUMN escalation_reason         VARCHAR(1000),
    ADD COLUMN escalated_to_id           UUID,
    ADD COLUMN resolved_at               TIMESTAMPTZ,
    ADD COLUMN resolved_by               UUID,
    ADD COLUMN auto_closed_at            TIMESTAMPTZ,
    ADD COLUMN closed_at                 TIMESTAMPTZ,
    ADD COLUMN reopen_until              TIMESTAMPTZ,
    ADD COLUMN last_customer_message_at  TIMESTAMPTZ,
    ADD COLUMN last_message_at           TIMESTAMPTZ,
    ADD COLUMN last_message_seq          BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN csat_score                SMALLINT,
    ADD COLUMN csat_comment              VARCHAR(2000),
    ADD COLUMN csat_submitted_at         TIMESTAMPTZ,
    ADD COLUMN continued_from_id         UUID,
    ADD COLUMN merged_into_id            UUID;

-- The three statuses of the skeleton, in the eleven of 16 §5: an open conversation without an agent waits in the
-- queue, one with an agent is assigned, a closed one is closed for good.
UPDATE chat.conversation
   SET status = CASE
                    WHEN status = 'CLOSED' THEN 'CLOSED'
                    WHEN assigned_agent_id IS NULL THEN 'QUEUED'
                    ELSE 'ASSIGNED' END,
       opened_by_type = 'CUSTOMER',
       status_changed_at = coalesce(last_modified_at, created_at),
       assigned_at = CASE WHEN assigned_agent_id IS NOT NULL THEN coalesce(last_modified_at, created_at) END,
       closed_at = CASE WHEN status = 'CLOSED' THEN coalesce(last_modified_at, created_at) END;

ALTER TABLE chat.conversation
    ALTER COLUMN opened_by_type SET NOT NULL,
    ALTER COLUMN status_changed_at SET NOT NULL,
    ADD CONSTRAINT ck_conversation_status CHECK (status IN ('NEW', 'QUEUED', 'ASSIGNED', 'ACTIVE',
        'WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL', 'ESCALATED', 'RESOLVED', 'REOPENED', 'AUTO_CLOSED', 'CLOSED')),
    ADD CONSTRAINT ck_conversation_opened_by_type CHECK (opened_by_type IN ('CUSTOMER', 'AGENT', 'SYSTEM')),
    -- An agent is on the conversation from the moment it is assigned until it ends; a new or queued one has none.
    ADD CONSTRAINT ck_conversation_agent CHECK (status NOT IN ('ASSIGNED', 'ACTIVE', 'WAITING_ON_CUSTOMER',
        'WAITING_ON_INTERNAL', 'ESCALATED') OR assigned_agent_id IS NOT NULL),
    ADD CONSTRAINT ck_conversation_unassigned CHECK (status NOT IN ('NEW', 'QUEUED') OR assigned_agent_id IS NULL),
    ADD CONSTRAINT ck_conversation_assigned_at CHECK ((assigned_agent_id IS NULL) = (assigned_at IS NULL)),
    ADD CONSTRAINT ck_conversation_escalation CHECK (status <> 'ESCALATED' OR escalation_reason IS NOT NULL),
    ADD CONSTRAINT ck_conversation_resolved CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL),
    ADD CONSTRAINT ck_conversation_auto_closed CHECK (status <> 'AUTO_CLOSED' OR auto_closed_at IS NOT NULL),
    ADD CONSTRAINT ck_conversation_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    -- A resolved or auto-closed conversation can be reopened by the customer until this moment (16 §5).
    ADD CONSTRAINT ck_conversation_reopen_window CHECK (status NOT IN ('RESOLVED', 'AUTO_CLOSED')
        OR reopen_until IS NOT NULL),
    ADD CONSTRAINT ck_conversation_csat CHECK (csat_score IS NULL OR csat_score BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_conversation_csat_submitted CHECK ((csat_score IS NULL) = (csat_submitted_at IS NULL)
        AND (csat_comment IS NULL OR csat_score IS NOT NULL)),
    ADD CONSTRAINT ck_conversation_last_message CHECK (last_message_seq >= 0
        AND (last_message_seq = 0) = (last_message_at IS NULL)),
    ADD CONSTRAINT ck_conversation_continued CHECK (continued_from_id IS NULL OR continued_from_id <> id),
    ADD CONSTRAINT ck_conversation_merged CHECK (merged_into_id IS NULL OR merged_into_id <> id),
    ADD CONSTRAINT fk_conversation_opened_by FOREIGN KEY (opened_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_conversation_escalated_to FOREIGN KEY (escalated_to_id) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_conversation_resolved_by FOREIGN KEY (resolved_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_conversation_continued_from FOREIGN KEY (continued_from_id)
        REFERENCES chat.conversation (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_conversation_merged_into FOREIGN KEY (merged_into_id)
        REFERENCES chat.conversation (id) ON DELETE RESTRICT;

COMMENT ON COLUMN chat.conversation.status IS
    'kltn-docs 16 §5: NEW, QUEUED, ASSIGNED, ACTIVE, WAITING_ON_CUSTOMER, WAITING_ON_INTERNAL, ESCALATED, RESOLVED, '
    'REOPENED, AUTO_CLOSED, CLOSED. Moves only as tg_conversation_lifecycle allows.';
COMMENT ON COLUMN chat.conversation.order_id IS
    'Legacy: the order the conversation was opened about (its primary ORDER context). Read by the delivery-window '
    'consent check (17 BR-28); chat.conversation_context is the list of what the conversation is about.';
COMMENT ON COLUMN chat.conversation.first_response_due_at IS
    'BR-09: when the first agent reply is due; first_responded_at is when it came, first_response_alerted_at when the '
    'supervisors were told it was late (package 3 SLA job: once).';
COMMENT ON COLUMN chat.conversation.reopen_until IS
    'Until when a RESOLVED or AUTO_CLOSED conversation reopens on the customer''s word; after it the customer gets a '
    'new conversation that continues this one (continued_from_id), and the close job moves this one to CLOSED.';
COMMENT ON COLUMN chat.conversation.last_message_seq IS
    'The sequence of the newest message: the next message gets one more. Moves only forward.';
COMMENT ON COLUMN chat.conversation.continued_from_id IS
    'The closed conversation this one continues: the customer wrote after its reopen window.';

-- 16 §5. Born NEW, or already routed (QUEUED / ASSIGNED) in the transaction that opened it. A conversation keeps its
-- customer and the conversation it continues, and its message sequence only moves forward.
CREATE FUNCTION chat.check_conversation_lifecycle() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('NEW', 'QUEUED', 'ASSIGNED') THEN
            RAISE EXCEPTION 'conversation %: a conversation starts NEW, QUEUED or ASSIGNED, not % (16 §5)', NEW.id,
                NEW.status USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.continued_from_id IS DISTINCT FROM OLD.continued_from_id
        OR NEW.opened_by IS DISTINCT FROM OLD.opened_by THEN
        RAISE EXCEPTION 'conversation %: a conversation keeps its customer, who opened it and what it continues', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.last_message_seq < OLD.last_message_seq THEN
        RAISE EXCEPTION 'conversation %: the message sequence does not go back (% -> %)', OLD.id, OLD.last_message_seq,
            NEW.last_message_seq USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        IF OLD.status = 'CLOSED' AND NEW.assigned_agent_id IS DISTINCT FROM OLD.assigned_agent_id THEN
            RAISE EXCEPTION 'conversation %: a closed conversation is not handed to anyone', OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'NEW' AND NEW.status IN ('QUEUED', 'ASSIGNED'))
         OR (OLD.status = 'QUEUED' AND NEW.status = 'ASSIGNED')
         OR (OLD.status = 'ASSIGNED' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'ACTIVE' AND NEW.status IN ('WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL', 'ESCALATED',
                                                      'RESOLVED'))
         OR (OLD.status = 'WAITING_ON_CUSTOMER' AND NEW.status IN ('ACTIVE', 'AUTO_CLOSED'))
         OR (OLD.status = 'WAITING_ON_INTERNAL' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'ESCALATED' AND NEW.status IN ('ACTIVE', 'RESOLVED'))
         OR (OLD.status = 'RESOLVED' AND NEW.status IN ('REOPENED', 'CLOSED'))
         OR (OLD.status = 'REOPENED' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'AUTO_CLOSED' AND NEW.status IN ('REOPENED', 'CLOSED'))) THEN
        RAISE EXCEPTION 'conversation %: % -> % is not a transition (16 §5)', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_conversation_lifecycle BEFORE INSERT OR UPDATE ON chat.conversation
    FOR EACH ROW EXECUTE FUNCTION chat.check_conversation_lifecycle();

DROP INDEX chat.ix_conversation_customer;
CREATE INDEX ix_conversation_customer ON chat.conversation (customer_id, last_message_at DESC NULLS LAST);
CREATE INDEX ix_conversation_agent ON chat.conversation (assigned_agent_id, status)
    WHERE assigned_agent_id IS NOT NULL;
CREATE INDEX ix_conversation_open_status ON chat.conversation (status, last_message_at DESC NULLS LAST)
    WHERE status NOT IN ('RESOLVED', 'AUTO_CLOSED', 'CLOSED');
-- BR-09: the overdue first responses, highlighted in the queue and alerted by package 3.
CREATE INDEX ix_conversation_first_response_due ON chat.conversation (first_response_due_at)
    WHERE first_responded_at IS NULL AND first_response_due_at IS NOT NULL
      AND status NOT IN ('RESOLVED', 'AUTO_CLOSED', 'CLOSED');
CREATE INDEX ix_conversation_reopen_until ON chat.conversation (reopen_until)
    WHERE status IN ('RESOLVED', 'AUTO_CLOSED');
CREATE INDEX ix_conversation_continued_from ON chat.conversation (continued_from_id)
    WHERE continued_from_id IS NOT NULL;
CREATE INDEX ix_conversation_merged_into ON chat.conversation (merged_into_id) WHERE merged_into_id IS NOT NULL;
CREATE INDEX ix_conversation_opened_by ON chat.conversation (opened_by) WHERE opened_by IS NOT NULL;
CREATE INDEX ix_conversation_escalated_to ON chat.conversation (escalated_to_id) WHERE escalated_to_id IS NOT NULL;
CREATE INDEX ix_conversation_resolved_by ON chat.conversation (resolved_by) WHERE resolved_by IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- chat.conversation_context
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat.conversation_context
(
    id                  UUID        NOT NULL,
    conversation_id     UUID        NOT NULL,
    kind                VARCHAR(32) NOT NULL,
    product_id          UUID,
    variant_id          UUID,
    design_id           UUID,
    design_project_id   UUID,
    quote_id            UUID,
    order_id            UUID,
    contract_id         UUID,
    production_order_id UUID,
    einvoice_id         UUID,
    -- The context of the page the conversation was opened from; at most one per conversation.
    is_primary          BOOLEAN     NOT NULL DEFAULT FALSE,
    attached_by         UUID,
    attached_at         TIMESTAMPTZ NOT NULL,

    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_conversation_context PRIMARY KEY (id),
    -- The target of the composite keys of chat.message and chat.conversation_event: a context of THAT conversation.
    CONSTRAINT uk_conversation_context_conversation UNIQUE (id, conversation_id),
    CONSTRAINT fk_conversation_context_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat.conversation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_product FOREIGN KEY (product_id)
        REFERENCES product.products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_variant FOREIGN KEY (variant_id)
        REFERENCES product.variants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_design FOREIGN KEY (design_id)
        REFERENCES design.design_draft (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_design_project FOREIGN KEY (design_project_id)
        REFERENCES design.design_project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_quote FOREIGN KEY (quote_id)
        REFERENCES ordering.quote (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_contract FOREIGN KEY (contract_id)
        REFERENCES ordering.sales_contract (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_production_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_einvoice FOREIGN KEY (einvoice_id)
        REFERENCES invoicing.einvoice (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_context_attached_by FOREIGN KEY (attached_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_conversation_context_kind CHECK (kind IN ('PRODUCT', 'CUSTOMER_SKU', 'DESIGN', 'DESIGN_PROJECT',
        'QUOTE', 'ORDER', 'CONTRACT', 'PRODUCTION_ORDER', 'EINVOICE')),
    -- Exactly the reference of its kind, and no other.
    CONSTRAINT ck_conversation_context_reference CHECK (num_nonnulls(product_id, variant_id, design_id,
        design_project_id, quote_id, order_id, contract_id, production_order_id, einvoice_id) = 1
        AND (kind <> 'PRODUCT' OR product_id IS NOT NULL)
        AND (kind <> 'CUSTOMER_SKU' OR variant_id IS NOT NULL)
        AND (kind <> 'DESIGN' OR design_id IS NOT NULL)
        AND (kind <> 'DESIGN_PROJECT' OR design_project_id IS NOT NULL)
        AND (kind <> 'QUOTE' OR quote_id IS NOT NULL)
        AND (kind <> 'ORDER' OR order_id IS NOT NULL)
        AND (kind <> 'CONTRACT' OR contract_id IS NOT NULL)
        AND (kind <> 'PRODUCTION_ORDER' OR production_order_id IS NOT NULL)
        AND (kind <> 'EINVOICE' OR einvoice_id IS NOT NULL))
);

COMMENT ON TABLE chat.conversation_context IS
    'What a conversation is about (kltn-docs 16 §1, §3): one row per document, always the conversation''s customer''s '
    '(BR-02, tg_conversation_context_owner). A product of the catalogue is anyone''s to ask about.';

-- One row per document per conversation, and one primary context.
CREATE UNIQUE INDEX uk_conversation_context_reference ON chat.conversation_context (conversation_id, kind,
    coalesce(product_id, variant_id, design_id, design_project_id, quote_id, order_id, contract_id,
             production_order_id, einvoice_id));
CREATE UNIQUE INDEX uk_conversation_context_primary ON chat.conversation_context (conversation_id) WHERE is_primary;
-- "The conversations about this document", for the document's own page.
CREATE INDEX ix_conversation_context_product ON chat.conversation_context (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX ix_conversation_context_variant ON chat.conversation_context (variant_id) WHERE variant_id IS NOT NULL;
CREATE INDEX ix_conversation_context_design ON chat.conversation_context (design_id) WHERE design_id IS NOT NULL;
CREATE INDEX ix_conversation_context_design_project ON chat.conversation_context (design_project_id)
    WHERE design_project_id IS NOT NULL;
CREATE INDEX ix_conversation_context_quote ON chat.conversation_context (quote_id) WHERE quote_id IS NOT NULL;
CREATE INDEX ix_conversation_context_order ON chat.conversation_context (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_conversation_context_contract ON chat.conversation_context (contract_id)
    WHERE contract_id IS NOT NULL;
CREATE INDEX ix_conversation_context_production_order ON chat.conversation_context (production_order_id)
    WHERE production_order_id IS NOT NULL;
CREATE INDEX ix_conversation_context_einvoice ON chat.conversation_context (einvoice_id)
    WHERE einvoice_id IS NOT NULL;
CREATE INDEX ix_conversation_context_attached_by ON chat.conversation_context (attached_by)
    WHERE attached_by IS NOT NULL;

-- 16 BR-02, the second time: the document is the conversation's customer's. A context names one document for good.
CREATE FUNCTION chat.check_conversation_context_owner() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    conversation_customer UUID;
    owner                 UUID;
    reference             UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.conversation_id, NEW.kind, NEW.product_id, NEW.variant_id, NEW.design_id,
            NEW.design_project_id, NEW.quote_id, NEW.order_id, NEW.contract_id, NEW.production_order_id,
            NEW.einvoice_id, NEW.attached_by, NEW.attached_at)
        IS DISTINCT FROM (OLD.conversation_id, OLD.kind, OLD.product_id, OLD.variant_id, OLD.design_id,
            OLD.design_project_id, OLD.quote_id, OLD.order_id, OLD.contract_id, OLD.production_order_id,
            OLD.einvoice_id, OLD.attached_by, OLD.attached_at) THEN
        RAISE EXCEPTION 'conversation context %: a context names one document of one conversation for good', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    -- A row without the reference of its kind (or of a kind the chat does not know) is ck_conversation_context_*'s to
    -- refuse, with its own name.
    reference := CASE NEW.kind
           WHEN 'PRODUCT' THEN NEW.product_id WHEN 'CUSTOMER_SKU' THEN NEW.variant_id
           WHEN 'DESIGN' THEN NEW.design_id WHEN 'DESIGN_PROJECT' THEN NEW.design_project_id
           WHEN 'QUOTE' THEN NEW.quote_id WHEN 'ORDER' THEN NEW.order_id WHEN 'CONTRACT' THEN NEW.contract_id
           WHEN 'PRODUCTION_ORDER' THEN NEW.production_order_id WHEN 'EINVOICE' THEN NEW.einvoice_id END;
    IF reference IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT c.customer_id INTO conversation_customer FROM chat.conversation c WHERE c.id = NEW.conversation_id;
    owner := CASE NEW.kind
        WHEN 'PRODUCT' THEN conversation_customer
        WHEN 'CUSTOMER_SKU' THEN (SELECT v.owner_customer_id FROM product.variants v WHERE v.id = NEW.variant_id)
        WHEN 'DESIGN' THEN (SELECT d.customer_id FROM design.design_draft d WHERE d.id = NEW.design_id)
        WHEN 'DESIGN_PROJECT' THEN (SELECT p.customer_id FROM design.design_project p
                                     WHERE p.id = NEW.design_project_id)
        WHEN 'QUOTE' THEN (SELECT q.customer_id FROM ordering.quote q WHERE q.id = NEW.quote_id)
        WHEN 'ORDER' THEN (SELECT o.customer_id FROM ordering.customer_order o WHERE o.id = NEW.order_id)
        WHEN 'CONTRACT' THEN (SELECT o.customer_id FROM ordering.sales_contract k
                                JOIN ordering.customer_order o ON o.id = k.order_id WHERE k.id = NEW.contract_id)
        WHEN 'PRODUCTION_ORDER' THEN (SELECT p.customer_id FROM ordering.production_order p
                                       WHERE p.id = NEW.production_order_id)
        WHEN 'EINVOICE' THEN (SELECT e.customer_id FROM invoicing.einvoice e WHERE e.id = NEW.einvoice_id)
    END;
    IF owner IS DISTINCT FROM conversation_customer THEN
        RAISE EXCEPTION 'conversation %: the % is not the conversation''s customer''s (16 BR-02)', NEW.conversation_id,
            NEW.kind USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_conversation_context_owner BEFORE INSERT OR UPDATE ON chat.conversation_context
    FOR EACH ROW EXECUTE FUNCTION chat.check_conversation_context_owner();

-- ---------------------------------------------------------------------------------------------
-- chat.message
-- ---------------------------------------------------------------------------------------------
ALTER TABLE chat.message
    ADD COLUMN seq               BIGINT,
    ADD COLUMN kind              VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN visibility        VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN client_message_id VARCHAR(64),
    ADD COLUMN context_id        UUID,
    ADD COLUMN card_kind         VARCHAR(32),
    ADD COLUMN card_payload      JSONB,
    ADD COLUMN card_state        VARCHAR(16),
    ADD COLUMN card_updated_at   TIMESTAMPTZ,
    ADD COLUMN edited_at         TIMESTAMPTZ,
    ADD COLUMN deleted_at        TIMESTAMPTZ,
    ADD COLUMN deleted_by        UUID,
    ALTER COLUMN body DROP NOT NULL;

UPDATE chat.message m
   SET seq = numbered.seq
  FROM (SELECT id, row_number() OVER (PARTITION BY conversation_id ORDER BY sent_at, id) AS seq
          FROM chat.message) numbered
 WHERE numbered.id = m.id;
UPDATE chat.conversation c
   SET last_message_seq = last.seq, last_message_at = last.sent_at,
       last_customer_message_at = (SELECT max(m.sent_at) FROM chat.message m
                                    WHERE m.conversation_id = c.id AND m.sender_type = 'CUSTOMER')
  FROM (SELECT conversation_id, max(seq) AS seq, max(sent_at) AS sent_at FROM chat.message
         GROUP BY conversation_id) last
 WHERE last.conversation_id = c.id;

ALTER TABLE chat.message
    ALTER COLUMN seq SET NOT NULL,
    ADD CONSTRAINT uk_message_seq UNIQUE (conversation_id, seq),
    -- The targets of the composite keys of chat.message_mention and chat.message_attachment, which name the kind.
    ADD CONSTRAINT uk_message_kind UNIQUE (id, kind),
    ADD CONSTRAINT uk_message_conversation UNIQUE (id, conversation_id),
    ADD CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_message_deleted_by FOREIGN KEY (deleted_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_message_context FOREIGN KEY (context_id, conversation_id)
        REFERENCES chat.conversation_context (id, conversation_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_message_seq CHECK (seq >= 1),
    ADD CONSTRAINT ck_message_kind CHECK (kind IN ('TEXT', 'ATTACHMENT', 'CARD', 'SYSTEM', 'INTERNAL_NOTE')),
    ADD CONSTRAINT ck_message_visibility CHECK (visibility IN ('CUSTOMER', 'STAFF')),
    -- BR-07: an internal note is the staff's and only the staff's; nothing else is hidden from the customer but a
    -- system line meant for the staff.
    ADD CONSTRAINT ck_message_internal_note CHECK ((kind = 'INTERNAL_NOTE') <= (visibility = 'STAFF' AND sender_type = 'AGENT')
        AND (visibility <> 'STAFF' OR kind IN ('INTERNAL_NOTE', 'SYSTEM'))),
    -- A customer writes text and sends files to the conversation, nothing else; a system line is the system's.
    ADD CONSTRAINT ck_message_customer CHECK (sender_type <> 'CUSTOMER' OR kind IN ('TEXT', 'ATTACHMENT')),
    ADD CONSTRAINT ck_message_system CHECK (kind <> 'SYSTEM' OR sender_type = 'SYSTEM'),
    ADD CONSTRAINT ck_message_sender CHECK (sender_type = 'SYSTEM' OR sender_id IS NOT NULL),
    ADD CONSTRAINT ck_message_body CHECK (kind NOT IN ('TEXT', 'SYSTEM', 'INTERNAL_NOTE')
        OR (body IS NOT NULL AND btrim(body) <> '')),
    ADD CONSTRAINT ck_message_client_id CHECK (client_message_id IS NULL
        OR client_message_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
    -- A card (package 3) is a kind, a payload object and a state, all three or none.
    ADD CONSTRAINT ck_message_card CHECK ((kind = 'CARD') = (card_kind IS NOT NULL)
        AND (card_kind IS NULL) = (card_payload IS NULL)
        AND (card_kind IS NULL) = (card_state IS NULL)
        AND (card_payload IS NULL OR jsonb_typeof(card_payload) = 'object')
        AND (card_updated_at IS NULL OR card_kind IS NOT NULL)),
    ADD CONSTRAINT ck_message_card_kind CHECK (card_kind IS NULL OR card_kind IN ('QUOTE_REQUEST', 'QUOTE',
        'CONTRACT', 'PAYMENT_INFO', 'PAYMENT_REMINDER', 'SAMPLE_APPROVAL', 'INVOICE', 'ORDER_STATUS',
        'PRODUCTION_PROGRESS', 'CSAT_SURVEY')),
    ADD CONSTRAINT ck_message_card_state CHECK (card_state IS NULL OR card_state IN ('OPEN', 'ACTED', 'SUPERSEDED',
        'EXPIRED', 'CANCELLED', 'CLOSED')),
    ADD CONSTRAINT ck_message_edited CHECK (edited_at IS NULL OR edited_at >= sent_at),
    ADD CONSTRAINT ck_message_deleted CHECK (deleted_by IS NULL OR deleted_at IS NOT NULL);

COMMENT ON COLUMN chat.message.seq IS
    'Position in the conversation, from 1 with no gaps: the cursor of the message list and of the read markers.';
COMMENT ON COLUMN chat.message.visibility IS
    'CUSTOMER: both sides see it. STAFF: only the staff (an internal note, BR-07; a system line for the staff). The '
    'chat repository filters on it for every read on a customer''s behalf.';
COMMENT ON COLUMN chat.message.client_message_id IS
    'The id the sending client gave the message: a retried send with the same id is the same message.';
COMMENT ON COLUMN chat.message.card_payload IS
    'A card''s data (package 3: quote request, quote, contract, payment info with VietQR, sample approval, invoice, '
    'order status...), shaped by card_kind; card_state is where its action stands.';
COMMENT ON COLUMN chat.message.sender_id IS
    'The account that sent it: the customer''s portal account or the staff member. NULL for a system line.';

-- One message per client id per conversation (a retried send).
CREATE UNIQUE INDEX uk_message_client_id ON chat.message (conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
CREATE INDEX ix_message_sender ON chat.message (sender_id) WHERE sender_id IS NOT NULL;
CREATE INDEX ix_message_context ON chat.message (context_id) WHERE context_id IS NOT NULL;
CREATE INDEX ix_message_deleted_by ON chat.message (deleted_by) WHERE deleted_by IS NOT NULL;
CREATE INDEX ix_message_open_card ON chat.message (card_kind, conversation_id) WHERE card_state = 'OPEN';
DROP INDEX chat.ix_message_conversation;

-- A message is what was said: who said it, where, when, of what kind and to whom never change. Only an edit (which
-- says when) changes a text or a note, only a card's own action changes the card, and a deleted message stays deleted.
CREATE FUNCTION chat.check_message_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.conversation_id, NEW.seq, NEW.sender_type, NEW.sender_id, NEW.kind, NEW.visibility, NEW.sent_at,
            NEW.client_message_id, NEW.context_id, NEW.card_kind)
        IS DISTINCT FROM (OLD.conversation_id, OLD.seq, OLD.sender_type, OLD.sender_id, OLD.kind, OLD.visibility,
            OLD.sent_at, OLD.client_message_id, OLD.context_id, OLD.card_kind) THEN
        RAISE EXCEPTION 'message %: who sent what, where, when and to whom does not change', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.body IS DISTINCT FROM OLD.body AND (NEW.edited_at IS NOT DISTINCT FROM OLD.edited_at
            OR OLD.kind NOT IN ('TEXT', 'INTERNAL_NOTE', 'ATTACHMENT')) THEN
        RAISE EXCEPTION 'message %: only an edit, which says when, changes what a text or a note says', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.deleted_at IS NOT NULL AND (NEW.deleted_at, NEW.deleted_by, NEW.body, NEW.card_payload, NEW.card_state)
        IS DISTINCT FROM (OLD.deleted_at, OLD.deleted_by, OLD.body, OLD.card_payload, OLD.card_state) THEN
        RAISE EXCEPTION 'message %: a deleted message stays as it was deleted', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_message_immutable BEFORE UPDATE ON chat.message
    FOR EACH ROW EXECUTE FUNCTION chat.check_message_immutable();

-- ---------------------------------------------------------------------------------------------
-- chat.message_mention
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat.message_mention
(
    id           UUID        NOT NULL,
    message_id   UUID        NOT NULL,
    -- Only an internal note mentions anyone: the key below names the message's kind.
    message_kind VARCHAR(16) NOT NULL DEFAULT 'INTERNAL_NOTE',
    user_id      UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_message_mention PRIMARY KEY (id),
    CONSTRAINT uk_message_mention UNIQUE (message_id, user_id),
    CONSTRAINT fk_message_mention_message FOREIGN KEY (message_id, message_kind)
        REFERENCES chat.message (id, kind) ON DELETE CASCADE,
    CONSTRAINT fk_message_mention_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_message_mention_kind CHECK (message_kind = 'INTERNAL_NOTE')
);

COMMENT ON TABLE chat.message_mention IS 'The staff an internal note @mentions (kltn-docs 16 §4.6 step 16).';
CREATE INDEX ix_message_mention_user ON chat.message_mention (user_id);

-- ---------------------------------------------------------------------------------------------
-- chat.message_attachment
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat.message_attachment
(
    id               UUID         NOT NULL,
    message_id       UUID         NOT NULL,
    message_kind     VARCHAR(16)  NOT NULL DEFAULT 'ATTACHMENT',
    file_key         VARCHAR(512) NOT NULL,
    original_name    VARCHAR(255),
    content_type     VARCHAR(100) NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    sha256           VARCHAR(64)  NOT NULL,
    width            INTEGER,
    height           INTEGER,
    uploaded_by      UUID         NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_message_attachment PRIMARY KEY (id),
    CONSTRAINT uk_message_attachment_file UNIQUE (file_key),
    CONSTRAINT fk_message_attachment_message FOREIGN KEY (message_id, message_kind)
        REFERENCES chat.message (id, kind) ON DELETE RESTRICT,
    CONSTRAINT fk_message_attachment_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_message_attachment_kind CHECK (message_kind IN ('ATTACHMENT', 'INTERNAL_NOTE')),
    -- 16 edge case "file quá lớn / định dạng lạ": images and PDF, 20 MB (FileCategory.CHAT_ATTACHMENT).
    CONSTRAINT ck_message_attachment_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp',
        'application/pdf')),
    CONSTRAINT ck_message_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    CONSTRAINT ck_message_attachment_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_message_attachment_dimensions CHECK ((width IS NULL) = (height IS NULL)
        AND (width IS NULL OR (width > 0 AND height > 0)))
);

COMMENT ON TABLE chat.message_attachment IS
    'A file sent in a conversation, stored through common storage after its virus scan. Seen by whoever sees its message.';
CREATE INDEX ix_message_attachment_message ON chat.message_attachment (message_id);
CREATE INDEX ix_message_attachment_uploaded_by ON chat.message_attachment (uploaded_by);

-- ---------------------------------------------------------------------------------------------
-- chat.participant_read
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat.participant_read
(
    id                   UUID        NOT NULL,
    conversation_id      UUID        NOT NULL,
    user_id              UUID        NOT NULL,
    reader_type          VARCHAR(16) NOT NULL,
    last_read_seq        BIGINT      NOT NULL DEFAULT 0,
    last_read_message_id UUID,
    last_read_at         TIMESTAMPTZ,

    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL,
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_participant_read PRIMARY KEY (id),
    CONSTRAINT uk_participant_read UNIQUE (conversation_id, user_id),
    CONSTRAINT fk_participant_read_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat.conversation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_participant_read_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_participant_read_message FOREIGN KEY (last_read_message_id, conversation_id)
        REFERENCES chat.message (id, conversation_id) ON DELETE RESTRICT,
    CONSTRAINT ck_participant_read_type CHECK (reader_type IN ('CUSTOMER', 'STAFF')),
    CONSTRAINT ck_participant_read_seq CHECK (last_read_seq >= 0
        AND (last_read_seq = 0) = (last_read_message_id IS NULL)
        AND (last_read_message_id IS NULL) = (last_read_at IS NULL))
);

COMMENT ON TABLE chat.participant_read IS
    'How far each participant has read a conversation: the sequence of the last message they saw. Only moves forward.';
CREATE INDEX ix_participant_read_user ON chat.participant_read (user_id);
CREATE INDEX ix_participant_read_message ON chat.participant_read (last_read_message_id)
    WHERE last_read_message_id IS NOT NULL;

CREATE FUNCTION chat.check_participant_read_forward() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.conversation_id, NEW.user_id) IS DISTINCT FROM (OLD.conversation_id, OLD.user_id)
        OR NEW.last_read_seq < OLD.last_read_seq THEN
        RAISE EXCEPTION 'read marker %: it is one reader''s in one conversation, and it only moves forward', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_participant_read_forward BEFORE UPDATE ON chat.participant_read
    FOR EACH ROW EXECUTE FUNCTION chat.check_participant_read_forward();

-- ---------------------------------------------------------------------------------------------
-- chat.conversation_event
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat.conversation_event
(
    id               UUID        NOT NULL,
    conversation_id  UUID        NOT NULL,
    type             VARCHAR(48) NOT NULL,
    actor_type       VARCHAR(16) NOT NULL,
    actor_id         UUID,
    occurred_at      TIMESTAMPTZ NOT NULL,
    from_status      VARCHAR(32),
    to_status        VARCHAR(32),
    context_id       UUID,
    message_id       UUID,
    target_user_id   UUID,
    note             VARCHAR(1000),
    detail           JSONB,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_conversation_event PRIMARY KEY (id),
    CONSTRAINT fk_conversation_event_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat.conversation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_event_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_conversation_event_target FOREIGN KEY (target_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_conversation_event_context FOREIGN KEY (context_id, conversation_id)
        REFERENCES chat.conversation_context (id, conversation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_event_message FOREIGN KEY (message_id, conversation_id)
        REFERENCES chat.message (id, conversation_id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_event_type CHECK (type IN ('OPENED', 'CONTINUED', 'CONTEXT_ATTACHED', 'QUEUED',
        'CLAIMED', 'ASSIGNED', 'TRANSFERRED', 'STATUS_CHANGED', 'FIRST_RESPONSE', 'INTERNAL_NOTE', 'MESSAGE_EDITED',
        'MESSAGE_DELETED', 'CARD_SENT', 'CARD_ACTION', 'CARD_STATE_CHANGED', 'CSAT_SUBMITTED', 'MERGED',
        'FIRST_RESPONSE_OVERDUE', 'RESOLUTION_OVERDUE')),
    CONSTRAINT ck_conversation_event_actor CHECK (actor_type IN ('CUSTOMER', 'AGENT', 'SYSTEM')
        AND (actor_type = 'SYSTEM' OR actor_id IS NOT NULL)),
    CONSTRAINT ck_conversation_event_status CHECK ((type = 'STATUS_CHANGED') <= (from_status IS NOT NULL
        AND to_status IS NOT NULL AND from_status <> to_status)),
    CONSTRAINT ck_conversation_event_detail CHECK (detail IS NULL OR jsonb_typeof(detail) = 'object')
);

COMMENT ON TABLE chat.conversation_event IS
    'kltn-docs 16 BR-01: every consequential action in a conversation, who did it and on which context. Append-only.';
CREATE INDEX ix_conversation_event_conversation ON chat.conversation_event (conversation_id, occurred_at);
CREATE INDEX ix_conversation_event_actor ON chat.conversation_event (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX ix_conversation_event_target ON chat.conversation_event (target_user_id)
    WHERE target_user_id IS NOT NULL;
CREATE INDEX ix_conversation_event_context ON chat.conversation_event (context_id) WHERE context_id IS NOT NULL;
CREATE INDEX ix_conversation_event_message ON chat.conversation_event (message_id) WHERE message_id IS NOT NULL;

CREATE FUNCTION chat.refuse_conversation_event_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'conversation event %: the conversation log is append-only (16 BR-01)', OLD.id
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER tg_conversation_event_append_only BEFORE UPDATE OR DELETE ON chat.conversation_event
    FOR EACH ROW EXECUTE FUNCTION chat.refuse_conversation_event_change();

-- ---------------------------------------------------------------------------------------------
-- chat.canned_response
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat.canned_response
(
    id               UUID          NOT NULL,
    -- NULL: shared with every agent; otherwise the agent's own.
    owner_id         UUID,
    title            VARCHAR(200)  NOT NULL,
    body             VARCHAR(4000) NOT NULL,
    shortcut         VARCHAR(32),
    category         VARCHAR(64),
    archived_at      TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_canned_response PRIMARY KEY (id),
    CONSTRAINT fk_canned_response_owner FOREIGN KEY (owner_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_canned_response_text CHECK (btrim(title) <> '' AND btrim(body) <> ''),
    CONSTRAINT ck_canned_response_shortcut CHECK (shortcut IS NULL OR shortcut ~ '^[a-z0-9][a-z0-9_-]{0,31}$')
);

COMMENT ON TABLE chat.canned_response IS
    'Sales'' saved replies; {variables} in the body (e.g. {customerName}) are filled in when used. Personal or shared.';
COMMENT ON COLUMN chat.canned_response.owner_id IS 'The agent whose own reply it is; NULL for one shared by everyone.';
-- One live shortcut per owner (and one among the shared replies).
CREATE UNIQUE INDEX uk_canned_response_shortcut ON chat.canned_response
    (coalesce(owner_id, '00000000-0000-0000-0000-000000000000'::uuid), shortcut)
    WHERE shortcut IS NOT NULL AND archived_at IS NULL;
CREATE INDEX ix_canned_response_owner ON chat.canned_response (owner_id) WHERE owner_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), p.resource || ':' || p.action, p.resource, p.action, 0, NOW(), 'flyway'
FROM (VALUES ('chat-conversations', 'VIEW_PAGE'), ('chat-conversations', 'READ'),
             ('chat-conversations', 'CREATE'), ('chat-conversations', 'UPDATE'),
             ('chat-supervision', 'VIEW_PAGE'), ('chat-supervision', 'READ'), ('chat-supervision', 'UPDATE'),
             ('chat-canned-responses', 'VIEW_PAGE'), ('chat-canned-responses', 'READ'),
             ('chat-canned-responses', 'CREATE'), ('chat-canned-responses', 'UPDATE'),
             ('chat-canned-responses', 'DELETE')) AS p(resource, action)
ON CONFLICT (code) DO NOTHING;

-- chat-conversations: the customer (its own conversations; scope OWN on the portal endpoints) and Sales already hold
-- it; the Sales Manager now does too. chat-supervision (assign, see every queue, take escalations, 16 §2): the Sales
-- Manager and the administrator. chat-canned-responses: whoever answers customers.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('CUSTOMER',      'chat-conversations',    'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('SALES_STAFF',   'chat-conversations',    'all'),
          ('SALES_MANAGER', 'chat-conversations',    'all'),
          ('SYSTEM_ADMIN',  'chat-conversations',    'all'),
          ('SALES_MANAGER', 'chat-supervision',      'all'),
          ('SYSTEM_ADMIN',  'chat-supervision',      'all'),
          ('SALES_STAFF',   'chat-canned-responses', 'all'),
          ('SALES_MANAGER', 'chat-canned-responses', 'all'),
          ('SYSTEM_ADMIN',  'chat-canned-responses', 'all')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND (g.actions = 'all' OR permission.action = ANY (string_to_array(g.actions, ',')))
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('SALES_STAFF', 'SALES_MANAGER', 'SYSTEM_ADMIN');
