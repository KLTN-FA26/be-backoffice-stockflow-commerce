/**
 * Spring Data repositories, the adapters implementing this module's domain ports, and the mappers
 * between entity and domain model.
 *
 * <p>The port is declared in {@code internal.domain} and implemented here, not the other way round.
 * That inversion is what keeps the domain free of JPA, and it is the reason the aggregates can be
 * unit-tested in milliseconds with no database.</p>
 *
 * <p>Query projections live here too: a projection is a shape the database returns, which makes it
 * a persistence concern rather than a domain one.</p>
 */
package com.stockflow.procurement.internal.repository;
