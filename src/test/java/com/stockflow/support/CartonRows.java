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
 *
 * <p>A carton is its catalogue variant's (owner decision Q1, V20261231000100): every helper finds or makes the carton
 * on the variant of the SKU's item ({@code inventory_items.variant_id}), so a customer's SKU and its variant share it,
 * and a carton made "for" a customer's SKU is its variant's.</p>
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
     * SKU's variant has a carton unit already, whatever its factor.
     */
    public static UUID unit(JdbcTemplate jdbc, String sku, int factor) {
        List<UUID> found = jdbc.queryForList("""
                SELECT u.id FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.variant_id = u.variant_id
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
     * retired either way (its cartons stay, as history). For a test that needs cartons of several sizes of one SKU
     * - stock that was loose rows of any size before {@code ck_stock_item_whole_cartons} (V20261117000100) - which
     * therefore takes the sizes in turn: a carton made earlier stays, in a unit that is no longer the active one.
     * {@link #unit} takes whichever carton unit the SKU has active.
     */
    public static UUID unitOf(JdbcTemplate jdbc, String sku, int factor) {
        List<UUID> found = jdbc.queryForList("""
                SELECT u.id FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.variant_id = u.variant_id
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
     * The carton of {@code factor} pieces of a catalogue variant, measured {@code length x width x height} cm and
     * {@code kg} when it is made: the variant's active carton when it holds {@code factor}; otherwise one of that count
     * made the variant's ONE active carton, the earlier one retired as the company changing carton retires it (BR-10).
     * A fixture issuing several customer SKUs on one variant with different counts takes the cartons in turn. The code
     * is {@code CARTON}, or {@code CARTON<factor>} once {@code CARTON} is taken.
     */
    public static UUID ofVariant(JdbcTemplate jdbc, UUID variantId, int factor, String kg, String length,
                                 String width, String height) {
        List<UUID> same = jdbc.queryForList("""
                SELECT id FROM inventory.uom_conversions WHERE variant_id = ? AND active AND factor = ?""",
                UUID.class, variantId, factor);
        if (!same.isEmpty()) {
            return same.getFirst();
        }
        String code = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT exists(SELECT 1 FROM inventory.uom_conversions WHERE variant_id = ? AND unit_of_measure = 'CARTON')""",
                Boolean.class, variantId)) ? "CARTON" + factor : "CARTON";
        List<UUID> earlier = jdbc.queryForList("""
                SELECT id FROM inventory.uom_conversions WHERE variant_id = ? AND unit_of_measure = ? AND factor = ?""",
                UUID.class, variantId, code, factor);
        jdbc.update("UPDATE inventory.uom_conversions SET active = FALSE WHERE variant_id = ? AND active", variantId);
        if (!earlier.isEmpty()) {
            jdbc.update("UPDATE inventory.uom_conversions SET active = TRUE WHERE id = ?", earlier.getFirst());
            return earlier.getFirst();
        }
        UUID carton = Identifiers.newId();
        jdbc.update("""
                INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor,
                    weight_kg, length_cm, width_cm, height_cm, active, version, created_at)
                VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?::numeric, TRUE, 0, NOW())""",
                carton, variantId, code, factor, kg, length, width, height);
        return carton;
    }

    /** The catalogue variant whose cartons the SKU's item is packed in. */
    public static UUID variantOf(JdbcTemplate jdbc, String sku) {
        return jdbc.queryForObject("SELECT variant_id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
    }

    /**
     * Makes this unit its variant's ONE active carton (01 BR-03, {@code uk_uom_conversions_one_carton}): the variant's
     * other active carton is retired - its cartons stay, as history - and this one is activated if it was retired.
     */
    public static void reactivate(JdbcTemplate jdbc, UUID unit) {
        jdbc.update("""
                UPDATE inventory.uom_conversions SET active = FALSE
                 WHERE variant_id = (SELECT variant_id FROM inventory.uom_conversions WHERE id = ?)
                   AND active AND id <> ?""", unit, unit);
        jdbc.update("UPDATE inventory.uom_conversions SET active = TRUE WHERE id = ? AND NOT active", unit);
    }

    /**
     * Makes the unit {@code code} of {@code factor} units of {@code item}'s variant its ONE active carton, as
     * {@link #reactivate} does, made first when the variant has none of that code. A unit of that code with another
     * factor is a fixture error, not a unit to take over: it fails loudly. {@code item} is the inventory item of any SKU
     * of the variant, a customer's SKU included: the carton is the variant's.
     */
    public static UUID cartonUnit(JdbcTemplate jdbc, UUID item, String code, int factor) {
        UUID variant = jdbc.queryForObject("SELECT variant_id FROM inventory.inventory_items WHERE id = ?", UUID.class,
                item);
        List<UUID> found = jdbc.queryForList("""
                SELECT id FROM inventory.uom_conversions
                 WHERE variant_id = ? AND unit_of_measure = ? AND factor = ?""", UUID.class, variant, code, factor);
        Integer sameCode = jdbc.queryForObject("""
                SELECT count(*) FROM inventory.uom_conversions WHERE variant_id = ? AND unit_of_measure = ?""",
                Integer.class, variant, code);
        if (found.isEmpty() && sameCode != null && sameCode > 0) {
            throw new IllegalStateException("The variant already has a unit " + code + " of another factor than "
                    + factor);
        }
        UUID id = found.isEmpty() ? Identifiers.newId() : found.getFirst();
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active)
                    VALUES (?, ?, ?, ?, FALSE)""", id, variant, code, factor);
        }
        jdbc.update("""
                UPDATE inventory.uom_conversions SET active = FALSE
                 WHERE variant_id = ? AND active AND id <> ?""", variant, id);
        jdbc.update("UPDATE inventory.uom_conversions SET active = TRUE WHERE id = ? AND NOT active", id);
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
                    SELECT ?, ?, i.id, u.id, u.factor, u.factor, ?, 'IN_STOCK'
                      FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.variant_id = u.variant_id
                     WHERE u.id = ? AND i.sku = ?""", id, lpn, location, unit, sku);
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
