package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.internal.domain.ContractAgreement;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.order.internal.domain.TermsReview;
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

    /** WF-05 TH-1 (Điều 318): the complaint period agreed; null on contracts drawn up before. */
    @Column(name = "return_window_days")
    private Integer returnWindowDays;

    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "sent_by")
    private UUID sentBy;

    /** V20270128000300 (14 BR-26): the customer's Sales owner when drawn up; written once, with the row. */
    @Column(name = "sales_owner_id", updatable = false)
    private UUID salesOwnerId;
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

    /** WF-05 Q-6 (V20261217000100): when the contract was liquidated. */
    @Column(name = "liquidated_at")
    private Instant liquidatedAt;

    /** 15 BR-11 (V20270101000100): PENDING while the draft waits for accounting to approve the customer's credit. */
    @Column(name = "credit_review", nullable = false, length = 16)
    private String creditReview = "NONE";

    /** V20270129000100: the article "Thoả thuận khác"; null when there is none. */
    @Column(name = "special_terms")
    private String specialTerms;
    /** The three standing terms when this contract's differ from the configured ones; null = the configured one. */
    @Column(name = "penalty_percent")
    private Integer penaltyPercent;
    @Column(name = "quantity_tolerance_percent")
    private Integer quantityTolerancePercent;
    @Column(name = "warranty_months")
    private Integer warrantyMonths;
    @Column(name = "seller_signer_name", length = 200)
    private String sellerSignerName;
    @Column(name = "seller_signer_title", length = 200)
    private String sellerSignerTitle;
    @Column(name = "seller_signer_authorization_ref", length = 100)
    private String sellerSignerAuthorizationRef;
    @Column(name = "buyer_signer_name", length = 200)
    private String buyerSignerName;
    @Column(name = "buyer_signer_title", length = 200)
    private String buyerSignerTitle;
    @Column(name = "non_standard_terms", nullable = false)
    private boolean nonStandardTerms;
    @Column(name = "revision", nullable = false)
    private int revision = 1;
    @Enumerated(EnumType.STRING)
    @Column(name = "terms_review", nullable = false, length = 16)
    private TermsReview termsReview = TermsReview.NONE;
    @Column(name = "terms_review_note", length = 500)
    private String termsReviewNote;
    @Column(name = "terms_review_requested_by")
    private UUID termsReviewRequestedBy;
    @Column(name = "terms_review_requested_at")
    private Instant termsReviewRequestedAt;
    @Column(name = "terms_reviewed_by")
    private UUID termsReviewedBy;
    @Column(name = "terms_reviewed_at")
    private Instant termsReviewedAt;
    @Column(name = "sign_by")
    private LocalDate signBy;
    @Column(name = "customer_signed_at")
    private Instant customerSignedAt;
    /** Written once, with the row. */
    @Column(name = "drawn_up_by", updatable = false)
    private UUID drawnUpBy;

    protected SalesContractJpaEntity() {
    }

    public SalesContractJpaEntity(UUID id, String contractNumber, UUID orderId, String sellerName, String sellerTaxCode,
                                  String sellerAddress, String sellerRepresentative, String sellerRepresentativeTitle,
                                  String buyerName, String buyerTaxCode, String buyerAddress, String buyerRepresentative,
                                  String buyerRepresentativeTitle, UUID salesOwnerId) {
        super(id);
        this.salesOwnerId = salesOwnerId;
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

    public void applyAgreement(ContractAgreement agreement, boolean nonStandard) {
        this.specialTerms = agreement.specialTerms();
        this.penaltyPercent = agreement.penaltyPercent();
        this.quantityTolerancePercent = agreement.quantityTolerancePercent();
        this.warrantyMonths = agreement.warrantyMonths();
        this.sellerSignerName = agreement.seller().name();
        this.sellerSignerTitle = agreement.seller().title();
        this.sellerSignerAuthorizationRef = agreement.seller().authorizationRef();
        this.buyerSignerName = agreement.buyer().name();
        this.buyerSignerTitle = agreement.buyer().title();
        this.nonStandardTerms = nonStandard;
    }

    public void applySigning(int revision, LocalDate signBy, Instant customerSignedAt) {
        this.revision = revision;
        this.signBy = signBy;
        this.customerSignedAt = customerSignedAt;
    }

    public void applyTermsReview(TermsReview state, String note, UUID requestedBy, Instant requestedAt, UUID reviewedBy,
                                 Instant reviewedAt) {
        this.termsReview = state;
        this.termsReviewNote = note;
        this.termsReviewRequestedBy = requestedBy;
        this.termsReviewRequestedAt = requestedAt;
        this.termsReviewedBy = reviewedBy;
        this.termsReviewedAt = reviewedAt;
    }

    public void drawnUpBy(UUID user) {
        this.drawnUpBy = user;
    }

    public void applyLiquidated(Instant at) {
        this.liquidatedAt = at;
    }

    public Instant getLiquidatedAt() { return liquidatedAt; }

    public void creditReview(boolean pending) {
        this.creditReview = pending ? "PENDING" : "NONE";
    }

    public boolean isCreditReviewPending() { return "PENDING".equals(creditReview); }
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
    public Integer getReturnWindowDays() { return returnWindowDays; }

    /** The complaint period as the contract states it; frozen with the rest once sent. */
    public void agreeReturnWindow(Integer days) {
        this.returnWindowDays = days;
    }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
    public UUID getSalesOwnerId() { return salesOwnerId; }
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
    public String getSpecialTerms() { return specialTerms; }
    public Integer getPenaltyPercent() { return penaltyPercent; }
    public Integer getQuantityTolerancePercent() { return quantityTolerancePercent; }
    public Integer getWarrantyMonths() { return warrantyMonths; }
    public String getSellerSignerName() { return sellerSignerName; }
    public String getSellerSignerTitle() { return sellerSignerTitle; }
    public String getSellerSignerAuthorizationRef() { return sellerSignerAuthorizationRef; }
    public String getBuyerSignerName() { return buyerSignerName; }
    public String getBuyerSignerTitle() { return buyerSignerTitle; }
    public boolean isNonStandardTerms() { return nonStandardTerms; }
    public int getRevision() { return revision; }
    public TermsReview getTermsReview() { return termsReview; }
    public String getTermsReviewNote() { return termsReviewNote; }
    public UUID getTermsReviewRequestedBy() { return termsReviewRequestedBy; }
    public Instant getTermsReviewRequestedAt() { return termsReviewRequestedAt; }
    public UUID getTermsReviewedBy() { return termsReviewedBy; }
    public Instant getTermsReviewedAt() { return termsReviewedAt; }
    public LocalDate getSignBy() { return signBy; }
    public Instant getCustomerSignedAt() { return customerSignedAt; }
    public UUID getDrawnUpBy() { return drawnUpBy; }
}
