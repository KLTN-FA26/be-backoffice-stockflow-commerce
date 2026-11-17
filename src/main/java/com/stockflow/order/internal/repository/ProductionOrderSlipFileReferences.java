package com.stockflow.order.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import com.stockflow.order.internal.domain.ProductionOrderSlipRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * The slips production orders were sent with (4A-3), for the orphaned-file sweep: without this the sweep would take a
 * slip for a file nobody names and delete what a producer was sent. The {@code documents} category is shared with what
 * other modules keep; the sweep asks every owner of a category, so a file is kept while any of them names it.
 */
@Component
class ProductionOrderSlipFileReferences implements StoredFileReferences {

    private final ProductionOrderSlipRepository slips;

    ProductionOrderSlipFileReferences(ProductionOrderSlipRepository slips) {
        this.slips = slips;
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
        return slips.referencedKeys(keys);
    }
}
