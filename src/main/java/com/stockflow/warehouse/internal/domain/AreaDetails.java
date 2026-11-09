package com.stockflow.warehouse.internal.domain;

/**
 * Everything about an area that may change after it is created - not its code, which is part of
 * its location code.
 *
 * <p>For a storage type, {@code settings} describe the area's storage location. A {@code NON_STORAGE} area has
 * no location, so they are dropped rather than refused: a form that still carries them from an earlier type should
 * not fail for it.</p>
 */
public record AreaDetails(AreaType type, String name, Footprint footprint, boolean obstacle,
                          LocationSettings settings) {

    static final int NAME_MAX_LENGTH = 200;

    public AreaDetails {
        if (type == null) {
            throw DomainChecks.invalid("An area needs a type");
        }
        name = DomainChecks.requiredText("name", name, NAME_MAX_LENGTH);
        if (footprint == null) {
            throw DomainChecks.invalid("An area needs a footprint");
        }
        if (type.isStorage()) {
            if (settings == null) {
                throw DomainChecks.invalid("A %s area holds stock, so it needs location settings".formatted(type));
            }
        } else {
            settings = null;
        }
    }
}
