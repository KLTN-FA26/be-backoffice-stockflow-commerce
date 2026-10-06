package com.stockflow.warehouse.internal.domain;

/**
 * What every bin made by {@link Shelf#generateBins} starts with, besides the code and footprint the
 * grid gives it (BR-14). Split the way the tables are: {@code type} and the override go on the bin,
 * {@code settings} on its storage location.
 *
 * @param storageClassOverride {@code null} = the shelf's default class applies
 */
public record BinDefaults(BinType type, StorageClass storageClassOverride, LocationSettings settings) {

    public BinDefaults {
        if (type == null) {
            throw DomainChecks.invalid("A bin needs a type");
        }
        if (settings == null) {
            throw DomainChecks.invalid("A bin needs location settings");
        }
    }

    BinDetails detailsAt(Footprint footprint) {
        return new BinDetails(null, footprint, type, storageClassOverride);
    }
}
