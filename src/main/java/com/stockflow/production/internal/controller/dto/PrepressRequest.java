package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The prepress verdict. FAIL needs a note saying what is wrong with the file. */
public record PrepressRequest(
        @NotNull(message = "decision is required") Decision decision,
        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
    public enum Decision { PASS, FAIL }
}
