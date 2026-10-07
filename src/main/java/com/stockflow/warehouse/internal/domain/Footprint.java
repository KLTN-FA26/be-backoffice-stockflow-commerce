package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * The rectangle a shelf, bin or area occupies, in the warehouse's map unit.
 *
 * <p>{@code (x, y)} is the top-left corner of the shape <b>after</b> rotation, and the rotation is a
 * quarter turn only (issue #18 D1, {@code ck_*_rotation} in the schema). That restriction is what
 * keeps every footprint axis-aligned, so overlap and containment are four comparisons each instead
 * of a separating-axis test, and the walking-distance grid of SCRUM-91 stays a grid. A quarter or
 * three-quarter turn swaps the sides: {@link #effectiveWidth()} runs along x, {@link
 * #effectiveLength()} along y.</p>
 *
 * <p>Every measure is held at scale 3 - the {@code NUMERIC(10,3)} the columns use - so two equal
 * shapes are equal records. A finer value is refused, not rounded: silently moving a shelf by a
 * fraction of a millimetre could turn a touching pair into an overlapping one.</p>
 *
 * <p>The geometry rules have no database twin (an exclusion constraint cannot span
 * {@code shelf} and {@code area}), so this class is where BR-06 and BR-07 are actually decided.</p>
 */
public record Footprint(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length, int rotation) {

    private static final int SCALE = 3;
    /** {@code NUMERIC(10,3)}: seven digits before the point. */
    private static final BigDecimal COLUMN_LIMIT = new BigDecimal("10000000");
    private static final Set<Integer> QUARTER_TURNS = Set.of(0, 90, 180, 270);

    public Footprint {
        x = measure("x", x);
        y = measure("y", y);
        width = measure("width", width);
        length = measure("length", length);
        if (x.signum() < 0 || y.signum() < 0) {
            throw invalid("A footprint cannot start at a negative position");
        }
        if (width.signum() <= 0 || length.signum() <= 0) {
            throw invalid("A footprint needs a positive width and length");
        }
        if (!QUARTER_TURNS.contains(rotation)) {
            throw invalid("Rotation must be 0, 90, 180 or 270 degrees, got " + rotation);
        }
    }

    /** The extent along x once rotated. */
    public BigDecimal effectiveWidth() {
        return isQuarterTurned() ? length : width;
    }

    /** The extent along y once rotated. */
    public BigDecimal effectiveLength() {
        return isQuarterTurned() ? width : length;
    }

    /**
     * Whether the two shapes share any area. Strict: two shelves standing back to back touch along
     * an edge and do <b>not</b> overlap.
     */
    public boolean overlaps(Footprint other) {
        return x.compareTo(other.right()) < 0 && other.x.compareTo(right()) < 0
                && y.compareTo(other.bottom()) < 0 && other.y.compareTo(bottom()) < 0;
    }

    /** Whether {@code other} lies entirely inside this shape; shared edges count as inside. */
    public boolean contains(Footprint other) {
        return x.compareTo(other.x) <= 0 && other.right().compareTo(right()) <= 0
                && y.compareTo(other.y) <= 0 && other.bottom().compareTo(bottom()) <= 0;
    }

    /**
     * Whether this shape lies inside a frame anchored at the origin - the warehouse map (BR-06), or a
     * shelf's own frame for its bins.
     */
    public boolean fitsWithin(BigDecimal frameWidth, BigDecimal frameLength) {
        return right().compareTo(frameWidth) <= 0 && bottom().compareTo(frameLength) <= 0;
    }

    private boolean isQuarterTurned() {
        return rotation == 90 || rotation == 270;
    }

    private BigDecimal right() {
        return x.add(effectiveWidth());
    }

    private BigDecimal bottom() {
        return y.add(effectiveLength());
    }

    private static BigDecimal measure(String name, BigDecimal value) {
        if (value == null) {
            throw invalid("A footprint needs " + name);
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException finerThanAMillimetre) {
            throw invalid("%s has more than %d decimals: %s".formatted(name, SCALE, value.toPlainString()));
        }
        if (scaled.abs().compareTo(COLUMN_LIMIT) >= 0) {
            throw invalid("%s is too large: %s".formatted(name, value.toPlainString()));
        }
        return scaled;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
