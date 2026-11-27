package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** What the salesperson wants the reply to say; the body may be empty. */
public record ReplyDraftRequest(
        @Schema(description = "Optional, at most 300 characters, in the salesperson's own words: what the reply "
                + "should say (remind the customer the contract is not signed, say)")
        @Size(max = 300) String instruction) {
}
