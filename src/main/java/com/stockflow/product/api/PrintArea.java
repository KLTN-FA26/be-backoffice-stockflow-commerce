package com.stockflow.product.api;

/**
 * One printable surface of a customization template, in millimetres on the flat template.
 *
 * @param code         {@code BODY} (a cup's wall) or {@code FRONT}, {@code BACK}, {@code LEFT}, {@code RIGHT} (a bag)
 * @param name         what the customer is shown
 * @param xMm          left of the trimmed area's flat bounding box
 * @param yMm          top of the trimmed area's flat bounding box
 * @param widthMm      width of the trimmed area's flat bounding box: a bag side's width, a straight cup wall's
 *                     circumference plus glue flap, a conical wall's unrolled band
 * @param heightMm     height of the trimmed area's flat bounding box
 * @param safeMarginMm inset from the trim line that nothing important may cross (kltn-docs 12 BR-05)
 * @param bleedMm      outset past the trim line that artwork must reach
 * @param shape        the flat outline of the trimmed area
 * @param meshPart     the part of the 3D model this area is wrapped on
 */
public record PrintArea(String code, String name, double xMm, double yMm, double widthMm, double heightMm,
                        double safeMarginMm, double bleedMm, PrintAreaShape shape, String meshPart) {
}
