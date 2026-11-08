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
 * V20261108000800 on the real upgrade path: a database migrated to the migration before it, holding a design in each
 * status the staff workflow wrote - one waiting for the customer's confirmation (SUBMITTED), one in the REJECTED status
 * nothing wrote, a draft and an approved one - then upgraded.
 *
 * <p>Proves the statuses become the four the business names (SUBMITTED waits for a decision, so PENDING_REVIEW;
 * REJECTED goes back to DRAFT), the old values are archived first, the other two stay as they were, the old designs
 * have no template (they are read, never laid out), and the new tables hold the rules they claim.</p>
 */
class DesignCustomerDraftsMigrationUpgradeTest {

    private static final String BEFORE = "20261108000700";
    private static final String MIGRATION = "20261108000800";

    @Test
    void upgradeRenamesTheStatusesKeepingTheOldOnesInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID submitted = UUID.randomUUID();
                UUID rejected = UUID.randomUUID();
                UUID draft = UUID.randomUUID();
                UUID approved = UUID.randomUUID();

                // The customer and the product are not what this test is about: foreign keys are off while the old
                // designs are written.
                RawSql.update(db, "SET session_replication_role = replica");
                for (Object[] row : new Object[][] {{submitted, "SUBMITTED"}, {rejected, "REJECTED"},
                        {draft, "DRAFT"}, {approved, "APPROVED"}}) {
                    RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, name, status, "
                            + "created_at, current_artifacts) VALUES (?, gen_random_uuid(), gen_random_uuid(), 'Old', "
                            + "?, NOW(), '{}'::jsonb)", row[0], row[1]);
                }
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(status(db, submitted)).isEqualTo("PENDING_REVIEW");
                assertThat(status(db, rejected)).isEqualTo("DRAFT");
                assertThat(status(db, draft)).isEqualTo("DRAFT");
                assertThat(status(db, approved)).isEqualTo("APPROVED");
                assertThat(RawSql.texts(db, "SELECT row_id::text || '=' || (row_data ->> 'status') FROM "
                        + "platform.legacy_archive WHERE source_table = 'design.design_draft.status'"))
                        .containsExactlyInAnyOrder(submitted + "=SUBMITTED", rejected + "=REJECTED");
                assertThat(RawSql.number(db, "SELECT count(*) FROM design.design_draft WHERE sku IS NOT NULL OR "
                        + "template_id IS NOT NULL OR archived_at IS NOT NULL")).isZero();

                // The old words are gone for good, and an archived design says when.
                RawSql.refused(db, "ck_design_draft_status",
                        "UPDATE design.design_draft SET status = 'SUBMITTED' WHERE id = ?", draft);
                RawSql.refused(db, "ck_design_draft_archived",
                        "UPDATE design.design_draft SET status = 'ARCHIVED' WHERE id = ?", draft);
                RawSql.update(db, "UPDATE design.design_draft SET status = 'ARCHIVED', archived_at = NOW() WHERE id = ?",
                        draft);
                RawSql.refused(db, "ck_design_draft_template",
                        "UPDATE design.design_draft SET sku = 'CUP-12' WHERE id = ?", approved);

                // A layout's checksum is its text's; anything else is refused.
                RawSql.refused(db, "ck_design_document_checksum", "INSERT INTO design.design_document (draft_id, "
                        + "document, checksum, updated_at) VALUES (?, '{}', ?, NOW())", approved, "a".repeat(64));
                RawSql.update(db, "INSERT INTO design.design_document (draft_id, document, checksum, updated_at) "
                        + "VALUES (?, '{}', encode(sha256(convert_to('{}', 'UTF8')), 'hex'), NOW())", approved);

                // An uploaded image is never changed or taken away.
                RawSql.update(db, "INSERT INTO design.design_asset (draft_id, id, storage_key, original_name, "
                        + "content_type, size_bytes, checksum, width_px, height_px, created_at) VALUES (?, "
                        + "gen_random_uuid(), 'design-assets/2026/11/a.png', 'a.png', 'image/png', 10, ?, 10, 10, NOW())",
                        approved, "b".repeat(64));
                RawSql.refused(db, "append-only", "UPDATE design.design_asset SET original_name = 'b.png' WHERE "
                        + "draft_id = ?", approved);
                RawSql.refused(db, "append-only", "DELETE FROM design.design_asset WHERE draft_id = ?", approved);
            }
        }
    }

    private static String status(Connection db, UUID id) throws Exception {
        return RawSql.text(db, "SELECT status FROM design.design_draft WHERE id = ?", id);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
