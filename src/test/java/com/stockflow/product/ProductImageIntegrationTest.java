package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageKeys;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductImageService;
import com.stockflow.product.internal.service.ProductMediaService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryDetails;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The product's own image (V20270106000100, ADR-0012): one per product, the face of its storefront card, public with
 * the product and with no second person; the variants' images stay the product page's.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.product-media.cloud-front-base-url=https://cdn.example.test")
class ProductImageIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductPublication publication;
    @Autowired ProductVariantService variants;
    @Autowired ProductMediaService media;
    @Autowired ProductImageService images;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    UUID editor;
    UUID approver;
    String suffix;

    @BeforeEach
    void people() {
        editor = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        actAs(editor);
    }

    void actAs(UUID user) {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
    }

    UUID createProduct() {
        var category = taxonomy.createCategory("CAT-" + suffix, null, new CategoryDetails("Ly", 0, null, null, null, true));
        var brand = taxonomy.createBrand("BR-" + suffix, "Brand " + suffix, null);
        return products.create(new CreateProductCommand("CUP-" + suffix, "Ly giấy 12oz", brand.id(),
                category.id(), "Ly in logo", null, TaxClass.STANDARD, List.of())).productId();
    }

    @Test
    @DisplayName("a draft's image is stored and shown in the back office, never copied to the CDN")
    void draftImageStaysPrivate() throws Exception {
        UUID productId = createProduct();

        var image = images.upload(productId, png("cup.png"), "  Mặt trước  ", editor);

        assertThat(image.altText()).isEqualTo("Mặt trước");
        assertThat(image.renditions()).isNotEmpty();
        assertThat(images.get(productId)).hasValueSatisfying(read -> {
            assertThat(read.original().key()).startsWith("product-originals/");
            assertThat(read.renditions()).hasSameSizeAs(image.renditions());
            // The test profile's storage signs no browser links (only S3/MinIO does), so none is promised here.
        });
        image.renditions().forEach(r ->
                assertThat(storage.exists(StorageKeys.publishedKeyOf(r.file().key()))).isFalse());
        assertThat(jdbc.queryForObject("""
                select count(*) from product.product_change_log
                where product_id = ? and change_type = 'UPDATED' and 'image' = any(changed_fields)""",
                Long.class, productId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("the card shows the product's image once published, by no second person, and the variants' without it")
    void cardShowsTheProductImage() throws Exception {
        UUID productId = createProduct();
        UUID variantId = variants.list(productId, 0, 1).items().getFirst().id();
        var variantImage = media.upload(productId, variantId, png("variant.png"), editor, null);
        products.submit(productId, editor);
        products.approve(productId, approver);
        actAs(approver);
        media.publish(productId, variantId, List.of(variantImage.id()), approver);
        actAs(editor);
        tx.executeWithoutResult(s -> jdbc.update(
                "update product.products set status = 'PUBLISHED', published_at = now() where id = ?", productId));
        String variantCard = publication.cards(Set.of(productId)).get(productId).primaryImageUrl();
        assertThat(variantCard).startsWith("https://cdn.example.test/");

        // Uploaded by the editor alone, on a published product: public at once.
        var first = images.upload(productId, png("cup.png"), null, editor);
        String largest = first.renditions().stream()
                .max(java.util.Comparator.comparingInt(r -> r.edge())).orElseThrow().file().key();
        String publicKey = StorageKeys.publishedKeyOf(largest);
        assertThat(storage.exists(publicKey)).isTrue();
        assertThat(publication.cards(Set.of(productId)).get(productId).primaryImageUrl())
                .isEqualTo("https://cdn.example.test/" + publicKey)
                .isNotEqualTo(variantCard);
        // The product page still leads with the variants' images.
        assertThat(publication.publishedImages(productId)).singleElement()
                .satisfies(i -> assertThat(i.id()).isEqualTo(variantImage.id()));

        // One image per product: a second upload replaces the first, whose private objects go after the commit.
        var second = images.upload(productId, png("cup-2.png"), null, editor);
        assertThat(images.get(productId)).hasValueSatisfying(read ->
                assertThat(read.original().key()).isEqualTo(second.original().key()));
        assertThat(storage.exists(first.original().key())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from product.products where id = ? and image_key is not null",
                Long.class, productId)).isEqualTo(1L);

        images.delete(productId);
        assertThat(images.get(productId)).isEmpty();
        assertThat(storage.exists(second.original().key())).isFalse();
        assertThat(publication.cards(Set.of(productId)).get(productId).primaryImageUrl()).isEqualTo(variantCard);
        images.delete(productId); // nothing left to take off: still fine
    }

    @Test
    @DisplayName("the alt text is edited on its own, and a product without an image has none to describe")
    void altText() throws Exception {
        UUID productId = createProduct();
        assertThatThrownBy(() -> images.describe(productId, "x"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
        images.upload(productId, png("cup.png"), "Cũ", editor);

        assertThat(images.describe(productId, "Mới").altText()).isEqualTo("Mới");
        assertThat(images.describe(productId, " ").altText()).isNull();
        assertThatThrownBy(() -> images.describe(productId, "x".repeat(256)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("a product under review takes no image")
    void pendingProductTakesNoImage() throws Exception {
        UUID productId = createProduct();
        products.submit(productId, editor);

        assertThatThrownBy(() -> images.upload(productId, png("cup.png"), null, editor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION));
        assertThat(images.get(productId)).isEmpty();
    }

    private static FileUpload png(String name) throws Exception {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        image.setRGB(10, 10, 0xff0000);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        return new FileUpload(name, "image/png", bytes.length, new ByteArrayInputStream(bytes));
    }
}
