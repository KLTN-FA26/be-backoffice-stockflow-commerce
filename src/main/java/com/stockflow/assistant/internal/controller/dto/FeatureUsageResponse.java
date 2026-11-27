package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** How often each assistant feature was used, per feature, role and day (contract §5.2). Counts only. */
public record FeatureUsageResponse(
        @Schema(description = "The first day read, Vietnam time") LocalDate from,
        @Schema(description = "The last day read, included") LocalDate to,
        @Schema(description = "Every feature key counted, used or not in the period: a key with no row was not used")
        List<String> featureKeys,
        @Schema(description = "One row per day, feature and role with at least one use; up to a minute behind")
        List<Row> rows) {

    public record Row(
            LocalDate day,
            @Schema(example = "chat") String featureKey,
            @Schema(description = "The users' roles, '+'-joined when they hold several; OTHER for a role created at "
                    + "run time", example = "SALES_STAFF") String role,
            long uses) {
    }
}
