package com.stockflow;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261102010000 on a database that already holds accounts flagged "must change password" (SCRUM-474, owner
 * decision PWD-LEGACY of 02/11/2026): those accounts have no known time the temporary password was set, so the
 * migration stamps them with the moment it runs and their 72 hours end 72 hours after the deploy. Accounts that
 * are not flagged are left alone.
 */
class IdentityTemporaryPasswordMigrationUpgradeTest {

    @Test
    void accountsFlaggedBeforeTheDeployExpireSeventyTwoHoursAfterIt() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            var underTest = MigrationVersion.fromVersion("20261102010000");
            String before = Arrays.stream(Flyway.configure()
                            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                            .locations("classpath:db/migration").load().info().all())
                    .map(MigrationInfo::getVersion)
                    .filter(version -> version.compareTo(underTest) < 0)
                    .max(Comparable::compareTo).orElseThrow().getVersion();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration").target(before).load().migrate();
            try (Connection sql = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID flaggedOne = user(sql, "flagged-one", true);
                UUID flaggedTwo = user(sql, "flagged-two", true);
                UUID ordinary = user(sql, "ordinary", false);

                var migrated = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration").target("20261102010000").load();
                assertThat(migrated.migrate().migrations).extracting(m -> m.version).containsExactly("20261102010000");

                // both flagged accounts carry the migration time: within the last minute, never in the future
                assertThat(number(sql, "select count(*) from identity.app_user where must_change_password"
                        + " and temporary_password_set_at between now() - interval '1 minute' and now()")).isEqualTo(2);
                // so the 72 hours end 72 hours after the deploy, and not a moment earlier
                assertThat(number(sql, "select count(*) from identity.app_user where must_change_password"
                        + " and temporary_password_set_at + interval '72 hours' > now() + interval '71 hours'"
                        + " and temporary_password_set_at + interval '72 hours' <= now() + interval '72 hours'"))
                        .isEqualTo(2);
                // an account that was not flagged has nothing to expire
                assertThat(number(sql, "select count(*) from identity.app_user where id = ?"
                        + " and temporary_password_set_at is null", ordinary)).isEqualTo(1);
                assertThat(number(sql, "select count(*) from identity.app_user where id in (?, ?)"
                        + " and temporary_password_set_at is not null", flaggedOne, flaggedTwo)).isEqualTo(2);
            }
        }
    }

    private static UUID user(Connection sql, String name, boolean mustChangePassword) throws SQLException {
        UUID id = UUID.randomUUID();
        update(sql, "insert into identity.app_user(id,username,email,password_hash,status,must_change_password,"
                + "version,created_at) values (?,?,?,'{bcrypt}x','ACTIVE',?,0,now())",
                id, name, name + "@example.test", mustChangePassword);
        return id;
    }
}
