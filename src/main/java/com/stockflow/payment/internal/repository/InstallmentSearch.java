package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
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
     */
    record InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status, InstallmentMilestone milestone,
                             LocalDate dueBefore, Boolean reached, Boolean overdue, LocalDate overdueOn,
                             LocalDate dueFrom) {

        public InstallmentFilter {
            overdueOn = overdue == null ? null : java.util.Objects.requireNonNull(overdueOn, "overdueOn");
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
     * whitespace aside ({@code PaymentReceipt.normalize}).
     */
    Page<PaymentReceipt> receipts(UUID customerId, PaymentReceiptStatus status, String reference, Pageable pageable);
}
