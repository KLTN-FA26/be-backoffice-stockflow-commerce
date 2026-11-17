package com.stockflow.procurement.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The photos goods receipts keep, for the orphaned-file sweep. {@link FileCategory#QC_EVIDENCE} is shared with
 * inventory's damage photos; the sweep takes a file as orphaned only when no owner of its category names it, so
 * without this owner every receipt photo would be.
 */
@Component
class GoodsReceiptPhotoFileReferences implements StoredFileReferences {

    private final GoodsReceiptPhotoJpaRepository photos;

    GoodsReceiptPhotoFileReferences(GoodsReceiptPhotoJpaRepository photos) {
        this.photos = photos;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.QC_EVIDENCE);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (category != FileCategory.QC_EVIDENCE || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(photos.storageKeysIn(keys));
    }
}
