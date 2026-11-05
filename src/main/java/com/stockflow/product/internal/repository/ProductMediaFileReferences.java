package com.stockflow.product.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The private originals of the product images a variant still shows, for the orphaned-file sweep: an image removed
 * from its gallery leaves its original behind, which is the file the sweep removes.
 */
@Component
class ProductMediaFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    ProductMediaFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.PRODUCT_IMAGE_ORIGINAL);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (category != FileCategory.PRODUCT_IMAGE_ORIGINAL || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT storage_key FROM product.media WHERE storage_key IN (:keys)""", Map.of("keys", keys), String.class));
    }
}
