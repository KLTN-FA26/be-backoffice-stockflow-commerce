package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.OrderSettlementResult;
import com.stockflow.payment.api.SettleCancellationCommand;
import com.stockflow.payment.api.SettleRemainderClosureCommand;
import com.stockflow.payment.api.SettleReturnCommand;
import com.stockflow.payment.api.RefundProgress;
import com.stockflow.payment.internal.domain.RefundStanding;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.SettlementBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Settles a contract order's money when it is cancelled or its rest closed (4B-9; kltn-docs 15 §4.6 steps 18-19,
 * BR-14..BR-16; 17 §4.8 steps 30 and 33), and when goods that came back on a return are refunded (4D-3; 17 §4.10
 * step 40). The arithmetic is {@link OrderSettlement}'s; this class locks, saves and
 * replays.
 *
 * <p><b>Synchronous, in the order's transaction</b> ({@code MANDATORY}). The order module calls it right after it
 * cancelled or closed the order and still holds the order's row lock, so the lock order every money movement keeps -
 * orders, then installments ({@link PaymentReceiptService}) - holds here too, and no receipt can pay an installment
 * between the cancellation and its settlement. Settled after commit instead, a receipt recorded in that gap would have
 * been taken on an installment the cancellation had already ruled out, and the refund worked out without it.</p>
 *
 * <p>Idempotent per cancellation (the order), per closure (its id) and per return (its id): a replay returns the
 * settlement already made.</p>
 */
@Service("paymentOrderSettlementService")
@Transactional(propagation = Propagation.MANDATORY)
public class OrderSettlementService {

    private static final Logger log = LoggerFactory.getLogger(OrderSettlementService.class);

    private final InstallmentBook installments;
    private final SettlementBook settlements;

    OrderSettlementService(InstallmentBook installments, SettlementBook settlements) {
        this.installments = installments;
        this.settlements = settlements;
    }

    /** {@link com.stockflow.payment.api.PaymentService#settleCancellation}. */
    public Optional<OrderSettlementResult> settleCancellation(SettleCancellationCommand command) {
        Optional<OrderSettlement> done = settlements.find(OrderSettlement.Kind.ORDER_CANCELLED, command.orderId());
        if (done.isPresent()) {
            return done.map(OrderSettlementService::toResult);
        }
        List<Installment> ofOrder = installments.lockOfOrder(command.orderId());
        if (ofOrder.isEmpty()) {
            return Optional.empty();
        }
        OrderSettlement.Fee fee = new OrderSettlement.Fee(command.feePercent(), command.feeAmount(),
                command.feeReason(), command.feeDetail());
        OrderSettlement.Outcome outcome = OrderSettlement.cancel(ofOrder, fee, command.cancelledBy(),
                command.cancelledAt(), day(command.cancelledAt()));
        OrderSettlement settlement = save(outcome);
        log.info("Order {} cancelled: {} installment(s) cancelled; {} {} collected, fee {} ({} of it owed), {} to refund",
                settlement.orderNumber(), outcome.changed().size(), settlement.collected(), settlement.currency(),
                settlement.fee(), settlement.feeOwed(), settlement.refundable());
        return Optional.of(toResult(settlement));
    }

    /** {@link com.stockflow.payment.api.PaymentService#settleRemainderClosure}. */
    public Optional<OrderSettlementResult> settleRemainderClosure(SettleRemainderClosureCommand command) {
        Optional<OrderSettlement> done = settlements.find(OrderSettlement.Kind.REMAINDER_CLOSED, command.closureId());
        if (done.isPresent()) {
            return done.map(OrderSettlementService::toResult);
        }
        List<Installment> ofOrder = installments.lockOfOrder(command.orderId());
        if (ofOrder.isEmpty()) {
            return Optional.empty();
        }
        OrderSettlement.Fee fee = command.feeAmount() == null ? OrderSettlement.Fee.none()
                : new OrderSettlement.Fee(null, command.feeAmount(), command.feeReason(), command.feeDetail());
        OrderSettlement.Outcome outcome = OrderSettlement.closeRemainder(ofOrder, command.closureId(),
                command.closedAmount(), fee, command.closedBy(), command.closedAt(), day(command.closedAt()));
        OrderSettlement settlement = save(outcome);
        log.info("Order {}: rest closed, {} {} worth; {} taken off {} installment(s), fee {} ({} of it owed), {} to "
                        + "refund", settlement.orderNumber(), settlement.closed(), settlement.currency(),
                settlement.reduced(), settlement.reductions().size(), settlement.fee(), settlement.feeOwed(),
                settlement.refundable());
        return Optional.of(toResult(settlement));
    }

    /** {@link com.stockflow.payment.api.PaymentService#settleReturn}. */
    public Optional<OrderSettlementResult> settleReturn(SettleReturnCommand command) {
        Optional<OrderSettlement> done = settlements.find(OrderSettlement.Kind.GOODS_RETURNED,
                command.returnRequestId());
        if (done.isPresent()) {
            return done.map(OrderSettlementService::toResult);
        }
        List<Installment> ofOrder = installments.lockOfOrder(command.orderId());
        if (ofOrder.isEmpty()) {
            return Optional.empty();
        }
        OrderSettlement.Outcome outcome = OrderSettlement.returned(ofOrder, command.returnRequestId(),
                command.returnedAmount(), command.settledBy(), command.settledAt(), day(command.settledAt()));
        OrderSettlement settlement = save(outcome);
        log.info("Order {}: goods returned worth {} {}; {} taken off {} installment(s), {} to refund",
                settlement.orderNumber(), settlement.closed(), settlement.currency(), settlement.reduced(),
                settlement.reductions().size(), settlement.refundable());
        return Optional.of(toResult(settlement));
    }

    /** {@link com.stockflow.payment.api.PaymentService#refundProgress}, read in the caller's transaction. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public RefundProgress refundProgress(java.util.UUID orderId) {
        RefundStanding standing = RefundStanding.of(orderId, settlements.ofOrder(orderId),
                settlements.recordsOf(orderId));
        return new RefundProgress(standing.refundable(), standing.refunded());
    }

    /** The installments changed, the fee item (before the settlement that names it), then the settlement. */
    private OrderSettlement save(OrderSettlement.Outcome outcome) {
        outcome.changed().forEach(installments::save);
        if (outcome.feeItem() != null) {
            installments.save(outcome.feeItem());
        }
        settlements.save(outcome.settlement());
        return outcome.settlement();
    }

    private static LocalDate day(java.time.Instant at) {
        return LocalDate.ofInstant(at, InstallmentService.BUSINESS_ZONE);
    }

    static OrderSettlementResult toResult(OrderSettlement s) {
        return new OrderSettlementResult(s.id(), s.kind().name(), s.collected(), s.reduced(), s.surplus(), s.fee(),
                s.feeCovered(), s.refundable(), s.feeOwed(), s.feeItemId(), s.currency());
    }
}
