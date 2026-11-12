package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.inventory.spi.ShortPickLines;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ShortPickLines}: inventory's question, asked while it approves a count's shortage of a carton an order still
 * holds (kltn-docs 20 §4.2 step 8). A plain read joining the caller's transaction; it locks nothing, so it cannot
 * invert the pick, order, stock lock order ({@code PickListService}).
 */
@Component
class ShortPickLineLookup implements ShortPickLines {

    private final PickLineJpaRepository pickLines;

    ShortPickLineLookup(PickLineJpaRepository pickLines) {
        this.pickLines = pickLines;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> shortHolds(Collection<UUID> reservationIds) {
        return reservationIds.isEmpty() ? Set.of() : new HashSet<>(pickLines.findShortReservationIds(reservationIds));
    }
}
