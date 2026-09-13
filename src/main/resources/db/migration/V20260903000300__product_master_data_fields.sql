-- SCRUM-56 (WBS 3.1.1.2): the product master data fields that were still missing per the TODO
-- left on ProductJpaEntity — brand, tax class, a media gallery, and bilingual name/description.
-- Approval-workflow columns (submitted_by, approved_by, ...) are a separate migration on the
-- stacked SCRUM-57 branch, since that subtask is scoped and reviewed independently.

ALTER TABLE product.product
    ADD COLUMN name_en        VARCHAR(300),
    ADD COLUMN description_en VARCHAR(2000),
    ADD COLUMN brand          VARCHAR(150),
    ADD COLUMN tax_class      VARCHAR(32);

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_tax_class
        CHECK (tax_class IS NULL OR tax_class IN ('STANDARD', 'REDUCED', 'EXEMPT'));

-- name_en/description_en/brand/tax_class stay nullable at the DB level even though the create
-- endpoint requires them: the table already has rows (none in practice yet, but the migration
-- itself must not assume that), and the real enforcement is the CreateProductRequest validation.

-- No version/audit columns: mirrors inventory.stock_reservation, the other child row that is
-- fully cascade-managed by its aggregate (loaded/saved as a unit, never edited on its own) rather
-- than variant/sku/print_config's plain-standalone-row shape.
CREATE TABLE product.product_image
(
    id         UUID         NOT NULL,
    product_id UUID         NOT NULL,
    url        VARCHAR(500) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_product_image PRIMARY KEY (id),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id)
        REFERENCES product.product (id) ON DELETE CASCADE
);

CREATE INDEX ix_product_image_product ON product.product_image (product_id);

COMMENT ON TABLE product.product_image IS 'Media gallery for a product master row (WBS 3.1.1.2).';
