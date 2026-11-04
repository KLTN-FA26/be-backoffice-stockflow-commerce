package com.stockflow.warehouse.internal.domain;

/**
 * Everything about a bin that may change after it is created - not its code, which is part of its
 * location code.
 *
 * @param footprint            in the shelf's own frame, so moving the shelf never moves its bins
 * @param storageClassOverride {@code null} = the shelf's default class applies
 */
public record BinDetails(String description, Footprint footprint, BinType type,
                         StorageClass storageClassOverride) {

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
