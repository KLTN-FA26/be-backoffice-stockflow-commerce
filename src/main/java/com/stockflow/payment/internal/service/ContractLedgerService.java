package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.ContractLedger;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.RefundStanding;
import com.stockflow.payment.internal.domain.SettlementBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@link ContractLedger} from the installment and settlement books.
 *
 * <p>The figures are made to add up the way the liquidation record prints them: owed, less the payments, plus the
 * refunds, is what is still to pay (above zero) or to pay back (below). So {@code owed} is not a sum of installment
 * amounts - a cancelled installment keeps the money collected on it until the refund - but what follows from the two
 * balances the books already keep: the payments in force, less what the settlements made refundable, plus what the
 * installments still standing have left.</p>
 */
@Service
class ContractLedgerService implements ContractLedger {

    private final InstallmentBook installments;
    private final SettlementBook settlements;

    ContractLedgerService(InstallmentBook installments, SettlementBook settlements) {
        this.installments = installments;
        this.settlements = settlements;
    }

    @Override
    @Transactional(readOnly = true)
    public Standing of(UUID orderId) {
        List<Installment> ofOrder = installments.ofOrder(orderId);
        List<Payment> payments = new ArrayList<>();
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Installment installment : ofOrder) {
            installments.receiptsOf(installment.id()).stream()
                    .filter(receipt -> receipt.status() == PaymentReceiptStatus.RECORDED)
                    .forEach(receipt -> payments.add(new Payment(receipt.receivedOn(), receipt.reference(),
                            receipt.amount())));
            if (installment.status() != InstallmentStatus.CANCELLED) {
                outstanding = outstanding.add(installment.outstanding());
            }
        }
        payments.sort(Comparator.comparing(Payment::receivedOn).thenComparing(Payment::reference));
        BigDecimal paid = payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<OrderSettlement> ofOrderSettled = settlements.ofOrder(orderId);
        RefundStanding refund = RefundStanding.of(orderId, ofOrderSettled, settlements.recordsOf(orderId));
        BigDecimal returned = ofOrderSettled.stream()
                .filter(settlement -> settlement.kind() == OrderSettlement.Kind.GOODS_RETURNED)
                .map(OrderSettlement::closed).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = paid.subtract(refund.refundable()).add(outstanding);
        return new Standing(owed, returned, List.copyOf(payments), refund.refunded(), outstanding, refund.remaining());
    }
}
