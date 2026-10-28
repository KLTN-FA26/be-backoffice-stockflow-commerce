package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.inventory.internal.service.BookVisibility;

import java.util.Optional;

/**
 * Who reads the books of a slot under a cycle count (SCRUM-146, owner decision of 28/10): whoever holds
 * {@code inventory-cycle-counts:APPROVE}. Asked by permission, so a custom role with the grant is covered and a role
 * name never is.
 *
 * <p>Takes the caller as an {@code Optional}, and an absent caller is blind: the stock screens are guarded by a
 * permission, so in a secured deployment there always is one, and where there is none (security switched off in
 * tests) hiding a slot's books is the side that cannot leak. Fail closed.</p>
 */
final class BlindCount {

    private BlindCount() {
    }

    static BookVisibility visibilityFor(Optional<CurrentUser> caller) {
        return BookVisibility.forCaller(caller.map(user -> user.can(InventoryResources.CYCLE_COUNTS, Action.APPROVE))
                .orElse(false));
    }
}
