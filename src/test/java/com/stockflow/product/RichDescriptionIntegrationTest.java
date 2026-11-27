package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.StorefrontProduct;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.DescriptionImageService;
import com.stockflow.product.internal.service.ProductAttributeService;
import com.stockflow.product.internal.domain.DescriptiveSpec;
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

import static com.stockflow.support.DemoData.demoId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Rich-text descriptions and the storefront page's facts (V20270106000300, ADR-0013): the editor's HTML is cleaned
 * before it is stored, a variant has its own, an image for a description is public at once, and the page shows the
 * brand, the category, the descriptive attributes and each variant with its options.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.product-media.cloud-front-base-url=https://cdn.example.test")
class RichDescriptionIntegrationTest {

    private static final UUID MATERIAL = demoId("demo:attr:MATERIAL");
    private static final UUID PAPER = demoId("demo:cups:attribute-value:MATERIAL:PAPER");

    @Autowired ProductService products;
    @Autowired ProductPublication publication;
    @Autowired ProductVariantService variants;
    @Autowired ProductAttributeService attributes;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired DescriptionImageService descriptionImages;
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

    @Test
    @DisplayName("the product's HTML is cleaned before it is stored, and the catalog reads only its text")
    void productDescriptionIsCleaned() {
        var created = products.create(new CreateProductCommand("RT-" + suffix, "Ly giấy", null, null, null,
                "<h2>Ly giấy</h2><p onclick=\"x()\">In <strong>logo</strong></p><script>x()</script>",
                TaxClass.STANDARD, List.of()));

        assertThat(created.description()).isEqualTo("<h2>Ly giấy</h2><p>In <strong>logo</strong></p>");
        assertThat(jdbc.queryForObject("select description from product.products where id = ?", String.class,
                created.productId())).isEqualTo("<h2>Ly giấy</h2><p>In <strong>logo</strong></p>");
        // What the catalog indexes for search: words, not markup.
        assertThat(publication.read(created.productId()).description()).isEqualTo("Ly giấy In logo");
    }

    @Test
    @DisplayName("the storefront page shows the HTML, brand, category, attributes and each variant with its own text")
    void storefrontPage() throws Exception {
        var category = taxonomy.createCategory("CAT-" + suffix, null,
                new CategoryDetails("Ly giấy", 0, null, null, null, true));
        var brand = taxonomy.createBrand("BR-" + suffix, "Brand " + suffix, null);
        UUID productId = products.create(new CreateProductCommand("RT-" + suffix, "Ly giấy 12oz", brand.id(),
                category.id(), null, "<p>Ly giấy <em>12oz</em></p>", TaxClass.STANDARD, List.of())).productId();
        attributes.replaceDescriptives(productId, List.of(new DescriptiveSpec(MATERIAL, 0, List.of(PAPER), null,
                null, null)));
        UUID variantId = variants.list(productId, 0, 1).items().getFirst().id();

        // An image for the description: public at once, and the only kind an <img> may show.
        var image = descriptionImages.upload(productId, png(), editor);
        assertThat(image.url()).startsWith("https://cdn.example.test/product-images/");
        assertThat(storage.exists(image.url().substring("https://cdn.example.test/".length()))).isTrue();

        var described = variants.describe(productId, variantId, "<p>Màu trắng</p><img src=\"" + image.url()
                + "\" alt=\"Ly\"><img src=\"https://tracker.test/p.gif\">");
        assertThat(described.description()).isEqualTo("<p>Màu trắng</p><img src=\"" + image.url() + "\" alt=\"Ly\">");

        products.submit(productId, editor);
        actAs(approver);
        products.approve(productId, approver); // approval puts the variants on sale
        actAs(editor);

        // Not a page until the product is published.
        assertThatThrownBy(() -> publication.storefront(productId)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        tx.executeWithoutResult(s -> jdbc.update(
                "update product.products set status = 'PUBLISHED', published_at = now() where id = ?", productId));

        StorefrontProduct page = publication.storefront(productId);
        assertThat(page.descriptionHtml()).isEqualTo("<p>Ly giấy <em>12oz</em></p>");
        assertThat(page.brand().name()).isEqualTo("Brand " + suffix);
        assertThat(page.category().id()).isEqualTo(category.id());
        assertThat(page.category().name()).isEqualTo("Ly giấy");
        assertThat(page.attributes()).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("MATERIAL");
            assertThat(a.values()).hasSize(1);
        });
        assertThat(page.variants()).singleElement().satisfies(v -> {
            assertThat(v.variantId()).isEqualTo(variantId);
            assertThat(v.ownDescription()).isTrue();
            assertThat(v.descriptionHtml()).startsWith("<p>Màu trắng</p>");
        });

        // Removing the variant's own description shows the product's.
        variants.describe(productId, variantId, "<p></p>");
        assertThat(publication.storefront(productId).variants()).singleElement().satisfies(v -> {
            assertThat(v.ownDescription()).isFalse();
            assertThat(v.descriptionHtml()).isEqualTo("<p>Ly giấy <em>12oz</em></p>");
        });
        assertThat(jdbc.queryForObject("""
                select count(*) from product.product_change_log
                where product_id = ? and change_type = 'VARIANT_UPDATED' and 'description' = any(changed_fields)""",
                Long.class, productId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("a product under review takes no description image and no variant description")
    void pendingProductTakesNoChange() throws Exception {
        var category = taxonomy.createCategory("CAT-" + suffix, null,
                new CategoryDetails("Ly", 0, null, null, null, true));
        var brand = taxonomy.createBrand("BR-" + suffix, "Brand " + suffix, null);
        UUID productId = products.create(new CreateProductCommand("RT-" + suffix, "Ly", brand.id(), category.id(),
                null, null, TaxClass.STANDARD, List.of())).productId();
        UUID variantId = variants.list(productId, 0, 1).items().getFirst().id();
        products.submit(productId, editor);

        assertThatThrownBy(() -> descriptionImages.upload(productId, png(), editor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION));
        assertThatThrownBy(() -> variants.describe(productId, variantId, "<p>x</p>"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION));
    }

    private static FileUpload png() throws Exception {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        image.setRGB(10, 10, 0xff0000);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        return new FileUpload("ly.png", "image/png", bytes.length, new ByteArrayInputStream(bytes));
    }
}
