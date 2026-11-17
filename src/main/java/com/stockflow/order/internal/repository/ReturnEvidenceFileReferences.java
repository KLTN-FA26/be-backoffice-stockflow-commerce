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
 * The photos a return keeps, for the orphaned-file sweep.
 */
@Component
class ReturnEvidenceFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    ReturnEvidenceFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.RETURN_EVIDENCE);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (category != FileCategory.RETURN_EVIDENCE || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT file_key FROM ordering.return_request_evidence WHERE file_key IN (:keys)""", Map.of("keys", keys), String.class));
    }
}
