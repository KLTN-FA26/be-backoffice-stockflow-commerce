package com.stockflow.design.internal.controller.dto;

import java.util.List;

/**
 * BR-DSG-001's machine checks of one design file. {@code passed} is false when a finding blocks the technical review
 * (fonts not embedded, an image below 150 dpi, a file that does not open); the others are for the reviewer to weigh.
 */
public record DesignPreflightResponse(boolean passed, List<Finding> findings) {

    public record Finding(String code, String detail, boolean blocking) {
    }
}
