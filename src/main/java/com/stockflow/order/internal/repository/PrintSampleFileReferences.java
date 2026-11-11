package com.stockflow.order.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The photos and videos print sample rounds keep (4A-4), for the orphaned-file sweep: an upload no round names - a
 * request that failed after its files were stored - is swept; one a round names never is.
 */
@Component
class PrintSampleFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    PrintSampleFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.PRINT_SAMPLE);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (category != FileCategory.PRINT_SAMPLE || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT storage_key FROM ordering.production_order_sample_media WHERE storage_key IN (:keys)""",
                Map.of("keys", keys), String.class));
    }
}
