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
 * stock row each (kltn-docs 20 BR-14, BR-18). Written as rows, for a test whose subject is not receiving: the way
 * a receipt makes them is {@code ReceivingPackagesIntegrationTest}'s. The SKU's inventory item must exist.
 */
public final class CartonRows {

    /** One carton in stock and the stock row inside it. */
    public record Carton(UUID id, String lpn, UUID stockItemId) {
    }

    private CartonRows() {
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

    /** {@code count} AVAILABLE cartons of {@code factor} units at {@code location}, received at {@code receivedAt}. */
    public static List<Carton> stock(JdbcTemplate jdbc, String sku, String location, int count, int factor,
                                     String lotNumber, Instant receivedAt) {
        return stock(jdbc, sku, location, count, factor, lotNumber, receivedAt, "AVAILABLE");
    }

    /** The same, with the stock status of the rows. */
    public static List<Carton> stock(JdbcTemplate jdbc, String sku, String location, int count, int factor,
                                     String lotNumber, Instant receivedAt, String status) {
        UUID unit = unit(jdbc, sku, factor);
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
                    row, sku, location, lotNumber, perCarton, status, Timestamp.from(receivedAt), id);
            made.add(new Carton(id, lpn, row));
        }
        return made;
    }
}
