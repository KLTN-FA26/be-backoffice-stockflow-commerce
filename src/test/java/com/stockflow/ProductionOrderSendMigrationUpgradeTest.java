package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261213000100 on the real upgrade path (design 2026-11-20 §2-§3; kltn-docs 17 §4.3 step 7, BR-23 rewritten): the
 * Sales Manager's approval of production orders is gone, so a production order waiting for it goes back to DRAFT, with
 * the reason and a status-history row saying why, and every production order already sent keeps its approver as the
 * one who sent it - the column the coordinator's sending writes from now on, set exactly when {@code sent_at} is.
 */
class ProductionOrderSendMigrationUpgradeTest {

    private static final String BEFORE = "20261212000100";
    private static final String MIGRATION = "20261213000100";
    private static final String REASON = "Approval step removed 2026-11-20";

    @Test
    void pendingOrdersGoBackToDraftAndSentOrdersKeepTheirSender() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // The demo seed has no production order short of SENT: a second one is drawn up for the batch of demo
                // order SO-20261031-910010, a copy of the one it has, and submitted for approval the old way.
                db.setAutoCommit(false);
                RawSql.update(db, "INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, "
                        + "customer_id, producer_id, receiving_warehouse_id, need_by_date, status, "
                        + "sample_approval_required, sample_required_by_default, sample_waiver_reason, drawn_up_by) "
                        + "SELECT md5('upgrade:po')::uuid, 'PRD-20261120-9901', p.sales_order_id, p.delivery_batch_id, "
                        + "p.customer_id, p.producer_id, p.receiving_warehouse_id, p.need_by_date, 'DRAFT', FALSE, "
                        + "p.sample_required_by_default, p.sample_waiver_reason, p.drawn_up_by "
                        + "FROM ordering.production_order p JOIN ordering.customer_order o ON o.id = p.sales_order_id "
                        + "WHERE o.order_number = 'SO-20261031-910010'");
                RawSql.update(db, "INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, "
                        + "sku, cartons, units_per_carton, design_snapshot_id, print_file_checksum) "
                        + "SELECT gen_random_uuid(), md5('upgrade:po')::uuid, l.order_line_id, l.sku, 10, "
                        + "l.units_per_carton, l.design_snapshot_id, l.print_file_checksum "
                        + "FROM ordering.production_order_line l JOIN ordering.production_order p "
                        + "ON p.id = l.production_order_id WHERE p.code <> 'PRD-20261120-9901' AND p.sales_order_id = "
                        + "(SELECT sales_order_id FROM ordering.production_order WHERE code = 'PRD-20261120-9901')");
                db.commit();
                db.setAutoCommit(true);
                RawSql.update(db, "UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', "
                        + "submitted_at = NOW() WHERE code = 'PRD-20261120-9901'");
                long sentBefore = RawSql.number(db,
                        "SELECT count(*) FROM ordering.production_order WHERE sent_at IS NOT NULL");
                assertThat(sentBefore).as("the demo seed has production orders already sent").isPositive();

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.text(db, "SELECT status || ':' || returned_reason FROM ordering.production_order "
                        + "WHERE code = 'PRD-20261120-9901'")).as("back to draft, saying why")
                        .isEqualTo("DRAFT:" + REASON);
                assertThat(RawSql.texts(db, "SELECT from_status || '>' || to_status || ':' || reason || ':' "
                        + "|| (changed_by IS NULL) FROM ordering.production_order_status_history h "
                        + "JOIN ordering.production_order p ON p.id = h.production_order_id "
                        + "WHERE p.code = 'PRD-20261120-9901'"))
                        .as("one history row, by no one").containsExactly("PENDING_APPROVAL>DRAFT:" + REASON + ":true");

                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.production_order "
                        + "WHERE sent_at IS NOT NULL AND sent_by IS NOT DISTINCT FROM approved_by"))
                        .as("every order sent keeps its approver as its sender").isEqualTo(sentBefore);
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.production_order "
                        + "WHERE sent_at IS NULL AND sent_by IS NOT NULL")).isZero();

                String sentId = RawSql.text(db, "SELECT id::text FROM ordering.production_order "
                        + "WHERE sent_at IS NOT NULL ORDER BY code LIMIT 1");
                RawSql.refusedBy(db, "kept as written", "BR-23: who sent it is written once",
                        "UPDATE ordering.production_order SET sent_by = NULL WHERE id = ?::uuid", sentId);
                // The CHECK on its own, past the trigger that refuses the same write first.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.refusedBy(db, "ck_production_order_sent", "sent_at and sent_by go together",
                        "UPDATE ordering.production_order SET sent_by = NULL WHERE id = ?::uuid", sentId);
                RawSql.update(db, "SET session_replication_role = origin");
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
