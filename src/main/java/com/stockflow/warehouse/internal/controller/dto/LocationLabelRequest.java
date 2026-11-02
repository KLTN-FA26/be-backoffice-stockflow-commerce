package com.stockflow.warehouse.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stockflow.warehouse.internal.domain.LabelSelection;
import com.stockflow.common.label.LabelFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What to print (SCRUM-97). At most one of {@code shelfId}, {@code zoneId} and {@code locationCodes};
 * none of them prints the whole warehouse.
 */
@Schema(description = "Print location labels. At most one of shelfId, zoneId, locationCodes; none = the whole "
        + "warehouse. INACTIVE locations, and bins on an INACTIVE shelf, are skipped - or refused when named "
        + "in locationCodes")
public record LocationLabelRequest(
        @Schema(description = "A4_SHEET: 24 labels of 70 x 37 mm per A4 page. THERMAL_ROLL: one 60 x 40 mm "
                + "label per page", example = "A4_SHEET")
        @NotNull LabelFormat format,
        @Schema(description = "Every bin of this shelf")
        UUID shelfId,
        @Schema(description = "Only these levels of shelfId", example = "[1, 2]")
        @Size(min = 1) Set<@NotNull @Min(1) @Max(99) Integer> levelIndexes,
        @Schema(description = "Every bin of the zone's shelves; a storage area belongs to no zone")
        UUID zoneId,
        @Schema(description = "Named bins or storage areas; case does not matter", example = "[\"HCM-A01-2-B\", "
                + "\"HCM-RCV01\"]")
        @Size(min = 1, max = 2000)
        List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String> locationCodes
) {

    @JsonIgnore
    @AssertTrue(message = "Send at most one of shelfId, zoneId, locationCodes")
    public boolean isOneSelector() {
        int selectors = (shelfId == null ? 0 : 1) + (zoneId == null ? 0 : 1) + (locationCodes == null ? 0 : 1);
        return selectors <= 1;
    }

    @JsonIgnore
    @AssertTrue(message = "levelIndexes needs shelfId")
    public boolean isLevelsWithShelf() {
        return levelIndexes == null || shelfId != null;
    }

    /** Call only on a validated request. */
    public LabelSelection toSelection() {
        if (shelfId != null) {
            return new LabelSelection.OfShelf(shelfId, levelIndexes);
        }
        if (zoneId != null) {
            return new LabelSelection.OfZone(zoneId);
        }
        if (locationCodes != null) {
            return new LabelSelection.OfCodes(Set.copyOf(locationCodes));
        }
        return new LabelSelection.WholeWarehouse();
    }
}
