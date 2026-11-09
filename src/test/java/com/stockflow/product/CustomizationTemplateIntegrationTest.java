package com.stockflow.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customization templates through HTTP (kltn-docs 01 §4.10): the print areas computed from the model, the refusals a
 * Sales Manager meets, one active template per variant, the reference die-line, and the storefront's P2 reading the
 * active template of a published product.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class CustomizationTemplateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired PrintConfigs printConfigs;
    @Autowired CatalogProjectionService projection;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private UUID productId;
    private UUID variantId;
    private String sku;
    private String base;

    @BeforeEach
    void customizableProduct() {
        UUID editor = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(editor, "test-" + editor, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        ProductSummary created = create(ProductKind.CUSTOMIZABLE);
        productId = created.productId();
        var variant = variants.list(productId, 0, 1).items().getFirst();
        variantId = variant.id();
        sku = variant.sku();
        base = "/api/v1/products/" + productId + "/variants/" + variantId + "/customization-templates";
    }

    private ProductSummary create(ProductKind kind) {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return products.create(new CreateProductCommand("TPL-" + suffix, "Ly giấy 12oz", "Paper cup 12oz", null, null,
                "Ly", null, null, TaxClass.STANDARD, kind, List.of()));
    }

    private static String cup(String code, double bleed, Long version) {
        return "{" + (code == null ? "" : "\"code\":\"" + code + "\",") + "\"name\":\"Ly 12oz - thân ly\","
                + "\"allowedFileFormats\":[\"image/png\",\"image/jpeg\",\"image/png\"],\"maxFileSizeMb\":20,"
                + "\"model\":{\"kind\":\"parametric-cup\",\"topDiameterMm\":90,\"bottomDiameterMm\":60,"
                + "\"heightMm\":110,\"rimMm\":3,\"seamMm\":8,\"bandTopOffsetMm\":6,\"bandBottomOffsetMm\":8,"
                + "\"material\":\"paper-white\"},"
                + "\"printAreas\":[{\"code\":\"BODY\",\"name\":\"Thân ly\",\"safeMarginMm\":3,\"bleedMm\":" + bleed
                + "}]" + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    void aCupTemplateIsCreatedInactiveWithItsWallComputedFromTheModel() throws Exception {
        JsonNode created = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                        .content(cup("CUP12-BODY", 2, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.minDpi").value(300))
                .andExpect(jsonPath("$.data.colorMode").value("CMYK"))
                .andExpect(jsonPath("$.data.allowedFileFormats.length()").value(2))
                .andExpect(jsonPath("$.data.model.kind").value("parametric-cup"))
                .andExpect(jsonPath("$.data.printAreas[0].code").value("BODY"))
                .andExpect(jsonPath("$.data.printAreas[0].shape.type").value("annular-sector"))
                .andExpect(jsonPath("$.data.printAreas[0].uvMapping.meshPart").value("body")));

        JsonNode wall = created.get("printAreas").get(0);
        assertThat(wall.get("shape").get("innerRadiusMm").asDouble()).isCloseTo(230.04, within(0.005));
        assertThat(wall.get("shape").get("sweepRad").asDouble()).isCloseTo(0.84894, within(0.000005));
        assertThat(wall.get("widthMm").asDouble()).isCloseTo(279.67, within(0.005));
        assertThat(printConfigs.ofVariant(sku, "CUP12-BODY")).hasValueSatisfying(config -> {
            assertThat(config.active()).isFalse();
            assertThat(config.printAreas()).hasSize(1);
        });

        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(cup("CUP12-BODY", 2, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_CODE_TAKEN"));
    }

    @Test
    void whatTheModelDoesNotHaveIsRefused() throws Exception {
        String bagWithThreeSides = "{\"code\":\"BAG-3\",\"name\":\"x\",\"allowedFileFormats\":[\"image/png\"],"
                + "\"maxFileSizeMb\":20,\"model\":{\"kind\":\"parametric-bag\",\"widthMm\":260,\"gussetMm\":120,"
                + "\"heightMm\":320,\"topFoldMm\":40,\"handle\":\"twisted\",\"material\":\"kraft-brown\"},"
                + "\"printAreas\":[{\"code\":\"FRONT\",\"name\":\"a\",\"safeMarginMm\":5,\"bleedMm\":3},"
                + "{\"code\":\"BACK\",\"name\":\"b\",\"safeMarginMm\":5,\"bleedMm\":3},"
                + "{\"code\":\"LEFT\",\"name\":\"c\",\"safeMarginMm\":5,\"bleedMm\":3}]}";
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(bagWithThreeSides))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_INVALID"));
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                        .content(cup("CUP12-BODY", 2, null).replace("parametric-cup", "gltf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_INVALID"));
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                        .content(cup("cup lower", 2, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_INVALID"));

        ProductSummary standard = create(ProductKind.STANDARD);
        UUID standardVariant = variants.list(standard.productId(), 0, 1).items().getFirst().id();
        mvc.perform(post("/api/v1/products/" + standard.productId() + "/variants/" + standardVariant
                        + "/customization-templates").contentType(MediaType.APPLICATION_JSON)
                        .content(cup("CUP12-BODY", 2, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_CUSTOMIZABLE"));
    }

    @Test
    void anUpdateRecomputesTheAreasAndAStaleOneIsRefused() throws Exception {
        JsonNode created = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(cup("CUP12-BODY", 2, null))).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        long version = created.get("version").asLong();

        mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(cup(null, 3, version + 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        JsonNode updated = data(mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(cup(null, 3, version))).andExpect(status().isOk()));

        assertThat(updated.get("code").asText()).isEqualTo("CUP12-BODY");
        assertThat(updated.get("printAreas")).hasSize(1);
        assertThat(updated.get("printAreas").get(0).get("bleedMm").asDouble()).isEqualTo(3);
        assertThat(updated.get("printAreas").get(0).get("widthMm").asDouble())
                .isEqualTo(created.get("printAreas").get(0).get("widthMm").asDouble());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.print_areas WHERE template_id = ?::uuid",
                Long.class, id)).isEqualTo(1);
        assertThat(updated.get("version").asLong()).isGreaterThan(version);
    }

    /**
     * Review of 2F-2: names and margins live in the print area rows, so an edit of only those used to leave the
     * template's version where it was - and a second editor holding that version overwrote it.
     */
    @Test
    void anEditOfOnlyTheMarginsMovesTheVersionSoAStaleEditorIsRefused() throws Exception {
        JsonNode created = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(cup("CUP12-BODY", 2, null))).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        long read = created.get("version").asLong();

        JsonNode first = data(mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(cup(null, 2, read).replace("\"safeMarginMm\":3", "\"safeMarginMm\":4")))
                .andExpect(status().isOk()));

        assertThat(first.get("version").asLong()).isGreaterThan(read);
        assertThat(first.get("printAreas").get(0).get("safeMarginMm").asDouble()).isEqualTo(4);
        mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(cup(null, 2, read)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
    }

    @Test
    void activatingATemplateRetiresTheVariantsActiveOne() throws Exception {
        JsonNode first = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(cup("CUP12-A", 2, null))).andExpect(status().isCreated()));
        JsonNode second = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(cup("CUP12-B", 2, null))).andExpect(status().isCreated()));

        mvc.perform(post(base + "/" + first.get("templateId").asText() + "/activation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + first.get("version") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
        mvc.perform(post(base + "/" + second.get("templateId").asText() + "/activation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + second.get("version") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        assertThat(printConfigs.ofVariant(sku, "CUP12-A")).hasValueSatisfying(c -> assertThat(c.active()).isFalse());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.customization_templates WHERE variant_id = ? "
                + "AND is_active", Long.class, variantId)).isEqualTo(1);

        // A product whose kind was changed afterwards can still retire its template.
        jdbc.update("UPDATE product.products SET kind = 'STANDARD' WHERE id = ?", productId);
        JsonNode active = data(mvc.perform(get(base + "/" + second.get("templateId").asText())));
        mvc.perform(post(base + "/" + second.get("templateId").asText() + "/deactivation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + active.get("version") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void aReferenceDieLineIsAttachedAndLinked() throws Exception {
        JsonNode created = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(cup("CUP12-BODY", 2, null))).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        var image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        mvc.perform(get(base + "/" + id + "/reference-file/download-url"))
                .andExpect(status().isNotFound());

        mvc.perform(multipart(base + "/" + id + "/reference-file")
                        .file(new MockMultipartFile("file", "khuon-ly.png", "image/png", png.toByteArray()))
                        .param("version", created.get("version").asText())
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceFileName").value("khuon-ly.png"));
        // The test profile stores files locally, which signs no browser links; the MinIO tests cover signing.
        assertThat(jdbc.queryForObject("SELECT reference_file_key FROM product.customization_templates WHERE id = "
                + "?::uuid", String.class, id)).startsWith("print-templates/");
    }

    @Test
    void theStorefrontReadsThePublishedProductsActiveTemplates() throws Exception {
        JsonNode created = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(cup("CUP12-BODY", 2, null))).andExpect(status().isCreated()));
        mvc.perform(post(base + "/" + created.get("templateId").asText() + "/activation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + created.get("version") + "}"))
                .andExpect(status().isOk());
        String slug = publish(productId, sku);

        mvc.perform(get("/api/v1/catalog/products/" + slug + "/customization"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.kind").value("CUSTOMIZABLE"))
                .andExpect(jsonPath("$.data.templates.length()").value(1))
                .andExpect(jsonPath("$.data.templates[0].sku").value(sku))
                .andExpect(jsonPath("$.data.templates[0].code").value("CUP12-BODY"))
                .andExpect(jsonPath("$.data.templates[0].printAreas[0].shape.type").value("annular-sector"));

        mvc.perform(get("/api/v1/catalog/products/no-such-product-" + UUID.randomUUID() + "/customization"))
                .andExpect(status().isNotFound());

        ProductSummary standard = create(ProductKind.STANDARD);
        String standardSku = variants.list(standard.productId(), 0, 1).items().getFirst().sku();
        String standardSlug = publish(standard.productId(), standardSku);
        mvc.perform(get("/api/v1/catalog/products/" + standardSlug + "/customization"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_CUSTOMIZABLE"));
    }

    /** Publishes a product straight in the source, as the catalogue search tests do, and returns its listing's slug. */
    private String publish(UUID product, String variantSku) {
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO product.product_categories (id, product_id, category_id, is_primary) "
                    + "VALUES (?, ?, md5('demo:cat:SOFA')::uuid, TRUE) ON CONFLICT DO NOTHING", UUID.randomUUID(),
                    product);
            jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE product_id = ?", product);
            jdbc.update("""
                    INSERT INTO product.media (id, variant_id, url, is_published, published_at, published_by, created_by)
                    SELECT ?, v.id, 'https://example.test/cup.jpg', TRUE, NOW(), md5('demo:user:approver')::uuid,
                           md5('demo:user:editor')::uuid::text
                    FROM product.variants v WHERE v.sku = ?""", UUID.randomUUID(), variantSku);
            jdbc.update("""
                    UPDATE product.products SET status = 'PUBLISHED', submitted_by = md5('demo:user:editor')::uuid,
                           submitted_at = NOW(), approved_by = md5('demo:user:approver')::uuid, approved_at = NOW(),
                           published_at = NOW(), seo_title = 'SEO', seo_description = 'SEO description'
                    WHERE id = ?""", product);
        });
        projection.project(product);
        return jdbc.queryForObject("SELECT slug FROM catalog.product_listing WHERE product_id = ?", String.class,
                product);
    }
}
