package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 *       listener's transaction has to commit the draft before anything may be sent anywhere.</li>
 *   <li><b>Deadline warnings</b> - an invoice is to be signed and sent no later than the working day after its date
 *       (NĐ 254 Phụ lục điểm 7); the accountants are told once, a day ahead by default, about each one still waiting.
 *       In the same sweep they are told, once per submission, of an invoice that has waited for the provider's answer
 *       for too long (WF-05 E5): the reconciliation keeps asking, but somebody has to know.</li>
 * </ul>
 *
 * <p>Bounded batches, the most urgent first; what does not fit waits for the next run. One invoice failing does not
 * stop the others.</p>
 */
@Component
class EInvoiceJobs {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceJobs.class);
    static final int BATCH = 50;

    private final EInvoiceWorkQueues queues;
    private final EInvoiceIssuance issuance;

    EInvoiceJobs(EInvoiceWorkQueues queues, EInvoiceIssuance issuance) {
        this.queues = queues;
        this.issuance = issuance;
    }

    @Scheduled(initialDelayString = "${stockflow.invoicing.reconcile-interval:PT30S}",
            fixedDelayString = "${stockflow.invoicing.reconcile-interval:PT30S}")
    @SchedulerLock(name = "invoicing.reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void reconcileAndIssue() {
        int reconciled = reconcileSubmitting();
        int issued = issueAutomatically();
        if (reconciled > 0 || issued > 0) {
            log.info("E-invoices: {} asked about at the provider, {} issued automatically", reconciled, issued);
        }
    }

    @Scheduled(initialDelayString = "${stockflow.invoicing.deadline-sweep-interval:PT15M}",
            fixedDelayString = "${stockflow.invoicing.deadline-sweep-interval:PT15M}")
    @SchedulerLock(name = "invoicing.deadline-warnings", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void warnOfDeadlines() {
        int warned = warnDue();
        int stuck = warnStuck();
        if (warned > 0 || stuck > 0) {
            log.info("E-invoices: {} close to their deadline and {} stuck at the provider announced", warned, stuck);
        }
    }

    /** @return how many invoices were asked about */
    int reconcileSubmitting() {
        int done = 0;
        for (UUID id : queues.submitting(BATCH)) {
            try {
                issuance.reconcile(id);
                done++;
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} was not reconciled: {}", id, failure.toString());
            }
        }
        return done;
    }

    /** @return how many drafts were sent to the provider */
    int issueAutomatically() {
        int done = 0;
        for (UUID id : queues.autoIssuable(BATCH)) {
            try {
                issuance.issue(id, null, null);
                done++;
            } catch (BusinessException refused) {
                // A draft that cannot be issued as it stands (no series for its year ...): the accountant's to see.
                log.warn("E-invoice {} was not issued automatically: {}", id, refused.errorCode());
            } catch (RuntimeException failure) {
                log.warn("E-invoice {} was not issued automatically: {}", id, failure.toString());
            }
        }
        return done;
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
