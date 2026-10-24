package com.stockflow.inventory.consumption;

/**
 * A photo of damaged goods, checked and already in storage, waiting to be attached to its damage report
 * (BR-STK-003). Made only by {@link PickDamageReporting#storePhotos}: the key is inventory's evidence, deleted again
 * when the transaction that stored it rolls back.
 *
 * @param checksum the SHA-256 of the file, hex
 */
public record DamagePhoto(String storageKey, String originalName, String contentType, long sizeBytes,
                          String checksum) {

    public DamagePhoto {
        if (storageKey == null || storageKey.isBlank() || originalName == null || contentType == null
                || checksum == null || sizeBytes <= 0) {
            throw new IllegalArgumentException("A stored photo has a key, a name, a type, content and a checksum");
        }
    }
}
