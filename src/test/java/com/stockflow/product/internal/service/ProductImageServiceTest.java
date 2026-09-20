package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.ProductImage;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductImageServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final ProductImageProcessor processor = mock(ProductImageProcessor.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final ProductImageService service = new ProductImageService(products, files, storage, processor,
            mock(com.stockflow.common.storage.UploadInspection.class), TransactionOperations.withoutTransaction());

    private Product draft() {
        return Product.draft("CUP", "Cup", "Cup", UUID.randomUUID(), null, null,
                "StockFlow", TaxClass.STANDARD, true, List.of(), null, null, null, null,
                null, null, null, null, null, false, false, null, false, null);
    }

    private FileUpload upload() {
        return new FileUpload("cup.png", "image/png", 12, new ByteArrayInputStream(new byte[12]));
    }

    @Test void uploadsUsingExistingCategoryAndPersistsKeyInsteadOfUrl() {
        var product = draft();
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        when(products.findForUpdate(product.id())).thenReturn(Optional.of(product));
        when(processor.prepare(any())).thenReturn(new ProductImageProcessor.Prepared(new byte[12], List.of()));
        var stored = new StoredFile("product-originals/cup.png", "cup.png", "image/png", 12, Instant.EPOCH);
        when(storage.store(eq(FileCategory.PRODUCT_IMAGE_ORIGINAL), any(), any(), anyLong(), any())).thenReturn(stored);
        var result = service.upload(product.id().value(), upload());
        assertThat(result.url()).isNull();
        assertThat(result.storedFile()).isEqualTo(stored);
        assertThat(product.images()).containsExactly(result);
        verify(products).save(product);
        verify(storage, never()).store(eq(FileCategory.PRODUCT_IMAGE), any(), any(), anyLong(), any());
    }

    @Test void refusesNonDraftAndMissingProductBeforeWritingStorage() {
        var product = draft();
        product.submit(UUID.randomUUID(), Instant.EPOCH);
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        assertThatThrownBy(() -> service.upload(product.id().value(), upload())).isInstanceOf(BusinessException.class);
        var absent = new ProductId(UUID.randomUUID());
        when(products.findById(absent)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(absent.value(), upload())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files, storage, processor);
    }

    @Test void refusesImageFromAnotherProductBeforeSigning() {
        var product = draft();
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        assertThatThrownBy(() -> service.downloadLink(product.id().value(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }

    @Test void legacyDetailsUpdateDoesNotLoseUploadedImageMetadata() {
        var product = draft();
        var image = new ProductImage(UUID.randomUUID(), null, 0,
                new StoredFile("product-images/cup.png", "cup.png", "image/png", 12, Instant.EPOCH));
        product.addImage(image);
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        when(products.categoryExists(product.categoryId())).thenReturn(true);
        when(products.save(product)).thenReturn(product);
        var details = new ProductServiceImpl(products, mock(ProductSearchRepository.class),
                mock(ProductEventPublisher.class), mock(com.stockflow.common.audit.AuditHistory.class),
                Clock.systemUTC());
        details.update(new UpdateProductCommand(product.id().value(), "Updated", "Updated",
                product.categoryId(), null, null, "StockFlow", TaxClass.STANDARD, true,
                List.of("https://example.com/legacy.png"), null, null, null, null,
                null, null, null, null, null, false, false, null, false, null));
        assertThat(product.images()).hasSize(2).contains(image);
        assertThat(product.images().stream().map(ProductImage::sortOrder).toList()).containsExactly(0, 1);
        assertThat(product.name()).isEqualTo("Updated");
    }

    @Test void uploadReplayReturnsSameAttachmentAndRejectsChangedBytes() throws Exception {
        var product = draft();
        var actor = UUID.randomUUID();
        var stored = new StoredFile("product-images/cup.png", "cup.png", "image/png", 12, Instant.EPOCH);
        var checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[12]));
        var original = new ProductImage(UUID.randomUUID(), null, 0, stored, List.of(), actor + ":image-request-1", checksum);
        product.addImage(original);
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        when(processor.prepare(any())).thenReturn(new ProductImageProcessor.Prepared(new byte[12], List.of()));
        assertThat(service.upload(product.id().value(), upload(), actor, "image-request-1")).isSameAs(original);
        when(processor.prepare(any())).thenReturn(new ProductImageProcessor.Prepared(new byte[]{1}, List.of()));
        assertThatThrownBy(() -> service.upload(product.id().value(), upload(), actor, "image-request-1"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode())
                        .isEqualTo(com.stockflow.common.error.ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verifyNoInteractions(files, storage);
    }

    @Test void databaseFailureAfterStoringDeletesEveryWrittenObject() {
        var product = draft();
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        when(products.findForUpdate(product.id())).thenReturn(Optional.of(product));
        when(products.save(product)).thenThrow(new IllegalStateException("db down"));
        when(processor.prepare(any())).thenReturn(new ProductImageProcessor.Prepared(new byte[12],
                List.of(new ProductImageProcessor.Rendered(256, 256, 256, "image/jpeg", new byte[3]))));
        when(storage.store(eq(FileCategory.PRODUCT_IMAGE_ORIGINAL), any(), any(), anyLong(), any()))
                .thenReturn(new StoredFile("product-originals/a.png", "cup.png", "image/png", 12, Instant.EPOCH));
        when(storage.store(eq(FileCategory.PRODUCT_RENDITION), any(), any(), anyLong(), any()))
                .thenReturn(new StoredFile("product-renditions/r.jpg", "display-256.jpg", "image/jpeg", 3, Instant.EPOCH));

        assertThatThrownBy(() -> service.upload(product.id().value(), upload()))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete("product-originals/a.png");
        verify(storage).delete("product-renditions/r.jpg");
        verify(storage, never()).store(eq(FileCategory.PRODUCT_IMAGE), any(), any(), anyLong(), any());
    }

    @Test void aConcurrentReplayFoundUnderTheLockDiscardsTheDuplicateObjects() throws Exception {
        var actor = UUID.randomUUID();
        var checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[12]));
        var winner = draft();
        var first = new ProductImage(UUID.randomUUID(), null, 0,
                new StoredFile("product-originals/first.png", "cup.png", "image/png", 12, Instant.EPOCH),
                List.of(), actor + ":image-request-2", checksum);
        winner.addImage(first);
        var beforeRace = winner;
        // The unlocked read sees no image yet; the locked read sees the racing request's image.
        var unlocked = draft();
        when(products.findById(beforeRace.id())).thenReturn(Optional.of(unlocked));
        when(products.findForUpdate(beforeRace.id())).thenReturn(Optional.of(winner));
        when(processor.prepare(any())).thenReturn(new ProductImageProcessor.Prepared(new byte[12], List.of()));
        when(storage.store(eq(FileCategory.PRODUCT_IMAGE_ORIGINAL), any(), any(), anyLong(), any()))
                .thenReturn(new StoredFile("product-originals/second.png", "cup.png", "image/png", 12, Instant.EPOCH));

        assertThat(service.upload(beforeRace.id().value(), upload(), actor, "image-request-2")).isSameAs(first);

        verify(storage, times(1)).delete("product-originals/second.png");
        verify(products, never()).save(any());
    }
}
