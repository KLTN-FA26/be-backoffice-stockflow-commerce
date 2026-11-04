package com.stockflow.warehouse.internal.service;

/**
 * The words a bin label prints before each part of its path ({@code Kệ A01 · Tầng 2 · Bin B}),
 * already translated. The caller resolves them from the message bundles, so the renderer stays a
 * pure function of its input and needs no {@code MessageSource}.
 */
public record LabelWords(String shelf, String level, String bin) {
}
