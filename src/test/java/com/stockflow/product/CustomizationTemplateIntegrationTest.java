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
import com.stockflow.support.TemplateModelFiles;
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
 * Sales Manager meets, the {@code .glb} saved with the measurements generated from it, one active template per
 * variant, the reference die-line, and the storefront's P2 reading the active template of a published product.
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
                "Ly", null, null, TaxClass.STANDARD, List.of()));
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

    /** {@code POST base} with the template's {@code .glb}, each print area on its {@code print:<code>} mesh. */
    private org.springframework.test.web.servlet.ResultActions createWithFile(String templateJson) throws Exception {
        return mvc.perform(TemplateModelFiles.create(base, templateJson));
    }

    private org.springframework.test.web.servlet.ResultActions updateJson(String id, String templateJson)
            throws Exception {
        return mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(templateJson));
    }

    private org.springframework.test.web.servlet.ResultActions activate(String id, long version) throws Exception {
        return mvc.perform(post(base + "/" + id + "/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"));
    }

    @Test
    void aCupTemplateIsCreatedInactiveWithItsWallComputedFromTheModelAndItsFile() throws Exception {
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.minDpi").value(300))
                .andExpect(jsonPath("$.data.colorMode").value("CMYK"))
                .andExpect(jsonPath("$.data.allowedFileFormats.length()").value(2))
                .andExpect(jsonPath("$.data.model.kind").value("parametric-cup"))
                .andExpect(jsonPath("$.data.printAreas[0].code").value("BODY"))
                .andExpect(jsonPath("$.data.printAreas[0].shape.type").value("annular-sector"))
                .andExpect(jsonPath("$.data.printAreas[0].uvMapping.meshPart").value("body"))
                .andExpect(jsonPath("$.data.model3d.hasFile").value(true))
                .andExpect(jsonPath("$.data.model3d.fileName").value("model.glb"))
                .andExpect(jsonPath("$.data.model3d.surfaces[0].printAreaCode").value("BODY"))
                .andExpect(jsonPath("$.data.model3d.surfaces[0].meshName").value("print:BODY")));

        JsonNode wall = created.get("printAreas").get(0);
        assertThat(wall.get("shape").get("innerRadiusMm").asDouble()).isCloseTo(230.04, within(0.005));
        assertThat(wall.get("shape").get("sweepRad").asDouble()).isCloseTo(0.84894, within(0.000005));
        assertThat(wall.get("widthMm").asDouble()).isCloseTo(279.67, within(0.005));
        assertThat(created.get("model3d").get("fileChecksum").asText()).hasSize(64);
        assertThat(printConfigs.ofVariant(sku, "CUP12-BODY")).hasValueSatisfying(config -> {
            assertThat(config.active()).isFalse();
            assertThat(config.printAreas()).hasSize(1);
            assertThat(config.model3d().fileKey()).startsWith("print-models/");
        });

        createWithFile(cup("CUP12-BODY", 2, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_CODE_TAKEN"));
    }

    @Test
    void aTemplateIsNotCreatedWithoutItsFileOrWithAFileThatIsNotAGlb() throws Exception {
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(cup("CUP12-BODY", 2, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));
        mvc.perform(multipart(base).file(TemplateModelFiles.templatePart(cup("CUP12-BODY", 2, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));
        mvc.perform(multipart(base).file(TemplateModelFiles.templatePart(cup("CUP12-BODY", 2, null)))
                        .file(new MockMultipartFile("file", "model.glb", "application/octet-stream",
                                "PK this is a zip, not a glb".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));

        assertThat(printConfigs.ofVariant(sku, "CUP12-BODY")).isEmpty();
    }

    @Test
    void aTemplateWithAFileMapsEveryAreaToItsPrintMesh() throws Exception {
        String unmapped = cup("CUP12-BODY", 2, null).replace("}]}", "}],\"model3d\":{\"surfaces\":[]}}");
        createWithFile(unmapped)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_SURFACES_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("model3d.surfaces"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PrintAreaMissing"));
        String misnamed = cup("CUP12-BODY", 2, null)
                .replace("}]}", "}],\"model3d\":{\"surfaces\":[{\"printAreaCode\":\"BODY\",\"meshName\":\"Cup_Body\"}]}}");
        createWithFile(misnamed)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("model3d.surfaces[0].meshName"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("MeshNameMismatch"));

        assertThat(printConfigs.ofVariant(sku, "CUP12-BODY")).isEmpty();
    }

    @Test
    void whatTheModelDoesNotHaveIsRefused() throws Exception {
        String bagWithThreeSides = "{\"code\":\"BAG-3\",\"name\":\"x\",\"allowedFileFormats\":[\"image/png\"],"
                + "\"maxFileSizeMb\":20,\"model\":{\"kind\":\"parametric-bag\",\"widthMm\":260,\"gussetMm\":120,"
                + "\"heightMm\":320,\"topFoldMm\":40,\"handle\":\"twisted\",\"material\":\"kraft-brown\"},"
                + "\"printAreas\":[{\"code\":\"FRONT\",\"name\":\"a\",\"safeMarginMm\":5,\"bleedMm\":3},"
                + "{\"code\":\"BACK\",\"name\":\"b\",\"safeMarginMm\":5,\"bleedMm\":3},"
                + "{\"code\":\"LEFT\",\"name\":\"c\",\"safeMarginMm\":5,\"bleedMm\":3}]}";
        createWithFile(bagWithThreeSides)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_INVALID"));
        createWithFile(cup("CUP12-BODY", 2, null).replace("parametric-cup", "gltf"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_INVALID"));
        createWithFile(cup("cup lower", 2, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_INVALID"));
    }

    @Test
    void anUpdateRecomputesTheAreasAndAStaleOneIsRefused() throws Exception {
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null)).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        long version = created.get("version").asLong();

        updateJson(id, cup(null, 3, version + 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        JsonNode updated = data(updateJson(id, cup(null, 3, version)).andExpect(status().isOk()));

        assertThat(updated.get("code").asText()).isEqualTo("CUP12-BODY");
        assertThat(updated.get("printAreas")).hasSize(1);
        assertThat(updated.get("printAreas").get(0).get("bleedMm").asDouble()).isEqualTo(3);
        assertThat(updated.get("printAreas").get(0).get("widthMm").asDouble())
                .isEqualTo(created.get("printAreas").get(0).get("widthMm").asDouble());
        assertThat(updated.get("model3d").get("surfaces")).hasSize(1);
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
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null)).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        long read = created.get("version").asLong();

        JsonNode first = data(updateJson(id, cup(null, 2, read).replace("\"safeMarginMm\":3", "\"safeMarginMm\":4"))
                .andExpect(status().isOk()));

        assertThat(first.get("version").asLong()).isGreaterThan(read);
        assertThat(first.get("printAreas").get(0).get("safeMarginMm").asDouble()).isEqualTo(4);
        updateJson(id, cup(null, 2, read))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
    }

    /**
     * The measurements are generated from the {@code .glb}: without a new file they, the model's kind and the mesh map
     * stay as they are, while the Sales Manager's own values change freely; with a file everything changes together.
     */
    @Test
    void whatTheFileDeterminesChangesOnlyWithANewFile() throws Exception {
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null)).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        long version = created.get("version").asLong();

        updateJson(id, cup(null, 2, version).replace("\"topDiameterMm\":90", "\"topDiameterMm\":95"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));
        updateJson(id, TemplateModelFiles.withSurfaces(cup(null, 2, version)).replace("print:BODY", "print:BODY-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));
        // The saved values sent back - surfaces included, a little off below the stored hundredth - are no change.
        JsonNode renamed = data(updateJson(id, TemplateModelFiles.withSurfaces(cup(null, 2, version))
                        .replace("\"heightMm\":110", "\"heightMm\":110.001")
                        .replace("\"rimMm\":3", "\"rimMm\":4").replace("Ly 12oz - thân ly", "Ly 12oz mới"))
                .andExpect(status().isOk()));
        assertThat(renamed.get("name").asText()).isEqualTo("Ly 12oz mới");
        assertThat(renamed.get("model").get("rimMm").asDouble()).isEqualTo(4);

        // A refused file save changes nothing: the version a client holds is still good.
        long held = renamed.get("version").asLong();
        mvc.perform(TemplateModelFiles.updateWithFile(base + "/" + id, cup(null, 2, held)
                        .replace("}]", "}],\"model3d\":{\"surfaces\":[{\"printAreaCode\":\"BODY\","
                                + "\"meshName\":\"Body\"}]}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_SURFACES_INVALID"));

        JsonNode resized = data(mvc.perform(TemplateModelFiles.updateWithFile(base + "/" + id,
                        cup(null, 2, held).replace("\"topDiameterMm\":90", "\"topDiameterMm\":95")))
                .andExpect(status().isOk()));
        assertThat(resized.get("model").get("topDiameterMm").asDouble()).isEqualTo(95);
        assertThat(resized.get("printAreas").get(0).get("widthMm").asDouble())
                .isGreaterThan(created.get("printAreas").get(0).get("widthMm").asDouble());
        assertThat(resized.get("model3d").get("hasFile").asBoolean()).isTrue();
        assertThat(resized.get("version").asLong()).isGreaterThan(held);
    }

    @Test
    void theModelFileIsNoLongerReplacedOnItsOwn() throws Exception {
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null)).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();

        int answered = mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, base + "/" + id + "/model-file")
                        .file(TemplateModelFiles.glbPart())
                        .param("version", created.get("version").asText()))
                .andReturn().getResponse().getStatus();

        assertThat(answered).isIn(404, 405);
        assertThat(jdbc.queryForObject("SELECT model_file_key FROM product.customization_templates WHERE id = ?::uuid",
                String.class, id)).startsWith("print-models/");
    }

    @Test
    void activatingATemplateRetiresTheVariantsActiveOne() throws Exception {
        JsonNode first = data(createWithFile(cup("CUP12-A", 2, null)).andExpect(status().isCreated()));
        JsonNode second = data(createWithFile(cup("CUP12-B", 2, null)).andExpect(status().isCreated()));

        activate(first.get("templateId").asText(), first.get("version").asLong())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
        activate(second.get("templateId").asText(), second.get("version").asLong())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        assertThat(printConfigs.ofVariant(sku, "CUP12-A")).hasValueSatisfying(c -> assertThat(c.active()).isFalse());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.customization_templates WHERE variant_id = ? "
                + "AND is_active", Long.class, variantId)).isEqualTo(1);

        // A template can be retired whatever the product is doing.
        JsonNode active = data(mvc.perform(get(base + "/" + second.get("templateId").asText())));
        mvc.perform(post(base + "/" + second.get("templateId").asText() + "/deactivation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + active.get("version") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    /** Hikari runs with auto-commit off: a statement outside a transaction would never be seen by the request. */
    private void sql(String statement, Object... args) {
        tx.executeWithoutResult(status -> jdbc.update(statement, args));
    }

    /**
     * A template written before files were required has none, or a partial mesh map: it is still read and its free
     * values edited, but it is offered to customers only once it has its file and every area on its print mesh.
     */
    @Test
    void activationNeedsTheFileAndACompleteMeshMap() throws Exception {
        JsonNode created = data(createWithFile(cup("CUP12-LEGACY", 2, null)).andExpect(status().isCreated()));
        String id = created.get("templateId").asText();
        long version = created.get("version").asLong();
        sql("UPDATE product.customization_templates SET model_file_key = NULL, model_file_name = NULL, "
                + "model_file_checksum = NULL, model_surfaces_json = '[]'::jsonb WHERE id = ?::uuid", id);

        activate(id, version)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));
        // No model at all either (written before models were stored): the same 409, the same fix (FE 2026-11-17 B-2).
        String noModel = data(createWithFile(cup("CUP12-NOMODEL", 2, null)).andExpect(status().isCreated()))
                .get("templateId").asText();
        sql("UPDATE product.customization_templates SET model_params_json = NULL, model_file_key = NULL, "
                + "model_file_name = NULL, model_file_checksum = NULL, model_surfaces_json = '[]'::jsonb "
                + "WHERE id = ?::uuid", noModel);
        activate(noModel, jdbc.queryForObject("SELECT version FROM product.customization_templates WHERE id = ?::uuid",
                Long.class, noModel))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));
        mvc.perform(get(base + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model3d.hasFile").value(false));
        JsonNode renamed = data(updateJson(id, cup(null, 2, version).replace("Ly 12oz - thân ly", "Ly cũ"))
                .andExpect(status().isOk()));
        updateJson(id, cup(null, 2, renamed.get("version").asLong()).replace("\"heightMm\":110", "\"heightMm\":112"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_MODEL_FILE_REQUIRED"));

        JsonNode withFile = data(mvc.perform(TemplateModelFiles.updateWithFile(base + "/" + id,
                cup(null, 2, renamed.get("version").asLong()))).andExpect(status().isOk()));
        sql("UPDATE product.customization_templates SET model_surfaces_json = '[]'::jsonb WHERE id = ?::uuid",
                id);
        activate(id, withFile.get("version").asLong())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_SURFACES_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PrintAreaMissing"));
        sql("UPDATE product.customization_templates SET model_surfaces_json = "
                + "'[{\"printAreaCode\":\"BODY\",\"meshName\":\"print:BODY\"}]'::jsonb WHERE id = ?::uuid", id);
        activate(id, withFile.get("version").asLong())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void aReferenceDieLineIsAttachedAndLinked() throws Exception {
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null)).andExpect(status().isCreated()));
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
        JsonNode created = data(createWithFile(cup("CUP12-BODY", 2, null)).andExpect(status().isCreated()));
        activate(created.get("templateId").asText(), created.get("version").asLong())
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
