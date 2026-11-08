package com.stockflow.common.storage;

import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every module's answer to the orphaned-file sweep runs against the real schema, and the categories it claims are the
 * ones whose files it owns - none of the never-swept ones.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StoredFileReferencesIntegrationTest {

    @Autowired List<StoredFileReferences> owners;
    @Autowired OrphanedFileSweeper sweeper;

    @Test
    void eachOwnerQueriesItsOwnTablesAndClaimsOnlyItsCategories() {
        Set<FileCategory> claimed = EnumSet.noneOf(FileCategory.class);
        for (StoredFileReferences owner : owners) {
            for (FileCategory category : owner.categories()) {
                claimed.add(category);
                assertThat(owner.referenced(category, List.of(category.prefix() + "/2026/01/01/nothing.jpg")))
                        .as("%s answers for %s", owner.getClass().getSimpleName(), category).isEmpty();
            }
        }
        assertThat(claimed).containsExactlyInAnyOrder(FileCategory.DESIGN_RENDER, FileCategory.PRODUCT_IMAGE_ORIGINAL,
                FileCategory.RETURN_EVIDENCE, FileCategory.QC_EVIDENCE, FileCategory.DOCUMENT,
                FileCategory.PRINT_TEMPLATE, FileCategory.DESIGN_ASSET);
        assertThat(claimed).doesNotContain(FileCategory.SUPPLIER_INVOICE, FileCategory.PRODUCT_IMAGE,
                FileCategory.EXPORT, FileCategory.EVIDENCE_COPY);
    }

    @Test
    void aSweepOverTheTestStorageRunsEndToEndWithoutDeleting() {
        var report = sweeper.sweep();
        assertThat(report.deleted()).as("report-only by default").isZero();
    }
}
