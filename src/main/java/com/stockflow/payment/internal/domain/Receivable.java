package com.stockflow.payment.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What is owed for one delivered credit order (kltn-docs 15 §4.3, §5.3; SCRUM-431): opened
 * at delivery, due on the delivery day plus the order's days to pay, paid by the customer's
 * transfers. The money received never exceeds what is owed ({@code ck_receivable_amounts}) and the
 * status always says what the money says ({@code ck_receivable_status_amounts}).
 *
 * <p>G2-3: also the balance of a deposit order the carrier collected on delivery ({@code DEPOSIT_BALANCE},
 * {@link Collector#CARRIER}, due on the delivery day), paid by the carrier's remittance (09 §4.3 step 4). A short
 * remittance leaves it PARTIALLY_PAID: the carrier's debt, and the order is not closed (09 edge "tiền thu hộ").</p>
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
    /** {@code CREDIT_ORDER} (a delivered credit order) or {@code CANCELLATION_FEE} (decision Q6a). */
    private String kind = "CREDIT_ORDER";
    /** G3-2: the delivered shipment of a split order this bills; null for a whole-order delivery or a fee. */
    private UUID shipmentId;
    /** G2-3: who pays it; CARRIER only for a deposit balance collected on delivery. */
    private Collector collector = Collector.CUSTOMER;

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

    /**
     * What a cancelled order's customer agreed to pay beyond the money already received (decision Q6a): owed from
     * the cancellation day. Opened, never collected: it is paid like any receivable, by a transfer the accountant
     * records and allocates.
     */
    public static Receivable openCancellationFee(UUID orderId, UUID customerId, BigDecimal amount, String currency,
                                                 Instant issuedAt, LocalDate dueDate) {
        Receivable fee = open(orderId, customerId, amount, currency, issuedAt, dueDate);
        fee.kind = "CANCELLATION_FEE";
        return fee;
    }

    /**
     * G2-3: the balance of a deposit order the carrier collected on delivery (kltn-docs 09 §2, 15 BR-08): due on the
     * delivery day, paid by the carrier's remittance, never by the customer's transfers.
     */
    public static Receivable openCarrierCollected(UUID orderId, UUID customerId, BigDecimal amount, String currency,
                                                  Instant deliveredAt, LocalDate deliveryDay) {
        Receivable balance = open(orderId, customerId, amount, currency, deliveredAt, deliveryDay);
        balance.kind = "DEPOSIT_BALANCE";
        balance.collector = Collector.CARRIER;
        return balance;
    }

    public Receivable collectedBy(Collector who) {
        this.collector = Objects.requireNonNull(who, "collector");
        return this;
    }

    public Collector collector() { return collector; }

    public boolean carrierCollected() { return collector == Collector.CARRIER; }

    public Receivable withKind(String kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        return this;
    }

    public String kind() { return kind; }

    public Receivable forShipment(UUID shipment) {
        this.shipmentId = shipment;
        return this;
    }

    public UUID shipmentId() { return shipmentId; }

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

    /**
     * Takes back {@code amount} of what was paid: an allocation reversed by the accountant (15 BR-06). The status
     * follows the money again: unpaid past its due date is OVERDUE, part paid PARTIALLY_PAID, nothing paid OPEN.
     */
    public void unallocate(BigDecimal amount, LocalDate today) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(paidAmount) > 0) {
            throw new IllegalStateException("Receivable %s cannot give back %s of the %s paid".formatted(id, amount,
                    paidAmount));
        }
        this.paidAmount = paidAmount.subtract(amount);
        this.settledAt = null;
        if (dueDate.isBefore(today)) {
            this.status = ReceivableStatus.OVERDUE;
        } else {
            this.status = paidAmount.signum() == 0 ? ReceivableStatus.OPEN : ReceivableStatus.PARTIALLY_PAID;
        }
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
