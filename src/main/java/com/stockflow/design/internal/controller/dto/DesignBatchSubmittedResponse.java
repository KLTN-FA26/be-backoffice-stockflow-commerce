package com.stockflow.design.internal.controller.dto;

import java.util.UUID;

/** A batch submitted (kltn-docs 12 §4.10): the review round it waits in, and the project with its designs now. */
public record DesignBatchSubmittedResponse(UUID roundId, DesignProjectResponse project) {
}
