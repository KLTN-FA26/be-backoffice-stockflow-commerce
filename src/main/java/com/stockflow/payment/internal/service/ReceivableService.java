package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
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
import java.util.List;
import java.util.UUID;

/**
 * Receivables of credit orders and the transfers that pay them (SCRUM-431, kltn-docs 15 §4.3).
 *
 * <ul>
 *   <li>Delivered credit order → a receivable for what it still owes, due on the delivery day (Saigon)
 *       plus the order's days to pay (15 §4.3 step 2). The customer's unallocated credit pays it first.</li>
 *   <li>The accountant records a transfer from the bank statement (BR-04), once per reference (BR-07);
 *       it pays the receivables the customer named, otherwise the earliest due first (BR-09). What is
 *       left stays as the customer's credit.</li>
 *   <li>Every allocation is announced as {@code PaymentCaptured} for its order (source
 *       RECEIVABLE_ALLOCATION), so the order knows what it has been paid.</li>
 * </ul>
 *
 * <p>Locks are always taken receivables first, then transfers, so a delivery and a transfer of the same
 * customer never deadlock.</p>
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

    /** Record a transfer; {@code allocateTo} empty means earliest due first. */
    public record RecordTransferCommand(UUID customerId, String reference, BigDecimal amount, String currency,
                                        LocalDate receivedOn, List<UUID> allocateTo, String note, UUID recordedBy) {
    }

    public record TransferResult(CustomerTransfer transfer, List<ReceivableBook.Allocation> allocations) {
    }

    private final ReceivableBook book;
    private final ReceivableSearch search;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReceivableService(ReceivableBook book, ReceivableSearch search, ApplicationEventPublisher events, Clock clock) {
        this.book = book;
        this.search = search;
        this.events = events;
        this.clock = clock;
    }

    /** Idempotent: a redelivered event finds the receivable already open. */
    public void orderDelivered(OrderDelivered event) {
        if (!"CREDIT".equals(event.paymentTerm()) || event.customerId() == null) {
            return;
        }
        BigDecimal owed = event.orderTotal().subtract(event.paidAmount() == null ? BigDecimal.ZERO : event.paidAmount());
        if (owed.signum() <= 0 || book.findReceivableOfOrder(event.orderId()).isPresent()) {
            return;
        }
        int days = event.creditTermDays() == null ? 0 : event.creditTermDays();
        LocalDate due = LocalDate.ofInstant(event.deliveredAt(), BUSINESS_ZONE).plusDays(days);
        Receivable receivable = book.save(Receivable.open(event.orderId(), event.customerId(), owed, event.currency(),
                event.deliveredAt(), due));
        log.info("Receivable {} opened for order {}: {} {} due {}", receivable.id(), event.orderNumber(), owed,
                event.currency(), due);
        // The customer's unallocated credit pays it first.
        List<Receivable> unpaid = book.lockUnpaid(event.customerId());
        allocate(book.lockWithCredit(event.customerId()), unpaid, null);
    }

    /**
     * @throws BusinessException {@code TRANSFER_REFERENCE_ALREADY_RECORDED} (BR-07);
     *         {@code RECEIVABLE_NOT_FOUND}; {@code ALLOCATION_CUSTOMER_MISMATCH}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer-transfer", resourceId = "#result?.transfer()?.id()")
    public TransferResult recordTransfer(RecordTransferCommand command) {
        if (book.referenceRecorded(command.reference())) {
            throw alreadyRecorded(command.reference());
        }
        List<Receivable> unpaid = book.lockUnpaid(command.customerId());
        List<Receivable> targets = unpaid;
        if (command.allocateTo() != null && !command.allocateTo().isEmpty()) {
            targets = new ArrayList<>();
            for (UUID id : command.allocateTo()) {
                Receivable named = book.findReceivable(id).orElseThrow(() ->
                        new BusinessException(ErrorCode.RECEIVABLE_NOT_FOUND, "No receivable " + id));
                if (!named.customerId().equals(command.customerId())) {
                    throw new BusinessException(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH,
                            "Receivable %s belongs to another customer".formatted(id));
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
        List<ReceivableBook.Allocation> made = allocate(new ArrayList<>(List.of(transfer)), targets, command.recordedBy());
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
                ReceivableBook.Allocation allocation = book.recordAllocation(transfer.id(), receivable.id(), applied,
                        now, by);
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
        return Pages.toResponse(search.receivables(customerId, status, dueBefore,
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
        return Pages.toResponse(search.transfers(customerId, Pages.of(page, size, TRANSFER_SORT.parse(sort))));
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
