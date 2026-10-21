package com.stockflow.payment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.contracts.PaymentReversed;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.TransferChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The accountant's work on money already recorded (G2-3, decision G3-Q3: one ledger of incoming money; kltn-docs 15
 * §2, §4.5, BR-04..BR-07):
 * <ul>
 *   <li>allocate a transfer's credit to an order or a receivable later - money that arrived before its order, or
 *       whose statement line named no order (15 edge "nội dung chuyển khoản sai / thiếu mã đơn");</li>
 *   <li>reverse an allocation made to the wrong order: a new ledger row, never an edit (BR-06). Taking money back from
 *       a confirmed order that then no longer meets its terms needs the accountant's explicit {@code holdOrder}; the
 *       order then goes ON_HOLD (reason PAYMENT) - it is never silently un-confirmed;</li>
 *   <li>refund part of a customer's credit (15 edge "khách chuyển thừa ... hoàn lại", "tiền về sau khi đơn đã
 *       huỷ"): a PENDING refund, never more than the transfer holds (BR-05);</li>
 *   <li>record what a carrier remitted for a balance it collected on delivery (09 §4.3 step 4): it pays exactly that
 *       carrier-collected receivable; a short remittance leaves it part paid - the carrier's debt (09 edge);</li>
 *   <li>read an order's payment history.</li>
 * </ul>
 *
 * <p>Locks: the order first ({@link OrderPayables#lockForPayment}), then the receivable, then the transfer - the order
 * {@link ReceivableService} takes them in.</p>
 */
@Service
@Transactional
public class MoneyLedgerService {

    private static final Logger log = LoggerFactory.getLogger(MoneyLedgerService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Statuses whose money is settled for good (or being refunded): history is not rewritten under them. */
    private static final Set<String> SETTLED = Set.of("CANCELLED", "COMPLETED", "CLOSED", "RETURN_REQUESTED",
            "RETURNED", "REFUNDED", "PARTIALLY_REFUNDED");

    /** Pay part of a transfer's credit to an order ({@code orderId}) or a receivable ({@code receivableId}). */
    public record AllocateCommand(UUID transferId, UUID orderId, UUID receivableId, BigDecimal amount, UUID by) {
    }

    /** Take an allocation back; {@code holdOrder} agrees to hold a confirmed order left unpaid by it. */
    public record ReverseCommand(UUID transferId, UUID allocationId, String reason, boolean holdOrder, UUID by) {
    }

    public record CreditRefundCommand(UUID transferId, BigDecimal amount, String reason, UUID by) {
    }

    public record CarrierRemittanceCommand(UUID receivableId, String carrierName, String remittanceReference,
                                           BigDecimal amount, LocalDate receivedOn, String note, UUID by) {
    }

    public record AllocationResult(CustomerTransfer transfer, ReceivableBook.Allocation allocation) {
    }

    public record CreditRefundResult(CustomerTransfer transfer, UUID refundId) {
    }

    public record TransferDetail(CustomerTransfer transfer, List<ReceivableBook.Allocation> allocations) {
    }

    /**
     * An order's money, as the ledger has it.
     *
     * @param collected              net allocations, earlier payments, less refunds asked for
     * @param outstandingReceivables what its receivables still owe (the customer's, or the carrier's)
     * @param mayStillTake           what a transfer may still pay to the order itself
     */
    public record OrderPayments(PayableOrder order, BigDecimal collected, BigDecimal outstandingReceivables,
                                BigDecimal mayStillTake, List<ReceivableBook.Allocation> allocations,
                                Map<UUID, CustomerTransfer> transfers, List<Receivable> receivables,
                                List<ReceivableBook.RefundLine> refunds) {
    }

    private final ReceivableBook book;
    private final OrderPayables orders;
    private final OrderAllocator orderAllocator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MoneyLedgerService(ReceivableBook book, OrderPayables orders, OrderAllocator orderAllocator,
                       ApplicationEventPublisher events, Clock clock) {
        this.book = book;
        this.orders = orders;
        this.orderAllocator = orderAllocator;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code TRANSFER_NOT_FOUND}; {@code VALIDATION_FAILED} unless exactly one of order and
     *         receivable is named; for an order those of {@link OrderAllocator}; for a receivable
     *         {@code RECEIVABLE_NOT_FOUND}, {@code ALLOCATION_CUSTOMER_MISMATCH}, {@code RECEIVABLE_CARRIER_COLLECTED}
     *         (a customer's transfer never pays what the carrier collected), {@code ALLOCATION_EXCEEDS_AMOUNT_DUE};
     *         {@code ALLOCATION_EXCEEDS_TRANSFER}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-transfer", resourceId = "#command.transferId()")
    public AllocationResult allocate(AllocateCommand command) {
        if ((command.orderId() == null) == (command.receivableId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name an order or a receivable, not both");
        }
        CustomerTransfer seen = transfer(command.transferId());
        if (command.orderId() != null) {
            PayableOrder order = orderAllocator.lockPayable(command.orderId(), seen.customerId());
            CustomerTransfer transfer = lockTransfer(command.transferId());
            ReceivableBook.Allocation allocation = orderAllocator.allocate(transfer, order, command.amount(),
                    command.by());
            return new AllocationResult(book.save(transfer), allocation);
        }
        Receivable receivable = book.lockReceivable(command.receivableId()).orElseThrow(() ->
                new BusinessException(ErrorCode.RECEIVABLE_NOT_FOUND, "No receivable " + command.receivableId()));
        if (!receivable.customerId().equals(seen.customerId())) {
            throw new BusinessException(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH,
                    "Receivable %s belongs to another customer".formatted(receivable.id()));
        }
        if (receivable.carrierCollected() && seen.channel() != TransferChannel.CARRIER_REMITTANCE) {
            throw new BusinessException(ErrorCode.RECEIVABLE_CARRIER_COLLECTED,
                    "Receivable %s is collected by the carrier".formatted(receivable.id()));
        }
        CustomerTransfer transfer = lockTransfer(command.transferId());
        BigDecimal amount = command.amount() != null ? command.amount()
                : receivable.outstanding().min(transfer.unallocatedAmount());
        if (amount.signum() <= 0 || amount.compareTo(receivable.outstanding()) > 0) {
            throw new BusinessException(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE,
                    "Receivable %s has %s %s outstanding".formatted(receivable.id(),
                            receivable.outstanding().stripTrailingZeros().toPlainString(), receivable.currency()));
        }
        transfer.takeExactly(amount);
        ReceivableBook.Allocation allocation = payReceivable(transfer, receivable, amount, command.by());
        return new AllocationResult(book.save(transfer), allocation);
    }

    /**
     * Takes back what an allocation brought (BR-06: a reversal row; the original stays). The money is the customer's
     * credit again; the order hears {@code PaymentReversed} and lowers what it has been paid.
     *
     * @throws BusinessException {@code TRANSFER_NOT_FOUND}; {@code ALLOCATION_NOT_FOUND} (also when the allocation is
     *         another transfer's); {@code ALLOCATION_ALREADY_REVERSED}; {@code PAYMENT_REVERSAL_NOT_ALLOWED} when a
     *         refund was asked of that money or the order is settled, cancelled or closed;
     *         {@code PAYMENT_REVERSAL_NEEDS_HOLD} when a confirmed order would be left short of its terms and
     *         {@code holdOrder} is not set
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-transfer", resourceId = "#command.transferId()")
    public AllocationResult reverse(ReverseCommand command) {
        String reason = command.reason() == null ? null : command.reason().trim();
        if (reason == null || reason.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the money is taken back");
        }
        transfer(command.transferId());
        ReceivableBook.Allocation original = book.findAllocation(command.allocationId())
                .filter(a -> a.transferId().equals(command.transferId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ALLOCATION_NOT_FOUND,
                        "Transfer %s has no allocation %s".formatted(command.transferId(), command.allocationId())));
        if (original.isReversal()) {
            throw new BusinessException(ErrorCode.ALLOCATION_ALREADY_REVERSED, "A reversal is not reversed");
        }
        PayableOrder order = orders.lockForPayment(original.orderId()).orElseThrow(() ->
                new IllegalStateException("Allocation %s names no order".formatted(original.id())));
        // Under the order's lock: a second reversal of the same allocation waits here and then finds this one.
        if (book.isReversed(original.id())) {
            throw new BusinessException(ErrorCode.ALLOCATION_ALREADY_REVERSED,
                    "Allocation %s was already reversed".formatted(original.id()));
        }
        if (book.hasRefunds(original.id())) {
            throw new BusinessException(ErrorCode.PAYMENT_REVERSAL_NOT_ALLOWED,
                    "A refund was asked of allocation %s's money".formatted(original.id()));
        }
        if (SETTLED.contains(order.status())) {
            throw new BusinessException(ErrorCode.PAYMENT_REVERSAL_NOT_ALLOWED,
                    "Order %s is %s; its money is settled".formatted(order.orderNumber(), order.status()));
        }
        BigDecimal threshold = order.confirmationThreshold();
        if (threshold != null && !command.holdOrder()
                && book.collectedFor(order.orderId()).subtract(original.amount()).compareTo(threshold) < 0) {
            throw new BusinessException(ErrorCode.PAYMENT_REVERSAL_NEEDS_HOLD,
                    "Order %s would be left short of its %s terms; agree to put it on hold".formatted(
                            order.orderNumber(), order.paymentTerm()));
        }
        if (original.receivableId() != null) {
            Receivable receivable = book.lockReceivable(original.receivableId()).orElseThrow();
            receivable.unallocate(original.amount(), LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE));
            book.save(receivable);
        }
        CustomerTransfer transfer = lockTransfer(command.transferId());
        transfer.giveBack(original.amount());
        Instant now = clock.instant();
        ReceivableBook.Allocation reversal;
        try {
            reversal = book.recordReversal(original, now, command.by(), reason);
        } catch (DataIntegrityViolationException raced) {
            throw new BusinessException(ErrorCode.ALLOCATION_ALREADY_REVERSED,
                    "Allocation %s was already reversed".formatted(original.id()));
        }
        CustomerTransfer saved = book.save(transfer);
        events.publishEvent(new PaymentReversed(order.orderId(), reversal.id(), original.id(), original.amount(),
                transfer.currency(), original.paysOrder() ? OrderAllocator.ORDER_ALLOCATION
                        : ReceivableService.ALLOCATION_SOURCE, command.by(), now));
        log.info("Allocation {} of transfer {} reversed: {} {} back from order {}", original.id(), transfer.id(),
                original.amount(), transfer.currency(), order.orderNumber());
        return new AllocationResult(saved, reversal);
    }

    /**
     * @throws BusinessException {@code TRANSFER_NOT_FOUND}; {@code ALLOCATION_EXCEEDS_TRANSFER} (BR-05)
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-transfer", resourceId = "#command.transferId()")
    public CreditRefundResult refundCredit(CreditRefundCommand command) {
        String reason = command.reason() == null ? null : command.reason().trim();
        if (reason == null || reason.isEmpty() || command.amount() == null || command.amount().signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A credit refund names its amount and reason");
        }
        CustomerTransfer transfer = lockTransfer(command.transferId());
        transfer.refundCredit(command.amount());
        CustomerTransfer saved = book.save(transfer);
        UUID refund = book.recordCreditRefund(saved, command.amount(), reason);
        log.info("Refund {} of {} {} asked from the credit of transfer {}", refund, command.amount(),
                saved.currency(), saved.id());
        return new CreditRefundResult(saved, refund);
    }

    /**
     * @throws BusinessException {@code RECEIVABLE_NOT_FOUND}; {@code RECEIVABLE_NOT_CARRIER_COLLECTED};
     *         {@code TRANSFER_REFERENCE_ALREADY_RECORDED} (BR-07, per remittance line and order)
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer-transfer", resourceId = "#result?.transfer()?.id()")
    public AllocationResult recordCarrierRemittance(CarrierRemittanceCommand command) {
        Receivable receivable = book.lockReceivable(command.receivableId()).orElseThrow(() ->
                new BusinessException(ErrorCode.RECEIVABLE_NOT_FOUND, "No receivable " + command.receivableId()));
        if (!receivable.carrierCollected()) {
            throw new BusinessException(ErrorCode.RECEIVABLE_NOT_CARRIER_COLLECTED,
                    "Receivable %s is paid by the customer".formatted(receivable.id()));
        }
        if (receivable.outstanding().signum() <= 0) {
            throw new BusinessException(ErrorCode.RECEIVABLE_SETTLED,
                    "Receivable %s is paid in full".formatted(receivable.id()));
        }
        String orderNumber = orders.find(receivable.orderId()).map(PayableOrder::orderNumber)
                .orElse(receivable.orderId().toString());
        String reference = remittanceReference(command.remittanceReference(), orderNumber, receivable);
        if (book.referenceRecorded(reference)) {
            throw alreadyRecorded(reference);
        }
        CustomerTransfer transfer;
        try {
            transfer = book.save(CustomerTransfer.carrierRemittance(receivable.customerId(), command.carrierName(),
                    reference, command.amount(), receivable.currency(), command.receivedOn(), command.by(),
                    clock.instant(), command.note()));
        } catch (DataIntegrityViolationException duplicate) {
            throw alreadyRecorded(reference);
        }
        BigDecimal applied = transfer.take(receivable.outstanding());
        ReceivableBook.Allocation allocation = applied.signum() > 0
                ? payReceivable(transfer, receivable, applied, command.by()) : null;
        CustomerTransfer saved = book.save(transfer);
        if (saved.unallocatedAmount().signum() > 0) {
            log.warn("Carrier remittance {} for order {} brought {} {} more than the balance it collected; kept as the "
                    + "customer's credit for the accountant to settle", saved.id(), orderNumber,
                    saved.unallocatedAmount(), saved.currency());
        }
        if (receivable.outstanding().signum() > 0) {
            log.warn("Carrier remittance {} for order {} is {} {} short: the carrier still owes it", saved.id(),
                    orderNumber, receivable.outstanding(), receivable.currency());
        }
        return new AllocationResult(saved, allocation);
    }

    @Transactional(readOnly = true)
    public TransferDetail transferDetail(UUID transferId) {
        return new TransferDetail(transfer(transferId), book.allocationsOfTransfer(transferId));
    }

    /** @throws BusinessException {@code NOT_FOUND} */
    @Transactional(readOnly = true)
    public OrderPayments orderPayments(UUID orderId) {
        PayableOrder order = orders.find(orderId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        List<ReceivableBook.Allocation> allocations = book.allocationsOfOrder(orderId);
        Map<UUID, CustomerTransfer> transfers = new LinkedHashMap<>();
        allocations.forEach(a -> transfers.computeIfAbsent(a.transferId(), id -> book.findTransfer(id).orElseThrow()));
        BigDecimal collected = book.collectedFor(orderId);
        BigDecimal outstanding = book.outstandingReceivablesOf(orderId);
        BigDecimal room = "CREDIT".equals(order.paymentTerm()) || "CANCELLED".equals(order.status())
                ? BigDecimal.ZERO : orderAllocator.mayStillTake(order);
        return new OrderPayments(order, collected, outstanding, room, allocations, transfers,
                book.receivablesOfOrder(orderId), book.refundsOfOrder(orderId));
    }

    /**
     * BR-07 for a carrier's remittance: one statement line of the carrier pays many parcels - several orders, and several
     * shipments of one split order - each once. The line's reference, the order and, for a shipment's balance, the last
     * 12 hex digits of the shipment id (its random part) name one of them: at most 60 + 1 + 18 + 1 + 12 characters.
     */
    static String remittanceReference(String statementLine, String orderNumber, Receivable receivable) {
        String base = Objects.requireNonNull(statementLine, "remittanceReference").trim() + ":" + orderNumber;
        if (receivable.shipmentId() == null) {
            return base;
        }
        String shipment = receivable.shipmentId().toString().replace("-", "");
        return base + ":" + shipment.substring(shipment.length() - 12);
    }

    private ReceivableBook.Allocation payReceivable(CustomerTransfer transfer, Receivable receivable, BigDecimal amount,
                                                    UUID by) {
        Instant now = clock.instant();
        BigDecimal applied = receivable.allocate(amount, now);
        if (applied.compareTo(amount) != 0) {
            throw new IllegalStateException("Receivable %s took %s of %s".formatted(receivable.id(), applied, amount));
        }
        book.save(receivable);
        ReceivableBook.Allocation allocation = book.recordAllocation(transfer.id(), receivable.orderId(),
                receivable.id(), applied, now, by);
        events.publishEvent(new PaymentCaptured(receivable.orderId(), allocation.id(), applied, receivable.currency(),
                transfer.channel() == TransferChannel.CARRIER_REMITTANCE ? PaymentMethod.COD
                        : PaymentMethod.BANK_TRANSFER, ReceivableService.ALLOCATION_SOURCE));
        return allocation;
    }

    private CustomerTransfer transfer(UUID id) {
        return book.findTransfer(id).orElseThrow(() ->
                new BusinessException(ErrorCode.TRANSFER_NOT_FOUND, "No transfer " + id));
    }

    private CustomerTransfer lockTransfer(UUID id) {
        return book.lockTransfer(id).orElseThrow(() ->
                new BusinessException(ErrorCode.TRANSFER_NOT_FOUND, "No transfer " + id));
    }

    private static BusinessException alreadyRecorded(String reference) {
        return new BusinessException(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED,
                "Transfer %s was already recorded".formatted(reference));
    }
}
