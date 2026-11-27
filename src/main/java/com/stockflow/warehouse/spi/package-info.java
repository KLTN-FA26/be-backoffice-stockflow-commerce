/**
 * <b>What the {@code warehouse} module needs from the modules above it</b> - a service-provider interface, the
 * reverse of {@code api}.
 *
 * <p>The warehouse map must refuse an edit of a bin's size or a level's clear height that would leave a carton
 * standing where it no longer fits, or a bin holding more than its new volume (kltn-docs 06 BR-23, decided
 * 2026-11-05). What stands in a bin is inventory's knowledge, and inventory already depends on
 * {@code warehouse :: api}; a dependency the other way would be a cycle. So the warehouse declares here the one
 * question it needs answered, inventory implements it ({@code allowedDependencies} names {@code "warehouse :: spi"}),
 * and the shelf service asks it synchronously, inside the edit's transaction. An event would come too late: the edit
 * must be refused, not undone.</p>
 *
 * <p>The same holds for taking a bin, a shelf, an area or a warehouse off the layout while cartons still stand there
 * (06 BR-04): {@link com.stockflow.warehouse.spi.LocationOccupants} answers which cartons do.</p>
 *
 * <p>Like {@code api}: records and interfaces only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.warehouse.spi;
