-- SCRUM-113 (WBS 3.2.1) — one row per day, atomically incremented, same pattern as
-- ordering.order_number_sequence (see OrderNumberSequence's own javadoc for why this beats
-- select max(...)+1 or a plain Postgres sequence: it must reset per day and survive concurrent
-- checkouts without ever handing out the same number twice).

CREATE TABLE procurement.po_number_sequence
(
    sequence_date DATE   NOT NULL,
    last_value    BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_po_number_sequence PRIMARY KEY (sequence_date)
);
