package com.stockflow.payment.internal.repository;

import com.stockflow.payment.api.PayableInstallment;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The columns and the row reading of {@link PayableInstallment}, shared by the two adapters that list payable
 * installments ({@link BankTransferReviewAdapter}, {@link InstallmentsToCollectAdapter}) so "payable", "what it still
 * owes" and "overdue" are written once, and the same way {@link Installment} says them.
 */
final class PayableInstallmentRows {

    /**
     * {@link com.stockflow.payment.internal.domain.InstallmentStatus#takesMoney}: neither PAID nor CANCELLED (OVERDUE is
     * historical, {@code ck_installment_status_facts} leaves no row in it), and something still owed.
     */
    static final String PAYABLE = "i.status IN ('NOT_DUE', 'DUE', 'PARTIALLY_PAID') "
            + "AND i.amount - i.paid_amount - i.reduced_amount > 0";

    /** {@link Installment#outstanding()} of a payable row. */
    static final String OUTSTANDING = "(i.amount - i.paid_amount - i.reduced_amount)";

    /** The select list {@link #read} reads; {@code :today} is the business day. */
    static final String COLUMNS = """
            i.id, i.customer_id, i.contract_id, i.contract_number, i.order_id, i.order_number, i.seq, i.name,
            i.milestone, i.amount, (i.amount - i.paid_amount - i.reduced_amount) AS outstanding, i.currency,
            i.due_date, i.status, payment.installment_is_overdue(i.due_date, i.status::text, :today) AS overdue""";

    private PayableInstallmentRows() {
    }

    static PayableInstallment read(ResultSet rs, LocalDate today) throws SQLException {
        LocalDate dueDate = rs.getObject("due_date", LocalDate.class);
        boolean overdue = rs.getBoolean("overdue");
        boolean feeItem = InstallmentMilestone.valueOf(rs.getString("milestone")).feeItem();
        String contractNumber = rs.getString("contract_number");
        int seq = rs.getInt("seq");
        BigDecimal outstanding = rs.getBigDecimal("outstanding");
        return new PayableInstallment(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("contract_id", UUID.class), contractNumber, rs.getObject("order_id", UUID.class),
                rs.getString("order_number"), seq, rs.getString("name"), rs.getString("milestone"), feeItem,
                whole(rs.getBigDecimal("amount")), whole(outstanding),
                rs.getString("currency"), dueDate, rs.getString("status"), overdue,
                overdue ? (int) ChronoUnit.DAYS.between(dueDate, today) : 0,
                Installment.transferReference(contractNumber, seq, feeItem));
    }

    /** Whole VND, as {@code ck_installment_amounts} keeps them: {@code 5000000}, not {@code 5000000.0000}. */
    private static BigDecimal whole(BigDecimal amount) {
        return amount.setScale(0, java.math.RoundingMode.HALF_UP);
    }
}
