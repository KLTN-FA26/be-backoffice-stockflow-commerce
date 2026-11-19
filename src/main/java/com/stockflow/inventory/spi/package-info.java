/**
 * <b>What {@code inventory} needs to ask of the module that picks</b> - a service-provider interface, the reverse of
 * {@code api}. A short pick's reason (the carton was not there, or it was damaged) is recorded on the pick line, which is
 * fulfillment's; fulfillment already depends on inventory, so the question is declared here and fulfillment answers it
 * ({@code allowedDependencies} names {@code "inventory :: spi"}), as the warehouse map asks inventory what stands in a bin.
 *
 * <p>Like {@code api}: records and interfaces only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.inventory.spi;
