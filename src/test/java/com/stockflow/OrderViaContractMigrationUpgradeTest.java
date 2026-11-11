package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * V20261108002300 on the real upgrade path (kltn-docs 14 §4.3, §5.3, BR-15; 17 §5.1; team plan 4B-5): PENDING_CONTRACT
 * and its edges; an order's status and its live contract's agree at commit; a signed contract is what confirms an order
 * (contract_signed_at), and only an order past the contract's wait carries it; orders confirmed before keep their basis.
 */
class OrderViaContractMigrationUpgradeTest {

    private static final String BEFORE = "20261108002200";
    private static final String MIGRATION = "20261108002300";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000e1";
    private static final String USER = "00000000-0000-4000-8000-0000000000e2";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000e3";
    private static final String ORDER = "00000000-0000-4000-8000-0000000000e4";
    private static final String LINE = "00000000-0000-4000-8000-0000000000e5";
    private static final String CONTRACT = "00000000-0000-4000-8000-0000000000e6";
    private static final String PAID = "00000000-0000-4000-8000-0000000000e7";
    private static final String BLANK = "UPG-VC";
    private static final String OWN = "UPG-VC-OWN";

    @Test
    void theOrderFollowsItsContract() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Orders confirmed before keep their basis: paid in full on prepaid terms.
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?::uuid", PAID))
                        .isEqualTo("CONFIRMED");
                assertThat(RawSql.texts(db, "SELECT from_status || '>' || to_status FROM ordering.order_status_transition "
                        + "WHERE from_status = 'PENDING_CONTRACT' OR to_status = 'PENDING_CONTRACT' ORDER BY 1"))
                        .containsExactly("DRAFT>PENDING_CONTRACT", "PENDING_CONTRACT>CANCELLED",
                                "PENDING_CONTRACT>CONFIRMED", "PENDING_CONTRACT>DRAFT", "PENDING_CONTRACT>ON_HOLD",
                                "PENDING_CONTRACT>PENDING_PAYMENT");

                // 14 §5.3: the order waits for a contract sent, and a contract sent has its order waiting.
                inOneTransaction(db, "has no contract sent",
                        "UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = '" + ORDER + "'");
                inOneTransaction(db, "has a contract sent for signing, but is DRAFT", send());
                inOneTransaction(db, null, send(),
                        "UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = '" + ORDER + "'");
                refusedBy(db, "ck_order_contract_signed", "UPDATE ordering.customer_order SET contract_signed_at = NOW() "
                        + "WHERE id = '" + ORDER + "'");

                // Cancelled to be drawn up again: back to DRAFT together.
                inOneTransaction(db, "has a contract sent for signing, but is DRAFT",
                        "UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = '" + ORDER + "'");

                // Signed: the order is confirmed on its contract, not on a payment its prepaid term would want.
                inOneTransaction(db, "has a signed contract, but is still PENDING_CONTRACT", sign());
                refusedBy(db, "ck_order_confirmed_basis", "UPDATE ordering.customer_order SET status = 'CONFIRMED' "
                        + "WHERE id = '" + ORDER + "'");
                inOneTransaction(db, null, sign(), "UPDATE ordering.customer_order SET status = 'CONFIRMED', "
                        + "contract_signed_at = NOW() WHERE id = '" + ORDER + "'");
                assertThat(RawSql.text(db, "SELECT status || ':' || (contract_signed_at IS NOT NULL) FROM "
                        + "ordering.customer_order WHERE id = ?::uuid", ORDER)).isEqualTo("CONFIRMED:true");
            }
        }
    }

    private static String send() {
        return "UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = '" + USER
                + "', sent_document_key = 'documents/hd.pdf' WHERE id = '" + CONTRACT + "'";
    }

    private static String sign() {
        return "UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = '" + USER
                + "', signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf' "
                + "WHERE id = '" + CONTRACT + "'";
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES (?::uuid, 'vc-sales', 'vc-sales@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
        RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                + "VALUES (?::uuid, 'Cong ty VC', 'vc@example.com', 'ACTIVE', 0, NOW())", CUSTOMER);
        RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, "
                + "created_at) VALUES (?::uuid, 'UPGVC', 'Ly', 'upgvc', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW())",
                PRODUCT);
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at) VALUES (gen_random_uuid(), ?::uuid, ?, 'Ly', "
                + "'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())", PRODUCT, BLANK);
        RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                + "current_artifacts) SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'DRAFT', NOW(), '{}'::jsonb",
                CUSTOMER, PRODUCT);
        RawSql.update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                + "created_at, artifact_manifest) SELECT gen_random_uuid(), d.id, ?, 'https://x/p.pdf', NOW(), NOW(), "
                + "'[]'::jsonb FROM design.design_draft d", "9".repeat(64));
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at, owner_customer_id, design_snapshot_id, "
                + "source_variant_id) SELECT gen_random_uuid(), ?::uuid, ?, 'Ly in logo', 'ACTIVE', FALSE, ?, 1, 0, "
                + "NOW(), ?::uuid, s.id, v.id FROM design.design_snapshot s, product.variants v WHERE v.sku = ?",
                PRODUCT, OWN, "OWNED-SKU:" + OWN, CUSTOMER, BLANK);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, default_purchase, active, version, created_at, default_shipping) SELECT gen_random_uuid(), "
                + "i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE FROM inventory.inventory_items i "
                + "WHERE i.sku = ?", OWN);
        // A draft order of 20 cartons with its draft contract: one batch of the 20 cartons, everything on signing.
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, tax_amount, currency, placed_at, created_at) VALUES (?::uuid, 'SO-UPG-VC', ?::uuid, "
                + "gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW())", ORDER, CUSTOMER);
        RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                + "uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount) SELECT ?::uuid, ?::uuid, ?, "
                + "1000, 2000, 'VND', u.id, 20, 50, 10, 200000 FROM inventory.uom_conversions u JOIN "
                + "inventory.inventory_items i ON i.id = u.inventory_item_id WHERE i.sku = ? AND u.unit_of_measure = "
                + "'CARTON'", LINE, ORDER, OWN, OWN);
        db.setAutoCommit(false);
        RawSql.update(db, "INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, "
                + "seller_tax_code, seller_address, seller_representative, seller_representative_title, buyer_name, "
                + "buyer_address, buyer_representative, buyer_representative_title, free_sample_rounds) VALUES "
                + "(?::uuid, 'HD-UPG-VC-1', ?::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A', 'Director', "
                + "'Cong ty VC', '1 Le Loi', 'Mr B', 'Director', 2)", CONTRACT, ORDER);
        RawSql.update(db, "INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end) VALUES "
                + "('00000000-0000-4000-8000-0000000000e8', ?::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 30)",
                CONTRACT);
        RawSql.update(db, "INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons) "
                + "VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000000e8', ?::uuid, 20)", LINE);
        RawSql.update(db, "INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, "
                + "amount, due_days) VALUES (gen_random_uuid(), ?::uuid, 1, 'All', 'ON_SIGNING', 2200000, 0)", CONTRACT);
        db.commit();
        db.setAutoCommit(true);
        // An order confirmed before, paid in full on prepaid terms.
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, paid_amount, paid_in_full_at, currency, placed_at, created_at) VALUES (?::uuid, "
                + "'SO-UPG-VC-P', ?::uuid, gen_random_uuid(), 'CONFIRMED', 1000, 1000, NOW(), 'VND', NOW(), NOW())",
                PAID, CUSTOMER);
    }

    /**
     * Runs the statements in one transaction. With {@code refusal} null it must commit; otherwise the commit must fail
     * with that message, and nothing is kept.
     */
    private static void inOneTransaction(Connection db, String refusal, String... statements) throws SQLException {
        db.setAutoCommit(false);
        try {
            for (String statement : statements) {
                RawSql.update(db, statement);
            }
            if (refusal == null) {
                db.commit();
                return;
            }
            SQLException refused = catchThrowableOfType(SQLException.class, db::commit);
            if (refused == null) {
                throw new AssertionError("refused at commit (" + refusal + "), but the database accepted it");
            }
            assertThat(refused.getMessage()).contains(refusal);
        } finally {
            db.rollback();
            db.setAutoCommit(true);
        }
    }

    private static void refusedBy(Connection db, String because, String sql) {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> RawSql.update(db, sql));
        if (refusal == null) {
            throw new AssertionError("refused by " + because + ", but the database accepted: " + sql);
        }
        assertThat(refusal.getSQLState()).as(refusal.getMessage()).startsWith("23");
        assertThat(refusal.getMessage()).contains(because);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
