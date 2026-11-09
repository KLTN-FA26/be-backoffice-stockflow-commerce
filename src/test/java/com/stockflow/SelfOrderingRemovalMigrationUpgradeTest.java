package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261113000100 on the real upgrade path: a database migrated to the migration before it, holding a customer's live
 * cart with a line, a converted cart and a guest cart - then upgraded.
 *
 * <p>Proves nothing is lost (every cart and cart line is archived whole before the tables go), the tables and the
 * {@code sales-carts} permission rows are gone, CUSTOMER no longer holds {@code sales-orders:CREATE} while the staff
 * who place orders on a customer's behalf still do, and every role whose grants changed moves its version (the
 * authorisation cache is keyed by it, ADR-0008) while a role whose grants did not change stays put.</p>
 */
class SelfOrderingRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108002900";
    private static final String MIGRATION = "20261113000100";

    @Test
    void upgradeRetiresTheCartAndTheCustomersRightToOrderKeepingTheRowsInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // The state the old code left: who holds what, and every role's version, before the migration.
                Map<String, Long> versionBefore = versions(db);
                List<String> holdersOfCarts = RawSql.texts(db, "SELECT DISTINCT r.code FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = "
                        + "rp.permission_id WHERE p.resource = 'sales-carts'");
                assertThat(holdersOfCarts).as("sales-carts is seeded").contains("CUSTOMER");
                assertThat(holder(db, "CUSTOMER", "sales-orders:CREATE")).isTrue();
                assertThat(holder(db, "SALES_STAFF", "sales-orders:CREATE"))
                        .as("assisted orders are placed by sales staff").isTrue();

                UUID customer = UUID.randomUUID();
                UUID order = UUID.randomUUID();
                UUID liveCart = UUID.randomUUID();
                UUID liveLine = UUID.randomUUID();
                UUID convertedCart = UUID.randomUUID();
                UUID guestCart = UUID.randomUUID();
                UUID guestLine = UUID.randomUUID();

                // Customers, orders and SKUs are not what this test is about: foreign keys and row triggers are off
                // while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO ordering.cart (id, customer_id, status, currency, last_activity_at, "
                        + "version, created_at) VALUES (?, ?, 'ACTIVE', 'VND', NOW(), 0, NOW())", liveCart, customer);
                RawSql.update(db, "INSERT INTO ordering.cart_line (id, cart_id, sku, quantity, unit_price_snapshot, "
                        + "added_at, version, created_at) VALUES (?, ?, 'CUP-OLD', 7, 1200.5000, NOW(), 0, NOW())",
                        liveLine, liveCart);
                RawSql.update(db, "INSERT INTO ordering.cart (id, customer_id, status, currency, converted_order_id, "
                        + "last_activity_at, version, created_at) VALUES (?, ?, 'CONVERTED', 'VND', ?, NOW(), 0, NOW())",
                        convertedCart, customer, order);
                RawSql.update(db, "INSERT INTO ordering.cart (id, guest_token, status, currency, last_activity_at, "
                        + "version, created_at) VALUES (?, 'guest-token-old-1', 'ACTIVE', 'VND', NOW(), 0, NOW())",
                        guestCart);
                RawSql.update(db, "INSERT INTO ordering.cart_line (id, cart_id, sku, quantity, added_at, version, "
                        + "created_at) VALUES (?, ?, 'CUP-OLD', 2, NOW(), 0, NOW())", guestLine, guestCart);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Archived whole before the tables went.
                assertThat(RawSql.numbersByText(db, "SELECT source_table, count(*) FROM platform.legacy_archive WHERE "
                        + "source_table IN ('ordering.cart', 'ordering.cart_line') GROUP BY source_table"))
                        .containsEntry("ordering.cart", 3L).containsEntry("ordering.cart_line", 2L);
                assertThat(RawSql.text(db, "SELECT row_data ->> 'customer_id' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.cart' AND row_id = ?", liveCart)).isEqualTo(customer.toString());
                assertThat(RawSql.text(db, "SELECT row_data ->> 'guest_token' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.cart' AND row_id = ?", guestCart)).isEqualTo("guest-token-old-1");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'converted_order_id' FROM platform.legacy_archive "
                        + "WHERE source_table = 'ordering.cart' AND row_id = ?", convertedCart))
                        .isEqualTo(order.toString());
                assertThat(RawSql.text(db, "SELECT row_data ->> 'quantity' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.cart_line' AND row_id = ?", liveLine)).isEqualTo("7");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'cart_id' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.cart_line' AND row_id = ?", guestLine))
                        .isEqualTo(guestCart.toString());

                // The tables and everything that pointed at them are gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'ordering' AND table_name IN ('cart', 'cart_line')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE prosrc ILIKE '%ordering.cart%'"))
                        .isZero();

                // The permission and every grant of it are gone; the customer can read orders but not create them.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource = "
                        + "'sales-carts' OR code LIKE 'sales-carts:%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp LEFT JOIN "
                        + "identity.permission p ON p.id = rp.permission_id WHERE p.id IS NULL")).isZero();
                assertThat(holder(db, "CUSTOMER", "sales-orders:CREATE")).isFalse();
                assertThat(holder(db, "CUSTOMER", "sales-orders:READ")).as("a customer still follows its orders")
                        .isTrue();
                assertThat(holder(db, "SALES_STAFF", "sales-orders:CREATE")).isTrue();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code = "
                        + "'sales-orders:CREATE'")).isEqualTo(1);

                // A role whose grants changed moves its version; one whose grants did not, does not.
                Map<String, Long> versionAfter = versions(db);
                for (String role : holdersOfCarts) {
                    assertThat(versionAfter.get(role)).as("version of " + role).isGreaterThan(versionBefore.get(role));
                }
                assertThat(versionAfter.get("CUSTOMER")).isGreaterThan(versionBefore.get("CUSTOMER"));
                versionBefore.forEach((role, before) -> {
                    if (!holdersOfCarts.contains(role) && !"CUSTOMER".equals(role)) {
                        assertThat(versionAfter.get(role)).as("version of " + role).isEqualTo(before);
                    }
                });
            }
        }
    }

    private static boolean holder(Connection db, String role, String permission) throws Exception {
        return RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = "
                + "rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE r.code = ? AND p.code = ?",
                role, permission) > 0;
    }

    private static Map<String, Long> versions(Connection db) throws Exception {
        return new HashMap<>(RawSql.numbersByText(db, "SELECT code, version FROM identity.app_role"));
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
