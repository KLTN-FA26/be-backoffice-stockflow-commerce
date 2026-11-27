package com.stockflow.design.internal.service;

import com.stockflow.common.storage.FileTransfers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.identity.api.IdentityService;
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
    private final com.stockflow.inventory.api.UomConversionService units =
            mock(com.stockflow.inventory.api.UomConversionService.class);
    private final DesignProjectJpaRepository projects = mock(DesignProjectJpaRepository.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final DraftViews views = new DraftViews(products, documents, mock(DesignArtifactJpaRepository.class),
            mock(FileTransfers.class), snapshots, customerSkus, customers, units, projects, identities);

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
        // The cup is drawn on a variant with a carton of 1,000, the mug on one with no carton; the draft on none yet.
        cup.drawOn(UUID.randomUUID(), "LY12-TRANG", UUID.randomUUID(), "T1");
        mug.drawOn(UUID.randomUUID(), "LY12-KRAFT", UUID.randomUUID(), "T2");
        UUID cupSnapshot = UUID.randomUUID();
        UUID mugSnapshot = UUID.randomUUID();
        when(products.findById(product)).thenReturn(Optional.empty());
        when(documents.savedAt(any())).thenReturn(List.of());
        when(snapshots.snapshotsOf(anyCollection())).thenReturn(List.of(new Snapshot(cup.getId(), cupSnapshot),
                new Snapshot(mug.getId(), mugSnapshot)));
        when(customerSkus.ofSnapshots(anyCollection())).thenReturn(Map.of(
                cupSnapshot, sku(cupSnapshot, "LY12-0101-001"), mugSnapshot, sku(mugSnapshot, "LY12-0101-002")));
        when(customers.fullNames(anyCollection())).thenReturn(Map.of(customer, "Quán A", other, "Quán B"));
        // R5-13: the carton is the variant's (owner decision Q1): the cup's variant has one of 1,000, the mug's none.
        when(units.conversionsOf(anyCollection())).thenReturn(Map.of(
                "LY12-TRANG", List.of(unit("LY12-TRANG", "CTN", 1000)),
                "LY12-KRAFT", List.<com.stockflow.inventory.api.UomConversion>of()));

        var shown = views.of(List.of(cup, mug, draft));

        assertThat(shown).extracting(DesignWorkflowService.DraftView::unitsPerCarton)
                .as("a design reads its variant's carton, approved or not; one without a carton, or on no variant, none")
                .containsExactly(1000, null, null);
        verify(units, times(1)).conversionsOf(anyCollection());

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

    @Test
    void aPageNamesItsProjectsAndReviewersInOneReadEach() {
        UUID product = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        DesignProjectJpaEntity project = new DesignProjectJpaEntity(customer, UUID.randomUUID(), "Tết 2027", null);
        DesignDraftJpaEntity returned = design(customer, product, DesignStatus.DRAFT);
        returned.joinProject(project.getId());
        returned.returnToDraft(reviewer, "Logo bị mờ", java.time.Instant.now());
        DesignDraftJpaEntity alsoInIt = design(customer, product, DesignStatus.DRAFT);
        alsoInIt.joinProject(project.getId());
        DesignDraftJpaEntity alone = design(customer, product, DesignStatus.DRAFT);
        when(products.findById(product)).thenReturn(Optional.empty());
        when(documents.savedAt(any())).thenReturn(List.of());
        when(customers.fullNames(anyCollection())).thenReturn(Map.of());
        when(projects.findAllById(any())).thenReturn(List.of(project));
        when(identities.displayNames(anyCollection())).thenReturn(Map.of(reviewer, "Trần Điều Phối"));

        var shown = views.of(List.of(returned, alsoInIt, alone));

        assertThat(shown).extracting(DesignWorkflowService.DraftView::projectName)
                .containsExactly("Tết 2027", "Tết 2027", null);
        assertThat(shown).extracting(DesignWorkflowService.DraftView::projectStatus)
                .containsExactly("ACTIVE", "ACTIVE", null);
        assertThat(shown).extracting(DesignWorkflowService.DraftView::reviewedByName)
                .containsExactly("Trần Điều Phối", null, null);
        verify(projects, times(1)).findAllById(any());
        verify(identities, times(1)).displayNames(anyCollection());
    }

    @Test
    void aPageInNoProjectAndNeverReviewedReadsNeither() {
        UUID product = UUID.randomUUID();
        when(products.findById(product)).thenReturn(Optional.empty());
        when(documents.savedAt(any())).thenReturn(List.of());
        when(customers.fullNames(anyCollection())).thenReturn(Map.of());

        var shown = views.of(design(UUID.randomUUID(), product, DesignStatus.DRAFT));

        assertThat(shown.projectName()).isNull();
        assertThat(shown.reviewedByName()).isNull();
        verify(projects, never()).findAllById(any());
        verify(identities, never()).displayNames(anyCollection());
    }

    private static com.stockflow.inventory.api.UomConversion unit(String sku, String code, int factor) {
        return new com.stockflow.inventory.api.UomConversion(UUID.randomUUID(), sku, code, factor, null, null,
                null, null, true, 0);
    }

    private static CustomerSku sku(UUID snapshot, String code) {
        return new CustomerSku(UUID.randomUUID(), UUID.randomUUID(), code, "Ly", UUID.randomUUID(), snapshot,
                UUID.randomUUID(), "LY12", "ACTIVE");
    }
}
