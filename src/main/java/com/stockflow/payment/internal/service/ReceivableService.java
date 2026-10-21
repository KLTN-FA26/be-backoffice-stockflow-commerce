package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.Collector;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import com.stockflow.payment.internal.repository.ReceivableSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Receivables of credit orders and the transfers that pay them (SCRUM-431, kltn-docs 15 §4.3); since G2-3 the one
 * ledger of incoming money: a transfer pays orders (prepaid, deposit, balance before delivery) as well.
 *
 * <ul>
 *   <li>Delivered credit order → a receivable for what it still owes, due on the delivery day (Saigon)
 *       plus the order's days to pay (15 §4.3 step 2). The customer's unallocated credit pays it first.</li>
 *   <li>The accountant records a transfer from the bank statement (BR-04), once per reference (BR-07);
 *       it pays the receivables the customer named, otherwise the earliest due first (BR-09). What is
 *       left stays as the customer's credit.</li>
 *   <li>Every allocation is announced as {@code PaymentCaptured} for its order (source
 *       RECEIVABLE_ALLOCATION), so the order knows what it has been paid.</li>
 *   <li>G2-3: a transfer may name orders it pays, with the amount for each ({@link OrderAllocator}); the rest pays the
 *       receivables named, or - when neither orders nor receivables are named - the earliest due first.</li>
 *   <li>G2-3: a delivered deposit order whose balance the carrier collected (ON_DELIVERY) becomes a carrier-collected
 *       receivable, due on the delivery day; the customer's transfers and credit never touch it.</li>
 * </ul>
 *
 * <p>Locks are always taken orders first, then receivables, then transfers, so a delivery, an allocation and a
 * transfer of the same customer never deadlock.</p>
 */
@Service
@Transactional
public class ReceivableService {

    private static final Logger log = LoggerFactory.getLogger(ReceivableService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String ALLOCATION_SOURCE = "RECEIVABLE_ALLOCATION";

    private static final SortWhitelist RECEIVABLE_SORT = SortWhitelist.of("dueDate", "issuedAt", "amount", "status")
            .withDefault("dueDate", Sort.Direction.ASC);
    private static final SortWhitelist TRANSFER_SORT = SortWhitelist.of("receivedOn", "recordedAt", "amount")
            .withDefault("receivedOn", Sort.Direction.DESC);

    /**
     * Record a transfer. {@code orders}: what it pays to which orders (G2-3). {@code allocateTo}: the receivables it
     * pays with the rest. Neither named: the earliest due receivables first (BR-09); orders named but no receivables:
     * the rest stays as the customer's credit.
     */
    public record RecordTransferCommand(UUID customerId, String reference, BigDecimal amount, String currency,
                                        LocalDate receivedOn, List<UUID> allocateTo, String note, UUID recordedBy,
                                        List<OrderPayment> orders) {

        public RecordTransferCommand(UUID customerId, String reference, BigDecimal amount, String currency,
                                     LocalDate receivedOn, List<UUID> allocateTo, String note, UUID recordedBy) {
            this(customerId, reference, amount, currency, receivedOn, allocateTo, note, recordedBy, List.of());
        }

        public RecordTransferCommand {
            orders = orders == null ? List.of() : List.copyOf(orders);
        }
    }

    /** Part of a transfer paid to one order. */
    public record OrderPayment(UUID orderId, BigDecimal amount) {
    }

    public record TransferResult(CustomerTransfer transfer, List<ReceivableBook.Allocation> allocations) {
    }

    private final ReceivableBook book;
    private final ReceivableSearch search;
    private final OrderAllocator orderAllocator;
    private final com.stockflow.payment.api.OrderPayables orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReceivableService(ReceivableBook book, ReceivableSearch search, OrderAllocator orderAllocator,
                      com.stockflow.payment.api.OrderPayables orders, ApplicationEventPublisher events, Clock clock) {
        this.book = book;
        this.search = search;
        this.orderAllocator = orderAllocator;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Decision Q6a: the agreed cancellation amount the money received did not cover is owed, due from the
     * cancellation day. One receivable per order ({@code uk_receivable_order}): a cancelled order is never
     * delivered, so no CREDIT_ORDER receivable exists or follows for it — the sale is not billed as well — and a
     * redelivered event finds this one open. Nothing is allocated or collected here.
     */
    public void cancellationOwed(OrderCancelled event) {
        BigDecimal owed = event.owedAmount() == null ? BigDecimal.ZERO : event.owedAmount();
        if (owed.signum() <= 0 || event.customerId() == null) {
            return;
        }
        Optional<Receivable> existing = book.findReceivableOfOrder(event.orderId());
        if (existing.isPresent()) {
            if (!"CANCELLATION_FEE".equals(existing.get().kind())) {
                throw new IllegalStateException(("Order %s already has a %s receivable; its cancellation fee needs the "
                        + "accountant").formatted(event.orderNumber(), existing.get().kind()));
            }
            return;
        }
        LocalDate due = LocalDate.ofInstant(event.cancelledAt(), BUSINESS_ZONE);
        Receivable receivable = book.save(Receivable.openCancellationFee(event.orderId(), event.customerId(), owed,
                event.currency(), event.cancelledAt(), due));
        log.info("Receivable {} opened for the cancellation fee of order {}: {} {} owed", receivable.id(),
                event.orderNumber(), owed, event.currency());
    }

    /**
     * Idempotent: a redelivered event finds the receivable already open. G3-2 (split shipments): one receivable per
     * delivered shipment, for what the order billed for it ({@code billedAmount}); an event without it - the whole
     * order delivered, written before G3-2 - bills what the order still owes, as before.
     *
     * <p>G2-3: a deposit order whose balance the carrier collects (ON_DELIVERY) bills what the carrier collected on
     * delivery - the amount fixed at the handover (15 BR-08) - as a carrier-collected receivable due that day; the
     * carrier's remittance pays it (09 §4.3 step 4). A deposit order paid before delivery bills nothing.</p>
     *
     * <p>BALCREDIT: a deposit order whose balance goes on credit (ON_CREDIT) bills what the order billed for the delivery
     * - the goods less the deposit not yet used - as the customer's DEPOSIT_BALANCE receivable, due after their days to
     * pay; their unallocated credit pays it first, as for a credit order.</p>
     */
    public void orderDelivered(OrderDelivered event) {
        if (event.customerId() == null) {
            return;
        }
        boolean credit = "CREDIT".equals(event.paymentTerm());
        boolean deposit = "DEPOSIT".equals(event.paymentTerm());
        boolean carrierCollects = deposit && "ON_DELIVERY".equals(event.balancePolicy());
        // BALCREDIT: the balance of a deposit order on credit is billed per delivery as a credit order's is, due after
        // the customer's days to pay, the customer's own debt (counted in their credit position).
        boolean balanceOnCredit = deposit && "ON_CREDIT".equals(event.balancePolicy());
        if (!credit && !deposit) {
            return;
        }
        Optional<Receivable> existing = event.shipmentId() == null ? book.findReceivableOfOrder(event.orderId())
                : book.findReceivableOfShipment(event.orderId(), event.shipmentId());
        if (existing.isPresent()) {
            return;
        }
        LocalDate deliveryDay = LocalDate.ofInstant(event.deliveredAt(), BUSINESS_ZONE);
        if (deposit && !carrierCollects && !balanceOnCredit) {
            balanceDeliveredUnpaid(event, deliveryDay);
            return;
        }
        BigDecimal owed = event.billedAmount() != null ? event.billedAmount()
                : event.orderTotal().subtract(event.paidAmount() == null ? BigDecimal.ZERO : event.paidAmount());
        if (owed.signum() <= 0) {
            return;
        }
        if (carrierCollects) {
            Receivable balance = book.save(Receivable.openCarrierCollected(event.orderId(), event.customerId(), owed,
                    event.currency(), event.deliveredAt(), deliveryDay).forShipment(event.shipmentId()));
            log.info("Receivable {} opened for order {}: the carrier collected {} {} on delivery", balance.id(),
                    event.orderNumber(), owed, event.currency());
            return;
        }
        int days = event.creditTermDays() == null ? 0 : event.creditTermDays();
        LocalDate due = deliveryDay.plusDays(days);
        Receivable opened = Receivable.open(event.orderId(), event.customerId(), owed, event.currency(),
                event.deliveredAt(), due).forShipment(event.shipmentId());
        Receivable receivable = book.save(balanceOnCredit ? opened.withKind("DEPOSIT_BALANCE") : opened);
        log.info("Receivable {} opened for order {}: {} {} due {}", receivable.id(), event.orderNumber(), owed,
                event.currency(), due);
        // The customer's unallocated credit pays it first.
        List<Receivable> unpaid = book.lockUnpaid(event.customerId());
        allocate(book.lockWithCredit(event.customerId()), unpaid, null);
    }

    /**
     * A deposit order whose balance was to be paid before delivery, delivered without it (G3 §4: the handover gate
     * should not let it happen - goods that left before G2-3, or money reversed after the handover). What it still owes
     * becomes the customer's receivable, due on the delivery day, and the accountant is warned. Measured on the ledger,
     * not on the event: the order's count of the money may be behind. A later shipment bills only what no receivable
     * of the order already holds.
     */
    private void balanceDeliveredUnpaid(OrderDelivered event, LocalDate deliveryDay) {
        BigDecimal billable = orders.find(event.orderId()).map(PayableOrder::billable).orElse(event.orderTotal());
        BigDecimal owed = billable.subtract(book.collectedFor(event.orderId()))
                .subtract(book.outstandingReceivablesOf(event.orderId()));
        if (owed.signum() <= 0) {
            return;
        }
        Receivable balance = book.save(Receivable.open(event.orderId(), event.customerId(), owed, event.currency(),
                event.deliveredAt(), deliveryDay).withKind("DEPOSIT_BALANCE").forShipment(event.shipmentId()));
        log.warn("Order {} was delivered with {} {} of its balance unpaid (to be paid before delivery): receivable {} "
                + "opened, due {}", event.orderNumber(), owed, event.currency(), balance.id(), deliveryDay);
        List<Receivable> unpaid = book.lockUnpaid(event.customerId());
        allocate(book.lockWithCredit(event.customerId()), unpaid, null);
    }

    /**
     * @throws BusinessException {@code TRANSFER_REFERENCE_ALREADY_RECORDED} (BR-07);
     *         {@code RECEIVABLE_NOT_FOUND}; {@code ALLOCATION_CUSTOMER_MISMATCH}; {@code RECEIVABLE_CARRIER_COLLECTED};
     *         for orders, those of {@link OrderAllocator}: {@code ALLOCATION_EXCEEDS_AMOUNT_DUE},
     *         {@code ALLOCATION_ORDER_CANCELLED}, {@code ALLOCATION_ORDER_NOT_PAYABLE}, and
     *         {@code ALLOCATION_EXCEEDS_TRANSFER} when the orders ask for more than the transfer brings
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer-transfer", resourceId = "#result?.transfer()?.id()")
    public TransferResult recordTransfer(RecordTransferCommand command) {
        if (book.referenceRecorded(command.reference())) {
            throw alreadyRecorded(command.reference());
        }
        // Orders first, by id: the lock order every money path takes (orders, receivables, transfers).
        List<OrderPayment> toOrders = new ArrayList<>(command.orders());
        if (new HashSet<>(toOrders.stream().map(OrderPayment::orderId).toList()).size() != toOrders.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name each order once");
        }
        toOrders.sort(Comparator.comparing(OrderPayment::orderId));
        List<PayableOrder> payable = new ArrayList<>();
        for (OrderPayment wanted : toOrders) {
            payable.add(orderAllocator.lockPayable(wanted.orderId(), command.customerId()));
        }
        List<Receivable> unpaid = book.lockUnpaid(command.customerId());
        List<Receivable> targets = toOrders.isEmpty() ? unpaid : List.of();
        if (command.allocateTo() != null && !command.allocateTo().isEmpty()) {
            targets = new ArrayList<>();
            for (UUID id : command.allocateTo()) {
                Receivable named = book.findReceivable(id).orElseThrow(() ->
                        new BusinessException(ErrorCode.RECEIVABLE_NOT_FOUND, "No receivable " + id));
                if (!named.customerId().equals(command.customerId())) {
                    throw new BusinessException(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH,
                            "Receivable %s belongs to another customer".formatted(id));
                }
                if (named.carrierCollected()) {
                    // 09 §2: the customer paid it to the carrier; paying it again would collect it twice.
                    throw new BusinessException(ErrorCode.RECEIVABLE_CARRIER_COLLECTED,
                            "Receivable %s is collected by the carrier".formatted(id));
                }
                unpaid.stream().filter(r -> r.id().equals(id)).findFirst().ifPresent(targets::add);
            }
        }
        CustomerTransfer transfer;
        try {
            transfer = book.save(CustomerTransfer.record(command.customerId(), command.reference(), command.amount(),
                    command.currency() == null ? "VND" : command.currency(), command.receivedOn(), command.recordedBy(),
                    clock.instant(), command.note()));
        } catch (DataIntegrityViolationException duplicate) {
            throw alreadyRecorded(command.reference());
        }
        List<ReceivableBook.Allocation> made = new ArrayList<>();
        for (int i = 0; i < toOrders.size(); i++) {
            made.add(orderAllocator.allocate(transfer, payable.get(i), toOrders.get(i).amount(), command.recordedBy()));
        }
        // Saves the transfer with what is left of it, whether or not a receivable took any.
        made.addAll(allocate(new ArrayList<>(List.of(transfer)), targets, command.recordedBy()));
        // The id, not the statement reference the accountant typed (log injection).
        log.info("Transfer {} of {} {} recorded for customer {}: {} allocation(s), {} left as credit",
                transfer.id(), command.amount(), transfer.currency(), command.customerId(), made.size(),
                transfer.unallocatedAmount());
        return new TransferResult(transfer, made);
    }

    /** Transfers in order, receivables in order: each receivable takes what the transfers still hold. */
    private List<ReceivableBook.Allocation> allocate(List<CustomerTransfer> transfers, List<Receivable> receivables,
                                                     UUID by) {
        List<ReceivableBook.Allocation> made = new ArrayList<>();
        Instant now = clock.instant();
        for (Receivable receivable : receivables) {
            for (CustomerTransfer transfer : transfers) {
                if (receivable.outstanding().signum() <= 0) {
                    break;
                }
                if (transfer.unallocatedAmount().signum() <= 0 || !transfer.currency().equals(receivable.currency())) {
                    continue;
                }
                BigDecimal taken = transfer.take(receivable.outstanding());
                BigDecimal applied = receivable.allocate(taken, now);
                ReceivableBook.Allocation allocation = book.recordAllocation(transfer.id(), receivable.orderId(),
                        receivable.id(), applied, now, by);
                made.add(allocation);
                events.publishEvent(new PaymentCaptured(receivable.orderId(), allocation.id(), applied,
                        receivable.currency(), PaymentMethod.BANK_TRANSFER, ALLOCATION_SOURCE));
            }
            book.save(receivable);
        }
        transfers.forEach(book::save);
        return made;
    }

    @Transactional(readOnly = true)
    public PageResponse<Receivable> receivables(UUID customerId, ReceivableStatus status, LocalDate dueBefore, int page,
                                                int size, String sort) {
        return receivables(customerId, status, dueBefore, null, null, page, size, sort);
    }

    /** G2-3: {@code collector} and {@code kind} null mean any. */
    @Transactional(readOnly = true)
    public PageResponse<Receivable> receivables(UUID customerId, ReceivableStatus status, LocalDate dueBefore,
                                                Collector collector, String kind, int page, int size, String sort) {
        return receivables(customerId, null, status, dueBefore, collector, kind, page, size, sort);
    }

    /** The same, narrowed to one order when {@code orderId} is set (E2E QA 2026-10-21: it used to be ignored). */
    @Transactional(readOnly = true)
    public PageResponse<Receivable> receivables(UUID customerId, UUID orderId, ReceivableStatus status,
                                                LocalDate dueBefore, Collector collector, String kind, int page,
                                                int size, String sort) {
        return Pages.toResponse(search.receivables(customerId, orderId, status, dueBefore, collector, kind,
                Pages.of(page, size, RECEIVABLE_SORT.parse(sort))));
    }

    @Transactional(readOnly = true)
    public Receivable receivable(UUID id) {
        return book.findReceivable(id).orElseThrow(() ->
                new BusinessException(ErrorCode.RECEIVABLE_NOT_FOUND, "No receivable " + id));
    }

    @Transactional(readOnly = true)
    public List<ReceivableBook.Allocation> allocationsOf(UUID receivableId) {
        return book.allocationsOfReceivable(receivableId);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerTransfer> transfers(UUID customerId, int page, int size, String sort) {
        return transfers(customerId, false, page, size, sort);
    }

    /** G2-3: {@code withCreditOnly} lists the money not yet allocated - the customer's credit. */
    @Transactional(readOnly = true)
    public PageResponse<CustomerTransfer> transfers(UUID customerId, boolean withCreditOnly, int page, int size,
                                                    String sort) {
        return Pages.toResponse(search.transfers(customerId, withCreditOnly,
                Pages.of(page, size, TRANSFER_SORT.parse(sort))));
    }

    /** OPEN / PARTIALLY_PAID past due become OVERDUE; run daily by {@code ReceivableOverdueJob}. */
    public int markOverdue() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        List<UUID> marked = book.markOverdue(today);
        if (!marked.isEmpty()) {
            log.info("{} receivable(s) overdue as of {}", marked.size(), today);
        }
        return marked.size();
    }

    private static BusinessException alreadyRecorded(String reference) {
        return new BusinessException(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED,
                "Transfer %s was already recorded".formatted(reference));
    }
}
