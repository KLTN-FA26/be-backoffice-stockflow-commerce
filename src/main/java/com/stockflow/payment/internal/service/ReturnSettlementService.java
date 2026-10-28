package com.stockflow.payment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.api.ReturnSettlement;
import com.stockflow.payment.api.SettleReturnCommand;
import com.stockflow.payment.api.SettleReturnResult;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.ReturnRefunds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The money of a resolved return (SCRUM-260; V20261028020100 explains the two ways it is booked). Called by the order
 * module inside the transaction that resolves the return, after the second person approved it.
 *
 * <ol>
 *   <li>The return's value first reduces what the order still owes on its receivables - the customer's own: a credit
 *       order's, a deposit balance on credit, not a carrier's (the customer paid the carrier) and not a fee. That is a
 *       RETURN_CREDIT entry of the ledger, allocated to those receivables earliest due first, each allocation announced
 *       as {@code PaymentCaptured} (method CREDIT_NOTE) so the order counts it as settled. An overdue receivable stays
 *       OVERDUE until it is settled in full, as with any payment.</li>
 *   <li>What the receivables do not take is either refunded - PENDING refunds of source RETURN on the money the order
 *       received, never more than it received (refused whole otherwise: {@code RETURN_REFUND_EXCEEDS_PAID}) - or kept
 *       as the customer's credit on the same RETURN_CREDIT entry.</li>
 * </ol>
 *
 * <p>Locks, as every money path takes them: the order ({@link OrderPayables#lockForPayment}), then its receivables,
 * then the entry. Nothing is paid out here: refunds wait for the accountant (SCRUM-220).</p>
 */
@Service
@Transactional
class ReturnSettlementService implements ReturnSettlement {

    private static final Logger log = LoggerFactory.getLogger(ReturnSettlementService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** The receivables a return's value may reduce: goods the customer owes for themselves. */
    private static final Set<String> GOODS_RECEIVABLES = Set.of("CREDIT_ORDER", "DEPOSIT_BALANCE");

    private final ReceivableBook book;
    private final ReturnRefunds refunds;
    private final OrderPayables orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReturnSettlementService(ReceivableBook book, ReturnRefunds refunds, OrderPayables orders,
                            ApplicationEventPublisher events, Clock clock) {
        this.book = book;
        this.refunds = refunds;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "return-settlement", resourceId = "#command.returnRequestId()")
    public SettleReturnResult settleReturn(SettleReturnCommand command) {
        PayableOrder order = orders.lockForPayment(command.orderId()).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "No order " + command.orderId()));
        if (!order.customerId().equals(command.customerId()) || !order.currency().equals(command.currency())) {
            throw new IllegalArgumentException("Return %s does not match order %s".formatted(command.rmaNumber(),
                    order.orderNumber()));
        }
        // Booked once per reference: the resolution, then perhaps the withdrawal of its replacement (credit only).
        if (book.referenceRecorded(command.reference())
                || command.refundRest() && refunds.hasRefunds(command.returnRequestId())) {
            throw new IllegalStateException("Return %s was already settled (%s)".formatted(command.rmaNumber(),
                    command.reference()));
        }
        Instant now = clock.instant();
        List<Receivable> owed = book.lockUnpaid(command.customerId()).stream()
                .filter(r -> r.orderId().equals(command.orderId()) && GOODS_RECEIVABLES.contains(r.kind()))
                .toList();
        BigDecimal outstanding = owed.stream().map(Receivable::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal offset = command.amount().min(outstanding);
        BigDecimal rest = command.amount().subtract(offset);
        BigDecimal kept = command.refundRest() ? BigDecimal.ZERO : rest;
        BigDecimal refunded = BigDecimal.ZERO;

        CustomerTransfer credit = null;
        if (offset.add(kept).signum() > 0) {
            credit = book.save(CustomerTransfer.returnCredit(command.customerId(), command.reference(),
                    offset.add(kept), command.currency(), LocalDate.ofInstant(now, BUSINESS_ZONE),
                    command.settledBy(), now, "Return %s of order %s".formatted(command.rmaNumber(),
                            command.orderNumber())));
            BigDecimal toOffset = offset;
            for (Receivable receivable : owed) {
                if (toOffset.signum() <= 0) {
                    break;
                }
                BigDecimal applied = receivable.allocate(credit.take(toOffset.min(receivable.outstanding())), now);
                toOffset = toOffset.subtract(applied);
                book.save(receivable);
                ReceivableBook.Allocation allocation = book.recordAllocation(credit.id(), command.orderId(),
                        receivable.id(), applied, now, command.settledBy());
                events.publishEvent(new PaymentCaptured(command.orderId(), allocation.id(), applied,
                        command.currency(), credit.channel().paymentMethod(), ReceivableService.sourceOf(receivable)));
            }
            credit = book.save(credit);
        }
        if (command.refundRest() && rest.signum() > 0) {
            List<ReturnRefunds.Asked> asked = refunds.requestReturnRefunds(command.orderId(),
                    command.returnRequestId(), rest, command.currency(),
                    "Return %s of order %s".formatted(command.rmaNumber(), command.orderNumber()));
            refunded = asked.stream().map(ReturnRefunds.Asked::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (refunded.compareTo(rest) < 0) {
                // Never a partial refund: the whole resolution rolls back, and Sales credits the rest instead.
                throw new BusinessException(ErrorCode.RETURN_REFUND_EXCEEDS_PAID, ("Return %s would refund %s %s but "
                        + "order %s has only %s %s of received money left to refund").formatted(command.rmaNumber(),
                        plain(rest), command.currency(), order.orderNumber(), plain(refunded), command.currency()),
                        List.of(), Map.of("refundable", refunded, "requested", rest));
            }
        }
        log.info("Return {} of order {} settled: {} set off, {} refunded, {} kept as credit ({})", command.rmaNumber(),
                order.orderNumber(), plain(offset), plain(refunded), plain(kept), command.currency());
        return new SettleReturnResult(command.returnRequestId(), offset, refunded, kept,
                credit == null ? null : credit.id());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
