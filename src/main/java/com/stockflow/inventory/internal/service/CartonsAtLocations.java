package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.warehouse.spi.LocationOccupants;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * kltn-docs 06 BR-04 (decided 2026-11-05): the warehouse map asks, before it takes a bin, a shelf, an area or a
 * warehouse off the layout, which cartons still stand there. What stands where is this module's knowledge, so the
 * answer is here and the question is the warehouse's SPI ({@link LocationOccupants}).
 *
 * <p>Not serialised against moves: a carton moved in while the place is being deactivated may slip past the check,
 * as with {@link BinStranding}. The map's own lock is the warehouse row; moves do not take it.</p>
 */
@Component
class CartonsAtLocations implements LocationOccupants {

    private final BinOccupancyQuery space;

    CartonsAtLocations(BinOccupancyQuery space) {
        this.space = space;
    }

    @Override
    public Cartons cartonsAt(Collection<String> locationCodes, int limit) {
        BinOccupancyQuery.Standing standing = space.standingAt(locationCodes, limit);
        return new Cartons(standing.total(), standing.first().stream()
                .map(c -> new Carton(c.locationCode(), c.lpn()))
                .toList());
    }
}
