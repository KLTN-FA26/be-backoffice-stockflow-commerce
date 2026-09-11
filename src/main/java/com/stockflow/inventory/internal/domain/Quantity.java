package com.stockflow.inventory.internal.domain;

/**
 * A non-negative count of physical units.
 *
 * <p>Immutable: every operator returns a new instance. The compact constructor is the only place
 * in the module that has to check for a negative quantity, because once a {@code Quantity} exists
 * it is valid by construction.</p>
 */
public record Quantity(int value) implements Comparable<Quantity> {

    public static final Quantity ZERO = new Quantity(0);

    public Quantity {
        if (value < 0) {
            throw new IllegalArgumentException("Quantity must not be negative: " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(this.value + other.value);
    }

    /** @throws IllegalArgumentException if the result would go below zero */
    public Quantity minus(Quantity other) {
        return new Quantity(this.value - other.value);
    }

    public boolean isLessThan(Quantity other) {
        return this.value < other.value;
    }

    public boolean isZero() {
        return this.value == 0;
    }

    @Override
    public int compareTo(Quantity other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
