package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.internal.domain.SalesContractStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code ordering.sales_contract} (4B-4). The delivery schedule and payment plan live in their own tables and are written
 * by the repository adapter in SQL, whole, while the contract is a draft: they have no identity worth tracking before the
 * contract is sent, and replacing them row by row through the persistence context would insert the new batch 1 before
 * the old one is gone.
 */
@Entity
@Table(name = "sales_contract", schema = "ordering")
public class SalesContractJpaEntity extends BaseEntity {

    @Column(name = "contract_number", nullable = false, updatable = false, length = 40)
    private String contractNumber;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SalesContractStatus status;

    @Column(name = "seller_name", nullable = false, updatable = false, length = 200)
    private String sellerName;
    @Column(name = "seller_tax_code", nullable = false, updatable = false, length = 14)
    private String sellerTaxCode;
    @Column(name = "seller_address", nullable = false, updatable = false, length = 500)
    private String sellerAddress;
    @Column(name = "seller_representative", nullable = false, updatable = false, length = 200)
    private String sellerRepresentative;
    @Column(name = "seller_representative_title", nullable = false, updatable = false, length = 100)
    private String sellerRepresentativeTitle;
    @Column(name = "buyer_name", nullable = false, updatable = false, length = 200)
    private String buyerName;
    @Column(name = "buyer_tax_code", updatable = false, length = 14)
    private String buyerTaxCode;
    @Column(name = "buyer_address", nullable = false, updatable = false, length = 500)
    private String buyerAddress;
    @Column(name = "buyer_representative", nullable = false, updatable = false, length = 200)
    private String buyerRepresentative;
    @Column(name = "buyer_representative_title", nullable = false, updatable = false, length = 100)
    private String buyerRepresentativeTitle;

    @Column(name = "free_sample_rounds", nullable = false)
    private int freeSampleRounds;

    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "sent_by")
    private UUID sentBy;
    @Column(name = "sent_document_key", length = 512)
    private String sentDocumentKey;

    @Column(name = "signed_on")
    private LocalDate signedOn;
    @Column(name = "signed_file_key", length = 512)
    private String signedFileKey;
    @Column(name = "signed_file_name", length = 255)
    private String signedFileName;
    @Column(name = "signed_at")
    private Instant signedAt;
    @Column(name = "signed_by")
    private UUID signedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancelled_by")
    private UUID cancelledBy;
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SalesContractJpaEntity() {
    }

    public SalesContractJpaEntity(UUID id, String contractNumber, UUID orderId, String sellerName, String sellerTaxCode,
                                  String sellerAddress, String sellerRepresentative, String sellerRepresentativeTitle,
                                  String buyerName, String buyerTaxCode, String buyerAddress, String buyerRepresentative,
                                  String buyerRepresentativeTitle) {
        super(id);
        this.contractNumber = contractNumber;
        this.orderId = orderId;
        this.sellerName = sellerName;
        this.sellerTaxCode = sellerTaxCode;
        this.sellerAddress = sellerAddress;
        this.sellerRepresentative = sellerRepresentative;
        this.sellerRepresentativeTitle = sellerRepresentativeTitle;
        this.buyerName = buyerName;
        this.buyerTaxCode = buyerTaxCode;
        this.buyerAddress = buyerAddress;
        this.buyerRepresentative = buyerRepresentative;
        this.buyerRepresentativeTitle = buyerRepresentativeTitle;
    }

    public void apply(SalesContractStatus status, int freeSampleRounds, Instant sentAt, UUID sentBy,
                      String sentDocumentKey, LocalDate signedOn, String signedFileKey, String signedFileName,
                      Instant signedAt, UUID signedBy, Instant cancelledAt, UUID cancelledBy,
                      String cancellationReason, Instant completedAt) {
        this.status = status;
        this.freeSampleRounds = freeSampleRounds;
        this.sentAt = sentAt;
        this.sentBy = sentBy;
        this.sentDocumentKey = sentDocumentKey;
        this.signedOn = signedOn;
        this.signedFileKey = signedFileKey;
        this.signedFileName = signedFileName;
        this.signedAt = signedAt;
        this.signedBy = signedBy;
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.cancellationReason = cancellationReason;
        this.completedAt = completedAt;
    }

    public String getContractNumber() { return contractNumber; }
    public UUID getOrderId() { return orderId; }
    public SalesContractStatus getStatus() { return status; }
    public String getSellerName() { return sellerName; }
    public String getSellerTaxCode() { return sellerTaxCode; }
    public String getSellerAddress() { return sellerAddress; }
    public String getSellerRepresentative() { return sellerRepresentative; }
    public String getSellerRepresentativeTitle() { return sellerRepresentativeTitle; }
    public String getBuyerName() { return buyerName; }
    public String getBuyerTaxCode() { return buyerTaxCode; }
    public String getBuyerAddress() { return buyerAddress; }
    public String getBuyerRepresentative() { return buyerRepresentative; }
    public String getBuyerRepresentativeTitle() { return buyerRepresentativeTitle; }
    public int getFreeSampleRounds() { return freeSampleRounds; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
    public String getSentDocumentKey() { return sentDocumentKey; }
    public LocalDate getSignedOn() { return signedOn; }
    public String getSignedFileKey() { return signedFileKey; }
    public String getSignedFileName() { return signedFileName; }
    public Instant getSignedAt() { return signedAt; }
    public UUID getSignedBy() { return signedBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCompletedAt() { return completedAt; }
}
