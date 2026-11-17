-- The process flows join the assistant's knowledge base (design §6.1): 04c-all-flows.md, the business and exception
-- flows in Vietnamese, and 04-data-flows.md, the four end-to-end flows in English. A flow is cut at its own code
-- (F-PO, X-SHORT, Flow A), not at a rule's row or a state machine's section, so it is a kind of its own; the indexer
-- writes its rows on the next start.
ALTER TABLE assistant.knowledge_chunk DROP CONSTRAINT ck_knowledge_chunk_kind;
ALTER TABLE assistant.knowledge_chunk ADD CONSTRAINT ck_knowledge_chunk_kind
    CHECK (kind IN ('RULE', 'TERM', 'STATE_MACHINE', 'FLOW'));
