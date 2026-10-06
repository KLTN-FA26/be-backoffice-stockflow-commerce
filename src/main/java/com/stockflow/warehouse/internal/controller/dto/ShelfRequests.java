package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request bodies of the shelf endpoints, grouped because they share their pieces. Update requests
 * never carry a code or a level index: both are part of location codes and never change (BR-13).
 */
public final class ShelfRequests {

    private ShelfRequests() {
    }

    /** The sides a picker can reach bins from, in the shelf's own frame (north = its top edge). */
    public record PickFaces(boolean north, boolean east, boolean south, boolean west) {
    }

    public record CreateShelf(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,20}") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            UUID zoneId,
            @NotNull @Valid FootprintRequest footprint,
            boolean obstacle,
            @NotNull PickFaces pickFaces,
            @NotNull StorageClass defaultStorageClass
    ) {
    }

    /** @param version the version the edit was based on, from the last read */
    public record UpdateShelf(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            UUID zoneId,
            @NotNull @Valid FootprintRequest footprint,
            boolean obstacle,
            @NotNull PickFaces pickFaces,
            @NotNull StorageClass defaultStorageClass,
            @PositiveOrZero long version
    ) {
    }

    /** A shelf's or a bin's status. Leaving {@code INACTIVE} needs the place free again. */
    public record ChangeStatus(@NotNull LocationStatus status) {
    }

    public record AddLevel(
            @Min(1) @Max(99) int levelIndex,
            @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal elevation,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal usableHeight,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight
    ) {
    }

    public record UpdateLevel(
            @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal elevation,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal usableHeight,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight
    ) {
    }

    /** @param storageClassOverride {@code null} = the shelf's default class applies */
    public record AddBin(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,20}") String code,
            @Size(max = 1000) String description,
            @NotNull @Valid FootprintRequest footprint,
            @NotNull BinType type,
            StorageClass storageClassOverride,
            @Positive Integer capacityUnits,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight,
            boolean pickable,
            boolean putawayTarget
    ) {
    }

    public record UpdateBin(
            @Size(max = 1000) String description,
            @NotNull @Valid FootprintRequest footprint,
            @NotNull BinType type,
            StorageClass storageClassOverride,
            @Positive Integer capacityUnits,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight,
            boolean pickable,
            boolean putawayTarget
    ) {
    }
}
