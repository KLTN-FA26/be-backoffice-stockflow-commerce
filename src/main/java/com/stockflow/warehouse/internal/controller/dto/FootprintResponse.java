package com.stockflow.warehouse.internal.controller.dto;

import java.math.BigDecimal;

/** A rectangle on the map - a shelf, a bin or an area - as {@link FootprintRequest} describes it. */
public record FootprintResponse(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length, int rotation) {
}
