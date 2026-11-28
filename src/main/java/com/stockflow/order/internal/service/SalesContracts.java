package com.stockflow.order.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sales contract of an order (4B-4; kltn-docs 14 §4.3 steps 11-17, §5.2, BR-12, BR-13, BR-14, BR-18, BR-23; 15
 * BR-11, BR-12, BR-18; 18 BR-05). Sales draws it up for a draft order, edits its delivery schedule, payment plan and
 * free print-sample rounds while it is a draft, sends it - the PDF the customer receives is kept - records it signed
 * with the signed file, or cancels it with a reason to draw up another. Accounting and the Sales Manager read it; the
 * customer reads a contract once it is sent.
 *
 * <p>A contract whose credit check fails when it is sent is not sent: it waits for accounting (15 BR-11 as changed on
 * 2026-11-26), who approves it - and it is sent at once - or refuses it back to Sales, who change the plan and send it
 * again; Sales may withdraw it while it waits.</p>
 */
public interface SalesContracts {

    /**
     * What Sales enters. On drawing up, anything left out starts from the defaults: the configured free sample rounds,
     * one delivery batch of every carton in the window the customer asked for, the customer's default payment schedule.
     * On an edit, the schedule and the plan are replaced whole.
     */
    record Draft(Integer freeSampleRounds, List<Batch> deliverySchedule, List<Installment> paymentPlan,
                 Integer returnWindowDays, AgreementInput agreement) {

        /** The complaint period left as it is: the configured default on drawing up, kept on an edit. */
        public Draft(Integer freeSampleRounds, List<Batch> deliverySchedule, List<Installment> paymentPlan) {
            this(freeSampleRounds, deliverySchedule, paymentPlan, null, null);
        }

        /** Nothing agreed beyond the standard terms. */
        public Draft(Integer freeSampleRounds, List<Batch> deliverySchedule, List<Installment> paymentPlan,
                     Integer returnWindowDays) {
            this(freeSampleRounds, deliverySchedule, paymentPlan, returnWindowDays, null);
        }
    }

    /**
     * What Sales enters beyond the schedule and the plan. Left out (null), a part is kept as it is on an edit; on drawing
     * up it starts empty. A blank text clears the special terms or a signer's name; a penalty, tolerance or warranty equal
     * to the configured one is the same as none. The signers are replaced whole when given.
     */
    record AgreementInput(String specialTerms, Integer penaltyPercent, Integer quantityTolerancePercent,
                          Integer warrantyMonths, SellerSigner sellerSigner, BuyerSigner buyerSigner) { }

    /** The seller's signer; {@code authorizationRef} is the number of the authorization when they are not the legal representative. */
    record SellerSigner(String name, String title, String authorizationRef) { }

    record BuyerSigner(String name, String title) { }

    /** The terms every contract starts from, for the editor to show beside an override. */
    record StandardTermsView(int penaltyPercent, int quantityTolerancePercent, int warrantyMonths) { }

    /**
     * Where the terms review (QĐ-4) stands, beyond the state itself.
     *
     * @param note the manager's note, or the reason it was rejected; null when none
     */
    record TermsReviewView(String note, UUID requestedBy, Instant requestedAt, UUID decidedBy, Instant decidedAt) { }

    /**
     * One revision of the contract as it was sent. {@code sentBy} and {@code sentByName} are not shown to the customer.
     *
     * @param changeRequestReason what the customer asked for after reading it; null while it stands
     */
    record RevisionView(int revision, Instant sentAt, UUID sentBy, String sentByName, String changeRequestReason,
                        Instant changeRequestedAt, boolean documentAvailable) { }

    /**
     * A filter of the back office's contract list.
     *
     * @param statuses     none = every status
     * @param salesOwnerId the Sales owner recorded on the contract; the caller's own when {@code mine}
     * @param signByBefore SENT contracts whose signing deadline is before this day
     * @param search       a part of the contract's or the order's number, or of the buyer's name
     */
    record ContractQuery(Set<String> statuses, UUID salesOwnerId, LocalDate signByBefore, String termsReview,
                         String search, int page, int size) { }

    /** A row of the contract list. */
    record ContractRow(UUID orderId, UUID contractId, String contractNumber, String orderNumber, UUID customerId,
                       String customerName, String status, int revision, BigDecimal totalAmount, Instant sentAt,
                       LocalDate signBy, boolean overdueToSign, Instant customerSignedAt, Instant signedAt,
                       String termsReview, String creditReview, UUID salesOwnerId, String salesOwnerName) { }

    /** The tiles over the list: how many contracts in each status and what is waiting. */
    record ContractSummary(Map<String, Long> byStatus, long awaitingCompanySignature, long overdueToSign,
                           long termsReviewPending) { }

    /** A delivery batch as entered, in delivery order. */
    record Batch(LocalDate windowStart, LocalDate windowEnd, List<BatchLine> lines) { }

    /** Cartons of one order line in a batch. */
    record BatchLine(UUID orderLineId, Integer cartons) { }

    /**
     * An installment as entered, in order: an amount or a percent of the order's total with tax.
     *
     * @param milestone        ON_SIGNING, BEFORE_DELIVERY or AFTER_DELIVERY
     * @param deliveryBatchSeq the delivery batch it is tied to, by number; never for ON_SIGNING
     */
    record Installment(String name, String milestone, BigDecimal amount, BigDecimal percent, Integer dueDays,
                       Integer deliveryBatchSeq) { }

    /** A party as the contract names it. */
    record Party(String name, String taxCode, String address, String representative, String representativeTitle) { }

    /** The order's money as the contract shows it - the order's, never stored twice (14 BR-12). */
    record Totals(BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount, String currency) { }

    /** @param value the batch's goods with their VAT: what an installment tied to it starts from (14 BR-13) */
    record BatchView(int seq, LocalDate windowStart, LocalDate windowEnd, BigDecimal value, List<BatchLineView> lines) { }

    /**
     * @param units          the pieces: cartons x the line's pieces per carton
     * @param unitsPerCarton R5-3: the line's pieces per carton; null for a line not ordered in cartons
     * @param designName     R5-3: the name of the design the customer's SKU was made to; null when it has none
     * @param previewUrl     R5-3: a short-lived link showing that design's preview; null when there is none
     * @param productName    the name of the product the SKU belongs to, so a schedule line reads as more than a code;
     *                       null for a SKU the catalogue does not know
     * @param variantName    the SKU's own (variant) name; null for an unknown SKU
     */
    record BatchLineView(UUID orderLineId, String sku, int cartons, int units, Integer unitsPerCarton,
                         String designName, String previewUrl, String productName, String variantName) {

        public BatchLineView(UUID orderLineId, String sku, int cartons, int units) {
            this(orderLineId, sku, cartons, units, null, null, null, null, null);
        }

        /** Without the SKU's names. */
        public BatchLineView(UUID orderLineId, String sku, int cartons, int units, Integer unitsPerCarton,
                             String designName, String previewUrl) {
            this(orderLineId, sku, cartons, units, unitsPerCarton, designName, previewUrl, null, null);
        }
    }

    /**
     * @param dueDate     R5-12: when the instalment made from this row falls due; null before the contract is signed
     *                    and while its milestone has not been reached
     * @param daysOverdue R5-12: days past {@code dueDate} it is still owing; 0 when not overdue, and before signing
     */
    record InstallmentView(int seq, String name, String milestone, BigDecimal percent, BigDecimal amount,
                           Integer dueDays, Integer deliveryBatchSeq, LocalDate dueDate, int daysOverdue) {

        public InstallmentView(int seq, String name, String milestone, BigDecimal percent, BigDecimal amount,
                               Integer dueDays, Integer deliveryBatchSeq) {
            this(seq, name, milestone, percent, amount, dueDays, deliveryBatchSeq, null, 0);
        }
    }

    /**
     * 15 BR-11: what a plan paying after delivery asks of the customer's credit, when it does not fit. A draft carries
     * it so Sales see before sending that the contract will wait for accounting (or change the plan instead); sending
     * it then opens a credit review rather than sending it (2026-11-26). Checked again at signing.
     *
     * @param outcome OVERDUE (the customer owes something overdue) or OVER_LIMIT
     */
    record CreditWarning(String outcome, BigDecimal creditLimit, BigDecimal exposure, BigDecimal afterDelivery) { }

    /**
     * 15 BR-11 (2026-11-26): one submission of the contract to accounting before it was sent, with the figures decided
     * on as they were then.
     *
     * @param outcome         PENDING, APPROVED, REJECTED or WITHDRAWN
     * @param checkOutcome    OVERDUE or OVER_LIMIT: why it needed accounting
     * @param contractVersion the version of the contract Sales sent for review
     * @param note            the approver's note, the reason it was refused, or why it was withdrawn; null when none
     */
    record CreditReviewView(UUID id, String outcome, String checkOutcome, BigDecimal creditLimit, BigDecimal exposure,
                            BigDecimal afterDelivery, BigDecimal overdueAmount, long contractVersion,
                            Instant submittedAt, UUID submittedBy, Instant decidedAt, UUID decidedBy, String note) { }

    /**
     * A contract waiting for accounting (15 BR-11), for accounting's queue.
     *
     * @param contractVersion the contract's version now: what an approval or a refusal is sent with
     */
    record CreditReviewWaiting(UUID reviewId, UUID orderId, String orderNumber, UUID contractId, String contractNumber,
                               long contractVersion, UUID customerId, String checkOutcome, BigDecimal creditLimit,
                               BigDecimal exposure, BigDecimal afterDelivery, BigDecimal overdueAmount,
                               UUID submittedBy, Instant submittedAt) { }

    /**
     * A plan suggested for the draft contract {@code contractId} read at {@code version}: what {@code paymentPlan}
     * would be, adding up to {@code totalAmount}, the order's total with tax.
     */
    record PlanSuggestion(UUID contractId, long version, BigDecimal totalAmount, List<InstallmentView> paymentPlan) { }

    /**
     * What inspecting a signed file found (the plan's "Signature view"). {@code kind} is DIGITAL or SCAN; a file this
     * could not read is never stored, so UNREADABLE does not appear here.
     *
     * @param reason the first thing wrong, a {@code SignedPdfInspector.Reasons} code; null when nothing is
     */
    record InspectionView(String kind, boolean valid, boolean coversWholeDocument, boolean matchesSentDocument,
                          boolean chainChecked, List<InspectedSigner> signers, String reason) { }

    record InspectedSigner(String subject, String issuer, String serial, Instant notBefore, Instant notAfter,
                           Instant signedAt) { }

    /**
     * How the customer signed. {@code ip} is the back office's only: the portal shows the customer their own signature
     * without it.
     */
    record CustomerSignatureView(String method, String signerName, String signerTitle, String email, Instant signedAt,
                                 String ip, boolean otpVerified, InspectionView inspection) { }

    /** How the company signed; {@code byName} is the account that did it. */
    record CompanySignatureView(String method, String signerName, String signerTitle, Instant signedAt, String byName,
                                String certificateSubject, String certificateIssuer, boolean testCertificate,
                                InspectionView inspection) { }

    /** Both signatures of the contract's current revision, with the SHA-256 of what each side signed and the code. */
    record SignatureView(CustomerSignatureView customer, CompanySignatureView company, String documentSha256,
                         String verificationCode) { }

    /**
     * @param creditWarning    what the plan's part paid after delivery would ask of the customer's credit, when it does
     *                         not fit (15 BR-10, BR-11): shown on a DRAFT contract - read, drawn up or edited - so Sales
     *                         decide before sending, as 14 §4.3 step 13 has it (change the plan, or send it to wait for
     *                         accounting); null otherwise, and never on the customer's copy
     * @param returnWindowDays the complaint period agreed, in calendar days from each delivery (Luật Thương mại Điều
     *                         318; WF-05 TH-1); null on a contract drawn up before it was written down, whose returns
     *                         use the configured default
     * @param appendixAvailable whether the contract has an appendix to download - exactly when
     *                         {@code GET .../contract/appendix} answers 200 ({@code ContractPaperService}): signed, and a
     *                         delivery window in force differs from the one signed (17 BR-28). Without it the back
     *                         office and the portal would show the button on every signed contract and explain a 404
     * @param creditReview     15 BR-11 (2026-11-26): PENDING while the draft waits for accounting - a send whose credit
     *                         check failed answers with it, the contract still a DRAFT - and NONE otherwise; NONE on the
     *                         customer's copy
     * @param latestCreditReview the latest submission to accounting, whatever became of it - a refusal's reason is read
     *                         here; null when there was none, and on the customer's copy
     * @param revision         starts at 1; each change request of the customer reopens the contract as the next one
     * @param signBy           the day the customer should have signed by; null for a draft and for a contract sent
     *                         before deadlines existed
     * @param overdueToSign    SENT and past {@code signBy}: nothing is cancelled for it, Sales extends or cancels
     * @param penaltyPercent   this contract's penalty when it differs from the standard one; null = the standard
     *                         ({@code standardTerms}); likewise the tolerance and the warranty
     * @param standardTerms    the configured terms a null override reads; null on the customer's copy
     * @param nonStandardTerms whether it deviates from them: a Sales Manager approves it before it is sent; false on the
     *                         customer's copy
     * @param termsReview      NONE, PENDING, APPROVED or REJECTED (QĐ-4); NONE on the customer's copy
     * @param termsReviewDetail who asked, who decided, and the note; null on the customer's copy
     * @param customerSignedAt when the customer signed; null until they do
     * @param canSign          the customer may sign now: SENT and not past {@code signBy}
     * @param canRequestChanges the customer may send it back for changes: SENT
     * @param customerSignature the evidence of the customer's signature - filled by the signing machinery, null until it
     *                         exists
     * @param companySignature the evidence of the company's signature - likewise
     * @param verificationCode the code a third party checks the signed contract with - likewise
     */
    record ContractView(UUID id, String contractNumber, UUID orderId, String orderNumber, String status, Party seller,
                        Party buyer, int freeSampleRounds, Totals totals, List<BatchView> deliverySchedule,
                        List<InstallmentView> paymentPlan, Instant sentAt, UUID sentBy, LocalDate signedOn,
                        String signedFileName, Instant signedAt, UUID signedBy, Instant cancelledAt,
                        UUID cancelledBy, String cancellationReason, Instant completedAt, long version,
                        CreditWarning creditWarning, Integer returnWindowDays, boolean appendixAvailable,
                        String creditReview, CreditReviewView latestCreditReview, int revision, LocalDate signBy,
                        boolean overdueToSign, String specialTerms, Integer penaltyPercent,
                        Integer quantityTolerancePercent, Integer warrantyMonths, SellerSigner sellerSigner,
                        BuyerSigner buyerSigner, StandardTermsView standardTerms, boolean nonStandardTerms,
                        String termsReview, TermsReviewView termsReviewDetail, Instant customerSignedAt,
                        boolean canSign, boolean canRequestChanges, CustomerSignatureView customerSignature,
                        CompanySignatureView companySignature, String verificationCode) {

        /** The same contract with the evidence of its signatures filled in: the signing steps know it only after the state changed. */
        public ContractView withSignatures(CustomerSignatureView customer, CompanySignatureView company, String code) {
            return new ContractView(
                    id, contractNumber, orderId, orderNumber, status, seller, buyer, freeSampleRounds, totals,
                    deliverySchedule, paymentPlan, sentAt, sentBy, signedOn, signedFileName, signedAt, signedBy,
                    cancelledAt, cancelledBy, cancellationReason, completedAt, version, creditWarning,
                    returnWindowDays, appendixAvailable, creditReview, latestCreditReview, revision, signBy,
                    overdueToSign, specialTerms, penaltyPercent, quantityTolerancePercent, warrantyMonths,
                    sellerSigner, buyerSigner, standardTerms, nonStandardTerms, termsReview, termsReviewDetail,
                    customerSignedAt, canSign, canRequestChanges, customer, company, code);
        }

        /** Before the contract's content and signing were looked at: a view drawn for a paper, or a test. */
        public ContractView(UUID id, String contractNumber, UUID orderId, String orderNumber, String status,
                            Party seller, Party buyer, int freeSampleRounds, Totals totals,
                            List<BatchView> deliverySchedule, List<InstallmentView> paymentPlan, Instant sentAt,
                            UUID sentBy, LocalDate signedOn, String signedFileName, Instant signedAt, UUID signedBy,
                            Instant cancelledAt, UUID cancelledBy, String cancellationReason, Instant completedAt,
                            long version, CreditWarning creditWarning, Integer returnWindowDays,
                            boolean appendixAvailable, String creditReview, CreditReviewView latestCreditReview) {
            this(id, contractNumber, orderId, orderNumber, status, seller, buyer, freeSampleRounds, totals,
                    deliverySchedule, paymentPlan, sentAt, sentBy, signedOn, signedFileName, signedAt, signedBy,
                    cancelledAt, cancelledBy, cancellationReason, completedAt, version, creditWarning,
                    returnWindowDays, appendixAvailable, creditReview, latestCreditReview, 1, null, false, null, null,
                    null, null, null, null, null, false, "NONE", null, null, false, false, null, null, null);
        }

        /** Before the credit review is looked at: a view drawn for a paper, or a test. */
        public ContractView(UUID id, String contractNumber, UUID orderId, String orderNumber, String status,
                            Party seller, Party buyer, int freeSampleRounds, Totals totals,
                            List<BatchView> deliverySchedule, List<InstallmentView> paymentPlan, Instant sentAt,
                            UUID sentBy, LocalDate signedOn, String signedFileName, Instant signedAt, UUID signedBy,
                            Instant cancelledAt, UUID cancelledBy, String cancellationReason, Instant completedAt,
                            long version, CreditWarning creditWarning, Integer returnWindowDays,
                            boolean appendixAvailable) {
            this(id, contractNumber, orderId, orderNumber, status, seller, buyer, freeSampleRounds, totals,
                    deliverySchedule, paymentPlan, sentAt, sentBy, signedOn, signedFileName, signedAt, signedBy,
                    cancelledAt, cancelledBy, cancellationReason, completedAt, version, creditWarning,
                    returnWindowDays, appendixAvailable, "NONE", null);
        }

        /** Before its appendix is looked at: a view drawn for a paper, or a test. */
        public ContractView(UUID id, String contractNumber, UUID orderId, String orderNumber, String status,
                            Party seller, Party buyer, int freeSampleRounds, Totals totals,
                            List<BatchView> deliverySchedule, List<InstallmentView> paymentPlan, Instant sentAt,
                            UUID sentBy, LocalDate signedOn, String signedFileName, Instant signedAt, UUID signedBy,
                            Instant cancelledAt, UUID cancelledBy, String cancellationReason, Instant completedAt,
                            long version, CreditWarning creditWarning, Integer returnWindowDays) {
            this(id, contractNumber, orderId, orderNumber, status, seller, buyer, freeSampleRounds, totals,
                    deliverySchedule, paymentPlan, sentAt, sentBy, signedOn, signedFileName, signedAt, signedBy,
                    cancelledAt, cancelledBy, cancellationReason, completedAt, version, creditWarning,
                    returnWindowDays, false);
        }

        /** With no complaint period written down. */
        public ContractView(UUID id, String contractNumber, UUID orderId, String orderNumber, String status,
                            Party seller, Party buyer, int freeSampleRounds, Totals totals,
                            List<BatchView> deliverySchedule, List<InstallmentView> paymentPlan, Instant sentAt,
                            UUID sentBy, LocalDate signedOn, String signedFileName, Instant signedAt, UUID signedBy,
                            Instant cancelledAt, UUID cancelledBy, String cancellationReason, Instant completedAt,
                            long version, CreditWarning creditWarning) {
            this(id, contractNumber, orderId, orderNumber, status, seller, buyer, freeSampleRounds, totals,
                    deliverySchedule, paymentPlan, sentAt, sentBy, signedOn, signedFileName, signedAt, signedBy,
                    cancelledAt, cancelledBy, cancellationReason, completedAt, version, creditWarning, null, false);
        }
    }

    /**
     * Draws up the order's contract (14 §4.3 step 11).
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (no such order); {@code SALES_CONTRACT_EXISTS};
     *         {@code SALES_CONTRACT_ORDER_NOT_DRAFT}; {@code SALES_CONTRACT_ORDER_NOT_CARTONS};
     *         {@code CUSTOMER_LEGAL_PROFILE_MISSING}; {@code SELLER_PROFILE_UNAVAILABLE};
     *         {@code DELIVERY_SCHEDULE_INVALID}; {@code PAYMENT_PLAN_INVALID}; {@code PAYMENT_SCHEDULE_NEEDS_CREDIT}
     */
    ContractView create(CurrentUser user, UUID orderId, Draft draft);

    /** The order's live contract. @throws com.stockflow.common.error.BusinessException {@code SALES_CONTRACT_NOT_FOUND} */
    ContractView get(UUID orderId);

    /** Every contract the order had, the cancelled ones too, newest first. */
    List<ContractView> history(UUID orderId);

    /**
     * {@link #history} for the system itself, outside any request: the chat's contract card follows the contract from
     * an event listener, where no caller and so no data scope exists. Never for an endpoint.
     */
    List<ContractView> historyForSystem(UUID orderId);

    /** A draft revised against the version read (14 §4.3 step 16). */
    ContractView edit(CurrentUser user, UUID orderId, long version, Draft draft);

    /**
     * The payment plan a draft contract would have with every installment tied to a delivery batch suggested again
     * from the batches as they are now (14 §4.3 step 12, BR-13; DS-02b): {@code ContractTerms.suggestTied}. Read only:
     * Sales takes it - or changes it - with {@link #edit}, at {@code version}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code SALES_CONTRACT_NOT_FOUND};
     *         {@code SALES_CONTRACT_STATUS_INVALID} once sent; {@code PAYMENT_PLAN_INVALID};
     *         {@code PAYMENT_SCHEDULE_NEEDS_CREDIT}
     */
    PlanSuggestion suggestPaymentPlan(UUID orderId);

    /**
     * Sent to the customer (14 §4.3 step 13) - unless the credit check of its plan fails (OVERDUE or OVER_LIMIT, 15
     * BR-11 as changed on 2026-11-26): it is then not sent but waits for accounting, a DRAFT with {@code creditReview}
     * PENDING and no PDF drawn, and accounting is told.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_ACTIVE} (409) when the customer is no
     *         longer active (Q-9): a contract already sent is still signed, a new one is not sent;
     *         {@code CONTRACT_CREDIT_REVIEW_PENDING} when it waits already
     */
    ContractView send(CurrentUser user, UUID orderId, long version);

    /**
     * 15 BR-11: accounting approves the customer's credit for a contract waiting, with a note, and it is sent at once
     * (decision 1 of the credit plan) as the Sales account that asked. Checked again as a send is, but not against the
     * credit: that is what was just decided. Whoever asked does not decide.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_CREDIT_REVIEW_NOT_PENDING};
     *         {@code CONTRACT_CREDIT_REVIEW_SELF}; {@code VALIDATION_FAILED} without a note; as {@link #send}
     */
    ContractView approveCredit(CurrentUser user, UUID orderId, long version, String note);

    /**
     * 15 BR-11: accounting refuses, with the reason; the contract stays a DRAFT for Sales to change and send again.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_CREDIT_REVIEW_NOT_PENDING};
     *         {@code CONTRACT_CREDIT_REVIEW_SELF}; {@code VALIDATION_FAILED} without a reason
     */
    ContractView refuseCredit(CurrentUser user, UUID orderId, long version, String reason);

    /**
     * 15 BR-11: Sales takes a contract waiting back from accounting, to change it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_CREDIT_REVIEW_NOT_PENDING}
     */
    ContractView withdrawCreditReview(CurrentUser user, UUID orderId, long version);

    /** 15 BR-11: the contracts waiting for accounting, the oldest first. */
    com.stockflow.common.api.PageResponse<CreditReviewWaiting> creditReviewsWaiting(int page, int size);

    /**
     * QĐ-4: Sales puts a draft that deviates from the standard terms in front of a Sales Manager. It stays a DRAFT, locked
     * against edits, until it is approved, rejected or withdrawn; a manager and the Sales Manager role are told.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_TERMS_REVIEW_NOT_NEEDED} (standard terms);
     *         {@code CONTRACT_TERMS_REVIEW_PENDING}; {@code CONTRACT_CREDIT_REVIEW_PENDING};
     *         {@code CUSTOMER_OWNED_BY_OTHER_SALES} (strict mode)
     */
    ContractView submitForTermsReview(CurrentUser user, UUID orderId, long version);

    /**
     * QĐ-4: a Sales Manager approves the terms as written; the contract may now be sent. The note is optional. Not a
     * Sales-owner step: any manager decides, whoever owns the customer.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_TERMS_REVIEW_NOT_PENDING}
     */
    ContractView approveTerms(CurrentUser user, UUID orderId, long version, String note);

    /**
     * QĐ-4: a Sales Manager rejects the terms, with the reason; the draft goes back to Sales.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_TERMS_REVIEW_NOT_PENDING};
     *         {@code VALIDATION_FAILED} without a reason
     */
    ContractView rejectTerms(CurrentUser user, UUID orderId, long version, String reason);

    /** QĐ-4: Sales takes the draft back from the Sales Manager to change it (the Sales owner's step). */
    ContractView withdrawTermsReview(CurrentUser user, UUID orderId, long version);

    /**
     * Moves the day the customer should have signed by, while the contract is SENT: today or later, at most 60 days ahead.
     * The Sales owner's step.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SALES_CONTRACT_STATUS_INVALID}; {@code VALIDATION_FAILED}
     */
    ContractView extendSignBy(CurrentUser user, UUID orderId, long version, LocalDate signBy);

    /** The revisions of the live contract that were sent, oldest first, with what the customer asked for after each. */
    List<RevisionView> revisions(UUID orderId);

    /** A link to the PDF the live contract's revision was sent as. */
    DownloadLink revisionDocument(UUID orderId, int revision);

    /**
     * The customer sends a contract they read back for changes, with a reason of 10 to 2000 characters: SENT → DRAFT as
     * the next revision, the order back to DRAFT, the PDF sent kept as that revision's; the responsible Sales is told.
     * Someone else's order, or a contract not sent, is 404.
     *
     * @param customerId who is asking, resolved from the sign-in; {@code userId} is their account
     * @throws com.stockflow.common.error.BusinessException {@code SALES_CONTRACT_STATUS_INVALID} unless SENT (also when
     *         the customer has signed already); {@code OPTIMISTIC_LOCK}; {@code VALIDATION_FAILED} for the reason
     */
    ContractView requestChanges(UUID customerId, UUID userId, UUID orderId, long version, String reason);

    /** The customer's own order's contract revisions that were sent, with their change requests; no sender is named. */
    List<RevisionView> customerRevisions(UUID customerId, UUID orderId);

    /** A link to the PDF an earlier or the current revision was sent as, for the customer. */
    DownloadLink customerRevisionDocument(UUID customerId, UUID orderId, int revision);

    /** The contracts of every order, newest first (the back office's contract page). */
    com.stockflow.common.api.PageResponse<ContractRow> list(ContractQuery query);

    /** How many contracts are in each status and what is waiting, for the tiles over the list. */
    ContractSummary summary();

    /**
     * The seam of the signing machinery, customer side: the customer signed - they proved who they are and agreed to the
     * contract in the steps the caller took - so SENT → CUSTOMER_SIGNED. Joins the caller's transaction, so the proof
     * (the code consumed, the evidence written) and the state change stand or fall together. The order is not touched: it
     * still waits for the company (QĐ-1). The same order-then-contract lock as every change to a contract. Someone else's
     * order, or a contract not sent, is 404.
     *
     * @param version the version the customer read
     * @param signedAt when they signed - the time the evidence carries - or null for now; never after now
     * @return the contract as the portal shows it
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_NOT_SIGNABLE};
     *         {@code CONTRACT_SIGN_DEADLINE_PASSED}; {@code OPTIMISTIC_LOCK}
     */
    ContractView customerSigned(UUID customerId, UUID userId, UUID orderId, long version, Instant signedAt);

    /**
     * What the signing machinery reads of a contract, read-only: the contract's identity and revision, the PDF that was
     * sent, who drew it up and sent it, and the signers it names (the seller's and buyer's own, else the legal
     * representatives).
     */
    record SigningFacts(UUID contractId, String contractNumber, UUID orderId, String orderNumber, UUID customerId,
                        int revision, long version, String status, String sentDocumentKey, LocalDate signBy,
                        String sellerName, String buyerName, String sellerSignerName, String sellerSignerTitle,
                        String buyerSignerName, String buyerSignerTitle) { }

    /**
     * The facts of the signed-in customer's own contract for one of their signing steps. Someone else's order, or a
     * contract not sent, is 404.
     *
     * @param version         the version the customer read; null to skip the check
     * @param requireSignable refuse a contract the customer may not sign now: not sent, or past its signing deadline
     * @throws com.stockflow.common.error.BusinessException {@code CONTRACT_NOT_SIGNABLE},
     *         {@code CONTRACT_SIGN_DEADLINE_PASSED}, {@code OPTIMISTIC_LOCK}
     */
    SigningFacts customerSigningFacts(UUID customerId, UUID orderId, Long version, boolean requireSignable);

    /**
     * The facts of any customer's contract for the company's signing steps, whatever the caller's data scope (the signer
     * is a manager, as {@link #companySigned}): the customer has signed, and the caller is not the account that drew it
     * up or sent it.
     *
     * @param version the version the signer read; null to skip the check
     * @throws com.stockflow.common.error.BusinessException {@code SALES_CONTRACT_STATUS_INVALID},
     *         {@code CONTRACT_SIGNER_IS_AUTHOR}, {@code OPTIMISTIC_LOCK}
     */
    SigningFacts companySigningFacts(CurrentUser user, UUID orderId, Long version);

    /**
     * The seam of the signing machinery, company side: the authorised signer signed after the customer, so
     * CUSTOMER_SIGNED → SIGNED with the file that was signed ({@code signedFileKey} is already stored; the signed file's
     * retention starts when this commits) and today as the day: the contract is in force and its order is placed exactly
     * as when staff record a signed file ({@link #sign}) - installments opened, the credit check run, the order confirmed,
     * waiting for the installment on signing, or held. Joins the caller's transaction. Any customer's contract: the
     * company's signer is a manager, not the customer's salesperson.
     *
     * @param signedAt when the signature was made - the time the certificate page and the PDF signature carry - or null
     *                 for now; never after now
     * @throws com.stockflow.common.error.BusinessException {@code SALES_CONTRACT_STATUS_INVALID} unless the customer signed;
     *         {@code CONTRACT_SIGNER_IS_AUTHOR} for the account that drew the contract up or sent it;
     *         {@code OPTIMISTIC_LOCK}
     */
    ContractView companySigned(CurrentUser user, UUID orderId, long version, String signedFileKey,
                               String signedFileName, Instant signedAt);

    /** Where the browser uploads the signed file (FileCategory.DOCUMENT); the contract is sent. */
    UploadSlot beginSignedFileUpload(CurrentUser user, UUID orderId, UploadSlotRequest request);

    /**
     * Recorded signed with the signed file - sent with the request, or uploaded through
     * {@link #beginSignedFileUpload} - and the day it was signed (14 §4.3 step 14, BR-14).
     */
    ContractView sign(CurrentUser user, UUID orderId, long version, IncomingFiles signedFile, LocalDate signedOn);

    /** Cancelled before it was signed, with a reason (14 §4.3 step 16). */
    ContractView cancel(CurrentUser user, UUID orderId, long version, String reason);

    /** The live contract drawn as a PDF now - what a draft would be sent as. */
    byte[] preview(UUID orderId);

    /** A link to the PDF the live contract was sent as. */
    DownloadLink sentDocument(UUID orderId);

    /** A link to the live contract's signed file. */
    DownloadLink signedFile(UUID orderId);

    /**
     * One of the customer's own orders' contract, once sent: a draft is Sales' work in progress. Someone else's order,
     * or no sent contract, is 404 either way.
     */
    ContractView customerContract(UUID customerId, UUID orderId);

    /**
     * A link to the PDF the customer was sent, or to the signed file once signed; {@code sentCopy} asks for the PDF that
     * was sent even after signing (17 §5.2: "bản gửi ký / bản đã ký").
     */
    DownloadLink customerDocument(UUID customerId, UUID orderId, boolean sentCopy);
}
