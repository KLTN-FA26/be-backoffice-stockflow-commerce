-- =============================================================================
-- Procurement: supplier master data and supplier price lists - EXPAND.
-- Module: procurement   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase R
--
-- New tables beside the old procurement.supplier / purchase_order / po_line / goods_receipt /
-- qc_result / supplier_invoice, which SupplierJpaEntity and friends still map. The old ones go in
-- db/pending/C4__contract_procurement.sql once the procurement code has moved over.
-- procurement.po_number_sequence is reused as it is.
--
-- What a supplier sells is keyed on inventory.inventory_items (created in V20260928003000, which
-- adds that foreign key): purchasing is a warehouse document and "a warehouse document records
-- the inventory item" (docs 01, BR-07).
--
-- Audit columns follow BaseEntity (created_by is the username, VARCHAR). A person who performs a
-- business step (submits, approves, receives...) is a separate UUID column with a foreign key to
-- identity.app_user, because it is a fact of the process, not bookkeeping.
-- =============================================================================


CREATE TABLE procurement.suppliers
(
    id                     UUID          NOT NULL,
    code                   VARCHAR(30)   NOT NULL,
    name                   VARCHAR(200)  NOT NULL,
    legal_name             VARCHAR(200),
    tax_id                 VARCHAR(30),
    status                 VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    currency               VARCHAR(3)    NOT NULL DEFAULT 'VND',
    payment_terms          VARCHAR(50),
    incoterms              VARCHAR(10),
    -- BR-04: a receipt may exceed the ordered quantity by at most this percentage.
    over_receipt_tolerance NUMERIC(5, 2),
    lead_time_days         INTEGER       DEFAULT 7,
    bank_account_name      VARCHAR(200),
    bank_account_number    VARCHAR(50),
    bank_name              VARCHAR(150),
    note                   TEXT,

    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by             VARCHAR(100),
    last_modified_at       TIMESTAMPTZ,
    last_modified_by       VARCHAR(100),

    CONSTRAINT pk_suppliers PRIMARY KEY (id),
    CONSTRAINT uk_suppliers_code UNIQUE (code),
    CONSTRAINT ck_suppliers_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_suppliers_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLACKLISTED')),
    CONSTRAINT ck_suppliers_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_suppliers_tolerance
        CHECK (over_receipt_tolerance IS NULL OR over_receipt_tolerance BETWEEN 0 AND 100),
    CONSTRAINT ck_suppliers_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0)
);

-- A tax id identifies one legal entity; optional because a small supplier may not have one yet.
CREATE UNIQUE INDEX uk_suppliers_tax_id ON procurement.suppliers (tax_id) WHERE tax_id IS NOT NULL;


-- Two-level Vietnamese address (province -> ward, since the 2025 reform), like customer.address.
CREATE TABLE procurement.supplier_addresses
(
    id               UUID         NOT NULL,
    supplier_id      UUID         NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    line1            VARCHAR(200) NOT NULL,
    line2            VARCHAR(200),
    ward             VARCHAR(120),
    ward_code        VARCHAR(20),
    province         VARCHAR(120),
    province_code    VARCHAR(20),
    country          VARCHAR(2)   NOT NULL DEFAULT 'VN',
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier_addresses PRIMARY KEY (id),
    CONSTRAINT fk_supplier_addresses_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE CASCADE,
    CONSTRAINT ck_supplier_addresses_type CHECK (type IN ('OFFICE', 'WAREHOUSE', 'BILLING', 'RETURN')),
    CONSTRAINT ck_supplier_addresses_country CHECK (country ~ '^[A-Z]{2}$')
);

CREATE INDEX ix_supplier_addresses_supplier ON procurement.supplier_addresses (supplier_id);
CREATE UNIQUE INDEX uk_supplier_addresses_default
    ON procurement.supplier_addresses (supplier_id, type) WHERE is_default;


CREATE TABLE procurement.supplier_contacts
(
    id               UUID         NOT NULL,
    supplier_id      UUID         NOT NULL,
    full_name        VARCHAR(150) NOT NULL,
    phone            VARCHAR(30),
    email            VARCHAR(150),
    position         VARCHAR(100),
    is_primary       BOOLEAN      NOT NULL DEFAULT FALSE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier_contacts PRIMARY KEY (id),
    CONSTRAINT fk_supplier_contacts_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE CASCADE,
    CONSTRAINT ck_supplier_contacts_reachable CHECK (phone IS NOT NULL OR email IS NOT NULL)
);

CREATE INDEX ix_supplier_contacts_supplier ON procurement.supplier_contacts (supplier_id);
CREATE UNIQUE INDEX uk_supplier_contacts_primary
    ON procurement.supplier_contacts (supplier_id) WHERE is_primary;


-- What a supplier sells: one row per (supplier, inventory item), with the current buying terms.
CREATE TABLE procurement.supplier_items
(
    id                UUID           NOT NULL,
    supplier_id       UUID           NOT NULL,
    inventory_item_id UUID           NOT NULL,
    supplier_sku_code VARCHAR(50),
    current_price     NUMERIC(18, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'VND',
    moq               INTEGER        NOT NULL DEFAULT 1,
    pack_size         INTEGER        NOT NULL DEFAULT 1,
    lead_time_days    INTEGER        DEFAULT 7,
    is_preferred      BOOLEAN        NOT NULL DEFAULT FALSE,

    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_supplier_items PRIMARY KEY (id),
    CONSTRAINT uk_supplier_items_pair UNIQUE (supplier_id, inventory_item_id),
    CONSTRAINT fk_supplier_items_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE CASCADE,
    CONSTRAINT ck_supplier_items_price CHECK (current_price > 0),
    CONSTRAINT ck_supplier_items_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_supplier_items_moq CHECK (moq >= 1),
    CONSTRAINT ck_supplier_items_pack_size CHECK (pack_size >= 1),
    CONSTRAINT ck_supplier_items_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0)
);

CREATE INDEX ix_supplier_items_item ON procurement.supplier_items (inventory_item_id);
-- One preferred supplier per item: what replenishment proposes by default.
CREATE UNIQUE INDEX uk_supplier_items_preferred
    ON procurement.supplier_items (inventory_item_id) WHERE is_preferred;


-- Price history. Append a row per price change; the periods of one supplier item never overlap,
-- so "the price on date D" has exactly one answer (btree_gist exclusion below).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE procurement.supplier_item_prices
(
    id               UUID           NOT NULL,
    supplier_item_id UUID           NOT NULL,
    unit_price       NUMERIC(18, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'VND',
    effective_from   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    effective_to     TIMESTAMPTZ,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier_item_prices PRIMARY KEY (id),
    CONSTRAINT uk_supplier_item_prices_from UNIQUE (supplier_item_id, effective_from),
    CONSTRAINT fk_supplier_item_prices_item FOREIGN KEY (supplier_item_id)
        REFERENCES procurement.supplier_items (id) ON DELETE CASCADE,
    CONSTRAINT ck_supplier_item_prices_price CHECK (unit_price > 0),
    CONSTRAINT ck_supplier_item_prices_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_supplier_item_prices_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_supplier_item_prices_overlap EXCLUDE USING gist (
        supplier_item_id WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&)
);
