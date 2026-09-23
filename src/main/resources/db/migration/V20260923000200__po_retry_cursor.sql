-- Only the scheduler position is stored here; Modulith remains the source of pending events.
CREATE TABLE notification.po_retry_cursor (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_publication_id UUID
);
INSERT INTO notification.po_retry_cursor (id) VALUES (1);
