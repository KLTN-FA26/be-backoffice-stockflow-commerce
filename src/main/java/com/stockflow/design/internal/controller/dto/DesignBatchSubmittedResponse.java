package com.stockflow.design.internal.controller.dto;

import java.util.UUID;

/**
 * A batch submitted (kltn-docs 12 §4.10): the review round it waits in, the project with its designs now, and the quote
 * request asked with it (BR-25.1) - null when none was asked.
 */
public record DesignBatchSubmittedResponse(UUID roundId, DesignProjectResponse project,
                                           DesignProjectQuoteRequestResponse quoteRequest) {
}
