package com.stockflow.design.internal.domain;

import java.util.UUID;

/**
 * An image an approved layout places, as the snapshot keeps it (kltn-docs 12 BR-08): the stored file and its checksum,
 * so the snapshot can be checked against the store as its files are.
 */
public record DesignSnapshotAsset(UUID assetId, String storageKey, String originalName, String contentType,
                                  long sizeBytes, String checksum, int widthPx, int heightPx) {
}
