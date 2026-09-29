CREATE TABLE ordering.checkout_request (
    request_id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ordering.design_quote (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL UNIQUE,
    customer_id uuid NOT NULL,
    customer_user_id uuid NOT NULL,
    design_snapshot_id uuid NOT NULL,
    sku varchar(64) NOT NULL,
    revision integer NOT NULL CHECK(revision>=1),
    current_revision_issued boolean NOT NULL DEFAULT false,
    status varchar(32) NOT NULL CHECK(status IN ('DRAFT','SENT','CHANGES_REQUESTED','ACCEPTED','CANCELLED','CONSUMED')),
    accepted_by uuid,
    accepted_at timestamptz,
    consumed_order_id uuid REFERENCES ordering.customer_order(id),
    response_note varchar(2000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(100),
    last_modified_at timestamptz,
    last_modified_by varchar(100),
    CHECK ((status IN ('ACCEPTED','CONSUMED'))=(accepted_by IS NOT NULL AND accepted_at IS NOT NULL)),
    CHECK (accepted_by IS NULL OR accepted_by=customer_user_id),
    CHECK ((status='CONSUMED')=(consumed_order_id IS NOT NULL))
    ,CHECK (status NOT IN ('SENT','CHANGES_REQUESTED','ACCEPTED','CONSUMED') OR current_revision_issued)
    ,CHECK (status<>'DRAFT' OR NOT current_revision_issued)
);
CREATE TABLE ordering.design_quote_offer (
    quote_id uuid NOT NULL REFERENCES ordering.design_quote(id),
    revision integer NOT NULL CHECK(revision>=1),
    quantity integer NOT NULL CHECK(quantity>0),
    unit_price numeric(18,2) NOT NULL CHECK(unit_price>0 AND unit_price=trunc(unit_price)),
    currency varchar(3) NOT NULL CHECK(currency='VND'),
    valid_until timestamptz NOT NULL,
    terms varchar(2000) NOT NULL CHECK(length(trim(terms))>0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    PRIMARY KEY(quote_id,revision),
    CHECK(valid_until>created_at),
    CHECK(unit_price*quantity<=999999999999999.9999)
);
CREATE INDEX ix_quote_customer ON ordering.design_quote(customer_user_id,id);
CREATE TABLE ordering.design_quote_issue (
    quote_id uuid NOT NULL,
    revision integer NOT NULL,
    issued_at timestamptz NOT NULL,
    issued_by uuid NOT NULL,
    PRIMARY KEY(quote_id,revision),
    FOREIGN KEY(quote_id,revision) REFERENCES ordering.design_quote_offer(quote_id,revision)
);
CREATE FUNCTION ordering.preserve_quote_offer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Quote commercial revisions are immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER trg_quote_offer_immutable BEFORE UPDATE OR DELETE ON ordering.design_quote_offer
FOR EACH ROW EXECUTE FUNCTION ordering.preserve_quote_offer();
CREATE TRIGGER trg_quote_issue_immutable BEFORE UPDATE OR DELETE ON ordering.design_quote_issue
FOR EACH ROW EXECUTE FUNCTION ordering.preserve_quote_offer();

INSERT INTO identity.permission(id,code,resource,action,description,version,created_at,created_by)
SELECT gen_random_uuid(),'sales-quotes:'||a,'sales-quotes',a,'Versioned design quotations',0,now(),'flyway'
FROM unnest(ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE']) a ON CONFLICT(code) DO NOTHING;
INSERT INTO identity.role_permission(id,role_id,permission_id,version,created_at,created_by)
SELECT gen_random_uuid(),r.id,p.id,0,now(),'flyway'
FROM identity.app_role r JOIN identity.permission p ON p.resource='sales-quotes'
WHERE (r.code IN ('SALES_STAFF','ORDER_COORDINATOR','ECOMMERCE_ADMIN')
       OR (r.code='CUSTOMER' AND p.action IN ('READ','UPDATE')))
AND NOT EXISTS(SELECT 1 FROM identity.role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
