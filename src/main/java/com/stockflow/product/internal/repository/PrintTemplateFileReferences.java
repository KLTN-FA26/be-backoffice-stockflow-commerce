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
 * The reference die-lines and 3D models customization templates name, for the orphaned-file sweep: one replaced by a
 * newer upload, or stored by an attach that never committed, is named by no template and goes - unless, for a model, an
 * approved design's snapshot still names it (the design module claims those).
 */
@Component
class PrintTemplateFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    PrintTemplateFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.PRINT_TEMPLATE, FileCategory.PRINT_MODEL);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        if (category == FileCategory.PRINT_MODEL) {
            return new HashSet<>(jdbc.queryForList("""
                    SELECT model_file_key FROM product.customization_templates WHERE model_file_key IN (:keys)""",
                    Map.of("keys", keys), String.class));
        }
        if (category != FileCategory.PRINT_TEMPLATE) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT reference_file_key FROM product.customization_templates WHERE reference_file_key IN (:keys)""",
                Map.of("keys", keys), String.class));
    }
}
