/**
 * <b>Authentication, users, roles and the permission matrix</b>
 *
 * <p>WBS 3.19.1 · database schema {@code identity}</p>
 *
 * <p><b>May depend on:</b> warehouse — to validate and resolve the warehouses staff are assigned to
 * (SCRUM-457). Plus {@code common} and {@code contracts}, which are available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "identity :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"warehouse :: api"})
package com.stockflow.identity;
