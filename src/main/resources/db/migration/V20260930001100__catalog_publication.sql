-- SEO/slug source is catalog. Product remains the only owner of approval and master data.
-- A product has one canonical URL; catalog_entry retains the existing per-SKU projection.
CREATE TABLE catalog.product_listing (
    product_id uuid PRIMARY KEY,
    slug varchar(140) NOT NULL UNIQUE,
    seo_title varchar(300),
    seo_description varchar(500),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    projected_revision bigint NOT NULL DEFAULT -1,
    enabled boolean NOT NULL DEFAULT false,
    ever_published boolean NOT NULL DEFAULT false,
    published_title varchar(300),
    published_description varchar(2000),
    published_seo_title varchar(300),
    published_seo_description varchar(500),
    last_attempt_at timestamptz,
    CONSTRAINT ck_listing_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_listing_revision CHECK (projected_revision >= -1 AND projected_revision <= revision),
    CONSTRAINT ck_listing_enabled CHECK (NOT enabled OR ever_published)
);
ALTER TABLE catalog.catalog_entry ADD COLUMN product_id uuid, ADD COLUMN source_revision bigint;
CREATE INDEX ix_catalog_entry_product ON catalog.catalog_entry(product_id);
-- Reserved namespace for this story's editable base price; other pricing rules remain untouched.
CREATE UNIQUE INDEX uk_catalog_base_price ON catalog.pricing_rule(name) WHERE name LIKE 'BASE:%';
ALTER TABLE catalog.pricing_rule ADD CONSTRAINT ck_catalog_base_vnd CHECK
    (name NOT LIKE 'BASE:%' OR (currency='VND' AND price>0 AND price=trunc(price)));

CREATE FUNCTION catalog.preserve_published_slug() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ever_published AND (NEW.slug IS DISTINCT FROM OLD.slug OR NOT NEW.ever_published) THEN
        RAISE EXCEPTION 'A published canonical slug is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_catalog_slug BEFORE UPDATE ON catalog.product_listing
    FOR EACH ROW EXECUTE FUNCTION catalog.preserve_published_slug();
