package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ListProductsQuery;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.service.ProductMediaService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryDetails;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.product.internal.service.ProductVariantService.NewVariant;
import com.stockflow.product.internal.service.SkuInventoryControlService;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The product module on the PIM tables only (contract C1): a product is born with its default
 * variant and that SKU's inventory item; logistics live on the inventory item; approval puts the
 * variants on sale; images belong to a variant and reach the storefront through a second person.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.product-media.cloud-front-base-url=https://cdn.example.test")
class ProductPimIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductPublication publication;
    @Autowired ProductVariantService variants;
    @Autowired ProductMediaService media;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired SkuInventoryControlService skus;
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
        // The username ReferenceRows gave the user: the auditor records it as created_by.
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
    }

    ProductSummary create(UUID brand, UUID category) {
        return products.create(new CreateProductCommand("CUP-" + suffix, "Ly giấy 12oz", "Paper cup 12oz", brand,
                category, "Ly in logo", null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE));
    }

    @Test
    @DisplayName("create writes product.products, the default variant and its inventory item; nothing legacy")
    void createWritesThePimTables() {
        var brand = taxonomy.createBrand("BR-" + suffix, "Brand " + suffix, null);
        var root = taxonomy.createCategory("CAT-" + suffix, null, new CategoryDetails("Ly", 0, null, null, null, true));
        var child = taxonomy.createCategory("CAT-" + suffix + "-P", root.id(),
                new CategoryDetails("Ly giấy", 1, null, null, null, true));
        assertThat(child.path()).isEqualTo("/CAT-" + suffix + "/CAT-" + suffix + "-P");
        assertThat(child.depth()).isEqualTo(1);

        ProductSummary created = create(brand.id(), child.id());

        assertThat(created.code()).isEqualTo("CUP-" + suffix);
        assertThat(created.slug()).isEqualTo("cup-" + suffix.toLowerCase());
        assertThat(created.brandName()).isEqualTo("Brand " + suffix);
        assertThat(created.categoryId()).isEqualTo(child.id());
        assertThat(created.kind()).isEqualTo(ProductKind.CUSTOMIZABLE);
        assertThat(created.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(created.createdAt()).isNotNull();

        var list = variants.list(created.productId(), 0, 20).items();
        assertThat(list).singleElement().satisfies(v -> {
            assertThat(v.sku()).isEqualTo("CUP-" + suffix);
            assertThat(v.defaultVariant()).isTrue();
            assertThat(v.status()).isEqualTo(VariantStatus.DRAFT);
        });
        assertThat(jdbc.queryForObject("select count(*) from inventory.inventory_items where sku = ?", Long.class,
                "CUP-" + suffix)).isOne();
        assertThat(products.containsSku(created.productId(), "CUP-" + suffix)).isTrue();
        assertThat(products.nameForSku("cup-" + suffix)).contains("Ly giấy 12oz");

        assertThat(products.list(new ListProductsQuery(0, 20, "CUP-" + suffix, null, child.id(), brand.id(), null))
                .items()).extracting(ProductSummary::productId).containsExactly(created.productId());

        assertThatThrownBy(() -> create(null, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_CODE_ALREADY_EXISTS));
        assertThatThrownBy(() -> create(UUID.randomUUID(), null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND));

        var updated = products.update(new UpdateProductCommand(created.productId(), "Ly giấy 16oz", "Paper cup 16oz",
                null, root.id(), null, null, null, TaxClass.REDUCED, ProductKind.CUSTOMIZABLE));
        assertThat(updated.categoryId()).isEqualTo(root.id());
        assertThat(updated.brandId()).isNull();
        assertThat(updated.slug()).isEqualTo(created.slug());
        assertThat(jdbc.queryForObject("select count(*) from product.product_categories where product_id = ?",
                Long.class, created.productId())).isOne();
    }

    @Test
    @DisplayName("logistics are per SKU, on the inventory item, with optimistic versioning")
    void logisticsLiveOnTheInventoryItem() {
        var created = create(null, null);
        UUID skuId = variants.list(created.productId(), 0, 1).items().getFirst().id();
        var before = skus.logistics(created.productId(), skuId);
        assertThat(before.logistics().unitOfMeasure()).isEqualTo("EACH");

        var logistics = new ItemLogistics("EACH", "893" + suffix.replaceAll("[^0-9]", "1"),
                new BigDecimal("0.012"), new BigDecimal("8"), new BigDecimal("8"), new BigDecimal("10"),
                new BigDecimal("6.2"), new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("50"), 1, 50,
                StorageClass.FRAGILE, false, null, true);
        var after = skus.describe(created.productId(), skuId, before.version(), logistics);

        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(after.logistics().storageClass()).isEqualTo(StorageClass.FRAGILE);
        assertThat(after.logistics().packSize()).isEqualTo(50);
        assertThat(jdbc.queryForObject("select qc_required from inventory.inventory_items where sku = ?",
                Boolean.class, "CUP-" + suffix)).isTrue();
        assertThatThrownBy(() -> skus.describe(created.productId(), skuId, before.version(), logistics))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK));
    }

    @Test
    @DisplayName("approval puts the drafted variants on sale; a later variant is activated on its own")
    void approvalActivatesVariants() {
        var category = taxonomy.createCategory("CAT-" + suffix, null, new CategoryDetails("Ly", 0, null, null, null, true));
        var created = create(null, category.id());
        var extra = variants.add(created.productId(), new NewVariant("cup-" + suffix + "-16", "16oz", "SIZE=16OZ", null));
        assertThat(extra.sku()).isEqualTo("CUP-" + suffix + "-16");
        assertThat(extra.position()).isEqualTo(1);

        products.submit(created.productId(), editor);
        assertThatThrownBy(() -> variants.add(created.productId(), new NewVariant("X-" + suffix, "x", null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION));
        products.approve(created.productId(), approver);

        assertThat(variants.list(created.productId(), 0, 20).items())
                .extracting(ProductVariantService.VariantView::status).containsOnly(VariantStatus.ACTIVE);
        assertThat(publication.read(created.productId()).skus()).hasSize(2);

        var late = variants.add(created.productId(), new NewVariant("CUP-" + suffix + "-8", "8oz", "SIZE=8OZ", null));
        assertThat(late.status()).isEqualTo(VariantStatus.DRAFT);
        assertThat(variants.activate(created.productId(), late.id()).status()).isEqualTo(VariantStatus.ACTIVE);
        assertThatThrownBy(() -> variants.update(created.productId(), late.id(),
                new NewVariant("CUP-" + suffix + "-8B", "8oz", "SIZE=8OZ", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_VARIANT_TRANSITION));
        UUID defaultId = variants.list(created.productId(), 0, 20).items().stream()
                .filter(ProductVariantService.VariantView::defaultVariant).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> variants.obsolete(created.productId(), defaultId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_VARIANT_TRANSITION));
        assertThat(variants.obsolete(created.productId(), late.id()).obsoletedAt()).isNotNull();
    }

    @Test
    @DisplayName("an uploaded image reaches the storefront only when someone else publishes it")
    void imagesArePublishedByASecondPerson() throws Exception {
        var category = taxonomy.createCategory("CAT-" + suffix, null, new CategoryDetails("Ly", 0, null, null, null, true));
        var created = create(null, category.id());
        UUID productId = created.productId();
        UUID variantId = variants.list(productId, 0, 1).items().getFirst().id();

        var image = media.upload(productId, variantId, png(), editor, null);
        assertThat(image.primary()).isTrue();
        assertThat(image.published()).isFalse();
        assertThat(image.renditions()).isNotEmpty();
        var second = media.upload(productId, variantId, png(), editor, "upload-" + suffix);
        assertThat(media.upload(productId, variantId, png(), editor, "upload-" + suffix).id()).isEqualTo(second.id());
        assertThat(media.list(productId, variantId)).hasSize(2);

        // Not before the product is approved, and never by the uploader.
        actAs(approver);
        assertThatThrownBy(() -> media.publish(productId, variantId, List.of(image.id()), approver))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_APPROVED));
        products.submit(productId, editor);
        products.approve(productId, approver);
        actAs(editor);
        assertThatThrownBy(() -> media.publish(productId, variantId, List.of(image.id()), editor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED));

        actAs(approver);
        var published = media.publish(productId, variantId, List.of(image.id()), approver);
        assertThat(published).singleElement().satisfies(m -> {
            assertThat(m.published()).isTrue();
            assertThat(m.publishedBy()).isEqualTo(approver);
        });
        // The product itself is not published yet: the storefront sees nothing of it.
        assertThatThrownBy(() -> publication.publishedImages(productId)).isInstanceOf(BusinessException.class);

        // Publication itself is the catalog's (CatalogManagementController); here only its effect matters.
        tx.executeWithoutResult(s -> jdbc.update(
                "update product.products set status = 'PUBLISHED', published_at = now() where id = ?", productId));
        assertThat(publication.publishedImages(productId)).singleElement()
                .satisfies(i -> assertThat(i.url()).startsWith("https://cdn.example.test/"));
        var gallery = media.publicGallery(productId, null);
        assertThat(gallery.sku()).isEqualTo("CUP-" + suffix);
        assertThat(gallery.images()).singleElement().satisfies(i -> {
            assertThat(i.imageId()).isEqualTo(image.id());
            assertThat(i.renditions()).allSatisfy(r -> assertThat(r.url()).startsWith("https://cdn.example.test/"));
        });

        var cover = media.describe(productId, variantId, second.id(), "Mặt trước", 5, true);
        assertThat(cover.primary()).isTrue();
        assertThat(media.get(productId, variantId, image.id()).primary()).isFalse();
        media.delete(productId, variantId, second.id());
        assertThat(media.get(productId, variantId, image.id()).primary()).isTrue();
        assertThat(media.withdraw(productId, variantId, image.id()).published()).isFalse();
        assertThatThrownBy(() -> media.publicGallery(productId, null)).isInstanceOf(BusinessException.class);
    }

    private static FileUpload png() throws Exception {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        image.setRGB(10, 10, 0xff0000);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        return new FileUpload("cup.png", "image/png", bytes.length, new ByteArrayInputStream(bytes));
    }
}
