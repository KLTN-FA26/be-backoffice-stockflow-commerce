package com.stockflow.procurement.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The files of purchasing records that {@code V20261113000200} archived, for the orphaned-file sweep: the documents
 * attached to purchase orders ({@link FileCategory#DOCUMENT}) and the photos receiving QC took
 * ({@link FileCategory#QC_EVIDENCE}).
 *
 * <p>Their rows left their tables for {@code platform.legacy_archive}, but both categories are still claimed by other
 * owners - production order slips, sales contracts and waybills; damage and receipt photos - so the sweep lists them,
 * and without this owner it would take every one of those files for an orphan and, with {@code delete=true}, delete
 * evidence the archive still points at. Supplier invoice scans need nothing: nobody claims their category, so the
 * sweep never lists it.</p>
 */
@Component
class ArchivedPurchasingFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    ArchivedPurchasingFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.DOCUMENT, FileCategory.QC_EVIDENCE);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        if (category == FileCategory.DOCUMENT) {
            return new HashSet<>(jdbc.queryForList("""
                    SELECT row_data ->> 'file_url' FROM platform.legacy_archive
                     WHERE source_table = 'procurement.purchase_order_attachments'
                       AND row_data ->> 'file_url' IN (:keys)""", Map.of("keys", keys), String.class));
        }
        if (category == FileCategory.QC_EVIDENCE) {
            return new HashSet<>(jdbc.queryForList("""
                    SELECT row_data ->> 'storage_key' FROM platform.legacy_archive
                     WHERE source_table = 'procurement.goods_receipt_photos'
                       AND row_data ->> 'storage_key' IN (:keys)""", Map.of("keys", keys), String.class));
        }
        return Set.of();
    }
}
