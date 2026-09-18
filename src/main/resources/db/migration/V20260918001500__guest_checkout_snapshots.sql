-- SCRUM-46: guest checkout keeps immutable contact/address facts without creating an account.
ALTER TABLE ordering.customer_order
    ALTER COLUMN customer_id DROP NOT NULL,
    ADD COLUMN contact_name VARCHAR(200),
    ADD COLUMN contact_email VARCHAR(320),
    ADD COLUMN contact_phone VARCHAR(32),
    ADD COLUMN shipping_recipient_name VARCHAR(200),
    ADD COLUMN shipping_phone VARCHAR(32),
    ADD COLUMN shipping_line1 VARCHAR(255),
    ADD COLUMN shipping_line2 VARCHAR(255),
    ADD COLUMN shipping_ward_code VARCHAR(20),
    ADD COLUMN shipping_ward_name VARCHAR(120),
    ADD COLUMN shipping_province_code VARCHAR(20),
    ADD COLUMN shipping_province_name VARCHAR(120),
    ADD COLUMN shipping_country_code VARCHAR(2),
    ADD COLUMN shipping_postal_code VARCHAR(20),
    ADD COLUMN billing_recipient_name VARCHAR(200),
    ADD COLUMN billing_phone VARCHAR(32),
    ADD COLUMN billing_line1 VARCHAR(255),
    ADD COLUMN billing_line2 VARCHAR(255),
    ADD COLUMN billing_ward_code VARCHAR(20),
    ADD COLUMN billing_ward_name VARCHAR(120),
    ADD COLUMN billing_province_code VARCHAR(20),
    ADD COLUMN billing_province_name VARCHAR(120),
    ADD COLUMN billing_country_code VARCHAR(2),
    ADD COLUMN billing_postal_code VARCHAR(20);

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_customer_or_guest
        CHECK (customer_id IS NOT NULL OR (
            contact_name IS NOT NULL AND contact_email IS NOT NULL AND contact_phone IS NOT NULL
            AND shipping_recipient_name IS NOT NULL AND shipping_line1 IS NOT NULL
            AND shipping_ward_code IS NOT NULL AND shipping_province_code IS NOT NULL
            AND shipping_country_code = 'VN'
            AND billing_recipient_name IS NOT NULL AND billing_line1 IS NOT NULL
            AND billing_ward_code IS NOT NULL AND billing_province_code IS NOT NULL
            AND billing_country_code = 'VN'));

CREATE INDEX ix_order_guest_email
    ON ordering.customer_order (lower(contact_email), placed_at DESC)
    WHERE customer_id IS NULL;

COMMENT ON COLUMN ordering.customer_order.contact_email IS
    'Checkout snapshot. Required for guest orders; never updated from a later customer profile edit.';
