package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261108001200 on the real upgrade path: a database migrated to the migration before it, holding a customer's
 * design, then upgraded. Proves the rules the tables state: a design joins a project of its own customer only, one
 * waiting for review neither leaves its project nor moves (though it may join one), a design waits in one round at a
 * time, and a project's history is append-only.
 */
class DesignProjectsMigrationUpgradeTest {

    private static final String BEFORE = "20261108001100";
    private static final String MIGRATION = "20261108001200";

    @Test
    void upgradeKeepsAProjectsDesignsToItsCustomerAndAWaitingDesignInPlace() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID customer = UUID.randomUUID();
                UUID stranger = UUID.randomUUID();
                UUID user = UUID.randomUUID();
                UUID draft = UUID.randomUUID();
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, name, status, "
                        + "created_at, current_artifacts, review_requested_at) VALUES (?, ?, gen_random_uuid(), 'D', "
                        + "'PENDING_REVIEW', NOW(), '{}'::jsonb, NOW())", draft, customer);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                UUID project = UUID.randomUUID();
                UUID other = UUID.randomUUID();
                UUID strangers = UUID.randomUUID();
                RawSql.update(db, "SET session_replication_role = replica");
                for (Object[] row : new Object[][] {{project, customer}, {other, customer}, {strangers, stranger}}) {
                    RawSql.update(db, "INSERT INTO design.design_project (id, customer_id, owner_user_id, name, status, "
                            + "created_at) VALUES (?, ?, ?, 'P', 'ACTIVE', NOW())", row[0], row[1], user);
                }
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                // Another customer's project does not take the design.
                RawSql.refused(db, "fk_design_draft_project", "UPDATE design.design_draft SET project_id = ? "
                        + "WHERE id = ?", strangers, draft);
                // Waiting for review, it joins a project; it neither leaves nor moves.
                RawSql.update(db, "UPDATE design.design_draft SET project_id = ? WHERE id = ?", project, draft);
                RawSql.refused(db, "tg_design_draft_project_while_pending", "UPDATE design.design_draft SET "
                        + "project_id = ? WHERE id = ?", other, draft);
                RawSql.refused(db, "tg_design_draft_project_while_pending", "UPDATE design.design_draft SET "
                        + "project_id = NULL WHERE id = ?", draft);

                // A design waits in one round at a time.
                UUID round = UUID.randomUUID();
                UUID second = UUID.randomUUID();
                RawSql.update(db, "SET session_replication_role = replica");
                for (UUID id : new UUID[] {round, second}) {
                    RawSql.update(db, "INSERT INTO design.design_review_round (id, project_id, submitted_by, "
                            + "submitted_at, created_at) VALUES (?, ?, ?, NOW(), NOW())", id, project, user);
                }
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                RawSql.update(db, "INSERT INTO design.design_review_round_design (round_id, draft_id) VALUES (?, ?)",
                        round, draft);
                RawSql.refused(db, "uk_design_review_round_design_waiting", "INSERT INTO "
                        + "design.design_review_round_design (round_id, draft_id) VALUES (?, ?)", second, draft);

                // The project's history is append-only.
                UUID event = UUID.randomUUID();
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, "
                        + "created_at) VALUES (?, ?, ?, 'PROJECT_CREATED', 'P', NOW())", event, project, user);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                assertThat(RawSql.text(db, "SELECT event FROM design.design_project_event WHERE id = ?", event))
                        .isEqualTo("PROJECT_CREATED");
                assertThatThrownBy(() -> RawSql.update(db, "DELETE FROM design.design_project_event WHERE id = ?",
                        event)).hasMessageContaining("append-only");
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
