package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;

/**
 * Where one carton sits in its vehicle - the box of the shared layout contract with the 3D viewer. Millimetres from the
 * inner-left floor corner by the front wall; {@code x} across, {@code y} up, {@code z} toward the door.
 *
 * @param w            placed size along {@code x}; {@code h} along {@code y}, {@code d} along {@code z}
 * @param lengthMm     the carton's measured sizes; with {@code orientation} they give {@code w}, {@code h}, {@code d}
 * @param loadSequence 1-based order in which to load it: every carton after the ones it rests on, front wall first
 * @param fill         a lower-priority carton topped up into the space the main cargo left
 */
public record Placement(String ref, String sourceRef, String partName, String kitGroup, int x, int y, int z, int w,
                        int h, int d, int lengthMm, int widthMm, int heightMm, Orientation orientation,
                        boolean rotatable, BigDecimal weightKg, int loadSequence, boolean fill) { }
