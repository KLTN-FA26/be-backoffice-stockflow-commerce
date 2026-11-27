package com.stockflow.customer.internal.service;

import com.stockflow.contracts.StaffAccountDeactivated;
import com.stockflow.customer.internal.domain.OpenLeads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * BR-S08: a salesperson's account was locked or disabled, so their customers wait for a Sales Manager to assign an
 * owner (NEEDS_ASSIGNMENT, the former owner kept, one history row each, {@code CustomerNeedsSalesOwner} per customer).
 * Nothing is moved to anyone by itself: who takes over is the manager's decision (BR-S09, BR-S10). Their open leads
 * (ASSIGNED, CONTACTED) go back to NEW, unassigned, into the managers' queue, each with a history row (owner decision
 * D44).
 *
 * <p>After the lock commits, in a transaction of its own, retried from the event publication registry. A second
 * delivery finds no customer still ASSIGNED to the account and changes nothing. Until it runs,
 * {@code ResponsibleSales} already treats the locked owner as gone: it reads the account, not only the state.</p>
 */
@Component
public class StaffDeactivationListener {

    private static final Logger log = LoggerFactory.getLogger(StaffDeactivationListener.class);

    /** The note on each lead put back (D44). A stored note, like every system note in a history: English. */
    static final String LEAD_RELEASED_NOTE = "The salesperson's account was locked or disabled: back to the managers' queue";

    private final SalesOwnershipService ownership;
    private final OpenLeads leads;
    private final Clock clock;

    StaffDeactivationListener(SalesOwnershipService ownership, OpenLeads leads, Clock clock) {
        this.ownership = ownership;
        this.leads = leads;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(StaffAccountDeactivated deactivated) {
        ownership.releaseCustomersOf(deactivated.userId(), deactivated.byUserId());
        List<UUID> released = leads.releaseOpenLeadsOf(deactivated.userId(), LEAD_RELEASED_NOTE,
                deactivated.byUserId(), clock.instant());
        if (!released.isEmpty()) {
            log.info("{} open lead(s) of account {} are back in the managers' queue", released.size(),
                    deactivated.userId());
        }
    }
}
