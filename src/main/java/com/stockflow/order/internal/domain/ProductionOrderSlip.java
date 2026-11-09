package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The slip a production order was sent with (4A-3, kltn-docs 17 §4.3 step 7): the PDF the producer works from, kept as
 * it was sent - one per production order, never changed - with the SHA-256 and size of the stored object, so it can be
 * shown again and a download refused when the object no longer hashes to what was sent. The database holds the same
 * rules ({@code ordering.production_order_slip}, its CHECKs and triggers).
 */
public record ProductionOrderSlip(UUID productionOrderId, String storageKey, String sha256, long sizeBytes,
                                  Instant createdAt) {

    /** {@code FileCategory.DOCUMENT}'s limit: a slip is a document. */
    public static final long MAX_BYTES = 25L * 1024 * 1024;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ProductionOrderSlip {
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (storageKey == null || !storageKey.startsWith("documents/")) {
            throw new IllegalArgumentException("A slip is kept among the documents");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("A slip's SHA-256 is 64 lower-case hex digits");
        }
        if (sizeBytes < 1 || sizeBytes > MAX_BYTES) {
            throw new IllegalArgumentException("A slip is a PDF of 1 byte to 25 MiB");
        }
    }

    /** The name a slip is saved under: the production order's code. */
    public static String fileName(String productionOrderCode) {
        return productionOrderCode + ".pdf";
    }
}
