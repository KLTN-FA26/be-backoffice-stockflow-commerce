package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.storage.FileCategory;
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

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductImageServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final ProductImageService service = new ProductImageService(products, files);

    private Product draft() {
        return Product.draft("CUP", "Cup", "Cup", UUID.randomUUID(), null, null,
                "StockFlow", TaxClass.STANDARD, true, List.of(), null, null, null, null);
    }

    private FileUpload upload() {
        return new FileUpload("cup.png", "image/png", 12, new ByteArrayInputStream(new byte[12]));
    }

    @Test void uploadsUsingExistingCategoryAndPersistsKeyInsteadOfUrl() {
        var product = draft();
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        var stored = new StoredFile("product-images/cup.png", "cup.png", "image/png", 12, Instant.EPOCH);
        when(files.store(eq(FileCategory.PRODUCT_IMAGE), any())).thenReturn(stored);
        var result = service.upload(product.id().value(), upload());
        assertThat(result.url()).isNull();
        assertThat(result.storedFile()).isEqualTo(stored);
        assertThat(product.images()).containsExactly(result);
        verify(products).save(product);
    }

    @Test void refusesNonDraftAndMissingProductBeforeWritingStorage() {
        var product = draft();
        product.submit(UUID.randomUUID(), Instant.EPOCH);
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        assertThatThrownBy(() -> service.upload(product.id().value(), upload())).isInstanceOf(BusinessException.class);
        var absent = new ProductId(UUID.randomUUID());
        when(products.findById(absent)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(absent.value(), upload())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
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
                mock(ProductEventPublisher.class), Clock.systemUTC());
        details.update(new UpdateProductCommand(product.id().value(), "Updated", "Updated",
                product.categoryId(), null, null, "StockFlow", TaxClass.STANDARD, true,
                List.of("https://example.com/legacy.png"), null, null, null, null));
        assertThat(product.images()).hasSize(2).contains(image);
        assertThat(product.images().stream().map(ProductImage::sortOrder).toList()).containsExactly(0, 1);
        assertThat(product.name()).isEqualTo("Updated");
    }
}
