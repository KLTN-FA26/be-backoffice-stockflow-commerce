package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.GalleryItem;
import com.stockflow.product.internal.entity.ProductGalleryJpaEntity;
import com.stockflow.product.internal.entity.ProductImageJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.entity.SkuJpaEntity;
import com.stockflow.product.internal.entity.VariantGalleryJpaEntity;
import com.stockflow.product.internal.repository.ProductGalleryJpaRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.SkuJpaRepository;
import com.stockflow.product.internal.repository.VariantGalleryJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductGalleryServiceTest {
    @Test void editDoesNotChangePublishedGalleryAndApprovalRequiresAnotherActor() {
        var products = mock(ProductJpaRepository.class);
        var galleries = mock(ProductGalleryJpaRepository.class);
        var files = mock(FileTransfers.class);
        var service = new ProductGalleryService(products, galleries, files,
                mock(VariantGalleryJpaRepository.class), mock(VariantJpaRepository.class),
                mock(SkuJpaRepository.class), new ProductMediaProperties("https://media.example.com"));
        var id = UUID.randomUUID(); var editor = UUID.randomUUID(); var approver = UUID.randomUUID();
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        var product = mock(ProductJpaEntity.class);
        when(product.getStatus()).thenReturn(ProductStatus.PUBLISHED);
        var available = List.of(image(first), image(second));
        when(product.getImages()).thenReturn(available);
        when(products.lockById(id)).thenReturn(Optional.of(product));
        var gallery = new ProductGalleryJpaEntity(id);
        gallery.edit(List.of(new GalleryItem(first, "Original")), editor);
        gallery.publish(approver);
        when(galleries.findById(id)).thenReturn(Optional.of(gallery));
        when(galleries.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var edited = service.edit(id, editor, 2, List.of(new GalleryItem(second, "Replacement")));
        assertThat(edited.published().getFirst().imageId()).isEqualTo(first);
        assertThatThrownBy(() -> service.approve(id, editor, edited.revision())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.approve(id, approver, 2)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
        assertThat(service.approve(id, approver, edited.revision()).published().getFirst().imageId()).isEqualTo(second);
        verify(files).publish("product-renditions/" + second + ".png");
        verify(files, never()).publish("product-renditions/" + first + ".png");
    }

    @Test void publicVariantGalleryOverridesAndOtherwiseInheritsTheApprovedProductGallery() {
        var products = mock(ProductJpaRepository.class);
        var galleries = mock(ProductGalleryJpaRepository.class);
        var variantGalleries = mock(VariantGalleryJpaRepository.class);
        var variants = mock(VariantJpaRepository.class);
        var skus = mock(SkuJpaRepository.class);
        var service = new ProductGalleryService(products, galleries, mock(FileTransfers.class),
                variantGalleries, variants, skus,
                new ProductMediaProperties("https://media.example.com"));
        var productId = UUID.randomUUID();
        var variantId = UUID.randomUUID();
        var productImage = UUID.randomUUID();
        var variantImage = UUID.randomUUID();
        var product = mock(ProductJpaEntity.class);
        when(product.getStatus()).thenReturn(ProductStatus.PUBLISHED);
        var productImageRow = image(productImage);
        var variantImageRow = image(variantImage);
        when(product.getImages()).thenReturn(List.of(productImageRow, variantImageRow));
        when(products.findByIdWithImages(productId)).thenReturn(Optional.of(product));
        when(variants.existsByIdAndProductId(variantId, productId)).thenReturn(true);
        when(skus.findByCode("SKU-BLUE")).thenReturn(Optional.of(
                new SkuJpaEntity(UUID.randomUUID(), variantId, "SKU-BLUE", null, false, "EA")));
        var productGallery = new ProductGalleryJpaEntity(productId);
        productGallery.edit(List.of(new GalleryItem(productImage, "Default")), UUID.randomUUID());
        productGallery.publish(UUID.randomUUID());
        when(galleries.findById(productId)).thenReturn(Optional.of(productGallery));

        var inherited = service.publicGallery(productId, "SKU-BLUE");
        assertThat(inherited.variantId()).isEqualTo(variantId);
        assertThat(inherited.images().getFirst().imageId()).isEqualTo(productImage);
        assertThat(inherited.images().getFirst().renditions().getFirst().url())
                .isEqualTo("https://media.example.com/product-images/" + productImage + ".png");

        var override = new VariantGalleryJpaEntity(variantId, productId);
        override.edit(List.of(new GalleryItem(variantImage, "Blue")), UUID.randomUUID());
        override.publish(UUID.randomUUID());
        when(variantGalleries.findById(variantId)).thenReturn(Optional.of(override));
        assertThat(service.publicGallery(productId, "SKU-BLUE").images().getFirst().imageId())
                .isEqualTo(variantImage);
    }

    private ProductImageJpaEntity image(UUID id) {
        var image = mock(ProductImageJpaEntity.class);
        var original = new com.stockflow.common.storage.StoredFile("product-originals/" + id + ".png", "image.png",
                "image/png", 20, java.time.Instant.EPOCH);
        var display = new com.stockflow.common.storage.StoredFile("product-renditions/" + id + ".png", "display-256.png",
                "image/png", 10, java.time.Instant.EPOCH);
        when(image.getId()).thenReturn(id);
        when(image.storedFile()).thenReturn(original);
        when(image.getRenditions()).thenReturn(List.of(new com.stockflow.product.internal.domain.ImageRendition(256, 256, 256, display)));
        return image;
    }
}
