/**
 * <b>Cart, checkout, order lifecycle, amendments, RMA</b>
 *
 * <p>WBS 3.14 + 3.17 · database schema {@code ordering}</p>
 *
 * <p><b>May depend on:</b> customer, catalog, inventory, design (and {@code design :: spi}: order answers
 * {@code RequestQuotes}, the quote drawn from a customer's quote request, R5-5), warehouse (the code of the warehouse an
 * order is released from), product (only to answer {@code SkuReferenceProbe}: which placed orders still hold
 * a product's SKUs; product cannot ask order itself, that would be a cycle through catalog; and {@code product :: spi}
 * to answer {@code TaxRatesToday}, the rates the customer SKU list of the quote editor shows, R5-4), notification (4A-3: what a
 * production order's producer was sent, and {@code ProductionOrderDirectory}, which order implements so notification
 * reads a message's text when it writes it). Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p>Placing an order from an accepted quote is where a customer's SKU gets its inventory item (owner decision Q5 of
 * 2026-11-26): {@code OrderServiceImpl.placeQuotedOrder} reads each line SKU's catalogue variant from
 * {@code product :: api}'s {@code CustomerSkus} and opens the items through {@code inventory :: api}, in the order's
 * transaction.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "order :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"customer :: api", "catalog :: api", "inventory :: api", "design :: api", "design :: spi", "warehouse :: api", "payment :: api", "identity :: api", "product :: api", "product :: spi", "notification :: api"})
package com.stockflow.order;
