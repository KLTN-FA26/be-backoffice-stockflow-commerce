package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stock as the warehouse keeps it since team plan 4C-2: whole, sealed LPN cartons of the SKU's one carton unit, one
 * stock row each (kltn-docs 20 BR-14, BR-18), always with a lot (BR-22: a missing lot is 'UNTRACED', team plan 4C-3).
 * Written as rows, for a test whose subject is not receiving: the way
 * a receipt makes them is {@code ReceivingPackagesIntegrationTest}'s. The SKU's inventory item must exist.
 */
public final class CartonRows {

    /** One carton in stock and the stock row inside it. */
    public record Carton(UUID id, String lpn, UUID stockItemId) {
    }

    /**
     * The lot of a carton a test gives none: every unit has one since team plan 5-5 (kltn-docs 01 BR-17), and the
     * database refuses live stock without it ({@code inventory.enforce_sku_policy}). It is the lot 4C-1 gave the demo
     * stock that had none, so a test's cartons and the demo's are one lot, as the lot-less stock of both once was.
     */
    public static final String DEFAULT_LOT = "UNTRACED";

    private CartonRows() {
    }

    /**
     * Moves every carton standing at a location whose code starts with {@code codePrefix} to {@code to}, rows and all,
     * by SQL - what a warehouse manager does before taking a place off the layout (kltn-docs 06 BR-04), for a test whose
     * subject is the layout rather than the move. {@code to} should be an area: no bin-fit check applies there.
     *
     * @return how many cartons moved
     */
    public static int moveOut(JdbcTemplate jdbc, String codePrefix, String to) {
        jdbc.update("UPDATE inventory.stock_item SET location_code = ? WHERE location_code LIKE ? || '%'"
                + " AND stock_package_id IS NOT NULL", to, codePrefix);
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
                 WHERE i.sku = ? AND NOT u.is_base AND u.active ORDER BY u.default_shipping DESC, u.created_at, u.id""",
                UUID.class, sku);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                SELECT ?, id, 'CTN', ? FROM inventory.inventory_items WHERE sku = ?""", id, factor, sku);
        return id;
    }

    /**
     * The SKU's carton unit of exactly {@code factor} units (code {@code CTN<factor>}), made the first time: for a test
     * that needs cartons of several sizes of one SKU - stock that was loose rows of any size before
     * {@code ck_stock_item_whole_cartons} (V20261117000100). Make these before calling {@link #unit}, which takes
     * whichever carton unit the SKU has first.
     */
    public static UUID unitOf(JdbcTemplate jdbc, String sku, int factor) {
        List<UUID> found = jdbc.queryForList("""
                SELECT u.id FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
                 WHERE i.sku = ? AND NOT u.is_base AND u.active AND u.factor = ? ORDER BY u.created_at, u.id""",
                UUID.class, sku, factor);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                SELECT ?, id, ?, ? FROM inventory.inventory_items WHERE sku = ?""", id, "CTN" + factor, factor, sku);
        return id;
    }

    /**
     * One carton of exactly {@code units} units of the SKU at {@code location} (its {@link #unitOf} unit), in
     * {@code status}; a null {@code lotNumber} is {@link #DEFAULT_LOT}. Call inside a transaction: the carton and its
     * row are checked together at commit.
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

    /** The same, with the stock status of the rows. */
    public static List<Carton> stock(JdbcTemplate jdbc, String sku, String location, int count, int factor,
                                     String lotNumber, Instant receivedAt, String status) {
        return stockOfUnit(jdbc, sku, unit(jdbc, sku, factor), location, count, lotNumber, receivedAt, status);
    }

    /** {@code count} cartons of exactly this carton unit of the SKU, however many carton units the SKU has. */
    public static List<Carton> stockOfUnit(JdbcTemplate jdbc, String sku, UUID unit, String location, int count,
                                           String lotNumber, Instant receivedAt, String status) {
        int perCarton = jdbc.queryForObject("SELECT factor FROM inventory.uom_conversions WHERE id = ?", Integer.class,
                unit);
        List<Carton> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = Identifiers.newId();
            String lpn = "T-" + id.toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
            jdbc.update("""
                    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                         received_qty, location_code, status)
                    SELECT ?, ?, inventory_item_id, id, factor, factor, ?, 'IN_STOCK'
                      FROM inventory.uom_conversions WHERE id = ?""", id, lpn, location, unit);
            UUID row = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved,
                                                      status, version, created_at, received_at, stock_package_id)
                    VALUES (?, ?, ?, ?, ?, 0, ?, 0, NOW(), ?, ?)""",
                    row, sku, location, lotNumber == null ? DEFAULT_LOT : lotNumber, perCarton, status, Timestamp.from(receivedAt), id);
            made.add(new Carton(id, lpn, row));
        }
        return made;
    }
}
