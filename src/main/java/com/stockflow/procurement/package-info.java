/**
 * <b>Goods receipt</b>: receiving against production orders, then putaway (4A-5, kltn-docs 03)
 *
 * <p>WBS 3.3 · database schema {@code procurement}. Suppliers, purchase orders, supplier invoices and subcontracting
 * were removed with purchasing (4A-8, kltn-docs 00 of 04/11); their tables stay until 4A-9 archives and drops them.
 * The module keeps its name until 4A-10 (QD-15).</p>
 *
 * <p><b>May depend on:</b> inventory, warehouse, identity, and order - receiving against a production order reads and
 * moves it through {@code order :: api}'s {@code ProductionOrderReceiving}; order never depends on procurement, so
 * there is no cycle. Plus {@code common} and {@code contracts}, which are available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "procurement :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"inventory :: api", "warehouse :: api",
        "identity :: api", "order :: api"})
package com.stockflow.procurement;
