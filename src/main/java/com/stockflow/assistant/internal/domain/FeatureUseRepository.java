package com.stockflow.assistant.internal.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * How often each assistant feature was used, per feature, role and business day ({@code assistant.feature_use},
 * V20270120001300; contract §5.2). Aggregates only: nothing here names a user or holds what they asked. Runs inside
 * the caller's transaction.
 */
public interface FeatureUseRepository {

    /** Adds each count to its day's row, creating the row when the day has none yet. */
    void add(List<FeatureCount> counts);

    /** The rows of the days from {@code from} to {@code to}, both included, by day, then feature, then role. */
    List<FeatureCount> between(LocalDate from, LocalDate to);

    /** Deletes up to {@code limit} rows of days before {@code day}; returns how many. */
    int deleteBefore(LocalDate day, int limit);

    /**
     * @param role the users' roles, '+'-joined ({@code SALES_STAFF+SALES_MANAGER}), {@code OTHER} for a role created at
     *             run time
     * @param day  a business day, Asia/Ho_Chi_Minh ({@link BusinessDate})
     */
    record FeatureCount(String featureKey, String role, LocalDate day, long uses) {

        public FeatureCount {
            Objects.requireNonNull(featureKey, "featureKey");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(day, "day");
            if (uses <= 0) {
                throw new IllegalArgumentException("a count is of at least one use: " + uses);
            }
        }
    }
}
