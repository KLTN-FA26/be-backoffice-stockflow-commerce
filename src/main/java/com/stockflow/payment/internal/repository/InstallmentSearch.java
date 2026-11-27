package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Paged lists of installments and receipts for the accountant, Sales and the customer (4B-6). */
public interface InstallmentSearch {

    /**
     * Every filter null means "any". {@code dueBefore} keeps installments due before that day; {@code reached} true keeps
     * those whose milestone came (what is due), false those still waiting for it. {@code overdue} with the day it is
     * asked on ({@code overdueOn}) keeps the installments overdue that day - past their due day and still owing - or,
     * false, those that are not: what the aged-receivables reading filters by, since overdue is no status
     * (WF-05 fix plan, section 8). {@code dueFrom} keeps installments due on or after that day (the overdue scan reads
     * the ones that turned overdue lately).
     *
     * <p>{@code customerIds} keeps the installments of those customers: null is any customer, empty is none - what a
     * search for customers that matched nobody must answer, never every row. {@code owing} true keeps what is due and
     * still owed (the receivables screen, FE BE-5): the milestone came ({@code reached_at} set) and it is DUE or
     * PARTIALLY_PAID - fee items included, which are reached the moment they are opened; overdue ones included, since
     * overdue is a flag on those two statuses, not a third. NOT_DUE never has a milestone that came
     * ({@code ck_installment_status_facts}), and one paid early before its milestone is not due yet. False or null is
     * no filter.</p>
     *
     * <p>{@code unannounced} true keeps the installments whose overdue was not announced for their current due date
     * (WF-05 BE-49: {@code overdue_announced_for} is not {@code due_date}) - the overdue scan's reading.</p>
     */
    record InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status, InstallmentMilestone milestone,
                             LocalDate dueBefore, Boolean reached, Boolean overdue, LocalDate overdueOn,
                             LocalDate dueFrom, java.util.Collection<UUID> customerIds, Boolean owing,
                             Boolean unannounced, String searchTerm) {

        public InstallmentFilter {
            overdueOn = overdue == null ? null : java.util.Objects.requireNonNull(overdueOn, "overdueOn");
            customerIds = customerIds == null ? null : java.util.List.copyOf(customerIds);
        }

        public InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status,
                                 InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                 Boolean overdue, LocalDate overdueOn, LocalDate dueFrom,
                                 java.util.Collection<UUID> customerIds, Boolean owing, Boolean unannounced) {
            this(customerId, orderId, status, milestone, dueBefore, reached, overdue, overdueOn, dueFrom,
                    customerIds, owing, unannounced, null);
        }

        /** Without the overdue scan's reading. */
        public InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status,
                                 InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                 Boolean overdue, LocalDate overdueOn, LocalDate dueFrom,
                                 java.util.Collection<UUID> customerIds, Boolean owing) {
            this(customerId, orderId, status, milestone, dueBefore, reached, overdue, overdueOn, dueFrom, customerIds,
                    owing, null);
        }

        /** Without a customer search or the owing reading. */
        public InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status,
                                 InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                 Boolean overdue, LocalDate overdueOn, LocalDate dueFrom) {
            this(customerId, orderId, status, milestone, dueBefore, reached, overdue, overdueOn, dueFrom, null, null,
                    null);
        }

        /** Without a lower bound on the due day. */
        public InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status,
                                 InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                 Boolean overdue, LocalDate overdueOn) {
            this(customerId, orderId, status, milestone, dueBefore, reached, overdue, overdueOn, null);
        }
    }

    Page<Installment> installments(InstallmentFilter filter, Pageable pageable);

    /**
     * Every filter null means "any"; {@code reference} matches the statement reference as BR-03 compares it - case and
     * whitespace aside ({@code PaymentReceipt.normalize}); the days bound {@code received_on}, both inclusive.
     */
    Page<PaymentReceipt> receipts(ReceiptFilter filter, Pageable pageable);

    /**
     * What the RECORDED receipts among those {@code filter} matches add up to, one total per currency (none when
     * nothing matched): the figure above the accountant's list (2026-11-14 plan, BE2-A). A voided receipt counts no
     * money (15 §5.3), so it is left out even when the filter asks for VOIDED ones.
     */
    List<ReceiptTotal> recordedTotals(ReceiptFilter filter);

    /** How many RECORDED receipts in one currency, and their sum. */
    record ReceiptTotal(String currency, long count, BigDecimal amount) {
    }
}
