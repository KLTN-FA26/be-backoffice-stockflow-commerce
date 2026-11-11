-- Formerly the sample base prices of the two canonical demo products (SOFA-3S-GREY, TABLE-OAK-160).
--
-- Emptied by team plan 5-3, not deleted: V20261110000100 dropped catalog.pricing_rule, since the storefront shows no
-- price and a customer's own price list is the one source of a price (kltn-docs 13 BR-04, D14). Flyway validates the
-- repeatable migrations it has applied, so a demo database that ran the old body would refuse to start were the file
-- gone; with the body changed it runs this one, which does nothing.
SELECT 1;
