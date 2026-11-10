/**
 * <b>Suppliers, purchase orders, goods receipt, invoice matching</b>
 *
 * <p>WBS 3.2 + 3.3 + 3.4 · database schema {@code procurement}</p>
 *
 * <p><b>May depend on:</b> product, inventory, warehouse, notification, identity, fulfillment and order - a return
 * receipt (team plan 4D-2) checks what comes back against the shipment's cartons ({@code fulfillment :: api}) or the
 * return request ({@code order :: api}); neither of them depends on procurement, so there is no cycle. Plus
 * {@code common} and {@code contracts}, which are available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "procurement :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"product :: api", "inventory :: api", "warehouse :: api", "notification :: api",
        "identity :: api", "fulfillment :: api", "order :: api"})
package com.stockflow.procurement;
