package com.stockflow.design.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The design files a draft revision or a confirmed snapshot names, and the images a customer uploaded into a design,
 * for the orphaned-file sweep. Every revision and every image row is kept, so only a file whose attaching transaction
 * never committed is an orphan.
 */
@Component
class DesignFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    DesignFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.DESIGN_RENDER, FileCategory.DESIGN_ASSET);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        if (category == FileCategory.DESIGN_ASSET) {
            return new HashSet<>(jdbc.queryForList(
                    "SELECT storage_key FROM design.design_asset WHERE storage_key IN (:keys)", Map.of("keys", keys),
                    String.class));
        }
        if (category != FileCategory.DESIGN_RENDER) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT storage_key FROM design.design_artifact WHERE storage_key IN (:keys)
                UNION
                SELECT artifact_url FROM design.design_snapshot WHERE artifact_url IN (:keys)""", Map.of("keys", keys), String.class));
    }
}
