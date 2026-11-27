package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A reply to the customer for Sales to edit and send themselves (contract §1.2). Nothing was posted. */
public record ReplyDraftResponse(
        DraftResponse draft,
        @Schema(description = "The customer's orders the draft was told about") List<String> orderNumbers,
        @Schema(example = "vi") String language) {
}
