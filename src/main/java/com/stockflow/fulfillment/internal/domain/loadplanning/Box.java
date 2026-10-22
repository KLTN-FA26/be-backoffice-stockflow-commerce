package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The earlier solver's {@code Box}: a quantity of one {@link ItemType}, with the sizes it is offered in. Those are the
 * type's own sizes, except when the solver re-offers cargo it already placed: then they are the placed sizes, as in
 * the original, which re-packed placed cartons in the orientation they had.
 */
final class Box {

    final ItemType type;
    final double w;
    final double h;
    final double d;
    final int quantity;
    private List<double[]> orientations;

    Box(ItemType type, double w, double h, double d, int quantity) {
        this.type = type;
        this.w = w;
        this.h = h;
        this.d = d;
        this.quantity = quantity;
    }

    Box withQuantity(int newQuantity) {
        return new Box(type, w, h, d, newQuantity);
    }

    String partName() {
        return type.itemKey;
    }

    double volume() {
        return w * h * d;
    }

    /**
     * {@code _box_orientations}: the given sizes only when the carton may not turn (BR-10), otherwise every distinct
     * permutation in ascending lexicographic order - the order decides ties, so it is kept exactly.
     */
    List<double[]> orientations() {
        if (orientations == null) {
            orientations = computeOrientations();
        }
        return orientations;
    }

    private List<double[]> computeOrientations() {
        List<double[]> out = new ArrayList<>();
        if (!type.rotatable) {
            out.add(new double[] {w, h, d});
            return out;
        }
        TreeSet<double[]> sorted = new TreeSet<>((a, b) -> {
            for (int i = 0; i < 3; i++) {
                int c = Double.compare(a[i], b[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        double[] dims = {w, h, d};
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] p : perms) {
            sorted.add(new double[] {dims[p[0]], dims[p[1]], dims[p[2]]});
        }
        out.addAll(sorted);
        return out;
    }
}
