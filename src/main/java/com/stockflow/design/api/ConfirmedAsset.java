package com.stockflow.design.api;

import java.util.UUID;

/**
 * One of the customer's own images - a photo, a logo - that a confirmed design places, as its snapshot froze it
 * (kltn-docs 12 §3 "Design snapshot": the layout and the original images). Part of what was approved: the bundle's
 * checksum covers it. A producer printing spot colours separates them from the original logo, not from the print file
 * (FE request 2026-11-18 BE-6). No storage key and no link cross the module boundary; {@link FulfillmentAsset} is the
 * link, handed out after a check.
 */
public record ConfirmedAsset(UUID assetId, String originalName, String contentType, long sizeBytes, String checksum) { }
