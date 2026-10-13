/**
 * <b>Templated email, SMS and in-app notifications</b>
 *
 * <p>WBS 3.19.2 · database schema {@code notification}</p>
 *
 * <p><b>May depend on:</b> identity (who to tell about a stock alert, by role) and warehouse (the name of the warehouse
 * in the message) - both only through their {@code api}; neither depends on this module. Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "notification :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"identity :: api", "warehouse :: api"})
package com.stockflow.notification;
