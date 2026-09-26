-- Only the scheduler position is stored here; Modulith remains the source of pending events.
CREATE TABLE notification.po_retry_cursor (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_publication_id UUID
);
INSERT INTO notification.po_retry_cursor (id) VALUES (1);
ALTER TABLE notification.delivery_log ADD COLUMN terminal BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_event_publication_retry_page ON public.event_publication(event_type, listener_id, id)
    WHERE completion_date IS NULL;
COMMENT ON TABLE public.event_publication IS
    'Written exclusively by Spring Modulith; common PendingPublicationReader provides bounded read-only retry selection.';
