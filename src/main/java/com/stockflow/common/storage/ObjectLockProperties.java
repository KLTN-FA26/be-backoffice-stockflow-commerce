package com.stockflow.common.storage;

import com.stockflow.common.domain.BusinessCalendar;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;

/**
 * S3 Object Lock for business records, bound from {@code stockflow.storage.object-lock.*}.
 *
 * <p>Off by default: locking needs a bucket created or configured with Object Lock
 * ({@code infra/aws}), which MinIO in development and the local filesystem in tests are not. With it
 * off nothing is locked and the records are protected by versioning alone.</p>
 *
 * <p>The periods of designs and contracts are placeholders until the business confirms them against the law that
 * applies - the retention period for sales contracts in particular. (Supplier invoices had one until purchasing went,
 * 4A-8.) Raising one later is possible; shortening a lock already applied is not, in either mode.</p>
 *
 * <p><b>An e-invoice is different, in period and in mode.</b> Accounting records are kept at least ten years (Luật Kế
 * toán 2015 Điều 41), and the period runs from the end of the accounting year, not from the day the record was made
 * (NĐ 174/2016/NĐ-CP Điều 12). So an invoice's files are locked to the end of the tenth year after the year they are
 * locked in, plus a day ({@link #retainUntil}) - ten years from the day of issue would end up to a year early. The
 * accounting year is taken to be the calendar year, which is the business's. And they are locked in COMPLIANCE mode
 * ({@code einvoiceMode}) whatever {@code mode} says: a lock an administrator can lift does not keep a record
 * unalterable, and a business record nobody may change is what the law asks for.</p>
 *
 * @param mode        the mode of every lock but an e-invoice's. GOVERNANCE lets a principal holding
 *                    {@code s3:BypassGovernanceRetention} (an administrator, never the application) remove a lock,
 *                    which test data needs; COMPLIANCE lets nobody, not even the account root, until the period ends
 * @param einvoice    how long after the end of the year of locking an e-invoice's files stay locked
 * @param einvoiceMode the mode of an e-invoice's lock: COMPLIANCE. GOVERNANCE only on a test bucket, whose invoices
 *                    are not records and must be deletable
 */
@ConfigurationProperties(prefix = "stockflow.storage.object-lock")
public record ObjectLockProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("GOVERNANCE") Mode mode,
        @DefaultValue("P1095D") Duration confirmedDesign,
        @DefaultValue("P3650D") Duration salesContract,
        @DefaultValue("P10Y") Period einvoice,
        @DefaultValue("COMPLIANCE") Mode einvoiceMode
) {

    public enum Mode { GOVERNANCE, COMPLIANCE }

    public ObjectLockProperties {
        requirePositive(confirmedDesign, "confirmed-design");
        requirePositive(salesContract, "sales-contract");
        if (einvoice == null || einvoice.isNegative() || einvoice.isZero()) {
            throw new IllegalArgumentException("stockflow.storage.object-lock.einvoice must be positive");
        }
        if (mode == null || einvoiceMode == null) {
            throw new IllegalArgumentException("stockflow.storage.object-lock.mode and einvoice-mode must be set");
        }
    }

    /** Off, the default modes and periods: what an unconfigured application binds to. */
    public static ObjectLockProperties disabled() {
        return new ObjectLockProperties(false, Mode.GOVERNANCE, Duration.ofDays(1095), Duration.ofDays(3650),
                Period.ofYears(10), Mode.COMPLIANCE);
    }

    /**
     * Until when a record locked at {@code now} stays locked. A design or a contract: its period from now. An
     * e-invoice: the end of the year of {@code now} in the business's calendar, plus its period, plus a day - the
     * locking happens on or after the invoice's date, so its year is never earlier than the invoice's, and the lock
     * is never shorter than the law's ten years from the end of the invoice's accounting year.
     */
    public Instant retainUntil(RecordRetention record, Instant now) {
        return switch (record) {
            case CONFIRMED_DESIGN -> now.plus(confirmedDesign);
            case SALES_CONTRACT -> now.plus(salesContract);
            case EINVOICE -> LocalDate.of(BusinessCalendar.date(now).getYear() + 1, 1, 1).plus(einvoice).plusDays(1)
                    .atStartOfDay(BusinessCalendar.ZONE).toInstant();
        };
    }

    /** The mode a record of this kind is locked in. */
    public Mode modeOf(RecordRetention record) {
        return record == RecordRetention.EINVOICE ? einvoiceMode : mode;
    }

    private static void requirePositive(Duration period, String name) {
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("stockflow.storage.object-lock." + name + " must be positive");
        }
    }
}
