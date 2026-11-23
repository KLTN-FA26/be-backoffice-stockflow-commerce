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
import com.stockflow.payment.api.HandedOverGoods;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.api.InstallmentOrder;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import com.stockflow.payment.internal.domain.SettlementBook;
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
 *       for an order cancelled, completed, closed, being returned or refunded, or one whose closure left money to
 *       refund (§4.4 step 13; 4B-9) - unless the receipt paid a cancellation fee item, whose money no settlement
 *       counted. Refused too for a receipt that paid an installment on signing, or before delivery, once the goods it
 *       let go were handed over (WF-05 Q-20, BE-33). A confirmed order left
 *       short of its installment on signing must be put on hold - the accountant says so ({@code holdOrders}), or the
 *       void is refused (BR-13); {@code PaymentReceiptVoided} then holds it.</li>
 * </ul>
 *
 * <p>Locks: orders first (through {@link InstallmentOrders#lockForPayment}, as every money movement takes them), then the
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

    /**
     * A receipt with the installments its lines pay, for display.
     *
     * @param voidBlockedBy why a void of it would be refused now - the code {@link #voidReceipt} would answer with
     *                      ({@code PAYMENT_RECEIPT_ALREADY_VOIDED}, {@code PAYMENT_RECEIPT_VOID_NOT_ALLOWED},
     *                      {@code PAYMENT_RECEIPT_VOID_AFTER_HANDOVER}); null when it may be voided. One rule for both
     *                      ({@link #voidRefusal}): the back office locks its void button on this before the click
     *                      (WF-05 Q-20), and the void refuses on the same reading under the orders' locks
     */
    public record ReceiptDetail(PaymentReceipt receipt, Map<UUID, Installment> installments, ErrorCode voidBlockedBy) {

        /** Whether a void of the receipt would be taken now, as far as the receipt and its orders go. */
        public boolean voidable() {
            return voidBlockedBy == null;
        }
    }

    private final InstallmentBook book;
    private final SettlementBook settlements;
    private final InstallmentSearch search;
    private final InstallmentOrders orders;
    private final CustomerService customers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PaymentReceiptService(InstallmentBook book, SettlementBook settlements, InstallmentSearch search,
                          InstallmentOrders orders, CustomerService customers, ApplicationEventPublisher events,
                          Clock clock) {
        this.book = book;
        this.settlements = settlements;
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
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + command.customerId())
                    .localized("refusal.payment.customerNotFound");
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, InstallmentService.BUSINESS_ZONE);
        List<UUID> named = command.lines() == null ? List.of() : command.lines().stream()
                .filter(line -> line != null && line.installmentId() != null)
                .map(PaymentReceipt.Payment::installmentId).toList();
        // The orders first, as every money movement locks them: a cancellation of one of them waits for this receipt,
        // or went first and is seen here - before its listener has cancelled the installments.
        Map<UUID, InstallmentOrder> payable = new HashMap<>();
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
                throw new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND, "No installment " + line.installmentId())
                        .localized("refusal.payment.installmentNotFound");
            }
            if (!installment.customerId().equals(receipt.customerId())) {
                throw new BusinessException(ErrorCode.INSTALLMENT_CUSTOMER_MISMATCH, ("Installment %d of contract %s "
                        + "is another customer's").formatted(installment.seq(), installment.contractNumber()))
                        .localized("refusal.payment.otherCustomer", installment.seq(), installment.contractNumber());
            }
            if (!installment.currency().equals(currency)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "One receipt is in one currency")
                        .localized("refusal.payment.oneCurrency");
            }
            InstallmentOrder order = payable.get(installment.orderId());
            // A cancelled order's fee item is what it still owes (4B-9, 15 §4.6 step 18): that money is taken.
            if (order != null && "CANCELLED".equals(order.status()) && !installment.isFeeItem()) {
                // 15 BR-06, edge "tiền về sau khi đơn đã huỷ": money for a cancelled order is settled outside.
                throw new BusinessException(ErrorCode.INSTALLMENT_NOT_PAYABLE, ("Installment %d of contract %s is of "
                        + "order %s, which is cancelled").formatted(installment.seq(), installment.contractNumber(),
                        order.orderNumber())).localized("refusal.payment.orderCancelled", installment.seq(),
                        installment.contractNumber(), order.orderNumber());
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
        return new ReceiptDetail(saved, installments, blockedBy(saved, installments, payable));
    }

    /**
     * @throws BusinessException {@code PAYMENT_RECEIPT_NOT_FOUND}; {@code PAYMENT_RECEIPT_ALREADY_VOIDED};
     *         {@code PAYMENT_RECEIPT_VOID_NOT_ALLOWED} (§4.4 step 13); {@code PAYMENT_RECEIPT_VOID_AFTER_HANDOVER}
     *         (WF-05 Q-20); {@code PAYMENT_RECEIPT_VOID_NEEDS_HOLD} (BR-13); {@code VALIDATION_FAILED} without a reason
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
        Map<UUID, InstallmentOrder> payable = new HashMap<>();
        orderIds.stream().sorted().forEach(orderId -> orders.lockForPayment(orderId)
                .ifPresent(order -> payable.put(orderId, order)));
        PaymentReceipt receipt = book.lockReceipt(command.receiptId()).orElseThrow(() -> notFound(command.receiptId()));
        Instant now = clock.instant();

        // Every installment of those orders, locked in id order: whether the orders are still paid on signing reads
        // installments the receipt did not pay too.
        List<UUID> all = orderIds.stream().flatMap(orderId -> book.ofOrder(orderId).stream()).map(Installment::id)
                .toList();
        Map<UUID, Installment> installments = book.lock(all).stream()
                .collect(Collectors.toMap(Installment::id, Function.identity()));
        // Read under the orders' locks taken above: a cancellation, a closure or a handover of one of them waits for
        // this void, or went first and is seen here.
        java.util.Optional<BusinessException> refused = voidRefusal(receipt, installments, payable);
        if (refused.isPresent()) {
            throw refused.get();
        }
        receipt.voidReceipt(command.reason(), command.voidedBy(), now);
        Map<UUID, Boolean> paidOnSigningBefore = new HashMap<>();
        orderIds.forEach(orderId -> paidOnSigningBefore.put(orderId, paidOnSigning(orderId, installments)));
        for (PaymentReceipt.Line line : receipt.lines()) {
            installments.get(line.installmentId()).unpay(line.amount());
        }
        List<String> leftShort = new ArrayList<>();
        for (UUID orderId : orderIds) {
            InstallmentOrder order = payable.get(orderId);
            if (paidOnSigningBefore.get(orderId) && !paidOnSigning(orderId, installments) && order.holdable()) {
                leftShort.add(order.orderNumber());
            }
        }
        if (!leftShort.isEmpty() && !command.holdOrders()) {
            throw new BusinessException(ErrorCode.PAYMENT_RECEIPT_VOID_NEEDS_HOLD, ("Voiding receipt %s leaves order(s) "
                    + "%s short of the installment on signing they were confirmed by: confirm putting them on hold")
                    .formatted(receipt.reference(), String.join(", ", leftShort)))
                    .localized("refusal.payment.voidNeedsHold", receipt.reference(), String.join(", ", leftShort));
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
                .distinct().collect(Collectors.toMap(Installment::id, Function.identity())),
                ErrorCode.PAYMENT_RECEIPT_ALREADY_VOIDED);
    }

    /**
     * Why a void of {@code receipt} would be refused, the first reason found; empty when it may be voided. The one rule
     * {@link #voidReceipt} enforces - under the orders' locks - and the receipt's detail shows before anyone clicks
     * ({@link ReceiptDetail#voidBlockedBy}; WF-05 Q-20: the back office hides or locks the button), so the two cannot
     * drift apart.
     *
     * <ul>
     *   <li>{@code PAYMENT_RECEIPT_ALREADY_VOIDED}: voided once already (BR-04).</li>
     *   <li>{@code PAYMENT_RECEIPT_VOID_NOT_ALLOWED} (section 4.4 step 13; 4B-9): it paid an installment cancelled
     *       since, or of an order that is cancelled, completed, closed, being returned or refunded, or of one a
     *       cancellation or a closure that left money to refund was worked out on after the receipt was recorded -
     *       taking that money back would leave the refund wrong. A fee item's money is the fee's alone - no settlement
     *       counted it - so a receipt recorded wrong against one is voided whatever its order became.</li>
     *   <li>{@code PAYMENT_RECEIPT_VOID_NOT_ALLOWED} too (audit r3 LM-3): it paid an installment before delivery of a
     *       batch a closure of the rest closed, before that closure ({@link #closedBatchSettled}). The closure took what
     *       it still owed off it - onto its fee item, or as the goods closed - and its batch will never be released or
     *       handed over: the money taken back would be owed on an installment with no milestone and no due day, the
     *       debt LM-3 is about, reached by a void instead of a closure.</li>
     *   <li>{@code PAYMENT_RECEIPT_VOID_AFTER_HANDOVER} (Q-20, BE-33): it paid an installment on signing, or before
     *       delivery, and the goods that money let go were handed over to a carrier ({@link #letGoodsGo}).</li>
     * </ul>
     *
     * <p>Not a refusal: a void that would leave a confirmed order short of its installment on signing
     * ({@code PAYMENT_RECEIPT_VOID_NEEDS_HOLD}, BR-13) - the accountant takes that one by agreeing to hold the order.
     * Nor a missing reason, which the void itself asks for.</p>
     *
     * @param installments   the installments the receipt's lines pay, at least
     * @param ofInstallments the orders of those installments
     */
    java.util.Optional<BusinessException> voidRefusal(PaymentReceipt receipt, Map<UUID, Installment> installments,
                                                      Map<UUID, InstallmentOrder> ofInstallments) {
        if (receipt.status() == PaymentReceiptStatus.VOIDED) {
            return java.util.Optional.of(new BusinessException(ErrorCode.PAYMENT_RECEIPT_ALREADY_VOIDED,
                    "Receipt %s was voided on %s".formatted(receipt.reference(), receipt.voidedAt()))
                    .localized("refusal.payment.alreadyVoided", receipt.reference()));
        }
        // 4B-9 (section 4.4 step 13): a cancellation, or a closure that left money to refund, worked the refund out
        // from what had been paid by then. A receipt recorded after the last such settlement was counted by none.
        // tg_payment_receipt_void says it again.
        Map<UUID, List<OrderSettlement>> settledOf = new HashMap<>();
        ofInstallments.keySet().forEach(orderId -> settledOf.put(orderId, settlements.ofOrder(orderId)));
        Set<UUID> settledOnPaid = new java.util.HashSet<>();
        settledOf.forEach((orderId, ofOrder) -> ofOrder.stream()
                .filter(s -> s.kind() == OrderSettlement.Kind.ORDER_CANCELLED || s.surplus().signum() > 0)
                .map(OrderSettlement::settledAt).max(Instant::compareTo)
                .filter(last -> !receipt.recordedAt().isAfter(last))
                .ifPresent(last -> settledOnPaid.add(orderId)));
        Map<UUID, HandedOverGoods> handedOver = new HashMap<>();
        for (PaymentReceipt.Line line : receipt.lines()) {
            Installment installment = installments.get(line.installmentId());
            InstallmentOrder order = installment == null ? null : ofInstallments.get(installment.orderId());
            if (installment == null || order == null) {
                throw new IllegalStateException("Receipt %s pays installment %s, which has no order"
                        .formatted(receipt.reference(), line.installmentId()));
            }
            if (!installment.isFeeItem() && (installment.status() == InstallmentStatus.CANCELLED
                    || SETTLED.contains(order.status()) || settledOnPaid.contains(installment.orderId()))) {
                return java.util.Optional.of(new BusinessException(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED,
                        ("Receipt %s paid order %s, which is %s: its money is settled with the order").formatted(
                                receipt.reference(), order.orderNumber(), order.status()))
                        .localized("refusal.payment.voidSettled", receipt.reference(), order.orderNumber()));
            }
            HandedOverGoods left = handedOver.computeIfAbsent(installment.orderId(), orders::handedOverGoods);
            if (closedBatchSettled(installment, left, settledOf.getOrDefault(installment.orderId(), List.of()),
                    receipt.recordedAt())) {
                return java.util.Optional.of(new BusinessException(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED,
                        ("Receipt %s paid installment %d of contract %s before delivery of a batch the rest's closure "
                                + "closed: its money is settled with the order").formatted(receipt.reference(),
                                installment.seq(), installment.contractNumber()))
                        .localized("refusal.payment.voidSettled", receipt.reference(), order.orderNumber()));
            }
            if (letGoodsGo(installment, left)) {
                return java.util.Optional.of(new BusinessException(ErrorCode.PAYMENT_RECEIPT_VOID_AFTER_HANDOVER,
                        ("Receipt %s paid installment %d (%s) of contract %s, and the goods it let go were handed over "
                                + "to a carrier").formatted(receipt.reference(), installment.seq(),
                                installment.milestone(), installment.contractNumber()), List.of(),
                        Map.of("installmentId", installment.id(), "orderNumber", order.orderNumber())));
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * {@link #voidRefusal} as the code the receipt's detail shows; null when it may be voided. A line whose installment
     * or order cannot be read makes it not voidable rather than failing the read: the void would stop on it.
     */
    private ErrorCode blockedBy(PaymentReceipt receipt, Map<UUID, Installment> installments,
                                Map<UUID, InstallmentOrder> ofInstallments) {
        boolean whole = receipt.lines().stream().allMatch(line -> installments.containsKey(line.installmentId())
                && ofInstallments.containsKey(installments.get(line.installmentId()).orderId()));
        if (receipt.status() != PaymentReceiptStatus.VOIDED && !whole) {
            return ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED;
        }
        return voidRefusal(receipt, installments, ofInstallments).map(BusinessException::errorCode).orElse(null);
    }

    /**
     * WF-05 decision Q-20 (BE-33): whether the money on {@code installment} is what let goods leave that have left - an
     * installment on signing once any goods of the order were handed over, one before delivery once its own batch was
     * (or, tied to no batch, once any goods were). Such a receipt is not voided: the handover was decided on it (BR-08),
     * and taken back it left an installment before delivery owing with no due day - never overdue, outside the credit
     * figure and the aged receivables (audit M-5). A receipt recorded against the wrong customer after the goods left
     * is corrected in the accounting software with an adjusting entry (Luật Kế toán Điều 27; a known limit, docs 15).
     * An installment after delivery, or a fee item, never let goods go.
     */
    static boolean letGoodsGo(Installment installment, HandedOverGoods handedOver) {
        return switch (installment.milestone()) {
            case ON_SIGNING -> handedOver.any();
            case BEFORE_DELIVERY -> installment.deliveryBatchId() == null ? handedOver.any()
                    : handedOver.batchIds().contains(installment.deliveryBatchId());
            case AFTER_DELIVERY, CANCELLATION_FEE, FAILED_TRIP_FEE -> false;
        };
    }

    /**
     * Audit r3 LM-3: whether {@code installment} is one before delivery, tied to a batch none of whose goods left, that a
     * closure of the rest recorded after {@code recordedAt} lowered - for the goods it closed, or by carrying what it
     * still owed onto its fee item - and left owing nothing. That batch was closed: it is released and handed over never,
     * so an installment owing again on it would have no milestone, no due day and no gate. One the closure left owing
     * is a batch whose labelled cartons it kept: it still gates their handover (BR-08), and a void there is Q-20's to
     * decide once they leave. {@code tg_payment_receipt_void} says it again.
     */
    static boolean closedBatchSettled(Installment installment, HandedOverGoods handedOver,
                                      List<OrderSettlement> ofOrder, Instant recordedAt) {
        return installment.milestone() == InstallmentMilestone.BEFORE_DELIVERY && installment.deliveryBatchId() != null
                && installment.outstanding().signum() == 0
                && !handedOver.batchIds().contains(installment.deliveryBatchId())
                && ofOrder.stream().anyMatch(s -> s.kind() == OrderSettlement.Kind.REMAINDER_CLOSED
                        && !recordedAt.isAfter(s.settledAt())
                        && s.reductions().stream().anyMatch(r -> r.installmentId().equals(installment.id())));
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
        return receipts(new ReceiptFilter(customerId, status, reference, null, null), page, size, sort);
    }

    /** The receipts {@code filter} matches, a page at a time; the received days bound the bank statement's day. */
    @Transactional(readOnly = true)
    public PageResponse<PaymentReceipt> receipts(ReceiptFilter filter, int page, int size, String sort) {
        return Pages.toResponse(search.receipts(filter, Pages.of(page, size, SORT.parse(sort))));
    }

    /**
     * The receipt, the installments it pays, and whether it may be voided now ({@link ReceiptDetail#voidBlockedBy}) -
     * read without the orders' locks: what the back office shows before a click, which the void reads again under
     * them.
     */
    @Transactional(readOnly = true)
    public ReceiptDetail receipt(UUID receiptId) {
        PaymentReceipt receipt = book.findReceipt(receiptId).orElseThrow(() -> notFound(receiptId));
        Map<UUID, Installment> installments = new HashMap<>();
        receipt.lines().forEach(line -> book.find(line.installmentId())
                .ifPresent(installment -> installments.put(installment.id(), installment)));
        if (receipt.status() == PaymentReceiptStatus.VOIDED) {
            return new ReceiptDetail(receipt, installments, ErrorCode.PAYMENT_RECEIPT_ALREADY_VOIDED);
        }
        Map<UUID, InstallmentOrder> ofInstallments = new HashMap<>();
        installments.values().stream().map(Installment::orderId).distinct().forEach(orderId ->
                orders.find(orderId).ifPresent(order -> ofInstallments.put(orderId, order)));
        return new ReceiptDetail(receipt, installments, blockedBy(receipt, installments, ofInstallments));
    }

    private static BusinessException notFound(UUID receiptId) {
        return new BusinessException(ErrorCode.PAYMENT_RECEIPT_NOT_FOUND, "No payment receipt " + receiptId)
                .localized("refusal.payment.receiptNotFound");
    }

    private static BusinessException referenceInUse(String reference) {
        return new BusinessException(ErrorCode.PAYMENT_RECEIPT_REFERENCE_IN_USE,
                "Statement line %s is already recorded".formatted(reference))
                .localized("refusal.payment.referenceInUse", reference);
    }
}
