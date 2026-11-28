package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20270129000100 on the real upgrade path (contract e-signing plan, 2026-11-28): contracts sent before revisions existed
 * become their own revision 1 in {@code ordering.sales_contract_revision} - the PDF they were sent as, when and by whom,
 * written by Flyway - and a draft, never sent, gets none. Every contract starts at revision 1 with standard terms and no
 * review. Over the demo seed, whose contracts are drafts, sent and signed ones.
 */
class ContractRevisionsMigrationUpgradeTest {

    private static final String BEFORE = "20270128000470";
    private static final String MIGRATION = "20270129000100";

    @Test
    void sentContractsBecomeTheirOwnFirstRevisionAndDraftsGetNone() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                long sent = RawSql.number(db, "SELECT count(*) FROM ordering.sales_contract WHERE sent_at IS NOT NULL");
                long drafts = RawSql.number(db, "SELECT count(*) FROM ordering.sales_contract WHERE sent_at IS NULL");
                assertThat(sent).as("the demo seed has contracts already sent").isPositive();
                assertThat(drafts).as("and drafts never sent").isPositive();

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.sales_contract_revision"))
                        .as("one revision for each contract sent, none for a draft").isEqualTo(sent);
                assertThat(RawSql.number(db, """
                        SELECT count(*) FROM ordering.sales_contract_revision r
                          JOIN ordering.sales_contract k ON k.id = r.contract_id
                         WHERE r.revision = 1 AND r.sent_document_key = k.sent_document_key
                           AND r.sent_at = k.sent_at AND r.sent_by = k.sent_by AND r.created_at = k.sent_at
                           AND r.created_by = 'flyway' AND r.change_request_reason IS NULL""")).as("the PDF it was "
                        + "sent as, when and by whom, and no change request").isEqualTo(sent);
                assertThat(RawSql.number(db, """
                        SELECT count(*) FROM ordering.sales_contract k
                         WHERE k.sent_at IS NULL AND EXISTS (SELECT 1 FROM ordering.sales_contract_revision r
                                                              WHERE r.contract_id = k.id)""")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.sales_contract WHERE revision <> 1 "
                        + "OR terms_review <> 'NONE' OR non_standard_terms OR sign_by IS NOT NULL "
                        + "OR customer_signed_at IS NOT NULL")).as("every contract starts at revision 1, standard, "
                        + "no review, no deadline, not signed on the portal").isZero();
                // What was sent stays as written: the revision row refuses a new PDF.
                String contractId = RawSql.text(db, "SELECT contract_id::text FROM ordering.sales_contract_revision "
                        + "ORDER BY contract_id LIMIT 1");
                RawSql.refusedBy(db, "is immutable once set", "the PDF a revision was sent as is written once",
                        "UPDATE ordering.sales_contract_revision SET sent_document_key = 'documents/other.pdf' "
                                + "WHERE contract_id = ?::uuid", contractId);
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }
}
