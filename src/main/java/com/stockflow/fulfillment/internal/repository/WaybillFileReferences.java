package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The waybill documents loads keep, and the waybill files imported (3-3), for the orphaned-file sweep. The {@code documents} category is shared with the
 * documents other modules keep; the sweep asks every owner of a category, so a file is kept while any of them names it.
 */
@Component
class WaybillFileReferences implements StoredFileReferences {

    private final LoadWaybillFileJpaRepository files;
    private final WaybillImportJpaRepository imports;

    WaybillFileReferences(LoadWaybillFileJpaRepository files, WaybillImportJpaRepository imports) {
        this.files = files;
        this.imports = imports;
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
        // An imported file is kept by its import even when no line applied to a load.
        Set<String> kept = new HashSet<>(files.storageKeysIn(keys));
        kept.addAll(imports.storageKeysIn(keys));
        return kept;
    }
}
