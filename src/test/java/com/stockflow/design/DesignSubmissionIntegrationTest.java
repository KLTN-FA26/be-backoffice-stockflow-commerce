package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.contracts.DesignSubmittedForReview;
import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer submits their design (kltn-docs 12 §4.3), over HTTP: the server's preflight refuses with each finding
 * located and records the failure without moving the version; the three files must be rendered from the layout as it
 * is; passing, the design is locked, waits in the Sales Manager's queue, and the reviewers are told.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
class DesignSubmissionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired PrintConfigs printConfigs;
    @Autowired UomConversionService units;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired RecordedEvents.Recorder recorded;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private UUID staff;
    private UUID owner;
    private String sku;
    private String design;
    private String templateId;
    private PrintSurface surface;

    @BeforeEach
    void aDraftOnACupTemplateWithACartonSpec() throws Exception {
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        as(staff);
        String code = "SUB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
                "{\"code\":\"CUP12-BODY\",\"name\":\"Ly 12oz\",\"allowedFileFormats\":[\"image/png\"],"
                        + "\"maxFileSizeMb\":20,\"model\":{\"kind\":\"parametric-cup\",\"topDiameterMm\":90,"
                        + "\"bottomDiameterMm\":60,\"heightMm\":110,\"rimMm\":3,\"seamMm\":8,\"bandTopOffsetMm\":6,"
                        + "\"bandBottomOffsetMm\":8,\"material\":\"paper-white\"},\"printAreas\":[{\"code\":\"BODY\","
                        + "\"name\":\"Thân ly\",\"safeMarginMm\":3,\"bleedMm\":2}]}")).andExpect(status().isCreated()));
        templateId = template.get("templateId").asText();
        mvc.perform(post(base + "/" + templateId + "/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + template.get("version") + "}")).andExpect(status().isOk());
        var config = printConfigs.byId(UUID.fromString(templateId)).orElseThrow();
        surface = PrintSurface.of(config.model(), config.printAreas().get(0));
        // The carton a customer orders in (B25): without it the design cannot be submitted.
        units.add(new Sku(sku), new UomConversionDraft("CARTON", 50, new BigDecimal("4.2"), new BigDecimal("60"),
                new BigDecimal("40"), new BigDecimal("35"), false, true, true));
        owner = tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            UUID customer = ReferenceRows.customer(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", user, customer);
            return user;
        });
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

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private long version() throws Exception {
        return data(mvc.perform(get("/api/v1/designs/" + design))).get("version").asLong();
    }

    private String uploadLogo(int px) throws Exception {
        return data(mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png(px, px))))
                .andExpect(status().isCreated())).get("assetId").asText();
    }

    /** Saves a layout with one 30 mm logo at the front of the cup; returns the layout's checksum. */
    private String saveLogoLayout(String assetId) throws Exception {
        double u = (surface.uFrom() + surface.uTo()) / 2;
        double v = (surface.vFrom() + surface.vTo()) / 2;
        String layout = "{\"schemaVersion\":1,\"templateCode\":\"CUP12-BODY\",\"sku\":\"" + sku + "\",\"surfaces\":"
                + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[{\"id\":\"logo\",\"type\":\"image\","
                + "\"assetId\":\"" + assetId + "\",\"naturalWidthPx\":1,\"naturalHeightPx\":1,\"widthMm\":30,"
                + "\"heightMm\":30,\"placement\":{\"u\":" + u + ",\"v\":" + v + ",\"rotationDeg\":0,\"scale\":1}}]}}}";
        return data(mvc.perform(put("/api/v1/designs/" + design + "/document").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version() + ",\"document\":" + layout + "}"))
                .andExpect(status().isOk())).get("checksum").asText();
    }

    /** A PDF of one page {@code widthMm} x {@code heightMm}, as the studio renders a print area or a spec sheet. */
    private static byte[] pdf(double widthMm, double heightMm) throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(
                    (float) (widthMm * 72 / 25.4), (float) (heightMm * 72 / 25.4))));
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void uploadFile(String role, String name, String type, byte[] bytes, String checksum) throws Exception {
        mvc.perform(multipart("/api/v1/designs/" + design + "/artifacts")
                        .file(new MockMultipartFile("file", name, type, bytes))
                        .param("role", role).param("documentChecksum", checksum))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentChecksum").value(checksum));
    }

    /** The three files the studio renders on submit, each naming {@code checksum}: the print file one page per area. */
    private void uploadFiles(String checksum) throws Exception {
        uploadFile("CUSTOMER_PREVIEW", "preview.png", "image/png", png(8, 8), checksum);
        uploadFile("PRINT_READY", "print.pdf", "application/pdf", pdf(surface.flatWidth(), surface.flatHeight()),
                checksum);
        uploadFile("TECHNICAL_SPEC", "spec.pdf", "application/pdf", pdf(210, 297), checksum);
    }

    private ResultActions submit(long version) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
    }

    private List<String> decisions() {
        return jdbc.queryForList("SELECT decision FROM design.design_decision WHERE draft_id = ?::uuid "
                + "ORDER BY created_at, id", String.class, design);
    }

    @Test
    void aDesignThatFailsPreflightStaysADraftWithTheFindingsLocatedAndRecorded() throws Exception {
        long before = version();
        submit(before)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PREFLIGHT_FAILED"))
                .andExpect(jsonPath("$.details.issues[0].rule").value("EMPTY_DESIGN"));

        // 100 px over 30 mm is 85 DPI: under the template's 300.
        saveLogoLayout(uploadLogo(100));
        submit(version())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.issues[0].rule").value("DPI_BELOW_MIN"))
                .andExpect(jsonPath("$.details.issues[0].surfaceCode").value("BODY"))
                .andExpect(jsonPath("$.details.issues[0].elementId").value("logo"))
                .andExpect(jsonPath("$.details.issues[0].values.dpi").value(85))
                .andExpect(jsonPath("$.details.issues[0].values.minDpi").value(300));

        assertThat(decisions()).containsSubsequence("CREATED", "PREFLIGHT_FAILED", "PREFLIGHT_FAILED");
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(jsonPath("$.data.status").value("DRAFT"));
        assertThat(jdbc.queryForObject("SELECT notes FROM design.design_decision WHERE draft_id = ?::uuid AND "
                + "decision = 'PREFLIGHT_FAILED' ORDER BY created_at DESC, id DESC LIMIT 1", String.class, design))
                .isEqualTo("DPI_BELOW_MIN BODY/logo");
    }

    @Test
    void theFilesMustBeRenderedFromTheLayoutAsItIs() throws Exception {
        String checksum = saveLogoLayout(uploadLogo(1200));
        submit(version())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_FILES_OUTDATED"))
                .andExpect(jsonPath("$.details.roles.length()").value(3));

        uploadFiles(checksum);
        // The customer moves something after rendering: the files no longer show the layout.
        String moved = saveLogoLayout(uploadLogo(1300));
        assertThat(moved).isNotEqualTo(checksum);
        submit(version())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.roles[0]").value("CUSTOMER_PREVIEW"));
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void aDesignThatPassesIsLockedQueuedAndAnnounced() throws Exception {
        uploadFiles(saveLogoLayout(uploadLogo(1200)));
        long version = version();
        submit(version - 1).andExpect(status().isConflict());

        JsonNode submitted = data(submit(version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.reviewRequestedAt").exists()));
        assertThat(submitted.get("version").asLong()).isGreaterThan(version);
        assertThat(decisions()).last().isEqualTo("SUBMITTED_FOR_REVIEW");
        assertThat(recorded.matching(DesignSubmittedForReview.class, e -> e.designId().toString().equals(design)))
                .singleElement().satisfies(event -> assertThat(event.sku()).isEqualTo(sku));

        // Locked while it waits (BR-07).
        mvc.perform(put("/api/v1/designs/" + design + "/name").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + submitted.get("version") + ",\"name\":\"x\"}"))
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                        .file(new MockMultipartFile("file", "late.png", "image/png", png(10, 10))))
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        submit(submitted.get("version").asLong()).andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));

        as(staff);
        JsonNode queue = data(mvc.perform(get("/api/v1/designs/review-queue")).andExpect(status().isOk()));
        assertThat(queue.get("items").findValuesAsText("id")).contains(design);
    }

    @Test
    void aVariantNoLongerOnSaleOrWithoutACartonCannotBeSubmitted() throws Exception {
        uploadFiles(saveLogoLayout(uploadLogo(1200)));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.variants SET status = 'BLOCKED' WHERE sku = ?", sku);
            jdbc.update("UPDATE inventory.uom_conversions SET active = FALSE, default_shipping = FALSE WHERE NOT is_base "
                    + "AND inventory_item_id = "
                    + "(SELECT id FROM inventory.inventory_items WHERE sku = ?)", sku);
        });

        JsonNode issues = json.readTree(submit(version())
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString()).get("details").get("issues");
        assertThat(issues.findValuesAsText("rule")).containsExactly("VARIANT_UNAVAILABLE", "CARTON_SPEC_MISSING");
        assertThat(issues.get(0).has("surfaceCode")).isFalse();
    }

    @Test
    void onlyItsOwnerSubmitsADesign() throws Exception {
        long version = version();
        UUID stranger = tx.execute(s -> ReferenceRows.user(entityManager));
        as(stranger);
        submit(version).andExpect(status().isNotFound());
        as(owner);
        assertThat(decisions()).containsExactly("CREATED");
    }

    /** Review of 2F-5: a template edited in place after the files were drawn on it leaves them outdated. */
    @Test
    void filesDrawnOnATemplateSinceEditedAreOutdated() throws Exception {
        uploadFiles(saveLogoLayout(uploadLogo(1200)));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.customization_templates SET version = version + 1 "
                + "WHERE id = ?::uuid", templateId));

        submit(version())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_FILES_OUTDATED"))
                .andExpect(jsonPath("$.details.roles.length()").value(3));
    }

    /** Review of 2F-5: the print file is checked before the design reaches the Sales Manager. */
    @Test
    void aPrintFileThePressCannotUseIsRefused() throws Exception {
        String checksum = saveLogoLayout(uploadLogo(1200));
        uploadFile("CUSTOMER_PREVIEW", "preview.png", "image/png", png(8, 8), checksum);
        uploadFile("TECHNICAL_SPEC", "spec.pdf", "application/pdf", pdf(210, 297), checksum);

        uploadFile("PRINT_READY", "print.png", "image/png", png(8, 8), checksum);
        submit(version())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.issues[0].rule").value("PRINT_FILE_INVALID"));

        // A PDF, but of A4 pages rather than the cup's unrolled wall.
        uploadFile("PRINT_READY", "print.pdf", "application/pdf", pdf(210, 297), checksum);
        submit(version())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.issues[0].rule").value("PRINT_FILE_INVALID"))
                .andExpect(jsonPath("$.details.issues[0].message").value(org.hamcrest.Matchers.containsString("size")));
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(jsonPath("$.data.status").value("DRAFT"));
        assertThat(decisions()).last().isEqualTo("PREFLIGHT_FAILED");
    }
}
