package com.stockflow.chat.internal.service;

import com.stockflow.contracts.CustomerSalesOwnerChanged;
import com.stockflow.contracts.StaffAccountDeactivated;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The chat's side of the Sales owner (sales-owner plan BR-S06, BR-S08; kltn-docs 16 BR-14): after the change or the
 * lock commits, in a transaction of its own, retried from the event publication registry until it succeeds. Both are
 * idempotent ({@link ConversationReassignment}): a change the customer module made and a lock identity made must not
 * wait for, nor be rolled back by, what the chat does next.
 */
@Component
public class SalesOwnerEventsListener {

    private final ConversationReassignment reassignment;

    SalesOwnerEventsListener(ConversationReassignment reassignment) {
        this.reassignment = reassignment;
    }

    /** BR-S06: the customer's open conversations follow the new owner. */
    @ApplicationModuleListener
    public void on(CustomerSalesOwnerChanged changed) {
        reassignment.followOwner(changed);
    }

    /** BR-S08: the agent's open conversations go back to the queue, flagged. */
    @ApplicationModuleListener
    public void on(StaffAccountDeactivated deactivated) {
        reassignment.requeueAgent(deactivated);
    }
}
