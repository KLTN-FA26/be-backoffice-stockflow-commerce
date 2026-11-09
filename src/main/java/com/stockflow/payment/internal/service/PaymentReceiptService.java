package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.contracts.PaymentReceiptRecorded;
import com.stockflow.contracts.PaymentReceiptVoided;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Payment receipts (4B-6, kltn-docs 15 §4.2, §4.4, §5.3; BR-02..BR-05, BR-13): the accountant records what the bank
 * statement shows against the installments it pays, and voids a receipt recorded wrong.
 *
 * <ul>
 *   <li><b>Record</b>: one statement line, one live receipt (BR-03); lines for installments of the receipt's customer
 *       only, each no more than the installment still owes, none for a paid or cancelled one (BR-05) - early payment of
 *       an installment not yet due included. Then {@code PaymentReceiptRecorded}: the order confirms itself once its
 *       installments on signing are paid (BR-07).</li>
 *   <li><b>Void</b>: kept, with who, when and why (BR-04); the installments it paid say again what is left. Refused
 *       for an order cancelled, completed, closed, being returned or refunded (§4.4 step 13). A confirmed order left
 *       short of its installment on signing must be put on hold - the accountant says so ({@code holdOrders}), or the
 *       void is refused (BR-13); {@code PaymentReceiptVoided} then holds it.</li>
 * </ul>
 *
 * <p>Locks: orders first (through {@link OrderPayables#lockForPayment}, as every money movement takes them), then the
 * receipt, then installments in id order.</p>
 */
@Service
@Transactional
public class PaymentReceiptService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReceiptService.class);

    /** §4.4 step 13: an order in one of these keeps its receipts as they are. */
    private static final Set<String> SETTLED = Set.of("CANCELLED", "COMPLETED", "CLOSED", "RETURN_REQUESTED",
            "RETURNED", "REFUNDED", "PARTIALLY_REFUNDED");

    private static final SortWhitelist SORT = SortWhitelist.of("receivedOn", "recordedAt", "amount")
            .withDefault("recordedAt", Sort.Direction.DESC);

    /** What the accountant records. */
    public record RecordReceiptCommand(UUID customerId, String reference, LocalDate receivedOn, String note,
                                       List<PaymentReceipt.Payment> lines, UUID recordedBy) {
    }

    /** What the accountant voids, why, and whether a confirmed order it leaves short may be put on hold. */
    public record VoidReceiptCommand(UUID receiptId, String reason, boolean holdOrders, UUID voidedBy) {
    }

    /** A receipt with the installments its lines pay, for display. */
    public record ReceiptDetail(PaymentReceipt receipt, Map<UUID, Installment> installments) {
    }

    private final InstallmentBook book;
    private final InstallmentSearch search;
    private final OrderPayables orders;
    private final CustomerService customers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PaymentReceiptService(InstallmentBook book, InstallmentSearch search, OrderPayables orders,
                          CustomerService customers, ApplicationEventPublisher events, Clock clock) {
        this.book = book;
        this.search = search;
        this.orders = orders;
        this.customers = customers;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code PAYMENT_RECEIPT_REFERENCE_IN_USE} (BR-03);
     *         {@code INSTALLMENT_NOT_FOUND}; {@code INSTALLMENT_CUSTOMER_MISMATCH}; {@code INSTALLMENT_NOT_PAYABLE};
     *         {@code PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING} (BR-05); {@code VALIDATION_FAILED} for what the receipt is on
     *         its own ({@link PaymentReceipt#record})
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "payment-receipt", resourceId = "#result?.receipt()?.id()")
    public ReceiptDetail record(RecordReceiptCommand command) {
        if (command.customerId() == null || customers.findById(command.customerId()).isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + command.customerId());
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, InstallmentService.BUSINESS_ZONE);
        List<UUID> named = command.lines() == null ? List.of() : command.lines().stream()
                .filter(line -> line != null && line.installmentId() != null)
                .map(PaymentReceipt.Payment::installmentId).toList();
        // The orders first, as every money movement locks them: a cancellation of one of them waits for this receipt,
        // or went first and is seen here - before its listener has cancelled the installments.
        Map<UUID, PayableOrder> payable = new HashMap<>();
        named.stream().map(book::find).flatMap(java.util.Optional::stream).map(Installment::orderId).distinct().sorted()
                .forEach(orderId -> orders.lockForPayment(orderId).ifPresent(order -> payable.put(orderId, order)));
        Map<UUID, Installment> installments = book.lock(named).stream()
                .collect(Collectors.toMap(Installment::id, Function.identity()));
        String currency = installments.values().stream().map(Installment::currency).findFirst().orElse("VND");
        PaymentReceipt receipt = PaymentReceipt.record(command.customerId(), command.reference(), command.receivedOn(),
                currency, command.note(), command.lines(), command.recordedBy(), now, today);
        if (book.referenceInUse(receipt.reference())) {
            throw referenceInUse(receipt.reference());
        }
        for (PaymentReceipt.Line line : receipt.lines()) {
            Installment installment = installments.get(line.installmentId());
            if (installment == null) {
                throw new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND, "No installment " + line.installmentId());
            }
            if (!installment.customerId().equals(receipt.customerId())) {
                throw new BusinessException(ErrorCode.INSTALLMENT_CUSTOMER_MISMATCH, ("Installment %d of contract %s "
                        + "is another customer's").formatted(installment.seq(), installment.contractNumber()));
            }
            if (!installment.currency().equals(currency)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "One receipt is in one currency");
            }
            PayableOrder order = payable.get(installment.orderId());
            if (order != null && "CANCELLED".equals(order.status())) {
                // 15 BR-06, edge "tiền về sau khi đơn đã huỷ": money for a cancelled order is settled outside.
                throw new BusinessException(ErrorCode.INSTALLMENT_NOT_PAYABLE, ("Installment %d of contract %s is of "
                        + "order %s, which is cancelled").formatted(installment.seq(), installment.contractNumber(),
                        order.orderNumber()));
            }
            installment.pay(line.amount(), now);
        }
        PaymentReceipt saved;
        try {
            saved = book.record(receipt);
        } catch (DataIntegrityViolationException raced) {
            // uk_payment_receipt_reference_recorded: another receipt took the reference since the check above.
            throw referenceInUse(receipt.reference());
        }
        installments.values().forEach(book::save);
        events.publishEvent(new PaymentReceiptRecorded(saved.id(), saved.customerId(), saved.reference(),
                saved.amount(), saved.currency(), saved.lines().stream().map(line -> {
                    Installment installment = installments.get(line.installmentId());
                    return new PaymentReceiptRecorded.Line(installment.id(), installment.orderId(),
                            installment.milestone().name(), line.amount(),
                            installment.status() == InstallmentStatus.PAID);
                }).toList(), saved.recordedBy(), saved.recordedAt()));
        log.info("Receipt {} recorded: {} {} over {} installment(s)", saved.reference(), saved.amount(),
                saved.currency(), saved.lines().size());
        return new ReceiptDetail(saved, installments);
    }

    /**
     * @throws BusinessException {@code PAYMENT_RECEIPT_NOT_FOUND}; {@code PAYMENT_RECEIPT_ALREADY_VOIDED};
     *         {@code PAYMENT_RECEIPT_VOID_NOT_ALLOWED} (§4.4 step 13); {@code PAYMENT_RECEIPT_VOID_NEEDS_HOLD} (BR-13);
     *         {@code VALIDATION_FAILED} without a reason
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "payment-receipt", resourceId = "#command.receiptId()")
    public ReceiptDetail voidReceipt(VoidReceiptCommand command) {
        PaymentReceipt unlocked = book.findReceipt(command.receiptId()).orElseThrow(() -> notFound(command.receiptId()));
        // The orders first, as every money movement locks them: a cancellation, a release or a handover of one of them
        // waits for this void, or went first and is seen here.
        Set<UUID> orderIds = new LinkedHashSet<>();
        for (PaymentReceipt.Line line : unlocked.lines()) {
            book.find(line.installmentId()).ifPresent(installment -> orderIds.add(installment.orderId()));
        }
        Map<UUID, PayableOrder> payable = new HashMap<>();
        orderIds.stream().sorted().forEach(orderId -> orders.lockForPayment(orderId)
                .ifPresent(order -> payable.put(orderId, order)));
        PaymentReceipt receipt = book.lockReceipt(command.receiptId()).orElseThrow(() -> notFound(command.receiptId()));
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, InstallmentService.BUSINESS_ZONE);
        receipt.voidReceipt(command.reason(), command.voidedBy(), now);

        // Every installment of those orders, locked in id order: whether the orders are still paid on signing reads
        // installments the receipt did not pay too.
        List<UUID> all = orderIds.stream().flatMap(orderId -> book.ofOrder(orderId).stream()).map(Installment::id)
                .toList();
        Map<UUID, Installment> installments = book.lock(all).stream()
                .collect(Collectors.toMap(Installment::id, Function.identity()));
        Map<UUID, Boolean> paidOnSigningBefore = new HashMap<>();
        orderIds.forEach(orderId -> paidOnSigningBefore.put(orderId, paidOnSigning(orderId, installments)));
        for (PaymentReceipt.Line line : receipt.lines()) {
            Installment installment = installments.get(line.installmentId());
            PayableOrder order = installment == null ? null : payable.get(installment.orderId());
            if (installment == null || order == null) {
                throw new IllegalStateException("Receipt %s pays installment %s, which has no order"
                        .formatted(receipt.reference(), line.installmentId()));
            }
            if (installment.status() == InstallmentStatus.CANCELLED || SETTLED.contains(order.status())) {
                throw new BusinessException(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED, ("Receipt %s paid order %s, "
                        + "which is %s: its money is settled with the order").formatted(receipt.reference(),
                        order.orderNumber(), order.status()));
            }
            installment.unpay(line.amount(), today);
        }
        List<String> leftShort = new ArrayList<>();
        for (UUID orderId : orderIds) {
            PayableOrder order = payable.get(orderId);
            if (paidOnSigningBefore.get(orderId) && !paidOnSigning(orderId, installments) && order.holdable()) {
                leftShort.add(order.orderNumber());
            }
        }
        if (!leftShort.isEmpty() && !command.holdOrders()) {
            throw new BusinessException(ErrorCode.PAYMENT_RECEIPT_VOID_NEEDS_HOLD, ("Voiding receipt %s leaves order(s) "
                    + "%s short of the installment on signing they were confirmed by: confirm putting them on hold")
                    .formatted(receipt.reference(), String.join(", ", leftShort)));
        }
        PaymentReceipt saved = book.saveVoided(receipt);
        receipt.lines().forEach(line -> book.save(installments.get(line.installmentId())));
        events.publishEvent(new PaymentReceiptVoided(saved.id(), saved.customerId(), saved.reference(), saved.amount(),
                saved.currency(), saved.lines().stream().map(line -> {
                    Installment installment = installments.get(line.installmentId());
                    return new PaymentReceiptVoided.Line(installment.id(), installment.orderId(),
                            installment.milestone().name(), line.amount());
                }).toList(), command.holdOrders() && !leftShort.isEmpty(), saved.voidedBy(), saved.voidedAt()));
        log.info("Receipt {} voided by {}{}", saved.reference(), saved.voidedBy(),
                leftShort.isEmpty() ? "" : "; order(s) " + leftShort + " to be put on hold");
        return new ReceiptDetail(saved, receipt.lines().stream().map(line -> installments.get(line.installmentId()))
                .collect(Collectors.toMap(Installment::id, Function.identity())));
    }

    private static boolean paidOnSigning(UUID orderId, Map<UUID, Installment> installments) {
        return installments.values().stream()
                .filter(i -> i.orderId().equals(orderId) && i.milestone() == InstallmentMilestone.ON_SIGNING)
                .filter(i -> i.status() != InstallmentStatus.CANCELLED)
                .allMatch(i -> i.status() == InstallmentStatus.PAID);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PaymentReceipt> receipts(UUID customerId, PaymentReceiptStatus status, String reference,
                                                 int page, int size, String sort) {
        return Pages.toResponse(search.receipts(customerId, status, reference, Pages.of(page, size, SORT.parse(sort))));
    }

    @Transactional(readOnly = true)
    public ReceiptDetail receipt(UUID receiptId) {
        PaymentReceipt receipt = book.findReceipt(receiptId).orElseThrow(() -> notFound(receiptId));
        Map<UUID, Installment> installments = new HashMap<>();
        receipt.lines().forEach(line -> book.find(line.installmentId())
                .ifPresent(installment -> installments.put(installment.id(), installment)));
        return new ReceiptDetail(receipt, installments);
    }

    private static BusinessException notFound(UUID receiptId) {
        return new BusinessException(ErrorCode.PAYMENT_RECEIPT_NOT_FOUND, "No payment receipt " + receiptId);
    }

    private static BusinessException referenceInUse(String reference) {
        return new BusinessException(ErrorCode.PAYMENT_RECEIPT_REFERENCE_IN_USE,
                "Statement line %s is already recorded".formatted(reference));
    }
}
