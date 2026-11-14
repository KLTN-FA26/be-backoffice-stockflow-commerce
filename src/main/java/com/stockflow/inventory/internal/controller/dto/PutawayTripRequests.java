package com.stockflow.inventory.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** What a clerk sends while working a putaway trip (kltn-docs 05 §4.5). Every scan is a code, never a quantity. */
public final class PutawayTripRequests {

    private PutawayTripRequests() {
    }

    /** Opens a trip in a warehouse. */
    public record Open(@NotNull UUID warehouseId) {
    }

    /** A carton scanned onto the cart. */
    public record Carton(@NotBlank @Size(max = 30) String lpn) {
    }

    /** At a stop: the bin scanned, then the carton put into it. */
    public record Placement(@NotBlank @Size(max = 64) String locationCode,
                            @NotBlank @Size(max = 30) String lpn) {
    }
}
