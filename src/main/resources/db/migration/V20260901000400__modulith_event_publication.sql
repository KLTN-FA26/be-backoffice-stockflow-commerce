-- =============================================================================
-- Spring Modulith's event publication registry.
--
-- WHAT IT IS. When a module publishes an event, Modulith writes one row here per listener,
-- INSIDE the publisher's transaction, and deletes it when that listener completes normally.
--
-- WHY IT MATTERS. It is the reason this codebase has no outbox table and no message broker.
-- The classic problem - "commit the data, then publish; what if the publish is lost?" - is solved
-- by making the publication part of the same commit. A listener that crashes leaves its row
-- behind, and spring.modulith.events.republish-outstanding-events-on-restart replays it.
--
-- WHY IT IS DECLARED HERE rather than left to spring.modulith.events.jdbc.schema-initialization.
-- Auto-creation at startup is convenient in development and wrong in production: the schema would
-- differ from what Flyway believes is deployed, and no migration would record it. One source of
-- truth for the schema, and it is this directory.
--
-- WHY IT IS IN public AND NOT IN A modulith SCHEMA. This project uses spring-modulith-starter-JPA,
-- whose JpaEventPublication entity is mapped to an unqualified table name, so Hibernate looks for
-- it on the connection's default search path. The spring.modulith.events.jdbc.schema property that
-- would move it belongs to the JDBC registry, not the JPA one, and setting it here would leave
-- ddl-auto=validate hunting for a table that is not where the entity says it is - a context that
-- refuses to start, with an error pointing at the wrong thing.
--
-- The column names and types are Modulith's, not ours - do not rename them.
-- =============================================================================

CREATE TABLE event_publication
(
    id               UUID         NOT NULL,
    listener_id      TEXT         NOT NULL,
    event_type       TEXT         NOT NULL,
    serialized_event TEXT         NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);

-- The query the restart-republish and the incomplete-publications actuator endpoint both run.
CREATE INDEX ix_event_publication_incomplete
    ON event_publication (completion_date) WHERE completion_date IS NULL;

CREATE INDEX ix_event_publication_serialized
    ON event_publication (serialized_event, listener_id, completion_date);

COMMENT ON TABLE event_publication IS
    'Framework-owned. Written by Spring Modulith; no application module reads or writes it.';
