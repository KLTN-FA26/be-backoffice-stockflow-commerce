package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261114000100 and V20261114000200 on the real upgrade path, over the demo seed (team plan 4D-3 and 4D-4; kltn-docs
 * 17 §4.10, BR-18; 03 §4.2; 15 BR-05; owner's decision QD-1: nothing archived, nothing refuses the upgrade). Written at
 * the schema of the QC era: a return counted in by parcel and not yet inspected (RECEIVED), a resolved one whose money
 * was kept as credit, an open claim, a free replacement order naming the resolved return, an occupied and an empty
 * QUALITY_CONTROL area (beside the demo's own), unfinished event publications of the withdrawn replacement.
 *
 * <p>Upgraded: the RECEIVED return is CANCELLED with its history line (no goods receipt to go on with); the resolved
 * one is history, its credit folded into what was set off and its money mode REFUND; the open claim stays; the QC and
 * replacement columns and the replacement link are gone, ck_order_confirmed_basis is intact; QUALITY_CONTROL areas are
 * quarantine areas, inactive when empty; the permissions of the QC era are gone and the roles that held them have a
 * newer version; the withdrawn contract's unfinished publication is deleted and nothing else. Round 2: a resolved return
 * keeps its claim (accepted units become refunded units, the settlement spread over them to the last digit), one settled
 * at nothing with an approved withdrawal loses its VAT flag instead of being refused, a negative received count is refused.</p>
 */
class LegacyReturnsRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261113000710";
    private static final String RETURNS_THROUGH_RECEIPTS = "20261114000100";
    private static final String MIGRATION = "20261114000200";

    private static final String RECEIVED = "00000000-0000-4000-8000-0000000d4101";
    private static final String RESOLVED = "00000000-0000-4000-8000-0000000d4102";
    private static final String REQUESTED = "00000000-0000-4000-8000-0000000d4103";
    private static final String NOTHING_REFUNDED = "00000000-0000-4000-8000-0000000d4104";
    private static final String EVEN_SPLIT = "00000000-0000-4000-8000-0000000d4105";
    private static final String REPLACEMENT_ORDER = "00000000-0000-4000-8000-0000000d4002";

    /** Rows of the schema before the upgrade, written with triggers and foreign keys off (their history is not the subject). */
    private static final String SEED = """
            SET session_replication_role = replica;
            INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at, warehouse_id)
            VALUES ('00000000-0000-4000-8000-0000000d4001', 'SO-UPG4D4-1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);
            INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
            VALUES ('00000000-0000-4000-8000-0000000d4011', '00000000-0000-4000-8000-0000000d4001', 'SOFA-3S-GREY', 10, 500, 'VND'),
                   ('00000000-0000-4000-8000-0000000d4012', '00000000-0000-4000-8000-0000000d4001', 'TABLE-OAK-160', 10, 500, 'VND');
            INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                requested_resolution, status, reason_code, requested_at, decided_by, decided_at, return_required, return_deadline,
                received_at, received_by, receiving_location_code, parcel_count)
            VALUES ('00000000-0000-4000-8000-0000000d4101', 'RMA-UPG4D4-1', '00000000-0000-4000-8000-0000000d4001', 'SO-UPG4D4-1',
                    'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REPLACEMENT',
                    'RECEIVED', 'DAMAGED_IN_TRANSIT', NOW(), md5('demo:user:approver')::uuid, NOW(), TRUE, NOW() + interval '14 days',
                    NOW(), md5('demo:user:editor')::uuid, 'HCM-QC01', 1);
            INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity,
                approved_quantity, received_quantity)
            VALUES ('00000000-0000-4000-8000-0000000d4111', '00000000-0000-4000-8000-0000000d4101', '00000000-0000-4000-8000-0000000d4011',
                    'SOFA-3S-GREY', FALSE, 500, 4, 4, 4);
            INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id, occurred_at)
            VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000d4101', 'AWAITING_RETURN', 'RECEIVED', 'RECEIVED', NULL, NOW());
            INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, unallocated_amount, currency, received_on, recorded_by, recorded_at, channel)
            VALUES ('00000000-0000-4000-8000-0000000d4501', 'c0000000-0000-4000-8000-000000000001', 'RETURN-CREDIT-UPG4D4', 700, 700, 'VND', CURRENT_DATE, md5('demo:user:approver')::uuid, NOW(), 'RETURN_CREDIT');
            INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                requested_resolution, status, reason_code, requested_at, decided_by, decided_at, return_required, return_deadline,
                received_at, received_by, receiving_location_code, parcel_count, inspected_by, inspected_at, money_mode, settlement_amount,
                credited_amount, offset_amount, credit_transfer_id, proposed_by, proposed_at, settled_by, settled_at, closed_at,
                vat_adjustment_required)
            VALUES ('00000000-0000-4000-8000-0000000d4102', 'RMA-UPG4D4-2', '00000000-0000-4000-8000-0000000d4001', 'SO-UPG4D4-1',
                    'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'CREDIT_NOTE',
                    'RESOLVED', 'PRINT_DEFECT', NOW(), md5('demo:user:approver')::uuid, NOW(), TRUE, NOW() + interval '14 days',
                    NOW(), md5('demo:user:editor')::uuid, 'HCM-QC01', 1, md5('demo:user:editor')::uuid, NOW(), 'CREDIT_NOTE', 1000,
                    700, 300, '00000000-0000-4000-8000-0000000d4501', md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW(), TRUE);
            INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity,
                approved_quantity, received_quantity, received_sku, accepted_quantity, rejected_quantity, item_condition, scrap_quantity,
                credit_quantity, credit_amount)
            VALUES ('00000000-0000-4000-8000-0000000d4112', '00000000-0000-4000-8000-0000000d4102', '00000000-0000-4000-8000-0000000d4012',
                    'TABLE-OAK-160', FALSE, 500, 2, 2, 2, 'TABLE-OAK-160', 2, 0, 'DEFECTIVE', 2, 2, 1000);
            INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                requested_resolution, status, reason_code, requested_at)
            VALUES ('00000000-0000-4000-8000-0000000d4103', 'RMA-UPG4D4-3', '00000000-0000-4000-8000-0000000d4001', 'SO-UPG4D4-1',
                    'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PORTAL', md5('demo:user:editor')::uuid, 'REPLACEMENT',
                    'REQUESTED', 'PRINT_DEFECT', NOW());
            INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at,
                replaces_return_request_id)
            VALUES ('00000000-0000-4000-8000-0000000d4002', 'SO-UPG4D4-R', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
                    'PENDING_PAYMENT', 0, 'VND', NOW(), NOW(), '00000000-0000-4000-8000-0000000d4102');
            INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
            VALUES ('00000000-0000-4000-8000-0000000d4013', '00000000-0000-4000-8000-0000000d4001', 'SOFA-3S-GREY', 10, 100, 'VND'),
                   ('00000000-0000-4000-8000-0000000d4014', '00000000-0000-4000-8000-0000000d4001', 'TABLE-OAK-160', 10, 100, 'VND'),
                   ('00000000-0000-4000-8000-0000000d4015', '00000000-0000-4000-8000-0000000d4001', 'SOFA-3S-GREY', 10, 100, 'VND');
            -- M1: resolved at nothing; a replacement withdrawal approved with an amount raised the VAT flag.
            INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                requested_resolution, status, reason_code, requested_at, decided_by, decided_at, return_required, return_deadline,
                received_at, received_by, receiving_location_code, parcel_count, inspected_by, inspected_at, settlement_amount,
                proposed_by, proposed_at, settled_by, settled_at, closed_at, vat_adjustment_required, replacement_order_id,
                withdrawal_status, withdrawal_reason, withdrawal_amount, withdrawal_proposed_by, withdrawal_proposed_at,
                withdrawal_decided_by, withdrawal_decided_at, withdrawal_offset_amount, withdrawal_credit_transfer_id)
            VALUES ('00000000-0000-4000-8000-0000000d4104', 'RMA-UPG4D4-4', '00000000-0000-4000-8000-0000000d4001', 'SO-UPG4D4-1',
                    'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REPLACEMENT',
                    'RESOLVED', 'PRINT_DEFECT', NOW(), md5('demo:user:approver')::uuid, NOW(), TRUE, NOW() + interval '14 days',
                    NOW(), md5('demo:user:editor')::uuid, 'HCM-QC01', 1, md5('demo:user:editor')::uuid, NOW(), 0,
                    md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW(), TRUE,
                    '00000000-0000-4000-8000-0000000d4002', 'APPROVED', 'replacement withdrawn', 100,
                    md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), 100,
                    '00000000-0000-4000-8000-0000000d4501');
            -- M2: a settlement of 100 spread over three equal lines (rounding goes to the last by id).
            INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                requested_resolution, status, reason_code, requested_at, decided_by, decided_at, return_required, return_deadline,
                received_at, received_by, receiving_location_code, parcel_count, inspected_by, inspected_at, money_mode, settlement_amount,
                refunded_amount, proposed_by, proposed_at, settled_by, settled_at, closed_at, vat_adjustment_required)
            VALUES ('00000000-0000-4000-8000-0000000d4105', 'RMA-UPG4D4-5', '00000000-0000-4000-8000-0000000d4001', 'SO-UPG4D4-1',
                    'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND',
                    'RESOLVED', 'PRINT_DEFECT', NOW(), md5('demo:user:approver')::uuid, NOW(), TRUE, NOW() + interval '14 days',
                    NOW(), md5('demo:user:editor')::uuid, 'HCM-QC01', 1, md5('demo:user:editor')::uuid, NOW(), 'REFUND', 100, 100,
                    md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW(), TRUE);
            INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity,
                approved_quantity, received_quantity, received_sku, accepted_quantity, rejected_quantity, item_condition, scrap_quantity)
            VALUES ('00000000-0000-4000-8000-0000000d4121', '00000000-0000-4000-8000-0000000d4105', '00000000-0000-4000-8000-0000000d4013', 'SOFA-3S-GREY', FALSE, 100, 1, 1, 1, 'SOFA-3S-GREY', 1, 0, 'DEFECTIVE', 1),
                   ('00000000-0000-4000-8000-0000000d4122', '00000000-0000-4000-8000-0000000d4105', '00000000-0000-4000-8000-0000000d4014', 'TABLE-OAK-160', FALSE, 100, 1, 1, 1, 'TABLE-OAK-160', 1, 0, 'DEFECTIVE', 1),
                   ('00000000-0000-4000-8000-0000000d4123', '00000000-0000-4000-8000-0000000d4105', '00000000-0000-4000-8000-0000000d4015', 'SOFA-3S-GREY', FALSE, 100, 1, 1, 1, 'SOFA-3S-GREY', 1, 0, 'DEFECTIVE', 1);
            INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
            VALUES ('00000000-0000-4000-8000-0000000d4201', md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QC77', 'OVERSIZE', FALSE, FALSE, 'ACTIVE'),
                   ('00000000-0000-4000-8000-0000000d4202', md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QC78', 'OVERSIZE', FALSE, FALSE, 'ACTIVE');
            INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
            VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, '00000000-0000-4000-8000-0000000d4201', 'QC77', 'QUALITY_CONTROL', 'QC77', 70, 2, 3, 3, 0, FALSE, 'ACTIVE'),
                   (gen_random_uuid(), md5('demo:wh:HCM')::uuid, '00000000-0000-4000-8000-0000000d4202', 'QC78', 'QUALITY_CONTROL', 'QC78', 70, 8, 3, 3, 0, FALSE, 'ACTIVE');
            INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at)
            VALUES ('00000000-0000-4000-8000-0000000d4301', 'SOFA-3S-GREY', 'HCM-QC77', 3, 0, 'BLOCKED', 0, NOW());
            INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
            VALUES ('00000000-0000-4000-8000-0000000d4401', 'l1', 'com.stockflow.contracts.ReturnReplacementWithdrawn', '{}', NOW(), NULL),
                   ('00000000-0000-4000-8000-0000000d4402', 'l1', 'com.stockflow.contracts.ReturnReplacementWithdrawn', '{}', NOW(), NOW()),
                   ('00000000-0000-4000-8000-0000000d4403', 'l1', 'com.stockflow.contracts.ReturnApproved', '{}', NOW(), NULL);
            SET session_replication_role = DEFAULT;
            """;

    @Test
    void theQcEraReturnFlowIsCleanedUpAndDropped() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                db.createStatement().execute(SEED);
                String rolesBefore = RawSql.text(db, "SELECT string_agg(r.code || ':' || r.version, ',' ORDER BY r.code) "
                        + "FROM identity.app_role r WHERE r.id IN (SELECT rp.role_id FROM identity.role_permission rp "
                        + "JOIN identity.permission p ON p.id = rp.permission_id WHERE p.resource IN "
                        + "('sales-rma-inspections', 'sales-rma-receipts'))");
                assertThat(rolesBefore).as("the demo grants both permissions to someone").isNotBlank();

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version)
                        .containsExactly(RETURNS_THROUGH_RECEIPTS, MIGRATION);

                // A return counted in by parcel cannot go on: CANCELLED with its history line, its proposal cleared.
                assertThat(RawSql.text(db, "SELECT status || ':' || settlement_amount::int || ':' || (closed_at IS NOT NULL) "
                        + "|| ':' || (goods_receipt_id IS NULL) FROM ordering.return_request WHERE id = ?::uuid", RECEIVED))
                        .isEqualTo("CANCELLED:0:true:true");
                assertThat(RawSql.texts(db, "SELECT from_status || '>' || to_status || ':' || reason FROM "
                        + "ordering.return_request_status_history WHERE return_request_id = ?::uuid AND to_status = "
                        + "'CANCELLED'", RECEIVED)).containsExactly("RECEIVED>CANCELLED:QC_ERA_RETURN_CLOSED_BY_MIGRATION");
                // A resolved one is history: the credit folded into what was set off, REFUND everywhere.
                assertThat(RawSql.text(db, "SELECT status || ':' || requested_resolution || ':' || money_mode || ':' || "
                        + "settlement_amount::int || ':' || offset_amount::int || ':' || refunded_amount::int FROM "
                        + "ordering.return_request WHERE id = ?::uuid", RESOLVED))
                        .isEqualTo("RESOLVED:REFUND:REFUND:1000:1000:0");
                assertThat(RawSql.text(db, "SELECT status || ':' || requested_resolution FROM ordering.return_request "
                        + "WHERE id = ?::uuid", REQUESTED)).as("an open claim stays").isEqualTo("REQUESTED:REFUND");
                // A resolved return keeps its claim (M2): accepted units become refunded units, the settlement is spread
                // over them by value, the last line (by id) taking the rounding, so the lines add up to it exactly.
                assertThat(RawSql.text(db, "SELECT refund_quantity || ':' || refund_amount::int FROM "
                        + "ordering.return_request_line WHERE return_request_id = ?::uuid", RESOLVED)).isEqualTo("2:1000");
                assertThat(RawSql.texts(db, "SELECT refund_quantity || ':' || refund_amount FROM "
                        + "ordering.return_request_line WHERE return_request_id = ?::uuid ORDER BY id", EVEN_SPLIT))
                        .containsExactly("1:33.3333", "1:33.3333", "1:33.3334");
                assertThat(RawSql.text(db, "SELECT (sum(l.refund_amount) = r.settlement_amount)::text FROM "
                        + "ordering.return_request r JOIN ordering.return_request_line l ON l.return_request_id = r.id "
                        + "WHERE r.id = ?::uuid GROUP BY r.id, r.settlement_amount", EVEN_SPLIT)).isEqualTo("true");
                // ... so the same units cannot be returned and refunded twice: 2 of the line's 10 are refunded already.
                RawSql.refused(db, "would claim", "INSERT INTO ordering.return_request_line (id, return_request_id, "
                        + "order_line_id, sku, custom_printed, unit_price, quantity) VALUES (gen_random_uuid(), "
                        + "?::uuid, '00000000-0000-4000-8000-0000000d4012', 'TABLE-OAK-160', FALSE, 500, 9)", REQUESTED);
                // M1: settled at nothing, a replacement withdrawal approved with an amount had raised the VAT flag; the
                // flag follows the settlement now, and the migration, running the triggers as they are, was not refused.
                assertThat(RawSql.text(db, "SELECT status || ':' || settlement_amount::int || ':' || vat_adjustment_required "
                        + "FROM ordering.return_request WHERE id = ?::uuid", NOTHING_REFUNDED))
                        .isEqualTo("RESOLVED:0:false");
                assertThat(RawSql.text(db, "SELECT vat_adjustment_required::text FROM ordering.return_request WHERE id = "
                        + "?::uuid", RESOLVED)).as("a settlement above nothing keeps its flag").isEqualTo("true");
                // L2: a return line never receives fewer than nothing (ck_return_request_line_received restated).
                RawSql.refused(db, "ck_return_request_line_received", "UPDATE ordering.return_request_line SET "
                        + "received_quantity = -1 WHERE return_request_id = ?::uuid", RESOLVED);

                // Columns and rules of the QC era are gone; the sets they widened are narrow again.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'ordering' AND ((table_name = 'return_request' AND column_name IN ('inspected_by', "
                        + "'inspected_at', 'inspection_note', 'receipt_note', 'parcel_count', 'credited_amount', "
                        + "'credit_transfer_id', 'replacement_order_id', 'withdrawal_status', 'withdrawal_amount', "
                        + "'withdrawal_credited_amount', 'withdrawal_credit_transfer_id')) OR (table_name = "
                        + "'return_request_line' AND column_name IN ('received_sku', 'lot_number', 'accepted_quantity', "
                        + "'rejected_quantity', 'item_condition', 'restock_quantity', 'scrap_quantity', "
                        + "'customer_return_quantity', 'received_plain', 'replacement_quantity', 'credit_quantity', "
                        + "'credit_amount', 'replacement_order_line_id')) OR (table_name = 'customer_order' AND "
                        + "column_name = 'replaces_return_request_id'))")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_return_request_inspected', 'ck_return_request_received', 'ck_return_request_credit_transfer', "
                        + "'ck_return_request_withdrawal', 'fk_return_request_credit_transfer', "
                        + "'ck_return_request_line_resolution', 'ck_return_request_line_disposition')")).isZero();
                assertThat(RawSql.text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = "
                        + "'ck_return_request_goods_back'")).isEqualTo("true");
                RawSql.refused(db, "an INSPECTED return", "UPDATE ordering.return_request SET status = 'INSPECTED' "
                        + "WHERE id = ?::uuid", REQUESTED);
                RawSql.refused(db, "a return asking for a replacement", "UPDATE ordering.return_request SET "
                        + "requested_resolution = 'REPLACEMENT' WHERE id = ?::uuid", REQUESTED);
                // The order that was a replacement is an ordinary one; ck_order_confirmed_basis names no replacement.
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?::uuid",
                        REPLACEMENT_ORDER)).isEqualTo("PENDING_PAYMENT");
                assertThat(RawSql.text(db, "SELECT (pg_get_constraintdef(oid) NOT LIKE '%replaces_return%')::text FROM "
                        + "pg_constraint WHERE conname = 'ck_order_confirmed_basis' AND conrelid = "
                        + "'ordering.customer_order'::regclass")).isEqualTo("true");
                RawSql.refused(db, "CONFIRMED with no basis", "UPDATE ordering.customer_order SET status = 'CONFIRMED' "
                        + "WHERE id = ?::uuid", REPLACEMENT_ORDER);

                // QUALITY_CONTROL areas are quarantine areas, inactive when empty, their codes kept.
                assertThat(RawSql.texts(db, "SELECT code || ':' || type || ':' || status FROM warehouse.area WHERE code "
                        + "IN ('QC77', 'QC78', 'QCA01') ORDER BY code"))
                        .containsExactly("QC77:QUARANTINE:ACTIVE", "QC78:QUARANTINE:INACTIVE",
                                "QCA01:QUARANTINE:INACTIVE");
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.area WHERE type = 'QUALITY_CONTROL'")).isZero();
                RawSql.refused(db, "a QUALITY_CONTROL area", "UPDATE warehouse.area SET type = 'QUALITY_CONTROL' WHERE "
                        + "code = 'QC77'");

                // The permissions are gone and so is the cached grant: the roles that held them are one version on.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource IN "
                        + "('sales-rma-inspections', 'sales-rma-receipts')")).isZero();
                for (String entry : rolesBefore.split(",")) {
                    String[] role = entry.split(":");
                    assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role[0]))
                            .as(role[0]).isEqualTo(Long.parseLong(role[1]) + 1);
                }

                // Only the withdrawn contract's unfinished publication went.
                assertThat(RawSql.texts(db, "SELECT right(event_type, 17) || ':' || (completion_date IS NULL) FROM "
                        + "event_publication WHERE id::text LIKE '00000000-0000-4000-8000-0000000d44%' ORDER BY id"))
                        .containsExactly("ReplacementWithdrawn:false", "ReturnApproved:true");
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
