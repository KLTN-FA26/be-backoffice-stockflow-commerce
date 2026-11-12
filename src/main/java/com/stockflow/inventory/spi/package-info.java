/**
 * <b>What the {@code inventory} module needs to ask of the modules above it</b> - a service-provider interface, the
 * reverse of {@code api}.
 *
 * <p>Approving a count's shortage of a carton an order still holds (kltn-docs 20 §4.2 step 8, 07 §4.4) must know
 * whether the picker has already reported that carton short: only then is the hold the shortage's to give up. A pick
 * line is {@code fulfillment}'s knowledge, and {@code fulfillment} already depends on {@code inventory}; a dependency
 * the other way would be a cycle. So inventory declares here the one question it needs answered, {@code fulfillment}
 * implements it ({@code allowedDependencies} names {@code "inventory :: spi"}), and the approval asks it synchronously,
 * inside its own transaction. The question is a plain read that takes no lock: asking it under the SKU lock cannot
 * invert the pick, order, stock lock order of the pick service.</p>
 *
 * <p>Like {@code api}: records and interfaces only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.inventory.spi;
