package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One quote asked over approved designs of a project (kltn-docs 12 §4.11, BR-21): the project's name as the context the
 * sales staff see (BR-17), one delivery date, and a line per design - its own SKU and the cartons asked, a suggestion.
 */
public record DesignProjectQuoteRequestResponse(UUID id, UUID projectId, String projectName, UUID customerId,
                                                String customerName, LocalDate desiredDeliveryDate, UUID requestedBy,
                                                Instant requestedAt, List<Line> lines) {

    /** One design of the request. */
    public record Line(UUID designId, String designName, String designStatus, String customerSku, int cartons) {
    }
}
