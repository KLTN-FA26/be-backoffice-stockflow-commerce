package com.stockflow.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261023000400 (SCRUM-287 PR T, review of #164) on a database that already holds transfer orders: every transfer
 * that existed before keeps travelling as it did - PARCEL, dispatched by hand - so an APPROVED or PICKING one is not
 * stranded behind transfer packing; one created after the migration gets the lead's default, FULL_TRUCKLOAD (LP2-8).
 */
class TransferLoadPlanningMigrationTest {

    private static final String BEFORE = "20261023000300";
    private static final String HCM = "md5('demo:wh:HCM')::uuid";
    private static final String EDITOR = "md5('demo:user:editor')::uuid";

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        return (target == null ? config : config.target(target)).load();
    }

    private static String one(Statement sql, String query) throws Exception {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void transfer(Statement sql, String key, String status, String warehouse) throws Exception {
        sql.executeUpdate("""
                INSERT INTO inventory.transfer_order (id, transfer_number, from_warehouse_id, to_warehouse_id, status,
                    approved_by, approved_at, version, created_at)
                VALUES (md5('mig:%s')::uuid, 'TO-MIG-%s', %s, %s, '%s',
                        CASE WHEN '%s' IN ('APPROVED', 'PICKING') THEN %s END,
                        CASE WHEN '%s' IN ('APPROVED', 'PICKING') THEN NOW() END, 0, NOW())"""
                .formatted(key, key, HCM, warehouse, status, status, EDITOR, status));
    }

    @Test
    @DisplayName("existing APPROVED and PICKING transfers become PARCEL (still dispatched by hand); new ones default "
            + "to FULL_TRUCKLOAD")
    void existingTransfersKeepTheirManualDispatch() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword()); Statement sql = connection.createStatement()) {
                sql.executeUpdate("""
                        INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,
                                                         map_height, version, created_at)
                        VALUES (md5('mig:wh')::uuid, 'Destination', 'ACTIVE', 'MIGT', 'Somewhere', 'M', 50, 50, 0,
                                NOW())""");
                transfer(sql, "approved", "APPROVED", "md5('mig:wh')::uuid");
                transfer(sql, "picking", "PICKING", "md5('mig:wh')::uuid");
            }
            flyway(pg, null).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword()); Statement sql = connection.createStatement()) {
                assertThat(one(sql, "SELECT string_agg(delivery_mode, ',' ORDER BY transfer_number) FROM "
                        + "inventory.transfer_order WHERE transfer_number LIKE 'TO-MIG-%'")).isEqualTo("PARCEL,PARCEL");
                transfer(sql, "new", "DRAFT", "md5('mig:wh')::uuid");
                assertThat(one(sql, "SELECT delivery_mode FROM inventory.transfer_order WHERE transfer_number = "
                        + "'TO-MIG-new'")).isEqualTo("FULL_TRUCKLOAD");
            }
        }
    }
}
