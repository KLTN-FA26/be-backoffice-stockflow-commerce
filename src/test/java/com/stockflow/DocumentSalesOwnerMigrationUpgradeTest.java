package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20270128000300 on the real upgrade path (kltn-docs 14 BR-26, §4.4 step 19: "Báo giá, hợp đồng lập trước 2026-11-28
 * được điền từ người gửi"): every quote and contract that existed before gets its sender as the Sales owner of when it
 * was drawn up - only when the sender holds SALES_STAFF (owner decision D42: a manager's document stays without one),
 * whatever the account's status today (it was the salesperson then). Unsent documents stay without one. Once set, the
 * column never changes; and V20270128000310's "làm hộ" table is there.
 */
class DocumentSalesOwnerMigrationUpgradeTest {

    private static final String BEFORE = "20270128000145";
    private static final String TARGET = "20270128000310";

    private static final UUID SALES = UUID.randomUUID();
    private static final UUID LOCKED_SALES = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private static final UUID SENT_BY_SALES = UUID.randomUUID();
    private static final UUID SENT_BY_LOCKED = UUID.randomUUID();
    private static final UUID SENT_BY_MANAGER = UUID.randomUUID();
    private static final UUID NEVER_SENT = UUID.randomUUID();
    private static final UUID CONTRACT_SENT = UUID.randomUUID();
    private static final UUID CONTRACT_BY_MANAGER = UUID.randomUUID();

    private static final Instant T1 = Instant.parse("2026-11-01T03:00:00Z");

    @Test
    @DisplayName("14 BR-26: documents drawn up before are given their salesperson sender as their owner, once")
    void documentsAreFilledFromTheirSalespersonSender() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, TARGET).migrate();
                assertThat(result.migrations).extracting(m -> m.version)
                        .containsExactly("20270128000300", "20270128000310");

                assertThat(quoteOwner(db, SENT_BY_SALES)).isEqualTo(SALES.toString());
                assertThat(quoteOwner(db, SENT_BY_LOCKED)).as("the salesperson then, though locked now")
                        .isEqualTo(LOCKED_SALES.toString());
                assertThat(quoteOwner(db, SENT_BY_MANAGER)).as("D42: a manager never owns").isEqualTo("null");
                assertThat(quoteOwner(db, NEVER_SENT)).as("nobody sent it").isEqualTo("null");
                assertThat(contractOwner(db, CONTRACT_SENT)).isEqualTo(SALES.toString());
                assertThat(contractOwner(db, CONTRACT_BY_MANAGER)).as("D42: a manager never owns")
                        .isEqualTo("null");

                RawSql.refusedBy(db, "immutable once set", "BR-26: a quote's owner never moves",
                        "UPDATE ordering.quote SET sales_owner_id = ? WHERE id = ?", MANAGER, SENT_BY_SALES);
                RawSql.refusedBy(db, "immutable once set", "BR-26: a contract's owner never moves",
                        "UPDATE ordering.sales_contract SET sales_owner_id = NULL WHERE id = ?", CONTRACT_SENT);
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_on_behalf_action")).isZero();
            }
        }
    }

    private static String quoteOwner(Connection db, UUID quote) throws SQLException {
        return RawSql.text(db, "SELECT coalesce(sales_owner_id::text, 'null') FROM ordering.quote WHERE id = ?", quote);
    }

    private static String contractOwner(Connection db, UUID contract) throws SQLException {
        return RawSql.text(db, "SELECT coalesce(sales_owner_id::text, 'null') FROM ordering.sales_contract WHERE id = ?",
                contract);
    }

    private static void seed(Connection db) throws SQLException {
        // Foreign keys and triggers off, as rows written before; CHECKs still hold.
        RawSql.update(db, "SET session_replication_role = replica");
        user(db, SALES, "ACTIVE", "SALES_STAFF");
        user(db, LOCKED_SALES, "LOCKED", "SALES_STAFF");
        user(db, MANAGER, "ACTIVE", "SALES_MANAGER");
        RawSql.update(db, "INSERT INTO customer.customer (id, email, full_name, status, created_at) VALUES "
                + "(?, ?, 'Upgrade customer', 'ACTIVE', NOW())", CUSTOMER, "upg-" + CUSTOMER + "@example.test");
        quote(db, SENT_BY_SALES, "SENT", SALES);
        quote(db, SENT_BY_LOCKED, "SENT", LOCKED_SALES);
        quote(db, SENT_BY_MANAGER, "SENT", MANAGER);
        quote(db, NEVER_SENT, "DRAFT", null);
        contract(db, CONTRACT_SENT, SALES);
        contract(db, CONTRACT_BY_MANAGER, MANAGER);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void user(Connection db, UUID id, String status, String role) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, version, created_at) VALUES "
                + "(?, ?, ?, ?, 0, NOW())", id, "upg-" + id, "upg-" + id + "@example.test", status);
        RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, version, created_at) SELECT "
                + "gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", id, role);
    }

    private static void quote(Connection db, UUID id, String status, UUID sender) throws SQLException {
        RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until, "
                + "sent_at, sent_by, created_at) VALUES (?, ?, gen_random_uuid(), ?, ?, NOW() + interval '30 days', ?, ?, "
                + "NOW())", id, "Q-" + id.toString().substring(0, 12), CUSTOMER, status,
                sender == null ? null : Timestamp.from(T1), sender);
    }

    /**
     * A contract that was sent and then cancelled: the contract's terms trigger (batches, installments) passes over a
     * cancelled contract, so the fixture needs no schedule; the backfill reads the sender all the same.
     */
    private static void contract(Connection db, UUID id, UUID sender) throws SQLException {
        UUID order = UUID.randomUUID();
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, request_id, customer_id, status, "
                + "total_amount, currency, placed_at, created_at) VALUES (?, ?, gen_random_uuid(), ?, 'DRAFT', 0, "
                + "'VND', NOW(), NOW())", order, "SO-" + order.toString().substring(0, 12), CUSTOMER);
        RawSql.update(db, "INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, "
                + "seller_tax_code, seller_address, seller_representative, seller_representative_title, buyer_name, "
                + "buyer_address, buyer_representative, buyer_representative_title, free_sample_rounds, sent_at, "
                + "sent_by, sent_document_key, cancelled_at, cancelled_by, cancellation_reason) VALUES (?, ?, ?, "
                + "'CANCELLED', 'Seller', '0312345678', 'Addr', 'Rep', 'Director', 'Buyer', 'Addr', 'Rep', 'Director', 0, "
                + "?, ?, 'contracts/k.pdf', ?, ?, 'redrawn')",
                id, "HD-" + order.toString().substring(0, 12), order, Timestamp.from(T1), sender, Timestamp.from(T1),
                sender);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
