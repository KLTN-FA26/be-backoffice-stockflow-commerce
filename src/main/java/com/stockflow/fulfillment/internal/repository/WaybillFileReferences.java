package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The waybill documents loads keep, for the orphaned-file sweep. The {@code documents} category is shared with the
 * documents other modules keep; the sweep asks every owner of a category, so a file is kept while any of them names it.
 */
@Component
class WaybillFileReferences implements StoredFileReferences {

    private final LoadWaybillFileJpaRepository files;

    WaybillFileReferences(LoadWaybillFileJpaRepository files) {
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
