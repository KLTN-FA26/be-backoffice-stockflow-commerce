package com.stockflow.warehouse.spi;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks the module that keeps stock which cartons stand at given locations, so that a bin, a shelf, an area or a
 * warehouse is not taken off the layout while cartons are still in it (kltn-docs 06 BR-04, decided 2026-11-05).
 * Implemented by {@code inventory}; see the package documentation for why the interface lives here.
 *
 * <p>Kept apart from {@link BinOccupants}, which judges new bin sizes: the two questions share no caller and no
 * answer.</p>
 */
public interface LocationOccupants {

    /** How many cartons a refusal lists; the total is always the real count. */
    int LISTED = 50;

    /**
     * The cartons standing at these locations: an {@code inventory.stock_package} at one of the codes, labelled on a
     * receipt not yet confirmed ({@code RECEIVING}) or in stock ({@code IN_STOCK}). A carton picked, lost or voided is
     * not there any more. Runs in the caller's transaction.
     *
     * @param locationCodes full location codes; empty answers none
     * @param limit         how many cartons to list, in location code then LPN order
     */
    Cartons cartonsAt(Collection<String> locationCodes, int limit);

    /** @param first at most the limit asked for, in location code then LPN order */
    record Cartons(long total, List<Carton> first) {

        public static final Cartons NONE = new Cartons(0, List.of());

        public Cartons {
            first = List.copyOf(first);
        }
    }

    record Carton(String locationCode, String lpn) {
    }

    /**
     * The one refusal of taking a place off the layout while cartons stand in it, for every kind of place.
     *
     * @param place what was to be deactivated, for the message ("bin HCM-A01-1-A")
     * @throws BusinessException {@code LOCATION_HOLDS_CARTONS}, the count and the listed cartons in its details, unless
     *         {@code cartons} is empty
     */
    static void refuse(Cartons cartons, String place) {
        if (cartons.total() == 0) {
            return;
        }
        String listed = cartons.first().stream()
                .map(c -> c.locationCode() + " " + c.lpn())
                .collect(Collectors.joining(", "));
        String more = cartons.total() > cartons.first().size()
                ? " and %d more".formatted(cartons.total() - cartons.first().size()) : "";
        throw new BusinessException(ErrorCode.LOCATION_HOLDS_CARTONS,
                "%s still holds %d carton(s): %s%s; move them first".formatted(place, cartons.total(), listed, more),
                List.of(), Map.of("total", cartons.total(), "cartons", cartons.first().stream()
                        .map(c -> Map.of("locationCode", c.locationCode(), "lpn", c.lpn()))
                        .toList()));
    }
}
