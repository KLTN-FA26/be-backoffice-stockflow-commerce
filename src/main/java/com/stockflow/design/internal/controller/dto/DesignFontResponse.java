package com.stockflow.design.internal.controller.dto;

/**
 * A font the design studio offers: {@code code} is what a layout and a print configuration store, {@code label} what
 * people read.
 */
public record DesignFontResponse(String code, String label) {
}
