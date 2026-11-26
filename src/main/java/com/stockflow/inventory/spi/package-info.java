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
 * <p>A short pick's reason (the carton was not there, or it was damaged) is recorded on the pick line, which is
 * fulfillment's: inventory asks it here too, and fulfillment answers it, as the warehouse map asks inventory what stands
 * in a bin.</p>
 *
 * <p>The carton label asks two more questions the same way (SCRUM-473): what each SKU is called ({@link SkuNames},
 * answered by {@code product}, which depends on {@code inventory :: api}), and which lot a goods receipt line is in
 * ({@link ReceiptLineLots}, answered by {@code procurement}) for a carton still being counted, which has no stock row
 * to carry its lot yet. Each is one question per label sheet, and a module test without the answering module prints
 * the label without that field.</p>
 *
 * <p>Whether a carton may be turned is its catalogue variant's ({@link CartonRotation}, answered by {@code product};
 * owner decision Q9): putaway, the bin-fit checks and the packaging resolver ask it, and a module test without
 * {@code product} treats every carton as rotatable, the documented default.</p>
 *
 * <p>Like {@code api}: records and interfaces only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.inventory.spi;
