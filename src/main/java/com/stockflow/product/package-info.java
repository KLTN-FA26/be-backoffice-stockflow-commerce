/**
 * <b>Product master data, variants, SKUs, print configuration</b>
 *
 * <p>WBS 3.1 · database schema {@code product}
 *
 * <p><b>May depend on:</b> inventory, to validate and synchronously apply SKU control settings.
 * Identity resolves media audit usernames for the four-eyes check. Plus {@code common} and {@code
 * contracts}, which are available to every module.
 *
 * <p>The module has two packages and the split is the whole point:
 *
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "product :: api"}.
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"inventory :: api", "identity :: api"})
package com.stockflow.product;
