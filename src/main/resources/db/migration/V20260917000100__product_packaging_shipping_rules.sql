-- SCRUM-75 (WBS 3.1.3.2) packaging specifications + SCRUM-76 (WBS 3.1.3.3) shipping rules &
-- restrictions on the product master. Nullable/false-default: a product can exist before these
-- are known, same treatment weight/dimensions got in V20260913000100.

ALTER TABLE product.product
    ADD COLUMN package_weight_kg           NUMERIC(10, 3),
    ADD COLUMN package_length_cm           NUMERIC(10, 2),
    ADD COLUMN package_width_cm            NUMERIC(10, 2),
    ADD COLUMN package_height_cm           NUMERIC(10, 2),
    ADD COLUMN package_count               INT,
    ADD COLUMN hazmat                      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN oversized                   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN requires_adult_signature    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN shipping_restriction_note   VARCHAR(500);

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_package_weight_positive
        CHECK (package_weight_kg IS NULL OR package_weight_kg > 0);

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_package_dimensions_positive
        CHECK ((package_length_cm IS NULL OR package_length_cm > 0)
           AND (package_width_cm IS NULL OR package_width_cm > 0)
           AND (package_height_cm IS NULL OR package_height_cm > 0));

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_package_count_positive
        CHECK (package_count IS NULL OR package_count > 0);
