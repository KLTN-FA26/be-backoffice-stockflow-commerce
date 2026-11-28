package com.stockflow.customer.internal.domain;

import com.stockflow.customer.api.SalesOwnerState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port for the customers a new one may duplicate (sales-owner plan BR-S14, case 16): the same tax code, phone or
 * e-mail, or the same name once accents, case, punctuation and the words of the legal form (limited company, joint-stock
 * company, household business...) are set aside.
 */
public interface CustomerDuplicates {

    /**
     * What matched. {@code TAX_CODE_ROOT} (finding P4): another unit of the same organisation - the same 10-digit root,
     * the tax code itself differing (the company of a unit, or another unit).
     */
    enum On { TAX_CODE, TAX_CODE_ROOT, PHONE, EMAIL, NAME }

    record Match(UUID customerId, String fullName, Set<On> matchedOn, UUID salesOwnerId, SalesOwnerState state) {
    }

    /**
     * Customers matching any of the values given (null ones are not compared), at most {@code limit}. The values come
     * normalised as stored: tax code by {@code TaxCode}, phone by the phone validator, e-mail lower case.
     */
    List<Match> find(String taxCode, String phone, String email, String name, int limit);
}
