/**
 * <b>Goods receipt</b>: receiving against production orders, then putaway (4A-5, kltn-docs 03)
 *
 * <p>WBS 3.3 · database schema {@code procurement}. Suppliers, purchase orders, supplier invoices and subcontracting
 * were removed with purchasing (4A-8, kltn-docs 00 of 04/11); their tables stay until 4A-9 archives and drops them.
 * The module keeps its name until 4A-10 (QD-15).</p>
 *
 * <p><b>May depend on:</b> inventory, warehouse, identity, fulfillment, order, customer and product - receiving against
 * a production order reads and moves it through {@code order :: api}'s {@code ProductionOrderReceiving}; a return
 * receipt (team plan 4D-2) checks what comes back against the shipment's cartons ({@code fulfillment :: api}) or the
 * return request ({@code order :: api}); the carton label asks it, through {@code inventory :: spi}, the lot of a
 * receipt line whose cartons are still being counted. The stock-in voucher of a return receipt names the customer by
 * its legal profile and the signed-in customer asking for its own ({@code customer :: api}), and each SKU by its name
 * ({@code product :: api}'s {@code CustomerSkus}). Neither order, fulfillment, customer nor product depends on
 * procurement, so there is no cycle. Plus {@code common} and {@code contracts}, which are available to every
 * module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "procurement :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"inventory :: api", "inventory :: spi", "warehouse :: api",
        "identity :: api", "fulfillment :: api", "order :: api", "customer :: api", "product :: api"})
package com.stockflow.procurement;
