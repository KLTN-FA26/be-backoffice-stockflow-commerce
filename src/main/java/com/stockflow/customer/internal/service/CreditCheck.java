package com.stockflow.customer.internal.service;

import com.stockflow.customer.internal.domain.CreditProfile;

import java.math.RoundingMode;

/**
 * Whether a customer has credit accounting approved with a limit above 0 (kltn-docs 18 BR-03): what an installment after
 * delivery needs, asked the same way where the schedule is set and where the credit terms change - and the way
 * {@code customer.check_default_payment_schedule()} asks it at commit, on the limit as {@code NUMERIC(19, 4)} stores it
 * (a limit of 0.00004 is stored as 0, so it is no credit here either).
 */
final class CreditCheck {

    private CreditCheck() {
    }

    static boolean approvedCredit(CreditProfile terms) {
        return terms.creditLimit() != null
                && terms.creditLimit().setScale(4, RoundingMode.HALF_UP).signum() > 0;
    }
}
