package com.stockflow.warehouse.internal.domain;

/**
 * Everything about a bin that may change after it is created - not its code, which is part of its
 * location code.
 *
 * <p>The footprint's width and length are also two of the three measures a carton must fit within (kltn-docs 06
 * BR-01) and of the bin's computed volume ({@code BinSize}); the third is its level's clear height.</p>
 *
 * @param footprint in the shelf's own frame, so moving the shelf never moves its bins
 */
public record BinDetails(String description, Footprint footprint, BinType type) {

    static final int DESCRIPTION_MAX_LENGTH = 1000;

    public BinDetails {
        description = DomainChecks.optionalText("description", description, DESCRIPTION_MAX_LENGTH);
        if (footprint == null) {
            throw DomainChecks.invalid("A bin needs a footprint");
        }
        if (type == null) {
            throw DomainChecks.invalid("A bin needs a type");
        }
    }
}
