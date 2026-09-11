/**
 * <b>Read models for warehouse KPIs, inventory and finance</b>
 *
 * <p>WBS 3.20 · database schema {@code reporting}</p>
 *
 * <p><b>May depend on:</b> none — this module calls no other business module. Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "reporting :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package com.stockflow.reporting;
