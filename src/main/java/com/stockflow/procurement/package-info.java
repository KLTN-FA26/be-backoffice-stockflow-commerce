/**
 * <b>Suppliers, purchase orders, goods receipt, QC, invoice matching</b>
 *
 * <p>WBS 3.2 + 3.3 + 3.4 · database schema {@code procurement}</p>
 *
 * <p><b>May depend on:</b> product, inventory, warehouse. Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "procurement :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"product :: api", "inventory :: api", "warehouse :: api"})
package com.stockflow.procurement;
