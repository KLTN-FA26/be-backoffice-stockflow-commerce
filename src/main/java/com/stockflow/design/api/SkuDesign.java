package com.stockflow.design.api;

import java.util.UUID;

/**
 * The design a customer's own SKU was made to, as a line of a quote, an order or a contract shows it beside the SKU.
 *
 * @param sku        the customer's SKU, as stored
 * @param designId   the design (draft) the SKU's approved snapshot froze
 * @param name       what the design is called; the SKU's own name when the design has none
 * @param previewUrl a short-lived link showing the design's preview image; null when it has none, or the store signs
 *                   no browser links
 */
public record SkuDesign(String sku, UUID designId, String name, String previewUrl) {
}
