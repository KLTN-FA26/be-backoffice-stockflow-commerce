/**
 * <b>What the {@code product} module needs from the modules above it</b> - a service-provider interface, the reverse
 * of {@code api}.
 *
 * <p>The back office shows, on a print configuration, how many customers' designs wait for review on it (decision 1,
 * 2026-11-20), so the Sales Manager sees who an edit of what is printed would send back to re-render. Designs are the
 * design module's, and design already depends on {@code product :: api}; a dependency the other way would be a cycle.
 * So product declares here the one question it asks, design implements it ({@code allowedDependencies} names
 * {@code "product :: spi"}), and product asks it where it answers - absent the design module (product's own module
 * test), there is simply nothing to count.</p>
 *
 * <p>Like {@code api}: interfaces and records only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.product.spi;
