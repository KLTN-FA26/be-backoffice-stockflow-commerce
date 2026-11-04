package com.stockflow.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261021000300 (G4-5) on a database that already holds packs, packages and shipments written by the old flow and
 * the test-data seed: shipments move to the 09 §5 statuses (PENDING → READY_TO_DISPATCH, DISPATCHED → HANDED_OVER)
 * with a number per order, their warehouse and their handover / outcome times; packages get their label code; packs
 * their packer. Then the new rules hold on that data: an order line is never packed more than it wants, and a scan is
 * never rewritten.
 */
class PackingStationMigrationTest {

    private static final String BEFORE = "20261021000200";
    private static final String HCM = "md5('demo:wh:HCM')::uuid";
    private static final String EDITOR = "md5('demo:user:editor')::uuid";

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        return (target == null ? config : config.target(target)).load();
    }

    private static List<String> rows(Statement sql, String query) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = sql.executeQuery(query)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    @Test
    void legacyPacksPackagesAndShipmentsAreCarriedOver() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement sql = connection.createStatement()) {
                sql.executeUpdate("""
                        INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                            total_amount, currency, placed_at, created_at, warehouse_id)
                        VALUES (md5('mig:o')::uuid, 'SO-MIG-PACK', 'c0000000-0000-4000-8000-000000000001',
                                gen_random_uuid(), 'PICKING', 2000, 'VND', NOW(), NOW(), %s)""".formatted(HCM));
                sql.executeUpdate("""
                        INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                        VALUES (md5('mig:l')::uuid, md5('mig:o')::uuid, 'SOFA-3S-GREY', 2, 1000, 'VND')""");
                sql.executeUpdate("""
                        INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, assigned_user_id,
                                                      assigned_at, started_at, picked_at, created_at)
                        VALUES (md5('mig:pick')::uuid, md5('mig:o')::uuid, 'SO-MIG-PACK', %s, 'PICKED', %s, NOW(), NOW(),
                                NOW() - INTERVAL '1 hour', NOW())""".formatted(HCM, EDITOR));
                sql.executeUpdate("""
                        INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, created_at)
                        VALUES (md5('mig:pack')::uuid, md5('mig:pick')::uuid, 'PACKED', NOW(), NOW())""");
                // Three shipments of one order, one per old status, made in this order.
                sql.executeUpdate("""
                        INSERT INTO fulfillment.shipment (id, pack_id, order_id, carrier, tracking_number, status,
                                                          dispatched_at, created_at)
                        VALUES (md5('mig:s1')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'GHN', 'T1', 'PENDING',
                                NULL, NOW() - INTERVAL '3 hours'),
                               (md5('mig:s2')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'GHN', 'T2', 'DISPATCHED',
                                NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'),
                               (md5('mig:s3')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'GHN', 'T3', 'DELIVERED',
                                NOW() - INTERVAL '90 minutes', NOW() - INTERVAL '1 hour')""");
                sql.executeUpdate("""
                        INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source)
                        VALUES (gen_random_uuid(), md5('mig:s3')::uuid, 'DELIVERED', TIMESTAMPTZ '2026-10-20 10:00+07',
                                'CARRIER_WEBHOOK')""");
                sql.executeUpdate("""
                        INSERT INTO fulfillment.package (id, pack_id, shipment_id, package_no, weight_kg, length_cm,
                                                         width_cm, height_cm, status, sealed_at, created_at)
                        VALUES (md5('mig:p1')::uuid, md5('mig:pack')::uuid, md5('mig:s3')::uuid, 1, 10, 50, 40, 30,
                                'SEALED', NOW(), NOW()),
                               (md5('mig:p2')::uuid, md5('mig:pack')::uuid, NULL, 2, NULL, NULL, NULL, NULL, 'VOIDED',
                                NULL, NOW())""");
                sql.executeUpdate("""
                        INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
                        VALUES (gen_random_uuid(), md5('mig:p1')::uuid, md5('mig:l')::uuid, 2),
                               (gen_random_uuid(), md5('mig:p2')::uuid, md5('mig:l')::uuid, 2)""");

                // Review #1: an order packed by the old completion - PACKED, a sealed parcel, no shipment - on deposit
                // terms whose carrier collects the balance (10M, 3M paid).
                sql.executeUpdate("""
                        INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                            total_amount, currency, placed_at, created_at, warehouse_id, payment_term,
                            deposit_required, balance_policy, paid_amount)
                        VALUES (md5('mig:o3')::uuid, 'SO-MIG-STRAND', 'c0000000-0000-4000-8000-000000000001',
                                gen_random_uuid(), 'PICKING', 10000000, 'VND', NOW(), NOW(), %s, 'DEPOSIT', 3000000,
                                'ON_DELIVERY', 3000000);
                        INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                        VALUES (md5('mig:l3')::uuid, md5('mig:o3')::uuid, 'SOFA-3S-GREY', 2, 5000000, 'VND');
                        INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status,
                                                      assigned_user_id, picked_at, created_at)
                        VALUES (md5('mig:pick3')::uuid, md5('mig:o3')::uuid, 'SO-MIG-STRAND', %s, 'PICKED', %s,
                                NOW(), NOW());
                        INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, created_at)
                        VALUES (md5('mig:pack3')::uuid, md5('mig:pick3')::uuid, 'PACKED', NOW(), NOW());
                        INSERT INTO fulfillment.package (id, pack_id, package_no, weight_kg, length_cm, width_cm,
                                                         height_cm, status, sealed_at, created_at)
                        VALUES (md5('mig:p31')::uuid, md5('mig:pack3')::uuid, 1, 80, 215, 95, 90, 'SEALED', NOW(),
                                NOW());
                        INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
                        VALUES (gen_random_uuid(), md5('mig:p31')::uuid, md5('mig:l3')::uuid, 2)"""
                        .formatted(HCM, HCM, EDITOR));

                assertThat(flyway(pg, null).migrate().migrationsExecuted).isPositive();

                assertThat(rows(sql, """
                        SELECT s.code, s.status, s.package_count, s.cod_amount::bigint, s.ready_by = %s,
                               (SELECT COUNT(*) FROM fulfillment.package p WHERE p.shipment_id = s.id),
                               (SELECT COUNT(*) FROM fulfillment.shipment_event e WHERE e.shipment_id = s.id
                                   AND e.status = 'READY_TO_DISPATCH')
                          FROM fulfillment.shipment s WHERE s.order_id = md5('mig:o3')::uuid""".formatted(EDITOR)))
                        .as("the stranded packed order now has a shipment to hand over, with the balance as COD")
                        .containsExactly("SO-MIG-STRAND-S1|READY_TO_DISPATCH|1|7000000|t|1|1");
                assertThat(rows(sql, "SELECT count(*) FROM fulfillment.shipment WHERE order_id = md5('mig:o')::uuid"))
                        .as("a pack that had shipments gets none more").containsExactly("3");

                assertThat(rows(sql, """
                        SELECT code, status, warehouse_id = %s, handed_over_at IS NOT NULL, outcome_at IS NOT NULL,
                               package_count, delivery_mode
                          FROM fulfillment.shipment WHERE order_id = md5('mig:o')::uuid ORDER BY code""".formatted(HCM)))
                        .containsExactly(
                                "SO-MIG-PACK-S1|READY_TO_DISPATCH|t|f|f|1|PARCEL",
                                "SO-MIG-PACK-S2|HANDED_OVER|t|t|f|1|PARCEL",
                                "SO-MIG-PACK-S3|DELIVERED|t|t|t|1|PARCEL");
                assertThat(rows(sql, "SELECT outcome_at = TIMESTAMPTZ '2026-10-20 10:00+07' FROM fulfillment.shipment "
                        + "WHERE id = md5('mig:s3')::uuid")).as("from its timeline").containsExactly("t");
                assertThat(rows(sql, """
                        SELECT package_no, label_code, status, voided_at IS NOT NULL, package_type, rotatable
                          FROM fulfillment.package WHERE pack_id = md5('mig:pack')::uuid ORDER BY package_no"""))
                        .containsExactly("1|PKG-SO-MIG-PACK-1|SEALED|f|CARTON|t", "2|PKG-SO-MIG-PACK-2|VOIDED|t|CARTON|t");
                assertThat(rows(sql, "SELECT packed_by = %s, started_at IS NOT NULL FROM fulfillment.pack WHERE id = "
                        .formatted(EDITOR) + "md5('mig:pack')::uuid")).containsExactly("t|t");

                // 08 BR-01 in the database: the live package already holds both units; one more is refused.
                assertThatThrownBy(() -> sql.executeUpdate("""
                        INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
                        VALUES (md5('mig:p3')::uuid, md5('mig:pack')::uuid, 3, 'PKG-SO-MIG-PACK-3', 'OPEN', NOW());
                        INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
                        VALUES (gen_random_uuid(), md5('mig:p3')::uuid, md5('mig:l')::uuid, 1)""")).hasMessageContaining(
                        "would be packed 3 times");
                // Review #5: a shipment that came back holds nothing - its units may be packed again.
                sql.executeUpdate("UPDATE fulfillment.shipment SET status = 'RETURNED' WHERE id = md5('mig:s3')::uuid");
                sql.executeUpdate("""
                        INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
                        VALUES (md5('mig:p5')::uuid, md5('mig:pack')::uuid, 5, 'PKG-SO-MIG-PACK-5', 'OPEN', NOW());
                        INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
                        VALUES (gen_random_uuid(), md5('mig:p5')::uuid, md5('mig:l')::uuid, 2)""");
                // A scan is written once and never changed; one parcel holds one order (08 BR-04).
                sql.executeUpdate("""
                        INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
                        VALUES (md5('mig:p4')::uuid, md5('mig:pack')::uuid, 4, 'PKG-SO-MIG-PACK-4', 'OPEN', NOW())""");
                sql.executeUpdate("""
                        INSERT INTO fulfillment.package_scan (id, package_id, order_line_id, scanned_code, quantity,
                                                              scanned_by, scanned_at)
                        VALUES (md5('mig:scan')::uuid, md5('mig:p4')::uuid, md5('mig:l')::uuid, 'SOFA-3S-GREY', 1, %s,
                                NOW())""".formatted(EDITOR));
                assertThatThrownBy(() -> sql.executeUpdate(
                        "UPDATE fulfillment.package_scan SET quantity = 2 WHERE id = md5('mig:scan')::uuid"))
                        .hasMessageContaining("append-only");
                sql.executeUpdate("""
                        INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                            total_amount, currency, placed_at, created_at, warehouse_id)
                        VALUES (md5('mig:o2')::uuid, 'SO-MIG-OTHER', 'c0000000-0000-4000-8000-000000000001',
                                gen_random_uuid(), 'PICKING', 1000, 'VND', NOW(), NOW(), %s);
                        INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                        VALUES (md5('mig:l2')::uuid, md5('mig:o2')::uuid, 'SOFA-3S-GREY', 1, 1000, 'VND')"""
                        .formatted(HCM));
                assertThatThrownBy(() -> sql.executeUpdate("""
                        INSERT INTO fulfillment.package_scan (id, package_id, order_line_id, scanned_code, quantity,
                                                              scanned_by, scanned_at)
                        VALUES (gen_random_uuid(), md5('mig:p4')::uuid, md5('mig:l2')::uuid, 'SOFA-3S-GREY', 1, %s,
                                NOW())""".formatted(EDITOR))).hasMessageContaining("its own order");
                assertThatThrownBy(() -> sql.executeUpdate("""
                        UPDATE fulfillment.shipment SET status = 'DELIVERY_FAILED' WHERE id = md5('mig:s3')::uuid"""))
                        .as("a failure has its reason").hasMessageContaining("ck_shipment_failure");
                assertThatThrownBy(() -> sql.executeUpdate("""
                        UPDATE fulfillment.package SET status = 'OPEN' WHERE id = md5('mig:p1')::uuid"""))
                        .as("a shipped parcel stays sealed").hasMessageContaining("ck_package_shipped_sealed");
            }
        }
    }
}
