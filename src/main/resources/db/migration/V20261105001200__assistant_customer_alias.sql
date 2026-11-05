-- Customer aliases (design §5.5, ADR-0014): the model provider is sent "KH#1", "KH#2" where a tool result names a
-- customer, and the panel shows the real name. Which customer an alias stands for is kept here, inside the company's
-- database, and never sent anywhere: the provider learns that a customer owes 120 million, not which one.
--
-- An alias belongs to one conversation and stays the same for all of it, so the model can follow a customer from one
-- question to the next, and an answer stored with "KH#1" reads back as the right name whenever it is reopened. The
-- rows go with the conversation (retention deletes it). A customer is never deleted while a conversation names it
-- (ADR-0007 rule 5, RESTRICT across modules): a number freed that way would be given to the next customer, and old
-- answers would read as someone else.
CREATE TABLE assistant.customer_alias
(
    conversation_id  UUID          NOT NULL,
    -- The n of "KH#n": 1, 2, 3 ... in the order the conversation's tools first named each customer.
    alias_number     INTEGER       NOT NULL,
    customer_id      UUID          NOT NULL,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_customer_alias PRIMARY KEY (conversation_id, alias_number),
    CONSTRAINT uk_customer_alias_customer UNIQUE (conversation_id, customer_id),
    CONSTRAINT fk_customer_alias_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant.conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_alias_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_alias_number CHECK (alias_number > 0)
);

-- The FK's own index: deleting a customer finds its aliases without a scan.
CREATE INDEX ix_customer_alias_customer ON assistant.customer_alias (customer_id);
