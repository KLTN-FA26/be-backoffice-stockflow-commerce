package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.contracts.DesignBatchSubmittedForReview;
import com.stockflow.contracts.DesignProjectQuoteRequested;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Design projects over HTTP (kltn-docs 12 §4.9-§4.11, §5.4; BR-17..BR-22; B42, B43): a customer gathers designs in a
 * project, places library files on them as copies, submits drafts together - all or none - in one review round the Sales
 * Manager decides design by design, and asks one quote over the approved ones.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
class DesignProjectIntegrationTest {

    private static final Set<PermissionCode> REVIEWER = Set.of(PermissionCode.of("design-designs", Action.APPROVE),
            PermissionCode.of("design-designs", Action.READ));
    private static final Set<PermissionCode> SALES = Set.of(PermissionCode.of("design-quote-requests", Action.READ),
            PermissionCode.of("design-designs", Action.READ));

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

    private UUID owner;
    private UUID customerId;
    private UUID manager;
    private UUID productId;
    private String sku;
    private PrintSurface surface;
    private String project;

    @BeforeEach
    void aCustomerWithAProject() throws Exception {
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        as(manager, Set.of());
        String code = "PRJ-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = products.create(new CreateProductCommand(code, "Ly giấy 12oz", "Paper cup 12oz", null, null,
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
        String templateId = template.get("templateId").asText();
        mvc.perform(post(base + "/" + templateId + "/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + template.get("version") + "}")).andExpect(status().isOk());
        var config = printConfigs.byId(UUID.fromString(templateId)).orElseThrow();
        surface = PrintSurface.of(config.model(), config.printAreas().get(0));
        units.add(new Sku(sku), new UomConversionDraft("CARTON", 50, new BigDecimal("4.2"), new BigDecimal("60"),
                new BigDecimal("40"), new BigDecimal("35"), false, true, true));
        String taxCode = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L,
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
        as(owner, Set.of());
        project = data(mvc.perform(post("/api/v1/design-projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nhận diện quán A\",\"description\":\"Ly và túi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))).get("id").asText();
    }

    private void as(UUID user, Set<PermissionCode> permissions) {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), permissions,
                DataScope.ALL, Set.of())));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long version(String design) {
        return jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?::uuid", Long.class, design);
    }

    private long projectVersion() {
        return jdbc.queryForObject("SELECT version FROM design.design_project WHERE id = ?::uuid", Long.class, project);
    }

    private static byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out);
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

    /** A draft started in {@code inProject} (or alone), its logo placed at {@code u}, its three files rendered. */
    private String draft(String inProject, double u) throws Exception {
        String design = data(mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku + "\",\"templateCode\":\"CUP12-BODY\","
                        + "\"name\":\"Ly\"" + (inProject == null ? "" : ",\"projectId\":\"" + inProject + "\"") + "}"))
                .andExpect(status().isCreated())).get("id").asText();
        String logo = data(mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png(1200, 1200))))
                .andExpect(status().isCreated())).get("assetId").asText();
        layOut(design, logo, u);
        return design;
    }

    /** A layout with {@code logo} at {@code u} across the wall. */
    private String layoutOf(String logo, double u) {
        double v = (surface.vFrom() + surface.vTo()) / 2;
        return "{\"schemaVersion\":1,\"templateCode\":\"CUP12-BODY\",\"sku\":\"" + sku + "\",\"surfaces\":"
                + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[{\"id\":\"logo\",\"type\":\"image\","
                + "\"assetId\":\"" + logo + "\",\"naturalWidthPx\":1200,\"naturalHeightPx\":1200,\"widthMm\":30,"
                + "\"heightMm\":30,\"placement\":{\"u\":" + u + ",\"v\":" + v + ",\"rotationDeg\":0,\"scale\":1}}]}}}";
    }

    /** Places {@code logo} at {@code u} and renders the three files from that layout. */
    private void layOut(String design, String logo, double u) throws Exception {
        String layout = layoutOf(logo, u);
        String checksum = data(mvc.perform(put("/api/v1/designs/" + design + "/document")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(design) + ",\"document\":"
                        + layout + "}")).andExpect(status().isOk())).get("checksum").asText();
        upload(design, "CUSTOMER_PREVIEW", "preview.png", "image/png", png(8, 8), checksum);
        upload(design, "PRINT_READY", "print.pdf", "application/pdf", pdf(surface.flatWidth(), surface.flatHeight()),
                checksum);
        upload(design, "TECHNICAL_SPEC", "spec.pdf", "application/pdf", pdf(210, 297), checksum);
    }

    private double middle() {
        return (surface.uFrom() + surface.uTo()) / 2;
    }

    private void upload(String design, String role, String name, String type, byte[] bytes, String checksum)
            throws Exception {
        mvc.perform(multipart("/api/v1/designs/" + design + "/artifacts")
                        .file(new MockMultipartFile("file", name, type, bytes))
                        .param("role", role).param("documentChecksum", checksum))
                .andExpect(status().isCreated());
    }

    private ResultActions batch(String... designs) throws Exception {
        StringBuilder body = new StringBuilder("{\"designs\":[");
        for (int i = 0; i < designs.length; i++) {
            body.append(i == 0 ? "" : ",").append("{\"id\":\"").append(designs[i]).append("\",\"version\":")
                    .append(version(designs[i])).append('}');
        }
        return mvc.perform(post("/api/v1/design-projects/" + project + "/review-rounds")
                .contentType(MediaType.APPLICATION_JSON).content(body.append("]}").toString()));
    }

    private ResultActions decide(String design, boolean approved, String notes) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(design) + ",\"approved\":" + approved
                        + (notes == null ? "" : ",\"notes\":\"" + notes + "\"") + "}"));
    }

    private String statusOf(String design) {
        return jdbc.queryForObject("SELECT status FROM design.design_draft WHERE id = ?::uuid", String.class, design);
    }

    private List<String> projectEvents() {
        return jdbc.queryForList("SELECT event FROM design.design_project_event WHERE project_id = ?::uuid "
                + "ORDER BY created_at, id", String.class, project);
    }

    @Test
    void designsJoinAndLeaveTheProjectOfTheirOwnCustomerAndTheProjectClosesOnceNoneWaits() throws Exception {
        String inside = draft(project, middle());
        String alone = draft(null, middle());
        mvc.perform(get("/api/v1/designs/" + inside)).andExpect(jsonPath("$.data.projectId").value(project));

        mvc.perform(post("/api/v1/design-projects/" + project + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + alone + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designs.length()").value(2));
        // Adding it again changes nothing; another project cannot take it.
        mvc.perform(post("/api/v1/design-projects/" + project + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + alone + "\"}")).andExpect(status().isOk());
        String other = data(mvc.perform(post("/api/v1/design-projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Khác\"}")).andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post("/api/v1/design-projects/" + other + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + alone + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_IN_ANOTHER_PROJECT"));
        assertThat(jdbc.queryForList("SELECT decision FROM design.design_decision WHERE draft_id = ?::uuid",
                String.class, alone)).contains("ADDED_TO_PROJECT");

        // Waiting for review, a design neither leaves nor lets the project close.
        mvc.perform(post("/api/v1/designs/" + inside + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(inside) + "}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/design-projects/" + project + "/designs/" + inside))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DESIGN_PENDING_IN_PROJECT"));
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_HAS_PENDING"));
        mvc.perform(delete("/api/v1/design-projects/" + project + "/designs/" + alone)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designs.length()").value(1));

        as(manager, REVIEWER);
        decide(inside, false, "Sửa màu").andExpect(status().isOk());
        as(owner, Set.of());
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        // Closed for good: no design joins, nothing is renamed; its designs are used on their own.
        mvc.perform(post("/api/v1/design-projects/" + project + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + alone + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_ARCHIVED"));
        mvc.perform(put("/api/v1/design-projects/" + project).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + ",\"name\":\"Mới\"}")).andExpect(status().isConflict());
        assertThat(projectEvents()).containsSubsequence("PROJECT_CREATED", "PROJECT_ARCHIVED");

        // Nobody else sees it.
        as(tx.execute(s -> ReferenceRows.user(entityManager)), Set.of());
        mvc.perform(get("/api/v1/design-projects/" + project)).andExpect(status().isNotFound());
    }

    @Test
    void aProjectHoldsAtMostTwentyDesignsArchivedOnesAside() throws Exception {
        String extra = draft(null, middle());
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 20; i++) {
                boolean archived = i == 0;
                jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, name, status, created_at, "
                        + "current_artifacts, owner_user_id, project_id, archived_at) VALUES (gen_random_uuid(), ?, ?, "
                        + "'x', ?, NOW(), '{}'::jsonb, ?, ?::uuid, " + (archived ? "NOW()" : "NULL") + ")", customerId,
                        productId, archived ? "ARCHIVED" : "DRAFT", owner, project);
            }
        });
        // 19 count: one more fits.
        mvc.perform(post("/api/v1/design-projects/" + project + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + extra + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku + "\",\"templateCode\":\"CUP12-BODY\","
                        + "\"name\":\"Ly\",\"projectId\":\"" + project + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_FULL"));
    }

    @Test
    void aLibraryFileIsPlacedOnADesignAsItsOwnCopy() throws Exception {
        String design = draft(project, middle());
        String logo = data(mvc.perform(multipart("/api/v1/design-projects/" + project + "/assets")
                .file(new MockMultipartFile("file", "brand.png", "image/png", png(900, 600))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.widthPx").value(900))).get("assetId").asText();
        String photo = data(mvc.perform(multipart("/api/v1/design-projects/" + project + "/assets")
                .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg(400, 400))))
                .andExpect(status().isCreated())).get("assetId").asText();
        mvc.perform(get("/api/v1/design-projects/" + project + "/assets")).andExpect(jsonPath("$.data.length()")
                .value(2));

        JsonNode placed = data(mvc.perform(post("/api/v1/designs/" + design + "/assets/library")
                .contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\"" + logo + "\"}"))
                .andExpect(status().isCreated()));
        String libraryKey = jdbc.queryForObject("SELECT storage_key FROM design.design_project_asset WHERE id = ?::uuid",
                String.class, logo);
        String copyKey = jdbc.queryForObject("SELECT storage_key FROM design.design_asset WHERE id = ?::uuid",
                String.class, placed.get("assetId").asText());
        assertThat(copyKey).isNotEqualTo(libraryKey).startsWith("design-assets/");
        // Again: the same image.
        mvc.perform(post("/api/v1/designs/" + design + "/assets/library").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"" + logo + "\"}"))
                .andExpect(jsonPath("$.data.assetId").value(placed.get("assetId").asText()));
        // The template takes PNG only: the photo is not placed.
        mvc.perform(post("/api/v1/designs/" + design + "/assets/library").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"" + photo + "\"}")).andExpect(status().isUnsupportedMediaType());
        // A design outside the project does not reach its library.
        String alone = draft(null, middle());
        mvc.perform(post("/api/v1/designs/" + alone + "/assets/library").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"" + logo + "\"}")).andExpect(status().isNotFound());

        // Removed from the library, the file stays the design's.
        mvc.perform(delete("/api/v1/design-projects/" + project + "/assets/" + logo)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/designs/" + design + "/assets"))
                .andExpect(jsonPath("$.data[?(@.assetId == '" + placed.get("assetId").asText() + "')]").isNotEmpty());
        assertThat(projectEvents()).containsSubsequence("ASSET_ADDED", "ASSET_ADDED", "ASSET_REMOVED");

        // At most 30 files (B43).
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 29; i++) {
                jdbc.update("INSERT INTO design.design_project_asset (id, project_id, storage_key, original_name, "
                        + "content_type, size_bytes, checksum, width_px, height_px, created_at) VALUES "
                        + "(gen_random_uuid(), ?::uuid, 'design-assets/qa/' || gen_random_uuid(), 'x.png', 'image/png', "
                        + "1, ?, 1, 1, NOW())", project, "a".repeat(64));
            }
        });
        mvc.perform(multipart("/api/v1/design-projects/" + project + "/assets")
                .file(new MockMultipartFile("file", "one-more.png", "image/png", png(10, 10))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_LIBRARY_FULL"));
    }

    @Test
    void aBatchGoesWholeOrNotAtAllAndItsRoundClosesOnceEveryDesignIsDecided() throws Exception {
        String first = draft(project, middle());
        String broken = draft(project, surface.uTo() + 500);
        String stale = draft(project, middle());
        // Its layout moved after its files were rendered: they are outdated.
        String staleLogo = jdbc.queryForObject("SELECT id::text FROM design.design_asset WHERE draft_id = ?::uuid",
                String.class, stale);
        mvc.perform(put("/api/v1/designs/" + stale + "/document").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(stale) + ",\"document\":" + layoutOf(staleLogo, middle() + 1) + "}"))
                .andExpect(status().isOk());

        batch(first, broken, stale).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_BATCH_PREFLIGHT_FAILED"))
                .andExpect(jsonPath("$.details.designs.length()").value(2))
                .andExpect(jsonPath("$.details.designs[?(@.designId == '" + broken + "')].issues").isNotEmpty())
                .andExpect(jsonPath("$.details.designs[?(@.designId == '" + stale + "')].roles").isNotEmpty());
        assertThat(statusOf(first)).isEqualTo("DRAFT");
        assertThat(statusOf(broken)).isEqualTo("DRAFT");
        assertThat(statusOf(stale)).isEqualTo("DRAFT");
        assertThat(projectEvents()).contains("BATCH_PREFLIGHT_FAILED");
        assertThat(jdbc.queryForObject("SELECT notes FROM design.design_project_event WHERE project_id = ?::uuid "
                + "AND event = 'BATCH_PREFLIGHT_FAILED'", String.class, project))
                .contains(broken + ": OUTSIDE_PRINT_AREA", stale + ": files to render again");
        assertThat(jdbc.queryForList("SELECT decision FROM design.design_decision WHERE draft_id = ?::uuid",
                String.class, broken)).contains("PREFLIGHT_FAILED");
        // One draft is no batch.
        batch(first).andExpect(status().isBadRequest());

        String logo = jdbc.queryForObject("SELECT id::text FROM design.design_asset WHERE draft_id = ?::uuid",
                String.class, broken);
        layOut(broken, logo, middle());
        String round = data(batch(first, broken).andExpect(status().isCreated())).get("roundId").asText();
        assertThat(statusOf(first)).isEqualTo("PENDING_REVIEW");
        assertThat(statusOf(broken)).isEqualTo("PENDING_REVIEW");
        assertThat(recorded.matching(DesignBatchSubmittedForReview.class, e -> e.roundId().toString().equals(round)))
                .singleElement().satisfies(e -> assertThat(e.designIds()).hasSize(2));
        assertThat(recorded.matching(DesignSubmittedForReview.class,
                e -> e.designId().toString().equals(first) || e.designId().toString().equals(broken))).isEmpty();

        // One item in the rounds' queue, none in the queue of designs submitted alone.
        as(manager, REVIEWER);
        mvc.perform(get("/api/v1/designs/review-queue"))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + first + "')]").isEmpty());
        mvc.perform(get("/api/v1/design-review-rounds")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + round + "')].projectName").value("Nhận diện quán A"));

        decide(first, false, "Logo lệch").andExpect(status().isOk());
        mvc.perform(get("/api/v1/design-review-rounds/" + round)).andExpect(jsonPath("$.data.closedAt").doesNotExist())
                // Returned, it is the customer's draft again: the round keeps its id and outcome, not the design.
                .andExpect(jsonPath("$.data.designs[?(@.designId == '" + first + "')].outcome").value("RETURNED"))
                .andExpect(jsonPath("$.data.designs[?(@.designId == '" + first + "')].design").isEmpty());

        // Returned from a round still open, it goes into a new round with another draft.
        as(owner, Set.of());
        layOut(stale, staleLogo, middle());
        String again = data(batch(first, stale).andExpect(status().isCreated())).get("roundId").asText();
        assertThat(again).isNotEqualTo(round);
        as(manager, REVIEWER);
        decide(broken, true, null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/design-review-rounds/" + round)).andExpect(jsonPath("$.data.closedAt").exists());
        assertThat(projectEvents()).containsSubsequence("BATCH_SUBMITTED", "BATCH_SUBMITTED", "BATCH_CLOSED");

        // Returned once more and submitted alone, it is in the queue of designs submitted alone.
        decide(stale, false, "Màu chưa đúng").andExpect(status().isOk());
        as(owner, Set.of());
        mvc.perform(post("/api/v1/designs/" + stale + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(stale) + "}"))
                .andExpect(status().isOk());
        as(manager, REVIEWER);
        mvc.perform(get("/api/v1/designs/review-queue"))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + stale + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + first + "')]").isEmpty());
    }

    @Test
    void approvingAllDecidesEachDesignOnItsOwnAndAQuoteIsAskedOverTheApprovedOnes() throws Exception {
        String cup = draft(project, middle());
        String mug = draft(project, middle());
        String round = data(batch(cup, mug).andExpect(status().isCreated())).get("roundId").asText();

        as(manager, REVIEWER);
        // The mug at a version the manager did not see: it stays waiting, the cup is approved.
        JsonNode result = data(mvc.perform(post("/api/v1/design-review-rounds/" + round + "/approvals")
                .contentType(MediaType.APPLICATION_JSON).content("{\"designs\":[{\"id\":\"" + cup + "\",\"version\":"
                        + version(cup) + "},{\"id\":\"" + mug + "\",\"version\":" + (version(mug) - 1) + "}]}"))
                .andExpect(status().isOk()));
        assertThat(result.get("outcomes").findValuesAsText("outcome")).containsExactlyInAnyOrder("APPROVED",
                "NOT_APPROVED");
        assertThat(result.get("round").get("closedAt")).isNull();
        mvc.perform(post("/api/v1/design-review-rounds/" + round + "/approvals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designs\":[{\"id\":\"" + mug + "\",\"version\":" + version(mug) + "}]}"))
                .andExpect(jsonPath("$.data.outcomes[0].outcome").value("APPROVED"))
                .andExpect(jsonPath("$.data.round.closedAt").exists());

        // One quote over both, one delivery date.
        as(owner, Set.of());
        String date = java.time.LocalDate.now(com.stockflow.common.domain.BusinessCalendar.ZONE).plusDays(20)
                .toString();
        JsonNode asked = data(mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"designId\":\"" + cup
                        + "\",\"cartons\":10},{\"designId\":\"" + mug + "\",\"cartons\":4}],\"desiredDeliveryDate\":\""
                        + date + "\"}")).andExpect(status().isCreated()));
        assertThat(asked.get("lines")).hasSize(2);
        assertThat(asked.get("lines").findValuesAsText("customerSku")).allSatisfy(s -> assertThat(s).isNotBlank());
        assertThat(recorded.matching(DesignProjectQuoteRequested.class,
                e -> e.projectId().toString().equals(project))).singleElement()
                .satisfies(e -> assertThat(e.lines()).extracting(DesignProjectQuoteRequested.Line::cartons)
                        .containsExactlyInAnyOrder(10, 4));
        assertThat(projectEvents()).contains("PROJECT_QUOTE_REQUESTED");

        // Only approved designs of the project, each named once.
        String fresh = draft(project, middle());
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"designId\":\"" + fresh + "\",\"cartons\":1}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_APPROVED"));
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"designId\":\"" + cup + "\",\"cartons\":1},{\"designId\":\"" + cup
                        + "\",\"cartons\":2}]}")).andExpect(status().isBadRequest());

        // The sales staff list it with the project's name.
        as(tx.execute(s -> ReferenceRows.user(entityManager)), SALES);
        mvc.perform(get("/api/v1/design-project-quote-requests")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + asked.get("id").asText() + "')].projectName")
                        .value("Nhận diện quán A"));
        mvc.perform(get("/api/v1/design-project-quote-requests").param("customerId", customerId.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        // A revision of an approved design joins the open project; once it is closed, a revision stands alone.
        as(owner, Set.of());
        String revision = data(mvc.perform(post("/api/v1/designs/" + cup + "/revisions")).andExpect(status().isOk()))
                .get("id").asText();
        mvc.perform(get("/api/v1/designs/" + revision)).andExpect(jsonPath("$.data.projectId").value(project));
        // The project full (B42), a revision is not refused: it stands alone.
        long held = jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE project_id = ?::uuid "
                + "AND status <> 'ARCHIVED'", Long.class, project);
        tx.executeWithoutResult(s -> {
            for (long i = held; i < 20; i++) {
                jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, name, status, created_at, "
                        + "current_artifacts, owner_user_id, project_id) VALUES (gen_random_uuid(), ?, ?, 'x', 'DRAFT', "
                        + "NOW(), '{}'::jsonb, ?, ?::uuid)", customerId, productId, owner, project);
            }
        });
        String outside = data(mvc.perform(post("/api/v1/designs/" + mug + "/revisions")).andExpect(status().isOk()))
                .get("id").asText();
        mvc.perform(get("/api/v1/designs/" + outside)).andExpect(jsonPath("$.data.projectId").doesNotExist());
        // Closed, the project takes no revision either.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET status = 'ARCHIVED', archived_at = "
                + "NOW() WHERE project_id = ?::uuid AND name = 'x'", project));
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isOk());
        String later = data(mvc.perform(post("/api/v1/designs/" + mug + "/revisions")).andExpect(status().isOk()))
                .get("id").asText();
        mvc.perform(get("/api/v1/designs/" + later)).andExpect(jsonPath("$.data.projectId").doesNotExist());
    }
}
