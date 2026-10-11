package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.BinNamingScheme;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies of the shelf endpoints, grouped because they share their pieces. Update requests
 * never carry a code or a level index: both are part of location codes and never change (BR-13).
 */
public final class ShelfRequests {

    private ShelfRequests() {
    }

    /** The sides a picker can reach bins from, in the shelf's own frame (north = its top edge). */
    @Schema(name = "PickFacesRequest")
    public record PickFaces(boolean north, boolean east, boolean south, boolean west) {
    }

    public record CreateShelf(
            @Schema(description = "Upper-cased. Part of every bin's location code; never changes", example = "A01")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,20}") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @Schema(description = "Optional")
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
            @Schema(description = "Optional")
            UUID zoneId,
            @NotNull @Valid FootprintRequest footprint,
            boolean obstacle,
            @NotNull PickFaces pickFaces,
            @NotNull StorageClass defaultStorageClass,
            @Schema(description = "The version from the last read; a stale one answers 409 OPTIMISTIC_LOCK")
            @PositiveOrZero long version
    ) {
    }

    /** A shelf's or a bin's status. Leaving {@code INACTIVE} needs the place free again. */
    @Schema(name = "ChangeShelfOrBinStatus")
    public record ChangeStatus(@NotNull LocationStatus status) {
    }

    public record AddLevel(
            @Schema(description = "Part of every bin's location code, without a leading zero; never changes",
                    example = "2")
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
            @Schema(description = "Upper-cased, unique on the level. The location code becomes "
                    + "prefix-shelf-level-bin", example = "B")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,20}") String code,
            @Size(max = 1000) String description,
            @NotNull @Valid FootprintRequest footprint,
            @NotNull BinType type,
            @Schema(description = "null = the shelf's defaultStorageClass applies")
            StorageClass storageClassOverride,
            @Schema(description = "Base units; null = not checked", example = "40")
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
            @Schema(description = "null = the shelf's defaultStorageClass applies")
            StorageClass storageClassOverride,
            @Schema(description = "Base units; null = not checked", example = "40")
            @Positive Integer capacityUnits,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight,
            boolean pickable,
            boolean putawayTarget
    ) {
    }

    /**
     * BR-14. No upper bound on the counts here: a grid over
     * {@value com.stockflow.warehouse.internal.domain.BinGrid#MAX_BINS_PER_LEVEL} bins is the domain's
     * {@code 409 SHELF_CAPACITY_EXCEEDED}, the same answer as adding one bin too many by hand.
     *
     * @param namingScheme {@code null} = {@code SEQUENTIAL}
     */
    public record GenerateBins(
            @Schema(description = "1 to 20 levels of this shelf, each with no bin yet")
            @NotEmpty @Size(max = 20) List<@NotNull UUID> levelIds,
            @Schema(description = "Rows of the grid, along the shelf's length", example = "1")
            @Min(1) int rowCount,
            @Schema(description = "Columns of the grid, along the shelf's width", example = "4")
            @Min(1) int columnCount,
            @Schema(description = "null = SEQUENTIAL")
            BinNamingScheme namingScheme,
            @NotNull @Valid BinDefaults defaults
    ) {
    }

    /**
     * What every generated bin starts with: {@code type} and the override go on the bin, the rest on
     * its storage location.
     *
     * @param storageClassOverride {@code null} = the shelf's default class applies
     */
    @Schema(name = "BinDefaultsRequest")
    public record BinDefaults(
            @NotNull BinType type,
            @Schema(description = "null = the shelf's defaultStorageClass applies")
            StorageClass storageClassOverride,
            @Schema(description = "Base units; null = not checked", example = "40")
            @Positive Integer capacityUnits,
            @Positive @Digits(integer = 7, fraction = 3) BigDecimal maxWeight,
            boolean pickable,
            boolean putawayTarget
    ) {
    }
}
