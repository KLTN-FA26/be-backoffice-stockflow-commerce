package com.stockflow.design.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/**
 * What "Duyệt tất cả" did (kltn-docs 12 §4.10, BR-20): per design, {@code APPROVED}, {@code NOT_APPROVED} with the
 * reason it stays waiting, or {@code SKIPPED} when it no longer waits in the round; and the round as it is now.
 */
public record DesignRoundApprovalResponse(List<Outcome> outcomes, DesignReviewRoundResponse round) {

    /** One design's outcome. */
    public record Outcome(UUID designId, String outcome, String errorCode, String message) {
    }
}
