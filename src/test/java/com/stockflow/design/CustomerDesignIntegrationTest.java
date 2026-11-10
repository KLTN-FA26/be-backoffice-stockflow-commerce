package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.json.CanonicalJson;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.design.internal.service.CustomerDesignService;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A design a customer makes on their own, through HTTP (kltn-docs 12 §4.1, §4.2, §4.7): started on a variant's active
 * template with the studio's empty layout, its layout saved canonically under the design's version, renamed, deleted
 * while a draft and then gone for everyone, reached by its owner only, at most 50 drafts a customer, and revised from an
 * approved design with its layout and images.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class CustomerDesignIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired CustomerDesignService designs;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private UUID staff;
    private UUID productId;
    private String sku;
    private String templatesBase;
    private String templateId;
    private UUID owner;
    private UUID customerId;

    @BeforeEach
    void customizableProductWithAnActiveTemplateAndACustomer() throws Exception {
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        as(staff);
        productId = approvedProduct(ProductKind.CUSTOMIZABLE);
        var variant = variants.list(productId, 0, 1).items().getFirst();
        sku = variant.sku();
        templatesBase = "/api/v1/products/" + productId + "/variants/" + variant.id() + "/customization-templates";
        JsonNode template = data(mvc.perform(post(templatesBase).contentType(MediaType.APPLICATION_JSON)
                .content(cupTemplate("CUP12-BODY"))).andExpect(status().isCreated()));
        templateId = template.get("templateId").asText();
        mvc.perform(post(templatesBase + "/" + templateId + "/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + template.get("version") + "}")).andExpect(status().isOk());
        UUID[] account = customerAccount();
        owner = account[0];
        customerId = account[1];
        as(owner);
    }

    // ---- fixtures ----

    private void as(UUID user) {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
    }

    /** An identity user and the ACTIVE customer whose own login it is: {user, customer}. */
    private UUID[] customerAccount() {
        return tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            UUID customer = ReferenceRows.customer(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", user, customer);
            return new UUID[] {user, customer};
        });
    }

    private UUID approvedProduct(ProductKind kind) {
        String code = "CDS-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID id = products.create(new CreateProductCommand(code, "Ly giấy 12oz", "Paper cup 12oz", null, null, "Ly",
                null, null, TaxClass.STANDARD, List.of())).productId();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.products SET status = 'APPROVED', submitted_by = ?, submitted_at = NOW(), "
                    + "approved_by = ?, approved_at = NOW() WHERE id = ?", ReferenceRows.user(entityManager),
                    ReferenceRows.user(entityManager), id);
            jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE product_id = ?", id);
        });
        return id;
    }

    private static String cupTemplate(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Ly 12oz - thân ly\",\"allowedFileFormats\":[\"image/png\"],"
                + "\"maxFileSizeMb\":20,\"model\":{\"kind\":\"parametric-cup\",\"topDiameterMm\":90,"
                + "\"bottomDiameterMm\":60,\"heightMm\":110,\"rimMm\":3,\"seamMm\":8,\"bandTopOffsetMm\":6,"
                + "\"bandBottomOffsetMm\":8,\"material\":\"paper-white\"},"
                + "\"printAreas\":[{\"code\":\"BODY\",\"name\":\"Thân ly\",\"safeMarginMm\":3,\"bleedMm\":2}]}";
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private ResultActions start(String name) throws Exception {
        return mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku
                        + "\",\"templateCode\":\"CUP12-BODY\",\"name\":\"" + name + "\"}"));
    }

    private JsonNode started() throws Exception {
        return data(start("Ly quán A").andExpect(status().isCreated()));
    }

    private ResultActions save(String id, long version, String document) throws Exception {
        return mvc.perform(put("/api/v1/designs/" + id + "/document").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"document\":" + document + "}"));
    }

    /** A layout of this design's template with {@code elements} on the cup's wall. */
    private String layout(String elements) {
        return "{\"schemaVersion\": 1, \"templateCode\": \"CUP12-BODY\", \"sku\": \"" + sku + "\", \"surfaces\": "
                + "{\"BODY\": {\"background\": {\"kind\": \"none\"}, \"elements\": [" + elements + "]}}}";
    }

    private static String text(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"text\",\"content\":\"Cà phê A\",\"sizePt\":12.50,"
                + "\"placement\":{\"u\":1e2,\"v\":-0.0,\"rotationDeg\":0,\"scale\":1}}";
    }

    private static String image(String id, String assetId) {
        return "{\"id\":\"" + id + "\",\"type\":\"image\",\"assetId\":\"" + assetId + "\",\"naturalWidthPx\":1000,"
                + "\"naturalHeightPx\":800,\"widthMm\":50,\"heightMm\":40}";
    }

    private UUID uploadedImage(String draftId) {
        UUID asset = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO design.design_asset (draft_id, id, storage_key, "
                + "original_name, content_type, size_bytes, checksum, width_px, height_px, created_at) VALUES "
                + "(?::uuid, ?, 'design-assets/2026/11/' || ? || '.png', 'logo.png', 'image/png', 2048, ?, 1000, 800, "
                + "NOW())", draftId, asset, asset.toString(), "b".repeat(64)));
        return asset;
    }

    private String storedLayout(String id) {
        return jdbc.queryForObject("SELECT document FROM design.design_document WHERE draft_id = ?::uuid",
                String.class, id);
    }

    // ---- tests ----

    @Test
    void aCustomerStartsADraftOnTheTemplateWithTheStudiosEmptyLayout() throws Exception {
        JsonNode draft = data(start("  Ly quán A  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.sku").value(sku))
                .andExpect(jsonPath("$.data.templateCode").value("CUP12-BODY"))
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.ownerUserId").value(owner.toString()))
                .andExpect(jsonPath("$.data.name").value("Ly quán A"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.updatedAt").exists()));
        String id = draft.get("id").asText();
        assertThat(draft.get("productSlug").asText()).isNotBlank();
        assertThat(draft.has("reviewerUserId")).isFalse();

        JsonNode document = data(mvc.perform(get("/api/v1/designs/" + id + "/document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.document.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.document.surfaces.BODY.background.kind").value("none"))
                .andExpect(jsonPath("$.data.document.surfaces.BODY.elements.length()").value(0)));
        String stored = storedLayout(id);
        assertThat(CanonicalJson.canonicalize(stored)).isEqualTo(stored);
        assertThat(document.get("checksum").asText()).isEqualTo(CanonicalJson.sha256Hex(stored));
        assertThat(CanonicalJson.canonicalize(document.get("document").toString())).isEqualTo(stored);

        mvc.perform(get("/api/v1/designs/" + id + "/history"))
                .andExpect(jsonPath("$.data.items[0].decision").value("CREATED"));
        mvc.perform(get("/api/v1/designs").param("status", "DRAFT"))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    @Test
    void aLayoutIsStoredCanonicallyAndEverySaveMovesTheVersion() throws Exception {
        String id = started().get("id").asText();
        String sent = layout(text("t1"));

        JsonNode saved = data(save(id, 0, sent).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.document.surfaces.BODY.elements[0].placement.u").value(100)));
        String stored = storedLayout(id);
        assertThat(stored).isEqualTo(CanonicalJson.canonicalize(sent))
                .contains("\"sizePt\":12.5").contains("\"u\":100").contains("\"v\":0");
        assertThat(saved.get("checksum").asText()).isEqualTo(CanonicalJson.sha256Hex(stored));

        // A second tab still holding version 0 is refused, not allowed to overwrite.
        save(id, 0, layout("")).andExpect(status().isConflict());
        assertThat(storedLayout(id)).isEqualTo(stored);
        mvc.perform(get("/api/v1/designs/" + id)).andExpect(jsonPath("$.data.version").value(1));

        save(id, 1, layout("")).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get("/api/v1/designs/" + id + "/document")).andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void aLayoutThatIsNotThisDesignsIsRefused() throws Exception {
        String id = started().get("id").asText();

        save(id, 0, layout("").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("document"));
        save(id, 0, layout("").replace("CUP12-BODY", "CUP12-OTHER")).andExpect(status().isBadRequest());
        save(id, 0, layout("").replace("\"BODY\"", "\"FRONT\"")).andExpect(status().isBadRequest());
        save(id, 0, layout(text("t1") + "," + text("t1"))).andExpect(status().isBadRequest());
        save(id, 0, layout("{\"id\":\"x\",\"type\":\"video\"}")).andExpect(status().isBadRequest());
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            many.add(text("t" + i));
        }
        save(id, 0, layout(String.join(",", many))).andExpect(status().isBadRequest());
        // An image is one uploaded into this design, or none.
        save(id, 0, layout(image("i1", UUID.randomUUID().toString()))).andExpect(status().isBadRequest());
        String otherDesign = started().get("id").asText();
        UUID theirs = uploadedImage(otherDesign);
        save(id, 0, layout(image("i1", theirs.toString()))).andExpect(status().isBadRequest());
        UUID mine = uploadedImage(id);
        save(id, 0, layout(image("i1", mine.toString()))).andExpect(status().isOk());

        // The body is read as sent: a member given twice is refused, not silently taken once.
        mvc.perform(put("/api/v1/designs/" + id + "/document").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"version\":1,\"document\":" + layout("") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        mvc.perform(put("/api/v1/designs/" + id + "/document").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document\":" + layout("") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
        assertThat(jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?::uuid", Long.class, id))
                .isEqualTo(1);
    }

    @Test
    void aLayoutOverTheLimitIsRefusedEvenWhenOnlyItsCanonicalFormIs() throws Exception {
        String id = started().get("id").asText();
        // Over the request cap: refused before anything reads it.
        save(id, 0, layout("{\"id\":\"t\",\"type\":\"text\",\"content\":\"" + "x".repeat(1_100_000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_BODY_TOO_LARGE"));
        // Under the cap, over the document limit.
        String padding = "\"" + "x".repeat(1_000_000) + "\"";
        save(id, 0, layout("{\"id\":\"t\",\"type\":\"text\",\"content\":" + padding + "}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_BODY_TOO_LARGE"));

        // 1e20 is four characters as sent and twenty-one once canonical: 300 kB in, 1.3 MB to store.
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            numbers.add("1e20");
        }
        String strokes = "{\"id\":\"d\",\"type\":\"drawing\",\"strokes\":[" + String.join(",", numbers) + "]}";
        save(id, 0, layout(strokes))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_BODY_TOO_LARGE"));
        assertThat(jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?::uuid", Long.class, id))
                .isZero();
    }

    @Test
    void aDraftIsRenamedAndDeletedAndThenGoneForEveryone() throws Exception {
        String id = started().get("id").asText();

        mvc.perform(put("/api/v1/designs/" + id + "/name").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Ly chi nhánh 2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Ly chi nhánh 2"))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put("/api/v1/designs/" + id + "/name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Cũ\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/designs/" + id + "/name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"name\":\" \"}")).andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/designs/" + id + "/archive").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        mvc.perform(get("/api/v1/designs/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + id + "/document")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + id + "/history")).andExpect(status().isNotFound());
        save(id, 2, layout("")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs")).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/designs").param("status", "ARCHIVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        mvc.perform(get("/api/v1/designs").param("status", "SUBMITTED")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM design.design_draft WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_decision WHERE draft_id = ?::uuid AND "
                + "decision = 'ARCHIVED'", Long.class, id)).isEqualTo(1);
    }

    @Test
    void onlyItsOwnerReachesADesign() throws Exception {
        String id = started().get("id").asText();
        as(customerAccount()[0]);

        mvc.perform(get("/api/v1/designs/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + id + "/document")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + id + "/template")).andExpect(status().isNotFound());
        save(id, 0, layout("")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/designs/" + id + "/name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/designs/" + id + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs")).andExpect(jsonPath("$.data.totalElements").value(0));

        // Staff do not start a customer's design (BR-01): there is no customer to own it.
        as(staff);
        start("Nhân viên tạo").andExpect(status().isForbidden());
    }

    @Test
    void aDesignThatIsNoLongerADraftDoesNotChange() throws Exception {
        String id = started().get("id").asText();
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE design.design_draft SET status = 'PENDING_REVIEW', review_requested_at = NOW() WHERE id = ?::uuid", id));

        save(id, 0, layout("")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        mvc.perform(put("/api/v1/designs/" + id + "/name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"x\"}")).andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        mvc.perform(post("/api/v1/designs/" + id + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_DRAFT"));
        mvc.perform(get("/api/v1/designs/" + id + "/document")).andExpect(status().isOk());
    }

    @Test
    void aCustomerHoldsAtMostFiftyDrafts() throws Exception {
        String one = started().get("id").asText();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 49; i++) {
                jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                        + "current_artifacts, owner_user_id) VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb, ?)",
                        Identifiers.newId(), customerId, productId, owner);
            }
        });
        start("Bản thứ 51").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_DRAFT_LIMIT_REACHED"));

        mvc.perform(post("/api/v1/designs/" + one + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        start("Bản thứ 50").andExpect(status().isCreated());
    }

    /** Two tabs starting the fiftieth draft at once: the per-customer lock lets exactly one through. */
    @Test
    void twoDraftsStartedAtOnceCannotBothTakeTheLastPlace() throws Exception {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 49; i++) {
                jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                        + "current_artifacts, owner_user_id) VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb, ?)",
                        Identifiers.newId(), customerId, productId, owner);
            }
        });
        var command = new CustomerDesignService.StartDesign(productId, sku, "CUP12-BODY", "Cùng lúc", null);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ErrorCode>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        designs.start(owner, command);
                        return null;
                    } catch (BusinessException ex) {
                        return ex.errorCode();
                    }
                }));
            }
            go.countDown();
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(null, ErrorCode.DESIGN_DRAFT_LIMIT_REACHED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE customer_id = ? AND "
                + "status = 'DRAFT'", Long.class, customerId)).isEqualTo(50);
    }

    @Test
    void whatCannotBeDesignedOnIsRefused() throws Exception {
        mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku
                                + "\",\"templateCode\":\"NO-SUCH\",\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_NOT_FOUND"));
        mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku + "\",\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());

        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'BLOCKED' WHERE sku = ?", sku));
        start("x").andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_NOT_FOUND"));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE sku = ?", sku));

        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = 'BLOCKED' WHERE id = ?",
                customerId));
        start("x").andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_ACTIVE"));
    }

    /** B39: a draft keeps its template as it is now, even once customers can no longer start designs on it. */
    @Test
    void aDraftKeepsItsTemplateWhenTheTemplateIsDeactivated() throws Exception {
        String id = started().get("id").asText();
        mvc.perform(get("/api/v1/designs/" + id + "/template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("CUP12-BODY"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.printAreas[0].code").value("BODY"))
                .andExpect(jsonPath("$.data.printAreas[0].shape.type").value("annular-sector"));

        as(staff);
        long version = jdbc.queryForObject("SELECT version FROM product.customization_templates WHERE id = ?::uuid",
                Long.class, templateId);
        mvc.perform(post(templatesBase + "/" + templateId + "/deactivation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}")).andExpect(status().isOk());
        as(owner);

        mvc.perform(get("/api/v1/designs/" + id + "/template")).andExpect(jsonPath("$.data.active").value(false));
        save(id, 0, layout(text("t1"))).andExpect(status().isOk());
        start("Bản mới").andExpect(status().isNotFound());
    }

    @Test
    void aRevisionOfAnApprovedDesignCopiesItsLayoutAndImages() throws Exception {
        String id = started().get("id").asText();
        UUID logo = uploadedImage(id);
        save(id, 0, layout(image("i1", logo.toString()) + "," + text("t1"))).andExpect(status().isOk());
        UUID snapshot = Identifiers.newId();
        UUID preview = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE design.design_draft SET status = 'APPROVED' WHERE id = ?::uuid", id);
            jdbc.update("INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, "
                    + "size_bytes, stored_at, checksum, created_at, artifact_role) VALUES (?, ?::uuid, "
                    + "'design-artifacts/2026/11/' || ? || '.png', 'preview.png', 'image/png', 10, NOW(), ?, NOW(), "
                    + "'CUSTOMER_PREVIEW')", preview, id, preview.toString(), "c".repeat(64));
            jdbc.update("INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                    + "created_at, artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec) VALUES (?, ?::uuid, "
                    + "?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb, ?, ?, ?, 'Logo')", snapshot, id,
                    "a".repeat(64), preview, staff, staff);
        });

        JsonNode revision = data(mvc.perform(post("/api/v1/designs/" + id + "/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.parentSnapshotId").value(snapshot.toString()))
                .andExpect(jsonPath("$.data.sku").value(sku))
                .andExpect(jsonPath("$.data.templateCode").value("CUP12-BODY")));
        String copy = revision.get("id").asText();

        assertThat(storedLayout(copy)).isEqualTo(storedLayout(id));
        assertThat(jdbc.queryForObject("SELECT storage_key FROM design.design_asset WHERE draft_id = ?::uuid AND id = ?",
                String.class, copy, logo)).startsWith("design-assets/");
        mvc.perform(get("/api/v1/designs/" + copy + "/history"))
                .andExpect(jsonPath("$.data.items[0].decision").value("DERIVED_FROM_SNAPSHOT"));
        // The copy is a draft of its own: its layout saves, the approved one's does not move.
        save(copy, 0, layout(image("i1", logo.toString()))).andExpect(status().isOk());
        assertThat(storedLayout(id)).contains("t1");

        // Once the template is retired, the approved layout has nothing to be drawn on: no revision.
        as(staff);
        long version = jdbc.queryForObject("SELECT version FROM product.customization_templates WHERE id = ?::uuid",
                Long.class, templateId);
        mvc.perform(post(templatesBase + "/" + templateId + "/deactivation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}")).andExpect(status().isOk());
        as(owner);
        mvc.perform(post("/api/v1/designs/" + id + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRINT_CONFIG_NOT_FOUND"));
    }

    /** Review of 2F-3: the request carries more than the document, so a document at the limit must still fit. */
    @Test
    void aDocumentAtTheLimitSavesThoughItsRequestIsLarger() throws Exception {
        String id = started().get("id").asText();
        String empty = CanonicalJson.canonicalize(layout("{\"id\":\"t\",\"type\":\"text\",\"content\":\"\"}"));
        int room = 1_000_000 - empty.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String full = layout("{\"id\": \"t\", \"type\": \"text\", \"content\": \"" + "x".repeat(room) + "\"}");

        save(id, 0, full).andExpect(status().isOk());
        assertThat(storedLayout(id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(1_000_000);
    }

    /**
     * 2F-6: the staff workflow is gone - its endpoints no longer exist - and staff never reach a customer's draft.
     */
    @Test
    void theStaffWorkflowIsGone() throws Exception {
        String id = started().get("id").asText();
        String base = "/api/v1/designs/" + id;

        as(staff);
        mvc.perform(get(base)).andExpect(status().isNotFound());
        as(owner);
        String[][] removed = {
                {"PUT", base + "/assignment", "{\"version\":0,\"reviewerId\":\"" + staff + "\"}"},
                {"PUT", base + "/specification", "{\"version\":0,\"spec\":\"x\"}"},
                {"POST", base + "/confirmation-requests", "{\"version\":0}"},
                {"POST", base + "/confirmations", "{\"version\":0,\"artifactId\":\"" + UUID.randomUUID() + "\"}"},
                {"POST", base + "/change-requests", "{\"version\":0,\"reason\":\"x\"}"},
                {"POST", base + "/confirmation-withdrawals", "{\"version\":0,\"reason\":\"x\"}"},
                {"POST", base + "/technical-reviews", "{\"version\":0}"},
                {"POST", "/api/v1/designs", "{}"}};
        for (String[] step : removed) {
            var request = step[0].equals("PUT") ? put(step[1]) : post(step[1]);
            int answered = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(step[2]))
                    .andReturn().getResponse().getStatus();
            assertThat(answered).as(step[0] + " " + step[1]).isIn(404, 405);
        }
        mvc.perform(get(base)).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void theStatusFilterTakesAnyCaseAndABlankOneIsNone() throws Exception {
        String id = started().get("id").asText();

        mvc.perform(get("/api/v1/designs").param("status", "draft")).andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(get("/api/v1/designs").param("status", "")).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/v1/designs").param("status", "approved")).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /** Review of 2F-3: a revision of a design staff started is one more draft of the customer's too (B37). */
    @Test
    void aRevisionOfADesignStaffStartedCountsAgainstTheLimit() throws Exception {
        UUID approved = Identifiers.newId();
        UUID preview = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                    + "current_artifacts, owner_user_id) VALUES (?, ?, ?, 'APPROVED', NOW(), '{}'::jsonb, ?)",
                    approved, customerId, productId, owner);
            jdbc.update("INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, "
                    + "size_bytes, stored_at, checksum, created_at, artifact_role) VALUES (?, ?, "
                    + "'design-artifacts/2026/11/' || ? || '.png', 'preview.png', 'image/png', 10, NOW(), ?, NOW(), "
                    + "'CUSTOMER_PREVIEW')", preview, approved, preview.toString(), "c".repeat(64));
            jdbc.update("INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                    + "created_at, artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec) VALUES (?, ?, ?, "
                    + "'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb, ?, ?, ?, 'Logo')", Identifiers.newId(),
                    approved, "a".repeat(64), preview, staff, staff);
            for (int i = 0; i < 50; i++) {
                jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                        + "current_artifacts, owner_user_id) VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb, ?)",
                        Identifiers.newId(), customerId, productId, owner);
            }
        });

        mvc.perform(post("/api/v1/designs/" + approved + "/revisions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_DRAFT_LIMIT_REACHED"));
    }
}
