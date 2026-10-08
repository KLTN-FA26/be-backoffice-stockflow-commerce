package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A verified, immutable design reference and the blank it will be printed on. */
public record SampleSpecification(UUID designSnapshotId, String designChecksum,
                                  Sku blankSku, int quantity, LocalDate dueDate) {
    public SampleSpecification {
        Objects.requireNonNull(designSnapshotId, "designSnapshotId");
        Objects.requireNonNull(blankSku, "blankSku");
        if (designChecksum == null || !designChecksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A SHA-256 design checksum is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Sample quantity must be positive");
        }
    }
}
