package com.stockflow.warehouse.internal.domain;

/**
 * What every bin made by {@link Shelf#generateBins} starts with, besides the code and footprint the
 * grid gives it (BR-14). Split the way the tables are: {@code type} goes on the bin, {@code settings} on its
 * storage location.
 */
public record BinDefaults(BinType type, LocationSettings settings) {

    public BinDefaults {
        if (type == null) {
            throw DomainChecks.invalid("A bin needs a type");
        }
        if (settings == null) {
            throw DomainChecks.invalid("A bin needs location settings");
        }
    }

    BinDetails detailsAt(Footprint footprint) {
        return new BinDetails(null, footprint, type);
    }
}
