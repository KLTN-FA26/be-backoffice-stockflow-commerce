/**
 * <b>Read models for warehouse KPIs, inventory and finance</b>
 *
 * <p>WBS 3.20 · database schema {@code reporting}</p>
 *
 * <p><b>May depend on:</b> order (the orders behind the rollups and the live status counts) and product (a SKU's
 * name). It listens to {@code OrderPlaced} and {@code OrderCancelled} from {@code contracts}. Plus {@code common} and
 * {@code contracts}, which are available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "reporting :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"order :: api", "product :: api"})
package com.stockflow.reporting;
