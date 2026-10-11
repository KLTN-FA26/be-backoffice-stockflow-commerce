package com.stockflow.payment.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What a customer owes for one delivered credit order (kltn-docs 15 §4.3, §5.3; SCRUM-431): opened
 * at delivery, due on the delivery day plus the order's days to pay, paid by the customer's
 * transfers. The money received never exceeds what is owed ({@code ck_receivable_amounts}) and the
 * status always says what the money says ({@code ck_receivable_status_amounts}).
 */
public final class Receivable extends AggregateRoot {

    private final UUID id;
    private final UUID orderId;
    private final UUID customerId;
    private final BigDecimal amount;
    private BigDecimal paidAmount;
    private final String currency;
    private final Instant issuedAt;
    private final LocalDate dueDate;
    private ReceivableStatus status;
    private Instant settledAt;
    private final long version;

    public Receivable(UUID id, UUID orderId, UUID customerId, BigDecimal amount, BigDecimal paidAmount, String currency,
                      Instant issuedAt, LocalDate dueDate, ReceivableStatus status, Instant settledAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        this.status = Objects.requireNonNull(status, "status");
        this.settledAt = settledAt;
        this.version = version;
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A receivable is owed money");
        }
    }

    public static Receivable open(UUID orderId, UUID customerId, BigDecimal amount, String currency, Instant issuedAt,
                                  LocalDate dueDate) {
        return new Receivable(Identifiers.newId(), orderId, customerId, amount, BigDecimal.ZERO, currency, issuedAt,
                dueDate, ReceivableStatus.OPEN, null, 0L);
    }

    public BigDecimal outstanding() {
        return amount.subtract(paidAmount);
    }

    /**
     * Pays up to {@code offered} of what is still owed.
     *
     * @return what was applied: never more than the outstanding amount, so the rest stays with the
     *         transfer as the customer's credit
     */
    public BigDecimal allocate(BigDecimal offered, Instant now) {
        if (offered == null || offered.signum() <= 0 || status == ReceivableStatus.PAID) {
            return BigDecimal.ZERO;
        }
        BigDecimal applied = offered.min(outstanding());
        this.paidAmount = paidAmount.add(applied);
        if (paidAmount.compareTo(amount) == 0) {
            this.status = ReceivableStatus.PAID;
            this.settledAt = now;
        } else if (status != ReceivableStatus.OVERDUE) {
            this.status = ReceivableStatus.PARTIALLY_PAID;
        }
        return applied;
    }

    /** OPEN or PARTIALLY_PAID past its due date becomes OVERDUE (15 §4.3 step 4). */
    public boolean markOverdue(LocalDate today) {
        if ((status == ReceivableStatus.OPEN || status == ReceivableStatus.PARTIALLY_PAID) && dueDate.isBefore(today)) {
            this.status = ReceivableStatus.OVERDUE;
            return true;
        }
        return false;
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID customerId() { return customerId; }
    public BigDecimal amount() { return amount; }
    public BigDecimal paidAmount() { return paidAmount; }
    public String currency() { return currency; }
    public Instant issuedAt() { return issuedAt; }
    public LocalDate dueDate() { return dueDate; }
    public ReceivableStatus status() { return status; }
    public Instant settledAt() { return settledAt; }
    public long version() { return version; }
}
