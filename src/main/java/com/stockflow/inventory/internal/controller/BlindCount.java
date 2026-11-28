package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.inventory.internal.service.BookVisibility;

import java.util.Optional;

/**
 * Who reads the books of a slot under a cycle count (SCRUM-146, owner decision of 28/10): whoever holds
 * {@code inventory-cycle-counts:APPROVE}, an absent caller being blind. The rule itself is
 * {@link BookVisibility#of}, shared with the published queries the assistant reads (kltn-docs 20 BR-07), so a screen and
 * an answer about the same slot cannot disagree.
 */
final class BlindCount {

    private BlindCount() {
    }

    static BookVisibility visibilityFor(Optional<CurrentUser> caller) {
        return BookVisibility.of(caller);
    }
}
