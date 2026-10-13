/**
 * <b>Templated email, SMS and in-app notifications</b>
 *
 * <p>WBS 3.19.2 · database schema {@code notification}</p>
 *
 * <p><b>May depend on:</b> {@code identity :: api} and {@code warehouse :: api} only, both as address books: a
 * staff notice that names a team ("Sales is notified", "alert purchasing", who to tell about a stock alert) is sent
 * to the active accounts holding that role, and only identity knows them; warehouse names the warehouse in a stock
 * alert. The module still makes no business decision through another module and no other module calls it for one;
 * neither depends on this module. Plus {@code common} and {@code contracts}, which are available to every module.</p>
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
