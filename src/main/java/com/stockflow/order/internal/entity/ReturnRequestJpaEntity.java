package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.internal.domain.ReturnChannel;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code ordering.return_request} (V20260928005400, reshaped by V20261025020000). The opening is written once; the
 * review, receipt, inspection, resolution and end move. The lines are children written through it; photos,
 * discrepancies and the status history are append-only rows of their own.
 */
@Entity
@Table(name = "return_request", schema = "ordering")
public class ReturnRequestJpaEntity extends BaseEntity {

    @Column(name = "rma_number", nullable = false, length = 30, updatable = false)
    private String rmaNumber;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, length = 32, updatable = false)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private ReturnChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 24, updatable = false)
    private ReturnReason reasonCode;

    @Column(name = "note", length = 2000, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_resolution", nullable = false, length = 16, updatable = false)
    private ReturnResolution requestedResolution;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ReturnStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "return_required")
    private Boolean returnRequired;

    @Column(name = "return_deadline")
    private Instant returnDeadline;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "receiving_location_code", length = 64)
    private String receivingLocationCode;

    @Column(name = "receipt_note", length = 1000)
    private String receiptNote;

    @Column(name = "inspected_by")
    private UUID inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "inspection_note", length = 1000)
    private String inspectionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "money_mode", length = 16)
    private ReturnResolution moneyMode;

    @Column(name = "settlement_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal settlementAmount = BigDecimal.ZERO;

    @Column(name = "proposed_by")
    private UUID proposedBy;

    @Column(name = "proposed_at")
    private Instant proposedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "settled_by")
    private UUID settledBy;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "credited_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditedAmount = BigDecimal.ZERO;

    @Column(name = "offset_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal offsetAmount = BigDecimal.ZERO;

    @Column(name = "credit_transfer_id")
    private UUID creditTransferId;

    @Column(name = "replacement_order_id")
    private UUID replacementOrderId;

    @Column(name = "vat_adjustment_required", nullable = false)
    private boolean vatAdjustmentRequired;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** LAZY: a list reads the header only; the detail and every command load the lines with a fetch join. */
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id")
    private List<ReturnRequestLineJpaEntity> lines = new ArrayList<>();

    protected ReturnRequestJpaEntity() {
    }

    public ReturnRequestJpaEntity(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                  UUID requestId, ReturnChannel channel, ReturnReason reasonCode, String note,
                                  ReturnResolution requestedResolution, UUID requestedBy, Instant requestedAt,
                                  String currency) {
        super(id);
        this.rmaNumber = rmaNumber;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.requestId = requestId;
        this.channel = channel;
        this.reasonCode = reasonCode;
        this.note = note;
        this.requestedResolution = requestedResolution;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.currency = currency;
    }

    public void addLine(ReturnRequestLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public void applyStatus(ReturnStatus status) {
        this.status = status;
    }

    public void applyReview(UUID decidedBy, Instant decidedAt, String decisionNote, String rejectionReason,
                            Boolean returnRequired, Instant returnDeadline) {
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.rejectionReason = rejectionReason;
        this.returnRequired = returnRequired;
        this.returnDeadline = returnDeadline;
    }

    public void applyReceipt(UUID receivedBy, Instant receivedAt, String locationCode, String note) {
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
        this.receivingLocationCode = locationCode;
        this.receiptNote = note;
    }

    public void applyInspection(UUID inspectedBy, Instant inspectedAt, String note) {
        this.inspectedBy = inspectedBy;
        this.inspectedAt = inspectedAt;
        this.inspectionNote = note;
    }

    public void applyResolution(ReturnResolution moneyMode, BigDecimal settlementAmount, UUID proposedBy,
                                Instant proposedAt, String note, UUID settledBy, Instant settledAt,
                                BigDecimal refunded, BigDecimal credited, BigDecimal offset, UUID creditTransferId,
                                UUID replacementOrderId, boolean vatAdjustmentRequired) {
        this.moneyMode = moneyMode;
        this.settlementAmount = settlementAmount;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
        this.resolutionNote = note;
        this.settledBy = settledBy;
        this.settledAt = settledAt;
        this.refundedAmount = refunded;
        this.creditedAmount = credited;
        this.offsetAmount = offset;
        this.creditTransferId = creditTransferId;
        this.replacementOrderId = replacementOrderId;
        this.vatAdjustmentRequired = vatAdjustmentRequired;
    }

    public void applyEnd(UUID cancelledBy, String cancellationReason, Instant closedAt) {
        this.cancelledBy = cancelledBy;
        this.cancellationReason = cancellationReason;
        this.closedAt = closedAt;
    }

    public String getRmaNumber() { return rmaNumber; }
    public UUID getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getCustomerId() { return customerId; }
    public UUID getRequestId() { return requestId; }
    public ReturnChannel getChannel() { return channel; }
    public ReturnReason getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public ReturnResolution getRequestedResolution() { return requestedResolution; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getCurrency() { return currency; }
    public ReturnStatus getStatus() { return status; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public String getRejectionReason() { return rejectionReason; }
    public Boolean getReturnRequired() { return returnRequired; }
    public Instant getReturnDeadline() { return returnDeadline; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getReceivingLocationCode() { return receivingLocationCode; }
    public String getReceiptNote() { return receiptNote; }
    public UUID getInspectedBy() { return inspectedBy; }
    public Instant getInspectedAt() { return inspectedAt; }
    public String getInspectionNote() { return inspectionNote; }
    public ReturnResolution getMoneyMode() { return moneyMode; }
    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public UUID getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public UUID getSettledBy() { return settledBy; }
    public Instant getSettledAt() { return settledAt; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public BigDecimal getCreditedAmount() { return creditedAmount; }
    public BigDecimal getOffsetAmount() { return offsetAmount; }
    public UUID getCreditTransferId() { return creditTransferId; }
    public UUID getReplacementOrderId() { return replacementOrderId; }
    public boolean isVatAdjustmentRequired() { return vatAdjustmentRequired; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getClosedAt() { return closedAt; }
    public List<ReturnRequestLineJpaEntity> getLines() { return lines; }
}
