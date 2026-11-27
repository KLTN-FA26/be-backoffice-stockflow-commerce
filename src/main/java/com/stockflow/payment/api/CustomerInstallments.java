package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A customer's open contract instalments, for a module that shows them without owning them - the chat's view of the
 * customer beside a conversation (kltn-docs 16 §4.1 step 3, §4.4: Sales sees what is due before sending transfer
 * details or a reminder). Read-only, one statement.
 */
public interface CustomerInstallments {

    /**
     * An instalment not yet settled.
     *
     * @param status   {@code NOT_DUE}, {@code DUE} or {@code PARTIALLY_PAID}, the payment module's names
     * @param overdue  whether its due date has passed (overdue is derived, never stored: V20261121000100)
     * @param dueDate  null while its milestone has not been reached
     * @param reducedAmount what a closure of the rest of the order took off it (15 §4.6 step 19), zero for most
     * @param outstanding   what it still owes: {@code amount - paidAmount - reducedAmount}, the installment's own
     *                      {@code outstanding()}. Given rather than left to the reader, because {@code amount -
     *                      paidAmount} overstates a reduced installment and the chat sends that figure to the customer
     */
    record OpenInstallment(UUID id, UUID contractId, String contractNumber, UUID orderId, String orderNumber, int seq,
                           String name, BigDecimal amount, BigDecimal paidAmount, String currency, LocalDate dueDate,
                           String status, boolean overdue, BigDecimal reducedAmount, BigDecimal outstanding) {
    }

    /** The customer's open instalments, the earliest due first (those with no due date last), at most {@code limit}. */
    List<OpenInstallment> openOf(UUID customerId, int limit);

    /**
     * How one instalment is paid by transfer, as the chat's transfer card shows it (kltn-docs 16 §4.4, BR-06): what it
     * still owes and what the transfer says. The account itself is {@link PaymentService#companyBankAccount}.
     *
     * @param outstanding       what is left to pay; zero once paid
     * @param status            {@code NOT_DUE}, {@code DUE}, {@code PARTIALLY_PAID}, {@code PAID} or {@code CANCELLED}
     * @param transferReference what the customer writes in the transfer: the contract's number and the instalment's
     *                          ({@code HD-... DOT1}), how accounting matches the money to it
     */
    record Transfer(UUID installmentId, UUID customerId, UUID contractId, String contractNumber, UUID orderId,
                    String orderNumber, int seq, String name, BigDecimal amount, BigDecimal outstanding,
                    String currency, LocalDate dueDate, String status, boolean overdue, String transferReference) {
    }

    /** One instalment's transfer details, whatever its status; empty when there is no such instalment. */
    java.util.Optional<Transfer> transfer(UUID installmentId);

    /**
     * When an instalment of a signed contract's plan falls due and how late it is (R5-12), for the contract's own view.
     *
     * @param dueDate     null while its milestone has not been reached
     * @param daysOverdue days past {@code dueDate} it is still owing, today in the business calendar; 0 when not overdue
     */
    record PlanDue(int seq, LocalDate dueDate, int daysOverdue) {
    }

    /**
     * {@link PlanDue} of each instalment made from {@code contractId}'s plan, by its {@code seq} - the plan row's; fee
     * items (a cancellation's, a failed trip's) name no plan row and are absent, as is everything of a contract not
     * signed. One statement; overdue as {@code payment.installment_is_overdue} reads it.
     */
    Map<Integer, PlanDue> planDues(UUID contractId);
}
