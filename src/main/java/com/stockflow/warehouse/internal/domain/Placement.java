package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Something occupying floor on a warehouse map: a shelf or an area still on the layout (not
 * {@code INACTIVE}, issue #18 D3). Boundaries are not placements - a wall alongside a shelf is
 * normal.
 *
 * <p>BR-07 spans aggregates, so no constraint can state it and no single aggregate can check it.
 * The application service reads every placement of the warehouse <b>under the warehouse row
 * lock</b> (issue #18 D4) and asks {@link #requireClear}.</p>
 */
public record Placement(UUID id, Kind kind, String code, Footprint footprint) {

    public enum Kind { SHELF, AREA }

    public Placement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(footprint, "footprint");
    }

    /**
     * BR-07: {@code candidate} overlaps nothing already placed. Touching edges is fine - shelves
     * stand back to back.
     *
     * @param self the id of the thing being placed, which may already be among {@code placed}
     */
    public static void requireClear(Footprint candidate, UUID self, Collection<Placement> placed) {
        placed.stream()
                .filter(other -> !other.id().equals(self))
                .filter(other -> other.footprint().overlaps(candidate))
                .findFirst()
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.LAYOUT_OVERLAP,
                            "This would overlap %s %s".formatted(other.kind().name().toLowerCase(Locale.ROOT), other.code()));
                });
    }
}
