package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.contracts.ContractSignDeadlinePassed;
import com.stockflow.order.internal.domain.SalesContractRevisions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The night's check of the contracts waiting for their customer's signature: each one past its signing deadline is
 * announced once per revision ({@link ContractSignDeadlinePassed}, which tells the salesperson who looks after the
 * customer - {@link ContractNotices}). Nothing is cancelled or changed on the contract: whether to extend, chase or cancel
 * is Sales' call. "Once" is the revision row's mark, written in the same transaction as the event.
 */
@Service
@Transactional
public class ContractSignDeadlineService {

    private static final Logger log = LoggerFactory.getLogger(ContractSignDeadlineService.class);

    /** More than a sales team lets pass in a night; the rest are announced by the next batch of the run. */
    static final int BATCH_SIZE = 200;

    private final SalesContractRevisions revisions;
    private final OrderEventPublisher events;
    private final Clock clock;

    ContractSignDeadlineService(SalesContractRevisions revisions, OrderEventPublisher events, Clock clock) {
        this.revisions = revisions;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Announces one batch of the contracts past their deadline that were not announced yet.
     *
     * @return how many were announced; the caller asks again while a full batch came back
     */
    public int announceOverdue() {
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.date(now);
        List<SalesContractRevisions.Overdue> overdue = revisions.claimOverdue(today, now, BATCH_SIZE);
        for (SalesContractRevisions.Overdue contract : overdue) {
            events.publish(new ContractSignDeadlinePassed(contract.orderId(), contract.contractId(),
                    contract.revision(), contract.signBy(), contract.contractNumber(), contract.orderNumber(),
                    contract.customerId(), now));
        }
        if (!overdue.isEmpty()) {
            log.info("{} contract(s) past their signing deadline announced", overdue.size());
        }
        return overdue.size();
    }
}
