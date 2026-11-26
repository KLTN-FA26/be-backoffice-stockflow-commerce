package com.stockflow.payment.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, reads SePay's transaction list for the incoming transfers its webhook did not deliver - a call SePay gave
 * up on after its retries, or one made while this server was down - and handles again the transfers a failure left
 * RECEIVED ({@link BankTransactionService#catchUp}). SePay's own advice for a webhook integration, and Casso's: the
 * webhook is fast, the list is complete. One instance at a time ({@code @SchedulerLock}); a run twice keeps nothing
 * twice, every transfer being kept once ({@code uk_bank_transaction_dedupe}). A separate bean so the call goes through
 * the service's proxy; public because a non-public {@code @Scheduled} method is not advised by the scheduler lock.
 */
@Component
class BankFeedCatchUpJob {

    static final String LOCK_AT_MOST = "PT30M";

    private final BankTransactionService transactions;

    BankFeedCatchUpJob(BankTransactionService transactions) {
        this.transactions = transactions;
    }

    @Scheduled(cron = "${stockflow.payment.bank-feed.catch-up-cron:0 40 1 * * *}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "payment.bank-feed-catch-up", lockAtMostFor = LOCK_AT_MOST, lockAtLeastFor = "PT10S")
    public void run() {
        transactions.catchUp();
    }
}
