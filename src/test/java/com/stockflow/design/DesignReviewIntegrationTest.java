package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.design.api.DesignService;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Sales Manager decides a customer's design, over HTTP (kltn-docs 12 §4.4, §4.5): they read it while it waits;
 * returning needs a reason and gives the customer a draft again; approving stamps the print file, draws the production
 * instruction, freezes layout, images and template in a snapshot that verifies, and issues the customer's own SKU - in
 * one step, so a SKU that cannot be issued leaves the design pending (BR-09).
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
class DesignReviewIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired PrintConfigs printConfigs;
    @Autowired UomConversionService units;
    @Autowired DesignService designs;
    @Autowired FileStorage storage;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired RecordedEvents.Recorder recorded;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private UUID staff;
    private UUID manager;
    private UUID owner;
    private UUID customerId;
    private String sku;
    private String design;
    private String templateId;
    private String logo;
    private String taxCode;
    private PrintSurface surface;

    @BeforeEach
    void aSubmittedDesign() throws Exception {
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        as(staff, false);
        String code = "REV-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
        units.add(new Sku(sku), new UomConversionDraft("CARTON", 50, new BigDecimal("4.2"), new BigDecimal("60"),
                new BigDecimal("40"), new BigDecimal("35"), false, true, true));
        taxCode = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L,
                9_999_999_999L));
        UUID[] account = tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            UUID customer = ReferenceRows.customer(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ?, tax_code = ? WHERE id = ?", user, taxCode,
                    customer);
            return new UUID[] {user, customer};
        });
        owner = account[0];
        customerId = account[1];
        as(owner, false);
        design = data(mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku
                        + "\",\"templateCode\":\"CUP12-BODY\",\"name\":\"Ly quán A\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        logo = data(mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png(1200, 1200))))
                .andExpect(status().isCreated())).get("assetId").asText();
        double u = (surface.uFrom() + surface.uTo()) / 2;
        double v = (surface.vFrom() + surface.vTo()) / 2;
        String layout = "{\"schemaVersion\":1,\"templateCode\":\"CUP12-BODY\",\"sku\":\"" + sku + "\",\"surfaces\":"
                + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[{\"id\":\"logo\",\"type\":\"image\","
                + "\"assetId\":\"" + logo + "\",\"naturalWidthPx\":1200,\"naturalHeightPx\":1200,\"widthMm\":30,"
                + "\"heightMm\":30,\"placement\":{\"u\":" + u + ",\"v\":" + v + ",\"rotationDeg\":0,\"scale\":1}}]}}}";
        String checksum = data(mvc.perform(put("/api/v1/designs/" + design + "/document")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"document\":" + layout + "}"))
                .andExpect(status().isOk())).get("checksum").asText();
        upload("CUSTOMER_PREVIEW", "preview.png", "image/png", png(8, 8), checksum);
        upload("PRINT_READY", "print.pdf", "application/pdf", pdf(surface.flatWidth(), surface.flatHeight()),
                checksum);
        upload("TECHNICAL_SPEC", "spec.pdf", "application/pdf", pdf(210, 297), checksum);
        mvc.perform(post("/api/v1/designs/" + design + "/technical-review-requests")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }

    private void as(UUID user, boolean reviewer) {
        Set<PermissionCode> permissions = reviewer ? Set.of(PermissionCode.of("design-designs", Action.APPROVE),
                PermissionCode.of("design-designs", Action.READ)) : Set.of();
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), permissions,
                DataScope.ALL, Set.of())));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?::uuid", Long.class, design);
    }

    private static byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] pdf(double widthMm, double heightMm) throws Exception {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle((float) (widthMm * 72 / 25.4), (float) (heightMm * 72 / 25.4))));
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void upload(String role, String name, String type, byte[] bytes, String checksum) throws Exception {
        mvc.perform(multipart("/api/v1/designs/" + design + "/artifacts")
                        .file(new MockMultipartFile("file", name, type, bytes))
                        .param("role", role).param("documentChecksum", checksum))
                .andExpect(status().isCreated());
    }

    private ResultActions decide(long version, boolean approved, String notes) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"approved\":" + approved
                        + (notes == null ? "" : ",\"notes\":\"" + notes + "\"") + "}"));
    }

    private List<String> decisions() {
        return jdbc.queryForList("SELECT decision FROM design.design_decision WHERE draft_id = ?::uuid "
                + "ORDER BY created_at, id", String.class, design);
    }

    @Test
    void aReviewerReadsTheDesignWhileItWaitsAndNobodyElseDoes() throws Exception {
        as(manager, true);
        String base = "/api/v1/designs/" + design;
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mvc.perform(get(base + "/document")).andExpect(status().isOk());
        mvc.perform(get(base + "/template")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("CUP12-BODY"));
        mvc.perform(get(base + "/assets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assetId").value(logo));
        mvc.perform(get(base + "/artifacts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get(base + "/history")).andExpect(status().isOk());

        as(staff, false);
        mvc.perform(get(base)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/document")).andExpect(status().isNotFound());
    }

    @Test
    void aReturnedDesignIsADraftAgainWithTheReason() throws Exception {
        as(manager, true);
        long version = version();
        decide(version, false, null).andExpect(status().isBadRequest());
        decide(version, false, "Logo quá sát mép, xin dời vào giữa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.reviewNotes").value("Logo quá sát mép, xin dời vào giữa"))
                .andExpect(jsonPath("$.data.reviewedBy").value(manager.toString()))
                .andExpect(jsonPath("$.data.reviewedAt").exists());

        assertThat(decisions()).last().isEqualTo("RETURNED");
        assertThat(recorded.matching(DesignReviewed.class, e -> e.designId().toString().equals(design)))
                .singleElement().satisfies(event -> assertThat(event.outcome()).isEqualTo("RETURNED"));
        // No longer in the queue, and the customer may change it again.
        mvc.perform(get("/api/v1/designs/review-queue"))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + design + "')]").isEmpty());
        as(owner, false);
        mvc.perform(put("/api/v1/designs/" + design + "/name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version() + ",\"name\":\"Ly quán A - sửa\"}")).andExpect(status().isOk());
    }

    @Test
    void anApprovedDesignIsFrozenStampedAndGivenItsOwnSku() throws Exception {
        as(manager, true);
        decide(version() - 1, true, null).andExpect(status().isConflict());
        decide(version(), true, "In đúng màu Pantone 186C")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        as(owner, false);
        JsonNode snapshot = data(mvc.perform(get("/api/v1/designs/" + design + "/snapshot")).andExpect(status().isOk()));
        assertThat(snapshot.get("artifacts").findValuesAsText("role"))
                .containsExactlyInAnyOrder("CUSTOMER_PREVIEW", "PRINT_READY", "TECHNICAL_SPEC", "PRODUCTION_INSTRUCTION");
        assertThat(snapshot.get("documentChecksum").asText()).hasSize(64);
        assertThat(snapshot.get("document").get("surfaces").get("BODY").get("elements").get(0).get("assetId").asText())
                .isEqualTo(logo);
        assertThat(snapshot.get("assets").findValuesAsText("assetId")).containsExactly(logo);
        String customerSku = snapshot.get("customerSku").asText();
        assertThat(customerSku).startsWith(jdbc.queryForObject("SELECT code FROM product.products p JOIN "
                + "product.variants v ON v.product_id = p.id WHERE v.sku = ?", String.class, sku) + "-" + taxCode + "-");
        assertThat(jdbc.queryForObject("SELECT status FROM product.variants WHERE sku = ?", String.class, customerSku))
                .isEqualTo("ACTIVE");

        // The snapshot adds up, layout and images included, and the files are where it says.
        UUID snapshotId = UUID.fromString(snapshot.get("id").asText());
        assertThat(designs.verifySnapshot(snapshotId, customerId).checksum()).isEqualTo(snapshot.get("checksum").asText());
        assertThat(designs.verifyForPrinting(snapshotId, snapshot.get("checksum").asText()).matching()).isTrue();

        // The print file the press gets carries its boxes and the PDF/X-4 identification.
        String printKey = jdbc.queryForObject("SELECT storage_key FROM design.design_artifact WHERE id = ?::uuid",
                String.class, snapshot.get("artifacts").findParents("role").stream()
                        .filter(a -> a.get("role").asText().equals("PRINT_READY")).findFirst().orElseThrow()
                        .get("artifactId").asText());
        try (var in = storage.download(printKey); PDDocument stamped = Loader.loadPDF(in.readAllBytes())) {
            assertThat(stamped.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion"))
                    .isEqualTo("PDF/X-4");
            assertThat(stamped.getDocumentInformation().getTrapped()).isEqualTo("False");
            PDRectangle trim = stamped.getPage(0).getTrimBox();
            assertThat((double) trim.getWidth()).isCloseTo(surface.trimBounds().width() * 72 / 25.4, within(0.01));
            assertThat(stamped.getDocumentCatalog().getMetadata()).isNotNull();
            // Named by its template and design: the variant's SKU is not the customer's.
            assertThat(stamped.getDocumentInformation().getTitle()).isEqualTo("Print file CUP12-BODY, design " + design)
                    .doesNotContain(sku);
        }

        // The production instruction names the SKU the customer was issued, not the variant it was drawn on.
        String sheetKey = jdbc.queryForObject("SELECT storage_key FROM design.design_artifact WHERE id = ?::uuid",
                String.class, snapshot.get("artifacts").findParents("role").stream()
                        .filter(a -> a.get("role").asText().equals("PRODUCTION_INSTRUCTION")).findFirst().orElseThrow()
                        .get("artifactId").asText());
        try (var in = storage.download(sheetKey); PDDocument sheet = Loader.loadPDF(in.readAllBytes())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(sheet);
            assertThat(text.lines().filter(line -> line.contains("SKU riêng của khách")))
                    .singleElement().asString().contains(customerSku);
            assertThat(text.lines().filter(line -> line.startsWith("Quy cách")))
                    .anySatisfy(line -> assertThat(line).contains(sku));
        }

        assertThat(decisions()).last().isEqualTo("APPROVED");
        // The Sales Manager's note stays in the history with the decision (BR-13).
        assertThat(jdbc.queryForObject("SELECT notes FROM design.design_decision WHERE draft_id = ?::uuid AND "
                + "decision = 'APPROVED'", String.class, design)).contains(customerSku, "In đúng màu Pantone 186C");
        assertThat(recorded.matching(DesignReviewed.class, e -> e.designId().toString().equals(design)))
                .singleElement().satisfies(event -> {
                    assertThat(event.outcome()).isEqualTo("APPROVED");
                    assertThat(event.sku()).isEqualTo(customerSku);
                });
        // The approved design reopens on the template as approved, whatever happens to the template since.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.customization_templates SET name = 'Đổi tên' "
                + "WHERE id = ?::uuid", templateId));
        mvc.perform(get("/api/v1/designs/" + design + "/template")).andExpect(jsonPath("$.data.name").value("Ly 12oz"));
    }

    @Test
    void aSkuThatCannotBeIssuedLeavesTheDesignPending() throws Exception {
        // The variant stopped being sold after the design was submitted (kltn-docs 12 edge cases): no SKU, no approval.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'BLOCKED' WHERE sku = ?", sku));
        as(manager, true);

        decide(version(), true, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_SKU_SOURCE_UNAVAILABLE"));

        mvc.perform(get("/api/v1/designs/" + design)).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_snapshot WHERE draft_id = ?::uuid",
                Long.class, design)).isZero();
        assertThat(decisions()).doesNotContain("APPROVED");
        // Returned with a reason, the customer chooses another variant with a new design.
        decide(version(), false, "Quy cách đã ngừng bán").andExpect(status().isOk());
    }

    @Test
    void onlyAPendingDesignIsDecided() throws Exception {
        as(manager, true);
        decide(version(), false, "Chưa đạt").andExpect(status().isOk());
        decide(version(), true, null).andExpect(status().isConflict());
    }
}
