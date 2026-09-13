package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Currency;

/**
 * <b>Aggregate root of the procurement module: one purchase order to one supplier.</b>
 *
 * <p>SCRUM-113 (WBS 3.2.1) lands creation only — {@link #draft}, always born {@code DRAFT} with at
 * least one line. The full lifecycle (approve/send/receive/cancel/close) is
 * {@code docs/business-design/03-state-machines.md} §2's ten-state machine; this module's own
 * {@code PurchaseOrderStatus} deliberately implements a simplified seven-state subset of it
 * (no separate {@code PENDING_APPROVAL}/{@code CONFIRMED}/{@code RECEIVED} states — see that
 * enum's javadoc), which is the scope this sprint's stories (SCRUM-113/116/119) commit to.</p>
 *
 * <p>Invariants: at least one line; {@code totalAmount} is derived from the lines' totals and
 * cannot be set independently of them (same reasoning as {@code order.internal.domain.Order});
 * every line's currency must match the order's own.</p>
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
    private final long version;
    private final Instant createdAt;
    private final String createdBy;

    public PurchaseOrder(PurchaseOrderId id, String poNumber, UUID supplierId,
                         PurchaseOrderStatus status, Currency currency, List<PoLine> lines,
                         LocalDate expectedAt, long version, Instant createdAt, String createdBy) {
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
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    /** A new purchase order, always born {@link PurchaseOrderStatus#DRAFT}. */
    public static PurchaseOrder draft(String poNumber, UUID supplierId, Currency currency,
                                      List<PoLine> lines, LocalDate expectedAt) {
        return new PurchaseOrder(PurchaseOrderId.newId(), poNumber, supplierId,
                PurchaseOrderStatus.DRAFT, currency, lines, expectedAt, 0L, null, null);
    }

    public Money totalAmount() {
        Money total = Money.zero(currency);
        for (PoLine line : lines) {
            total = total.plus(line.lineTotal());
        }
        return total;
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
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
}
