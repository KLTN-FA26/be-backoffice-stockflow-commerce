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
 * ({@link OrderRefundRecorded}; a void names the record voided). A cancelled order stays CANCELLED (17 §5.1 -
 * CANCELLED is final); a RETURNED order the standing shows refunded in full moves on to REFUNDED / PARTIALLY_REFUNDED
 * in the order module (4D-3), and a void takes a REFUNDED / PARTIALLY_REFUNDED one back to RETURNED there, waiting for
 * its refund again (TT-4) - the order is not closed while a refund is owed. A CLOSED order is not reopened: what a void
 * leaves owed on it shows here, on {@link #orderRefunds} with {@code outstanding}, oldest first.</p>
 */
@Service
@Transactional
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /**
     * An order's refund: where it stands, the settlements that made it, and the records against it; and what the
     * order's fee items still owe ({@code feeOwed}, WF-05 BE-51: shown beside the refund, never set off by itself).
     */
    public record OrderRefundDetail(RefundStanding standing, String orderNumber, String currency,
                                    List<OrderSettlement> settlements, List<RefundRecord> records,
                                    java.math.BigDecimal feeOwed) {
    }

    /** What the accountant records. */
    public record RecordRefundCommand(UUID orderId, BigDecimal amount, LocalDate refundedOn, String reference,
                                      String note, UUID recordedBy) {
    }

    private final SettlementBook book;
    private final com.stockflow.payment.internal.domain.InstallmentBook installments;
    private final RefundSearch search;
    private final InstallmentOrders orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final com.stockflow.customer.api.CustomerLegalProfiles legalProfiles;

    RefundService(SettlementBook book, com.stockflow.payment.internal.domain.InstallmentBook installments,
                  RefundSearch search, InstallmentOrders orders, ApplicationEventPublisher events, Clock clock,
                  com.stockflow.customer.api.CustomerLegalProfiles legalProfiles) {
        this.legalProfiles = legalProfiles;
        this.book = book;
        this.installments = installments;
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
                .orElseThrow(() -> orderNotFound(command.orderId()));
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, InstallmentService.BUSINESS_ZONE);
        // Audit r3 RAD-6: the account the money went to, as the customer's profile names it now - kept with the
        // record, so its confirmation never prints an account the customer changed to later.
        RefundRecord.PayeeAccount payee = legalProfiles.legalProfile(order.customerId())
                .map(com.stockflow.customer.api.CustomerLegalProfile::bankAccount)
                .map(bank -> RefundRecord.PayeeAccount.of(bank.accountNumber(), bank.bankName(), bank.accountHolder()))
                .orElse(null);
        RefundRecord record = RefundRecord.record(order.orderId(), order.customerId(), order.orderNumber(),
                command.amount(), order.currency(), command.refundedOn(), command.reference(), command.note(),
                command.recordedBy(), now, today, payee);
        RefundStanding standing = standingOf(order.orderId());
        if (record.amount().compareTo(standing.remaining()) > 0) {
            throw new BusinessException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE, ("Order %s has %s %s still to refund, "
                    + "not %s").formatted(order.orderNumber(), standing.remaining().toPlainString(), order.currency(),
                    record.amount().toPlainString())).localized("refusal.refund.exceedsRefundable",
                    order.orderNumber(), standing.remaining(), order.currency(), record.amount());
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
        return announced(detail(order.orderId(), null), order.orderId(), command.recordedBy(), now, null);
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
        return announced(detail(record.orderId(), null), record.orderId(), voidedBy, clock.instant(), record);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * D50: what the company bears of the fees kept with the order's cancellation and closures - "Phí do công ty chịu",
     * the accountant's view of the order's money. Zero when the customer bore every fee or none was kept.
     */
    @Transactional(readOnly = true)
    public java.math.BigDecimal feeBorneByCompany(UUID orderId) {
        return book.ofOrder(orderId).stream().map(OrderSettlement::companyFee)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

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
                .orElseThrow(() -> orderNotFound(orderId));
        List<OrderSettlement> settlements = book.ofOrder(orderId);
        List<RefundRecord> records = book.recordsOf(orderId);
        // WF-05 BE-51: the fee items still owed on this order - a cancellation fee a closure left - beside the refund.
        java.math.BigDecimal feeOwed = installments.ofOrder(orderId).stream()
                .filter(com.stockflow.payment.internal.domain.Installment::isFeeItem)
                .map(com.stockflow.payment.internal.domain.Installment::outstanding)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return new OrderRefundDetail(RefundStanding.of(orderId, settlements, records), order.orderNumber(),
                order.currency(), settlements, records, feeOwed);
    }

    @Transactional(readOnly = true)
    public PageResponse<RefundRecord> records(UUID customerId, UUID orderId, RefundRecord.Status status, int page,
                                              int size) {
        return Pages.toResponse(search.records(customerId, orderId, status, Pages.of(page, size)));
    }

    /**
     * Orders with something to refund (the accountant's work list), the oldest debt first: ordered by the first of the
     * order's settlements that made something refundable, so a refund owed longest - one never made, or made and voided
     * since, the order perhaps CLOSED by then (TT-4) - is on top rather than behind every newer one.
     */
    @Transactional(readOnly = true)
    public PageResponse<com.stockflow.payment.internal.domain.OrderRefund> orderRefunds(UUID customerId, Boolean outstanding, int page,
                                                               int size) {
        return Pages.toResponse(search.orderRefunds(customerId, outstanding, Pages.of(page, size)));
    }

    /**
     * 4D-3 (17 §4.10 step 40): the order hears where its refund stands after commit, so a returned order refunded in
     * full moves on to REFUNDED / PARTIALLY_REFUNDED; and - TT-4 - which record a void voided, so a refunded order goes
     * back to RETURNED (the reason stays here, read through {@code PaymentService.refundVoidReason}).
     *
     * @param voided the record a void voided; null for a record made
     */
    private OrderRefundDetail announced(OrderRefundDetail detail, UUID orderId, UUID by, Instant at,
                                        RefundRecord voided) {
        events.publishEvent(new OrderRefundRecorded(orderId, detail.orderNumber(), detail.standing().refundable(),
                detail.standing().refunded(), detail.currency(), by, at, voided == null ? null : voided.id()));
        return detail;
    }

    private RefundStanding standingOf(UUID orderId) {
        return RefundStanding.of(orderId, book.ofOrder(orderId), book.recordsOf(orderId));
    }

    private static BusinessException notFound(UUID recordId) {
        return new BusinessException(ErrorCode.REFUND_RECORD_NOT_FOUND, "No refund record " + recordId)
                .localized("refusal.refund.notFound");
    }

    private static BusinessException orderNotFound(UUID orderId) {
        return new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId)
                .localized("refusal.payment.orderNotFound");
    }

    private static BusinessException referenceInUse(String reference) {
        return new BusinessException(ErrorCode.REFUND_REFERENCE_IN_USE,
                "Statement line %s is already recorded as a refund".formatted(reference))
                .localized("refusal.refund.referenceInUse", reference);
    }
}
