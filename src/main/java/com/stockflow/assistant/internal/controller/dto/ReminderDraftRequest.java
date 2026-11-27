package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** How the reminder should sound; the facts in it are the installment's whatever the tone. */
public record ReminderDraftRequest(
        @Schema(description = "GENTLE (the default) or FIRM: firmer for money well overdue, never a threat",
                allowableValues = {"GENTLE", "FIRM"}, nullable = true) String tone) {
}
