package com.stockflow.production.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The reviewed specification is retained even after the next round has been edited. */
public record SampleReview(int round, SampleSpecification specification, Decision decision,
                           String feedback, UUID recordedBy, Instant recordedAt) {
    public enum Decision { APPROVED, CHANGES_REQUESTED }

    public SampleReview {
        if (round < 1) {
            throw new IllegalArgumentException("Round must be positive");
        }
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(recordedBy, "recordedBy");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (decision == Decision.CHANGES_REQUESTED && (feedback == null || feedback.isBlank())) {
            throw new IllegalArgumentException("Changes require customer feedback");
        }
        feedback = feedback == null ? null : feedback.strip();
    }
}
