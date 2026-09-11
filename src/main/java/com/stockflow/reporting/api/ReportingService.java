package com.stockflow.reporting.api;

/**
 * THE public API of the reporting module — the only package other modules may import.
 *
 * <p>STARTER STUB. Reporting is a read-model / CQRS module: it answers queries and owns no write
 * aggregate, which is why this module has an {@code api} and a {@code service} but deliberately no
 * {@code internal.domain} aggregate. Replace the empty body with the read models the screens need.</p>
 *
 * <p>Every return type is a record declared in THIS package, never a domain object or JPA entity
 * ({@code ArchitectureTest.theApiPackageLeaksNothingInternal} / {@code theApiPublishesNoEntities}).
 * A read model may be projected straight from SQL — see {@code inventory.internal.repository}'s
 * projection rows for the shape.</p>
 */
public interface ReportingService {

    // TODO: declare the read models the reporting screens need, e.g.
    //       WarehouseKpiSummary warehouseKpis(UUID warehouseId); with the record in this package.
}
