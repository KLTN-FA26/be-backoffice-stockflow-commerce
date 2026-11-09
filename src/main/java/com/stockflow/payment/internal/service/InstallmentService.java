package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.ContractInstallments;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * their milestone comes, OVERDUE past their due day, CANCELLED with their order. Receipts pay them
 * ({@link PaymentReceiptService}).
 *
 * <ul>
 *   <li>ON_SIGNING: due from the signature, by the day signed plus its days.</li>
 *   <li>BEFORE_DELIVERY: due when the order is released to be shipped ({@code OrderReleased}); no due day - it blocks
 *       the handover until paid (BR-08), which the order module reads through {@link InstallmentStanding}.</li>
 *   <li>AFTER_DELIVERY: due when the order is delivered in full - the last delivery, or the rest closed after it (15
 *       D23) - by that day plus its days (BR-09): the customer's receivable.</li>
 * </ul>
 *
 * <p>An installment tied to a delivery batch (17 §4.5) falls due with the order for now: nothing releases or delivers a
 * batch on its own until 4B-8, and an order released or delivered is released or delivered whole.</p>
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
    private final OrderPayables orders;
    private final CompanyBankProperties bank;
    private final Clock clock;

    InstallmentService(InstallmentBook book, InstallmentSearch search, OrderPayables orders, CompanyBankProperties bank,
                       Clock clock) {
        this.book = book;
        this.search = search;
        this.orders = orders;
        this.bank = bank;
        this.clock = clock;
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
            if (installment.milestone() == InstallmentMilestone.ON_SIGNING) {
                installment.reach(contract.signedAt(), signedOn);
            }
            book.save(installment);
        }
        log.info("Contract {} of order {}: {} installment(s) opened", contract.contractNumber(), contract.orderNumber(),
                contract.installments().size());
    }

    /** The order was released to be shipped: what it pays before delivery is due (15 §4.1 step 2). */
    public void released(UUID orderId, Instant at) {
        reach(orderId, InstallmentMilestone.BEFORE_DELIVERY, at);
    }

    /**
     * Goods of the order were delivered, or the rest of it closed: once it is delivered in full, what it pays after
     * delivery is due from that day (15 §4.1 step 2, D23). A delivery of part of a split order waits for the last one.
     *
     * @param wholeOrder the event delivered the whole order at once (no shipment of its own)
     */
    public void delivered(UUID orderId, Instant at, boolean wholeOrder) {
        if (wholeOrder) {
            reach(orderId, InstallmentMilestone.AFTER_DELIVERY, at);
            return;
        }
        // The day the order was delivered in full, as the order has it: a shipment's event handled late - after the
        // order's last delivery - must not date the receivable from an earlier delivery (BR-09).
        orders.deliveredInFullAt(orderId).ifPresent(last -> reach(orderId, InstallmentMilestone.AFTER_DELIVERY, last));
    }

    private void reach(UUID orderId, InstallmentMilestone milestone, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, BUSINESS_ZONE);
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() == milestone && installment.reach(at, day)) {
                book.save(installment);
                log.info("Installment {} of contract {} is due ({}){}", installment.seq(),
                        installment.contractNumber(), milestone,
                        installment.dueDate() == null ? "" : " by " + installment.dueDate());
            }
        }
    }

    /**
     * The order was cancelled (15 §4.6 step 18, BR-15): its installments not paid in full are no longer owed. What
     * they were paid stays on them; the fee and the refund are 4B-9's.
     */
    public void orderCancelled(UUID orderId, Instant at) {
        int cancelled = 0;
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.cancel(at)) {
                book.save(installment);
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("Order {} cancelled: {} installment(s) cancelled", orderId, cancelled);
        }
    }

    /** 15 §4.1 step 4: every night, installments past their due day and not paid in full become OVERDUE. */
    public int markOverdue() {
        List<UUID> marked = book.markOverdue(LocalDate.now(clock.withZone(BUSINESS_ZONE)));
        if (!marked.isEmpty()) {
            log.info("{} installment(s) became overdue", marked.size());
        }
        return marked.size();
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
        BigDecimal total = live.stream().map(Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
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
     * those whose milestone came (what is due), false those still waiting for it.
     */
    @Transactional(readOnly = true)
    public PageResponse<Installment> installments(UUID customerId, UUID orderId, InstallmentStatus status,
                                                  InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                                  int page, int size, String sort) {
        return Pages.toResponse(search.installments(new InstallmentSearch.InstallmentFilter(customerId, orderId, status,
                milestone, dueBefore, reached), Pages.of(page, size, SORT.parse(sort))));
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
