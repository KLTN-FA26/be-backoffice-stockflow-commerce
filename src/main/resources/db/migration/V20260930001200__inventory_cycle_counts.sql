CREATE TABLE inventory.cycle_count (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL UNIQUE,
    warehouse_code varchar(64) NOT NULL,
    assigned_to uuid NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('PLANNED','COUNTING','VARIANCE_REVIEW','APPROVED','POSTED','CANCELLED')),
    approved_by uuid,
    approved_at timestamptz,
    posted_at timestamptz,
    note varchar(1000),
    mutation bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(100),
    last_modified_at timestamptz,
    last_modified_by varchar(100),
    CHECK (approved_by IS NULL OR approved_by<>assigned_to),
    CHECK (status<>'APPROVED' OR approved_by IS NOT NULL),
    CHECK ((approved_by IS NULL)=(approved_at IS NULL)),
    CHECK ((status='POSTED')=(posted_at IS NOT NULL))
);
CREATE TABLE inventory.cycle_count_line (
    count_id uuid NOT NULL REFERENCES inventory.cycle_count(id),
    stock_id uuid NOT NULL REFERENCES inventory.stock_item(id),
    baseline_qty integer NOT NULL CHECK (baseline_qty>=0),
    baseline_version bigint NOT NULL,
    counted_qty integer CHECK (counted_qty>=0),
    reason varchar(1000),
    active boolean NOT NULL DEFAULT true,
    PRIMARY KEY(count_id,stock_id),
    CHECK (counted_qty IS NULL OR counted_qty=baseline_qty OR (reason IS NOT NULL AND length(trim(reason))>0))
);
CREATE UNIQUE INDEX uk_active_count_stock ON inventory.cycle_count_line(stock_id) WHERE active;
CREATE TABLE inventory.stock_adjustment (
    id uuid PRIMARY KEY,
    count_id uuid NOT NULL REFERENCES inventory.cycle_count(id),
    stock_id uuid NOT NULL REFERENCES inventory.stock_item(id),
    before_qty integer NOT NULL CHECK(before_qty>=0),
    after_qty integer NOT NULL CHECK(after_qty>=0),
    counted_by uuid NOT NULL,
    approved_by uuid NOT NULL,
    reason varchar(1000) NOT NULL CHECK(length(trim(reason))>0),
    posted_at timestamptz NOT NULL,
    posting_tx bigint NOT NULL DEFAULT txid_current(),
    UNIQUE(count_id,stock_id),
    CHECK(before_qty<>after_qty),
    CHECK(counted_by<>approved_by)
);
CREATE INDEX ix_count_warehouse_status ON inventory.cycle_count(warehouse_code,status);
