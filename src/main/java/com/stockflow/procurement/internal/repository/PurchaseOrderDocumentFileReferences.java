package com.stockflow.procurement.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** The documents purchase orders keep, for the orphaned-file sweep. */
@Component
class PurchaseOrderDocumentFileReferences implements StoredFileReferences {

    private final PurchaseOrderAttachmentJpaRepository attachments;

    PurchaseOrderDocumentFileReferences(PurchaseOrderAttachmentJpaRepository attachments) {
        this.attachments = attachments;
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
        return new HashSet<>(attachments.storageKeysIn(keys));
    }
}
