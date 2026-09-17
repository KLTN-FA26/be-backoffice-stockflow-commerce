package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Currency;

/**
 * <b>Aggregate root of the procurement module: one purchase order to one supplier.</b>
 *
 * <p>SCRUM-113 (WBS 3.2.1) lands creation — {@link #draft}, always born {@code DRAFT} with at
 * least one line. SCRUM-116 (WBS 3.2.4) adds the tracking lifecycle: {@link #approve}/
 * {@link #send}/{@link #cancel}/{@link #receiveGoods}/{@link #closeShort}, following
 * {@link PurchaseOrderStatus#canTransitionTo}'s table (receiving is the one edge that table does
 * not express — see its own javadoc). The full lifecycle is
 * {@code docs/business-design/03-state-machines.md} §2's ten-state machine; this module's own
 * {@code PurchaseOrderStatus} deliberately implements a simplified seven-state subset of it, which
 * is the scope this sprint's stories (SCRUM-113/116/119) commit to.</p>
 *
 * <p>Invariants: at least one line; {@code totalAmount} is derived from the lines' totals and
 * cannot be set independently of them (same reasoning as {@code order.internal.domain.Order});
 * every line's currency must match the order's own; {@link #cancel} is only reachable while
 * nothing has been received (BR-PO, "nothing received yet") — see its own javadoc for why the
 * state machine alone is enough to guarantee that, with no separate check needed.</p>
 *
 * <p>No Spring, no JPA, no annotations — {@code ArchitectureTest.domainDoesNotDependOnFrameworks}
 * enforces it.</p>
 */
public final class PurchaseOrder extends AggregateRoot {

    private final PurchaseOrderId id;
    private final String poNumber;
    private final UUID supplierId;
    private PurchaseOrderStatus status;
    private final Currency currency;
    private final List<PoLine> lines;
    private final LocalDate expectedAt;
    private String cancellationReason;
    private String closeShortReason;
    private final long version;
    private final Instant createdAt;
    private final String createdBy;

    public PurchaseOrder(PurchaseOrderId id, String poNumber, UUID supplierId,
                         PurchaseOrderStatus status, Currency currency, List<PoLine> lines,
                         LocalDate expectedAt, String cancellationReason, String closeShortReason,
                         long version, Instant createdAt, String createdBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.poNumber = requireNonBlank(poNumber, "poNumber");
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        this.status = Objects.requireNonNull(status, "status");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
        if (this.lines.isEmpty()) {
            throw new IllegalArgumentException("A purchase order must have at least one line");
        }
        for (PoLine line : this.lines) {
            if (!line.unitPrice().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Line currency " + line.unitPrice().currency() + " does not match order currency " + currency);
            }
        }
        this.expectedAt = expectedAt;
        this.cancellationReason = cancellationReason;
        this.closeShortReason = closeShortReason;
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    /** A new purchase order, always born {@link PurchaseOrderStatus#DRAFT}. */
    public static PurchaseOrder draft(String poNumber, UUID supplierId, Currency currency,
                                      List<PoLine> lines, LocalDate expectedAt) {
        return new PurchaseOrder(PurchaseOrderId.newId(), poNumber, supplierId,
                PurchaseOrderStatus.DRAFT, currency, lines, expectedAt, null, null, 0L, null, null);
    }

    public Money totalAmount() {
        Money total = Money.zero(currency);
        for (PoLine line : lines) {
            total = total.plus(line.lineTotal());
        }
        return total;
    }

    /** DRAFT -&gt; APPROVED. BR-PO-002 (approver's limit &gt;= PO total) is not enforced — see the
     *  class javadoc on {@code ProcurementServiceImpl.createPurchaseOrder} for why. */
    public void approve(Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.APPROVED);
        this.status = PurchaseOrderStatus.APPROVED;
    }

    /** APPROVED -&gt; SENT. */
    public void send(Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.SENT);
        this.status = PurchaseOrderStatus.SENT;
    }

    /**
     * DRAFT/APPROVED/SENT -&gt; CANCELLED.
     *
     * <p>No separate "nothing received yet" check is needed here: {@link #receiveGoods} always
     * moves the status to {@code PARTIALLY_RECEIVED} or {@code CLOSED} the instant anything is
     * received, and {@link PurchaseOrderStatus#canTransitionTo} does not permit cancelling from
     * either of those — so every state this method accepts already guarantees zero receipts.</p>
     */
    public void cancel(String reason, Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.CANCELLED);
        this.status = PurchaseOrderStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    /**
     * SCRUM-116/WBS 3.2.4.1. Advances each named line's {@code quantityReceived}, then rolls the
     * order up to {@link PurchaseOrderStatus#CLOSED} once every line's open quantity reaches zero,
     * or {@link PurchaseOrderStatus#PARTIALLY_RECEIVED} otherwise. Valid from {@code SENT} or
     * {@code PARTIALLY_RECEIVED} (a self-loop {@link PurchaseOrderStatus#canTransitionTo} cannot
     * express — see that method's javadoc).
     *
     * @param receivedByLineId how much arrived per {@link PoLine#id()}; a line not present in the
     *                         map is untouched
     */
    public void receiveGoods(Map<UUID, Integer> receivedByLineId, Instant now) {
        if (status != PurchaseOrderStatus.SENT && status != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new InvalidPurchaseOrderTransitionException(id,
                    "cannot receive goods while %s (must be SENT or PARTIALLY_RECEIVED)".formatted(status));
        }
        for (Map.Entry<UUID, Integer> entry : receivedByLineId.entrySet()) {
            PoLine line = lines.stream().filter(l -> l.id().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Line %s is not on purchase order %s".formatted(entry.getKey(), id)));
            line.receive(entry.getValue());
        }
        boolean fullyReceived = lines.stream().allMatch(line -> line.openQuantity() == 0);
        this.status = fullyReceived ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }

    /** PARTIALLY_RECEIVED -&gt; CLOSED_SHORT: the remaining open quantity is written off. */
    public void closeShort(String reason, Instant now) {
        requireCanTransitionTo(PurchaseOrderStatus.CLOSED_SHORT);
        this.status = PurchaseOrderStatus.CLOSED_SHORT;
        this.closeShortReason = reason;
    }

    private void requireCanTransitionTo(PurchaseOrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidPurchaseOrderTransitionException(id, status, target);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public PurchaseOrderId id() { return id; }
    public String poNumber() { return poNumber; }
    public UUID supplierId() { return supplierId; }
    public PurchaseOrderStatus status() { return status; }
    public Currency currency() { return currency; }
    public List<PoLine> lines() { return List.copyOf(lines); }
    public LocalDate expectedAt() { return expectedAt; }
    public String cancellationReason() { return cancellationReason; }
    public String closeShortReason() { return closeShortReason; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
}
