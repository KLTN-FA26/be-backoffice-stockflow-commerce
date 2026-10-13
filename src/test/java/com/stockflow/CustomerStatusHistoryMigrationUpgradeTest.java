package com.stockflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261014000500 on a database that already holds customers in every status, at the integration
 * checkpoint's newest migration (V20261014000300): existing customers get no invented history, and the
 * table refuses a reopen without a reason.
 */
class CustomerStatusHistoryMigrationUpgradeTest {

    @Test
    void upgradeKeepsExistingCustomersAndTheHistoryStatesTheReopenRule() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration").target("20261014000300").load().migrate();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement sql = connection.createStatement()) {
                UUID closed = customer(sql, "INACTIVE", null);
                UUID active = customer(sql, "ACTIVE", "0312345678");
                customer(sql, "BLOCKED", null);

                var migrated = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration").target("20261014000500").load();
                assertThat(migrated.migrate().migrations).extracting(m -> m.version).containsExactly("20261014000500");

                assertThat(count(sql, "select count(*) from customer.customer_status_history")).isZero();
                assertThat(count(sql, "select count(*) from customer.customer")).isEqualTo(3);
                assertThat(count(sql, "select count(*) from customer.customer where tax_code is null")).isEqualTo(2);

                String insert = "insert into customer.customer_status_history(id,customer_id,from_status,to_status,"
                        + "reason,changed_at) values (gen_random_uuid(),'" + closed + "',";
                refused(sql, "reopen without a reason", insert + "'INACTIVE','ACTIVE',null,now())");
                refused(sql, "reopen with a blank reason", insert + "'INACTIVE','PENDING_ACTIVATION','  ',now())");
                refused(sql, "a change to the same status", insert + "'ACTIVE','ACTIVE',null,now())");
                refused(sql, "an unknown status", insert + "'ACTIVE','SUSPENDED',null,now())");
                refused(sql, "a customer that does not exist", "insert into customer.customer_status_history"
                        + "(id,customer_id,to_status,changed_at) values (gen_random_uuid(),gen_random_uuid(),'ACTIVE',now())");
                sql.executeUpdate(insert + "'INACTIVE','ACTIVE','customer came back',now())");
                sql.executeUpdate("insert into customer.customer_status_history(id,customer_id,to_status,changed_at)"
                        + " values (gen_random_uuid(),'" + active + "','PENDING_ACTIVATION',now())");
                assertThat(count(sql, "select count(*) from customer.customer_status_history")).isEqualTo(2);
            }
        }
    }

    private static UUID customer(Statement sql, String status, String taxCode) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.executeUpdate("insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values ('"
                + id + "','Co','" + id + "@example.test','" + status + "',"
                + (taxCode == null ? "null" : "'" + taxCode + "'") + ",0,now())");
        return id;
    }

    private static void refused(Statement sql, String rule, String statement) {
        assertThatThrownBy(() -> sql.executeUpdate(statement)).as(rule).isInstanceOf(SQLException.class);
    }

    private static long count(Statement sql, String query) throws SQLException {
        try (var row = sql.executeQuery(query)) {
            row.next();
            return row.getLong(1);
        }
    }
}
