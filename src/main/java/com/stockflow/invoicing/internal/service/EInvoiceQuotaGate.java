package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Nothing is sent while the provider's plan has no invoices left (WF-05 BE-54; decision Q-22, row BR-09 of kltn-docs
 * 21). SePay refuses every invoice then ({@code QUOTA_HAS_BEEN_USERD_UP}, nothing created, not to be retried on its own)
 * and each refused draft had to be reopened and issued again by hand - with the automatic issuing on (Q-1), every
 * delivery of the day. Held back instead, a draft stays a draft: the accountant's issue is refused with
 * {@code EINVOICE_QUOTA_EXHAUSTED}, the automatic issuing skips its batch and the accountants are told
 * ({@link EInvoiceProviderAccountAlert.Problem#QUOTA_EXHAUSTED}, once a day by the inbox), and once the plan is topped
 * up the drafts go out by themselves.
 *
 * <p>The quota is asked of the provider ({@code GET /v1/usage}) and kept {@link #FRESH} - the automatic issuing runs
 * every 30 seconds and must not ask each time. Asked outside any transaction: no row lock waits on the provider. A
 * provider that does not tell its quota, or does not answer, holds nothing back: the provider's own refusal is the
 * second line.</p>
 */
@Component
class EInvoiceQuotaGate {

    /**
     * The code the accountants read when issuing is held back for a used-up plan (the inbox prints it where a refused
     * invoice's code would stand): nothing was refused, the provider's usage said none is left.
     */
    static final String HELD_BACK_CODE = "QUOTA_REMAINING_0";

    /** How long a quota read from the provider is trusted. */
    static final Duration FRESH = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(EInvoiceQuotaGate.class);

    private record Known(long remaining, Instant at) {
    }

    private final EInvoiceProviders providers;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ConcurrentMap<String, Known> known = new ConcurrentHashMap<>();

    EInvoiceQuotaGate(EInvoiceProviders providers, ApplicationEventPublisher events, Clock clock,
                      PlatformTransactionManager transactions) {
        this.providers = providers;
        this.events = events;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Whether the provider's plan is known to have no invoice left. */
    boolean exhausted(String provider) {
        Instant now = clock.instant();
        Known kept = known.get(provider);
        if (kept != null && now.isBefore(kept.at().plus(FRESH))) {
            return kept.remaining() <= 0;
        }
        Optional<EInvoiceProvider> port = providers.find(provider);
        if (port.isEmpty()) {
            return false;
        }
        OptionalLong remaining;
        try {
            remaining = port.get().remainingQuota();
        } catch (ProviderUnavailableException unavailable) {
            log.warn("E-invoice provider {} did not tell its quota; nothing is held back for it", provider);
            return false;
        } catch (RuntimeException failure) {
            log.warn("E-invoice provider {}'s quota could not be read: {}", provider, failure.getClass().getSimpleName());
            return false;
        }
        if (remaining.isEmpty()) {
            known.remove(provider);
            return false;
        }
        known.put(provider, new Known(remaining.getAsLong(), now));
        return remaining.getAsLong() <= 0;
    }

    /**
     * One invoice was handed to the provider: a quota read a moment ago counts one less, so a batch stops at the last
     * invoice the plan holds instead of trusting a stale count for {@link #FRESH} and sending the rest to be refused
     * for good (each then REJECTED, left for reopening by hand - the case BE-54 removes).
     */
    void sent(String provider) {
        known.computeIfPresent(provider, (name, kept) -> new Known(kept.remaining() - 1, kept.at()));
    }

    /**
     * The provider refused an invoice because the plan is used up ({@code QUOTA_HAS_BEEN_USERD_UP}), or this gate said
     * so: whatever was read before, nothing more is sent until the quota is read again after {@link #FRESH}. A plain
     * event listener on purpose: it takes effect at once, even if the refusal's own transaction is still open - holding
     * back one round too many costs 30 seconds, sending one too many costs a refused invoice.
     */
    @EventListener
    public void on(EInvoiceProviderAccountAlert alert) {
        if (alert.problem() == EInvoiceProviderAccountAlert.Problem.QUOTA_EXHAUSTED) {
            known.put(alert.provider(), new Known(0, clock.instant()));
        }
    }

    /** How often the automatic issuing, held back, says so again: the inbox keeps it to once a day per person anyway. */
    static final Duration ANNOUNCE_EVERY = Duration.ofHours(1);

    private final ConcurrentMap<String, Instant> announced = new ConcurrentHashMap<>();

    /**
     * Tells the accountants the plan is used up and issuing waits (the inbox keeps it to once a day). At most once an
     * hour per provider from this instance: the automatic issuing runs every 30 seconds, and each announcement is a row
     * in the event registry.
     */
    void announceExhausted(String provider) {
        Instant now = clock.instant();
        Instant last = announced.get(provider);
        if (last != null && now.isBefore(last.plus(ANNOUNCE_EVERY))) {
            return;
        }
        announced.put(provider, now);
        EInvoiceProviderAccountAlert alert = new EInvoiceProviderAccountAlert(provider,
                EInvoiceProviderAccountAlert.Problem.QUOTA_EXHAUSTED, HELD_BACK_CODE, 0L, null, now);
        transaction.executeWithoutResult(status -> events.publishEvent(alert));
    }
}
