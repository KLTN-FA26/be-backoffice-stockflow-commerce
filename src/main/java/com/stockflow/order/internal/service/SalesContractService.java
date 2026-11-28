package com.stockflow.order.internal.service;

import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerPaymentSchedules;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.order.internal.domain.ContractAgreement;
import com.stockflow.order.internal.domain.ContractCreditFigures;
import com.stockflow.order.internal.domain.ContractCreditReviews;
import com.stockflow.order.internal.domain.ContractParty;
import com.stockflow.order.internal.domain.ContractTerms;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.PaymentMilestone;
import com.stockflow.order.internal.domain.SalesContractList;
import com.stockflow.order.internal.domain.SalesContractRevisions;
import com.stockflow.order.internal.domain.SentRevision;
import com.stockflow.order.internal.domain.TermsReview;
import com.stockflow.order.internal.domain.PlannedInstallment;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;

/** See {@link SalesContracts}. */
@Service
@Transactional
class SalesContractService implements SalesContracts {

    private static final Logger log = LoggerFactory.getLogger(SalesContractService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /**
     * The name of the one installment a contract gets when the customer has no schedule: printed on a Vietnamese
     * contract and shown to the customer, so it is read from the Vietnamese bundle whatever the caller's language,
     * as the production instruction sheet does.
     */
    private static final String ON_SIGNING = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"))
            .getString("contract.installment.onSigning");
    private static final java.time.ZoneId VIETNAM = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final CustomerService customers;
    private final CustomerLegalProfiles legalProfiles;
    private final CustomerPaymentSchedules paymentSchedules;
    private final PaymentService payments;
    private final CreditChecks creditChecks;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final SalesContractDocument document;
    private final SellerProperties seller;
    private final ContractProperties defaults;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OrderEventPublisher events;
    private final com.stockflow.order.internal.repository.OrderHoldJpaRepository holds;
    /** Its window is the complaint period a new contract states unless Sales agree another (WF-05 TH-1). */
    private final com.stockflow.order.internal.domain.ReturnPolicy returnPolicy;
    private final com.stockflow.design.api.SkuDesigns skuDesigns;
    private final com.stockflow.payment.api.CustomerInstallments installments;
    /** The letterhead and the products' names a contract's paper prints, as the quotation's does. */
    private final SalesDocumentFacts shared;
    /** The windows moved since signing, which decide whether the contract has an appendix. */
    private final com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords;
    private final LineNames lineNames;
    /** 15 BR-11 (2026-11-26): the contracts put in front of accounting before they are sent. */
    private final ContractCreditReviews creditReviews;
    /** Tells accounting a contract waits for it, and Sales what accounting decided. */
    private final SalesFollowUpService followUps;
    /** 14 BR-25, BR-26: who may work on a customer's contracts, and whose customer it is when one is drawn up. */
    private final CustomerOwnership ownership;
    /** The PDF each revision was sent as, and the customer's change requests. */
    private final SalesContractRevisions revisions;
    private final SalesContractList contractList;
    private final com.stockflow.identity.api.IdentityService identity;
    private final ContractEvidence evidence;
    /** The customer's one-time codes: moving the signing deadline lifts their lock ({@link #extendSignBy}). */
    private final com.stockflow.order.internal.service.signing.ContractSignOtps otps;
    /** Days from sending to the day the customer should have signed by (contract e-signing plan). */
    private final int signDays;

    SalesContractService(OrderRepository orders, SalesContractRepository contracts, CustomerService customers,
                         CustomerLegalProfiles legalProfiles, CustomerPaymentSchedules paymentSchedules,
                         PaymentService payments, CreditChecks creditChecks, FileTransfers files, StagedUploads staged,
                         SalesContractDocument document, SellerProperties seller, ContractProperties defaults,
                         TransactionTemplate tx, Clock clock, OrderEventPublisher events,
                         com.stockflow.order.internal.repository.OrderHoldJpaRepository holds,
                         com.stockflow.order.internal.domain.ReturnPolicy returnPolicy,
                         com.stockflow.design.api.SkuDesigns skuDesigns,
                         com.stockflow.payment.api.CustomerInstallments installments,
                         SalesDocumentFacts shared,
                         com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords,
                         LineNames lineNames, ContractCreditReviews creditReviews, SalesFollowUpService followUps,
                         CustomerOwnership ownership, SalesContractRevisions revisions,
                         SalesContractList contractList, com.stockflow.identity.api.IdentityService identity,
                         ContractEvidence evidence,
                         com.stockflow.order.internal.service.signing.ContractSignOtps otps,
                         @org.springframework.beans.factory.annotation.Value("${stockflow.order.contract.sign-days:7}")
                         int signDays) {
        if (signDays < 1 || signDays > SalesContract.MAX_SIGN_DAYS_AHEAD) {
            throw new IllegalArgumentException("stockflow.order.contract.sign-days must be 1 to "
                    + SalesContract.MAX_SIGN_DAYS_AHEAD);
        }
        this.revisions = revisions;
        this.contractList = contractList;
        this.identity = identity;
        this.evidence = evidence;
        this.otps = otps;
        this.signDays = signDays;
        this.ownership = ownership;
        this.lineNames = lineNames;
        this.creditReviews = creditReviews;
        this.followUps = followUps;
        this.shared = shared;
        this.batchRecords = batchRecords;
        this.returnPolicy = returnPolicy;
        this.skuDesigns = skuDesigns;
        this.installments = installments;
        this.events = events;
        this.holds = holds;
        this.orders = orders;
        this.contracts = contracts;
        this.customers = customers;
        this.legalProfiles = legalProfiles;
        this.paymentSchedules = paymentSchedules;
        this.payments = payments;
        this.creditChecks = creditChecks;
        this.files = files;
        this.staged = staged;
        this.document = document;
        this.seller = seller;
        this.defaults = defaults;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView create(CurrentUser user, UUID orderId, Draft draft) {
        Order order = lockInScope(orderId);
        ownership.requireMayActFor(user, order.customerId());
        if (contracts.findLiveByOrderId(orderId).isPresent()) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_EXISTS, ("Order %s already has a contract in force; "
                    + "cancel it to draw up another").formatted(order.orderNumber()))
                    .localized("refusal.contract.exists", order.orderNumber().value());
        }
        if (!seller.complete()) {
            throw new BusinessException(ErrorCode.SELLER_PROFILE_UNAVAILABLE,
                    "stockflow.seller.* is not configured in this deployment");
        }
        CustomerLegalProfile buyer = legalProfiles.legalProfile(order.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_LEGAL_PROFILE_MISSING, ("Customer of "
                        + "order %s: record the registered address and the legal representative first (18 BR-05)")
                        .formatted(order.orderNumber())).localized("refusal.contract.legalProfileMissing",
                        order.orderNumber().value()));
        Draft given = draft == null ? new Draft(null, null, null) : draft;
        SalesContract.requireContractable(order);
        List<DeliveryBatch> schedule = given.deliverySchedule() == null ? ContractTerms.defaultSchedule(order)
                : ContractTerms.schedule(order, batches(given.deliverySchedule()));
        CreditTerms credit = customers.creditTerms(order.customerId());
        List<PlannedInstallment> plan = ContractTerms.plan(order, schedule.size(),
                given.paymentPlan() == null ? defaultPlan(order.customerId()) : installments(given.paymentPlan()),
                creditApproved(credit), credit == null ? null : credit.creditTermDays());
        String number = "HD-" + order.orderNumber().value().substring(3) + "-" + (contracts.countByOrderId(orderId) + 1);
        SalesContract contract = SalesContract.draft(Identifiers.newId(), number, order, sellerParty(),
                buyerParty(buyer), given.freeSampleRounds() == null ? defaults.freeSampleRounds()
                        : given.freeSampleRounds(), schedule, plan, ownership.ownerAtDrawingUp(order.customerId()));
        // Điều 318: the complaint period is written in the contract, the configured one unless agreed otherwise.
        contract.agreeReturnWindow(order, given.returnWindowDays() == null ? (int) returnPolicy.window().toDays()
                : given.returnWindowDays());
        contract.reviseAgreement(order, merged(ContractAgreement.NONE, given.agreement()), standard());
        contract.drawnUpBy(user.userId());
        SalesContract saved = contracts.save(contract);
        log.info("Sales contract {} drawn up for order {}", saved.contractNumber(), order.orderNumber());
        return view(saved, order, draftWarning(order, saved));
    }

    @Override
    @Transactional(readOnly = true)
    public ContractView get(UUID orderId) {
        Order order = readInScope(orderId);
        SalesContract contract = live(order);
        return view(contract, order, draftWarning(order, contract));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractView> history(UUID orderId) {
        Order order = readInScope(orderId);
        return contracts.findByOrderId(orderId).stream().map(contract -> view(contract, order, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractView> historyForSystem(UUID orderId) {
        Order order = orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        return contracts.findByOrderId(orderId).stream().map(contract -> view(contract, order, null)).toList();
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView edit(CurrentUser user, UUID orderId, long version, Draft draft) {
        Order order = lockInScope(orderId);
        ownership.requireMayActFor(user, order.customerId());
        SalesContract contract = live(order);
        // The state first (BR-14): a sent contract is not edited, whatever the schedule or plan sent with the edit.
        contract.requireRevisable(order);
        requireVersion(contract, version);
        Draft given = draft == null ? new Draft(null, null, null) : draft;
        List<DeliveryBatch> schedule = given.deliverySchedule() == null ? contract.schedule()
                : ContractTerms.schedule(order, batches(given.deliverySchedule()));
        CreditTerms credit = customers.creditTerms(order.customerId());
        List<PlannedInstallment> plan = given.paymentPlan() == null
                ? ContractTerms.plan(order, schedule.size(), contract.plan().stream().map(SalesContractService::entry)
                        .toList(), creditApproved(credit), credit == null ? null : credit.creditTermDays())
                : ContractTerms.plan(order, schedule.size(), installments(given.paymentPlan()), creditApproved(credit),
                        credit == null ? null : credit.creditTermDays());
        contract.revise(order, given.freeSampleRounds() == null ? contract.freeSampleRounds()
                : given.freeSampleRounds(), schedule, plan);
        if (given.returnWindowDays() != null) {
            contract.agreeReturnWindow(order, given.returnWindowDays());
        }
        if (given.agreement() != null) {
            contract.reviseAgreement(order, merged(contract.agreement(), given.agreement()), standard());
        }
        SalesContract saved = contracts.save(contract);
        return view(saved, order, draftWarning(order, saved));
    }

    /**
     * The special terms, overrides and signers an edit asks for over what the contract has: a part left out (null) is kept,
     * a blank text clears the special terms or a signer's name, the signers are replaced whole when given.
     */
    static ContractAgreement merged(ContractAgreement current, SalesContracts.AgreementInput given) {
        if (given == null) {
            return current;
        }
        ContractAgreement.SellerSigner seller = given.sellerSigner() == null ? current.seller()
                : new ContractAgreement.SellerSigner(given.sellerSigner().name(), given.sellerSigner().title(),
                given.sellerSigner().authorizationRef());
        ContractAgreement.BuyerSigner buyer = given.buyerSigner() == null ? current.buyer()
                : new ContractAgreement.BuyerSigner(given.buyerSigner().name(), given.buyerSigner().title());
        return new ContractAgreement(given.specialTerms() == null ? current.specialTerms() : given.specialTerms(),
                given.penaltyPercent() == null ? current.penaltyPercent() : given.penaltyPercent(),
                given.quantityTolerancePercent() == null ? current.quantityTolerancePercent()
                        : given.quantityTolerancePercent(),
                given.warrantyMonths() == null ? current.warrantyMonths() : given.warrantyMonths(), seller, buyer);
    }

    /** The configured terms a contract starts from, which an override is measured against (QĐ-4). */
    private ContractAgreement.Standard standard() {
        return new ContractAgreement.Standard(defaults.penaltyPercent(), defaults.quantityTolerancePercent(),
                defaults.warrantyMonths());
    }

    /** The day the customer should have signed by, for a contract sent at {@code now}. */
    private LocalDate signByFor(Instant now) {
        return LocalDate.ofInstant(now, VIETNAM).plusDays(signDays);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), VIETNAM);
    }

    /**
     * Not transactional itself: the PDF is drawn and kept with no lock held - storage is slow, and the order's row lock
     * makes everyone else wait - from the version read; the contract is then sent in one short transaction if it is
     * still that version, and the PDF goes if it is not.
     *
     * <p>15 BR-11 (2026-11-26): a plan whose credit check fails - something overdue, or past the limit with this
     * contract - is not sent but put in front of accounting, in the first transaction and with no PDF drawn. The check
     * runs again in the second, since a receipt or another contract may have moved what the customer owes in between:
     * failing there, the contract waits for accounting all the same and the PDF drawn goes. No approval is looked for
     * here: approving sends the contract, so a draft never holds one.</p>
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'SENT'}")
    public ContractView send(CurrentUser user, UUID orderId, long version) {
        SendStep first = tx.execute(status -> {
            Order order = lockInScope(orderId);
            ownership.requireMayActFor(user, order.customerId());
            SalesContract contract = live(order, version);
            Instant now = clock.instant();
            contract.requireSendable(order, now);
            requireActiveCustomer(order);
            requireCreditFor(order, contract);
            ContractCreditFigures check = creditCheck(order, contract);
            if (check != null && check.fails()) {
                return new SendStep(submitForCreditReview(user, order, contract, version, check, now), null);
            }
            return new SendStep(null, facts(contract, order, LocalDate.ofInstant(now, VIETNAM), true));
        });
        if (first.waiting() != null) {
            return first.waiting();
        }
        SalesContractDocument.Facts facts = first.facts();
        byte[] pdf = document.render(facts);
        try (PreparedFiles prepared = IncomingFiles.sent(List.of(new FileUpload(facts.contract().contractNumber()
                        + ".pdf", "application/pdf", pdf.length, new ByteArrayInputStream(pdf))))
                .prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                Order order = lockInScope(orderId);
                // Asked again under the lock: the customer may have moved to another salesperson while the PDF was drawn.
                ownership.requireMayActFor(user, order.customerId());
                SalesContract contract = live(order, version);
                requireActiveCustomer(order);
                requireCreditFor(order, contract);
                Instant now = clock.instant();
                ContractCreditFigures check = creditCheck(order, contract);
                if (check != null && check.fails()) {
                    // Moved since the first step: it waits for accounting, and the PDF, never adopted, goes.
                    return submitForCreditReview(user, order, contract, version, check, now);
                }
                contract.send(order, now, user.userId(), stored.key(), signByFor(now));
                SalesContract saved = contracts.save(contract);
                revisions.recordSent(saved.id(), saved.sentRevision());
                // 14 §4.3 step 13: the order now waits for the signature (4B-5).
                order.sendContract(user.userId(), now);
                orders.save(order);
                prepared.adopt();
                files.retainAfterCommit(stored.key(), RecordRetention.SALES_CONTRACT);
                statusChanged(saved, order, user.userId(), now);
                log.info("Sales contract {} of order {} sent", saved.contractNumber(), order.orderNumber());
                return view(saved, order, creditWarning(order, saved));
            });
        }
    }

    /** What the first step of a send found: the contract put in front of accounting, or what its PDF shows. */
    private record SendStep(ContractView waiting, SalesContractDocument.Facts facts) { }

    /**
     * 15 BR-11: the draft waits for accounting instead of being sent - locked against edits, the figures accounting
     * decides on kept as they are now - and whoever decides credit is told.
     */
    private ContractView submitForCreditReview(CurrentUser user, Order order, SalesContract contract, long version,
                                               ContractCreditFigures check, Instant now) {
        contract.submitForCreditReview(order, now);
        SalesContract saved = contracts.save(contract);
        ContractCreditReviews.Review review = creditReviews.open(saved.id(), version, check, user.userId(), now);
        followUps.contractCreditReviewRequested(review.id(), order.id().value(), order.orderNumber().value(),
                saved.contractNumber(), check.outcome().name(), user.userId());
        log.info("Sales contract {} of order {} waits for accounting: credit check {}", saved.contractNumber(),
                order.orderNumber(), check.outcome());
        return view(saved, order, creditWarning(order, saved));
    }

    /**
     * Not transactional itself, as {@link #send}: checked and drawn with no lock held, then approved and sent in one
     * short transaction if the contract is still that version and still waiting. The credit is not checked again - it
     * is what accounting decides here; whether the customer still has credit at all after delivery
     * ({@link #requireCreditFor}) is, as at any send.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.APPROVE, resourceType = "sales-contract-credit", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'SENT'}")
    public ContractView approveCredit(CurrentUser user, UUID orderId, long version, String note) {
        String text = requireDecisionText(note, "refusal.contract.creditReview.noteRequired");
        SalesContractDocument.Facts facts = tx.execute(status -> {
            Order order = lockForAccounting(orderId);
            SalesContract contract = live(order, version);
            ContractCreditReviews.Review review = waitingReview(contract);
            requireOtherThanSubmitter(contract, review, user);
            Instant now = clock.instant();
            contract.requireCreditApprovable(order, now);
            requireActiveCustomer(order);
            requireCreditFor(order, contract);
            return facts(contract, order, LocalDate.ofInstant(now, VIETNAM), true);
        });
        byte[] pdf = document.render(facts);
        try (PreparedFiles prepared = IncomingFiles.sent(List.of(new FileUpload(facts.contract().contractNumber()
                        + ".pdf", "application/pdf", pdf.length, new ByteArrayInputStream(pdf))))
                .prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                Order order = lockForAccounting(orderId);
                SalesContract contract = live(order, version);
                ContractCreditReviews.Review review = waitingReview(contract);
                requireOtherThanSubmitter(contract, review, user);
                requireActiveCustomer(order);
                requireCreditFor(order, contract);
                Instant now = clock.instant();
                // Sent as the Sales account that asked, who follows it with the customer: the reminder of a contract
                // left unsigned and the chat card go to them; the review names the approver.
                UUID sales = review.submittedBy();
                contract.approveCredit(order, now, sales, stored.key(), signByFor(now));
                SalesContract saved = contracts.save(contract);
                revisions.recordSent(saved.id(), saved.sentRevision());
                creditReviews.close(review.id(), ContractCreditReviews.Outcome.APPROVED, user.userId(), now, text);
                order.sendContract(sales, now);
                orders.save(order);
                prepared.adopt();
                files.retainAfterCommit(stored.key(), RecordRetention.SALES_CONTRACT);
                statusChanged(saved, order, sales, now);
                followUps.contractCreditDecided(review.id(), order.id().value(), order.orderNumber().value(),
                        saved.contractNumber(), order.customerId(), sales, true);
                log.info("Sales contract {} of order {}: credit approved by accounting, sent", saved.contractNumber(),
                        order.orderNumber());
                return view(saved, order, creditWarning(order, saved));
            });
        }
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "sales-contract-credit", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView refuseCredit(CurrentUser user, UUID orderId, long version, String reason) {
        String text = requireDecisionText(reason, "refusal.contract.creditReview.reasonRequired");
        Order order = lockForAccounting(orderId);
        SalesContract contract = live(order, version);
        ContractCreditReviews.Review review = waitingReview(contract);
        requireOtherThanSubmitter(contract, review, user);
        Instant now = clock.instant();
        contract.refuseCredit();
        SalesContract saved = contracts.save(contract);
        creditReviews.close(review.id(), ContractCreditReviews.Outcome.REJECTED, user.userId(), now, text);
        followUps.contractCreditDecided(review.id(), order.id().value(), order.orderNumber().value(),
                saved.contractNumber(), order.customerId(), review.submittedBy(), false);
        log.info("Sales contract {} of order {}: credit refused by accounting, back to Sales", saved.contractNumber(),
                order.orderNumber());
        return view(saved, order, draftWarning(order, saved));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract-credit", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView withdrawCreditReview(CurrentUser user, UUID orderId, long version) {
        Order order = lockInScope(orderId);
        // 14 BR-25: withdrawing puts the contract back in Sales' hands, so it is the owner's step, as sending it was
        // (a salesperson who lost the customer no longer pulls back what was sent for approval).
        ownership.requireMayActFor(user, order.customerId());
        SalesContract contract = live(order, version);
        ContractCreditReviews.Review review = waitingReview(contract);
        contract.withdrawCreditReview();
        SalesContract saved = contracts.save(contract);
        creditReviews.close(review.id(), ContractCreditReviews.Outcome.WITHDRAWN, user.userId(), clock.instant(), null);
        log.info("Sales contract {} of order {}: credit review withdrawn", saved.contractNumber(), order.orderNumber());
        return view(saved, order, draftWarning(order, saved));
    }

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.common.api.PageResponse<CreditReviewWaiting> creditReviewsWaiting(int page, int size) {
        var pageable = com.stockflow.common.persistence.Pages.of(page, size,
                org.springframework.data.domain.Sort.unsorted());
        List<CreditReviewWaiting> items = creditReviews.waiting(pageable.getOffset(), pageable.getPageSize()).stream()
                .map(w -> new CreditReviewWaiting(w.reviewId(), w.orderId(), w.orderNumber(), w.contractId(),
                        w.contractNumber(), w.contractVersion(), w.customerId(), w.figures().outcome().name(),
                        w.figures().creditLimit(), w.figures().exposure(), w.figures().afterDelivery(),
                        w.figures().overdueAmount(), w.submittedBy(), w.submittedAt()))
                .toList();
        return com.stockflow.common.api.PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(),
                creditReviews.countWaiting());
    }

    /** The contract's review still waiting; refused when it waits for nothing (15 BR-11). */
    private ContractCreditReviews.Review waitingReview(SalesContract contract) {
        if (!contract.creditReviewPending()) {
            throw new BusinessException(ErrorCode.CONTRACT_CREDIT_REVIEW_NOT_PENDING, ("Contract %s is not waiting for "
                    + "accounting to approve the customer's credit").formatted(contract.contractNumber()))
                    .localized("refusal.contract.creditReviewNotPending", contract.contractNumber());
        }
        return creditReviews.pending(contract.id()).orElseThrow(() -> new IllegalStateException(
                "Contract " + contract.contractNumber() + " waits for accounting with no review open"));
    }

    /** 15 BR-11, four eyes: whoever sent the contract for review does not decide it. */
    private static void requireOtherThanSubmitter(SalesContract contract, ContractCreditReviews.Review review,
                                                  CurrentUser user) {
        if (review.submittedBy().equals(user.userId())) {
            throw new BusinessException(ErrorCode.CONTRACT_CREDIT_REVIEW_SELF, ("Contract %s was sent for review by you: "
                    + "someone else decides its credit").formatted(contract.contractNumber()))
                    .localized("refusal.contract.creditReviewSelf", contract.contractNumber());
        }
    }

    /** A note or a reason, required and at most 500 characters ({@code ck_contract_credit_review_note}). */
    private static String requireDecisionText(String given, String key) {
        String text = given == null ? "" : given.strip();
        if (text.isEmpty() || text.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A credit decision says why, in at most 500 "
                    + "characters").localized(key, 500);
        }
        return text;
    }

    /**
     * Q-9 (WF-02 decision 12, kltn-docs 14 BR-01): only an active customer is sent a new contract. One sent before the
     * customer was blocked is still signed and paid, and one signed is carried out - neither passes through here.
     */
    private void requireActiveCustomer(Order order) {
        String status = customers.findById(order.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).status();
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE, "Customer " + order.customerId() + " is "
                    + status + "; a new contract is sent to an ACTIVE customer only")
                    .localized("refusal.contract.customerNotActive",
                            new com.stockflow.common.i18n.Label("CustomerStatus", status));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PlanSuggestion suggestPaymentPlan(UUID orderId) {
        Order order = readInScope(orderId);
        SalesContract contract = live(order);
        contract.requireRevisable(order);
        CreditTerms credit = customers.creditTerms(order.customerId());
        List<PlannedInstallment> plan = ContractTerms.plan(order, contract.schedule().size(),
                ContractTerms.suggestTied(order, contract.schedule(), contract.plan().stream()
                        .map(SalesContractService::entry).toList()),
                creditApproved(credit), credit == null ? null : credit.creditTermDays());
        return new PlanSuggestion(contract.id(), contract.version(), order.total().amount(),
                plan.stream().map(SalesContractService::installmentView).toList());
    }

    // ---- terms review (QĐ-4) -------------------------------------------------------------------------------------------

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract-terms", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'TERMS_REVIEW_PENDING'}")
    public ContractView submitForTermsReview(CurrentUser user, UUID orderId, long version) {
        Order order = lockInScope(orderId);
        // 14 BR-25: putting the draft in front of a manager is the owner's step, as sending it is.
        ownership.requireMayActFor(user, order.customerId());
        SalesContract contract = live(order, version);
        boolean approved = contract.termsReview() == TermsReview.APPROVED;
        Instant now = clock.instant();
        contract.submitForTermsReview(order, now, user.userId());
        SalesContract saved = contracts.save(contract);
        if (!approved) {
            events.publish(new com.stockflow.contracts.ContractTermsReviewRequested(order.id().value(), saved.id(),
                    saved.revision(), saved.contractNumber(), order.orderNumber().value(), user.userId(), now));
            log.info("Sales contract {} of order {} waits for its terms to be approved", saved.contractNumber(),
                    order.orderNumber());
        }
        return view(saved, order, draftWarning(order, saved));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "sales-contract-terms", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView approveTerms(CurrentUser user, UUID orderId, long version, String note) {
        return decideTerms(user, orderId, version, note, true);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "sales-contract-terms", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView rejectTerms(CurrentUser user, UUID orderId, long version, String reason) {
        return decideTerms(user, orderId, version, reason, false);
    }

    /**
     * A manager's decision: the order locked whatever the caller's customers (any Sales Manager decides - the terms are
     * the company's, not the salesperson's), the contract's version checked, the outcome recorded and announced.
     */
    private ContractView decideTerms(CurrentUser user, UUID orderId, long version, String note, boolean approve) {
        Order order = lockForAccounting(orderId);
        SalesContract contract = live(order, version);
        Instant now = clock.instant();
        UUID requestedBy = contract.termsReviewRequestedBy();
        if (approve) {
            contract.approveTerms(note, now, user.userId());
        } else {
            contract.rejectTerms(note, now, user.userId());
        }
        SalesContract saved = contracts.save(contract);
        events.publish(new com.stockflow.contracts.ContractTermsReviewDecided(order.id().value(), saved.id(),
                saved.revision(), saved.contractNumber(), order.orderNumber().value(), order.customerId(),
                approve ? "APPROVED" : "REJECTED", requestedBy, user.userId(), now));
        log.info("Sales contract {} of order {}: terms {}", saved.contractNumber(), order.orderNumber(),
                approve ? "approved" : "rejected");
        return view(saved, order, draftWarning(order, saved));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract-terms", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'TERMS_REVIEW_WITHDRAWN'}")
    public ContractView withdrawTermsReview(CurrentUser user, UUID orderId, long version) {
        Order order = lockInScope(orderId);
        ownership.requireMayActFor(user, order.customerId());
        SalesContract contract = live(order, version);
        contract.withdrawTermsReview();
        SalesContract saved = contracts.save(contract);
        log.info("Sales contract {} of order {}: terms review withdrawn", saved.contractNumber(), order.orderNumber());
        return view(saved, order, draftWarning(order, saved));
    }

    // ---- signing deadline, revisions and the customer's change request --------------------------------------------------

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, field: 'signBy'}")
    public ContractView extendSignBy(CurrentUser user, UUID orderId, long version, LocalDate signBy) {
        Order order = lockInScope(orderId);
        ownership.requireMayActFor(user, order.customerId());
        SalesContract contract = live(order, version);
        contract.extendSignBy(signBy, today());
        SalesContract saved = contracts.save(contract);
        // Sales giving the customer more time is also Sales vouching for them: the wrong codes counted so far are
        // forgiven, so a customer locked out by CONTRACT_OTP_LOCKED can sign again (and no sooner than a person acted).
        otps.forgiveWrongTries(saved.id(), saved.revision(), clock.instant());
        log.info("Sales contract {} of order {}: to be signed by {}", saved.contractNumber(), order.orderNumber(),
                saved.signBy());
        return view(saved, order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionView> revisions(UUID orderId) {
        return revisionViews(live(readInScope(orderId)), true);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink revisionDocument(UUID orderId, int revision) {
        return revisionLink(live(readInScope(orderId)), revision);
    }

    /**
     * Not the PDF of the revision itself but its row: the sent PDF is kept under the row's key, and a contract sent before
     * revisions existed (or fixtured without a row) is its own current revision.
     */
    private List<SalesContractRevisions.Revision> revisionRows(SalesContract contract) {
        List<SalesContractRevisions.Revision> rows = new java.util.ArrayList<>(revisions.of(contract.id()));
        if (contract.sentAt() != null && rows.stream().noneMatch(r -> r.revision() == contract.revision())) {
            rows.add(new SalesContractRevisions.Revision(contract.id(), contract.revision(), contract.sentDocumentKey(),
                    contract.sentAt(), contract.sentBy(), null, null, null));
        }
        rows.sort(java.util.Comparator.comparingInt(SalesContractRevisions.Revision::revision));
        return rows;
    }

    private List<RevisionView> revisionViews(SalesContract contract, boolean withSender) {
        List<SalesContractRevisions.Revision> rows = revisionRows(contract);
        Map<UUID, String> names = withSender && !rows.isEmpty()
                ? identity.displayNames(rows.stream().map(SalesContractRevisions.Revision::sentBy).distinct().toList())
                : Map.of();
        return rows.stream().map(r -> new RevisionView(r.revision(), r.sentAt(), withSender ? r.sentBy() : null,
                withSender ? names.get(r.sentBy()) : null, r.changeRequestReason(), r.changeRequestedAt(),
                r.documentKey() != null)).toList();
    }

    private DownloadLink revisionLink(SalesContract contract, int revision) {
        SalesContractRevisions.Revision found = revisionRows(contract).stream().filter(r -> r.revision() == revision)
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Contract %s has no revision %d"
                        .formatted(contract.contractNumber(), revision)));
        return files.downloadLink(found.documentKey(), contract.contractNumber() + "-v" + revision + ".pdf");
    }

    /**
     * The customer sends the contract back: the contract row is read and changed under its order's lock, as every change
     * to a contract is (the order first, the contract after it), so a signature arriving at the same moment finds either
     * the contract still sent or a draft, never half of each.
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'DRAFT', reason: 'CHANGES_REQUESTED'}")
    public ContractView requestChanges(UUID customerId, UUID userId, UUID orderId, long version, String reason) {
        String text = SalesContract.requireChangeReason(reason);
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        SalesContract contract = portalContract(order);
        requireVersion(contract, version);
        Instant now = clock.instant();
        SentRevision ended = contract.requestChanges(text);
        SalesContract saved = contracts.save(contract);
        revisions.recordChangeRequest(saved.id(), ended.revision(), text, now, userId);
        order.reopenContract(userId, now);
        orders.save(order);
        statusChanged(saved, order, userId, now);
        events.publish(new com.stockflow.contracts.ContractChangeRequested(order.id().value(), saved.id(),
                ended.revision(), saved.contractNumber(), order.orderNumber().value(), order.customerId(), now));
        log.info("Sales contract {} of order {}: the customer asked for changes, revision {} next",
                saved.contractNumber(), order.orderNumber(), saved.revision());
        return portalCopy(view(saved, order, null), saved);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'CUSTOMER_SIGNED'}")
    public ContractView customerSigned(UUID customerId, UUID userId, UUID orderId, long version, Instant signedAt) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        SalesContract contract = portalContract(order);
        requireVersion(contract, version);
        Instant now = signedAt == null ? clock.instant() : signedAt;
        contract.customerSigned(now);
        SalesContract saved = contracts.save(contract);
        statusChanged(saved, order, userId, now);
        log.info("Sales contract {} of order {} signed by the customer; it waits for the company", saved.contractNumber(),
                order.orderNumber());
        return portalCopy(view(saved, order, null), saved);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'SIGNED', by: 'COMPANY'}")
    public ContractView companySigned(CurrentUser user, UUID orderId, long version, String signedFileKey,
                                      String signedFileName, Instant signedAt) {
        Order order = lockForAccounting(orderId);
        SalesContract contract = live(order, version);
        Instant now = signedAt == null ? clock.instant() : signedAt;
        LocalDate today = LocalDate.ofInstant(now, VIETNAM);
        // The state first: a contract the customer has not signed answers that, not who may sign it.
        contract.requireCompanySignable(today, now);
        contract.requireNotAuthor(user.userId());
        contract.companySigned(today, signedFileKey, signedFileName, now, user.userId());
        SalesContract saved = contracts.save(contract);
        placeSigned(order, saved, user.userId(), now);
        files.retainAfterCommit(signedFileKey, RecordRetention.SALES_CONTRACT);
        statusChanged(saved, order, user.userId(), now);
        log.info("Sales contract {} of order {} signed by the company; the order is {}", saved.contractNumber(),
                order.orderNumber(), order.status());
        return view(saved, order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public SigningFacts customerSigningFacts(UUID customerId, UUID orderId, Long version, boolean requireSignable) {
        Order order = customersOrder(customerId, orderId);
        SalesContract contract = portalContract(order);
        if (contract.status() == SalesContractStatus.DRAFT) {
            // The next revision after a change request: the customer has nothing to sign until it is sent again.
            throw new BusinessException(ErrorCode.CONTRACT_NOT_SIGNABLE, "Contract %s is a draft"
                    .formatted(contract.contractNumber())).localized("refusal.contract.notSignable",
                    contract.contractNumber(), contract.status());
        }
        if (version != null) {
            requireVersion(contract, version);
        }
        if (requireSignable) {
            contract.requireCustomerSignable(today());
        }
        return signingFacts(contract, order);
    }

    @Override
    @Transactional(readOnly = true)
    public SigningFacts companySigningFacts(CurrentUser user, UUID orderId, Long version) {
        Order order = readOrder(orderId);
        SalesContract contract = live(order);
        if (version != null) {
            requireVersion(contract, version);
        }
        Instant now = clock.instant();
        // The state first: a contract the customer has not signed answers that, not who may sign it.
        contract.requireCompanySignable(LocalDate.ofInstant(now, VIETNAM), now);
        contract.requireNotAuthor(user.userId());
        return signingFacts(contract, order);
    }

    private static SigningFacts signingFacts(SalesContract contract, Order order) {
        ContractEvidence.Signer sellerSigner = ContractEvidence.sellerSigner(contract);
        ContractEvidence.Signer buyerSigner = ContractEvidence.buyerSigner(contract);
        return new SigningFacts(contract.id(), contract.contractNumber(), contract.orderId(),
                order.orderNumber().value(), order.customerId(), contract.revision(), contract.version(),
                contract.status().name(), contract.sentDocumentKey(), contract.signBy(), contract.seller().name(),
                contract.buyer().name(), sellerSigner.name(), sellerSigner.title(), buyerSigner.name(),
                buyerSigner.title());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionView> customerRevisions(UUID customerId, UUID orderId) {
        return revisionViews(portalContract(customersOrder(customerId, orderId)), false);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink customerRevisionDocument(UUID customerId, UUID orderId, int revision) {
        return revisionLink(portalContract(customersOrder(customerId, orderId)), revision);
    }

    // ---- the contract list -----------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.common.api.PageResponse<ContractRow> list(ContractQuery query) {
        var pageable = com.stockflow.common.persistence.Pages.of(query.page(), query.size(),
                org.springframework.data.domain.Sort.unsorted());
        SalesContractList.Page found = contractList.page(new SalesContractList.Query(statuses(query.statuses()),
                query.salesOwnerId(), query.signByBefore(), termsReview(query.termsReview()), query.search(), today(),
                pageable.getPageNumber(), pageable.getPageSize()));
        Map<UUID, String> owners = found.rows().isEmpty() ? Map.of() : identity.displayNames(found.rows().stream()
                .map(SalesContractList.Row::salesOwnerId).filter(Objects::nonNull).distinct().toList());
        List<ContractRow> rows = found.rows().stream().map(r -> new ContractRow(r.orderId(), r.contractId(),
                r.contractNumber(), r.orderNumber(), r.customerId(), r.customerName(), r.status().name(), r.revision(),
                r.totalAmount(), r.sentAt(), r.signBy(), r.overdueToSign(), r.customerSignedAt(), r.signedAt(),
                r.termsReview().name(), r.creditReview(), r.salesOwnerId(),
                r.salesOwnerId() == null ? null : owners.get(r.salesOwnerId()))).toList();
        return com.stockflow.common.api.PageResponse.of(rows, pageable.getPageNumber(), pageable.getPageSize(),
                found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public ContractSummary summary() {
        SalesContractList.Summary found = contractList.summary(today());
        Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
        for (SalesContractStatus status : SalesContractStatus.values()) {
            byStatus.put(status.name(), found.byStatus().getOrDefault(status, 0L));
        }
        return new ContractSummary(byStatus, found.awaitingCompanySignature(), found.overdueToSign(),
                found.termsReviewPending());
    }

    private static Set<SalesContractStatus> statuses(Set<String> given) {
        if (given == null || given.isEmpty()) {
            return Set.of();
        }
        Set<SalesContractStatus> parsed = new LinkedHashSet<>();
        for (String name : given) {
            try {
                parsed.add(SalesContractStatus.valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown contract status " + name);
            }
        }
        return parsed;
    }

    private static TermsReview termsReview(String given) {
        if (given == null || given.isBlank()) {
            return null;
        }
        try {
            return TermsReview.valueOf(given.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown terms review " + given);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UploadSlot beginSignedFileUpload(CurrentUser user, UUID orderId, UploadSlotRequest request) {
        live(readInScope(orderId)).requireSignable();
        return staged.begin(FileCategory.DOCUMENT, user.userId(), request);
    }

    /**
     * Not transactional itself: the signed file is checked and kept first, outside the transaction, then the contract
     * is recorded signed in one. The same upload named again - also after a commit whose outcome the first request never
     * learned - answers with the contract it signed.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.APPROVE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'SIGNED'}")
    public ContractView sign(CurrentUser user, UUID orderId, long version, IncomingFiles signedFile,
                             LocalDate signedOn) {
        if (signedFile == null || signedFile.count() != 1 || signedOn == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "One signed file and the day it was signed")
                    .localized("refusal.contract.signedFileRequired");
        }
        if (signedFile instanceof IncomingFiles.Uploaded uploaded) {
            // The key is the upload's own, so a contract naming it is the one this upload signed.
            Optional<String> key = staged.keyOf(FileCategory.DOCUMENT, user.userId(), uploaded.uploadIds().getFirst());
            Optional<ContractView> earlier = key.flatMap(k -> tx.execute(status -> {
                Order order = readOrder(orderId);
                return contracts.findLiveByOrderId(orderId).filter(c -> k.equals(c.signedFileKey()))
                        .map(c -> view(c, order, null));
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        String sentKey = tx.execute(status -> {
            SalesContract contract = live(readOrder(orderId), version);
            contract.requireSignable(signedOn, clock.instant());
            return contract.sentDocumentKey();
        });
        try (PreparedFiles prepared = signedFile.prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            // What the file is, found outside any transaction (a signed PDF takes a while to check): a digital
            // signature is verified, a scan is accepted as one. Never a reason to refuse what staff recorded.
            byte[] signed = evidence.read(stored.key());
            byte[] sent = sentKey == null ? null : evidence.read(sentKey);
            ContractEvidence.Inspected inspected = evidence.inspect(signed, sent);
            return tx.execute(status -> {
                Order order = lockInScope(orderId);
                SalesContract contract = live(order, version);
                Instant now = clock.instant();
                contract.sign(signedOn, stored.key(), stored.originalName(), now, user.userId());
                SalesContract saved = contracts.save(contract);
                evidence.recordStaffSigned(saved, user.userId(), stored.key(), prepared.single().sha256(), inspected,
                        now, sent == null ? prepared.single().sha256() : ContractEvidence.sha256Hex(sent));
                placeSigned(order, saved, user.userId(), now);
                prepared.adopt();
                files.retainAfterCommit(stored.key(), RecordRetention.SALES_CONTRACT);
                statusChanged(saved, order, user.userId(), now);
                log.info("Sales contract {} of order {} signed on {}; the order is {}", saved.contractNumber(),
                        order.orderNumber(), signedOn, order.status());
                return view(saved, order, null);
            });
        }
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'CANCELLED'}")
    public ContractView cancel(CurrentUser user, UUID orderId, long version, String reason) {
        Order order = lockInScope(orderId);
        ownership.requireMayActFor(user, order.customerId());
        SalesContract contract = live(order, version);
        Instant now = clock.instant();
        contract.cancel(reason, now, user.userId());
        SalesContract saved = contracts.save(contract);
        // 14 §4.3 step 16: cancelled to be drawn up again - the order goes back to DRAFT (4B-5). A cancellation request
        // the customer sent meanwhile stays PENDING for Sales to decide (17 §4.8 step 28: a request lapses only once
        // the order can no longer be cancelled, and a DRAFT order can): closing it here dropped the customer's wish and
        // told them the order had been packed or shipped. Approved, it cancels the DRAFT order.
        order.withdrawContract(user.userId(), saved.cancellationReason(), now);
        orders.save(order);
        statusChanged(saved, order, user.userId(), now);
        log.info("Sales contract {} of order {} cancelled", saved.contractNumber(), order.orderNumber());
        return view(saved, order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] preview(UUID orderId) {
        Order order = readInScope(orderId);
        return document.render(facts(live(order), order));
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink sentDocument(UUID orderId) {
        SalesContract contract = live(readInScope(orderId));
        if (contract.sentDocumentKey() == null) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s was not sent yet"
                    .formatted(contract.contractNumber()))
                    .localized("refusal.contract.notSentYet", contract.contractNumber());
        }
        return files.downloadLink(contract.sentDocumentKey(), contract.contractNumber() + ".pdf");
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink signedFile(UUID orderId) {
        SalesContract contract = live(readInScope(orderId));
        if (contract.signedFileKey() == null) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is not signed"
                    .formatted(contract.contractNumber()))
                    .localized("refusal.contract.notSignedYet", contract.contractNumber());
        }
        return files.downloadLink(contract.signedFileKey(), contract.signedFileName());
    }

    @Override
    @Transactional(readOnly = true)
    public ContractView customerContract(UUID customerId, UUID orderId) {
        Order order = customersOrder(customerId, orderId);
        SalesContract contract = portalContract(order);
        return portalCopy(view(contract, order, null), contract);
    }

    /**
     * The customer's copy: who in the back office sent or signed it is not theirs to read, nor the terms review or the
     * credit position. A draft that came back after the customer asked for changes reads as CHANGES_REQUESTED, as a
     * quote does: Sales' work in progress is not theirs yet, so its schedule, plan and special terms are not shown.
     */
    private static ContractView portalCopy(ContractView full, SalesContract contract) {
        boolean reopened = contract.status() == SalesContractStatus.DRAFT;
        return new ContractView(full.id(), full.contractNumber(), full.orderId(), full.orderNumber(),
                reopened ? "CHANGES_REQUESTED" : full.status(), full.seller(), full.buyer(), full.freeSampleRounds(),
                full.totals(), reopened ? List.of() : full.deliverySchedule(),
                reopened ? List.of() : full.paymentPlan(), full.sentAt(), null, full.signedOn(), full.signedFileName(),
                full.signedAt(), null, null, null, null, full.completedAt(), full.version(), null,
                full.returnWindowDays(), full.appendixAvailable(), "NONE", null, full.revision(), full.signBy(),
                full.overdueToSign(), reopened ? null : full.specialTerms(), reopened ? null : full.penaltyPercent(),
                reopened ? null : full.quantityTolerancePercent(), reopened ? null : full.warrantyMonths(),
                reopened ? null : full.sellerSigner(), reopened ? null : full.buyerSigner(), null, false, "NONE", null,
                full.customerSignedAt(), full.canSign(), full.canRequestChanges(),
                portalCustomerSignature(full.customerSignature()), portalCompanySignature(full.companySignature()),
                inForce(contract) ? full.verificationCode() : null);
    }

    /** Signed by both sides: the code printed on the certificate page is the customer's to have only then. */
    private static boolean inForce(SalesContract contract) {
        return switch (contract.status()) {
            case SIGNED, COMPLETED, LIQUIDATED -> true;
            default -> false;
        };
    }

    /** The customer's own signature as the portal shows it: the address it came from is the back office's. */
    static CustomerSignatureView portalCustomerSignature(CustomerSignatureView signature) {
        return signature == null ? null : new CustomerSignatureView(signature.method(), signature.signerName(),
                signature.signerTitle(), signature.email(), signature.signedAt(), null, signature.otpVerified(),
                signature.inspection());
    }

    /** The company's signature as the portal shows it: which account in the back office pressed the button is not theirs. */
    static CompanySignatureView portalCompanySignature(CompanySignatureView signature) {
        return signature == null ? null : new CompanySignatureView(signature.method(), signature.signerName(),
                signature.signerTitle(), signature.signedAt(), null, signature.certificateSubject(),
                signature.certificateIssuer(), signature.testCertificate(), signature.inspection());
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink customerDocument(UUID customerId, UUID orderId, boolean sentCopy) {
        SalesContract contract = sentContract(customersOrder(customerId, orderId));
        return contract.signedFileKey() != null && !sentCopy
                ? files.downloadLink(contract.signedFileKey(), contract.signedFileName())
                : files.downloadLink(contract.sentDocumentKey(), contract.contractNumber() + ".pdf");
    }

    /**
     * Tells the modules that follow contracts - the chat's contract card (kltn-docs 16 §4.3 step 8) - that it was sent,
     * signed or cancelled. Published in the step's transaction, delivered after commit.
     */
    private void statusChanged(SalesContract contract, Order order, UUID by, Instant at) {
        events.publish(new com.stockflow.contracts.SalesContractStatusChanged(contract.id(), contract.contractNumber(),
                order.id().value(), order.orderNumber().value(), order.customerId(), contract.status().name(), by, at));
    }

    // ---- loading ---------------------------------------------------------------------------------------------------

    /**
     * The order, read unscoped - only inside the signing path, whose change goes through {@link #lockInScope} right
     * after: the replay lookup of an upload already made, and the check before the signed file is stored.
     */
    private Order readOrder(UUID orderId) {
        return orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    /**
     * The order as the caller's data scope sees it, for a read (out of scope reads as absent, 404): a contract carries
     * the customer's legal details and prices, so it is read under the same scope it is changed under - not unscoped as
     * the order's own summary is.
     */
    private Order readInScope(UUID orderId) {
        return orders.findByIdInScopeForReading(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    /**
     * The order locked for the rest of the transaction, as the caller's data scope sees it (out of scope reads as
     * absent): every change to its contract goes through this lock, so two Sales staff never both draw up its one live
     * contract (14 BR-12).
     */
    private Order lockInScope(UUID orderId) {
        return orders.findByIdInScope(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    /**
     * The order locked for accounting's credit decision (15 BR-11), whatever the caller's data scope: credit is decided
     * across every customer, as {@code CreditHoldService} decides an order held at signing.
     */
    private Order lockForAccounting(UUID orderId) {
        return orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private Order customersOrder(UUID customerId, UUID orderId) {
        return orders.findById(new OrderId(orderId)).filter(order -> order.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private SalesContract live(Order order) {
        return contracts.findLiveByOrderId(order.id().value()).orElseThrow(() -> new BusinessException(
                ErrorCode.SALES_CONTRACT_NOT_FOUND, "Order %s has no contract".formatted(order.orderNumber()))
                .localized("refusal.contract.none", order.orderNumber().value()));
    }

    private SalesContract live(Order order, long version) {
        SalesContract contract = live(order);
        requireVersion(contract, version);
        return contract;
    }

    private static void requireVersion(SalesContract contract, long version) {
        if (contract.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Contract %s changed since it was read"
                    .formatted(contract.contractNumber())).localized("refusal.contract.changed", contract.contractNumber());
        }
    }

    /**
     * The contract the customer may read: a sent one - or the draft of the next revision after they asked for changes
     * (revision 2 and later), so the portal can say it is being revised; a first draft is Sales' work in progress and
     * reads as absent.
     */
    private SalesContract portalContract(Order order) {
        return contracts.findLiveByOrderId(order.id().value())
                .filter(c -> c.status() != SalesContractStatus.DRAFT || c.revision() > 1)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_CONTRACT_NOT_FOUND,
                        "Order %s has no contract sent".formatted(order.orderNumber()))
                        .localized("refusal.contract.noneSent", order.orderNumber().value()));
    }

    /** A sent contract, for the customer: a draft is Sales' work in progress and reads as absent. */
    private SalesContract sentContract(Order order) {
        return contracts.findLiveByOrderId(order.id().value()).filter(c -> c.status() != SalesContractStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_CONTRACT_NOT_FOUND,
                        "Order %s has no contract sent".formatted(order.orderNumber()))
                        .localized("refusal.contract.noneSent", order.orderNumber().value()));
    }

    // ---- building --------------------------------------------------------------------------------------------------

    private ContractParty sellerParty() {
        return new ContractParty(seller.name(), seller.taxCode(), seller.address(), seller.representative(),
                seller.representativeTitle());
    }

    private static ContractParty buyerParty(CustomerLegalProfile buyer) {
        return new ContractParty(buyer.companyName(), buyer.taxCode(), buyer.registeredAddress(),
                buyer.representativeName(), buyer.representativeTitle());
    }

    /** 15 BR-12: credit accounting approved - a limit above 0 - is what paying after delivery needs. */
    private static boolean creditApproved(CreditTerms credit) {
        return credit != null && credit.creditLimit() != null && credit.creditLimit().signum() > 0;
    }

    /**
     * The customer's default payment schedule (14 §4.3 step 11, 18 §3) as the new contract's plan: its shares of the
     * total with tax. One awaiting accounting's approval is not drafted on (15 BR-12): everything on signing then, which
     * Sales changes.
     */
    private List<ContractTerms.InstallmentEntry> defaultPlan(UUID customerId) {
        DefaultPaymentSchedule schedule = paymentSchedules.defaultPaymentSchedule(customerId);
        if (schedule.awaitingApproval() || schedule.installments().isEmpty()) {
            return List.of(new ContractTerms.InstallmentEntry(ON_SIGNING, PaymentMilestone.ON_SIGNING, null,
                    HUNDRED, com.stockflow.customer.api.DefaultPaymentSchedule.DEFAULT_ON_SIGNING_DUE_DAYS, null));
        }
        return schedule.installments().stream()
                .map(i -> new ContractTerms.InstallmentEntry(i.name(), PaymentMilestone.valueOf(i.milestone().name()),
                        null, i.percent(), i.milestone() == com.stockflow.customer.api.PaymentMilestone.BEFORE_DELIVERY
                        ? null : i.dueDays(), null))
                .toList();
    }

    private static List<ContractTerms.BatchEntry> batches(List<Batch> given) {
        return given.stream().map(batch -> batch == null ? null : new ContractTerms.BatchEntry(batch.windowStart(),
                batch.windowEnd(), batch.lines() == null ? List.of() : batch.lines().stream()
                        .map(line -> line == null ? null : new ContractTerms.LineEntry(line.orderLineId(),
                                line.cartons()))
                        .toList()))
                .toList();
    }

    private static List<ContractTerms.InstallmentEntry> installments(List<Installment> given) {
        return given.stream().map(i -> i == null ? null : new ContractTerms.InstallmentEntry(i.name(),
                milestone(i.milestone()), i.amount(), i.percent(), i.dueDays(), i.deliveryBatchSeq())).toList();
    }

    private static PaymentMilestone milestone(String name) {
        if (name == null) {
            return null;
        }
        try {
            return PaymentMilestone.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** A planned installment as an entry again, kept as an amount: the plan of a draft whose schedule changed. */
    private static ContractTerms.InstallmentEntry entry(PlannedInstallment installment) {
        return new ContractTerms.InstallmentEntry(installment.name(), installment.milestone(),
                installment.percent() == null ? installment.amount().amount() : null, installment.percent(),
                installment.dueDays(), installment.deliveryBatchSeq());
    }

    /**
     * 15 BR-12: paying after delivery is credit, and only accounting grants it - checked again when the contract is sent,
     * so the customer is offered no credit accounting took back since the plan was drafted.
     */
    private void requireCreditFor(Order order, SalesContract contract) {
        if (contract.plan().stream().anyMatch(i -> i.milestone() == PaymentMilestone.AFTER_DELIVERY)
                && !creditApproved(customers.creditTerms(order.customerId()))) {
            throw new BusinessException(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT, ("Contract %s pays after delivery, "
                    + "and accounting approves no credit for this customer now").formatted(contract.contractNumber()))
                    .localized("refusal.contract.needsCreditNow", contract.contractNumber());
        }
    }

    /**
     * 14 §4.3 step 13: Sales are warned before they send - a DRAFT contract carries the warning its plan would raise
     * now, so they may change the plan, or send it knowing it will wait for accounting (15 BR-11 as changed on
     * 2026-11-26). Read again at each step: what the customer owes moves with every receipt.
     */
    private CreditWarning draftWarning(Order order, SalesContract contract) {
        return contract.status() == com.stockflow.order.internal.domain.SalesContractStatus.DRAFT
                ? creditWarning(order, contract) : null;
    }

    /**
     * 15 BR-11: what is paid after delivery is credit, checked when the contract is sent: the customer has something
     * overdue, or the exposure with it would pass the limit. On a draft it tells Sales the contract would wait for
     * accounting if sent as it is (2026-11-26: a refusal to send, no longer a warning only); on a contract sent after
     * accounting approved it, what was approved.
     */
    private CreditWarning creditWarning(Order order, SalesContract contract) {
        ContractCreditFigures check = creditCheck(order, contract);
        return check == null || !check.fails() ? null
                : new CreditWarning(check.outcome().name(), check.creditLimit(), check.exposure(),
                        check.afterDelivery());
    }

    /**
     * 15 BR-10, BR-11: the credit a contract asks for is what its plan pays after delivery; what the customer owes
     * besides is what the installments of their signed contracts paid after delivery still owe, not yet due included,
     * and the cancellation fee items not paid ({@code creditPosition}; since 4B-10 nothing else - the money ledger's
     * receivables and checkout's credit orders went). An installment overdue, or past the limit with this one: the
     * check fails. Null when the plan asks for no credit.
     */
    private ContractCreditFigures creditCheck(Order order, SalesContract contract) {
        BigDecimal afterDelivery = contract.plan().stream()
                .filter(i -> i.milestone() == PaymentMilestone.AFTER_DELIVERY).map(i -> i.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (afterDelivery.signum() == 0) {
            return null;
        }
        CreditPosition position = payments.creditPosition(order.customerId());
        // The other signed contracts' installments paid after delivery (15 BR-10), as every credit check counts them;
        // this contract's are not open yet - they open after.
        CreditTerms credit = customers.creditTerms(order.customerId());
        return ContractCreditFigures.check(credit == null ? null : credit.creditLimit(),
                position.outstandingReceivables(), afterDelivery, position.hasOverdue(), position.overdueAmount());
    }

    /**
     * The order placed by its signed contract (14 §4.3 step 15; 15 BR-07, BR-11): a plan paying after delivery is
     * checked against the customer's credit - recorded either way, and a failed check holds the order for accounting,
     * as a credit order over its limit at checkout is held (the same hold row, decided at {@code /credit-approval});
     * otherwise the order waits for the installment on signing, or is confirmed. Then {@code OrderPlaced}.
     *
     * <p>A contract accounting approved before it was sent (15 BR-11, decision 2 of the credit plan, 2026-11-26) is not
     * held for a check that still fails but is no worse than what was approved: the check is recorded as it came out,
     * naming the approval. Worse - something newly overdue, or more owed - and it is held as any other.</p>
     */
    private void placeSigned(Order order, SalesContract contract, UUID by, Instant now) {
        // The customer row locked first, as checkout does (15 BR-03): two contracts of one customer signed at once are
        // checked against the limit one after the other, each seeing the other's after-delivery part.
        customers.lockCreditTerms(order.customerId());
        ContractCreditFigures check = creditCheck(order, contract);
        Optional<ContractCreditReviews.Review> approved = check != null && check.fails()
                ? creditReviews.approved(contract.id()).filter(review -> check.noWorseThan(review.figures()))
                : Optional.empty();
        boolean hold = check != null && check.fails() && approved.isEmpty();
        boolean paysOnSigning = paysOnSigning(contract);
        order.contractSigned(hold, paysOnSigning, by, now);
        orders.save(order);
        if (check != null) {
            String note = approved.map(review -> "Sales contract %s; credit approved before it was sent (%s)"
                    .formatted(contract.contractNumber(), review.id()))
                    .orElse("Sales contract " + contract.contractNumber());
            creditChecks.record(new CreditChecks.Check(order.id().value(), now, check.creditLimit(), check.exposure(),
                    check.afterDelivery(), check.outcome(), null, null, note,
                    approved.map(ContractCreditReviews.Review::id).orElse(null)));
        }
        if (hold) {
            holds.save(new com.stockflow.order.internal.entity.OrderHoldJpaEntity(Identifiers.newId(),
                    order.id().value(), OrderServiceImpl.CREDIT_HOLD, now));
        }
        // 15 §4.1 step 1 (4B-6): the installments, in this transaction - after the credit check, which counts the plan's
        // after-delivery part itself; held for credit or not, a signed contract is owed what it says.
        payments.openInstallments(installmentsOf(order, contract));
        events.publishEventsOf(order);
    }

    /** The signed plan as payment opens it: each row, and the delivery batch it is tied to. */
    private static com.stockflow.payment.api.ContractInstallments installmentsOf(Order order, SalesContract contract) {
        Map<Integer, UUID> batches = new HashMap<>();
        contract.schedule().forEach(batch -> batches.put(batch.seq(), batch.id()));
        return new com.stockflow.payment.api.ContractInstallments(contract.id(), contract.contractNumber(),
                order.id().value(), order.orderNumber().value(), order.customerId(),
                order.total().currency().getCurrencyCode(), contract.signedOn(), contract.signedAt(),
                contract.plan().stream().map(i -> new com.stockflow.payment.api.ContractInstallments.Planned(i.id(),
                        i.seq(), i.name(), i.milestone().name(), i.amount().amount(), i.dueDays(),
                        i.deliveryBatchSeq() == null ? null : batches.get(i.deliveryBatchSeq()),
                        i.deliveryBatchSeq())).toList());
    }

    /** Whether the contract has an installment due on signing (14 §4.3 step 12): the order waits for it. */
    static boolean paysOnSigning(SalesContract contract) {
        return contract.plan().stream().anyMatch(i -> i.milestone() == PaymentMilestone.ON_SIGNING);
    }

    private ContractView view(SalesContract contract, Order order, CreditWarning warning) {
        Map<UUID, OrderLine> lines = new HashMap<>();
        order.lines().forEach(line -> lines.put(line.id(), line));
        Map<Integer, Money> values = ContractTerms.batchValues(order, contract.schedule());
        // R5-3: the design behind each SKU, for the whole schedule at once.
        Map<String, com.stockflow.design.api.SkuDesign> designs = skuDesigns.ofSkus(order.lines().stream()
                .map(line -> line.sku().code()).distinct().toList());
        // What each SKU is called, for the whole schedule at once: a schedule line reads as more than a code.
        Map<String, LineNames.Names> names = lineNames.of(order.lines().stream().map(line -> line.sku().code())
                .toList());
        List<BatchView> schedule = contract.schedule().stream().map(batch -> new BatchView(batch.seq(),
                batch.windowStart(), batch.windowEnd(), values.get(batch.seq()).amount(), batch.lines().stream()
                .map(line -> {
                    OrderLine orderLine = lines.get(line.orderLineId());
                    com.stockflow.design.api.SkuDesign design = orderLine == null ? null
                            : designs.get(orderLine.sku().code());
                    LineNames.Names named = orderLine == null ? LineNames.Names.NONE
                            : LineNames.of(names, orderLine.sku().code());
                    return new BatchLineView(line.orderLineId(), orderLine == null ? null : orderLine.sku().code(),
                            line.cartons(), orderLine == null || orderLine.unitsPerCarton() == null ? 0
                            : line.cartons() * orderLine.unitsPerCarton(),
                            orderLine == null ? null : orderLine.unitsPerCarton(),
                            design == null ? null : design.name(), design == null ? null : design.previewUrl(),
                            named.productName(), named.variantName());
                }).toList())).toList();
        // R5-12: a signed contract's plan made instalments; each row shows when its own falls due and how late it is.
        Map<Integer, com.stockflow.payment.api.CustomerInstallments.PlanDue> dues = contract.signedAt() == null
                ? Map.of() : installments.planDues(contract.id());
        List<InstallmentView> plan = contract.plan().stream()
                .map(i -> installmentView(i, dues.get(i.seq()))).toList();
        boolean overdue = contract.overdueToSign(today());
        ContractAgreement agreement = contract.agreement();
        SignatureView signature = evidence.view(contract.id(), contract.revision(), true);
        return new ContractView(contract.id(), contract.contractNumber(), contract.orderId(),
                order.orderNumber().value(), contract.status().name(), party(contract.seller()),
                party(contract.buyer()), contract.freeSampleRounds(), new Totals(order.netTotal().amount(),
                order.taxTotal().amount(), order.total().amount(), order.total().currency().getCurrencyCode()),
                schedule, plan, contract.sentAt(), contract.sentBy(), contract.signedOn(), contract.signedFileName(),
                contract.signedAt(), contract.signedBy(), contract.cancelledAt(), contract.cancelledBy(),
                contract.cancellationReason(), contract.completedAt(), contract.version(), warning,
                contract.returnWindowDays(), appendixAvailable(contract),
                contract.creditReviewPending() ? "PENDING" : "NONE",
                creditReviews.latest(contract.id()).map(SalesContractService::creditReviewView).orElse(null),
                contract.revision(), contract.signBy(), overdue, agreement.specialTerms(), agreement.penaltyPercent(),
                agreement.quantityTolerancePercent(), agreement.warrantyMonths(),
                agreement.seller().isNone() ? null : new SellerSigner(agreement.seller().name(),
                        agreement.seller().title(), agreement.seller().authorizationRef()),
                agreement.buyer().isNone() ? null : new BuyerSigner(agreement.buyer().name(),
                        agreement.buyer().title()),
                new StandardTermsView(defaults.penaltyPercent(), defaults.quantityTolerancePercent(),
                        defaults.warrantyMonths()), contract.nonStandard(), contract.termsReview().name(),
                contract.termsReview() == TermsReview.NONE ? null : new TermsReviewView(contract.termsReviewNote(),
                        contract.termsReviewRequestedBy(), contract.termsReviewRequestedAt(),
                        contract.termsReviewedBy(), contract.termsReviewedAt()),
                contract.customerSignedAt(), contract.status() == SalesContractStatus.SENT && !overdue,
                contract.status() == SalesContractStatus.SENT, signature.customer(), signature.company(),
                signature.verificationCode());
    }

    private static CreditReviewView creditReviewView(ContractCreditReviews.Review review) {
        ContractCreditFigures figures = review.figures();
        return new CreditReviewView(review.id(), review.outcome().name(), figures.outcome().name(),
                figures.creditLimit(), figures.exposure(), figures.afterDelivery(), figures.overdueAmount(),
                review.contractVersion(), review.submittedAt(), review.submittedBy(), review.decidedAt(),
                review.decidedBy(), review.note());
    }

    /**
     * Whether {@code ContractPaperService.appendix} has something to draw for this contract: it is signed, and the
     * window in force of one of its batches - the latest move, else the one signed - differs from the window it was
     * signed with. A window moved and moved back has no appendix, as the paper has none. One read of the order's moves,
     * and only for a signed contract, so a contract's history costs at most one.
     */
    private boolean appendixAvailable(SalesContract contract) {
        if (contract.signedOn() == null || contract.signedAt() == null
                || contract.status() == SalesContractStatus.CANCELLED) {
            return false;
        }
        Map<UUID, com.stockflow.order.internal.domain.DeliveryBatchRecords.WindowChange> latest = new HashMap<>();
        batchRecords.windowChangesOf(contract.orderId()).forEach(change -> latest.put(change.batchId(), change));
        for (DeliveryBatch batch : contract.schedule()) {
            var moved = latest.get(batch.id());
            if (moved != null && batch.windowStart() != null && (!batch.windowStart().equals(moved.newStart())
                    || !Objects.equals(batch.windowEnd(), moved.newEnd()))) {
                return true;
            }
        }
        return false;
    }

    private static InstallmentView installmentView(PlannedInstallment i) {
        return installmentView(i, null);
    }

    private static InstallmentView installmentView(PlannedInstallment i,
                                                   com.stockflow.payment.api.CustomerInstallments.PlanDue due) {
        return new InstallmentView(i.seq(), i.name(), i.milestone().name(), i.percent(), i.amount().amount(),
                i.dueDays(), i.deliveryBatchSeq(), due == null ? null : due.dueDate(),
                due == null ? 0 : due.daysOverdue());
    }

    private static Party party(ContractParty party) {
        return new Party(party.name(), party.taxCode(), party.address(), party.representative(),
                party.representativeTitle());
    }

    /** What the PDF shows; dated the day it was sent, or today for a draft's preview. */
    private SalesContractDocument.Facts facts(SalesContract contract, Order order) {
        return facts(contract, order, LocalDate.ofInstant(
                contract.sentAt() == null ? clock.instant() : contract.sentAt(), VIETNAM), false);
    }

    /** @param issued the copy that goes to the customer, which a draft's watermark must not cross */
    private SalesContractDocument.Facts facts(SalesContract contract, Order order, LocalDate dated, boolean issued) {
        return new SalesContractDocument.Facts(view(contract, order, null), order, dated, shared.head(),
                shared.names(order.lines().stream().map(line -> line.sku().code()).toList()), issued);
    }
}
