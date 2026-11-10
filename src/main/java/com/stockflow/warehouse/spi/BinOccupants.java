package com.stockflow.warehouse.spi;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.api.BinSize;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks the module that keeps stock whether bins may take new sizes (kltn-docs 06 BR-23). Implemented by
 * {@code inventory}; see the package documentation for why the interface lives here.
 */
public interface BinOccupants {

    /**
     * What a change of these bins' sizes would strand: each carton standing in one of them that would no longer fit
     * it dimension by dimension (06 BR-01, turned on the horizontal when its item allows), and each bin whose new
     * volume would be less than the volume of the cartons in it. Runs in the caller's transaction.
     *
     * @param newSizes location code of a bin to its size after the change
     * @return empty when the change strands nothing
     */
    List<Stranded> strandedBy(Map<String, BinSize> newSizes);

    /**
     * @param lpn null for a bin whose volume would be short ({@link Why#OVER_VOLUME}): no single carton is to blame
     */
    record Stranded(String locationCode, String lpn, Why why) {
    }

    enum Why { DOES_NOT_FIT, OVER_VOLUME }

    /**
     * The one refusal of a change that strands cartons, for both sides that can make one (the map and the carton
     * unit), so they answer alike.
     *
     * @param change what is changed, for the message ("the bin's new size")
     * @throws BusinessException {@code CARTONS_WOULD_NOT_FIT}, the bins and cartons in its details, unless
     *         {@code stranded} is empty
     */
    static void refuse(List<Stranded> stranded, String change) {
        if (stranded.isEmpty()) {
            return;
        }
        String listed = stranded.stream()
                .map(s -> s.lpn() == null ? s.locationCode() + " (volume)" : s.locationCode() + " " + s.lpn())
                .collect(Collectors.joining(", "));
        throw new BusinessException(ErrorCode.CARTONS_WOULD_NOT_FIT,
                "With %s these cartons would no longer fit where they stand: %s; move them first".formatted(change,
                        listed), List.of(), Map.of("stranded", stranded.stream()
                        .map(s -> Map.of("locationCode", s.locationCode(), "lpn", s.lpn() == null ? "" : s.lpn(),
                                "why", s.why().name()))
                        .toList()));
    }
}
