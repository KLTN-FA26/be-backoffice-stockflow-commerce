package com.stockflow.warehouse.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** A rectangle on the map - a shelf, a bin or an area - as {@link FootprintRequest} describes it. */
@Schema(name = "Footprint", description = "A rectangle in the map unit; a bin's is relative to its shelf")
public record FootprintResponse(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length, int rotation) {
}
