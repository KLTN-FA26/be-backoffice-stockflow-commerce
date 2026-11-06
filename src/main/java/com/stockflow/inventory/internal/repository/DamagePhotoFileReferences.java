package com.stockflow.inventory.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The photos a damage report keeps (on the shelf, at picking, at packing), for the orphaned-file sweep.
 */
@Component
class DamagePhotoFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    DamagePhotoFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return new HashSet<>(jdbc.queryForList("""
                SELECT storage_key FROM inventory.stock_damage_photo WHERE storage_key IN (:keys)""", Map.of("keys", keys), String.class));
    }
}
