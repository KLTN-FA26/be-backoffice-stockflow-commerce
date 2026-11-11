/**
 * <b>Storefront listing, detail pages, search and SEO</b>
 *
 * <p>WBS 3.13 · database schema {@code catalog}</p>
 *
 * <p><b>May depend on:</b> product, and inventory for the stock indicator of a customer's own SKUs, in cartons
 * (kltn-docs 13 BR-07: catalog is a consumer of available-to-promise, never its owner), and customer to
 * verify the authenticated subject's active portal profile. Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p>No price reaches the storefront (13 BR-04). The one price there is - a customer's own, internal, staff-only price
 * list - lives here too, for Sales to start a quote from (13 §4.2).</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "catalog :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"product :: api", "inventory :: api", "customer :: api"})
package com.stockflow.catalog;
