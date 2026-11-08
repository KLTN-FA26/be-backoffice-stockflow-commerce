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
 * V20261108000400 on the real upgrade path: a database migrated to the migration before it, holding what parcel
 * shipping, the delivery-mode choice and COD left behind - a parcel load still loading (a shipment on its manifest,
 * a parcel on board), a parcel load dispatched long ago, a load plan's vehicle, a shipment waiting with a carrier chosen
 * in advance, a deposit order whose carrier collects the balance - one shipment with the carrier (COD 700), one
 * delivered whose delivery payment has not billed yet (COD 200, its OrderDelivered still waiting) - a change of delivery
 * mode in the order's history, a customer whose terms let the carrier collect, a shipment event still waiting to be
 * delivered, and the three permissions - then upgraded.
 *
 * <p>Proves nothing is lost (every value dropped is archived), the live parcel load is called off and empties, the
 * history and the plan's vehicle are untouched, the COD the carrier still owes is billed to the carrier (so the delivery
 * never makes it the customer's debt), ON_DELIVERY is gone from orders and terms, the waiting event lost its deliveryMode
 * and nothing else of its text, the columns and the table are gone, the new CHECKs hold, and every role that held a
 * removed permission moves its version.</p>
 */
class ParcelShippingRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108000300";
    private static final String MIGRATION = "20261108000400";

    @Test
    void upgradeRetiresParcelShippingAndCodKeepingEverythingInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID warehouse = UUID.randomUUID();
                UUID user = UUID.randomUUID();
                UUID carrier = UUID.randomUUID();
                UUID order = UUID.randomUUID();
                UUID waiting = UUID.randomUUID();
                UUID leftLongAgo = UUID.randomUUID();
                UUID pack = UUID.randomUUID();
                UUID parcel = UUID.randomUUID();
                UUID liveLoad = UUID.randomUUID();
                UUID oldLoad = UUID.randomUUID();
                UUID plan = UUID.randomUUID();
                UUID vehicle = UUID.randomUUID();
                UUID planLoad = UUID.randomUUID();
                UUID customer = UUID.randomUUID();
                UUID orderShipment = UUID.randomUUID();
                UUID deliveredShipment = UUID.randomUUID();
                UUID terms = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                long adminVersion = version(db, "SYSTEM_ADMIN");
                long managerVersion = version(db, "WAREHOUSE_MANAGER");
                long staffVersion = version(db, "WAREHOUSE_STAFF");
                long coordinatorVersion = version(db, "ORDER_COORDINATOR");
                long accountantVersion = version(db, "ACCOUNTANT");

                // The warehouse, the carrier, the customer and the people are not what this test is about: foreign
                // keys and row triggers are off while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                // The customer is real: the receivable billed to the carrier names it (a live foreign key).
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, created_at) VALUES (?, "
                        + "'Old Customer', 'old@example.com', 'ACTIVE', NOW())", customer);
                // A deposit order whose carrier collects the balance: one shipment with the carrier (COD 700), one
                // delivered whose delivery payment has not billed yet (COD 200).
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, request_id, customer_id, status, "
                        + "total_amount, currency, placed_at, created_at, payment_term, deposit_required, "
                        + "deposit_received_at, paid_amount, balance_policy, cod_amount, delivery_mode, shipped_at) "
                        + "VALUES (?, 'SO-OLD-1', gen_random_uuid(), ?, 'SHIPPED', 1500, 'VND', NOW(), NOW(), 'DEPOSIT', "
                        + "300, NOW(), 300, 'ON_DELIVERY', 0, 'PARCEL', NOW())", order, customer);
                RawSql.update(db, "INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, "
                        + "cod_amount, delivery_mode) VALUES (?, ?, 'HANDED_OVER', 1000, NOW(), 700, 'PARCEL')",
                        orderShipment, order);
                RawSql.update(db, "INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, "
                        + "outcome_at, cod_amount, delivery_mode) VALUES (?, ?, 'DELIVERED', 500, NOW(), NOW(), 200, "
                        + "'PARCEL')", deliveredShipment, order);
                RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                        + "publication_date) VALUES (gen_random_uuid(), 'com.stockflow.payment.internal.service."
                        + "OrderDeliveredListener.on(com.stockflow.contracts.OrderDelivered)', 'com.stockflow.contracts."
                        + "OrderDelivered', '{\"orderId\":\"" + order + "\",\"orderNumber\":\"SO-OLD-1\","
                        + "\"customerId\":\"" + customer + "\",\"paymentTerm\":\"DEPOSIT\",\"orderTotal\":1500,"
                        + "\"paidAmount\":300,\"currency\":\"VND\",\"creditTermDays\":null,"
                        + "\"deliveredAt\":\"2026-11-07T03:00:00Z\",\"shipmentId\":\"" + deliveredShipment + "\","
                        + "\"billedAmount\":200,\"balancePolicy\":\"ON_DELIVERY\"}', NOW())");
                RawSql.update(db, "INSERT INTO ordering.order_delivery_mode_change (id, order_id, delivery_mode, "
                        + "previous_mode, order_status, changed_at) VALUES (gen_random_uuid(), ?, 'PARCEL', "
                        + "'FULL_TRUCKLOAD', 'CONFIRMED', NOW())", order);
                RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, approved_by, approved_at, "
                        + "allow_prepaid, allow_deposit, deposit_percent, deposit_balance_policy) VALUES (?, "
                        + "gen_random_uuid(), ?, NOW(), TRUE, TRUE, 30, 'ON_DELIVERY')", terms, user);
                // A shipment waiting with a carrier chosen in advance, on the live parcel load with one parcel on board.
                RawSql.update(db, "INSERT INTO fulfillment.shipment (id, order_id, status, created_at, code, warehouse_id, "
                        + "ready_at, delivery_mode, package_count, cod_amount, label_cod_amount, label_printed_at, "
                        + "carrier_id) VALUES (?, ?, 'READY_TO_DISPATCH', NOW(), 'SO-OLD-1-S1', ?, NOW(), 'PARCEL', 1, "
                        + "700, 700, NOW(), ?)", waiting, order, warehouse, carrier);
                RawSql.update(db, "INSERT INTO fulfillment.pack (id, pick_id, status, created_at, started_at, "
                        + "packed_at) VALUES (?, gen_random_uuid(), 'PACKED', NOW(), NOW(), NOW())", pack);
                RawSql.update(db, "INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, "
                        + "shipment_id, sealed_at, weight_kg, length_cm, width_cm, height_cm) VALUES (?, ?, 1, "
                        + "'PKG-OLD-1', 'SEALED', ?, NOW(), 5, 30, 30, 30)", parcel, pack, waiting);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, "
                        + "mode, carrier_id, loading_started_at, shipment_count, package_count, cod_total) VALUES (?, "
                        + "'LD-OLD-1', gen_random_uuid(), ?, 'LOADING', 'PARCEL', ?, NOW(), 1, 1, 700)", liveLoad,
                        warehouse, carrier);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, "
                        + "added_by, mode, label_cod_amount) VALUES (gen_random_uuid(), ?, ?, NOW(), ?, 'PARCEL', 700)",
                        liveLoad, waiting, user);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, "
                        + "scan_id, loaded_at, loaded_by) VALUES (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), NOW(), "
                        + "?)", liveLoad, parcel, waiting, user);
                // A parcel load that left long ago, with its shipment: history.
                RawSql.update(db, "INSERT INTO fulfillment.shipment (id, order_id, status, created_at, code, warehouse_id, "
                        + "ready_at, delivery_mode, package_count, cod_amount, label_cod_amount, carrier_id, load_id, "
                        + "handed_over_at, handed_over_by, outcome_at) VALUES (?, ?, 'DELIVERED', NOW(), 'SO-OLD-1-S0', "
                        + "?, NOW(), 'PARCEL', 1, 0, 0, ?, ?, NOW(), ?, NOW())", leftLongAgo, order, warehouse, carrier,
                        oldLoad, user);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, "
                        + "mode, carrier_id, loading_started_at, dispatched_at, dispatched_by, carrier_representative, "
                        + "vehicle_plate, shipment_count, package_count) VALUES (?, 'LD-OLD-0', gen_random_uuid(), ?, "
                        + "'DISPATCHED', 'PARCEL', ?, NOW(), NOW(), ?, 'Driver', '51C-00000', 1, 1)", oldLoad,
                        warehouse, carrier, user);
                // A load plan's vehicle, PLANNED.
                RawSql.update(db, "INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, "
                        + "calculated_at, confirmed_at, confirmed_by) VALUES (?, 'LP-OLD-1', gen_random_uuid(), ?, "
                        + "'CONFIRMED', NOW(), NOW(), ?)", plan, warehouse, user);
                RawSql.update(db, "INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, vehicle_type_id, "
                        + "vehicle_code, vehicle_kind, inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg) "
                        + "VALUES (?, ?, 1, gen_random_uuid(), 'TRUCK-OLD', 'TRUCK', 2000, 1000, 1000, 1000)", vehicle,
                        plan);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, "
                        + "mode, load_plan_vehicle_id) VALUES (?, 'LD-OLD-2', gen_random_uuid(), ?, 'PLANNED', "
                        + "'FULL_TRUCKLOAD', ?)", planLoad, warehouse, vehicle);
                // The customer's message on the handover, still waiting to be delivered, as Jackson wrote it.
                String shipmentEvent = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"shipmentCode\":\"SO-OLD-1-S0\","
                        + "\"warehouseId\":\"" + warehouse + "\",\"deliveryMode\":\"PARCEL\",\"previousStatus\":"
                        + "\"READY_TO_DISPATCH\",\"status\":\"HANDED_OVER\",\"packageCount\":1}";
                RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                        + "publication_date) VALUES (?, 'com.stockflow.notification.SomeListener.on(com.stockflow."
                        + "contracts.ShipmentStatusChanged)', 'com.stockflow.contracts.ShipmentStatusChanged', ?, NOW())",
                        event, shipmentEvent);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The live parcel load is called off and empty; its shipment waits, with no carrier, for a load plan.
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.dispatch_load WHERE id = ?", liveLoad))
                        .isEqualTo("CANCELLED");
                assertThat(RawSql.number(db, "SELECT shipment_count + package_count FROM fulfillment.dispatch_load "
                        + "WHERE id = ?", liveLoad)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.dispatch_load_shipment WHERE load_id = ? "
                        + "AND active", liveLoad)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.dispatch_load_package WHERE load_id = ? "
                        + "AND active", liveLoad)).isZero();
                assertThat(RawSql.text(db, "SELECT status || ':' || coalesce(carrier_id::text, 'none') FROM "
                        + "fulfillment.shipment WHERE id = ?", waiting)).isEqualTo("READY_TO_DISPATCH:none");
                // History and the plan's vehicle are as they were.
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.dispatch_load WHERE id = ?", oldLoad))
                        .isEqualTo("DISPATCHED");
                assertThat(RawSql.text(db, "SELECT carrier_id::text FROM fulfillment.shipment WHERE id = ?",
                        leftLongAgo)).isEqualTo(carrier.toString());
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.dispatch_load WHERE id = ?", planLoad))
                        .isEqualTo("PLANNED");
                // The COD the carrier still owes is billed to it, due when it was (or is) collected; the delivery to come
                // finds the receivable and bills the customer nothing.
                assertThat(RawSql.texts(db, "SELECT collector || ':' || kind || ':' || amount::bigint || ':' || "
                        + "due_date || ':' || status FROM payment.receivable WHERE order_id = ? AND shipment_id = ?",
                        order, orderShipment)).containsExactly("CARRIER:DEPOSIT_BALANCE:700:"
                        + RawSql.text(db, "SELECT CURRENT_DATE::text") + ":OPEN");
                assertThat(RawSql.texts(db, "SELECT collector || ':' || amount::bigint || ':' || due_date FROM "
                        + "payment.receivable WHERE order_id = ? AND shipment_id = ?", order, deliveredShipment))
                        .containsExactly("CARRIER:200:2026-11-07");
                assertThat(RawSql.number(db, "SELECT count(*) FROM payment.receivable WHERE collector = 'CUSTOMER'"))
                        .isZero();
                // Nothing is collected on delivery any more.
                assertThat(RawSql.text(db, "SELECT balance_policy FROM ordering.customer_order WHERE id = ?", order))
                        .isEqualTo("BEFORE_DELIVERY");
                assertThat(RawSql.text(db, "SELECT deposit_balance_policy FROM customer.credit_profile WHERE id = ?",
                        terms)).isEqualTo("BEFORE_DELIVERY");
                // The waiting event lost its deliveryMode and nothing else of its text (the registry finds a
                // publication by that exact text); what it was is archived.
                assertThat(RawSql.text(db, "SELECT serialized_event FROM event_publication WHERE id = ?", event))
                        .isEqualTo(shipmentEvent.replace(",\"deliveryMode\":\"PARCEL\"", ""));
                assertThat(RawSql.text(db, "SELECT row_data ->> 'serialized_event' FROM platform.legacy_archive WHERE "
                        + "source_table = 'public.event_publication' AND row_id = ?", event)).isEqualTo(shipmentEvent);

                // Archived before it went.
                assertThat(RawSql.text(db, "SELECT row_data ->> 'delivery_mode' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.customer_order' AND row_id = ?", order)).isEqualTo("PARCEL");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'balance_policy' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.customer_order' AND row_id = ?", order)).isEqualTo("ON_DELIVERY");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'cod_amount' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.order_shipment' AND row_id = ?", orderShipment))
                        .isEqualTo("700.0000");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'delivery_mode') || ':' || (row_data ->> 'carrier_id') "
                        + "FROM platform.legacy_archive WHERE source_table = 'fulfillment.shipment' AND row_id = ?",
                        waiting)).isEqualTo("PARCEL:" + carrier);
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'mode') || ':' || (row_data ->> 'status') FROM "
                        + "platform.legacy_archive WHERE source_table = 'fulfillment.dispatch_load' AND row_id = ?",
                        liveLoad)).isEqualTo("PARCEL:LOADING");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table IN "
                        + "('ordering.order_delivery_mode_change', 'fulfillment.dispatch_load_package', "
                        + "'customer.credit_profile.deposit_balance_policy')")).isEqualTo(3);
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'fulfillment.dispatch_load_shipment'")).isEqualTo(1);

                // What parcel shipping, the delivery mode and COD kept in the schema is gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE (table_schema, "
                        + "table_name) IN (('fulfillment', 'shipment'), ('fulfillment', 'dispatch_load'), "
                        + "('fulfillment', 'dispatch_load_shipment'), ('ordering', 'customer_order'), "
                        + "('ordering', 'order_shipment')) AND column_name IN ('delivery_mode', 'cod_amount', "
                        + "'label_cod_amount', 'label_printed_at', 'mode', 'cod_total')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'ordering' AND table_name = 'order_delivery_mode_change'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE prosrc ~ "
                        + "'delivery_mode|label_cod_amount|cod_total'")).isZero();
                RawSql.refused(db, "ck_order_balance_policy", "UPDATE ordering.customer_order SET balance_policy = "
                        + "'ON_DELIVERY' WHERE id = ?", order);
                RawSql.refused(db, "ck_credit_profile_balance_policy", "UPDATE customer.credit_profile SET "
                        + "deposit_balance_policy = 'ON_DELIVERY' WHERE id = ?", terms);
                RawSql.refused(db, "ck_dispatch_load_vehicle", "INSERT INTO fulfillment.dispatch_load (id, code, "
                        + "request_id, warehouse_id, status) VALUES (gen_random_uuid(), 'LD-NEW-1', gen_random_uuid(), "
                        + "?, 'PLANNED')", warehouse);

                // Every role that held a removed permission moves its version; one that held none does not.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE "
                        + "'fulfillment-load-cancellations:%' OR code LIKE 'sales-order-delivery-modes:%' OR code = "
                        + "'fulfillment-dispatch:CREATE'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code = "
                        + "'fulfillment-dispatch:UPDATE'")).isEqualTo(1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(adminVersion + 1);
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(managerVersion + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffVersion + 1);
                assertThat(version(db, "ORDER_COORDINATOR")).isEqualTo(coordinatorVersion + 1);
                assertThat(version(db, "ACCOUNTANT")).isEqualTo(accountantVersion);
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
