package com.stockflow.order.internal.service;

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

    SalesContractService(OrderRepository orders, SalesContractRepository contracts, CustomerService customers,
                         CustomerLegalProfiles legalProfiles, CustomerPaymentSchedules paymentSchedules,
                         PaymentService payments, CreditChecks creditChecks, FileTransfers files, StagedUploads staged,
                         SalesContractDocument document, SellerProperties seller, ContractProperties defaults,
                         TransactionTemplate tx, Clock clock) {
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
                    "stockflow.order.seller.* is not configured in this deployment");
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
        Order order = inScope(orderId);
        return view(live(order), order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractView> history(UUID orderId) {
        Order order = inScope(orderId);
        return contracts.findByOrderId(orderId).stream().map(contract -> view(contract, order, null)).toList();
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId}")
    public ContractView edit(CurrentUser user, UUID orderId, long version, Draft draft) {
        Order order = lockInScope(orderId);
        SalesContract contract = live(order, version);
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

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sales-contract", resourceId = "#result?.id()",
            details = "{orderId: #orderId, to: 'SENT'}")
    public ContractView send(CurrentUser user, UUID orderId, long version) {
        Order order = lockInScope(orderId);
        SalesContract contract = live(order, version);
        Instant now = clock.instant();
        contract.requireSendable(order, now);
        byte[] pdf = document.render(facts(contract, order));
        StoredFile stored = files.store(FileCategory.DOCUMENT, new FileUpload(contract.contractNumber() + ".pdf",
                "application/pdf", pdf.length, new ByteArrayInputStream(pdf), null));
        contract.send(order, now, user.userId(), stored.key());
        SalesContract saved = contracts.save(contract);
        files.retainAfterCommit(stored.key(), RecordRetention.SALES_CONTRACT);
        log.info("Sales contract {} of order {} sent", saved.contractNumber(), order.orderNumber());
        return view(saved, order, creditWarning(order, saved));
    }

    @Override
    @Transactional(readOnly = true)
    public UploadSlot beginSignedFileUpload(CurrentUser user, UUID orderId, UploadSlotRequest request) {
        live(inScope(orderId)).requireSignable();
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
                Order order = inScope(orderId);
                return contracts.findLiveByOrderId(orderId).filter(c -> k.equals(c.signedFileKey()))
                        .map(c -> view(c, order, null));
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        tx.executeWithoutResult(status -> live(inScope(orderId), version).requireSignable());
        try (PreparedFiles prepared = signedFile.prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                Order order = lockInScope(orderId);
                SalesContract contract = live(order, version);
                contract.sign(signedOn, stored.key(), stored.originalName(), clock.instant(), user.userId());
                SalesContract saved = contracts.save(contract);
                prepared.adopt();
                files.retainAfterCommit(stored.key(), RecordRetention.SALES_CONTRACT);
                log.info("Sales contract {} of order {} signed on {}", saved.contractNumber(), order.orderNumber(),
                        signedOn);
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
        contract.cancel(reason, clock.instant(), user.userId());
        SalesContract saved = contracts.save(contract);
        log.info("Sales contract {} of order {} cancelled", saved.contractNumber(), order.orderNumber());
        return view(saved, order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] preview(UUID orderId) {
        Order order = inScope(orderId);
        return document.render(facts(live(order), order));
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink sentDocument(UUID orderId) {
        SalesContract contract = live(inScope(orderId));
        if (contract.sentDocumentKey() == null) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, "Contract %s was not sent yet"
                    .formatted(contract.contractNumber()));
        }
        return files.downloadLink(contract.sentDocumentKey(), contract.contractNumber() + ".pdf");
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink signedFile(UUID orderId) {
        SalesContract contract = live(inScope(orderId));
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
        return view(sentContract(order), order, null);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink customerDocument(UUID customerId, UUID orderId) {
        SalesContract contract = sentContract(customersOrder(customerId, orderId));
        return contract.signedFileKey() != null
                ? files.downloadLink(contract.signedFileKey(), contract.signedFileName())
                : files.downloadLink(contract.sentDocumentKey(), contract.contractNumber() + ".pdf");
    }

    // ---- loading ---------------------------------------------------------------------------------------------------

    /** The order, read as the back office reads an order ({@code GET /orders/{id}}). */
    private Order inScope(UUID orderId) {
        return orders.findById(new OrderId(orderId))
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
        if (contract.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Contract %s changed since it was read"
                    .formatted(contract.contractNumber()));
        }
        return contract;
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

    /** 15 BR-12: credit accounting approved - allowed, with a limit above 0 - is what paying after delivery needs. */
    private static boolean creditApproved(CreditTerms credit) {
        return credit != null && credit.allowCredit() && credit.creditLimit() != null
                && credit.creditLimit().signum() > 0;
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
     * 15 BR-11: what is paid after delivery is credit, checked when the contract is sent - a warning, never a refusal:
     * the customer has something overdue, or the exposure with it would pass the limit.
     */
    private CreditWarning creditWarning(Order order, SalesContract contract) {
        BigDecimal afterDelivery = contract.plan().stream()
                .filter(i -> i.milestone() == PaymentMilestone.AFTER_DELIVERY).map(i -> i.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (afterDelivery.signum() == 0) {
            return null;
        }
        CreditPosition position = payments.creditPosition(order.customerId());
        BigDecimal exposure = creditChecks.undeliveredCreditExposure(order.customerId(), order.id().value())
                .add(position.outstandingReceivables());
        CreditTerms credit = customers.creditTerms(order.customerId());
        BigDecimal limit = credit == null || credit.creditLimit() == null ? BigDecimal.ZERO : credit.creditLimit();
        String outcome = position.hasOverdue() ? "OVERDUE"
                : exposure.add(afterDelivery).compareTo(limit) > 0 ? "OVER_LIMIT" : null;
        return outcome == null ? null : new CreditWarning(outcome, limit, exposure, afterDelivery);
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
        List<InstallmentView> plan = contract.plan().stream().map(i -> new InstallmentView(i.seq(), i.name(),
                i.milestone().name(), i.percent(), i.amount().amount(), i.dueDays(), i.deliveryBatchSeq())).toList();
        return new ContractView(contract.id(), contract.contractNumber(), contract.orderId(),
                order.orderNumber().value(), contract.status().name(), party(contract.seller()),
                party(contract.buyer()), contract.freeSampleRounds(), new Totals(order.netTotal().amount(),
                order.taxTotal().amount(), order.total().amount(), order.total().currency().getCurrencyCode()),
                schedule, plan, contract.sentAt(), contract.sentBy(), contract.signedOn(), contract.signedFileName(),
                contract.signedAt(), contract.signedBy(), contract.cancelledAt(), contract.cancelledBy(),
                contract.cancellationReason(), contract.completedAt(), contract.version(), warning);
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
