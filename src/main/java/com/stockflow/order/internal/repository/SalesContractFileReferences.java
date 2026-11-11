package com.stockflow.order.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import com.stockflow.order.internal.domain.SalesContractRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * The documents sales contracts keep (4B-4) - the PDF each was sent as and its signed file - for the orphaned-file sweep.
 * The {@code documents} category is shared with what other modules keep; the sweep asks every owner of a category, so a
 * file is kept while any of them names it.
 */
@Component
class SalesContractFileReferences implements StoredFileReferences {

    private final SalesContractRepository contracts;

    SalesContractFileReferences(SalesContractRepository contracts) {
        this.contracts = contracts;
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
        return contracts.referencedKeys(keys);
    }
}
