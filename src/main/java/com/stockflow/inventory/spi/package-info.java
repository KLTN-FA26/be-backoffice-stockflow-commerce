/**
 * <b>What the {@code inventory} module needs from the modules above it</b> - a service-provider interface, the reverse
 * of {@code api}.
 *
 * <p>A carton picked for an order leaves stock whole at the scan (CONSUMED) and waits at the dispatch area for its check,
 * its shipping label and its handover. Its LPN label can still tear or go missing there, and is then printed again by
 * its LPN (kltn-docs 03 §4.1 step 4, 08 §6 edge case "Nhãn LPN rách"). Which consumed cartons are such cartons - picked
 * for an order and not handed over yet - is fulfillment's knowledge, and fulfillment already depends on
 * {@code inventory :: api}; a dependency the other way would be a cycle. So inventory declares here the one question it
 * needs answered, fulfillment implements it ({@code allowedDependencies} names {@code "inventory :: spi"}), and the
 * label print asks it synchronously, inside its own read.</p>
 *
 * <p>Like {@code api}: records and interfaces only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.inventory.spi;
