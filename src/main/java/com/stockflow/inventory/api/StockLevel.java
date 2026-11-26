package com.stockflow.inventory.api;

/**
 * Inventory Level: the stock of one SKU in <b>one warehouse</b>, summed over that warehouse's
 * locations and statuses.
 *
 * <p>A computed figure, never entered or edited — the glossary defines it as the aggregate of the
 * location rows, and system-wide stock is the sum over warehouses. The quantities follow the
 * glossary's vocabulary rather than the per-row {@link StockAvailability} one, because the two
 * use "available" differently:</p>
 * <ul>
 *   <li>{@code onHand} — every physical unit, whatever its status;</li>
 *   <li>{@code available} — units in {@code AVAILABLE} status, <b>before</b> subtracting holds;</li>
 *   <li>{@code reserved} — units held on this warehouse's rows and not allocated: older checkout
 *       holds;</li>
 *   <li>{@code allocated} — units a release placed on a bin and lot for picking (F-FUL step 02, SCRUM-152), held until
 *       picked. Disjoint from {@code reserved}, as the glossary keeps the two apart;</li>
 *   <li>{@code atp} — {@code available - reserved - allocated}, capped by what is still promisable system-wide once
 *       SKU reservations are counted: what may still be promised to a customer.</li>
 * </ul>
 *
 * <p>Each figure again in cartons, counted (ADR-0011, step 3e): the warehouse handles whole cartons only, and the units
 * cannot be divided back into them once cartons of two counts of the SKU stand in one warehouse. {@code atpCartons}
 * is worked out count by count, as a hold is promised (a hold is served only by cartons of its own count), then
 * summed.</p>
 */
public record StockLevel(
        String sku,
        String warehouseCode,
        int onHand,
        int available,
        int reserved,
        int allocated,
        int atp,
        int onHandCartons,
        int availableCartons,
        int reservedCartons,
        int allocatedCartons,
        int atpCartons
) {
}
