package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Request bodies of the boundary endpoints. A wall has {@code passable: false} and no
 * {@code operationalStatus}; a door needs one - checked by the domain, a {@code 400} otherwise.
 */
public final class BoundaryRequests {

    private BoundaryRequests() {
    }

    public record CreateBoundary(
            @NotNull BoundaryType type,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal startX,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal startY,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal endX,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal endY,
            boolean passable,
            DoorStatus operationalStatus
    ) {
    }

    /** @param version the version the edit was based on, from the last read */
    public record UpdateBoundary(
            @NotNull BoundaryType type,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal startX,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal startY,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal endX,
            @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal endY,
            boolean passable,
            DoorStatus operationalStatus,
            @PositiveOrZero long version
    ) {
    }
}
