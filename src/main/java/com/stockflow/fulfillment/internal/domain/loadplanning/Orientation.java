package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * How a carton's measured sizes map onto the vehicle's axes. Only right-angle turns exist - a carton is always square
 * to the walls (BR-10) - so these six are every way it can lie.
 *
 * <p>The name is the code shared with the 3D viewer: which measured size - <b>L</b>ength, <b>W</b>idth or
 * <b>H</b>eight - lies along {@code z} (toward the door), {@code x} (across) and {@code y} (up), in that order.
 * {@link #LWH} is the carton as measured.</p>
 *
 * <p>The {@link #index()} is the earlier solver's: the position of the placed {@code (x, y, z)} sizes among the
 * {@code itertools.permutations} of the measured {@code (width, height, length)}. When two sizes are equal several
 * orientations give the same placed sizes, and the first matching one is reported - so a cube is always {@code LWH}.
 * Never reorder the constants.</p>
 */
public enum Orientation {

    /** x = width, y = height, z = length: as measured. The only one for a carton that may not turn. */
    LWH(0, 0, 1, 2),
    /** x = width, y = length, z = height. */
    HWL(1, 0, 2, 1),
    /** x = height, y = width, z = length. */
    LHW(2, 1, 0, 2),
    /** x = height, y = length, z = width. */
    WHL(3, 1, 2, 0),
    /** x = length, y = width, z = height. */
    HLW(4, 2, 0, 1),
    /** x = length, y = height, z = width. */
    WLH(5, 2, 1, 0);

    private final int index;
    /** For x, y and z: which of the measured (width, height, length) lies along it. */
    private final int[] source;

    Orientation(int index, int alongX, int alongY, int alongZ) {
        this.index = index;
        this.source = new int[] {alongX, alongY, alongZ};
    }

    /** The earlier solver's 0-5 index. */
    public int index() {
        return index;
    }

    /** The viewer's code, e.g. {@code "LWH"}. */
    public String code() {
        return name();
    }

    public static Orientation ofIndex(int index) {
        for (Orientation each : values()) {
            if (each.index == index) {
                return each;
            }
        }
        throw new IllegalArgumentException("no orientation " + index);
    }

    public static Orientation ofCode(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("no orientation " + code, e);
        }
    }

    /** The placed sizes {@code (x, y, z)} of a carton lying this way. */
    public int[] extents(CargoPackage carton) {
        int[] measured = {carton.widthMm(), carton.heightMm(), carton.lengthMm()};
        return new int[] {measured[source[0]], measured[source[1]], measured[source[2]]};
    }

    /**
     * The orientation that lays the carton with these placed sizes: the first in index order that matches, or null
     * when none does (the sizes are not the carton's).
     */
    public static Orientation of(CargoPackage carton, int w, int h, int d) {
        for (Orientation each : values()) {
            int[] e = each.extents(carton);
            if (e[0] == w && e[1] == h && e[2] == d) {
                return each;
            }
        }
        return null;
    }

    /** Whether a carton may lie this way (BR-10: a carton that may not turn keeps its placing orientation). */
    public boolean allowedFor(CargoPackage carton) {
        if (carton.rotatable()) {
            return true;
        }
        int[] e = extents(carton);
        return e[0] == carton.widthMm() && e[1] == carton.heightMm() && e[2] == carton.lengthMm();
    }
}
