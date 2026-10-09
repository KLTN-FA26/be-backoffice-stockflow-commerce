package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request bodies of the area endpoints. No {@code zoneId}: an area belongs to no zone. Updates never
 * carry the code, which is part of the location code (BR-13).
 */
public final class AreaRequests {

    private AreaRequests() {
    }

    /**
     * The area's storage location. Required for every type but {@code NON_STORAGE}, for which it is
     * ignored (the domain answers {@code 400} when a storage type comes without it).
     *
     * @param storageClass {@code null} = {@code NORMAL} (issue #18 D9)
     */
    @Schema(name = "AreaLocationRequest")
    public record Location(
            @Schema(description = "null = NORMAL")
            StorageClass storageClass,
            @Positive Integer capacityUnits,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight,
            boolean pickable,
            boolean putawayTarget
    ) {
    }

    public record CreateArea(
            @Schema(description = "Upper-cased. The location code becomes prefix-area; never changes",
                    example = "RCV02")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,20}") String code,
            @NotNull AreaType type,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Valid FootprintRequest footprint,
            boolean obstacle,
            @Schema(description = "Required for every type but NON_STORAGE, where it is ignored")
            @Valid Location location
    ) {
    }

    /**
     * A full replacement, like every {@code PUT}: a {@code location} without {@code storageClass}
     * sets it back to {@code NORMAL}, so send the current one to keep it. {@code NON_STORAGE} to a
     * storage type creates the location; the other way is refused
     * ({@code 409 AREA_TYPE_CHANGE_NOT_ALLOWED}, issue #18 D10).
     *
     * @param version the version the edit was based on, from the last read
     */
    public record UpdateArea(
            @NotNull AreaType type,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Valid FootprintRequest footprint,
            boolean obstacle,
            @Schema(description = "Required for every type but NON_STORAGE, where it is ignored")
            @Valid Location location,
            @Schema(description = "The version from the last read; a stale one answers 409 OPTIMISTIC_LOCK")
            @PositiveOrZero long version
    ) {
    }

    /** Written to the area and its location alike (issue #18 D8). */
    @Schema(name = "ChangeAreaStatus")
    public record ChangeStatus(@NotNull LocationStatus status) {
    }
}
