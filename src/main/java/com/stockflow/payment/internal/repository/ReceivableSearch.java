package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.Collector;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

/** Paged lists of receivables and transfers for the accountant, Sales and the customer. */
public interface ReceivableSearch {

    /** Every filter null means "any"; {@code dueBefore} keeps receivables due before that day. */
    Page<Receivable> receivables(UUID customerId, UUID orderId, ReceivableStatus status, LocalDate dueBefore,
                                 Collector collector, String kind, Pageable pageable);

    /** {@code withCreditOnly}: only transfers with money not yet allocated (the customer's credit, G2-3). */
    Page<CustomerTransfer> transfers(UUID customerId, boolean withCreditOnly, Pageable pageable);
}
