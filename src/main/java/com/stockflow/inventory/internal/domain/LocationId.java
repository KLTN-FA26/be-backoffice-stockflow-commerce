package com.stockflow.inventory.internal.domain;

/**
 * Reference to a bin in the Zone / Aisle / Rack / Level / Bin hierarchy (BRD 3.5.1.2).
 *
 * <p>The {@code warehouse} module owns what a location <i>is</i> — its capacity, its zone type,
 * whether it is pickable. Inventory only needs to say which one a quantity sits in, so it keeps
 * the code and nothing else. This is a deliberate choice over a JPA {@code @ManyToOne} to a
 * warehouse entity: an object reference across a module boundary would let inventory navigate
 * into warehouse's model and quietly become dependent on its internals.</p>
 */
public record LocationId(String code) {

    public LocationId {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Location code must not be blank");
        }
        code = code.trim().toUpperCase();
    }

    /**
     * The warehouse this location belongs to: the segment before the first hyphen ({@code HN} in
     * {@code HN-A01-2-03} or {@code HN-RCV01}). The prefix is part of the immutable code (BR-13),
     * which is what makes deriving the warehouse from it safe without asking {@code warehouse}.
     */
    public String warehouseCode() {
        int dash = code.indexOf('-');
        return dash > 0 ? code.substring(0, dash) : code;
    }

    @Override
    public String toString() {
        return code;
    }
}
