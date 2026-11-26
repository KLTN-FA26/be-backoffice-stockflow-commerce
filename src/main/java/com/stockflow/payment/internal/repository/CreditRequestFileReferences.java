package com.stockflow.payment.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Payment's answer to the orphaned-file sweep for the documents kept with credit requests. */
@Component
class CreditRequestFileReferences implements StoredFileReferences {

    private final CreditRequestFileJpaRepository files;

    CreditRequestFileReferences(CreditRequestFileJpaRepository files) {
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
