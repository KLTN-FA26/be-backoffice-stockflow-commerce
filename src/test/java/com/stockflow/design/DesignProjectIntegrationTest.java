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
import com.stockflow.contracts.DesignProjectQuoteRequestReady;
import com.stockflow.contracts.DesignProjectQuoteRequested;
import com.stockflow.contracts.DesignSubmittedForReview;
import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.design.internal.service.DesignProjectService;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TemplateModelFiles;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.TestPropertySource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
// On here, as in production: a retry with the same Idempotency-Key must be told what the first attempt was (BR-23).
@TestPropertySource(properties = "stockflow.idempotency.enabled=true")
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
    @Autowired DesignProjectService projectService;
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
                "Ly", null, null, TaxClass.STANDARD, List.of())).productId();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.products SET status = 'APPROVED', submitted_by = ?, submitted_at = NOW(), "
                    + "approved_by = ?, approved_at = NOW() WHERE id = ?", ReferenceRows.user(entityManager),
                    ReferenceRows.user(entityManager), productId);
            jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE product_id = ?", productId);
        });
        var variant = variants.list(productId, 0, 1).items().getFirst();
        sku = variant.sku();
        String base = "/api/v1/products/" + productId + "/variants/" + variant.id() + "/customization-templates";
        JsonNode template = data(mvc.perform(TemplateModelFiles.create(base,
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
                new BigDecimal("40"), new BigDecimal("35"), true));
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

    /**
     * A draft started in {@code inProject}, its logo placed at {@code u}, its three files rendered. {@code null} is a
     * design in no project: one started before every design had to be in one (BR-23.2), which the table still holds -
     * started in {@link #project}, then taken out of it, as such a design never was in one.
     */
    private String draft(String inProject, double u) throws Exception {
        String design = data(start(inProject == null ? project : inProject, null).andExpect(status().isCreated()))
                .get("id").asText();
        if (inProject == null) {
            tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET project_id = NULL "
                    + "WHERE id = ?::uuid", design));
        }
        String logo = data(mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png(1200, 1200))))
                .andExpect(status().isCreated())).get("assetId").asText();
        layOut(design, logo, u);
        return design;
    }

    /** Starts a design in {@code inProject} (none when null), under {@code key} when one is given. */
    private ResultActions start(String inProject, String key) throws Exception {
        var request = post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku + "\",\"templateCode\":\"CUP12-BODY\","
                        + "\"name\":\"Ly\"" + (inProject == null ? "" : ",\"projectId\":\"" + inProject + "\"") + "}");
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    /** One more open project of the caller's. */
    private String newProject(String name) throws Exception {
        return data(mvc.perform(post("/api/v1/design-projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated())).get("id").asText();
    }

    /** Fills {@code inProject} up to its 20 designs with drafts of the customer's. */
    private void fill(String inProject) {
        long held = jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE project_id = ?::uuid "
                + "AND status <> 'ARCHIVED'", Long.class, inProject);
        tx.executeWithoutResult(s -> {
            for (long i = held; i < 20; i++) {
                jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, name, status, created_at, "
                        + "current_artifacts, owner_user_id, project_id) VALUES (gen_random_uuid(), ?, ?, 'x', 'DRAFT', "
                        + "NOW(), '{}'::jsonb, ?, ?::uuid)", customerId, productId, owner, inProject);
            }
        });
    }

    /** The project {@code design} is in, or null. */
    private String projectOf(String design) {
        return jdbc.queryForObject("SELECT project_id::text FROM design.design_draft WHERE id = ?::uuid", String.class,
                design);
    }

    /**
     * An approved design staff started for the customer - no template of its own - in {@code inProject} (none when
     * null), with the snapshot a revision derives from.
     */
    private String approvedByStaff(String inProject) {
        UUID approved = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                    + "current_artifacts, owner_user_id, project_id) VALUES (?, ?, ?, 'APPROVED', NOW(), '{}'::jsonb, "
                    + "?, ?::uuid)", approved, customerId, productId, owner, inProject);
            jdbc.update("INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, "
                    + "size_bytes, stored_at, checksum, created_at, artifact_role) VALUES (?, ?, "
                    + "'design-artifacts/2026/11/' || ? || '.png', 'preview.png', 'image/png', 10, NOW(), ?, NOW(), "
                    + "'CUSTOMER_PREVIEW')", preview, approved, preview.toString(), "c".repeat(64));
            jdbc.update("INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                    + "created_at, artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec) VALUES (?, ?, ?, "
                    + "'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb, ?, ?, ?, 'Logo')", UUID.randomUUID(),
                    approved, "a".repeat(64), preview, manager, manager);
        });
        return approved.toString();
    }

    /** A second ACTIVE variant of the product, with its own active template CUP16-BODY: somewhere to clone onto. */
    private String anotherVariantWithItsTemplate() throws Exception {
        as(manager, Set.of());
        String other = "PRJ16-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID variant = variants.add(productId, new ProductVariantService.NewVariant(other, "Ly 16oz", List.of(), null))
                .id();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE id = ?", variant));
        String base = "/api/v1/products/" + productId + "/variants/" + variant + "/customization-templates";
        JsonNode template = data(mvc.perform(TemplateModelFiles.create(base,
                "{\"code\":\"CUP16-BODY\",\"name\":\"Ly 16oz\",\"allowedFileFormats\":[\"image/png\"],"
                        + "\"maxFileSizeMb\":20,\"model\":{\"kind\":\"parametric-cup\",\"topDiameterMm\":90,"
                        + "\"bottomDiameterMm\":60,\"heightMm\":130,\"rimMm\":3,\"seamMm\":8,\"bandTopOffsetMm\":6,"
                        + "\"bandBottomOffsetMm\":8,\"material\":\"paper-white\"},\"printAreas\":[{\"code\":\"BODY\","
                        + "\"name\":\"Thân ly\",\"safeMarginMm\":3,\"bleedMm\":2}]}")).andExpect(status().isCreated()));
        mvc.perform(post(base + "/" + template.get("templateId").asText() + "/activation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + template.get("version") + "}"))
                .andExpect(status().isOk());
        as(owner, Set.of());
        return other;
    }

    /** Clones {@code design} onto {@code target}, into {@code projectJson} (a raw JSON value) when one is given. */
    private ResultActions cloneOnto(String design, String target, String key, String projectJson) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/clones").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantSku\":\"" + target + "\",\"document\":{"
                        + "\"schemaVersion\":1,\"templateCode\":\"CUP16-BODY\",\"sku\":\"" + target + "\",\"surfaces\":"
                        + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[]}}}"
                        + (projectJson == null ? "" : ",\"projectId\":" + projectJson) + "}"));
    }

    /** The owner adds {@code design} to {@code inProject}: from no project, or moved from another of theirs. */
    private ResultActions move(String design, String inProject) throws Exception {
        return mvc.perform(post("/api/v1/design-projects/" + inProject + "/designs")
                .contentType(MediaType.APPLICATION_JSON).content("{\"designId\":\"" + design + "\"}"));
    }

    private List<String> decisionsOf(String design) {
        return jdbc.queryForList("SELECT decision FROM design.design_decision WHERE draft_id = ?::uuid "
                + "ORDER BY created_at, id", String.class, design);
    }

    private long designsOfTheCustomer() {
        return jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE customer_id = ?", Long.class,
                customerId);
    }

    private long draftsOfTheCustomer() {
        return jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE customer_id = ? AND status = 'DRAFT'",
                Long.class, customerId);
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

    /**
     * BR-23.2 (T1): a design is started in a project or not at all. The refusal is a settled answer, so a retry with
     * the same Idempotency-Key is told the same from the stored response, and nothing was written either time.
     */
    @Test
    void aDesignIsNotStartedWithoutAProject() throws Exception {
        long designs = designsOfTheCustomer();
        long drafts = draftsOfTheCustomer();
        String key = UUID.randomUUID().toString();
        start(null, key).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("projectId"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("NotNull"));
        start(null, key).andExpect(status().isBadRequest())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("projectId"));
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        assertThat(draftsOfTheCustomer()).isEqualTo(drafts);
    }

    /**
     * BR-23.2 (T2): only one of the caller's open projects with room takes a new design. A closed or a full project is
     * a 409 that a retry with the same key runs again (it may be answered otherwise once there is room); another
     * customer's project does not exist for the caller, a 404 the retry is told again. No design is written.
     */
    @Test
    void aDesignIsStartedOnlyInAnOpenProjectOfTheCallersWithRoom() throws Exception {
        String closed = newProject("Đã đóng");
        mvc.perform(post("/api/v1/design-projects/" + closed + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + jdbc.queryForObject("SELECT version FROM design.design_project WHERE id = "
                        + "?::uuid", Long.class, closed) + "}")).andExpect(status().isOk());
        String full = newProject("Đầy");
        fill(full);
        as(tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", user,
                    ReferenceRows.customer(entityManager));
            return user;
        }), Set.of());
        String theirs = newProject("Của khách khác");
        as(owner, Set.of());
        long designs = designsOfTheCustomer();

        String key = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            start(closed, key).andExpect(status().isConflict())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"))
                    .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_ARCHIVED"));
        }
        key = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            start(full, key).andExpect(status().isConflict())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"))
                    .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_FULL"));
        }
        key = UUID.randomUUID().toString();
        start(theirs, key).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        start(theirs, key).andExpect(status().isNotFound())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        start(UUID.randomUUID().toString(), null).andExpect(status().isNotFound());

        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE project_id = ?::uuid",
                Long.class, theirs)).isZero();
        // A customer that may take on no new work starts nothing, even in an open project of its own (BR-01).
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = 'BLOCKED' WHERE id = ?",
                customerId));
        start(project, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_ACTIVE"));
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
    }

    /**
     * BR-23.3 (T6), owner decision 2026-11-25: a clone follows the revision's rule - into the source's project while it
     * is open and has room, else into the open project with room the caller names, else not at all. The source's
     * project full or closed, it is refused - a 409 a retry with the same Idempotency-Key runs again, not one it is
     * told from a stored response - and nothing is written.
     */
    @Test
    void aCloneIsWrittenIntoAProjectOrNotAtAll() throws Exception {
        String target = anotherVariantWithItsTemplate();
        String source = data(start(project, null).andExpect(status().isCreated())).get("id").asText();
        // No project named: the clone is written into the source's own while it is open and has room.
        JsonNode inSource = data(cloneOnto(source, target, UUID.randomUUID().toString(), null)
                .andExpect(status().isCreated()));
        assertThat(projectOf(inSource.get("design").get("id").asText())).isEqualTo(project);
        fill(project);
        long designs = designsOfTheCustomer();

        String key = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            cloneOnto(source, target, key, null).andExpect(status().isConflict())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"))
                    .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_FULL"))
                    .andExpect(jsonPath("$.details.projectId").value(project));
        }
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        cloneOnto(source, target, UUID.randomUUID().toString(), "\"not-an-id\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("projectId"));
        cloneOnto(source, target, UUID.randomUUID().toString(), "\"" + UUID.randomUUID() + "\"")
                .andExpect(status().isNotFound());
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        // The source's project closed: refused too, naming it.
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isOk());
        cloneOnto(source, target, UUID.randomUUID().toString(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_ARCHIVED"))
                .andExpect(jsonPath("$.details.projectId").value(project));
        assertThat(designsOfTheCustomer()).isEqualTo(designs);

        String other = newProject("Quán B");
        JsonNode clone = data(cloneOnto(source, target, UUID.randomUUID().toString(), "\"" + other + "\"")
                .andExpect(status().isCreated()));
        assertThat(clone.get("design").get("projectId").asText()).isEqualTo(other);
        assertThat(clone.has("notInProject")).as("always null now, so never in the answer").isFalse();
        assertThat(projectOf(clone.get("design").get("id").asText())).isEqualTo(other);
        assertThat(designsOfTheCustomer()).isEqualTo(designs + 1);
    }

    /**
     * BR-23.3 (T6b): a revision of a design staff started - no template of its own - is written into a project as any
     * revision is: the source's own, or the one the caller names. A source in no project, started before every design
     * had to be in one, has none to lend: the caller must name one.
     */
    @Test
    void aRevisionOfADesignStaffStartedIsWrittenIntoAProjectToo() throws Exception {
        String inProject = approvedByStaff(project);
        String revision = data(mvc.perform(post("/api/v1/designs/" + inProject + "/revisions"))
                .andExpect(status().isOk())).get("id").asText();
        assertThat(projectOf(revision)).isEqualTo(project);

        String alone = approvedByStaff(null);
        long designs = designsOfTheCustomer();
        mvc.perform(post("/api/v1/designs/" + alone + "/revisions")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("projectId"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("NotNull"));
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        String other = newProject("Quán B");
        String named = data(mvc.perform(post("/api/v1/designs/" + alone + "/revisions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"" + other + "\"}"))
                .andExpect(status().isOk())).get("id").asText();
        assertThat(projectOf(named)).isEqualTo(other);
    }

    @Test
    void designsJoinTheProjectOfTheirOwnCustomerNeverLeaveForNoneAndTheProjectClosesOnceNoneWaits() throws Exception {
        String inside = draft(project, middle());
        String alone = draft(null, middle());
        mvc.perform(get("/api/v1/designs/" + inside)).andExpect(jsonPath("$.data.projectId").value(project));

        mvc.perform(post("/api/v1/design-projects/" + project + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + alone + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designs.length()").value(2));
        // Adding it again changes nothing (moving it to another project: see aDesignMovesBetweenItsOwnersOpenProjects).
        List<String> written = decisionsOf(alone);
        mvc.perform(post("/api/v1/design-projects/" + project + "/designs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"designId\":\"" + alone + "\"}")).andExpect(status().isOk());
        assertThat(decisionsOf(alone)).isEqualTo(written).doesNotContain("REMOVED_FROM_PROJECT");

        // T7 (BR-23.4): a design is never taken out of its project into none - there is no such endpoint any more.
        for (String design : List.of(inside, alone)) {
            mvc.perform(delete("/api/v1/design-projects/" + project + "/designs/" + design))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
            assertThat(projectOf(design)).isEqualTo(project);
        }
        assertThat(decisionsOf(alone)).doesNotContain("REMOVED_FROM_PROJECT");

        // Waiting for review, a design does not let the project close.
        mvc.perform(post("/api/v1/designs/" + inside + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(inside) + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_HAS_PENDING"));

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

    /**
     * BR-23.4 (T8): a design moves from one of its owner's open projects to another - a draft as well as an approved
     * one - and its history says both steps: out of the one, then into the other.
     */
    @Test
    void aDesignMovesBetweenItsOwnersOpenProjects() throws Exception {
        String other = newProject("Quán B");
        String drafted = draft(project, middle());
        String approved = approvedByStaff(project);
        for (String design : List.of(drafted, approved)) {
            move(design, other).andExpect(status().isOk());
            assertThat(projectOf(design)).isEqualTo(other);
            mvc.perform(get("/api/v1/designs/" + design + "/history")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].decision").value("ADDED_TO_PROJECT"))
                    .andExpect(jsonPath("$.data.items[0].notes").value("Project " + other))
                    .andExpect(jsonPath("$.data.items[1].decision").value("REMOVED_FROM_PROJECT"))
                    .andExpect(jsonPath("$.data.items[1].notes").value("Project " + project));
        }
        mvc.perform(get("/api/v1/design-projects/" + project)).andExpect(jsonPath("$.data.designs.length()").value(0));
        mvc.perform(get("/api/v1/design-projects/" + other)).andExpect(jsonPath("$.data.designs.length()").value(2));
        // Asked again where it already is, nothing changes; moved back, it is the first project's again.
        move(drafted, other).andExpect(status().isOk());
        move(drafted, project).andExpect(status().isOk());
        assertThat(projectOf(drafted)).isEqualTo(project);
        assertThat(decisionsOf(drafted)).containsSubsequence("ADDED_TO_PROJECT", "CREATED", "REMOVED_FROM_PROJECT",
                "ADDED_TO_PROJECT", "REMOVED_FROM_PROJECT", "ADDED_TO_PROJECT").hasSize(6);

        // A full project takes no more; the design stays where it was.
        fill(other);
        move(drafted, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_FULL"));
        assertThat(projectOf(drafted)).isEqualTo(project);

        // Not the caller's, archived, or of another customer: to the caller none of them exists.
        String strangers = draft(project, middle());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET owner_user_id = ? WHERE id = ?::uuid",
                ReferenceRows.user(entityManager), strangers));
        String archived = draft(project, middle());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET status = 'ARCHIVED', archived_at = "
                + "NOW() WHERE id = ?::uuid", archived));
        // A design is in a project of its own customer only (the table's key says so): another customer's is in none.
        String anotherCustomers = draft(project, middle());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET project_id = NULL, customer_id = ? "
                + "WHERE id = ?::uuid", ReferenceRows.customer(entityManager), anotherCustomers));
        String third = newProject("Quán C");
        for (String design : List.of(strangers, archived, anotherCustomers)) {
            move(design, third).andExpect(status().isNotFound());
            assertThat(projectOf(design)).isEqualTo(design.equals(anotherCustomers) ? null : project);
        }
        mvc.perform(get("/api/v1/design-projects/" + third)).andExpect(jsonPath("$.data.designs.length()").value(0));
    }

    /**
     * BR-23.4 (T9, T8b): a design waiting for review stays in its project, and a closed project keeps its designs; each
     * refusal leaves the design where it was and writes nothing.
     */
    @Test
    void aDesignStaysInItsProjectWhileItWaitsForReviewOrOnceTheProjectIsClosed() throws Exception {
        String other = newProject("Quán B");
        String design = draft(project, middle());
        mvc.perform(post("/api/v1/designs/" + design + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(design) + "}"))
                .andExpect(status().isOk());
        move(design, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PENDING_IN_PROJECT"));
        assertThat(projectOf(design)).isEqualTo(project);

        as(manager, REVIEWER);
        decide(design, false, "Sửa màu").andExpect(status().isOk());
        as(owner, Set.of());
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isOk());
        move(design, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_ARCHIVED"))
                .andExpect(jsonPath("$.details.projectId").value(project));
        assertThat(projectOf(design)).isEqualTo(project);
        assertThat(decisionsOf(design)).doesNotContain("REMOVED_FROM_PROJECT");
        mvc.perform(get("/api/v1/design-projects/" + other)).andExpect(jsonPath("$.data.designs.length()").value(0));
    }

    /**
     * BR-23.4 (T10): two moves in opposite directions at once - A to B and B to A - both finish, round after round:
     * each locks both projects in the same order before its design, so neither waits on the other in a cycle.
     */
    @Test
    void movesInOppositeDirectionsAtOnceBothFinish() throws Exception {
        String other = newProject("Quán B");
        UUID a = UUID.fromString(project);
        UUID b = UUID.fromString(other);
        UUID one = UUID.fromString(data(start(project, null).andExpect(status().isCreated())).get("id").asText());
        UUID two = UUID.fromString(data(start(other, null).andExpect(status().isCreated())).get("id").asText());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                // Even rounds move one A->B and two B->A; odd rounds move them back.
                UUID oneTo = round % 2 == 0 ? b : a;
                UUID twoTo = round % 2 == 0 ? a : b;
                CountDownLatch go = new CountDownLatch(1);
                Future<?> first = pool.submit(() -> {
                    go.await();
                    return projectService.addDesign(oneTo, owner, one);
                });
                Future<?> second = pool.submit(() -> {
                    go.await();
                    return projectService.addDesign(twoTo, owner, two);
                });
                go.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
                assertThat(projectOf(one.toString())).isEqualTo(oneTo.toString());
                assertThat(projectOf(two.toString())).isEqualTo(twoTo.toString());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(decisionsOf(one.toString()).stream().filter("REMOVED_FROM_PROJECT"::equals)).hasSize(6);
        assertThat(decisionsOf(two.toString()).stream().filter("ADDED_TO_PROJECT"::equals)).hasSize(7);
    }

    /**
     * FE request BE-15: the storefront offers a project only the designs in none, a page at a time - filtered in the
     * query, so the page and its total agree.
     */
    @Test
    void theOwnDesignsListKeepsThoseInAProjectOrThoseInNone() throws Exception {
        String inside = draft(project, middle());
        String alone = draft(null, middle());
        String alsoAlone = draft(null, middle());

        mvc.perform(get("/api/v1/designs").param("inProject", "false").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(alsoAlone));
        mvc.perform(get("/api/v1/designs").param("inProject", "false").param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.data.items[0].id").value(alone));
        mvc.perform(get("/api/v1/designs").param("inProject", "true"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(inside))
                .andExpect(jsonPath("$.data.items[0].projectId").value(project));
        mvc.perform(get("/api/v1/designs").param("inProject", "false").param("status", "DRAFT"))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/v1/designs")).andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(get("/api/v1/designs").param("inProject", "maybe")).andExpect(status().isBadRequest());
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
        start(project, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_FULL"));
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
    void aBatchRefusedForStaleVersionsNamesEveryStaleDesign() throws Exception {
        String fresh = draft(project, middle());
        String staleOne = draft(project, middle());
        String staleTwo = draft(project, middle());
        String body = "{\"designs\":[{\"id\":\"%s\",\"version\":%d},{\"id\":\"%s\",\"version\":%d},{\"id\":\"%s\",\"version\":%d}]}"
                .formatted(fresh, version(fresh), staleOne, version(staleOne) - 1, staleTwo, version(staleTwo) - 1);

        mvc.perform(post("/api/v1/design-projects/" + project + "/review-rounds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.details.designIds.length()").value(2))
                .andExpect(jsonPath("$.details.designIds[0]").value(staleOne))
                .andExpect(jsonPath("$.details.designIds[1]").value(staleTwo));
        assertThat(statusOf(fresh)).isEqualTo("DRAFT");
    }

    /**
     * FE request 2026-11-17 S-1, S-3, S-6: a batch refused for several reasons names every design refused, each with its
     * own code - a design not in the project too - and a design no longer a draft says so even at a stale version.
     */
    @Test
    void aBatchRefusedForSeveralReasonsNamesEveryDesignWithItsOwnReason() throws Exception {
        String fresh = draft(project, middle());
        String stale = draft(project, middle());
        String submitted = draft(project, middle());
        mvc.perform(post("/api/v1/designs/" + submitted + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(submitted) + "}"))
                .andExpect(status().isOk());
        String alone = draft(null, middle());
        String body = ("{\"designs\":[{\"id\":\"%s\",\"version\":%d},{\"id\":\"%s\",\"version\":%d},"
                + "{\"id\":\"%s\",\"version\":%d},{\"id\":\"%s\",\"version\":%d}]}")
                .formatted(fresh, version(fresh), stale, version(stale) - 1, submitted, version(submitted) - 1, alone,
                        version(alone));

        mvc.perform(post("/api/v1/design-projects/" + project + "/review-rounds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details.designIds.length()").value(1))
                .andExpect(jsonPath("$.details.designIds[0]").value(alone))
                .andExpect(jsonPath("$.details.designs.length()").value(3))
                .andExpect(jsonPath("$.details.designs[?(@.designId == '" + stale + "')].errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.details.designs[?(@.designId == '" + submitted + "')].errorCode")
                        .value("DESIGN_NOT_DRAFT"))
                .andExpect(jsonPath("$.details.designs[?(@.designId == '" + alone + "')].errorCode")
                        .value("NOT_FOUND"));
        assertThat(statusOf(fresh)).isEqualTo("DRAFT");
        assertThat(statusOf(stale)).isEqualTo("DRAFT");
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
        assertThat(asked.get("status").asText()).isEqualTo("OPEN");

        // Clicked twice (A3): the open request answers, 200, and nobody is told again.
        String sameAsk = "{\"lines\":[{\"designId\":\"" + mug + "\",\"cartons\":4},{\"designId\":\"" + cup
                + "\",\"cartons\":10}],\"desiredDeliveryDate\":\"" + date + "\"}";
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                .contentType(MediaType.APPLICATION_JSON).content(sameAsk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(asked.get("id").asText()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_project_quote_request WHERE project_id = "
                + "?::uuid", Long.class, project)).isEqualTo(1);
        assertThat(recorded.matching(DesignProjectQuoteRequested.class,
                e -> e.projectId().toString().equals(project))).as("told once").hasSize(1);
        assertThat(projectEvents().stream().filter("PROJECT_QUOTE_REQUESTED"::equals)).hasSize(1);
        // Something else asked while it is open: the same request now asks that, and the sales staff are told.
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"designId\":\"" + cup
                        + "\",\"cartons\":12}],\"desiredDeliveryDate\":\"" + date + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(asked.get("id").asText()))
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.lines[0].cartons").value(12));
        assertThat(recorded.matching(DesignProjectQuoteRequested.class,
                e -> e.projectId().toString().equals(project))).hasSize(2);

        // Only approved designs of the project, each named once.
        String fresh = draft(project, middle());
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"designId\":\"" + fresh + "\",\"cartons\":1}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_APPROVED"));
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"designId\":\"" + cup + "\",\"cartons\":1},{\"designId\":\"" + cup
                        + "\",\"cartons\":2}]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mỗi thiết kế chỉ chọn một lần trong yêu cầu."));
        // The one date of the request is its own field, told once - not under a line (WF-02 review).
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"designId\":\"" + cup + "\",\"cartons\":1}],\"desiredDeliveryDate\":\""
                        + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("desiredDeliveryDate"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Ngày giao mong muốn phải từ hôm nay trở đi."));

        // The sales staff list it with the project's name.
        as(tx.execute(s -> ReferenceRows.user(entityManager)), SALES);
        mvc.perform(get("/api/v1/design-project-quote-requests")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + asked.get("id").asText() + "')].projectName")
                        .value("Nhận diện quán A"));
        mvc.perform(get("/api/v1/design-project-quote-requests").param("customerId", customerId.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        // Settled otherwise, they close it with the reason (BR-25.3); the project then asks anew.
        as(tx.execute(s -> ReferenceRows.user(entityManager)), Set.of(PermissionCode.of("design-quote-requests",
                Action.UPDATE), PermissionCode.of("design-quote-requests", Action.READ)));
        mvc.perform(post("/api/v1/design-project-quote-requests/" + asked.get("id").asText() + "/closure")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Khách đặt qua điện thoại\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeReason").value("Khách đặt qua điện thoại"))
                .andExpect(jsonPath("$.data.closedAt").exists());
        as(owner, Set.of());
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                .contentType(MediaType.APPLICATION_JSON).content(sameAsk)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.id").value(org.hamcrest.Matchers.not(asked.get("id").asText())));
        // FE request BE-3: the sales staff's list in one status, counted after the filter.
        as(tx.execute(s -> ReferenceRows.user(entityManager)), SALES);
        mvc.perform(get("/api/v1/design-project-quote-requests").param("customerId", customerId.toString())
                        .param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(asked.get("id").asText()))
                .andExpect(jsonPath("$.data.items[0].status").value("CLOSED"));
        mvc.perform(get("/api/v1/design-project-quote-requests").param("customerId", customerId.toString())
                        .param("status", "OPEN"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].id").value(org.hamcrest.Matchers.not(asked.get("id").asText())));
        mvc.perform(get("/api/v1/design-project-quote-requests").param("customerId", customerId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/v1/design-project-quote-requests").param("status", "CLOSED"))
                .andExpect(jsonPath("$.data.items[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("CLOSED"))));
        mvc.perform(get("/api/v1/design-project-quote-requests").param("status", "PENDING"))
                .andExpect(status().isBadRequest());

        // BR-23.4, point A: a design an open quote request of its project names stays in that project until the
        // sales staff answer it.
        as(owner, Set.of());
        String elsewhere = newProject("Quán C");
        move(cup, elsewhere).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_IN_OPEN_QUOTE_REQUEST"))
                .andExpect(jsonPath("$.details.projectId").value(project));
        assertThat(projectOf(cup)).isEqualTo(project);
        assertThat(decisionsOf(cup)).doesNotContain("REMOVED_FROM_PROJECT");

        // BR-23.3 (T3): a revision of an approved design is written into the source's open project.
        as(owner, Set.of());
        String revision = data(mvc.perform(post("/api/v1/designs/" + cup + "/revisions")).andExpect(status().isOk()))
                .get("id").asText();
        mvc.perform(get("/api/v1/designs/" + revision)).andExpect(jsonPath("$.data.projectId").value(project));
        // The project full, the revision is refused, naming the project, and nothing is written: a new design never
        // stands in no project, as B42 once let it.
        fill(project);
        long designs = designsOfTheCustomer();
        mvc.perform(post("/api/v1/designs/" + mug + "/revisions")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_FULL"))
                .andExpect(jsonPath("$.details.projectId").value(project));
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        // T4: closed, the project takes no revision either, and says so.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET status = 'ARCHIVED', archived_at = "
                + "NOW() WHERE project_id = ?::uuid AND name = 'x'", project));
        mvc.perform(post("/api/v1/design-projects/" + project + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + projectVersion() + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/designs/" + mug + "/revisions")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_ARCHIVED"))
                .andExpect(jsonPath("$.details.projectId").value(project));
        assertThat(designsOfTheCustomer()).isEqualTo(designs);
        // T5: named another open project of the caller's, the revision is written there.
        String other = newProject("Quán B");
        String later = data(mvc.perform(post("/api/v1/designs/" + mug + "/revisions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"" + other + "\"}"))
                .andExpect(status().isOk())).get("id").asText();
        mvc.perform(get("/api/v1/designs/" + later)).andExpect(jsonPath("$.data.projectId").value(other));
        mvc.perform(get("/api/v1/designs/" + later + "/history"))
                .andExpect(jsonPath("$.data.items[0].decision").value("DERIVED_FROM_SNAPSHOT"))
                .andExpect(jsonPath("$.data.items[1].decision").value("ADDED_TO_PROJECT"))
                .andExpect(jsonPath("$.data.items[1].notes").value("Project " + other));
    }

    /** A batch of {@code designs} with {@code quoteRequest} asked over some of them (12 BR-25.1). */
    private ResultActions batchAsking(String quoteRequest, String... designs) throws Exception {
        StringBuilder body = new StringBuilder("{\"designs\":[");
        for (int i = 0; i < designs.length; i++) {
            body.append(i == 0 ? "" : ",").append("{\"id\":\"").append(designs[i]).append("\",\"version\":")
                    .append(version(designs[i])).append('}');
        }
        return mvc.perform(post("/api/v1/design-projects/" + project + "/review-rounds")
                .contentType(MediaType.APPLICATION_JSON).content(body.append("],\"quoteRequest\":").append(quoteRequest)
                        .append('}').toString()));
    }

    private static String quoteOver(String date, Object... designAndCartons) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < designAndCartons.length; i += 2) {
            lines.append(i == 0 ? "" : ",").append("{\"designId\":")
                    .append(designAndCartons[i] == null ? "null" : "\"" + designAndCartons[i] + "\"")
                    .append(",\"cartons\":").append(designAndCartons[i + 1]).append('}');
        }
        return "{\"lines\":[" + lines + "]" + (date == null ? "" : ",\"desiredDeliveryDate\":\"" + date + "\"") + "}";
    }

    private long projectRequests() {
        return jdbc.queryForObject("SELECT count(*) FROM design.design_project_quote_request WHERE project_id = ?::uuid",
                Long.class, project);
    }

    /** T15a (12 BR-25.1): four submitted with a quote, one failing its preflight: nothing submitted, nothing asked. */
    @Test
    void aBatchAskingAQuoteGoesWholeOrNotAtAll() throws Exception {
        String a = draft(project, middle());
        String b = draft(project, middle());
        String c = draft(project, middle());
        String broken = draft(project, surface.uTo() + 500);
        String date = java.time.LocalDate.now(com.stockflow.common.domain.BusinessCalendar.ZONE).plusDays(20).toString();

        batchAsking(quoteOver(date, a, 10, b, 20, c, 30, broken, 40), a, b, c, broken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_BATCH_PREFLIGHT_FAILED"))
                .andExpect(jsonPath("$.details.designs.length()").value(1));
        assertThat(List.of(statusOf(a), statusOf(b), statusOf(c), statusOf(broken))).containsOnly("DRAFT");
        assertThat(projectRequests()).isZero();
        assertThat(recorded.matching(DesignProjectQuoteRequested.class, e -> e.projectId().toString().equals(project)))
                .isEmpty();
    }

    /**
     * BR-25.1: what the batch asks is checked before anything else - every fault named under {@code quoteRequest.},
     * nothing submitted - then asked with the submission, in one step; Sales read "approved x/y" meanwhile and are told
     * once when the last design on it is approved (BR-25.2).
     */
    @Test
    void aBatchAsksAQuoteOverItsDesignsAndSalesHearWhenTheLastIsApproved() throws Exception {
        String cup = draft(project, middle());
        String mug = draft(project, middle());
        String bag = draft(project, middle());
        String alone = draft(null, middle());
        java.time.LocalDate today = java.time.LocalDate.now(com.stockflow.common.domain.BusinessCalendar.ZONE);

        batchAsking(quoteOver(today.minusDays(1).toString(), alone, 1, cup, 0, cup, 2, null, 3), cup, mug, bag)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quoteRequest.lines[0].designId')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quoteRequest.lines[1].cartons')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quoteRequest.lines[2].designId')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quoteRequest.lines[3].designId')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quoteRequest.desiredDeliveryDate')]").isNotEmpty());
        batchAsking(quoteOver(null, cup, 1_000_001), cup, mug).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quoteRequest.lines[0].cartons"));
        assertThat(List.of(statusOf(cup), statusOf(mug), statusOf(bag))).containsOnly("DRAFT");
        assertThat(projectRequests()).isZero();

        String date = today.plusDays(20).toString();
        JsonNode submitted = data(batchAsking(quoteOver(date, cup, 10, mug, 4), cup, mug, bag)
                .andExpect(status().isCreated()));
        JsonNode asked = submitted.get("quoteRequest");
        assertThat(asked.get("status").asText()).isEqualTo("OPEN");
        assertThat(asked.get("lines")).hasSize(2);
        assertThat(asked.get("lines").findValuesAsText("designStatus")).containsOnly("PENDING_REVIEW");
        assertThat(asked.get("approvedCount").asInt()).isZero();
        assertThat(asked.get("lineCount").asInt()).isEqualTo(2);
        assertThat(asked.get("desiredDeliveryDate").asText()).isEqualTo(date);
        assertThat(List.of(statusOf(cup), statusOf(mug), statusOf(bag))).containsOnly("PENDING_REVIEW");
        assertThat(recorded.matching(DesignProjectQuoteRequested.class, e -> e.projectId().toString().equals(project)))
                .singleElement().satisfies(e -> assertThat(e.lines()).hasSize(2));
        String request = asked.get("id").asText();

        as(manager, REVIEWER);
        decide(cup, true, null).andExpect(status().isOk());
        decide(bag, true, null).andExpect(status().isOk());
        assertThat(recorded.matching(DesignProjectQuoteRequestReady.class,
                e -> e.requestId().toString().equals(request))).as("the mug still waits").isEmpty();
        as(owner, Set.of());
        mvc.perform(get("/api/v1/design-projects/" + project + "/quote-requests"))
                .andExpect(jsonPath("$.data[0].approvedCount").value(1))
                .andExpect(jsonPath("$.data[0].lineCount").value(2));

        as(manager, REVIEWER);
        decide(mug, true, null).andExpect(status().isOk());
        assertThat(recorded.matching(DesignProjectQuoteRequestReady.class,
                e -> e.requestId().toString().equals(request))).singleElement()
                .satisfies(e -> assertThat(e.lines()).isEqualTo(2));
        as(owner, Set.of());
        mvc.perform(get("/api/v1/design-projects/" + project + "/quote-requests"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[0].approvedCount").value(2))
                .andExpect(jsonPath("$.data[0].lineCount").value(2));
    }

    /**
     * BR-25: a line returned for changes reads why; dropped by the customer, the rest is ready and Sales hear it; an
     * empty ask closes the request (BR-25.4), which is then changed no more.
     */
    @Test
    void aReturnedLineReadsWhyAndDroppingItMakesTheRequestReady() throws Exception {
        String cup = draft(project, middle());
        String mug = draft(project, middle());
        String date = java.time.LocalDate.now(com.stockflow.common.domain.BusinessCalendar.ZONE).plusDays(20).toString();
        String request = data(batchAsking(quoteOver(date, cup, 10, mug, 4), cup, mug)
                .andExpect(status().isCreated())).get("quoteRequest").get("id").asText();

        as(manager, REVIEWER);
        decide(cup, true, null).andExpect(status().isOk());
        decide(mug, false, "Logo lệch").andExpect(status().isOk());
        assertThat(recorded.matching(DesignProjectQuoteRequestReady.class,
                e -> e.requestId().toString().equals(request))).isEmpty();
        as(owner, Set.of());
        mvc.perform(get("/api/v1/design-projects/" + project + "/quote-requests"))
                .andExpect(jsonPath("$.data[0].lines[?(@.designId == '" + mug + "')].designStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].lines[?(@.designId == '" + mug + "')].returnReason").value("Logo lệch"));

        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"designId\":\"" + cup
                                + "\",\"cartons\":10}],\"desiredDeliveryDate\":\"" + date + "\",\"requestId\":\""
                                + request + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(request))
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.approvedCount").value(1));
        assertThat(recorded.matching(DesignProjectQuoteRequestReady.class,
                e -> e.requestId().toString().equals(request))).hasSize(1);

        // An empty ask closes it (BR-25.4); a closed request is changed no more.
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[],\"requestId\":\"" + request
                                + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        mvc.perform(post("/api/v1/design-projects/" + project + "/quote-requests")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"designId\":\"" + cup
                                + "\",\"cartons\":1}],\"requestId\":\"" + request + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_QUOTE_REQUEST_CLOSED"));
    }
}
