package com.stockflow.design.internal.service;

import com.stockflow.common.storage.FileTransfers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE request BE-2: a page of designs carries each approved design's own SKU and each design's customer name, read
 * once for the page - never a snapshot or a customer read per row.
 */
class DraftViewsTest {

    private record Snapshot(UUID getDraftId, UUID getId) implements DesignSnapshotJpaRepository.SnapshotOf { }

    private final ProductService products = mock(ProductService.class);
    private final DesignDocumentJpaRepository documents = mock(DesignDocumentJpaRepository.class);
    private final DesignSnapshotJpaRepository snapshots = mock(DesignSnapshotJpaRepository.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final DraftViews views = new DraftViews(products, documents, mock(DesignArtifactJpaRepository.class),
            mock(FileTransfers.class), snapshots, customerSkus, customers);

    private static DesignDraftJpaEntity design(UUID customer, UUID product, DesignStatus status) {
        return new DesignDraftJpaEntity(UUID.randomUUID(), customer, product, "Ly", null, status);
    }

    @Test
    void aPageReadsTheSkusAndTheNamesOnceForAllItsDesigns() {
        UUID product = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        DesignDraftJpaEntity cup = design(customer, product, DesignStatus.APPROVED);
        DesignDraftJpaEntity mug = design(customer, product, DesignStatus.APPROVED);
        DesignDraftJpaEntity draft = design(other, product, DesignStatus.DRAFT);
        UUID cupSnapshot = UUID.randomUUID();
        UUID mugSnapshot = UUID.randomUUID();
        when(products.findById(product)).thenReturn(Optional.empty());
        when(documents.savedAt(any())).thenReturn(List.of());
        when(snapshots.snapshotsOf(anyCollection())).thenReturn(List.of(new Snapshot(cup.getId(), cupSnapshot),
                new Snapshot(mug.getId(), mugSnapshot)));
        when(customerSkus.ofSnapshots(anyCollection())).thenReturn(Map.of(
                cupSnapshot, sku(cupSnapshot, "LY12-0101-001"), mugSnapshot, sku(mugSnapshot, "LY12-0101-002")));
        when(customers.fullNames(anyCollection())).thenReturn(Map.of(customer, "Quán A", other, "Quán B"));

        var shown = views.of(List.of(cup, mug, draft));

        assertThat(shown).extracting(DesignWorkflowService.DraftView::customerSku)
                .containsExactly("LY12-0101-001", "LY12-0101-002", null);
        assertThat(shown).extracting(DesignWorkflowService.DraftView::customerName)
                .containsExactly("Quán A", "Quán A", "Quán B");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(snapshots, times(1)).snapshotsOf(asked.capture());
        assertThat(asked.getValue()).as("only an approved design has a SKU")
                .containsExactlyInAnyOrder(cup.getId(), mug.getId());
        verify(customerSkus, times(1)).ofSnapshots(anyCollection());
        verify(customerSkus, never()).ofSnapshot(any());
        verify(customers, times(1)).fullNames(anyCollection());
    }

    @Test
    void aPageWithNoApprovedDesignAsksForNoSku() {
        UUID product = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        when(products.findById(product)).thenReturn(Optional.empty());
        when(documents.savedAt(any())).thenReturn(List.of());
        when(customers.fullNames(anyCollection())).thenReturn(Map.of(customer, "Quán A"));

        var shown = views.of(design(customer, product, DesignStatus.PENDING_REVIEW));

        assertThat(shown.customerSku()).isNull();
        assertThat(shown.customerName()).isEqualTo("Quán A");
        verify(snapshots, never()).snapshotsOf(anyCollection());
        verify(customerSkus, never()).ofSnapshots(anyCollection());
    }

    private static CustomerSku sku(UUID snapshot, String code) {
        return new CustomerSku(UUID.randomUUID(), UUID.randomUUID(), code, "Ly", UUID.randomUUID(), snapshot,
                "LY12", "ACTIVE");
    }
}
