package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.spi.LocationOccupants;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * kltn-docs 06 BR-04 (decided 2026-11-05): a bin, a shelf, an area or a warehouse goes {@code INACTIVE} - off the
 * layout - only once no carton stands there; {@code BLOCKED} and {@code MAINTENANCE} stay allowed with cartons in
 * place, as the way to lock a place while it is counted or emptied. The map services work out the place's location
 * codes and ask here, under the warehouse row lock they already hold.
 *
 * <p><b>No database twin</b>, for the reason {@code ShelfLayoutServiceImpl} gives for BR-06 and BR-07: the rule spans
 * the warehouse and inventory schemas, and a warehouse trigger reading inventory would reverse the module dependency.
 * Moves do not take the warehouse lock, so a carton moved in while the place is being deactivated may slip past.</p>
 */
@Component
class EmptyBeforeInactive {

    /**
     * Inventory, through the warehouse's own SPI. Through a provider only because a test slice of this module alone
     * has no inventory; the application always has one.
     */
    private final ObjectProvider<LocationOccupants> occupants;

    EmptyBeforeInactive(ObjectProvider<LocationOccupants> occupants) {
        this.occupants = occupants;
    }

    /**
     * @param locationCodes every location of the place: a bin's own, a shelf's bins', an area's, a warehouse's
     * @param place         the place, for the message ("bin HCM-A01-1-A")
     * @throws com.stockflow.common.error.BusinessException {@code LOCATION_HOLDS_CARTONS}, listing the cartons
     */
    void require(Collection<String> locationCodes, String place) {
        LocationOccupants stock = occupants.getIfAvailable();
        if (stock == null || locationCodes.isEmpty()) {
            return;
        }
        LocationOccupants.refuse(stock.cartonsAt(locationCodes, LocationOccupants.LISTED), place);
    }
}
