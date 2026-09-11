package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Flat row produced by the allocation-planning query.
 *
 * <p>Constructed directly by Hibernate through a JPQL constructor expression, so selecting these
 * puts nothing in the persistence context — which is the whole reason it exists. Fields are the
 * raw column types rather than the domain value objects, because a constructor expression can only
 * pass what the result set holds.</p>
 *
 * <p>Public, and a persistence type: JPQL constructor expressions need a fully-qualified,
 * accessible class. It never leaves this package — {@link #toCandidate()} converts it on the way
 * out.</p>
 */
public record AvailabilityRow(
        UUID id,
        String locationCode,
        String lotNumber,
        LocalDate expiryDate,
        StockStatus status,
        int onHand,
        int reserved
) {

    /** Domain-facing view. The subtraction is safe: the query filters {@code onHand > reserved}. */
    public StockAllocator.Candidate toCandidate() {
        return new StockAllocator.Candidate(
                new StockItemId(id),
                new LocationId(locationCode),
                lotNumber,
                expiryDate,
                status,
                Quantity.of(onHand - reserved));
    }
}
