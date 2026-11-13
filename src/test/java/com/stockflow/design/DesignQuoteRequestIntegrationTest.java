package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.contracts.DesignQuoteRequested;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.design.internal.domain.PrintSurface;
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
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer asks the sales staff for a quote on their design, over HTTP (kltn-docs 12 §4.8; 16 §4.2 step 5; BR-15;
 * B40; plan D7): with a draft's submission - nothing sent if the preflight fails - or on an approved design; one open
 * request per design, which asking again changes; the sales staff list the requests, read a design one is open on while
 * it waits or once it is approved, and close the ones they answered; a returned design keeps its request, and deleting
 * the draft closes it.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
class DesignQuoteRequestIntegrationTest {

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
    private UUID sales;
    private String sku;
    private String design;
    private String logo;
    private PrintSurface surface;

    @BeforeEach
    void aDraftReadyToSubmit() throws Exception {
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = tx.execute(s -> ReferenceRows.user(entityManager));
        as(manager, Set.of());
        String code = "QRQ-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID productId = products.create(new CreateProductCommand(code, "Ly giấy 12oz", "Paper cup 12oz", null, null,
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
        design = data(mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku
                        + "\",\"templateCode\":\"CUP12-BODY\",\"name\":\"Ly quán A\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        logo = data(mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png(1200, 1200))))
                .andExpect(status().isCreated())).get("assetId").asText();
        String checksum = saveLayout((surface.uFrom() + surface.uTo()) / 2);
        upload("CUSTOMER_PREVIEW", "preview.png", "image/png", png(8, 8), checksum);
        upload("PRINT_READY", "print.pdf", "application/pdf", pdf(surface.flatWidth(), surface.flatHeight()),
                checksum);
        upload("TECHNICAL_SPEC", "spec.pdf", "application/pdf", pdf(210, 297), checksum);
    }

    private void as(UUID user, Set<PermissionCode> permissions) {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), permissions,
                DataScope.ALL, Set.of())));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?::uuid", Long.class, design);
    }

    /** The logo placed at {@code u} across the wall; the layout's checksum. */
    private String saveLayout(double u) throws Exception {
        double v = (surface.vFrom() + surface.vTo()) / 2;
        String layout = "{\"schemaVersion\":1,\"templateCode\":\"CUP12-BODY\",\"sku\":\"" + sku + "\",\"surfaces\":"
                + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[{\"id\":\"logo\",\"type\":\"image\","
                + "\"assetId\":\"" + logo + "\",\"naturalWidthPx\":1200,\"naturalHeightPx\":1200,\"widthMm\":30,"
                + "\"heightMm\":30,\"placement\":{\"u\":" + u + ",\"v\":" + v + ",\"rotationDeg\":0,\"scale\":1}}]}}}";
        return data(mvc.perform(put("/api/v1/designs/" + design + "/document")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version() + ",\"document\":"
                        + layout + "}"))
                .andExpect(status().isOk())).get("checksum").asText();
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

    private ResultActions submit(String quoteRequest) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/technical-review-requests")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version()
                        + (quoteRequest == null ? "" : ",\"quoteRequest\":" + quoteRequest) + "}"));
    }

    private ResultActions decide(boolean approved, String notes) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version() + ",\"approved\":" + approved
                        + (notes == null ? "" : ",\"notes\":\"" + notes + "\"") + "}"));
    }

    private ResultActions ask(String body) throws Exception {
        return mvc.perform(post("/api/v1/designs/" + design + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long requests() {
        return jdbc.queryForObject("SELECT count(*) FROM design.design_quote_request WHERE draft_id = ?::uuid",
                Long.class, design);
    }

    private List<String> decisions() {
        return jdbc.queryForList("SELECT decision FROM design.design_decision WHERE draft_id = ?::uuid "
                + "ORDER BY created_at, id", String.class, design);
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessCalendar.ZONE);
    }

    @Test
    void aDraftSubmittedWithARequestReachesTheSalesStaffAtOnceAndIsQuotedOnceApproved() throws Exception {
        LocalDate wanted = today().plusDays(30);
        submit("{\"cartons\":20,\"desiredDeliveryDate\":\"" + wanted + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        assertThat(decisions()).containsSubsequence("SUBMITTED_FOR_REVIEW", "QUOTE_REQUESTED");
        DesignQuoteRequested asked = recorded.matching(DesignQuoteRequested.class,
                e -> e.designId().toString().equals(design)).getFirst();
        assertThat(asked.designStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(asked.customerSku()).isNull();
        assertThat(asked.cartons()).isEqualTo(20);
        assertThat(asked.desiredDeliveryDate()).isEqualTo(wanted);

        // The sales staff have it at once, with the design's status: not quotable yet (BR-15).
        as(sales, SALES);
        JsonNode listed = data(mvc.perform(get("/api/v1/design-quote-requests").param("customerId",
                customerId.toString()).param("status", "OPEN")).andExpect(status().isOk())).get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asText()).isEqualTo(asked.requestId().toString());
        assertThat(listed.get(0).get("designStatus").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(listed.get(0).get("cartons").asInt()).isEqualTo(20);
        assertThat(listed.get(0).get("desiredDeliveryDate").asText()).isEqualTo(wanted.toString());
        assertThat(listed.get(0).has("customerSku")).isFalse();
        // And they read the design it is on, read-only (plan D7).
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/designs/" + design + "/artifacts")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/designs/" + design + "/quote-requests")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        // Without the right to read requests, nobody but the owner and a reviewer.
        as(sales, Set.of(PermissionCode.of("design-designs", Action.READ)));
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(status().isNotFound());
        // And the whole list, every customer, with no filter at all (no parameter PostgreSQL must type).
        as(sales, SALES);
        mvc.perform(get("/api/v1/design-quote-requests")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + asked.requestId() + "')]").isNotEmpty());

        // Approved: the sales staff are told, and the request now carries the customer's SKU.
        as(manager, REVIEWER);
        decide(true, null).andExpect(status().isOk());
        DesignReviewed decided = recorded.matching(DesignReviewed.class,
                e -> e.designId().toString().equals(design)).getFirst();
        assertThat(decided.openQuoteRequestId()).isEqualTo(asked.requestId());
        as(sales, SALES);
        JsonNode quotable = data(mvc.perform(get("/api/v1/design-quote-requests").param("customerId",
                customerId.toString())).andExpect(status().isOk())).get("items").get(0);
        assertThat(quotable.get("designStatus").asText()).isEqualTo("APPROVED");
        assertThat(quotable.get("customerSku").asText()).isEqualTo(decided.sku());
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(status().isOk());
    }

    @Test
    void nothingIsSentWhenThePreflightFailsOrTheRequestIsMalformed() throws Exception {
        submit("{\"cartons\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quoteRequest.cartons"));
        submit("{\"cartons\":1000001}").andExpect(status().isBadRequest());
        submit("{\"cartons\":5,\"desiredDeliveryDate\":\"" + today().minusDays(1) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quoteRequest.desiredDeliveryDate"));
        // The logo far off the wall: the preflight refuses, and the request goes nowhere (§4.8 step 24).
        saveLayout(surface.uTo() + 500);
        submit("{\"cartons\":5}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PREFLIGHT_FAILED"));

        assertThat(requests()).isZero();
        assertThat(decisions()).doesNotContain("QUOTE_REQUESTED");
        assertThat(recorded.matching(DesignQuoteRequested.class, e -> e.designId().toString().equals(design)))
                .isEmpty();
    }

    @Test
    void aReturnedDesignKeepsItsRequestAndDeletingTheDraftClosesIt() throws Exception {
        submit("{\"cartons\":10}").andExpect(status().isOk());
        UUID request = jdbc.queryForObject("SELECT id FROM design.design_quote_request WHERE draft_id = ?::uuid",
                UUID.class, design);

        as(manager, REVIEWER);
        decide(false, "Logo quá sát mép").andExpect(status().isOk());
        assertThat(recorded.matching(DesignReviewed.class, e -> e.designId().toString().equals(design)))
                .singleElement().satisfies(e -> assertThat(e.openQuoteRequestId()).isEqualTo(request));

        // The request stays open and shows the design as a draft with its reason (B40)...
        as(sales, SALES);
        JsonNode card = data(mvc.perform(get("/api/v1/design-quote-requests").param("customerId",
                customerId.toString())).andExpect(status().isOk())).get("items").get(0);
        assertThat(card.get("status").asText()).isEqualTo("OPEN");
        assertThat(card.get("designStatus").asText()).isEqualTo("DRAFT");
        assertThat(card.get("returnReason").asText()).isEqualTo("Logo quá sát mép");
        // ...but the draft is the customer's alone again.
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(status().isNotFound());

        // Submitted again with new numbers, the request follows the design and asks them: no second request.
        as(owner, Set.of());
        submit("{\"cartons\":30}").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        assertThat(requests()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cartons FROM design.design_quote_request WHERE id = ?", Integer.class,
                request)).isEqualTo(30);
        assertThat(decisions()).last().isEqualTo("QUOTE_REQUESTED");

        // Returned once more and deleted: the request closes with the draft.
        as(manager, REVIEWER);
        decide(false, "Vẫn sát mép").andExpect(status().isOk());
        as(owner, Set.of());
        mvc.perform(post("/api/v1/designs/" + design + "/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version() + "}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status || ':' || (closed_at IS NOT NULL) FROM "
                + "design.design_quote_request WHERE id = ?", String.class, request)).isEqualTo("CLOSED:true");
        as(sales, SALES);
        mvc.perform(get("/api/v1/design-quote-requests").param("customerId", customerId.toString())
                .param("status", "OPEN")).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void anApprovedDesignIsAskedOnItsOwnAgainWhileOpenAndAnewOnceAnswered() throws Exception {
        submit(null).andExpect(status().isOk());
        // Waiting for review, a quote is asked with the submission, not on its own.
        ask("{\"cartons\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_APPROVED"));
        as(manager, REVIEWER);
        decide(true, null).andExpect(status().isOk());
        String customerSku = recorded.matching(DesignReviewed.class, e -> e.designId().toString().equals(design))
                .getFirst().sku();

        as(owner, Set.of());
        ask("{\"cartons\":50,\"desiredDeliveryDate\":\"" + today() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.designStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.customerSku").value(customerSku))
                .andExpect(jsonPath("$.data.cartons").value(50))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        assertThat(recorded.matching(DesignQuoteRequested.class, e -> e.designId().toString().equals(design)))
                .singleElement().satisfies(e -> assertThat(e.customerSku()).isEqualTo(customerSku));
        assertThat(decisions()).last().isEqualTo("QUOTE_REQUESTED");
        // Asked again while open: the same request asks the new numbers.
        String first = data(ask("{\"cartons\":60}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons").value(60))).get("id").asText();
        mvc.perform(get("/api/v1/designs/" + design + "/quote-requests")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Answered, the salesperson closes it; closing it again changes nothing.
        as(sales, SALES);
        mvc.perform(post("/api/v1/design-quote-requests/" + first + "/closure")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedBy").value(sales.toString()));
        mvc.perform(post("/api/v1/design-quote-requests/" + first + "/closure")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value(sales.toString()));
        mvc.perform(post("/api/v1/design-quote-requests/" + UUID.randomUUID() + "/closure"))
                .andExpect(status().isNotFound());
        // The design's next order: a new request.
        as(owner, Set.of());
        String second = data(ask("{\"cartons\":10}").andExpect(status().isCreated())).get("id").asText();
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get("/api/v1/designs/" + design + "/quote-requests")).andExpect(jsonPath("$.data.length()").value(2));

        // Nobody else asks on it, nor reads its requests.
        UUID stranger = tx.execute(s -> ReferenceRows.user(entityManager));
        as(stranger, Set.of());
        ask("{\"cartons\":5}").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + design + "/quote-requests")).andExpect(status().isNotFound());
        // Another customer's filter shows none of it.
        as(sales, SALES);
        mvc.perform(get("/api/v1/design-quote-requests").param("customerId", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void theSalesStaffDoNotReadADesignWaitingForReviewThatAsksNothing() throws Exception {
        submit(null).andExpect(status().isOk());
        as(sales, SALES);
        mvc.perform(get("/api/v1/designs/" + design)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/designs/" + design + "/quote-requests")).andExpect(status().isNotFound());
    }

    @Test
    void aCustomerNoLongerActiveAsksNothing() throws Exception {
        submit(null).andExpect(status().isOk());
        as(manager, REVIEWER);
        decide(true, null).andExpect(status().isOk());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = 'INACTIVE' WHERE id = ?",
                customerId));

        as(owner, Set.of());
        ask("{\"cartons\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_ACTIVE"));
        assertThat(requests()).isZero();
    }

    @Test
    void twoAsksAtOnceMakeOneRequest() throws Exception {
        submit(null).andExpect(status().isOk());
        as(manager, REVIEWER);
        decide(true, null).andExpect(status().isOk());
        as(owner, Set.of());

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> asks = new java.util.ArrayList<>();
            for (int cartons : new int[] {5, 7}) {
                asks.add(pool.submit(() -> {
                    start.await();
                    return ask("{\"cartons\":" + cartons + "}").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var answer : asks) {
                statuses.add(answer.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(requests()).isEqualTo(1);
    }
}
