package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.ContractInstallments;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import com.stockflow.contracts.InstallmentDue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A signed contract's installments (4B-6, kltn-docs 15 §4.1, §4.3, §5.1, §5.2): opened with the signature, due when
 * their milestone comes, overdue - a flag, not a status - past their due day. Receipts pay them ({@link PaymentReceiptService}); a cancellation
 * or a closure of the rest settles them with the order ({@link OrderSettlementService}, 4B-9).
 *
 * <ul>
 *   <li>ON_SIGNING: due from the signature, by the day signed plus its days.</li>
 *   <li>BEFORE_DELIVERY: due when the order is released to be shipped ({@code OrderReleased}); no due day - it blocks
 *       the handover until paid (BR-08), which the order module reads through {@link InstallmentStanding}.</li>
 *   <li>AFTER_DELIVERY: due when the order is delivered in full - the last delivery, or the rest closed after it (15
 *       D23) - by that day plus its days (BR-09): the customer's receivable.</li>
 * </ul>
 *
 * <p>An installment tied to a delivery batch follows that batch (4B-8, 17 §4.5): one before delivery falls due when the
 * batch is released, one after delivery when the batch is delivered in full.</p>
 */
@Service
@Transactional
public class InstallmentService {

    private static final Logger log = LoggerFactory.getLogger(InstallmentService.class);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final SortWhitelist SORT = SortWhitelist.of("dueDate", "seq", "amount", "status", "createdAt")
            .withDefault("dueDate", Sort.Direction.ASC);

    /** An installment and the receipts that paid it. */
    public record InstallmentDetail(Installment installment, List<InstallmentBook.ReceiptOfInstallment> receipts) {
    }

    /** An order's installments and where they stand (15 §5.2). */
    public record OrderInstallments(InstallmentStanding standing, List<Installment> installments) {
    }

    /**
     * How the customer pays one installment by bank transfer (15 §4.2 step 5): the company's account, what is left to
     * pay, and what to write in the transfer.
     */
    public record TransferInstructions(Installment installment, CompanyBankAccount account, BigDecimal amountDue,
                                       String reference) {
    }

    private final InstallmentBook book;
    private final InstallmentSearch search;
    private final InstallmentOrders orders;
    private final CompanyBankProperties bank;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Autowired
    InstallmentService(InstallmentBook book, InstallmentSearch search, InstallmentOrders orders, CompanyBankProperties bank,
                       Clock clock, ApplicationEventPublisher events) {
        this.book = book;
        this.search = search;
        this.orders = orders;
        this.bank = bank;
        this.clock = clock;
        this.events = events;
    }

    /** Without anyone told that an installment fell due: the unit tests that drive the arithmetic alone. */
    InstallmentService(InstallmentBook book, InstallmentSearch search, InstallmentOrders orders, CompanyBankProperties bank,
                       Clock clock) {
        this(book, search, orders, bank, clock, event -> { });
    }

    /** {@link com.stockflow.payment.api.PaymentService#openInstallments}. */
    public void open(ContractInstallments contract) {
        if (book.opened(contract.contractId())) {
            return;
        }
        LocalDate signedOn = contract.signedOn();
        for (ContractInstallments.Planned planned : contract.installments()) {
            Installment installment = Installment.open(contract.contractId(), planned.contractInstallmentId(),
                    contract.orderId(), contract.customerId(), contract.contractNumber(), contract.orderNumber(),
                    planned.seq(), planned.name(), InstallmentMilestone.valueOf(planned.milestone()),
                    planned.deliveryBatchId(), planned.deliveryBatchSeq(), planned.amount(), contract.currency(),
                    planned.dueDays());
            boolean due = installment.milestone() == InstallmentMilestone.ON_SIGNING
                    && installment.reach(contract.signedAt(), signedOn);
            book.save(installment);
            if (due) {
                fellDue(installment);
            }
        }
        log.info("Contract {} of order {}: {} installment(s) opened", contract.contractNumber(), contract.orderNumber(),
                contract.installments().size());
    }

    /** The order was released to be shipped: what it pays before delivery is due (15 §4.1 step 2). */
    public void released(UUID orderId, Instant at) {
        released(orderId, at, null);
    }

    /**
     * 4B-8 (15 §4.1, 17 §4.5): a delivery batch released makes due the installments before delivery tied to it, and
     * those tied to no batch (due with the first release); null: the order released whole, every one of them.
     */
    public void released(UUID orderId, Instant at, UUID deliveryBatchId) {
        LocalDate day = LocalDate.ofInstant(at, BUSINESS_ZONE);
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() == InstallmentMilestone.BEFORE_DELIVERY
                    && (deliveryBatchId == null || installment.deliveryBatchId() == null
                            || deliveryBatchId.equals(installment.deliveryBatchId()))
                    && installment.reach(at, day)) {
                book.save(installment);
                fellDue(installment);
                log.info("Installment {} of contract {} is due (BEFORE_DELIVERY{})", installment.seq(),
                        installment.contractNumber(), deliveryBatchId == null ? "" : ", its delivery batch released");
            }
        }
    }

    /** {@link com.stockflow.payment.api.PaymentService#dueBeforeHandover(UUID, UUID)}. */
    @Transactional(readOnly = true)
    public java.math.BigDecimal dueBeforeHandover(UUID orderId, UUID deliveryBatchId) {
        return book.ofOrder(orderId).stream().filter(i -> i.status() != InstallmentStatus.CANCELLED)
                .filter(i -> i.milestone() == InstallmentMilestone.ON_SIGNING
                        || i.milestone() == InstallmentMilestone.BEFORE_DELIVERY
                        && (deliveryBatchId == null || i.deliveryBatchId() == null
                                || deliveryBatchId.equals(i.deliveryBatchId())))
                .map(Installment::outstanding).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /**
     * Goods of the order were delivered, or the rest of it closed (15 §4.1 step 2, D23): what it pays after delivery is
     * due from the day its goods were delivered. An installment tied to a delivery batch counts from the day that batch
     * was delivered in full (4B-8, 17 §4.5); one tied to no batch, from the day the whole order was. A delivery of part
     * of a batch, or of a split order, waits for the last one.
     *
     * @param wholeOrder the event delivered the whole order at once (no shipment of its own)
     */
    public void delivered(UUID orderId, Instant at, boolean wholeOrder) {
        if (wholeOrder) {
            reach(orderId, InstallmentMilestone.AFTER_DELIVERY, at);
            return;
        }
        // The days as the order has them, read before the installments are locked (orders before installments): a
        // shipment's event handled late - after a later delivery - must not date the receivable from an earlier one.
        java.util.Map<UUID, Instant> batches = orders.batchesDeliveredAt(orderId);
        Instant whole = orders.deliveredInFullAt(orderId).orElse(null);
        if (batches.isEmpty() && whole == null) {
            return;
        }
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() != InstallmentMilestone.AFTER_DELIVERY) {
                continue;
            }
            Instant from = installment.deliveryBatchId() == null ? whole
                    : batches.getOrDefault(installment.deliveryBatchId(), whole);
            if (from != null && installment.reach(from, LocalDate.ofInstant(from, BUSINESS_ZONE))) {
                book.save(installment);
                fellDue(installment);
                log.info("Installment {} of contract {} is due (AFTER_DELIVERY) by {}", installment.seq(),
                        installment.contractNumber(), installment.dueDate());
            }
        }
    }

    private void reach(UUID orderId, InstallmentMilestone milestone, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, BUSINESS_ZONE);
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() == milestone && installment.reach(at, day)) {
                book.save(installment);
                fellDue(installment);
                log.info("Installment {} of contract {} is due ({}){}", installment.seq(),
                        installment.contractNumber(), milestone,
                        installment.dueDate() == null ? "" : " by " + installment.dueDate());
            }
        }
    }

    /**
     * {@link com.stockflow.payment.api.PaymentService#chargeFailedDelivery}: the fee item of a failed trip, numbered
     * after the contract's plan and its other fee items, due from today. The order's installments are locked first, in
     * id order, as every money movement of the order locks them - so two fee items never take the same number.
     *
     * <p><b>Due today, deliberately</b> (open question for the owner, WF-05 review F10). The fee is owed for a trip
     * that already happened, as the cancellation fee item is (15 §5.1 "đến hạn ngay"); no payment term was agreed for
     * it, so none is invented here. The consequence is meant: from the next business day it is unpaid it is overdue
     * ({@link Installment#overdue}), and the customer's credit position then shows it overdue - which refuses the
     * release of their orders paying after delivery, this one's next batches included ({@code CUSTOMER_CREDIT_OVERDUE},
     * {@code OrderReleaseService.release}), until it is paid. The redelivery itself is
     * released without that check ({@code OrderReleaseService.releaseRedelivery}), so the goods that came back are
     * never held hostage by their own fee. Giving the fee a term (a {@code due_days}, or the contract's terms) would be
     * a one-line change in {@link Installment#openDeliveryFailureFee}.</p>
     */
    public Optional<UUID> chargeFailedDelivery(com.stockflow.payment.api.ChargeFailedDeliveryCommand command) {
        List<Installment> ofOrder = book.lockOfOrder(command.orderId());
        if (ofOrder.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal amount = command.amount();
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The fee of a failed trip is more than zero, in whole units of the currency");
        }
        Installment first = ofOrder.stream().filter(i -> !i.isFeeItem()).findFirst().orElse(ofOrder.getFirst());
        int seq = ofOrder.stream().filter(Installment::isFeeItem).mapToInt(Installment::seq).max()
                .orElse(Installment.FIRST_FEE_SEQ - 1) + 1;
        if (seq > Installment.LAST_FEE_SEQ) {
            throw new IllegalStateException("Contract %s has no room for another fee item"
                    .formatted(first.contractNumber()));
        }
        Installment fee = Installment.openDeliveryFailureFee(first, seq, command.label(), amount,
                command.chargedAt(), LocalDate.ofInstant(command.chargedAt(), BUSINESS_ZONE));
        book.save(fee);
        fellDue(fee);
        log.info("Contract {}: failed trip charged {} {} on fee item {} ({})", first.contractNumber(),
                amount.toPlainString(), first.currency(), seq, command.label());
        return Optional.of(fee.id());
    }

    /**
     * Tells the modules that follow installments - the chat's transfer card (kltn-docs 16 §4.4 step 9, BR-06) - that
     * one fell due. Published in the transaction that reached its milestone, delivered after commit; the amount it still
     * owes is read when the card is drawn.
     */
    private void fellDue(Installment installment) {
        events.publishEvent(new InstallmentDue(installment.id(), installment.contractId(),
                installment.contractNumber(), installment.orderId(), installment.customerId(), installment.seq(),
                installment.dueDate(), installment.reachedAt()));
    }

    /**
     * The business day overdue is read on (15 §4.1 step 4): an installment is overdue from the day after its due day, in
     * the business calendar - worked out when asked, by no nightly job (WF-05 fix plan, section 8).
     */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    /** {@link com.stockflow.payment.api.PaymentService#installmentStanding}. */
    @Transactional(readOnly = true)
    public InstallmentStanding standing(UUID orderId) {
        return standingOf(orderId, book.ofOrder(orderId));
    }

    static InstallmentStanding standingOf(UUID orderId, List<Installment> installments) {
        if (installments.isEmpty()) {
            return InstallmentStanding.none(orderId);
        }
        List<Installment> live = installments.stream().filter(i -> i.status() != InstallmentStatus.CANCELLED)
                .toList();
        boolean onSigningPaid = live.stream().filter(i -> i.milestone() == InstallmentMilestone.ON_SIGNING)
                .allMatch(i -> i.status() == InstallmentStatus.PAID);
        BigDecimal beforeDelivery = live.stream().filter(i -> i.milestone() == InstallmentMilestone.BEFORE_DELIVERY)
                .map(Installment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal onSigning = live.stream().filter(i -> i.milestone() == InstallmentMilestone.ON_SIGNING)
                .map(Installment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        // What closures took off is no longer owed (4B-9, 15 §4.6 step 19).
        BigDecimal total = live.stream().map(Installment::owedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = live.stream().map(Installment::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        InstallmentStanding.ContractPaymentStatus status = live.isEmpty() ? null
                : live.stream().allMatch(i -> i.status() == InstallmentStatus.PAID)
                ? InstallmentStanding.ContractPaymentStatus.PAID
                : paid.signum() > 0 ? InstallmentStanding.ContractPaymentStatus.PARTIALLY_PAID
                : InstallmentStanding.ContractPaymentStatus.UNPAID;
        return new InstallmentStanding(orderId, true, onSigningPaid, beforeDelivery, beforeDelivery.add(onSigning),
                total, paid, status);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * Every filter null means "any"; {@code dueBefore} keeps installments due before that day; {@code reached} true keeps
     * those whose milestone came (what is due), false those still waiting for it; {@code overdue} true keeps those
     * overdue today, false those that are not.
     *
     * @throws BusinessException {@code INSTALLMENT_OVERDUE_IS_A_FLAG} (400) for {@code status} OVERDUE: no installment is
     *         in it any more ({@link InstallmentStatus}), so the filter would answer an empty list - which a client
     *         written against the old status reads as "nothing overdue". It is told to send {@code overdue=true}.
     */
    @Transactional(readOnly = true)
    public PageResponse<Installment> installments(UUID customerId, UUID orderId, InstallmentStatus status,
                                                  InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                                  Boolean overdue, int page, int size, String sort) {
        if (status == InstallmentStatus.OVERDUE) {
            throw new BusinessException(ErrorCode.INSTALLMENT_OVERDUE_IS_A_FLAG,
                    "OVERDUE is not an installment status any more; filter with overdue=true");
        }
        return Pages.toResponse(search.installments(new InstallmentSearch.InstallmentFilter(customerId, orderId, status,
                milestone, dueBefore, reached, overdue, overdue == null ? null : today()),
                Pages.of(page, size, SORT.parse(sort))));
    }

    /**
     * One installment and its receipts; {@code ownCustomerId} not null: only that customer's (anyone else's is not
     * found, as if it did not exist).
     */
    @Transactional(readOnly = true)
    public InstallmentDetail detail(UUID installmentId, UUID ownCustomerId) {
        Installment installment = find(installmentId, ownCustomerId);
        return new InstallmentDetail(installment, book.receiptsOf(installmentId));
    }

    /** The order's installments and their standing; {@code ownCustomerId} as for {@link #detail}. */
    @Transactional(readOnly = true)
    public OrderInstallments ofOrder(UUID orderId, UUID ownCustomerId) {
        List<Installment> installments = book.ofOrder(orderId);
        if (installments.isEmpty() || ownCustomerId != null
                && !ownCustomerId.equals(installments.getFirst().customerId())) {
            throw new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND,
                    "Order %s has no installments".formatted(orderId));
        }
        return new OrderInstallments(standingOf(orderId, installments), installments);
    }

    /**
     * How to pay the installment (15 §4.2 step 5): what it still owes, to the company's account, writing its reference.
     *
     * @throws BusinessException {@code INSTALLMENT_NOT_FOUND}; {@code INSTALLMENT_NOT_PAYABLE} when paid or cancelled;
     *         {@code TRANSFER_INSTRUCTIONS_UNAVAILABLE} while the company's account is not configured
     */
    @Transactional(readOnly = true)
    public TransferInstructions transferInstructions(UUID installmentId, UUID ownCustomerId) {
        Installment installment = find(installmentId, ownCustomerId);
        if (!installment.status().takesMoney()) {
            throw new BusinessException(ErrorCode.INSTALLMENT_NOT_PAYABLE, "Installment %d of contract %s is %s"
                    .formatted(installment.seq(), installment.contractNumber(), installment.status()));
        }
        if (!bank.configured()) {
            throw new BusinessException(ErrorCode.TRANSFER_INSTRUCTIONS_UNAVAILABLE,
                    "The company's bank account is not configured");
        }
        CompanyBankAccount account = new CompanyBankAccount(bank.bankName().strip(), bank.accountNumber().strip(),
                bank.accountName().strip());
        return new TransferInstructions(installment, account, installment.outstanding(),
                installment.transferReference());
    }

    private Installment find(UUID installmentId, UUID ownCustomerId) {
        Optional<Installment> found = book.find(installmentId)
                .filter(i -> ownCustomerId == null || ownCustomerId.equals(i.customerId()));
        return found.orElseThrow(() -> new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND,
                "No installment " + installmentId));
    }
}
