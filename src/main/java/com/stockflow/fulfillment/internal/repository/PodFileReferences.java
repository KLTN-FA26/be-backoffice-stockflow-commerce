package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Fulfillment's answer to the orphaned-file sweep for proofs of delivery (3-6), kept as {@code documents}. */
@Component
class PodFileReferences implements StoredFileReferences {

    private final ShipmentPodFileJpaRepository files;

    PodFileReferences(ShipmentPodFileJpaRepository files) {
        this.files = files;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.DOCUMENT);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (category != FileCategory.DOCUMENT || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(files.storageKeysIn(keys));
    }
}
