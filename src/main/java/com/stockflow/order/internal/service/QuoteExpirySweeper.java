package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SENT → EXPIRED when the validity ends (03-state-machines §18, actor "system").
 *
 * <p>The sweep only writes down what is already true: every read reports a sent quote past its validity
 * as EXPIRED, and acceptance refuses it, from the first moment — the sweep is not what enforces BR-ORD-008.
 * It makes the stored status agree, so lists filtered by status and reports are right. Bounded batches on
 * {@code ix_quote_sent_validity}; one instance at a time.</p>
 */
@Component
class QuoteExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(QuoteExpirySweeper.class);
    static final int BATCH_SIZE = 100;

    private final Quotes quotes;

    QuoteExpirySweeper(Quotes quotes) {
        this.quotes = quotes;
    }

    @Scheduled(fixedDelayString = "${stockflow.order.quote-expiry-interval:PT5M}")
    @SchedulerLock(name = "order.expireQuotes", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void expireQuotes() {
        int expired = quotes.expireDue(BATCH_SIZE);
        if (expired > 0) {
            log.info("Quote sweep expired {} quote(s)", expired);
        }
    }
}
