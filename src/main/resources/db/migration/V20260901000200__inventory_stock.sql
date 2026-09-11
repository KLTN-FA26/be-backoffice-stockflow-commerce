-- =============================================================================
-- Inventory: stock on hand and the holds placed against it.
-- Module: inventory   Aggregate: StockItem   BRD 3.6, 3.10
-- =============================================================================

CREATE TABLE inventory.stock_item
(
    id               UUID         NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    location_code    VARCHAR(64)  NOT NULL,
    -- Nullable: not every product is lot-tracked. Part of the natural key, which is why the
    -- unique index below uses COALESCE - in SQL, NULL <> NULL, so a plain UNIQUE constraint
    -- would happily accept the same (sku, location) pair twice when lot_number is NULL.
    lot_number       VARCHAR(64),
    expiry_date      DATE,

    on_hand          INTEGER      NOT NULL DEFAULT 0,
    -- Denormalised sum of active reservations. Kept because the storefront asks for ATP on every
    -- product view and must not aggregate the child table to answer. The application recomputes
    -- it on every save, and the CHECK below is the safety net if that ever goes wrong.
    reserved         INTEGER      NOT NULL DEFAULT 0,
    status           VARCHAR(32)  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_stock_item PRIMARY KEY (id),
    CONSTRAINT ck_stock_item_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT ck_stock_item_reserved_non_negative CHECK (reserved >= 0),
    -- THE anti-oversell invariant, stated once in the domain model and once here. Duplication on
    -- purpose: the domain gives a good error message, the database is what a bad migration, a
    -- manual UPDATE or a future bug cannot talk its way past.
    CONSTRAINT ck_stock_item_reserved_within_on_hand CHECK (reserved <= on_hand),
    CONSTRAINT ck_stock_item_status
        CHECK (status IN ('AVAILABLE', 'QUARANTINE', 'DAMAGED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uk_stock_item_sku_location_lot
    ON inventory.stock_item (sku, location_code, COALESCE(lot_number, ''));

-- Partial index: the ATP query only ever looks at sellable rows, and in a mature warehouse most
-- rows are not. Indexing only those keeps it small enough to stay in cache.
CREATE INDEX ix_stock_item_sku_available
    ON inventory.stock_item (sku) WHERE status = 'AVAILABLE';

CREATE INDEX ix_stock_item_location ON inventory.stock_item (location_code);
CREATE INDEX ix_stock_item_expiry ON inventory.stock_item (expiry_date) WHERE expiry_date IS NOT NULL;

COMMENT ON TABLE inventory.stock_item IS
    'Stock of one SKU at one location from one lot. Owned by the inventory module.';
COMMENT ON COLUMN inventory.stock_item.reserved IS
    'Denormalised sum of stock_reservation rows in status HELD.';


CREATE TABLE inventory.stock_reservation
(
    id             UUID        NOT NULL,
    stock_item_id  UUID        NOT NULL,
    -- Reference into ordering.customer_order. No FOREIGN KEY: cross-schema constraints would tie
    -- the two modules' migrations together and block ever extracting one.
    order_id       UUID        NOT NULL,
    -- The caller's own idempotency key, shared by every hold one reserve() call produced.
    -- NOT unique: a line for ten units drawn as six-plus-four holds two rows with this same value.
    -- It is what the replay check reads, and that check has to run BEFORE lots are chosen - by the
    -- time a plan exists, the first attempt's holds have already changed what looks available.
    root_request_id UUID       NOT NULL,
    -- Derived per stock item from root_request_id. THIS is the one that carries the UNIQUE
    -- constraint: it differs per lot, so a multi-lot line does not collide with itself, while a
    -- retry of the same request against the same lot still cannot hold the stock twice.
    request_id     UUID        NOT NULL,
    quantity       INTEGER     NOT NULL,
    reserved_at    TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    status         VARCHAR(32) NOT NULL,
    release_reason VARCHAR(32),
    closed_at      TIMESTAMPTZ,

    CONSTRAINT pk_stock_reservation PRIMARY KEY (id),
    CONSTRAINT fk_reservation_stock_item
        FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id) ON DELETE CASCADE,
    CONSTRAINT uk_stock_reservation_request UNIQUE (request_id),
    CONSTRAINT ck_stock_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_reservation_status
        CHECK (status IN ('HELD', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    -- A closed reservation must say when it closed, and an open one must not pretend it has.
    CONSTRAINT ck_stock_reservation_closed_at
        CHECK ((status = 'HELD' AND closed_at IS NULL) OR (status <> 'HELD' AND closed_at IS NOT NULL))
);

CREATE INDEX ix_stock_reservation_order ON inventory.stock_reservation (order_id);

-- Drives the replay check on the hot checkout path, so it must be an index lookup.
CREATE INDEX ix_stock_reservation_root_request
    ON inventory.stock_reservation (root_request_id) WHERE status = 'HELD';

-- Drives the sweeper, which runs every minute. Partial on HELD because expired-and-closed rows
-- are kept forever as an audit trail and would otherwise dominate the index.
CREATE INDEX ix_stock_reservation_expiry
    ON inventory.stock_reservation (expires_at) WHERE status = 'HELD';

COMMENT ON TABLE inventory.stock_reservation IS
    'One hold on stock. Child of stock_item; never loaded independently.';
