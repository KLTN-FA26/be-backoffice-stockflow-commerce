package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.contracts.OrderRefundRecorded;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.api.InstallmentOrder;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.domain.RefundStanding;
import com.stockflow.payment.internal.domain.SettlementBook;
import com.stockflow.payment.internal.repository.RefundSearch;
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
import java.util.List;
import java.util.UUID;

/**
 * The refunds of an order (4B-9; kltn-docs 15 §4.5, §5.3, BR-14; D15): what its settlements made refundable, what the
 * accountant recorded as refunded outside the system, and where that leaves it.
 *
 * <ul>
 *   <li><b>Record</b>: an amount no larger than what is still to refund (BR-14), the day it left, the outgoing statement
 *       line - once per live record. Several records may refund one order in parts (§4.5 step 16).</li>
 *   <li><b>Void</b>: a record made wrong is voided with a reason and kept; its amount is to refund again.</li>
 * </ul>
 *
 * <p>Locks: the order first ({@link InstallmentOrders#lockForPayment}, as every money movement takes it), so two records
 * for one order are decided one after the other against the same settlements; {@code tg_refund_record_total} says the
 * cap again at commit.</p>
 *
 * <p>The refund's standing is computed ({@link RefundStanding}), and every record or void announces it
 * ({@link OrderRefundRecorded}). A cancelled order stays CANCELLED (17 §5.1 - CANCELLED is final); a RETURNED order the
 * standing shows refunded in full moves on to REFUNDED / PARTIALLY_REFUNDED in the order module (4D-3).</p>
 */
@Service
@Transactional
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /** An order's refund: where it stands, the settlements that made it, and the records against it. */
    public record OrderRefundDetail(RefundStanding standing, String orderNumber, String currency,
                                    List<OrderSettlement> settlements, List<RefundRecord> records) {
    }

    /** What the accountant records. */
    public record RecordRefundCommand(UUID orderId, BigDecimal amount, LocalDate refundedOn, String reference,
                                      String note, UUID recordedBy) {
    }

    private final SettlementBook book;
    private final RefundSearch search;
    private final InstallmentOrders orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RefundService(SettlementBook book, RefundSearch search, InstallmentOrders orders, ApplicationEventPublisher events,
                  Clock clock) {
        this.book = book;
        this.search = search;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code NOT_FOUND} for no such order; {@code REFUND_EXCEEDS_REFUNDABLE} above what is
     *         still to refund (BR-14), nothing to refund included; {@code REFUND_REFERENCE_IN_USE} for a statement line
     *         another live record names; {@code VALIDATION_FAILED} for what the record is on its own
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "refund-record",
            resourceId = "#result?.records()?.getLast()?.id()")
    public OrderRefundDetail record(RecordRefundCommand command) {
        InstallmentOrder order = orders.lockForPayment(command.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + command.orderId()));
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, InstallmentService.BUSINESS_ZONE);
        RefundRecord record = RefundRecord.record(order.orderId(), order.customerId(), order.orderNumber(),
                command.amount(), order.currency(), command.refundedOn(), command.reference(), command.note(),
                command.recordedBy(), now, today);
        RefundStanding standing = standingOf(order.orderId());
        if (record.amount().compareTo(standing.remaining()) > 0) {
            throw new BusinessException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE, ("Order %s has %s %s still to refund, "
                    + "not %s").formatted(order.orderNumber(), standing.remaining().toPlainString(), order.currency(),
                    record.amount().toPlainString()));
        }
        if (book.refundReferenceInUse(record.reference())) {
            throw referenceInUse(record.reference());
        }
        try {
            book.record(record);
        } catch (DataIntegrityViolationException raced) {
            // uk_refund_record_reference_recorded: another record took the reference since the check above. Any other
            // refusal is not that, and is not reported as it.
            if (String.valueOf(raced.getMessage()).contains("uk_refund_record_reference_recorded")) {
                throw referenceInUse(record.reference());
            }
            throw raced;
        }
        log.info("Order {}: refund {} of {} {} recorded", order.orderNumber(), record.reference(), record.amount(),
                record.currency());
        return announced(detail(order.orderId(), null), order.orderId(), command.recordedBy(), now);
    }

    /**
     * @throws BusinessException {@code REFUND_RECORD_NOT_FOUND}; {@code REFUND_RECORD_ALREADY_VOIDED};
     *         {@code VALIDATION_FAILED} without a reason
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "refund-record", resourceId = "#recordId")
    public OrderRefundDetail voidRecord(UUID recordId, String reason, UUID voidedBy) {
        RefundRecord unlocked = book.findRecord(recordId).orElseThrow(() -> notFound(recordId));
        orders.lockForPayment(unlocked.orderId());
        RefundRecord record = book.lockRecord(recordId).orElseThrow(() -> notFound(recordId));
        record.voidRecord(reason, voidedBy, clock.instant());
        book.saveVoided(record);
        log.info("Order {}: refund {} voided", record.orderNumber(), record.reference());
        return announced(detail(record.orderId(), null), record.orderId(), voidedBy, clock.instant());
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * The order's refund; {@code ownCustomerId} not null: only that customer's (anyone else's is not found, as if it did
     * not exist).
     *
     * @throws BusinessException {@code NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public OrderRefundDetail detail(UUID orderId, UUID ownCustomerId) {
        InstallmentOrder order = orders.find(orderId)
                .filter(o -> ownCustomerId == null || ownCustomerId.equals(o.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        List<OrderSettlement> settlements = book.ofOrder(orderId);
        List<RefundRecord> records = book.recordsOf(orderId);
        return new OrderRefundDetail(RefundStanding.of(orderId, settlements, records), order.orderNumber(),
                order.currency(), settlements, records);
    }

    @Transactional(readOnly = true)
    public PageResponse<RefundRecord> records(UUID customerId, UUID orderId, RefundRecord.Status status, int page,
                                              int size) {
        return Pages.toResponse(search.records(customerId, orderId, status, Pages.of(page, size)));
    }

    /** Orders with something to refund, most recently settled first (the accountant's work list). */
    @Transactional(readOnly = true)
    public PageResponse<com.stockflow.payment.internal.domain.OrderRefund> orderRefunds(UUID customerId, Boolean outstanding, int page,
                                                               int size) {
        return Pages.toResponse(search.orderRefunds(customerId, outstanding, Pages.of(page, size)));
    }

    /**
     * 4D-3 (17 §4.10 step 40): the order hears where its refund stands after commit, so a returned order refunded in
     * full moves on to REFUNDED / PARTIALLY_REFUNDED.
     */
    private OrderRefundDetail announced(OrderRefundDetail detail, UUID orderId, UUID by, Instant at) {
        events.publishEvent(new OrderRefundRecorded(orderId, detail.orderNumber(), detail.standing().refundable(),
                detail.standing().refunded(), detail.currency(), by, at));
        return detail;
    }

    private RefundStanding standingOf(UUID orderId) {
        return RefundStanding.of(orderId, book.ofOrder(orderId), book.recordsOf(orderId));
    }

    private static BusinessException notFound(UUID recordId) {
        return new BusinessException(ErrorCode.REFUND_RECORD_NOT_FOUND, "No refund record " + recordId);
    }

    private static BusinessException referenceInUse(String reference) {
        return new BusinessException(ErrorCode.REFUND_REFERENCE_IN_USE,
                "Statement line %s is already recorded as a refund".formatted(reference));
    }
}
