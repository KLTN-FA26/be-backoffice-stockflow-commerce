package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The images a customer uploads into their design, through HTTP (kltn-docs 12 §4.2 step 5, BR-04): measured upright and
 * stored, only of the types and size the design's template takes, retried safely with an idempotency key, only into a
 * draft, at most 100 a design, only by the owner, without moving the design's version - and placeable in its layout.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class DesignAssetIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;
    /** The test profile stores files on disk, which cannot sign a link: only the signing is stood in for. */
    @MockitoSpyBean FileTransfers files;

    private UUID staff;
    private UUID owner;
    private String sku;
    private String templateId;
    private String design;

    @BeforeEach
    void aDraftOnACupTemplate() throws Exception {
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        as(staff);
        String code = "AST-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID productId = products.create(new CreateProductCommand(code, "Ly giấy 12oz", "Paper cup 12oz", null, null,
                "Ly", null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE, List.of())).productId();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.products SET status = 'APPROVED', submitted_by = ?, submitted_at = NOW(), "
                    + "approved_by = ?, approved_at = NOW() WHERE id = ?", ReferenceRows.user(entityManager),
                    ReferenceRows.user(entityManager), productId);
            jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE product_id = ?", productId);
        });
        var variant = variants.list(productId, 0, 1).items().getFirst();
        sku = variant.sku();
        String base = "/api/v1/products/" + productId + "/variants/" + variant.id() + "/customization-templates";
        JsonNode template = data(mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(
                "{\"code\":\"CUP12-BODY\",\"name\":\"Ly 12oz\",\"allowedFileFormats\":[\"image/png\",\"image/jpeg\"],"
                        + "\"maxFileSizeMb\":20,\"model\":{\"kind\":\"parametric-cup\",\"topDiameterMm\":90,"
                        + "\"bottomDiameterMm\":60,\"heightMm\":110,\"rimMm\":3,\"seamMm\":8,\"bandTopOffsetMm\":6,"
                        + "\"bandBottomOffsetMm\":8,\"material\":\"paper-white\"},\"printAreas\":[{\"code\":\"BODY\","
                        + "\"name\":\"Thân ly\",\"safeMarginMm\":3,\"bleedMm\":2}]}")).andExpect(status().isCreated()));
        templateId = template.get("templateId").asText();
        mvc.perform(post(base + "/" + templateId + "/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + template.get("version") + "}")).andExpect(status().isOk());
        owner = customerAccount();
        as(owner);
        design = data(mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku
                        + "\",\"templateCode\":\"CUP12-BODY\",\"name\":\"Ly quán A\"}"))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private void as(UUID user) {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
    }

    private UUID customerAccount() {
        return tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            UUID customer = ReferenceRows.customer(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", user, customer);
            return user;
        });
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private ResultActions upload(String designId, String name, String type, byte[] bytes, String key)
            throws Exception {
        var request = multipart("/api/v1/designs/" + designId + "/assets")
                .file(new MockMultipartFile("file", name, type, bytes));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?::uuid", Long.class, design);
    }

    @Test
    void anImageIsMeasuredStoredAndListedWithoutMovingTheDesignsVersion() throws Exception {
        byte[] png = image("png", 1200, 800);

        JsonNode asset = data(upload(design, "logo.png", "image/png", png, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.designId").value(design))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.widthPx").value(1200))
                .andExpect(jsonPath("$.data.heightPx").value(800))
                .andExpect(jsonPath("$.data.sizeBytes").value(png.length))
                .andExpect(jsonPath("$.data.checksum").value(Checksums.sha256Hex(png))));
        String id = asset.get("assetId").asText();
        assertThat(version()).isZero();
        doReturn(new DownloadLink("https://storage.example/signed", java.time.Instant.now().plusSeconds(300)))
                .when(files).viewLink(anyString());
        assertThat(jdbc.queryForObject("SELECT storage_key FROM design.design_asset WHERE id = ?::uuid", String.class,
                id)).startsWith("design-assets/");

        upload(design, "photo.jpg", "image/jpeg", image("jpg", 300, 200), null).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/designs/" + design + "/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].originalName").value("photo.jpg"))
                .andExpect(jsonPath("$.data[1].assetId").value(id));
        mvc.perform(get("/api/v1/designs/" + design + "/assets/" + id + "/download-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").exists());
        mvc.perform(get("/api/v1/designs/" + design + "/assets/" + UUID.randomUUID() + "/download-url"))
                .andExpect(status().isNotFound());

        // The uploaded image can now be placed in the layout.
        String layout = "{\"schemaVersion\":1,\"templateCode\":\"CUP12-BODY\",\"sku\":\"" + sku + "\",\"surfaces\":"
                + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[{\"id\":\"i1\",\"type\":\"image\","
                + "\"assetId\":\"" + id + "\",\"naturalWidthPx\":1200,\"naturalHeightPx\":800}]}}}";
        mvc.perform(put("/api/v1/designs/" + design + "/document").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"document\":" + layout + "}")).andExpect(status().isOk());
    }

    @Test
    void aRetryWithTheSameKeyAnswersWithTheSameImageAndAnotherFileIsRefused() throws Exception {
        byte[] png = image("png", 40, 30);
        String first = data(upload(design, "logo.png", "image/png", png, "logo-upload-0001")
                .andExpect(status().isCreated())).get("assetId").asText();

        upload(design, "logo.png", "image/png", png, "logo-upload-0001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetId").value(first));
        upload(design, "logo.png", "image/png", image("png", 41, 30), "logo-upload-0001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_asset WHERE draft_id = ?::uuid", Long.class,
                design)).isEqualTo(1);
    }

    @Test
    void onlyWhatTheTemplateTakesIsAccepted() throws Exception {
        // WebP is a design image in general, but this template takes PNG and JPEG only.
        upload(design, "logo.webp", "image/webp", new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'},
                null)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post("/api/v1/designs/" + design + "/assets/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"a.webp\",\"contentType\":\"image/webp\",\"sizeBytes\":10,"
                                + "\"sha256\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isUnsupportedMediaType());

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.customization_templates SET max_file_size_mb = 1 WHERE id = ?::uuid", templateId));
        var noise = new BufferedImage(700, 700, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 700; y++) {
            for (int x = 0; x < 700; x++) {
                noise.setRGB(x, y, ThreadLocalRandom.current().nextInt());
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", out);
        assertThat(out.size()).isGreaterThan(1024 * 1024);
        upload(design, "big.png", "image/png", out.toByteArray(), null)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));

        byte[] huge = image("png", 1, 1);
        java.nio.ByteBuffer.wrap(huge, 16, 8).putInt(8000).putInt(5001);
        upload(design, "huge.png", "image/png", huge, null)
                .andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_asset WHERE draft_id = ?::uuid", Long.class,
                design)).isZero();
    }

    @Test
    void onlyADraftOfItsOwnerTakesImagesAndAtMostOneHundred() throws Exception {
        byte[] png = image("png", 10, 10);

        as(customerAccount());
        upload(design, "logo.png", "image/png", png, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + design + "/assets")).andExpect(status().isNotFound());
        as(staff);
        upload(design, "logo.png", "image/png", png, null).andExpect(status().isNotFound());
        as(owner);

        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 100; i++) {
                UUID asset = Identifiers.newId();
                jdbc.update("INSERT INTO design.design_asset (draft_id, id, storage_key, original_name, content_type, "
                        + "size_bytes, checksum, width_px, height_px, created_at) VALUES (?::uuid, ?, "
                        + "'design-assets/2026/11/' || ? || '.png', 'a.png', 'image/png', 1, ?, 1, 1, NOW())",
                        design, asset, asset.toString(), "a".repeat(64));
            }
        });
        upload(design, "logo.png", "image/png", png, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_ASSET_LIMIT_REACHED"));

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE design.design_draft SET status = 'PENDING_REVIEW' WHERE id = ?::uuid", design));
        upload(design, "logo.png", "image/png", png, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        mvc.perform(post("/api/v1/designs/" + design + "/assets/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"a.png\",\"contentType\":\"image/png\",\"sizeBytes\":10,"
                                + "\"sha256\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        // Still readable while it waits.
        mvc.perform(get("/api/v1/designs/" + design + "/assets")).andExpect(jsonPath("$.data.length()").value(100));
    }

    @Test
    void anImageOfAnArchivedDesignIsGoneWithIt() throws Exception {
        String id = data(upload(design, "logo.png", "image/png", image("png", 10, 10), null)
                .andExpect(status().isCreated())).get("assetId").asText();
        mvc.perform(post("/api/v1/designs/" + design + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/designs/" + design + "/assets")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + design + "/assets/" + id + "/download-url"))
                .andExpect(status().isNotFound());
        // The row stays: images are append-only, history is kept.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_asset WHERE id = ?::uuid", Long.class, id))
                .isEqualTo(1);
    }
}
