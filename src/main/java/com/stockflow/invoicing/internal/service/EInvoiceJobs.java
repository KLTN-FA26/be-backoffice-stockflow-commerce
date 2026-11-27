package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The invoicing module's background work, one instance at a time ({@code @SchedulerLock}):
 *
 * <ul>
 *   <li><b>Reconciliation</b> - a provider answers later, and never calls back (kltn-docs 21 §2), so every invoice that
 *       is SUBMITTING is asked about until it is issued or refused. This is also what finishes an invoice whose reply
 *       was lost or whose application stopped mid-call: it asks by transaction id, and sends only what the provider has
 *       never received ({@link EInvoiceIssuance#reconcile}).</li>
 *   <li><b>Automatic issuing</b> - when it is turned on, drafts with a complete buyer and nothing to look at are issued
 *       here, a moment after they were drawn up. Done by the job and not by the listener that draws the draft up: the
 *       listener's transaction has to commit the draft before anything may be sent anywhere. A draft it holds back - a
 *       warning that needs a person, a refusal it met - is announced at once (audit r2 M-2), once per reason.</li>
 *   <li><b>Deadline warnings</b> - an invoice is to be issued by the end of its date (NĐ 254 Điều 9 khoản 1;
 *       WF-05 Q-1); the accountants are told once, four hours ahead by default, about each one still waiting,
 *       and once its deadline has passed, reminded once a day for as long as it is not issued (HĐ-2) - an invoice left
 *       in draft or stuck at the provider is late every day it stays so. In the same sweep they are told, once per
 *       submission, of an invoice that has waited for the provider's answer for too long (WF-05 E5): the
 *       reconciliation keeps asking, but somebody has to know. And the provider's remaining invoices are looked at
 *       ({@link EInvoiceQuotaWatch}, kltn-docs 21 BR-09), so a plan running out is known before invoices are
 *       refused for it.</li>
 * </ul>
 *
 * <p>Bounded batches, the most urgent first; what does not fit waits for the next run. One invoice failing does not
 * stop the others.</p>
 *
 * <p>The scheduler lock is not what keeps two instances from talking to the provider about one invoice at once: it
 * runs out after {@code lockAtMostFor} however long the run takes, and the accountant's "check again" never takes it.
 * That is the per-invoice lease ({@link EInvoiceIssuance#reconcile}). The lock only spares the provider a second batch
 * asking the same questions, so the reconciliation stops starting new invoices after {@link #RECONCILE_BUDGET}, well
 * inside the lock, and leaves the rest to the next run.</p>
 */
@Component
class EInvoiceJobs {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceJobs.class);
    static final int BATCH = 50;
    /**
     * How long the reconciliation goes on starting new invoices. Below {@code lockAtMostFor} (10 minutes) by more than
     * one invoice can take in the usual case; an invoice whose provider calls all run into their timeouts may still
     * outlast the lock, and its lease is what protects it then.
     */
    static final Duration RECONCILE_BUDGET = Duration.ofMinutes(3);

    private final EInvoiceWorkQueues queues;
    private final EInvoiceIssuance issuance;
    private final EInvoiceQuotaWatch quota;
    private final EInvoiceDrafts drafts;
    private final EInvoiceQuotaGate quotaGate;

    EInvoiceJobs(EInvoiceWorkQueues queues, EInvoiceIssuance issuance, EInvoiceQuotaWatch quota,
                 EInvoiceDrafts drafts, EInvoiceQuotaGate quotaGate) {
        this.queues = queues;
        this.issuance = issuance;
        this.quota = quota;
        this.drafts = drafts;
        this.quotaGate = quotaGate;
    }

    @Scheduled(initialDelayString = "${stockflow.invoicing.reconcile-interval:PT30S}",
            fixedDelayString = "${stockflow.invoicing.reconcile-interval:PT30S}")
    @SchedulerLock(name = "invoicing.reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void reconcileAndIssue() {
        int reconciled = reconcileSubmitting();
        int issued = issueAutomatically();
        int held = announceHeld();
        if (reconciled > 0 || issued > 0 || held > 0) {
            log.info("E-invoices: {} asked about at the provider, {} issued automatically, {} held back announced",
                    reconciled, issued, held);
        }
    }

    @Scheduled(initialDelayString = "${stockflow.invoicing.deadline-sweep-interval:PT15M}",
            fixedDelayString = "${stockflow.invoicing.deadline-sweep-interval:PT15M}")
    @SchedulerLock(name = "invoicing.deadline-warnings", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void warnOfDeadlines() {
        int warned = warnDue();
        int reminded = remindOverdue();
        int stuck = warnStuck();
        int overdue = announceResultOverdue();
        if (warned > 0 || reminded > 0 || stuck > 0 || overdue > 0) {
            log.info("E-invoices: {} close to their deadline, {} past it, {} stuck at the provider and {} overdue for "
                    + "the provider's result announced", warned, reminded, stuck, overdue);
        }
        try {
            quota.check();
        } catch (RuntimeException failure) {
            log.warn("The e-invoice provider's quota was not looked at: {}", failure.toString());
        }
    }

    /**
     * Asks the provider again, about once a day each, about every transaction let go without its word - withdrawn, or
     * refused on its processing report or the tax authority's refusal alone - for 30 days (WF-05 BE-07, decision Q-5;
     * {@link EInvoiceIssuance#watchLetGoTransactions}). Hourly, so a day's rows are spread out; each row is asked once
     * in {@link EInvoiceIssuanceSteps#WATCH_ASKED_EVERY}.
     */
    @Scheduled(initialDelayString = "${stockflow.invoicing.let-go-watch-interval:PT1H}",
            fixedDelayString = "${stockflow.invoicing.let-go-watch-interval:PT1H}")
    @SchedulerLock(name = "invoicing.let-go-watch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    public void watchLetGoTransactions() {
        try {
            int found = issuance.watchLetGoTransactions(BATCH);
            if (found > 0) {
                log.warn("E-invoices: {} transaction(s) let go were found issued at the provider after all", found);
            }
        } catch (RuntimeException failure) {
            log.warn("Let-go e-invoice transactions were not asked about: {}", failure.toString());
        }
    }

    /** @return how many invoices were asked about */
    int reconcileSubmitting() {
        int done = 0;
        long stopAt = System.nanoTime() + RECONCILE_BUDGET.toNanos();
        for (UUID id : queues.submitting(BATCH)) {
            if (System.nanoTime() - stopAt > 0) {
                log.info("E-invoice reconciliation stopped after {}; the rest waits for the next run", RECONCILE_BUDGET);
                break;
            }
            try {
                issuance.reconcile(id);
                done++;
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} was not reconciled: {}", id, failure.toString());
            }
        }
        return done;
    }

    /**
     * Sends the drafts the automatic issuing takes, each checked first against the rules as they are now
     * ({@link EInvoiceDrafts#recheckForAutomaticIssue}): a draft drawn up before a rule existed - the e-mail warning, a
     * VAT reduction period - is held back with its warning instead of being issued as the rule never was.
     *
     * @return how many drafts were sent to the provider
     */
    int issueAutomatically() {
        int done = 0;
        List<UUID> waiting = queues.autoIssuable(BATCH);
        if (waiting.isEmpty()) {
            return 0;
        }
        String provider = queues.activeProvider();
        if (quotaGate.exhausted(provider)) {
            // BE-54 (Q-22, BR-09): sent now, each would be refused for good and need reopening by hand. They stay
            // drafts and go out on their own once the plan is topped up; the accountants are told.
            quotaGate.announceExhausted(provider);
            log.warn("E-invoices: {} draft(s) wait - the provider's plan has no invoice left", waiting.size());
            return 0;
        }
        for (UUID id : waiting) {
            try {
                if (!drafts.recheckForAutomaticIssue(id)) {
                    log.info("E-invoice {} was not issued automatically: it no longer is what drawing it up now "
                            + "gives, and waits for the accountant", id);
                    continue;
                }
                issuance.issue(id, null, null);
                done++;
            } catch (BusinessException refused) {
                // A draft that cannot be issued as it stands (no series for its year ...): the accountant's to see -
                // told now, not at the deadline warning (audit r2 M-2), unless nobody can act on it per draft.
                log.warn("E-invoice {} was not issued automatically: {}", id, refused.errorCode());
                if (worthTelling(refused.errorCode())) {
                    announce(id, refused.errorCode().name());
                }
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} was not issued automatically: {}", id, failure.toString());
            }
        }
        return done;
    }

    /**
     * Tells the accountants of every draft the automatic issuing holds back for its warnings, at once (audit r2 M-2): a
     * draft drawn up with one, or given one by {@link EInvoiceDrafts#recheckForAutomaticIssue} a moment ago. Run right
     * after the automatic issuing, so the word goes out within a run of the draft being held.
     *
     * @return how many were announced
     */
    int announceHeld() {
        int done = 0;
        for (UUID id : queues.heldUnannounced(BATCH)) {
            if (announce(id, null)) {
                done++;
            }
        }
        return done;
    }

    private boolean announce(UUID id, String refusal) {
        try {
            return queues.announceHeld(id, refusal).isPresent();
        } catch (RuntimeException failure) {
            log.warn("E-invoice {} held back from the automatic issuing was not announced: {}", id, failure.toString());
            return false;
        }
    }

    /**
     * Whether a refusal of the automatic issuing is told per draft. Not a passing one - the provider not answering, a
     * concurrent change - which the next run retries; not a used-up plan, told once for the account
     * ({@link EInvoiceQuotaGate}); and not a delivery no longer delivered, whose draft the correction voids.
     */
    static boolean worthTelling(ErrorCode code) {
        return code.httpStatus() < 500 && code != ErrorCode.OPTIMISTIC_LOCK
                && code != ErrorCode.EINVOICE_QUOTA_EXHAUSTED && code != ErrorCode.EINVOICE_NOT_INVOICEABLE;
    }

    /** @return how many invoices were announced */
    int warnDue() {
        int done = 0;
        for (UUID id : queues.dueUnwarned(BATCH)) {
            try {
                if (queues.warnOfDeadline(id).isPresent()) {
                    done++;
                }
            } catch (RuntimeException failure) {
                log.warn("The deadline of e-invoice {} was not announced: {}", id, failure.toString());
            }
        }
        return done;
    }

    /** @return how many invoices past their deadline were reminded of */
    int remindOverdue() {
        int done = 0;
        for (UUID id : queues.overdueToRemind(BATCH)) {
            try {
                if (queues.remindOverdue(id).isPresent()) {
                    done++;
                }
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} past its deadline was not reminded of: {}", id, failure.toString());
            }
        }
        return done;
    }

    /** @return how many submissions overdue for the provider's result were announced (BE-08) */
    int announceResultOverdue() {
        int done = 0;
        for (UUID id : queues.resultOverdueUnannounced(BATCH)) {
            try {
                if (queues.announceResultOverdue(id).isPresent()) {
                    done++;
                }
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} overdue for the provider's result was not announced: {}", id,
                        failure.toString());
            }
        }
        return done;
    }

    /** @return how many stuck submissions were announced */
    int warnStuck() {
        int done = 0;
        for (UUID id : queues.stuckUnannounced(BATCH)) {
            try {
                if (queues.announceStuck(id).isPresent()) {
                    done++;
                }
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} stuck at the provider was not announced: {}", id, failure.toString());
            }
        }
        return done;
    }
}
