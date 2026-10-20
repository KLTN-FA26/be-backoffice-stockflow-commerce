package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What a sample round prints: a confirmed design snapshot on a blank, how many pieces, by when.
 *
 * <p>No checksum: a design snapshot is immutable, and the checksum the workshop prints against is
 * resolved from the design module when the round is submitted and frozen on the SAMPLE production
 * order ({@code production_order.design_checksum}), which is the only table that carries it.</p>
 */
public record SampleSpecification(UUID designSnapshotId, Sku blankSku, int quantity, LocalDate dueDate) {

    /** {@code ck_sample_request_quantity}: a sample is a few pieces, not a production run. */
    public static final int MAX_QUANTITY = 1000;

    public SampleSpecification {
        Objects.requireNonNull(designSnapshotId, "designSnapshotId");
        Objects.requireNonNull(blankSku, "blankSku");
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Sample quantity must be between 1 and " + MAX_QUANTITY);
        }
    }
}
