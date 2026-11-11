package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261110000100 (team plan 5-3) on the real upgrade path: a database migrated to the migration before it, holding
 * every kind of price the storefront had - a base price, a group price, a rule for every SKU, a promotion, a price
 * copied onto a storefront entry, a price on a variant's sales unit, a customer's own price - and the group-price
 * permission, then upgraded.
 *
 * <p>Proves nothing is lost (every row dropped and every value removed is archived, whole), the tables and the two
 * columns are gone while the entry, the sales unit and the customer's own price stay, {@code inventory.item_in_use} - which read
 * the price rules - still answers and still counts the customer's price, and every role that held the permission moves
 * its version while one that held none does not.</p>
 */
class CatalogPriceRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108003200";   // the migration right before it on the merged branch (final rescope merge), so other lines' role version bumps run first
    private static final String MIGRATION = "20261110000100";

    @Test
    void upgradeDropsEveryPriceButTheCustomersOwnKeepingThemInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID base = UUID.randomUUID();
                UUID group = UUID.randomUUID();
                UUID general = UUID.randomUUID();
                UUID promotion = UUID.randomUUID();
                UUID entry = UUID.randomUUID();
                UUID unpriced = UUID.randomUUID();
                UUID customerPrice = UUID.randomUUID();
                UUID pricedUnit = UUID.randomUUID();
                UUID baseUnit = UUID.randomUUID();
                long grants = RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.code LIKE 'catalog-segment-prices:%'");
                assertThat(grants).as("the permission is seeded and granted before").isPositive();
                long managerVersion = version(db, "SALES_MANAGER");
                long adminVersion = version(db, "SYSTEM_ADMIN");
                long salesVersion = version(db, "SALES_STAFF");
                long warehouseVersion = version(db, "WAREHOUSE_STAFF");

                // The variant, the group and the customer are not what this test is about: foreign keys and row
                // triggers are off while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO catalog.pricing_rule (id, name, sku, price, currency, priority, active, "
                        + "version, created_at, created_by) VALUES (?, 'BASE:CUP-OLD', 'CUP-OLD', 12500000, 'VND', 0, "
                        + "true, 3, NOW(), 'operator')", base);
                RawSql.update(db, "INSERT INTO catalog.pricing_rule (id, name, sku, segment_id, price, currency, created_at) "
                        + "VALUES (?, 'SEGMENT:dealers', 'CUP-OLD', gen_random_uuid(), 11000000, 'VND', NOW())", group);
                RawSql.update(db, "INSERT INTO catalog.pricing_rule (id, name, price, currency, priority, active, "
                        + "created_at) VALUES (?, 'Everything', 9000000, 'VND', 1, false, NOW())", general);
                RawSql.update(db, "INSERT INTO catalog.promotion (id, code, name, type, value, created_at) VALUES "
                        + "(?, 'TET10', 'Tet', 'PERCENTAGE', 10, NOW())", promotion);
                RawSql.update(db, "INSERT INTO catalog.catalog_entry (id, sku, title, slug, price, currency, published, "
                        + "created_at) VALUES (?, 'CUP-OLD', 'Cup', 'sku-cup-old', 12500000, 'VND', true, NOW())", entry);
                RawSql.update(db, "INSERT INTO catalog.catalog_entry (id, sku, title, slug, published, created_at) "
                        + "VALUES (?, 'CUP-NEW', 'Cup', 'sku-cup-new', true, NOW())", unpriced);
                RawSql.update(db, "INSERT INTO catalog.customer_price (id, customer_id, sku, created_at) VALUES "
                        + "(?, gen_random_uuid(), 'CUP-OWN', NOW())", customerPrice);
                RawSql.update(db, "INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, price) "
                        + "VALUES (?, gen_random_uuid(), gen_random_uuid(), 450000)", pricedUnit);
                RawSql.update(db, "INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id) "
                        + "VALUES (?, gen_random_uuid(), gen_random_uuid())", baseUnit);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                // Merged with the other lines, migrations of theirs may sit between BEFORE and this one (final rescope merge).
                assertThat(result.migrations).extracting(m -> m.version).last().isEqualTo(MIGRATION);

                // Archived whole before it went: every rule, the promotion, the entry's price.
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'price') || ':' || (row_data ->> 'version') || ':' || "
                        + "(row_data ->> 'created_by') FROM platform.legacy_archive WHERE source_table = "
                        + "'catalog.pricing_rule' AND row_id = ?", base)).isEqualTo("12500000.00:3:operator");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'segment_id' FROM platform.legacy_archive WHERE "
                        + "source_table = 'catalog.pricing_rule' AND row_id = ?", group)).isNotNull();
                assertThat(RawSql.text(db, "SELECT row_data ->> 'name' FROM platform.legacy_archive WHERE "
                        + "source_table = 'catalog.pricing_rule' AND row_id = ?", general)).isEqualTo("Everything");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'code') || ':' || (row_data ->> 'value') FROM "
                        + "platform.legacy_archive WHERE source_table = 'catalog.promotion' AND row_id = ?", promotion))
                        .isEqualTo("TET10:10.00");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'sku') || ':' || (row_data ->> 'price') || ':' || "
                        + "(row_data ->> 'currency') FROM platform.legacy_archive WHERE source_table = "
                        + "'catalog.catalog_entry.price' AND row_id = ?", entry)).isEqualTo("CUP-OLD:12500000.00:VND");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'catalog.catalog_entry.price' AND row_id = ?", unpriced)).as("no price, nothing to keep").isZero();
                assertThat(RawSql.text(db, "SELECT row_data ->> 'price' FROM platform.legacy_archive WHERE source_table = "
                        + "'product.variant_sales_units.price' AND row_id = ?", pricedUnit)).isEqualTo("450000.00");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'product.variant_sales_units.price' AND row_id = ?", baseUnit)).isZero();

                // The tables and the columns are gone; the entries and the customer's own price stay.
                assertThat(RawSql.text(db, "SELECT coalesce(to_regclass('catalog.pricing_rule')::text, 'gone') || ':' || "
                        + "coalesce(to_regclass('catalog.promotion')::text, 'gone')")).isEqualTo("gone:gone");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'catalog' AND table_name = 'catalog_entry' AND column_name IN ('price', 'currency')")).isZero();
                assertThat(RawSql.texts(db, "SELECT sku FROM catalog.catalog_entry ORDER BY sku"))
                        .containsExactly("CUP-NEW", "CUP-OLD");
                assertThat(RawSql.number(db, "SELECT count(*) FROM catalog.customer_price WHERE id = ?", customerPrice))
                        .isEqualTo(1);
                // A sales unit stays, without its price: it names a unit the variant is sold in.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'product' AND table_name = 'variant_sales_units' AND column_name = 'price'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM product.variant_sales_units WHERE id IN (?, ?)",
                        pricedUnit, baseUnit)).isEqualTo(2);

                // "Is this item used?" read the price rules: it still runs, and the customer's price still counts.
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(gen_random_uuid(), 'CUP-OWN')::text"))
                        .isEqualTo("true");
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(gen_random_uuid(), 'CUP-NONE')::text"))
                        .isEqualTo("false");

                // The permission and its grants, archived then deleted; every role that held it moves its version.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE "
                        + "'catalog-segment-prices:%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.permission' AND row_data ->> 'code' LIKE 'catalog-segment-prices:%'")).isEqualTo(5);
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive a WHERE a.source_table = "
                        + "'identity.role_permission' AND (a.row_data ->> 'permission_id') IN (SELECT row_id::text FROM "
                        + "platform.legacy_archive WHERE source_table = 'identity.permission' AND row_data ->> 'code' "
                        + "LIKE 'catalog-segment-prices:%')")).isEqualTo(grants);
                assertThat(version(db, "SALES_MANAGER")).isEqualTo(managerVersion + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(adminVersion + 1);
                assertThat(version(db, "SALES_STAFF")).isEqualTo(salesVersion + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(warehouseVersion);
            }
        }
    }

    private static long version(Connection db, String role) throws Exception {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
