package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * V20261111000110 on the real upgrade path (kltn-docs 20 BR-14, BR-18, BR-22, §4.6, §5.6; 01 BR-03; 07 BR-03; open
 * question C45; team plan 4C-1, QD-5): stock written loose, in opened cartons, in cartons of another unit and in
 * cartons of no lot goes into whole sealed cartons of its SKU's carton unit. A hold of whole cartons gets a carton per
 * carton - a hold of several split into a hold per carton, each named by the order line and picked by its own open
 * pick line; the free units fill cartons oldest first, across stock rows; less than a carton left is written off by a
 * POSTED DATA_CORRECTION adjustment; a whole carton keeps its LPN (sealed again, relabelled lot UNTRACED); every emptied
 * row's units are on the ledger, boxed or written off. It stops, naming them, on stock it cannot box: a SKU with no
 * carton unit, a hold or a reservation that is not whole cartons, a document still to act on loose stock.
 */
class LooseStockToCartonsMigrationUpgradeTest {

    private static final String BEFORE = "20261108002400";
    private static final String MIGRATION = "20261111000110";
    private static final String BY = "V20261111000110";

    private static final String CUP = "UPG-CUP";
    private static final String LID = "UPG-LID";
    private static final String BAG = "UPG-BAG";
    private static final String SERIAL = "UPG-SER";
    private static final String BIN_A = "UPG-A01-1-A";
    private static final String BIN_B = "UPG-B01-1-A";
    private static final String QUARANTINE = "UPG-Q01";

    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000c1000";
    private static final String ORDER_1 = "00000000-0000-4000-8000-0000000c1001";
    private static final String ORDER_2 = "00000000-0000-4000-8000-0000000c1002";
    private static final String USER = "00000000-0000-4000-8000-0000000c1003";
    private static final String ORDER_LINE_2 = "00000000-0000-4000-8000-0000000c1004";
    private static final String PICK_2 = "00000000-0000-4000-8000-0000000c1005";
    // Lots: the cup's two lots of no number (received 10 and 5 days ago), L-7, L-8, L-9, the quarantined one, P3's, and
    // the cup's lot UNTRACED made before (reused, not made again).
    private static final String LOT_0 = "00000000-0000-4000-8000-0000000c1100";
    private static final String LOT_0B = "00000000-0000-4000-8000-0000000c1101";
    private static final String LOT_7 = "00000000-0000-4000-8000-0000000c1102";
    private static final String LOT_8 = "00000000-0000-4000-8000-0000000c1103";
    private static final String LOT_Q = "00000000-0000-4000-8000-0000000c1104";
    private static final String LOT_P3 = "00000000-0000-4000-8000-0000000c1105";
    private static final String LOT_9 = "00000000-0000-4000-8000-0000000c1106";
    private static final String LOT_UNTRACED = "00000000-0000-4000-8000-0000000c1107";
    // Stock rows.
    private static final String R1 = "00000000-0000-4000-8000-0000000c1201";
    private static final String R2 = "00000000-0000-4000-8000-0000000c1202";
    private static final String R3 = "00000000-0000-4000-8000-0000000c1203";
    private static final String R4 = "00000000-0000-4000-8000-0000000c1204";
    private static final String R5 = "00000000-0000-4000-8000-0000000c1205";
    private static final String R6 = "00000000-0000-4000-8000-0000000c1206";
    private static final String R7 = "00000000-0000-4000-8000-0000000c1207";
    private static final String R8 = "00000000-0000-4000-8000-0000000c1208";
    private static final String R9 = "00000000-0000-4000-8000-0000000c1209";
    private static final String R10 = "00000000-0000-4000-8000-0000000c1210";
    private static final String R11 = "00000000-0000-4000-8000-0000000c1211";
    private static final String R12 = "00000000-0000-4000-8000-0000000c1212";
    private static final String S1 = "00000000-0000-4000-8000-0000000c1213";
    private static final String S2 = "00000000-0000-4000-8000-0000000c1214";
    // Cartons: P1 opened and short, P2 opened and whole, P3 sealed and whole with no lot, P4 long consumed, P5 sealed
    // and full in a retired unit of 8.
    private static final String P1 = "00000000-0000-4000-8000-0000000c1301";
    private static final String P2 = "00000000-0000-4000-8000-0000000c1302";
    private static final String P3 = "00000000-0000-4000-8000-0000000c1303";
    private static final String P4 = "00000000-0000-4000-8000-0000000c1304";
    private static final String P5 = "00000000-0000-4000-8000-0000000c1305";
    // Holds: H1 one carton of loose r1 (picking), H2 the whole of P2, H5 two cartons of loose r10 (picking); H3, H4
    // and the reservation of 7 the ones it stops on.
    private static final String H1 = "00000000-0000-4000-8000-0000000c1401";
    private static final String H2 = "00000000-0000-4000-8000-0000000c1402";
    private static final String H3 = "00000000-0000-4000-8000-0000000c1403";
    private static final String H4 = "00000000-0000-4000-8000-0000000c1404";
    private static final String H5 = "00000000-0000-4000-8000-0000000c1405";
    private static final String RESERVED_7 = "00000000-0000-4000-8000-0000000c1406";
    private static final String PICK_LINE = "00000000-0000-4000-8000-0000000c1501";
    private static final String PICK_LINE_2 = "00000000-0000-4000-8000-0000000c1502";
    private static final String PENDING = "00000000-0000-4000-8000-0000000c1601";
    private static final String TASK = "00000000-0000-4000-8000-0000000c1701";
    private static final String COUNT = "00000000-0000-4000-8000-0000000c1801";

    @Test
    void looseAndOpenedStockGoesIntoWholeCartons() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                // It stops on what it cannot box, naming it, and changes nothing; fixed, it goes on to the next.
                stops(postgres, "no carton unit", BAG);
                unit(db, BAG, "BAG", 5, true, false);
                stops(postgres, "not a number of whole cartons", H3);
                release(db, H3, R2, 3);
                stops(postgres, "take part of a carton", H4);
                release(db, H4, R6, 4);
                stops(postgres, "reservations are not a number of whole cartons", RESERVED_7);
                RawSql.update(db, "UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = "
                        + "'MANUAL_OVERRIDE', closed_at = NOW() WHERE id = ?::uuid", RESERVED_7);
                stops(postgres, "await a decision", "ADJ-UPG-PENDING");
                RawSql.update(db, "UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() "
                        + "WHERE id = ?::uuid", PENDING);
                stops(postgres, "cycle counts still count loose stock", "CC-UPG-1");
                RawSql.update(db, "UPDATE inventory.cycle_count SET status = 'CANCELLED' WHERE id = ?::uuid", COUNT);
                stops(postgres, "putaway tasks move loose stock", TASK);
                RawSql.update(db, "UPDATE warehouse.putaway_task SET status = 'CANCELLED' WHERE id = ?::uuid", TASK);
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package WHERE created_by = ?", BY))
                        .as("a stop leaves nothing behind").isZero();

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);
                String today = RawSql.text(db,
                        "SELECT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 'YYYYMMDD')");

                // Nothing is loose; every carton in stock is one sealed carton of the SKU's carton count, of one lot.
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item WHERE on_hand > 0 "
                        + "AND stock_package_id IS NULL")).isZero();
                assertThat(RawSql.texts(db, "SELECT p.lpn FROM inventory.stock_package p JOIN inventory.stock_item s "
                        + "ON s.stock_package_id = p.id AND s.on_hand > 0 WHERE p.status = 'IN_STOCK' AND "
                        + "(NOT p.unopened OR s.on_hand <> p.nominal_qty OR p.received_qty <> p.nominal_qty OR "
                        + "s.lot_number IS NULL)")).isEmpty();

                // The bin of loose cups: 23 (10 held) + 9 = 32. The hold gets a carton of its own from r1, the 22 free
                // units fill two cartons oldest first (10 of r1; 3 of r1 and 7 of r2), the 2 left of r2 are written off.
                // Lot UNTRACED is the cup's one made before, not a new one; the carton keeps r1's receipt date.
                String heldCarton = RawSql.text(db, "SELECT s.stock_package_id::text FROM inventory.stock_reservation h "
                        + "JOIN inventory.stock_item s ON s.id = h.stock_item_id WHERE h.id = ?::uuid", H1);
                assertThat(RawSql.text(db, "SELECT s.on_hand || ':' || s.reserved || ':' || s.lot_number || ':' "
                        + "|| s.lot_id || ':' || (s.received_at = r.received_at) || ':' || s.status FROM "
                        + "inventory.stock_item s, inventory.stock_item r WHERE s.stock_package_id = ?::uuid AND r.id = "
                        + "?::uuid", heldCarton, R1)).isEqualTo("10:10:UNTRACED:" + LOT_UNTRACED + ":true:AVAILABLE");
                assertThat(RawSql.text(db, "SELECT l.lpn || ':' || l.whole_carton || ':' || l.allocated_qty FROM "
                        + "fulfillment.pick_line l WHERE l.id = ?::uuid", PICK_LINE)).isEqualTo(RawSql.text(db,
                        "SELECT lpn || ':true:10' FROM inventory.stock_package WHERE id = ?::uuid", heldCarton));
                assertThat(RawSql.texts(db, "SELECT string_agg(m.quantity || '@' || CASE m.lot_id WHEN s.lot_id THEN "
                        + "'its-lot' ELSE 'other' END, '+' ORDER BY m.quantity DESC) FROM inventory.stock_package p "
                        + "JOIN inventory.stock_item s ON s.stock_package_id = p.id JOIN inventory.stock_movement m ON "
                        + "m.reference_id = p.id WHERE p.location_code = ? AND p.created_by = ? AND s.sku = ? AND "
                        + "s.lot_number = 'UNTRACED' GROUP BY p.id ORDER BY 1", BIN_A, BY, CUP))
                        .as("each carton: one MOVE per stock row it was filled from, of the carton's lot")
                        .containsExactly("10@its-lot", "10@its-lot", "7@its-lot+3@its-lot");
                assertThat(RawSql.texts(db, "SELECT on_hand || ':' || reserved FROM inventory.stock_item "
                        + "WHERE id IN (?::uuid, ?::uuid) ORDER BY id", R1, R2)).containsExactly("0:0", "0:0");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_lot WHERE sku = ? AND lot_number = "
                        + "'UNTRACED'", CUP)).as("one lot UNTRACED per SKU").isEqualTo(1);
                assertThat(RawSql.text(db, "SELECT COALESCE(lot_number, '-') FROM inventory.stock_lot WHERE id = ?::uuid",
                        LOT_0)).as("a lot is never renamed").isEqualTo("-");

                // Two cartons held by order 2 as one hold of 20: a hold per carton, for the same order and line, each
                // picked by an open pick line of its own naming its carton.
                assertThat(RawSql.texts(db, "SELECT h.quantity || ':' || s.on_hand || ':' || s.reserved || ':' "
                        + "|| s.lot_id || ':' || l.allocated_qty || ':' || (l.lpn = p.lpn) || ':' || l.whole_carton "
                        + "|| ':' || l.pick_id || ':' || (h.id = ?::uuid) FROM inventory.stock_reservation h JOIN "
                        + "ordering.order_line_reservation olr ON olr.reservation_id = h.id AND olr.order_line_id = "
                        + "?::uuid JOIN inventory.stock_item s ON s.id = h.stock_item_id JOIN inventory.stock_package p "
                        + "ON p.id = s.stock_package_id JOIN fulfillment.pick_line l ON l.reservation_id = h.id AND "
                        + "l.status = 'OPEN' WHERE h.status = 'HELD' ORDER BY 1 DESC", H5, ORDER_LINE_2))
                        .containsExactly("10:10:10:" + LOT_9 + ":10:true:true:" + PICK_2 + ":true",
                                "10:10:10:" + LOT_9 + ":10:true:true:" + PICK_2 + ":false");
                assertThat(RawSql.number(db, "SELECT count(DISTINCT s.stock_package_id) FROM inventory.stock_reservation "
                        + "h JOIN inventory.stock_item s ON s.id = h.stock_item_id WHERE h.order_id = ?::uuid AND "
                        + "h.status = 'HELD' AND s.lot_number = 'L-9'", ORDER_2)).isEqualTo(2);
                assertThat(RawSql.text(db, "SELECT on_hand || ':' || reserved FROM inventory.stock_item WHERE id = "
                        + "?::uuid", R10)).isEqualTo("0:0");

                // The write-offs: POSTED DATA_CORRECTION adjustments, asked and approved by the disabled stand-ins,
                // numbered on from the day's sequence, each with its ADJUSTMENT ledger line from the row's place and lot.
                assertThat(RawSql.texts(db, "SELECT a.sku || ' ' || a.quantity_delta || ' at ' || a.location_code || "
                        + "' lot ' || COALESCE(a.lot_number, '-') || ' ' || a.status || ' ' || a.reason_code || ' ' "
                        + "|| (a.adjustment_number ~ ('^ADJ-' || ? || '-000[89]$')) || ' ' || q.username || '/' "
                        + "|| d.username || ' ledger ' || m.quantity || '@' || m.lot_id FROM inventory.stock_adjustment "
                        + "a JOIN identity.app_user q ON q.id = a.requested_by JOIN identity.app_user d ON d.id = "
                        + "a.decided_by JOIN inventory.stock_movement m ON m.reference_type = 'STOCK_ADJUSTMENT' AND "
                        + "m.reference_id = a.id AND m.movement_type = 'ADJUSTMENT' AND m.from_location_code = "
                        + "a.location_code WHERE a.created_by = ? ORDER BY a.location_code", today, BY))
                        .containsExactly(
                                CUP + " -2 at " + BIN_A + " lot - POSTED DATA_CORRECTION true "
                                        + "system.legacy-import/system.legacy-approval ledger 2@" + LOT_0B,
                                CUP + " -5 at " + QUARANTINE + " lot - POSTED DATA_CORRECTION true "
                                        + "system.legacy-import/system.legacy-approval ledger 5@" + LOT_Q);
                assertThat(RawSql.texts(db, "SELECT status FROM identity.app_user WHERE username IN "
                        + "('system.legacy-import', 'system.legacy-approval')")).containsOnly("DISABLED");

                // Every emptied row's units are on the ledger, boxed (its MOVE lines name it) or written off.
                for (String row : new String[]{R1, R2, R3, R4, R7, R8, R9, R10, R11, R12, S1, S2}) {
                    assertThat(RawSql.number(db, "SELECT (SELECT (row_data ->> 'on_hand')::int FROM "
                            + "platform.legacy_archive WHERE row_id = ?::uuid) - COALESCE((SELECT sum(quantity) FROM "
                            + "inventory.stock_movement WHERE movement_type = 'MOVE' AND reference_type = "
                            + "'STOCK_PACKAGE' AND reason LIKE '%' || ? || '%'), 0) - COALESCE((SELECT sum(-a.quantity_delta) "
                            + "FROM inventory.stock_adjustment a, platform.legacy_archive o WHERE o.row_id = ?::uuid AND "
                            + "a.created_by = ? AND a.location_code = o.row_data ->> 'location_code' AND a.sku = "
                            + "o.row_data ->> 'sku' AND a.lot_number IS NOT DISTINCT FROM o.row_data ->> 'lot_number' "
                            + "AND a.received_at IS NOT DISTINCT FROM (o.row_data ->> 'received_at')::timestamptz), 0)",
                            row, row, row, BY)).as("units of %s not on the ledger", row).isZero();
                }

                // The opened, short carton P1 (7 of L-7) and 3 loose of L-7 beside it: one new carton labelled with
                // P1's units (the older); P1 is emptied and closed. P5, sealed and full but in a retired unit of 8, and
                // 2 loose of L-8: one carton of the cup's 10; P5 closed.
                assertThat(RawSql.texts(db, "SELECT status || ':' || unopened || ':' || (closed_at IS NOT NULL) FROM "
                        + "inventory.stock_package WHERE id IN (?::uuid, ?::uuid)", P1, P5))
                        .containsOnly("CONSUMED:false:true");
                assertThat(RawSql.texts(db, "SELECT s.lot_number || ':' || s.on_hand || ':' || s.lot_id || ':' "
                        + "|| s.received_at::text || ':' || u.unit_of_measure || ':' || string_agg(m.quantity || '/' "
                        + "|| COALESCE(m.from_stock_package_id::text, 'loose'), ',' ORDER BY m.quantity DESC) FROM "
                        + "inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id JOIN "
                        + "inventory.uom_conversions u ON u.id = p.uom_conversion_id JOIN inventory.stock_movement m ON "
                        + "m.to_stock_package_id = p.id WHERE p.location_code = ? AND p.created_by = ? GROUP BY s.id, "
                        + "u.unit_of_measure ORDER BY 1", BIN_B, BY)).containsExactly(
                        "L-7:10:" + LOT_7 + ":" + receivedAt(db, R3) + ":CTN:7/" + P1 + ",3/loose",
                        "L-8:10:" + LOT_8 + ":" + receivedAt(db, R12) + ":CTN:8/" + P5 + ",2/loose");
                // P2 held whole by an order: kept, sealed again; P3 sealed with no lot: kept, relabelled UNTRACED in
                // place with a MOVE line into itself.
                assertThat(RawSql.text(db, "SELECT p.unopened || ':' || p.received_qty || ':' || s.reserved || ':' "
                        + "|| h.stock_item_id FROM inventory.stock_package p JOIN inventory.stock_item s ON "
                        + "s.stock_package_id = p.id JOIN inventory.stock_reservation h ON h.id = ?::uuid WHERE p.id = "
                        + "?::uuid", H2, P2)).isEqualTo("true:10:10:" + R5);
                assertThat(RawSql.text(db, "SELECT p.lpn || ':' || s.lot_number || ':' || s.lot_id FROM "
                        + "inventory.stock_package p JOIN inventory.stock_item s ON s.id = ?::uuid WHERE p.id = ?::uuid",
                        R6, P3)).isEqualTo("LPN-UPG-P3:UNTRACED:" + LOT_UNTRACED);
                assertThat(RawSql.texts(db, "SELECT movement_type || ':' || quantity || ':' || (from_stock_package_id "
                        + "= to_stock_package_id) || ':' || lot_number || ':' || (reason LIKE '%' || ? || '%') FROM "
                        + "inventory.stock_movement WHERE reference_id = ?::uuid", R6, P3))
                        .containsExactly("MOVE:10:true:UNTRACED:true");
                assertThat(RawSql.text(db, "SELECT status || ':' || COALESCE(last_modified_by, '-') FROM "
                        + "inventory.stock_package WHERE id = ?::uuid", P4)).isEqualTo("CONSUMED:-");
                // Quarantined stock stays quarantined, in a carton; the lid in two boxes of its one active packing unit
                // (the retired one is not a carton unit); the bag in its new unit, of a new lot UNTRACED; each serial
                // number in a carton of one, still its own.
                assertThat(RawSql.text(db, "SELECT count(*) || ':' || min(s.status) || ':' || min(s.lot_number) FROM "
                        + "inventory.stock_item s WHERE s.location_code = ? AND s.on_hand > 0", QUARANTINE))
                        .isEqualTo("1:QUARANTINE:UNTRACED");
                assertThat(RawSql.texts(db, "SELECT s.sku || ':' || u.unit_of_measure || ':' || s.on_hand || ':' "
                        + "|| s.lot_number || ':' || COALESCE(s.serial_number, '-') FROM inventory.stock_item s JOIN "
                        + "inventory.stock_package p ON p.id = s.stock_package_id JOIN inventory.uom_conversions u ON "
                        + "u.id = p.uom_conversion_id WHERE s.sku IN (?, ?, ?) AND s.on_hand > 0 ORDER BY 1", LID, BAG,
                        SERIAL)).containsExactly(BAG + ":BAG:5:UNTRACED:-", LID + ":BOX:4:LID-1:-",
                        LID + ":BOX:4:LID-1:-", SERIAL + ":ONE:1:UNTRACED:SN-1", SERIAL + ":ONE:1:UNTRACED:SN-2");
                assertThat(RawSql.texts(db, "SELECT sku || ':' || origin_type || ':' || COALESCE(goods_receipt_id::text, "
                        + "'-') FROM inventory.stock_lot WHERE created_by = ? ORDER BY sku", BY))
                        .as("the bag's and the serial SKU's lot UNTRACED, and the lid's LID-1 that had no lot row")
                        .containsExactly(BAG + ":UNTRACED:-", LID + ":UNTRACED:-", SERIAL + ":UNTRACED:-");

                // 13 cartons made, numbered on from the day's sequence (41 handed out before).
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package WHERE created_by = ? AND "
                        + "lpn ~ ('^LPN-' || ? || '-0000(4[2-9]|5[0-4])$')", BY, today)).isEqualTo(13);
                assertThat(RawSql.number(db, "SELECT last_value FROM platform.document_sequence WHERE document_type = "
                        + "'LPN' AND sequence_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date"))
                        .isEqualTo(54);

                // Everything it changed is archived as it was.
                assertThat(RawSql.numbersByText(db, "SELECT source_table, count(*) FROM platform.legacy_archive "
                        + "GROUP BY source_table")).containsEntry("inventory.stock_item", 13L)
                        .containsEntry("inventory.stock_package", 4L).containsEntry("inventory.stock_reservation", 2L)
                        .containsEntry("fulfillment.pick_line", 2L);
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'on_hand') || ':' || (row_data ->> 'reserved') FROM "
                        + "platform.legacy_archive WHERE row_id = ?::uuid", R1)).isEqualTo("23:10");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'quantity' FROM platform.legacy_archive WHERE row_id = "
                        + "?::uuid", H5)).isEqualTo("20");

                // The guards are on again; the ledger lets a MOVE stay in place only to box units into its carton; a
                // lot is allowed on a SKU that is not lot-tracked.
                assertThat(RawSql.texts(db, "SELECT tgenabled::text FROM pg_trigger WHERE tgname IN "
                        + "('trg_stock_sku_policy', 'tg_stock_package_transition')")).containsOnly("O");
                RawSql.refused(db, "a MOVE in place for a move task", "INSERT INTO inventory.stock_movement (id, "
                        + "movement_type, sku, from_location_code, to_location_code, quantity, reference_type, "
                        + "reference_id, occurred_at) VALUES (gen_random_uuid(), 'MOVE', ?, ?, ?, 1, 'MOVE_TASK', "
                        + "gen_random_uuid(), NOW())", CUP, BIN_A, BIN_A);
                RawSql.refused(db, "a MOVE in place boxing into no carton", "INSERT INTO inventory.stock_movement (id, "
                        + "movement_type, sku, from_location_code, to_location_code, quantity, reference_type, "
                        + "reference_id, occurred_at) VALUES (gen_random_uuid(), 'MOVE', ?, ?, ?, 1, 'STOCK_PACKAGE', "
                        + "?::uuid, NOW())", CUP, BIN_A, BIN_A, P3);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, "
                        + "reserved, status, created_at, received_at) VALUES (gen_random_uuid(), ?, ?, 'L-NEW', 1, 0, "
                        + "'AVAILABLE', NOW(), NOW())", CUP, BIN_A);
            }
        }
    }

    /** The migration fails naming {@code what}, and leaves the database as it was. */
    private static void stops(PostgreSQLContainer<?> postgres, String because, String what) {
        FlywayException failure = catchThrowableOfType(FlywayException.class,
                () -> flyway(postgres, MIGRATION).migrate());
        assertThat(failure).as("V20261111000110 stops: %s", because).isNotNull();
        assertThat(failure.getMessage()).contains(because).contains(what);
    }

    private static void release(Connection db, String hold, String row, int quantity) throws SQLException {
        RawSql.update(db, "UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = "
                + "'MANUAL_OVERRIDE', closed_at = NOW() WHERE id = ?::uuid", hold);
        RawSql.update(db, "UPDATE inventory.stock_item SET reserved = reserved - ? WHERE id = ?::uuid", quantity, row);
    }

    private static String receivedAt(Connection db, String row) throws SQLException {
        return RawSql.text(db, "SELECT received_at::text FROM inventory.stock_item WHERE id = ?::uuid", row);
    }

    private static void seed(Connection db) throws SQLException {
        // The warehouse, the orders, the pick and the people are not what this is about: foreign keys and row triggers
        // are off while the old state is written. The SKUs and places are real rows, which the new cartons name.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, "
                + "is_pickable, is_putaway_target, status) VALUES (gen_random_uuid(), ?::uuid, 'BIN', ?, 'NORMAL', TRUE, "
                + "TRUE, 'ACTIVE'), (gen_random_uuid(), ?::uuid, 'BIN', ?, 'NORMAL', TRUE, TRUE, 'ACTIVE'), "
                + "(gen_random_uuid(), ?::uuid, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')", WAREHOUSE, BIN_A,
                WAREHOUSE, BIN_B, WAREHOUSE, QUARANTINE);
        for (String sku : new String[]{CUP, LID, BAG, SERIAL}) {
            RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, policy_configured, lot_tracked, "
                    + "removal_strategy) VALUES (gen_random_uuid(), ?, TRUE, FALSE, 'FEFO')", sku);
            RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                    + "is_base, active, version, created_at) SELECT gen_random_uuid(), id, 'EACH', 1, TRUE, TRUE, 0, "
                    + "NOW() FROM inventory.inventory_items WHERE sku = ?", sku);
        }
        unit(db, CUP, "CTN", 10, true, true);
        unit(db, CUP, "OLD8", 8, false, false);
        unit(db, LID, "BOX", 4, true, false);
        unit(db, LID, "OLD", 8, false, false);
        unit(db, SERIAL, "ONE", 1, true, false);
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at) VALUES (?::uuid, 'SO-UPG-C1-1', gen_random_uuid(), "
                + "gen_random_uuid(), 'DRAFT', 1, 'VND', NOW(), NOW()), (?::uuid, 'SO-UPG-C1-2', gen_random_uuid(), "
                + "gen_random_uuid(), 'DRAFT', 1, 'VND', NOW(), NOW())", ORDER_1, ORDER_2);
        RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) VALUES "
                + "(?::uuid, ?::uuid, ?, 20, 1000, 'VND')", ORDER_LINE_2, ORDER_2, CUP);
        RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, status, created_at, order_number, warehouse_id) "
                + "VALUES (?::uuid, ?::uuid, 'PICKING', NOW(), 'SO-UPG-C1-2', ?::uuid)", PICK_2, ORDER_2, WAREHOUSE);

        lot(db, LOT_0, CUP, null, "10 days");
        lot(db, LOT_0B, CUP, null, "5 days");
        lot(db, LOT_7, CUP, "L-7", "20 days");
        lot(db, LOT_8, CUP, "L-8", "15 days");
        lot(db, LOT_9, CUP, "L-9", "12 days");
        lot(db, LOT_Q, CUP, null, "30 days");
        lot(db, LOT_P3, CUP, null, "40 days");
        lot(db, LOT_UNTRACED, CUP, "UNTRACED", "50 days");
        // Loose cups in bin A: r1 23 (10 held for order 1, picking), r2 9 (3 held for order 1, a part of a carton),
        // r10 20 of L-9 held whole for order 2's line, picked by one open line.
        row(db, R1, CUP, BIN_A, null, LOT_0, "AVAILABLE", 23, 10, null);
        row(db, R2, CUP, BIN_A, null, LOT_0B, "AVAILABLE", 9, 3, null);
        hold(db, H1, R1, ORDER_1, 10);
        hold(db, H3, R2, ORDER_1, 3);
        RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, "
                + "location_code, allocated_qty, status) VALUES (?::uuid, gen_random_uuid(), gen_random_uuid(), ?::uuid, "
                + "?, ?, 10, 'OPEN')", PICK_LINE, H1, CUP, BIN_A);
        row(db, R10, CUP, BIN_A, "L-9", LOT_9, "AVAILABLE", 20, 20, null);
        hold(db, H5, R10, ORDER_2, 20);
        RawSql.update(db, "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES "
                + "(?::uuid, ?::uuid)", ORDER_LINE_2, H5);
        RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, "
                + "location_code, lot_number, allocated_qty, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, "
                + "'L-9', 20, 'OPEN')", PICK_LINE_2, PICK_2, ORDER_LINE_2, H5, CUP, BIN_A);
        // Bin B: P1 opened with 7 of L-7 left, 3 loose of L-7 beside it; P2 opened, still whole, held whole; P5 sealed
        // with 8 of L-8 in the retired unit of 8, 2 loose of L-8 beside it.
        carton(db, P1, "LPN-UPG-P1", CUP, "CTN", BIN_B, "IN_STOCK", false);
        row(db, R3, CUP, BIN_B, "L-7", LOT_7, "AVAILABLE", 7, 0, P1);
        row(db, R4, CUP, BIN_B, "L-7", LOT_7, "AVAILABLE", 3, 0, null);
        RawSql.update(db, "UPDATE inventory.stock_item SET received_at = NOW() - interval '2 days' WHERE id = ?::uuid",
                R4);
        carton(db, P2, "LPN-UPG-P2", CUP, "CTN", BIN_B, "IN_STOCK", false);
        row(db, R5, CUP, BIN_B, "L-8", LOT_8, "AVAILABLE", 10, 10, P2);
        hold(db, H2, R5, ORDER_2, 10);
        carton(db, P5, "LPN-UPG-P5", CUP, "OLD8", BIN_B, "IN_STOCK", true);
        row(db, R12, CUP, BIN_B, "L-8", LOT_8, "AVAILABLE", 8, 0, P5);
        row(db, R11, CUP, BIN_B, "L-8", LOT_8, "AVAILABLE", 2, 0, null);
        RawSql.update(db, "UPDATE inventory.stock_item SET received_at = NOW() - interval '3 days' WHERE id = ?::uuid",
                R11);
        // Bin A: P3 sealed and whole, of no lot, 4 of it held by order 2; P4 consumed long ago.
        carton(db, P3, "LPN-UPG-P3", CUP, "CTN", BIN_A, "IN_STOCK", true);
        row(db, R6, CUP, BIN_A, null, LOT_P3, "AVAILABLE", 10, 4, P3);
        hold(db, H4, R6, ORDER_2, 4);
        carton(db, P4, "LPN-UPG-P4", CUP, "CTN", BIN_A, "CONSUMED", false);
        // 15 quarantined, loose; lids loose with a lot but no lot row; bags with no carton unit; two serial numbers.
        row(db, R7, CUP, QUARANTINE, null, LOT_Q, "QUARANTINE", 15, 0, null);
        row(db, R8, LID, BIN_A, "LID-1", null, "AVAILABLE", 8, 0, null);
        row(db, R9, BAG, BIN_A, null, null, "AVAILABLE", 5, 0, null);
        row(db, S1, SERIAL, BIN_A, null, null, "AVAILABLE", 1, 0, null);
        row(db, S2, SERIAL, BIN_A, null, null, "AVAILABLE", 1, 0, null);
        RawSql.update(db, "UPDATE inventory.stock_item SET serial_number = CASE id WHEN ?::uuid THEN 'SN-1' ELSE "
                + "'SN-2' END WHERE id IN (?::uuid, ?::uuid)", S1, S1, S2);
        // 7 cups reserved for order 1, not a number of cartons.
        RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, "
                + "reserved_at, status, sku) VALUES (?::uuid, ?::uuid, gen_random_uuid(), gen_random_uuid(), 7, NOW(), "
                + "'HELD', ?)", RESERVED_7, ORDER_1, CUP);
        // A write-off of loose cups awaiting its approval; a count of a loose slot planned; a putaway task of a loose
        // remainder still open.
        RawSql.update(db, "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                + "quantity_delta, reason_code, requested_by) VALUES (?::uuid, 'ADJ-UPG-PENDING', ?, ?, -1, 'DAMAGED', "
                + "?::uuid)", PENDING, BIN_A, CUP, USER);
        RawSql.update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status) "
                + "VALUES (?::uuid, 'CC-UPG-1', ?::uuid, 'MANUAL', 'PLANNED')", COUNT, WAREHOUSE);
        RawSql.update(db, "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty) "
                + "VALUES (gen_random_uuid(), ?::uuid, ?, ?, 23)", COUNT, BIN_A, CUP);
        RawSql.update(db, "INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, "
                + "quantity, status, created_at) VALUES (?::uuid, gen_random_uuid(), gen_random_uuid(), ?, 4, 'PENDING', "
                + "NOW())", TASK, CUP);
        // 41 LPNs and 7 adjustment numbers handed out today already.
        RawSql.update(db, "INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) VALUES "
                + "('LPN', (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 41), "
                + "('ADJ', (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 7)");
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    private static void unit(Connection db, String sku, String name, int factor, boolean active, boolean shipping)
            throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, active, default_shipping, version, created_at) SELECT gen_random_uuid(), id, ?, ?, FALSE, ?, "
                + "?, 0, NOW() FROM inventory.inventory_items WHERE sku = ?", name, factor, active, shipping, sku);
    }

    private static void lot(Connection db, String id, String sku, String number, String age) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at) "
                + "VALUES (?::uuid, ?, ?, NOW() - ?::interval, 'UNTRACED', NOW())", id, sku, number, age);
    }

    /** A stock row received with its lot (or a day ago when it names none). */
    private static void row(Connection db, String id, String sku, String location, String lot, String lotId,
                            String status, int onHand, int reserved, String carton) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, lot_id, on_hand, "
                + "reserved, status, created_at, received_at, stock_package_id) VALUES (?::uuid, ?, ?, ?, ?::uuid, ?, ?, "
                + "?, NOW(), COALESCE((SELECT received_at FROM inventory.stock_lot WHERE id = ?::uuid), NOW() - "
                + "interval '1 day'), ?::uuid)", id, sku, location, lot, lotId, onHand, reserved, status, lotId, carton);
    }

    private static void hold(Connection db, String id, String row, String order, int quantity) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, "
                + "request_id, quantity, reserved_at, status, sku) SELECT ?::uuid, ?::uuid, ?::uuid, gen_random_uuid(), "
                + "gen_random_uuid(), ?, NOW(), 'HELD', sku FROM inventory.stock_item WHERE id = ?::uuid", id, row,
                order, quantity, row);
    }

    private static void carton(Connection db, String id, String lpn, String sku, String unit, String location,
                               String status, boolean unopened) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, unopened, closed_at, created_at) SELECT ?::uuid, ?, "
                + "i.id, u.id, u.factor, u.factor, ?, ?, ?, CASE ? WHEN 'CONSUMED' THEN NOW() END, NOW() FROM "
                + "inventory.inventory_items i JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND "
                + "u.unit_of_measure = ? WHERE i.sku = ?", id, lpn, location, status, unopened, status, unit, sku);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
