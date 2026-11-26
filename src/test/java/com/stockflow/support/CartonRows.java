package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stock as the warehouse keeps it: whole, sealed LPN cartons of the SKU's one carton unit (kltn-docs 20 BR-14,
 * BR-18), each one row of {@code inventory.stock_package} that is the stock itself (ADR-0011, V20261230000600: there
 * is no stock row beside it), always with a lot (BR-22: a missing lot is 'UNTRACED', team plan 4C-3).
 * Written as rows, for a test whose subject is not receiving: the way
 * a receipt makes them is {@code ReceivingPackagesIntegrationTest}'s. The SKU's inventory item must exist.
 */
public final class CartonRows {

    /** One carton in stock. */
    public record Carton(UUID id, String lpn) {
    }

    /**
     * The lot of a carton a test gives none: every unit has one since team plan 5-5 (kltn-docs 01 BR-17), and the
     * database refuses a carton in stock without it ({@code ck_stock_package_lot}). It is the lot 4C-1 gave the demo
     * stock that had none, so a test's cartons and the demo's are one lot, as the lot-less stock of both once was.
     */
    public static final String DEFAULT_LOT = "UNTRACED";

    private CartonRows() {
    }

    /**
     * Moves every carton standing at a location whose code starts with {@code codePrefix} to {@code to},
     * by SQL - what a warehouse manager does before taking a place off the layout (kltn-docs 06 BR-04), for a test whose
     * subject is the layout rather than the move. {@code to} should be an area: no bin-fit check applies there.
     *
     * @return how many cartons moved
     */
    public static int moveOut(JdbcTemplate jdbc, String codePrefix, String to) {
        return jdbc.update("UPDATE inventory.stock_package SET location_code = ? WHERE location_code LIKE ? || '%'",
                to, codePrefix);
    }

    /**
     * The SKU's carton unit of {@code factor} units, made the first time (code {@code CTN}); the existing one when the
     * SKU has a carton unit already, whatever its factor.
     */
    public static UUID unit(JdbcTemplate jdbc, String sku, int factor) {
        List<UUID> found = jdbc.queryForList("""
                SELECT u.id FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
                 WHERE i.sku = ? AND u.active ORDER BY u.created_at, u.id""",
                UUID.class, sku);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        return cartonUnit(jdbc, itemOf(jdbc, sku), "CTN", factor);
    }

    /**
     * The SKU's carton unit of exactly {@code factor} units (code {@code CTN<factor>}), made the SKU's ONE active carton
     * (01 BR-03): made the first time, made active again when it was retired, and the SKU's other active carton is
     * retired either way (its cartons stay, as history). For a test that needs cartons of several sizes of one SKU,
     * which therefore takes the sizes in turn: a carton made earlier stays, in a unit that is no longer the active one.
     * {@link #unit} takes whichever carton unit the SKU has active.
     */
    public static UUID unitOf(JdbcTemplate jdbc, String sku, int factor) {
        List<UUID> found = jdbc.queryForList("""
                SELECT u.id FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
                 WHERE i.sku = ? AND u.active AND u.factor = ? ORDER BY u.created_at, u.id""",
                UUID.class, sku, factor);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        return cartonUnit(jdbc, itemOf(jdbc, sku), "CTN" + factor, factor);
    }

    private static UUID itemOf(JdbcTemplate jdbc, String sku) {
        return jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
    }

    /**
     * Makes this unit its item's ONE active packing unit (01 BR-03, {@code uk_uom_conversions_one_carton}): the item's
     * other active packing unit is retired - its cartons stay, as history - and this one is activated if it was retired.
     */
    public static void reactivate(JdbcTemplate jdbc, UUID unit) {
        jdbc.update("""
                UPDATE inventory.uom_conversions SET active = FALSE
                 WHERE inventory_item_id = (SELECT inventory_item_id FROM inventory.uom_conversions WHERE id = ?)
                   AND active AND id <> ?""", unit, unit);
        jdbc.update("UPDATE inventory.uom_conversions SET active = TRUE WHERE id = ? AND NOT active", unit);
    }

    /**
     * Makes the item's unit {@code code} of {@code factor} units its ONE active carton, as {@link #reactivate} does,
     * made first when the item has none of that code. A unit of that code with another factor is a fixture error, not
     * a unit to take over: it fails loudly.
     */
    public static UUID cartonUnit(JdbcTemplate jdbc, UUID item, String code, int factor) {
        List<UUID> found = jdbc.queryForList("""
                SELECT id FROM inventory.uom_conversions
                 WHERE inventory_item_id = ? AND unit_of_measure = ? AND factor = ?""", UUID.class, item, code, factor);
        Integer sameCode = jdbc.queryForObject("""
                SELECT count(*) FROM inventory.uom_conversions WHERE inventory_item_id = ? AND unit_of_measure = ?""",
                Integer.class, item, code);
        if (found.isEmpty() && sameCode != null && sameCode > 0) {
            throw new IllegalStateException("The item already has a unit " + code + " of another factor than " + factor);
        }
        UUID id = found.isEmpty() ? Identifiers.newId() : found.getFirst();
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active)
                    VALUES (?, ?, ?, ?, FALSE)""", id, item, code, factor);
        }
        jdbc.update("""
                UPDATE inventory.uom_conversions SET active = FALSE
                 WHERE inventory_item_id = ? AND active AND id <> ?""", item, id);
        jdbc.update("UPDATE inventory.uom_conversions SET active = TRUE WHERE id = ? AND NOT active", id);
        return id;
    }

    /**
     * One carton of exactly {@code units} units of the SKU at {@code location} (its {@link #unitOf} unit), in
     * stock status {@code status}; a null {@code lotNumber} is {@link #DEFAULT_LOT}.
     */
    public static Carton carton(JdbcTemplate jdbc, String sku, String location, int units, String lotNumber,
                                Instant receivedAt, String status) {
        return stockOfUnit(jdbc, sku, unitOf(jdbc, sku, units), location, 1, lotNumber, receivedAt, status)
                .getFirst();
    }

    /**
     * {@code count} AVAILABLE cartons of {@code factor} units at {@code location}, received at {@code receivedAt}; a
     * null {@code lotNumber} is {@link #DEFAULT_LOT}.
     */
    public static List<Carton> stock(JdbcTemplate jdbc, String sku, String location, int count, int factor,
                                     String lotNumber, Instant receivedAt) {
        return stock(jdbc, sku, location, count, factor, lotNumber, receivedAt, "AVAILABLE");
    }

    /** The same, with the stock status of the cartons. */
    public static List<Carton> stock(JdbcTemplate jdbc, String sku, String location, int count, int factor,
                                     String lotNumber, Instant receivedAt, String status) {
        return stockOfUnit(jdbc, sku, unit(jdbc, sku, factor), location, count, lotNumber, receivedAt, status);
    }

    /**
     * {@code count} cartons of exactly this carton unit of the SKU, however many carton units the SKU has: IN_STOCK
     * in stock status {@code status}, of the lot {@link #lot} finds or opens. A null {@code receivedAt} is a carton
     * whose receipt time is not known (it sorts last in FIFO).
     */
    public static List<Carton> stockOfUnit(JdbcTemplate jdbc, String sku, UUID unit, String location, int count,
                                           String lotNumber, Instant receivedAt, String status) {
        UUID lot = lot(jdbc, sku, lotNumber == null ? DEFAULT_LOT : lotNumber, receivedAt);
        List<Carton> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = Identifiers.newId();
            String lpn = "T-" + id.toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
            jdbc.update("""
                    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                         counted_at, location_code, status, stock_status, lot_id,
                                                         received_at)
                    SELECT ?, ?, inventory_item_id, id, factor, NOW(), ?, 'IN_STOCK', ?, ?, ?
                      FROM inventory.uom_conversions WHERE id = ?""", id, lpn, location, status, lot,
                    receivedAt == null ? null : Timestamp.from(receivedAt), unit);
            made.add(new Carton(id, lpn));
        }
        return made;
    }

    /**
     * The lot of {@code sku} with this number and receipt time: the oldest there is, else a new UNTRACED one - what
     * the application does for a receipt with no origin ({@code StockLots.openUntraced}). A carton names its lot by
     * id; the lot number is the lot's.
     */
    public static UUID lot(JdbcTemplate jdbc, String sku, String lotNumber, Instant receivedAt) {
        Timestamp at = receivedAt == null ? null : Timestamp.from(receivedAt);
        List<UUID> found = jdbc.queryForList("""
                SELECT id FROM inventory.stock_lot
                 WHERE sku = ? AND lot_number = ? AND received_at IS NOT DISTINCT FROM ?::timestamptz
                 ORDER BY created_at, id LIMIT 1""", UUID.class, sku, lotNumber, at);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
                VALUES (?, ?, ?, ?, 'UNTRACED', NOW())""", id, sku, lotNumber, at);
        return id;
    }
}
