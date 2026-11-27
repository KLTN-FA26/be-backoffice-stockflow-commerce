/**
 * <b>Warehouse map, locations, slotting engine, putaway</b>
 *
 * <p>WBS 3.5 + 3.11 · database schema {@code warehouse}</p>
 *
 * <p><b>May depend on:</b> none — this module calls no other business module. Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p><b>Called by:</b> every module holding a reference to {@code warehouse.storage_location} -
 * procurement (receiving, QC), inventory (stock, movements, adjustments, counts) and fulfillment
 * (picking, move tasks) - through {@code WarehouseService.findLocation}, to check a location before
 * writing a reference to it (ADR-0007). The order module also reads how much volume a warehouse's bins offer, for the
 * capacity board it works out (BE1, kltn-docs 06 §4.6), through {@code WarehouseCapacities}. Nothing else of the map is
 * public (issue #18 D6).</p>
 *
 * <p><b>No warehouse scope on the map.</b> Warehouse staff and managers are limited to their assigned warehouses
 * (BR-SEC-002), but the map and its locations are not: kltn-docs 06 §2 says so in as many words ("bản đồ và vị trí
 * không giới hạn theo kho được gán"), and a warehouse manager declares warehouses. So nothing in this module calls
 * {@code WarehouseScope}; only putaway work is limited by warehouse, in {@code inventory}. Do not add the check here
 * without that rule changing first - {@code WarehouseMapScopeHttpIntegrationTest} pins it.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "warehouse :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package com.stockflow.warehouse;
