-- =============================================================================
-- Ordering: carts, the order status timeline, and returns (RMA) (docs 14, 17).
-- Module: order (schema ordering)   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase G
--
--   cart / cart_line      docs 14 §5.1: ACTIVE -> ABANDONED / CONVERTED / MERGED (a guest cart
--                         merges into the account cart at login). Permission: sales-carts.
--   order_status_history  docs 17: "order timeline" - every transition, who, why. Append-only.
--   return_request(_line) docs 17: Return Requested -> Returned -> Refunded. Permission: sales-rmas.
--                         Returned goods re-enter stock through a putaway task (docs 09 BR-06:
--                         never straight into on-hand), hence the putaway_task column at the end.
-- =============================================================================


CREATE TABLE ordering.cart
(
    id                  UUID         NOT NULL,
    customer_id         UUID,
    -- A guest's cart is found by an opaque token in a cookie, never by an id in a URL.
    guest_token         VARCHAR(64),
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    currency            VARCHAR(3)   NOT NULL DEFAULT 'VND',
    converted_order_id  UUID,
    merged_into_cart_id UUID,
    last_activity_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_cart PRIMARY KEY (id),
    CONSTRAINT uk_cart_guest_token UNIQUE (guest_token),
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cart_order FOREIGN KEY (converted_order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cart_merged_into FOREIGN KEY (merged_into_cart_id)
        REFERENCES ordering.cart (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cart_owner CHECK (customer_id IS NOT NULL OR guest_token IS NOT NULL),
    CONSTRAINT ck_cart_status CHECK (status IN ('ACTIVE', 'ABANDONED', 'CONVERTED', 'MERGED')),
    CONSTRAINT ck_cart_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_cart_converted CHECK ((status = 'CONVERTED') = (converted_order_id IS NOT NULL)),
    CONSTRAINT ck_cart_merged CHECK ((status = 'MERGED') = (merged_into_cart_id IS NOT NULL)),
    CONSTRAINT ck_cart_not_self CHECK (merged_into_cart_id IS NULL OR merged_into_cart_id <> id)
);

-- One live cart per signed-in customer; the one a page load finds.
CREATE UNIQUE INDEX uk_cart_active_customer ON ordering.cart (customer_id)
    WHERE status = 'ACTIVE' AND customer_id IS NOT NULL;
CREATE INDEX ix_cart_abandon_sweep ON ordering.cart (last_activity_at) WHERE status = 'ACTIVE';


CREATE TABLE ordering.cart_line
(
    id                  UUID           NOT NULL,
    cart_id             UUID           NOT NULL,
    sku                 VARCHAR(64)    NOT NULL,
    quantity            INTEGER        NOT NULL,
    design_snapshot_id  UUID,
    -- The price shown when the line was added, to warn the shopper if it changed; checkout always
    -- re-prices from the catalog.
    unit_price_snapshot NUMERIC(19, 4),
    added_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_cart_line PRIMARY KEY (id),
    CONSTRAINT fk_cart_line_cart FOREIGN KEY (cart_id) REFERENCES ordering.cart (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_line_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_cart_line_design FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cart_line_qty CHECK (quantity BETWEEN 1 AND 999),
    CONSTRAINT ck_cart_line_price CHECK (unit_price_snapshot IS NULL OR unit_price_snapshot >= 0)
);

-- The same SKU with the same design is one line whose quantity grows; a different design of the
-- same SKU is a different line. COALESCE: NULLs are distinct in a unique index.
CREATE UNIQUE INDEX uk_cart_line_item ON ordering.cart_line
    (cart_id, sku, COALESCE(design_snapshot_id, '00000000-0000-0000-0000-000000000000'::uuid));


CREATE TABLE ordering.order_status_history
(
    id               UUID         NOT NULL,
    order_id         UUID         NOT NULL,
    from_status      VARCHAR(32),
    to_status        VARCHAR(32)  NOT NULL,
    reason           VARCHAR(500),
    actor_id         UUID,
    occurred_at      TIMESTAMPTZ  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_status_history PRIMARY KEY (id),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_status_history_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    -- The same alphabet as ck_order_status, without repeating its list: when the order lifecycle
    -- grows (docs 17 has more states than the code today), history must not need a migration too.
    CONSTRAINT ck_order_status_history_status CHECK (to_status ~ '^[A-Z_]{1,32}$'
        AND (from_status IS NULL OR from_status ~ '^[A-Z_]{1,32}$')),
    CONSTRAINT ck_order_status_history_change CHECK (from_status IS DISTINCT FROM to_status)
);

CREATE INDEX ix_order_status_history_order ON ordering.order_status_history (order_id, occurred_at);

CREATE TRIGGER tg_order_status_history_append_only
    BEFORE UPDATE OR DELETE ON ordering.order_status_history
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');


CREATE TABLE ordering.return_request
(
    id               UUID          NOT NULL,
    rma_number       VARCHAR(30)   NOT NULL,
    order_id         UUID          NOT NULL,
    customer_id      UUID,
    status           VARCHAR(16)   NOT NULL DEFAULT 'REQUESTED',
    reason_code      VARCHAR(24)   NOT NULL,
    note             VARCHAR(2000),
    requested_at     TIMESTAMPTZ   NOT NULL,
    decided_by       UUID,
    decided_at       TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    received_at      TIMESTAMPTZ,
    refund_id        UUID,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_return_request PRIMARY KEY (id),
    CONSTRAINT uk_return_request_number UNIQUE (rma_number),
    CONSTRAINT fk_return_request_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_request_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_request_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_return_request_refund FOREIGN KEY (refund_id)
        REFERENCES payment.refund (id) ON DELETE RESTRICT,
    CONSTRAINT ck_return_request_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED',
        'IN_TRANSIT', 'RECEIVED', 'REFUNDED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_return_request_reason CHECK (reason_code IN ('DEFECTIVE', 'DAMAGED_IN_TRANSIT',
        'WRONG_ITEM', 'NOT_AS_DESCRIBED', 'CHANGED_MIND', 'OTHER')),
    CONSTRAINT ck_return_request_decided CHECK (status IN ('REQUESTED', 'CANCELLED')
        OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_return_request_rejected CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CONSTRAINT ck_return_request_received CHECK (status NOT IN ('RECEIVED', 'REFUNDED')
        OR received_at IS NOT NULL),
    CONSTRAINT ck_return_request_refunded CHECK (status <> 'REFUNDED' OR refund_id IS NOT NULL)
);

CREATE INDEX ix_return_request_order ON ordering.return_request (order_id);
CREATE INDEX ix_return_request_status ON ordering.return_request (status);


CREATE TABLE ordering.return_request_line
(
    id                UUID         NOT NULL,
    return_request_id UUID         NOT NULL,
    order_line_id     UUID         NOT NULL,
    quantity          INTEGER      NOT NULL,
    -- Filled in when the goods arrive and are inspected.
    item_condition    VARCHAR(16),
    disposition       VARCHAR(16),

    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_return_request_line PRIMARY KEY (id),
    CONSTRAINT uk_return_request_line_pair UNIQUE (return_request_id, order_line_id),
    CONSTRAINT fk_return_request_line_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_line_order_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT ck_return_request_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_return_request_line_condition CHECK (item_condition IS NULL
        OR item_condition IN ('UNOPENED', 'GOOD', 'DAMAGED', 'DEFECTIVE')),
    -- docs 09 step 9: back to AVAILABLE if intact, QUARANTINE if doubtful, scrapped if broken.
    CONSTRAINT ck_return_request_line_disposition CHECK (disposition IS NULL
        OR disposition IN ('RESTOCK', 'QUARANTINE', 'SCRAP')),
    CONSTRAINT ck_return_request_line_inspected CHECK (disposition IS NULL OR item_condition IS NOT NULL)
);

CREATE INDEX ix_return_request_line_order_line ON ordering.return_request_line (order_line_id);


-- A return returns lines of its own order, and never more units of a line than were ordered,
-- counting every return of that line that is still alive.
CREATE FUNCTION ordering.check_return_request_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    ordered  INTEGER;
    returned INTEGER;
BEGIN
    SELECT l.quantity INTO ordered
      FROM ordering.order_line l
      JOIN ordering.return_request r ON r.order_id = l.order_id
     WHERE l.id = NEW.order_line_id AND r.id = NEW.return_request_id
       FOR UPDATE OF l;
    IF ordered IS NULL THEN
        RAISE EXCEPTION 'return line must be a line of the returned order' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(SUM(x.quantity), 0) INTO returned
      FROM ordering.return_request_line x
      JOIN ordering.return_request r ON r.id = x.return_request_id
     WHERE x.order_line_id = NEW.order_line_id AND x.id <> NEW.id
       AND r.status NOT IN ('REJECTED', 'CANCELLED');
    IF returned + NEW.quantity > ordered THEN
        RAISE EXCEPTION 'would return % of % ordered', returned + NEW.quantity, ordered
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_line_check BEFORE INSERT OR UPDATE ON ordering.return_request_line
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_request_line();


-- Returned goods are put away like any arrival (docs 09 BR-06).
ALTER TABLE warehouse.putaway_task
    ADD COLUMN return_request_line_id UUID,
    ADD CONSTRAINT fk_putaway_task_return_line FOREIGN KEY (return_request_line_id)
        REFERENCES ordering.return_request_line (id) ON DELETE RESTRICT;
