package com.stockflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261017000500 on a database that already holds customers in every status, at the integration
 * checkpoint's newest migration (V20261017000300): existing customers get no invented history, and the
 * table refuses a reopen without a reason.
 */
class CustomerStatusHistoryMigrationUpgradeTest {

    @Test
    void upgradeKeepsExistingCustomersAndTheHistoryStatesTheReopenRule() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            // Everything before V20261017000500, whoever wrote it (lane A's 0004xx sit in between since the
            // integration), so the step below is exactly the migration under test.
            var underTest = org.flywaydb.core.api.MigrationVersion.fromVersion("20261017000500");
            String before = java.util.Arrays.stream(Flyway.configure()
                            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                            .locations("classpath:db/migration").load().info().all())
                    .map(org.flywaydb.core.api.MigrationInfo::getVersion)
                    .filter(version -> version.compareTo(underTest) < 0)
                    .max(Comparable::compareTo).orElseThrow().getVersion();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration").target(before).load().migrate();
            try (Connection sql = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID closed = customer(sql, "INACTIVE", null);
                UUID active = customer(sql, "ACTIVE", "0312345678");
                customer(sql, "BLOCKED", null);

                var migrated = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration").target("20261017000500").load();
                assertThat(migrated.migrate().migrations).extracting(m -> m.version).containsExactly("20261017000500");

                assertThat(number(sql, "select count(*) from customer.customer_status_history")).isZero();
                assertThat(number(sql, "select count(*) from customer.customer")).isEqualTo(3);
                assertThat(number(sql, "select count(*) from customer.customer where tax_code is null")).isEqualTo(2);

                String insert = "insert into customer.customer_status_history(id,customer_id,from_status,to_status,"
                        + "reason,changed_at) values (gen_random_uuid(),?,?,?,?,now())";
                refused(sql, "reopen without a reason", insert, closed, "INACTIVE", "ACTIVE", null);
                refused(sql, "reopen with a blank reason", insert, closed, "INACTIVE", "PENDING_ACTIVATION", "  ");
                refused(sql, "a change to the same status", insert, closed, "ACTIVE", "ACTIVE", null);
                refused(sql, "an unknown status", insert, closed, "ACTIVE", "SUSPENDED", null);
                refused(sql, "a customer that does not exist", "insert into customer.customer_status_history"
                        + "(id,customer_id,to_status,changed_at) values (gen_random_uuid(),gen_random_uuid(),'ACTIVE',now())");
                update(sql, insert, closed, "INACTIVE", "ACTIVE", "customer came back");
                update(sql, "insert into customer.customer_status_history(id,customer_id,to_status,changed_at)"
                        + " values (gen_random_uuid(),?,'PENDING_ACTIVATION',now())", active);
                assertThat(number(sql, "select count(*) from customer.customer_status_history")).isEqualTo(2);
            }
        }
    }

    private static UUID customer(Connection sql, String status, String taxCode) throws SQLException {
        UUID id = UUID.randomUUID();
        update(sql, "insert into customer.customer(id,full_name,email,status,tax_code,version,created_at)"
                + " values (?,'Co',?,?,?,0,now())", id, id + "@example.test", status, taxCode);
        return id;
    }
}
