-- =============================================================================
-- WF04 G2: every change of an order's delivery address, kept as written (kltn-docs 17 §4.7 steps 23-24, BR-16;
-- 08 BR-04). Sales change the address from the customer's address book (14 §4.2 step 9, 18) until the order's first
-- carton is labelled; tg_order_label_lock (baseline) already refuses the change after that, in the database.
--
-- A table of its own, like Sales' decisions on partial shipments and the closures of the rest
-- (ordering.order_partial_shipment_decision, ordering.order_remainder_closure): an address change is not a status
-- change, so it has nothing to put in ordering.order_status_history. GET /orders/{id}/history lists it with the order's
-- status changes as an entry of its own type (ADDRESS_CHANGE), for staff only. Both addresses are kept whole, so the
-- entry says what the address was and what it became even after later changes.
--
-- No backfill: until now the address never changed after the order was drafted.
-- =============================================================================

CREATE TABLE ordering.order_address_change (
    id                      uuid         NOT NULL,
    order_id                uuid         NOT NULL,
    order_status            varchar(32)  NOT NULL,
    previous_recipient_name varchar(200),
    previous_phone          varchar(32),
    previous_line1          varchar(255),
    previous_line2          varchar(255),
    previous_ward_code      varchar(20),
    previous_ward_name      varchar(120),
    previous_province_code  varchar(20),
    previous_province_name  varchar(120),
    previous_country_code   varchar(2),
    previous_postal_code    varchar(20),
    new_recipient_name      varchar(200) NOT NULL,
    new_phone               varchar(32)  NOT NULL,
    new_line1               varchar(255) NOT NULL,
    new_line2               varchar(255),
    new_ward_code           varchar(20)  NOT NULL,
    new_ward_name           varchar(120) NOT NULL,
    new_province_code       varchar(20)  NOT NULL,
    new_province_name       varchar(120) NOT NULL,
    new_country_code        varchar(2)   NOT NULL,
    new_postal_code         varchar(20),
    changed_by              uuid         NOT NULL,
    changed_at              timestamptz  NOT NULL,
    version                 bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    created_by              varchar(100),
    last_modified_at        timestamptz,
    last_modified_by        varchar(100),
    CONSTRAINT pk_order_address_change PRIMARY KEY (id),
    -- An OrderStatus name, the order's when it changed; only the shape is checked, as ck_order_partial_shipment_decision_status does.
    CONSTRAINT ck_order_address_change_status CHECK (order_status ~ '^[A-Z_]{1,32}$'),
    -- Only Vietnamese addresses are delivered to (OrderAddressSnapshot, ck_order_customer_or_guest).
    CONSTRAINT ck_order_address_change_country CHECK (new_country_code = 'VN'),
    CONSTRAINT fk_order_address_change_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_address_change_actor FOREIGN KEY (changed_by) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_order_address_change_order ON ordering.order_address_change (order_id, changed_at);

CREATE TRIGGER tg_order_address_change_append_only BEFORE DELETE OR UPDATE ON ordering.order_address_change
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

COMMENT ON TABLE ordering.order_address_change IS 'Every change of an order''s delivery address by Sales before its first carton was labelled (kltn-docs 17 BR-16): who, when, from what to what. Append-only; shown to staff in the order''s history.';
