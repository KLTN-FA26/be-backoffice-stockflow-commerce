package com.stockflow.order.internal.domain;

import java.util.Objects;

/**
 * A party to a sales contract as the contract names it (kltn-docs 14 §4.3 step 11; 18 §3): copied when the contract is
 * drawn up, so a later change of the company's profile leaves it as it was (18 §6).
 *
 * @param taxCode null for a buyer that has none yet (a household business, 18 §3)
 */
public record ContractParty(String name, String taxCode, String address, String representative,
                            String representativeTitle) {

    public ContractParty {
        name = required(name, "name");
        address = required(address, "address");
        representative = required(representative, "representative");
        representativeTitle = required(representativeTitle, "representativeTitle");
        taxCode = taxCode == null || taxCode.isBlank() ? null : taxCode.strip();
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return value.strip();
    }
}
