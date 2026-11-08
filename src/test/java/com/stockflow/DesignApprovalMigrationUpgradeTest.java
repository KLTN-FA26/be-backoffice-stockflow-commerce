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
 * V20261108001000 on the real upgrade path: a database migrated to the migration before it, holding a design of the
 * staff workflow with its designer and reviewer assigned, then upgraded.
 *
 * <p>Proves the staff workflow's columns are archived and gone, its permission is gone with every role that held it
 * moving its version, the decision history takes only the business's words from now on while the old rows stay, and the
 * snapshot's layout columns hold the rules they claim.</p>
 */
class DesignApprovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108000900";
    private static final String MIGRATION = "20261108001000";

    @Test
    void upgradeRetiresTheStaffWorkflowKeepingItsTracesInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID draft = UUID.randomUUID();
                UUID designer = UUID.randomUUID();
                UUID reviewer = UUID.randomUUID();
                long managerVersion = version(db, "SALES_MANAGER");
                long adminVersion = version(db, "SYSTEM_ADMIN");
                long customerVersion = version(db, "CUSTOMER");

                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, name, status, "
                        + "created_at, current_artifacts, assigned_user_id, reviewer_user_id, preflight_passed) VALUES "
                        + "(?, gen_random_uuid(), gen_random_uuid(), 'Old', 'DRAFT', NOW(), '{}'::jsonb, ?, ?, FALSE)",
                        draft, designer, reviewer);
                RawSql.update(db, "INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, "
                        + "draft_version, created_at) VALUES (gen_random_uuid(), ?, ?, 'SENT_TO_CUSTOMER', 'x', 0, NOW())",
                        draft, designer);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The staff workflow's columns: archived, then gone.
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'assigned_user_id') || ':' || (row_data ->> "
                        + "'reviewer_user_id') || ':' || (row_data ->> 'preflight_passed') FROM platform.legacy_archive "
                        + "WHERE source_table = 'design.design_draft.staff_workflow' AND row_id = ?", draft))
                        .isEqualTo(designer + ":" + reviewer + ":false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'design' AND table_name = 'design_draft' AND column_name IN ('assigned_user_id', "
                        + "'reviewer_user_id', 'preflight_passed', 'last_editor_user_id')")).isZero();

                // Its permission: gone, every role that held it moved its version, the others did not.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE "
                        + "'design-administration:%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.permission' AND row_data ->> 'code' LIKE 'design-administration:%'")).isEqualTo(2);
                assertThat(version(db, "SALES_MANAGER")).isEqualTo(managerVersion + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(adminVersion + 1);
                assertThat(version(db, "CUSTOMER")).isEqualTo(customerVersion);

                // The history keeps its old words and takes only the business's from now on.
                assertThat(RawSql.number(db, "SELECT count(*) FROM design.design_decision WHERE draft_id = ?", draft))
                        .isEqualTo(1);
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.refused(db, "ck_design_decision_type", "INSERT INTO design.design_decision (id, draft_id, "
                        + "actor_id, decision, notes, draft_version, created_at) VALUES (gen_random_uuid(), ?, ?, "
                        + "'CUSTOMER_CONFIRMED', 'x', 0, NOW())", draft, designer);
                RawSql.update(db, "INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, "
                        + "draft_version, created_at) VALUES (gen_random_uuid(), ?, ?, 'RETURNED', 'x', 0, NOW())",
                        draft, reviewer);

                // A snapshot's layout: all of it or none, and its checksum is its text's.
                RawSql.refused(db, "ck_design_snapshot_layout", "INSERT INTO design.design_snapshot (id, draft_id, "
                        + "checksum, artifact_url, confirmed_at, created_at, artifact_manifest, document) VALUES "
                        + "(gen_random_uuid(), ?, ?, 'design-renders/a.pdf', NOW(), NOW(), '[]'::jsonb, '{}')", draft,
                        "a".repeat(64));
                RawSql.refused(db, "ck_design_snapshot_document_checksum", "INSERT INTO design.design_snapshot (id, "
                        + "draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest, document, "
                        + "document_checksum, assets, template) VALUES (gen_random_uuid(), ?, ?, 'design-renders/a.pdf', "
                        + "NOW(), NOW(), '[]'::jsonb, '{}', ?, '[]'::jsonb, '{}'::jsonb)", draft, "a".repeat(64),
                        "b".repeat(64));
                RawSql.update(db, "SET session_replication_role = DEFAULT");
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
