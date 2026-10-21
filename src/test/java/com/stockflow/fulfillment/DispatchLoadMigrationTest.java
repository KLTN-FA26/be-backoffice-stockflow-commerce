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
 * V20261021000400/500 (G4-2a) on a database that already holds shipments migrated by V20261021000300 (in-flight data on
 * develop's seed and the deployed databases): the free-text carriers are carried to the catalogue, a carrier it does
 * not know gets its own entry; shipments that left before loads existed are {@code whole_order_handover}; one waiting for
 * dispatch is untouched and, from now on, leaves only on a manifest. An {@code ordering.order_shipment} row without
 * its shipment stops the migration, naming it.
 */
class DispatchLoadMigrationTest {

    private static final String BEFORE = "20261021000300";
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

    private static void order(Statement sql) throws Exception {
        sql.executeUpdate("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, created_at, warehouse_id)
                VALUES (md5('mig:o')::uuid, 'SO-MIG-LOAD', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
                        'PICKING', 2000, 'VND', NOW(), NOW(), %s);
                INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, assigned_user_id,
                                              picked_at, created_at)
                VALUES (md5('mig:pick')::uuid, md5('mig:o')::uuid, 'SO-MIG-LOAD', %s, 'PICKED', %s, NOW(), NOW());
                INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, started_at, created_at)
                VALUES (md5('mig:pack')::uuid, md5('mig:pick')::uuid, 'PACKED', NOW(), NOW(), NOW())"""
                .formatted(HCM, HCM, EDITOR));
    }

    @Test
    void shipmentsInFlightKeepTheirCarrierAndAreMarkedLegacy() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement sql = connection.createStatement()) {
                order(sql);
                sql.executeUpdate("""
                        INSERT INTO fulfillment.shipment (id, pack_id, order_id, carrier, status, code, warehouse_id,
                                                          ready_at, handed_over_at, outcome_at, created_at)
                        VALUES (md5('mig:s1')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'Viettel Post',
                                'HANDED_OVER', 'SO-MIG-LOAD-S1', %s, NOW(), NOW(), NULL, NOW() - INTERVAL '3 hours'),
                               (md5('mig:s2')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, ' Ahamove ',
                                'DELIVERED', 'SO-MIG-LOAD-S2', %s, NOW(), NOW(), NOW(), NOW() - INTERVAL '2 hours'),
                               (md5('mig:s3')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, NULL,
                                'READY_TO_DISPATCH', 'SO-MIG-LOAD-S3', %s, NOW(), NULL, NULL, NOW()),
                               (md5('mig:s4')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'J&T',
                                'HANDED_OVER', 'SO-MIG-LOAD-S4', %s, NOW(), NOW(), NULL, NOW()),
                               (md5('mig:s5')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'gHn',
                                'DELIVERED', 'SO-MIG-LOAD-S5', %s, NOW(), NOW(), NOW(), NOW()),
                               (md5('mig:s6')::uuid, md5('mig:pack')::uuid, md5('mig:o')::uuid, 'ahamove',
                                'DELIVERED', 'SO-MIG-LOAD-S6', %s, NOW(), NOW(), NOW(), NOW())"""
                        .formatted(HCM, HCM, HCM, HCM, HCM, HCM));

                assertThat(flyway(pg, null).migrate().migrationsExecuted).isPositive();

                assertThat(rows(sql, """
                        SELECT s.code, s.whole_order_handover, c.code, c.name FROM fulfillment.shipment s
                          LEFT JOIN fulfillment.carrier c ON c.id = s.carrier_id
                         WHERE s.order_id = md5('mig:o')::uuid ORDER BY s.code"""))
                        .hasSize(6)
                        .satisfies(r -> assertThat(r.get(0)).isEqualTo("SO-MIG-LOAD-S1|t|VTP|Viettel Post"))
                        .satisfies(r -> assertThat(r.get(1)).matches("SO-MIG-LOAD-S2\\|t\\|L[0-9A-F]{8}\\|[Aa]hamove"))
                        .satisfies(r -> assertThat(r.get(2)).isEqualTo("SO-MIG-LOAD-S3|f|null|null"))
                        .satisfies(r -> assertThat(r.get(3)).isEqualTo("SO-MIG-LOAD-S4|t|JTE|J&T Express"))
                        .satisfies(r -> assertThat(r.get(4)).isEqualTo("SO-MIG-LOAD-S5|t|GHN|Giao Hàng Nhanh"))
                        .satisfies(r -> assertThat(r.get(5)).isEqualTo(r.get(1).replace("S2", "S6")));
                assertThat(rows(sql, "SELECT count(*) FROM fulfillment.carrier WHERE lower(name) = 'ahamove'"))
                        .as("one entry for one carrier however it was spelt").containsExactly("1");
                assertThat(rows(sql, "SELECT count(*) FROM fulfillment.shipment WHERE label_cod_amount <> cod_amount"))
                        .containsExactly("0");
                assertThat(rows(sql, "SELECT code FROM fulfillment.carrier WHERE created_by = 'flyway' ORDER BY code"))
                        .contains("GHN", "GHTK", "JTE", "VTP");
                // From now on a shipment leaves only on a signed manifest.
                assertThatThrownBy(() -> sql.executeUpdate("""
                        UPDATE fulfillment.shipment SET status = 'HANDED_OVER', handed_over_at = NOW(),
                               handed_over_by = %s WHERE id = md5('mig:s3')::uuid""".formatted(EDITOR)))
                        .hasMessageContaining("ck_shipment_handed_over_load");
                // The legacy ones still take their outcome.
                sql.executeUpdate("""
                        UPDATE fulfillment.shipment SET status = 'DELIVERED', outcome_at = NOW()
                         WHERE id = md5('mig:s1')::uuid""");
            }
        }
    }

    @Test
    void anOrderShipmentWithoutItsShipmentStopsTheMigration() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement sql = connection.createStatement()) {
                order(sql);
                sql.executeUpdate("""
                        INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
                        VALUES (md5('mig:orphan')::uuid, md5('mig:o')::uuid, 'HANDED_OVER', 1000, NOW())""");
                assertThatThrownBy(() -> flyway(pg, null).migrate())
                        .hasStackTraceContaining("ordering.order_shipment rows without a fulfillment.shipment");
            }
        }
    }
}
