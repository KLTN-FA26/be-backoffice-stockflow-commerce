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
import com.stockflow.order.internal.domain.ContractParty;
import com.stockflow.order.internal.domain.ContractTerms;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.PaymentMilestone;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** See {@link SalesContracts}. */
@Service
@Transactional
class SalesContractService implements SalesContracts {

    private static final Logger log = LoggerFactory.getLogger(SalesContractService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
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
    private final com.stockflow.order.internal.domain.CancellationRequestRepository cancellationRequests;

    SalesContractService(OrderRepository orders, SalesContractRepository contracts, CustomerService customers,
                         CustomerLegalProfiles legalProfiles, CustomerPaymentSchedules paymentSchedules,
                         PaymentService payments, CreditChecks creditChecks, FileTransfers files, StagedUploads staged,
                         SalesContractDocument document, SellerProperties seller, ContractProperties defaults,
                         TransactionTemplate tx, Clock clock, OrderEventPublisher events,
                         com.stockflow.order.internal.repository.OrderHoldJpaRepository holds,
                         com.stockflow.order.internal.domain.CancellationRequestRepository cancellationRequests) {
        this.events = events;
        this.holds = holds;
        this.cancellationRequests = cancellationRequests;
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
        if (contracts.findLiveByOrderId(orderId).isPresent()) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_EXISTS, ("Order %s already has a contract in force; "
                    + "cancel it to draw up another").formatted(order.orderNumber()));
        }
        if (!seller.complete()) {
            throw new BusinessException(ErrorCode.SELLER_PROFILE_UNAVAILABLE,
                    "stockflow.seller.* is not configured in this deployment");
        }
        CustomerLegalProfile buyer = legalProfiles.legalProfile(order.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_LEGAL_PROFILE_MISSING, ("Customer of "
                        + "order %s: record the registered address and the legal representative first (18 BR-05)")
                        .formatted(order.orderNumber())));
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
                        : given.freeSampleRounds(), schedule, plan);
        SalesContract saved = contracts.save(contract);
        log.info("Sales contract {} drawn up for order {}", saved.contractNumber(), order.orderNumber());
        return view(saved, order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractView get(UUID orderId) {
        Order order = readInScope(orderId);
        return view(live(order), order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractView> history(UUID orderId) {
        Order order = readInScope(orderId);
        return contracts.findByOrderId(orderId).stream().map(contract -> view(contract, order, null)).toList();
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView edit(CurrentUser user, UUID orderId, long version, Draft draft) {
        Order order = lockInScope(orderId);
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
        return view(contracts.save(contract), order, null);
    }

    /**
     * Not transactional itself: the PDF is drawn and kept with no lock held - storage is slow, and the order's row lock
     * makes everyone else wait - from the version read; the contract is then sent in one short transaction if it is
     * still that version, and the PDF goes if it is not.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'SENT'}")
    public ContractView send(CurrentUser user, UUID orderId, long version) {
        SalesContractDocument.Facts facts = tx.execute(status -> {
            Order order = lockInScope(orderId);
            SalesContract contract = live(order, version);
            Instant now = clock.instant();
            contract.requireSendable(order, now);
            requireCreditFor(order, contract);
            return new SalesContractDocument.Facts(view(contract, order, null), order, LocalDate.ofInstant(now,
                    VIETNAM));
        });
        byte[] pdf = document.render(facts);
        try (PreparedFiles prepared = IncomingFiles.sent(List.of(new FileUpload(facts.contract().contractNumber()
                        + ".pdf", "application/pdf", pdf.length, new ByteArrayInputStream(pdf))))
                .prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                Order order = lockInScope(orderId);
                SalesContract contract = live(order, version);
                requireCreditFor(order, contract);
                Instant now = clock.instant();
                contract.send(order, now, user.userId(), stored.key());
                SalesContract saved = contracts.save(contract);
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "One signed file and the day it was signed");
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
        tx.executeWithoutResult(status -> live(readOrder(orderId), version).requireSignable(signedOn,
                clock.instant()));
        try (PreparedFiles prepared = signedFile.prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                Order order = lockInScope(orderId);
                SalesContract contract = live(order, version);
                Instant now = clock.instant();
                contract.sign(signedOn, stored.key(), stored.originalName(), now, user.userId());
                SalesContract saved = contracts.save(contract);
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
        SalesContract contract = live(order, version);
        Instant now = clock.instant();
        contract.cancel(reason, now, user.userId());
        SalesContract saved = contracts.save(contract);
        // 14 §4.3 step 16: cancelled to be drawn up again - the order goes back to DRAFT (4B-5). A cancellation request
        // the customer sent meanwhile closes: the order is out of their sight until a new contract is sent.
        boolean wasWaiting = order.status() == com.stockflow.order.api.OrderStatus.PENDING_CONTRACT;
        order.withdrawContract(user.userId(), now);
        orders.save(order);
        if (wasWaiting) {
            cancellationRequests.findPending(orderId).ifPresent(request -> {
                request.expire("Closed: the contract was withdrawn to be drawn up again; ask once it is sent.", now);
                cancellationRequests.save(request);
            });
        }
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
                    .formatted(contract.contractNumber()));
        }
        return files.downloadLink(contract.sentDocumentKey(), contract.contractNumber() + ".pdf");
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink signedFile(UUID orderId) {
        SalesContract contract = live(readInScope(orderId));
        if (contract.signedFileKey() == null) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s is not signed"
                    .formatted(contract.contractNumber()));
        }
        return files.downloadLink(contract.signedFileKey(), contract.signedFileName());
    }

    @Override
    @Transactional(readOnly = true)
    public ContractView customerContract(UUID customerId, UUID orderId) {
        Order order = customersOrder(customerId, orderId);
        ContractView full = view(sentContract(order), order, null);
        // The customer's copy: who in the back office sent or signed it is not theirs to read.
        return new ContractView(full.id(), full.contractNumber(), full.orderId(), full.orderNumber(), full.status(),
                full.seller(), full.buyer(), full.freeSampleRounds(), full.totals(), full.deliverySchedule(),
                full.paymentPlan(), full.sentAt(), null, full.signedOn(), full.signedFileName(), full.signedAt(), null,
                null, null, null, full.completedAt(), full.version(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink customerDocument(UUID customerId, UUID orderId) {
        SalesContract contract = sentContract(customersOrder(customerId, orderId));
        return contract.signedFileKey() != null
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

    private Order customersOrder(UUID customerId, UUID orderId) {
        return orders.findById(new OrderId(orderId)).filter(order -> order.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private SalesContract live(Order order) {
        return contracts.findLiveByOrderId(order.id().value()).orElseThrow(() -> new BusinessException(
                ErrorCode.SALES_CONTRACT_NOT_FOUND, "Order %s has no contract".formatted(order.orderNumber())));
    }

    private SalesContract live(Order order, long version) {
        SalesContract contract = live(order);
        requireVersion(contract, version);
        return contract;
    }

    private static void requireVersion(SalesContract contract, long version) {
        if (contract.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Contract %s changed since it was read"
                    .formatted(contract.contractNumber()));
        }
    }

    /** A sent contract, for the customer: a draft is Sales' work in progress and reads as absent. */
    private SalesContract sentContract(Order order) {
        return contracts.findLiveByOrderId(order.id().value()).filter(c -> c.status() != SalesContractStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_CONTRACT_NOT_FOUND,
                        "Order %s has no contract sent".formatted(order.orderNumber())));
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
            return List.of(new ContractTerms.InstallmentEntry("On signing", PaymentMilestone.ON_SIGNING, null,
                    HUNDRED, 0, null));
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
                    + "and accounting approves no credit for this customer now").formatted(contract.contractNumber()));
        }
    }

    /**
     * 15 BR-11: what is paid after delivery is credit, checked when the contract is sent - a warning, never a refusal:
     * the customer has something overdue, or the exposure with it would pass the limit.
     */
    private CreditWarning creditWarning(Order order, SalesContract contract) {
        CreditCheck check = creditCheck(order, contract);
        return check == null || check.outcome() == CreditChecks.Outcome.WITHIN_LIMIT ? null
                : new CreditWarning(check.outcome().name(), check.limit(), check.exposure(), check.afterDelivery());
    }

    /** What a contract's plan asks of the customer's credit, against what they owe and may owe. */
    private record CreditCheck(CreditChecks.Outcome outcome, BigDecimal limit, BigDecimal exposure,
                               BigDecimal afterDelivery) { }

    /**
     * 15 BR-10, BR-11: the credit a contract asks for is what its plan pays after delivery; what the customer owes
     * besides is what the installments of their signed contracts paid after delivery still owe, not yet due included,
     * and the cancellation fee items not paid ({@code creditPosition}; since 4B-10 nothing else - the money ledger's
     * receivables and checkout's credit orders went). An installment overdue, or past the limit with this one: the
     * check fails. Null when the plan asks for no credit.
     */
    private CreditCheck creditCheck(Order order, SalesContract contract) {
        BigDecimal afterDelivery = contract.plan().stream()
                .filter(i -> i.milestone() == PaymentMilestone.AFTER_DELIVERY).map(i -> i.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (afterDelivery.signum() == 0) {
            return null;
        }
        CreditPosition position = payments.creditPosition(order.customerId());
        // The other signed contracts' installments paid after delivery (15 BR-10), as every credit check counts them;
        // this contract's are not open yet - they open after.
        BigDecimal exposure = position.outstandingReceivables();
        CreditTerms credit = customers.creditTerms(order.customerId());
        BigDecimal limit = credit == null || credit.creditLimit() == null ? BigDecimal.ZERO : credit.creditLimit();
        CreditChecks.Outcome outcome = position.hasOverdue() ? CreditChecks.Outcome.OVERDUE
                : exposure.add(afterDelivery).compareTo(limit) > 0 ? CreditChecks.Outcome.OVER_LIMIT
                : CreditChecks.Outcome.WITHIN_LIMIT;
        return new CreditCheck(outcome, limit, exposure, afterDelivery);
    }

    /**
     * The order placed by its signed contract (14 §4.3 step 15; 15 BR-07, BR-11): a plan paying after delivery is
     * checked against the customer's credit - recorded either way, and a failed check holds the order for accounting,
     * as a credit order over its limit at checkout is held (the same hold row, decided at {@code /credit-approval});
     * otherwise the order waits for the installment on signing, or is confirmed. Then {@code OrderPlaced}.
     */
    private void placeSigned(Order order, SalesContract contract, UUID by, Instant now) {
        // The customer row locked first, as checkout does (15 BR-03): two contracts of one customer signed at once are
        // checked against the limit one after the other, each seeing the other's after-delivery part.
        customers.lockCreditTerms(order.customerId());
        CreditCheck check = creditCheck(order, contract);
        boolean hold = check != null && check.outcome() != CreditChecks.Outcome.WITHIN_LIMIT;
        boolean paysOnSigning = paysOnSigning(contract);
        order.contractSigned(hold, paysOnSigning, by, now);
        orders.save(order);
        if (check != null) {
            creditChecks.record(new CreditChecks.Check(order.id().value(), now, check.limit(), check.exposure(),
                    check.afterDelivery(), check.outcome(), null, null, "Sales contract " + contract.contractNumber()));
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
        List<BatchView> schedule = contract.schedule().stream().map(batch -> new BatchView(batch.seq(),
                batch.windowStart(), batch.windowEnd(), values.get(batch.seq()).amount(), batch.lines().stream()
                .map(line -> {
                    OrderLine orderLine = lines.get(line.orderLineId());
                    return new BatchLineView(line.orderLineId(), orderLine == null ? null : orderLine.sku().code(),
                            line.cartons(), orderLine == null || orderLine.unitsPerCarton() == null ? 0
                            : line.cartons() * orderLine.unitsPerCarton());
                }).toList())).toList();
        List<InstallmentView> plan = contract.plan().stream().map(SalesContractService::installmentView).toList();
        return new ContractView(contract.id(), contract.contractNumber(), contract.orderId(),
                order.orderNumber().value(), contract.status().name(), party(contract.seller()),
                party(contract.buyer()), contract.freeSampleRounds(), new Totals(order.netTotal().amount(),
                order.taxTotal().amount(), order.total().amount(), order.total().currency().getCurrencyCode()),
                schedule, plan, contract.sentAt(), contract.sentBy(), contract.signedOn(), contract.signedFileName(),
                contract.signedAt(), contract.signedBy(), contract.cancelledAt(), contract.cancelledBy(),
                contract.cancellationReason(), contract.completedAt(), contract.version(), warning);
    }

    private static InstallmentView installmentView(PlannedInstallment i) {
        return new InstallmentView(i.seq(), i.name(), i.milestone().name(), i.percent(), i.amount().amount(),
                i.dueDays(), i.deliveryBatchSeq());
    }

    private static Party party(ContractParty party) {
        return new Party(party.name(), party.taxCode(), party.address(), party.representative(),
                party.representativeTitle());
    }

    /** What the PDF shows; dated the day it was sent, or today for a draft's preview. */
    private SalesContractDocument.Facts facts(SalesContract contract, Order order) {
        return new SalesContractDocument.Facts(view(contract, order, null), order, LocalDate.ofInstant(
                contract.sentAt() == null ? clock.instant() : contract.sentAt(), VIETNAM));
    }
}
