package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261112000300 on the real upgrade path, over the demo seed (team plan 4D-2; kltn-docs 03 §1, §4.2, BR-14; 09 BR-06;
 * 17 BR-18). Receipts written before - one against a purchase order and posted, one without a purchase order still a
 * draft - become PURCHASE receipts, untouched, and every new CHECK is validated on them; a blind purchase receipt is
 * still written as before. On the upgraded schema a RETURN receipt is taken for a returned shipment into the
 * warehouse's quarantine area, and refused against two sources, against a shipment not returned, or as a purchase
 * receipt naming a shipment.
 */
class ReturnReceiptsMigrationUpgradeTest {

    private static final String BEFORE = "20261112000200";
    private static final String MIGRATION = "20261112000300";

    private static final String HCM = "md5('demo:wh:HCM')::uuid";
    private static final String EDITOR = "md5('demo:user:editor')::uuid";
    private static final String PO_RECEIPT = "00000000-0000-4000-8000-0000000d2001";
    private static final String BLIND_RECEIPT = "00000000-0000-4000-8000-0000000d2002";
    private static final String ORDER = "00000000-0000-4000-8000-0000000d2101";
    private static final String RETURNED = "00000000-0000-4000-8000-0000000d2201";
    private static final String DELIVERED = "00000000-0000-4000-8000-0000000d2202";
    private static final String RETURN_REQUEST = "00000000-0000-4000-8000-0000000d2301";

    /** A RETURN receipt against {@code shipment} and/or {@code request} of {@link #ORDER}, into HCM-QC01. */
    private static final String RETURN_RECEIPT = "INSERT INTO procurement.goods_receipts (id, receipt_number, "
            + "warehouse_id, status, received_at, received_by, receipt_type, customer_order_id, return_shipment_id, "
            + "return_request_id, quarantine_location_id) VALUES (gen_random_uuid(), ?, " + HCM + ", 'DRAFT', NOW(), "
            + EDITOR + ", 'RETURN', '" + ORDER + "'::uuid, ?::uuid, ?::uuid, (SELECT id FROM warehouse.storage_location "
            + "WHERE location_code = 'HCM-QC01'))";

    @Test
    void earlierReceiptsBecomePurchaseReceiptsAndReturnReceiptsKeepTheirRules() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The receipts written before are purchase receipts, as they were, naming nothing of a return.
                assertThat(RawSql.texts(db, "SELECT receipt_type || ':' || status || ':' || (customer_order_id IS NULL "
                        + "AND return_shipment_id IS NULL AND return_request_id IS NULL AND quarantine_location_id IS "
                        + "NULL) FROM procurement.goods_receipts WHERE id IN (?::uuid, ?::uuid) ORDER BY id",
                        PO_RECEIPT, BLIND_RECEIPT))
                        .containsExactly("PURCHASE:CLOSED:true", "PURCHASE:DRAFT:true");
                assertThat(RawSql.text(db, "SELECT blind_reason FROM procurement.goods_receipts WHERE id = ?::uuid",
                        BLIND_RECEIPT)).isEqualTo("Arrived without a PO");

                // Every new or rewritten CHECK holds on them: none was left NOT VALID.
                assertThat(RawSql.texts(db, "SELECT conname || ':' || convalidated FROM pg_constraint WHERE conname IN "
                        + "('ck_goods_receipts_type', 'ck_goods_receipts_return_source', "
                        + "'ck_goods_receipts_return_purchase', 'ck_goods_receipts_blind', "
                        + "'ck_goods_receipts_blind_approved', 'ck_goods_receipts_return_status', "
                        + "'ck_stock_movement_reference', 'ck_stock_movement_return_receipt') ORDER BY conname"))
                        .containsExactly("ck_goods_receipts_blind:true", "ck_goods_receipts_blind_approved:true",
                                "ck_goods_receipts_return_purchase:true", "ck_goods_receipts_return_source:true",
                                "ck_goods_receipts_return_status:true", "ck_goods_receipts_type:true",
                                "ck_stock_movement_reference:true", "ck_stock_movement_return_receipt:true");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'procurement' AND table_name = 'goods_receipt_return_cartons'")).isOne();

                // A blind purchase receipt is written as before: a supplier and a reason, no type named.
                RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, "
                        + "received_at, received_by, supplier_id, blind_reason) VALUES (gen_random_uuid(), "
                        + "'GR-UPG4D2-B2', " + HCM + ", 'DRAFT', NOW(), " + EDITOR + ", (SELECT supplier_id FROM "
                        + "procurement.purchase_orders LIMIT 1), 'Second truck')");
                RawSql.refused(db, "a purchase receipt naming a returned shipment", "INSERT INTO "
                        + "procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, "
                        + "received_by, supplier_id, blind_reason, return_shipment_id) VALUES (gen_random_uuid(), "
                        + "'GR-UPG4D2-B3', " + HCM + ", 'DRAFT', NOW(), " + EDITOR + ", (SELECT supplier_id FROM "
                        + "procurement.purchase_orders LIMIT 1), 'Second truck', ?::uuid)", RETURNED);

                // A RETURN receipt: one source, a returned one.
                RawSql.refused(db, "a return receipt against a shipment and a return request", RETURN_RECEIPT,
                        "GR-UPG4D2-R1", RETURNED, RETURN_REQUEST);
                RawSql.refused(db, "a return receipt against nothing", RETURN_RECEIPT, "GR-UPG4D2-R2", null, null);
                RawSql.refused(db, "a return receipt for a delivered shipment", RETURN_RECEIPT, "GR-UPG4D2-R3",
                        DELIVERED, null);
                RawSql.update(db, RETURN_RECEIPT, "GR-UPG4D2-R4", RETURNED, null);
                RawSql.refused(db, "a second return receipt for the same shipment", RETURN_RECEIPT, "GR-UPG4D2-R5",
                        RETURNED, null);
                RawSql.update(db, RETURN_RECEIPT, "GR-UPG4D2-R6", null, RETURN_REQUEST);
                assertThat(RawSql.number(db, "SELECT count(*) FROM procurement.goods_receipts WHERE receipt_type = "
                        + "'RETURN' AND status = 'DRAFT'")).isEqualTo(2);
            }
        }
    }

    /**
     * Demo-like rows of the old schema, with foreign keys and row triggers off while they are written (the order's
     * and the shipment's history is not this test's subject): a posted receipt against a demo purchase order and a
     * blind draft; an order of HCM with one shipment brought back by the carrier, one delivered, and an approved return
     * request waiting for its goods.
     */
    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, "
                + "warehouse_id, status, received_at, received_by, confirmed_at, confirmed_by, closed_at) SELECT "
                + "?::uuid, 'GR-UPG4D2-P1', r.po_id, r.id, " + HCM + ", 'CLOSED', NOW() - interval '9 days', " + EDITOR
                + ", NOW() - interval '9 days', " + EDITOR + ", NOW() - interval '8 days' FROM "
                + "procurement.purchase_order_revisions r ORDER BY r.id LIMIT 1", PO_RECEIPT);
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, "
                + "received_at, received_by, supplier_id, blind_reason) SELECT ?::uuid, 'GR-UPG4D2-B1', " + HCM
                + ", 'DRAFT', NOW(), " + EDITOR + ", supplier_id, 'Arrived without a PO' FROM "
                + "procurement.purchase_orders LIMIT 1", BLIND_RECEIPT);
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, warehouse_id) VALUES (?::uuid, 'SO-UPG4D2-1', "
                + "'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000, 'VND', NOW(), NOW(), "
                + HCM + ")", ORDER);
        RawSql.update(db, "INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, ready_at, "
                + "created_at, handed_over_at, outcome_at, whole_order_handover) VALUES (?::uuid, ?::uuid, 'RETURNED', "
                + "'SO-UPG4D2-1-S1', " + HCM + ", NOW(), NOW(), NOW(), NOW(), TRUE), (?::uuid, ?::uuid, 'DELIVERED', "
                + "'SO-UPG4D2-1-S2', " + HCM + ", NOW(), NOW(), NOW(), NOW(), TRUE)", RETURNED, ORDER, DELIVERED,
                ORDER);
        RawSql.update(db, "INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, "
                + "request_id, channel, requested_by, requested_resolution, status, reason_code, requested_at, "
                + "return_required, return_deadline, decided_by, decided_at) VALUES (?::uuid, 'RMA-UPG4D2-1', ?::uuid, "
                + "'SO-UPG4D2-1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'BACK_OFFICE', " + EDITOR
                + ", 'REFUND', 'AWAITING_RETURN', 'DAMAGED_IN_TRANSIT', NOW(), TRUE, NOW() + interval '14 days', "
                + "md5('demo:user:approver')::uuid, NOW())", RETURN_REQUEST, ORDER);
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }
}
