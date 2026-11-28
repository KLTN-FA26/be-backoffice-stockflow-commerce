package com.stockflow.identity.internal.service;

import com.stockflow.contracts.StaffAccountDeactivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BR-S08: when an account stops working, the covers in which it stood in for someone are cancelled - nobody would
 * answer the absent person's customers otherwise, and {@code ResponsibleSales} falls back to the owner or the managers.
 *
 * <p>A plain {@code @EventListener}, not an after-commit module listener: it runs inside the transaction that locks or
 * disables the account, so the covers and the status change commit or roll back together. The other modules hear the
 * same event after the commit.</p>
 */
@Component
class StandInDeactivation {

    private static final Logger log = LoggerFactory.getLogger(StandInDeactivation.class);

    private final StaffCoverService covers;

    StandInDeactivation(StaffCoverService covers) {
        this.covers = covers;
    }

    @EventListener
    public void on(StaffAccountDeactivated deactivated) {
        int cancelled = covers.cancelStandingIn(deactivated.userId(), deactivated.byUserId());
        if (cancelled > 0) {
            log.info("{} cover(s) by account {} cancelled: the account is {}", cancelled, deactivated.userId(),
                    deactivated.status());
        }
    }
}
