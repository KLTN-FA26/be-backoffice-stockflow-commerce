package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20270104000100 and V20270104000200 on the real upgrade path (owner decisions Q1 and Q9 of 2026-11-26): the carton
 * and whether it may be turned are the catalogue variant's. Before them, every customer's SKU carried a copy of its
 * variant's carton on its own inventory item. After them:
 *
 * <ul>
 *   <li>a variant with an active carton of its own keeps it, and the copies of its customers' SKUs go;</li>
 *   <li>a variant with none takes the most common active copy (same count, size and weight), the earliest of them;</li>
 *   <li>a copy a document counts in is retired and kept (BR-10), its code suffixed where the variant's code is taken;
 *       a copy nothing counts in is deleted;</li>
 *   <li>every item names its variant, and rotatable moves from the variant's own item to the variant.</li>
 * </ul>
 */
class CartonPerVariantMigrationUpgradeTest {

    private static final String BEFORE = "20270103000100";
    private static final String CARTONS = "20270104000100";
    private static final String ROTATABLE = "20270104000200";

    private static final UUID CUP = UUID.randomUUID();
    private static final UUID CUP_A = UUID.randomUUID();
    private static final UUID CUP_B = UUID.randomUUID();
    private static final UUID CUP_C = UUID.randomUUID();
    private static final UUID BOX = UUID.randomUUID();
    private static final UUID BOX_A = UUID.randomUUID();

    private static final UUID CUP_A_THUNG = UUID.randomUUID();
    private static final UUID CUP_A_OLD = UUID.randomUUID();
    private static final UUID CUP_B_THUNG = UUID.randomUUID();
    private static final UUID CUP_C_THUNG = UUID.randomUUID();
    private static final UUID BOX_CTN = UUID.randomUUID();
    private static final UUID BOX_A_CTN = UUID.randomUUID();

    @Test
    void copiesMergeOntoTheirVariantAndRotatableFollows() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, ROTATABLE).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(CARTONS, ROTATABLE);

                assertThat(RawSql.texts(db, cartons(), CUP))
                        .as("no carton of its own: the most common copy (1,000 of 60 x 40 x 50, twice) wins over the "
                                + "earliest (500, once); of the two alike the earlier stays, the other is deleted; the "
                                + "500 a quote counts in is retired under a suffixed code; the unused retired copy goes")
                        .containsExactly(CUP_A_THUNG + ":THUNG:1000:true", CUP_C_THUNG + ":THUNG_R2:500:false");
                assertThat(RawSql.texts(db, cartons(), BOX))
                        .as("its own active carton stays; the copy a quote counts in is retired beside it")
                        .containsExactly(BOX_CTN + ":CTN:24:true", BOX_A_CTN + ":CTN_R2:24:false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.uom_conversions WHERE id IN (?, ?)",
                        CUP_B_THUNG, CUP_A_OLD)).as("copies nothing counts in are deleted").isZero();

                assertThat(RawSql.texts(db, "SELECT i.sku || ':' || v.sku FROM inventory.inventory_items i "
                        + "JOIN product.variants v ON v.id = i.variant_id ORDER BY i.sku"))
                        .as("every item names the variant whose carton it is packed in")
                        .containsExactly("UPG-BOX:UPG-BOX", "UPG-BOX-A:UPG-BOX", "UPG-CUP:UPG-CUP",
                                "UPG-CUP-A:UPG-CUP", "UPG-CUP-B:UPG-CUP", "UPG-CUP-C:UPG-CUP");
                assertThat(RawSql.texts(db, "SELECT sku || ':' || rotatable FROM product.variants "
                        + "WHERE owner_customer_id IS NULL ORDER BY sku"))
                        .as("rotatable taken from the variant's own item")
                        .containsExactly("UPG-BOX:true", "UPG-CUP:false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE "
                        + "(table_schema, table_name, column_name) IN (('inventory', 'uom_conversions', "
                        + "'inventory_item_id'), ('inventory', 'inventory_items', 'rotatable'))"))
                        .as("the item keeps neither the carton nor the flag").isZero();
                assertThat(RawSql.text(db, "SELECT u.unit_of_measure FROM ordering.quote_line l "
                        + "JOIN inventory.uom_conversions u ON u.id = l.uom_conversion_id WHERE l.sku = 'UPG-CUP-C'"))
                        .as("the quote line still counts in the carton it was written in").isEqualTo("THUNG_R2");

                RawSql.refusedBy(db, "has none of its own", "a carton declared on a customer's SKU",
                        "INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active) "
                                + "VALUES (gen_random_uuid(), ?, 'EXTRA', 5, FALSE)", CUP_A);
                RawSql.refusedBy(db, "uk_uom_conversions_one_carton", "a second active carton on the variant",
                        "INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active) "
                                + "VALUES (gen_random_uuid(), ?, 'EXTRA', 5, TRUE)", CUP);
                assertThat(RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, "
                        + "factor, active) VALUES (gen_random_uuid(), ?, 'EXTRA', 5, FALSE)", CUP))
                        .as("a retired carton beside the active one, as history").isEqualTo(1);
                RawSql.refusedBy(db, "never changes", "an item moved to another variant",
                        "UPDATE inventory.inventory_items SET variant_id = ? WHERE sku = 'UPG-CUP-A'", BOX);
                assertThat(RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, "
                        + "is_default, attribute_signature, position, version, created_at) SELECT gen_random_uuid(), "
                        + "product_id, 'UPG-CUP-2', 'cup 2', 'DRAFT', FALSE, 'UPG-CUP-2', 9, 0, NOW() "
                        + "FROM product.variants WHERE id = ?", CUP)).isEqualTo(1);
                assertThat(RawSql.text(db, "SELECT v.sku FROM inventory.inventory_items i JOIN product.variants v "
                        + "ON v.id = i.variant_id WHERE i.sku = 'UPG-CUP-2'"))
                        .as("an item made after the migration names its variant (tg_variant_inventory_item)")
                        .isEqualTo("UPG-CUP-2");
            }
        }
    }

    /** id:code:factor:active of each carton of the variant, active first, then by code. */
    private static String cartons() {
        return "SELECT id || ':' || unit_of_measure || ':' || factor || ':' || active FROM inventory.uom_conversions "
                + "WHERE variant_id = ? ORDER BY active DESC, unit_of_measure";
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers off, as the old rules let them be: what is tested is the migration on rows that
        // existed. CHECKs still hold.
        RawSql.update(db, "SET session_replication_role = replica");
        UUID cups = UUID.randomUUID();
        UUID boxes = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        String product = "INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at) "
                + "VALUES (?, ?, ?, ?, 'DRAFT', 'CUSTOMIZABLE', 0, NOW())";
        RawSql.update(db, product, cups, "UPGCUP", "upgrade cup", "upg-cup");
        RawSql.update(db, product, boxes, "UPGBOX", "upgrade box", "upg-box");
        String catalogue = "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at) VALUES (?, ?, ?, ?, 'ACTIVE', TRUE, 'DEFAULT', 0, "
                + "0, NOW())";
        RawSql.update(db, catalogue, CUP, cups, "UPG-CUP", "cup");
        RawSql.update(db, catalogue, BOX, boxes, "UPG-BOX", "box");
        String own = "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, "
                + "position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', FALSE, ?, ?, 0, NOW(), ?, gen_random_uuid(), ?)";
        RawSql.update(db, own, CUP_A, cups, "UPG-CUP-A", "cup A", "OWNED-SKU:UPG-CUP-A", 1, customer, CUP);
        RawSql.update(db, own, CUP_B, cups, "UPG-CUP-B", "cup B", "OWNED-SKU:UPG-CUP-B", 2, customer, CUP);
        RawSql.update(db, own, CUP_C, cups, "UPG-CUP-C", "cup C", "OWNED-SKU:UPG-CUP-C", 3, customer, CUP);
        RawSql.update(db, own, BOX_A, boxes, "UPG-BOX-A", "box A", "OWNED-SKU:UPG-BOX-A", 1, customer, BOX);

        // The items the variant trigger made; the cup's own was set not to be turned.
        String item = "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, rotatable, created_at) "
                + "VALUES (gen_random_uuid(), ?, 'EACH', ?, NOW())";
        RawSql.update(db, item, "UPG-CUP", false);
        RawSql.update(db, item, "UPG-BOX", true);
        for (String sku : new String[] {"UPG-CUP-A", "UPG-CUP-B", "UPG-CUP-C", "UPG-BOX-A"}) {
            RawSql.update(db, item, sku, true);
        }

        String carton = "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "weight_kg, length_cm, width_cm, height_cm, active, created_at) SELECT ?, id, ?, ?, ?, ?, ?, ?, ?, "
                + "NOW() - ?::interval FROM inventory.inventory_items WHERE sku = ?";
        // The cup has no carton of its own. Its copies: two alike of 1,000 (A the earlier), one of 500 that is the
        // earliest of all and that a quote counts in, and a retired one nothing counts in.
        RawSql.update(db, carton, CUP_C_THUNG, "THUNG", 500, 9.5, 70, 45, 55, true, "4 days", "UPG-CUP-C");
        RawSql.update(db, carton, CUP_A_THUNG, "THUNG", 1000, 8.6, 60, 40, 50, true, "3 days", "UPG-CUP-A");
        RawSql.update(db, carton, CUP_B_THUNG, "THUNG", 1000, 8.6, 60, 40, 50, true, "2 days", "UPG-CUP-B");
        RawSql.update(db, carton, CUP_A_OLD, "OLD", 800, 8, 60, 40, 50, false, "9 days", "UPG-CUP-A");
        // The box has its own carton; its customer's SKU has a copy a quote counts in, created earlier.
        RawSql.update(db, carton, BOX_CTN, "CTN", 24, 2, 40, 30, 30, true, "1 day", "UPG-BOX");
        RawSql.update(db, carton, BOX_A_CTN, "CTN", 24, 2, 40, 30, 30, true, "2 days", "UPG-BOX-A");

        String line = "INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, "
                + "units_per_carton) VALUES (gen_random_uuid(), gen_random_uuid(), 1, ?, ?, 2, ?)";
        RawSql.update(db, line, "UPG-CUP-C", CUP_C_THUNG, 500);
        RawSql.update(db, line, "UPG-BOX-A", BOX_A_CTN, 24);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
