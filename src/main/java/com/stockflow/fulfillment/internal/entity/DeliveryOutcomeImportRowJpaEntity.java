package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One line of a delivery-outcome file (table {@code fulfillment.delivery_outcome_import_row}): its cells as written
 * (cut to the column), the vehicle it was matched to, and what the check made of it - ACCEPTED, or ERROR with its
 * reason. What came of an accepted line is on the carrier event made from it, not here. Append-only.
 */
@Entity
@Table(name = "delivery_outcome_import_row", schema = "fulfillment")
public class DeliveryOutcomeImportRowJpaEntity extends BaseEntity {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String ERROR = "ERROR";

    @Column(name = "import_id", nullable = false, updatable = false) private UUID importId;
    @Column(name = "line_no", nullable = false, updatable = false) private int lineNo;
    @Column(name = "waybill_no", length = 255, updatable = false) private String waybillNo;
    @Column(name = "outcome", length = 255, updatable = false) private String outcome;
    @Column(name = "failure_reason", length = 500, updatable = false) private String failureReason;
    @Column(name = "reported_at", length = 255, updatable = false) private String reportedAt;
    @Column(name = "recipient", length = 255, updatable = false) private String recipient;
    @Column(name = "note", length = 500, updatable = false) private String note;
    @Column(name = "shipment_code", length = 255, updatable = false) private String shipmentCode;
    @Column(name = "load_id", updatable = false) private UUID loadId;
    @Column(name = "verdict", nullable = false, length = 16, updatable = false) private String verdict;
    @Column(name = "problem", length = 32, updatable = false) private String problem;

    protected DeliveryOutcomeImportRowJpaEntity() {
    }

    /** @param problem why the line was refused; null for an accepted one */
    @SuppressWarnings("java:S107")
    public DeliveryOutcomeImportRowJpaEntity(UUID id, UUID importId, int lineNo, String waybillNo, String outcome,
                                             String failureReason, String reportedAt, String recipient, String note,
                                             String shipmentCode, UUID loadId, String problem) {
        super(id);
        this.importId = importId;
        this.lineNo = lineNo;
        this.waybillNo = cut(waybillNo, 255);
        this.outcome = cut(outcome, 255);
        this.failureReason = cut(failureReason, 500);
        this.reportedAt = cut(reportedAt, 255);
        this.recipient = cut(recipient, 255);
        this.note = cut(note, 500);
        this.shipmentCode = cut(shipmentCode, 255);
        this.loadId = loadId;
        this.verdict = problem == null ? ACCEPTED : ERROR;
        this.problem = problem;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getImportId() { return importId; }
    public int getLineNo() { return lineNo; }
    public String getWaybillNo() { return waybillNo; }
    public String getOutcome() { return outcome; }
    public String getFailureReason() { return failureReason; }
    public String getReportedAt() { return reportedAt; }
    public String getRecipient() { return recipient; }
    public String getNote() { return note; }
    public String getShipmentCode() { return shipmentCode; }
    public UUID getLoadId() { return loadId; }
    public String getVerdict() { return verdict; }
    public String getProblem() { return problem; }
}
