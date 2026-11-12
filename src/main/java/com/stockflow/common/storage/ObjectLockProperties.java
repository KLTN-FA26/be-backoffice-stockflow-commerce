package com.stockflow.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * S3 Object Lock for business records, bound from {@code stockflow.storage.object-lock.*}.
 *
 * <p>Off by default: locking needs a bucket created or configured with Object Lock
 * ({@code infra/aws}), which MinIO in development and the local filesystem in tests are not. With it
 * off nothing is locked and the records are protected by versioning alone.</p>
 *
 * <p>The periods are placeholders until the business confirms them against the law that applies -
 * the retention period for sales contracts in particular; an e-invoice's ten years are Luật Kế toán 2015 Điều 41. (Supplier invoices had one until purchasing went, 4A-8.)
 * Raising one later is
 * possible; shortening a lock already applied is not, in either mode.</p>
 *
 * @param mode GOVERNANCE lets a principal holding {@code s3:BypassGovernanceRetention} (an
 *             administrator, never the application) remove a lock, which test data needs;
 *             COMPLIANCE lets nobody, not even the account root, until the period ends
 */
@ConfigurationProperties(prefix = "stockflow.storage.object-lock")
public record ObjectLockProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("GOVERNANCE") Mode mode,
        @DefaultValue("P1095D") Duration confirmedDesign,
        @DefaultValue("P3650D") Duration salesContract,
        @DefaultValue("P3650D") Duration einvoice
) {

    public enum Mode { GOVERNANCE, COMPLIANCE }

    public ObjectLockProperties {
        requirePositive(confirmedDesign, "confirmed-design");
        requirePositive(salesContract, "sales-contract");
        requirePositive(einvoice, "einvoice");
        if (mode == null) {
            throw new IllegalArgumentException("stockflow.storage.object-lock.mode must be set");
        }
    }

    /** Off, GOVERNANCE, the default periods: what an unconfigured application binds to. */
    public static ObjectLockProperties disabled() {
        return new ObjectLockProperties(false, Mode.GOVERNANCE, Duration.ofDays(1095), Duration.ofDays(3650),
                Duration.ofDays(3650));
    }

    public Duration periodOf(RecordRetention record) {
        return switch (record) {
            case CONFIRMED_DESIGN -> confirmedDesign;
            case SALES_CONTRACT -> salesContract;
            case EINVOICE -> einvoice;
        };
    }

    private static void requirePositive(Duration period, String name) {
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("stockflow.storage.object-lock." + name + " must be positive");
        }
    }
}
