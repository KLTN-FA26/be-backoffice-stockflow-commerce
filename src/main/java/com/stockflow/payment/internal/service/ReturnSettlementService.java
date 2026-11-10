package com.stockflow.payment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.InstallmentOrder;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.api.ReturnSettlement;
import com.stockflow.payment.api.SettleReturnCommand;
import com.stockflow.payment.api.SettleReturnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The money of a resolved return, since the money ledger went (4B-10). Called by the order module inside the
 * transaction that resolves the return, after the second person approved it.
 *
 * <p>Until 4B-10 a return's value reduced the order's receivables in the ledger, and the rest was refunded from the
 * money the ledger had received for the order, or kept there as the customer's credit. The ledger is gone, and a
 * contract order's money is on its installments, which the ledger never held - so for every order placed through a
 * contract a refund was refused already ({@code RETURN_REFUND_EXCEEDS_PAID}, nothing received). That stays so here:</p>
 * <ul>
 *   <li><b>Refund</b>: nothing can be refunded through a return yet; a positive amount is refused whole. The amount to
 *       refund of a return worked out from the installments (kltn-docs 15 §4.5 step 15, the 4B-9 settlements) is
 *       4D-3's.</li>
 *   <li><b>Credit</b>: the system keeps no credit (15 BR-05, BR-06: money not tied to an installment is the accounting
 *       software's). The value is reported as credited and nothing is booked here; the accountant settles it outside,
 *       as the return's record says.</li>
 * </ul>
 *
 * <p>Locks the order first, as every money path does, so a resolution and a receipt of one order are decided one after
 * the other.</p>
 */
@Service
@Transactional
class ReturnSettlementService implements ReturnSettlement {

    private static final Logger log = LoggerFactory.getLogger(ReturnSettlementService.class);

    private final InstallmentOrders orders;

    ReturnSettlementService(InstallmentOrders orders) {
        this.orders = orders;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "return-settlement", resourceId = "#command.returnRequestId()")
    public SettleReturnResult settleReturn(SettleReturnCommand command) {
        InstallmentOrder order = orders.lockForPayment(command.orderId()).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "No order " + command.orderId()));
        if (!order.customerId().equals(command.customerId()) || !order.currency().equals(command.currency())) {
            throw new IllegalArgumentException("Return %s does not match order %s".formatted(command.rmaNumber(),
                    order.orderNumber()));
        }
        if (command.refundRest()) {
            throw new BusinessException(ErrorCode.RETURN_REFUND_EXCEEDS_PAID, ("Return %s would refund %s %s but no "
                    + "money of order %s can be refunded through a return").formatted(command.rmaNumber(),
                    plain(command.amount()), command.currency(), order.orderNumber()), List.of(),
                    Map.of("refundable", BigDecimal.ZERO, "requested", command.amount()));
        }
        log.info("Return {} of order {}: {} {} credited, to be settled outside the system", command.rmaNumber(),
                order.orderNumber(), plain(command.amount()), command.currency());
        return new SettleReturnResult(command.returnRequestId(), BigDecimal.ZERO, BigDecimal.ZERO, command.amount(),
                null);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal refundCapacity(UUID orderId, UUID customerId, String currency) {
        return BigDecimal.ZERO;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
