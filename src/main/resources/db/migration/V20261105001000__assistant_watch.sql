-- The assistant's watch (design §9.1, ADR-0012): every few minutes it reads, as each back-office user, a few of their
-- lists (orders on hold, purchase orders gone late, SKUs at their reorder point) and tells them in the in-app inbox
-- what is new since it last looked. Optionally it also answers a fixed question about the new items in a conversation
-- of theirs, which they find in their own list.

-- Who opened a conversation: the user asking (CHAT), or the watch for them (PROACTIVE). A proactive conversation is
-- still its owner's, read and continued like any other; only its first question is the watch's, and that one does not
-- count against the owner's daily limit. Existing rows are the users' own, and so is a row written without saying
-- (the application always says): CHAT is the safe reading, since it is the one the daily limit counts.
ALTER TABLE assistant.conversation
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    ADD CONSTRAINT ck_conversation_origin CHECK (origin IN ('CHAT', 'PROACTIVE'));

-- The watch's daily cap on proactive conversations counts these.
CREATE INDEX ix_conversation_proactive ON assistant.conversation (owner_user_id, created_at)
    WHERE origin = 'PROACTIVE';

-- How the watch looks at a section for a user: since when, how (the user's scope and warehouses then) and last when.
-- A first look records what is there without telling anyone; so does a look after the view changed (a manager moved to
-- another warehouse sees that warehouse's old news, which is not news) or after a long pause (the watch switched off
-- for a while). A section the user may no longer read loses its row, so getting the tool back is a first look too.
CREATE TABLE assistant.watch_state
(
    owner_user_id    UUID          NOT NULL,
    section          VARCHAR(40)   NOT NULL,
    -- SHA-256 of the view (scope and warehouse ids): a manager of many warehouses has a long one.
    view             VARCHAR(64)   NOT NULL,
    started_at       TIMESTAMPTZ   NOT NULL,
    last_looked_at   TIMESTAMPTZ   NOT NULL,
    -- Since when the list has been longer than one read, so its news is not posted; the user is told once.
    partial_since    TIMESTAMPTZ,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_watch_state PRIMARY KEY (owner_user_id, section),
    CONSTRAINT fk_watch_state_owner FOREIGN KEY (owner_user_id) REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_watch_state_section CHECK (section IN ('lowStock', 'heldOrders', 'overduePurchaseOrders')),
    CONSTRAINT ck_watch_state_view CHECK (view ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_watch_state_looked CHECK (last_looked_at >= started_at),
    CONSTRAINT ck_watch_state_partial CHECK (partial_since IS NULL OR partial_since >= started_at)
);

-- What the watch has seen on a user's list: one row per item while it is on it. A list read whole drops the rows of
-- items no longer on it, so an item's return is news again; a list read only in part (more items than one read
-- returns) drops nothing, since an item missing from it may just be further down.
CREATE TABLE assistant.watch_item
(
    id               UUID          NOT NULL,
    owner_user_id    UUID          NOT NULL,
    section          VARCHAR(40)   NOT NULL,
    -- The item's own codes, as its list shows them: a SKU; an order number with its hold reason; a PO number.
    item_key         VARCHAR(200)  NOT NULL,
    first_seen_at    TIMESTAMPTZ   NOT NULL,
    last_seen_at     TIMESTAMPTZ   NOT NULL,
    -- When the item was dealt with: posted to its owner, or set aside unposted (a first look's items, a section the
    -- watch does not post). Null while it waits, so a look that stopped half way finishes it at the next.
    notified_at      TIMESTAMPTZ,
    -- When the watch answered a question about it, and in which conversation (gone with it).
    analysed_at      TIMESTAMPTZ,
    conversation_id  UUID,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_watch_item PRIMARY KEY (id),
    CONSTRAINT fk_watch_item_owner FOREIGN KEY (owner_user_id) REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_watch_item_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant.conversation (id) ON DELETE SET NULL,
    CONSTRAINT fk_watch_item_state FOREIGN KEY (owner_user_id, section)
        REFERENCES assistant.watch_state (owner_user_id, section) ON DELETE CASCADE,
    CONSTRAINT uk_watch_item UNIQUE (owner_user_id, section, item_key),
    CONSTRAINT ck_watch_item_key CHECK (length(btrim(item_key)) > 0),
    CONSTRAINT ck_watch_item_seen CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT ck_watch_item_analysis CHECK (conversation_id IS NULL OR analysed_at IS NOT NULL)
);

-- The items still to deal with, per user.
CREATE INDEX ix_watch_item_pending ON assistant.watch_item (owner_user_id) WHERE notified_at IS NULL;
CREATE INDEX ix_watch_item_conversation ON assistant.watch_item (conversation_id) WHERE conversation_id IS NOT NULL;
-- The retention job's sweep of what nobody has looked at for a long time.
CREATE INDEX ix_watch_state_looked ON assistant.watch_state (last_looked_at);
