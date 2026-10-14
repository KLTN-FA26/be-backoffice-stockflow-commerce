/**
 * <b>Picking, packing, shipping, proof of delivery</b>
 *
 * <p>WBS 3.7 + 3.8 + 3.9 · database schema {@code fulfillment}</p>
 *
 * <p><b>May depend on:</b> order, inventory, warehouse, design, identity. Plus {@code common} and {@code contracts}, which are
 * available to every module. From inventory it uses two named interfaces: {@code api}, and
 * {@code consumption} — the narrow one that deducts picked stock and books put-backs, in the pick
 * line's own transaction (SCRUM-275). No other module may use {@code consumption}.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "fulfillment :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"order :: api", "inventory :: api",
        "inventory :: consumption", "warehouse :: api", "design :: api", "identity :: api"})
package com.stockflow.fulfillment;
