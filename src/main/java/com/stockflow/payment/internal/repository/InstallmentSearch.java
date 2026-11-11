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
     * those whose milestone came (what is due), false those still waiting for it.
     */
    record InstallmentFilter(UUID customerId, UUID orderId, InstallmentStatus status, InstallmentMilestone milestone,
                             LocalDate dueBefore, Boolean reached) {
    }

    Page<Installment> installments(InstallmentFilter filter, Pageable pageable);

    /** Every filter null means "any"; {@code reference} matches the statement reference exactly. */
    Page<PaymentReceipt> receipts(UUID customerId, PaymentReceiptStatus status, String reference, Pageable pageable);
}
