package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One vehicle of a plan as the 3D viewer renders it: the {@code Layout} contract of the frontend
 * ({@code src/features/load-planning/schemas.ts}, FE #71; design v2 §5). The advisory estimate (PR EST) and the real
 * load plan (PR D) answer in this one shape, built by {@link Layouts}.
 *
 * <p>Every length is whole millimetres in the planner's frame: {@code x} across the width, {@code y} up, {@code z}
 * along the length toward the door, from the inner floor corner at the front wall. {@code innerMm.l} runs along
 * {@code z}, {@code w} along {@code x}, {@code h} along {@code y}.</p>
 *
 * @param vehicleTypeId  not in the frontend's schema (it ignores unknown keys): which catalogue type the vehicle is
 * @param volumeFillPct  loaded volume over the inner volume, percent; extra, like {@code weightFillPct}
 */
public record Layout(int seq, String code, UUID vehicleTypeId, Dims innerMm, BigDecimal maxPayloadKg,
                     List<Block> gussets, List<Box> boxes, long usedVolumeMm3, BigDecimal usedWeightKg,
                     BigDecimal volumeFillPct, BigDecimal weightFillPct) {

    public Layout {
        gussets = List.copyOf(gussets);
        boxes = List.copyOf(boxes);
    }

    /** Sizes in the order printed on a carton: {@code l} length, {@code w} width, {@code h} height. */
    public record Dims(int l, int w, int h) { }

    /** A reserved corner block (BR-10 gusset): smallest corner and extent along x, y, z. */
    public record Block(int xMm, int yMm, int zMm, int wMm, int hMm, int dMm) { }

    /**
     * One placed carton.
     *
     * @param ref          the carton: a package id, or {@code <lineId>#<n>} for an estimated carton; unique in a plan
     * @param sourceRef    the estimate line that made the carton; null for a packed parcel (it would only repeat ref)
     * @param label        the short code printed on its face (the SKU)
     * @param partName     what it is - the colour key and the row key of the viewer: the part code the planner was
     *                     given ({@code SKU}, or {@code SKU/2} for the second box of a kit)
     * @param kitGroup     the set it belongs to, or null
     * @param origMm       its measured sizes, before turning
     * @param orientation  the viewer's code (z, x, y order of the measured sides, {@link Orientation})
     * @param loadSequence 1-based order to load the vehicle in; extra to the frontend's schema
     */
    public record Box(int xMm, int yMm, int zMm, int wMm, int hMm, int dMm, String ref, String sourceRef, String label,
                      String partName, String kitGroup, Dims origMm, String orientation, boolean rotatable,
                      BigDecimal weightKg, int loadSequence) { }

    /**
     * Cartons the plan left out, grouped as the frontend's {@code UnplacedLineSchema} reads them (FE #71: label,
     * partName, origMm, quantity): one entry per source line, part, size and reason.
     *
     * @param sourceRef the estimate line the cartons come from; null for packed parcels
     * @param reason    the planner's reason, the same for every carton of the entry
     * @param quantity  how many cartons
     * @param refs      the cartons, in the planner's order; {@code refs.size() == quantity}
     */
    public record UnplacedLine(String sourceRef, String label, String partName, Dims origMm, UnplacedReason reason,
                               int quantity, List<String> refs) {

        public UnplacedLine {
            refs = List.copyOf(refs);
        }
    }
}
