package com.stockflow.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.design.api.DesignArtifactRole;
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
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Design Studio from A to Z on a real object store (team plan 2F-10): PostgreSQL and MinIO, every file through S3 -
 * the template, a design started and laid out, images uploaded both through the backend and straight to the store
 * with a signed PUT, the three files rendered, a submission refused by the preflight and one that passes with a quote
 * request, a return, a resubmission, the approval (print file stamped and production instruction drawn from the store,
 * snapshot, the customer's SKU), a revision, the quote asked again - and the production side downloading the print
 * file by its checksum from a browser origin, as the storefront and the producer's screen will.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
class DesignStudioEndToEndTest {

    private static final String BUCKET = "stockflow-e2e";
    private static final String KEY = "stockflow-e2e";
    private static final String SECRET = "stockflow-e2e-secret";
    private static final String STOREFRONT = "http://localhost:3000";

    // As MinioMediaIntegrationTest: the community-maintained build of the same server.
    private static final GenericContainer<?> MINIO = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .withEnv("MINIO_ROOT_USER", KEY).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server /data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static {
        MINIO.start();
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(KEY, SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
    }

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        registry.add("stockflow.storage.provider", () -> "s3");
        registry.add("stockflow.storage.bucket", () -> BUCKET);
        registry.add("stockflow.storage.endpoint", DesignStudioEndToEndTest::endpoint);
        registry.add("stockflow.storage.region", () -> "ap-southeast-1");
        registry.add("stockflow.storage.path-style", () -> "true");
        registry.add("stockflow.storage.access-key", () -> KEY);
        registry.add("stockflow.storage.secret-key", () -> SECRET);
    }

    private static final Set<PermissionCode> REVIEWER = Set.of(PermissionCode.of("design-designs", Action.APPROVE),
            PermissionCode.of("design-designs", Action.READ));

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired PrintConfigs printConfigs;
    @Autowired UomConversionService units;
    @Autowired DesignService designs;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private final HttpClient browser = HttpClient.newHttpClient();
    private PrintSurface surface;
    private String sku;
    private String design;

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

    /** The layout with the logo at {@code u} across the wall; the files rendered from it. */
    private void layOutAndRender(String logo, double u) throws Exception {
        double v = (surface.vFrom() + surface.vTo()) / 2;
        String layout = "{\"schemaVersion\":1,\"templateCode\":\"CUP12-BODY\",\"sku\":\"" + sku + "\",\"surfaces\":"
                + "{\"BODY\":{\"background\":{\"kind\":\"none\"},\"elements\":[{\"id\":\"logo\",\"type\":\"image\","
                + "\"assetId\":\"" + logo + "\",\"naturalWidthPx\":1200,\"naturalHeightPx\":1200,\"widthMm\":30,"
                + "\"heightMm\":30,\"placement\":{\"u\":" + u + ",\"v\":" + v + ",\"rotationDeg\":0,\"scale\":1}}]}}}";
        String checksum = data(mvc.perform(put("/api/v1/designs/" + design + "/document")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version() + ",\"document\":"
                        + layout + "}")).andExpect(status().isOk())).get("checksum").asText();
        render("CUSTOMER_PREVIEW", "preview.png", "image/png", png(8, 8), checksum);
        render("PRINT_READY", "print.pdf", "application/pdf", pdf(surface.flatWidth(), surface.flatHeight()), checksum);
        render("TECHNICAL_SPEC", "spec.pdf", "application/pdf", pdf(210, 297), checksum);
    }

    private void render(String role, String name, String type, byte[] bytes, String checksum) throws Exception {
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

    @Test
    void aCustomerDesignGoesFromTemplateToPrintFileThroughTheObjectStore() throws Exception {
        // The Sales Manager's template.
        UUID manager = tx.execute(s -> ReferenceRows.user(entityManager));
        as(manager, REVIEWER);
        String code = "E2E-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
        mvc.perform(post(base + "/" + template.get("templateId").asText() + "/activation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + template.get("version") + "}"))
                .andExpect(status().isOk());
        var config = printConfigs.byId(UUID.fromString(template.get("templateId").asText())).orElseThrow();
        surface = PrintSurface.of(config.model(), config.printAreas().get(0));
        units.add(new Sku(sku), new UomConversionDraft("CARTON", 50, new BigDecimal("4.2"), new BigDecimal("60"),
                new BigDecimal("40"), new BigDecimal("35"), false, true, true));

        // The customer starts a design.
        String taxCode = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L,
                9_999_999_999L));
        UUID owner = tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            UUID customer = ReferenceRows.customer(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ?, tax_code = ? WHERE id = ?", user, taxCode, customer);
            return user;
        });
        as(owner, Set.of());
        design = data(mvc.perform(post("/api/v1/designs/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"sku\":\"" + sku
                        + "\",\"templateCode\":\"CUP12-BODY\",\"name\":\"Ly quán A\"}"))
                .andExpect(status().isCreated())).get("id").asText();

        // An image through the backend, and one straight to the store with a signed PUT.
        String logo = data(mvc.perform(multipart("/api/v1/designs/" + design + "/assets")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png(1200, 1200))))
                .andExpect(status().isCreated())).get("assetId").asText();
        byte[] photo = png(1000, 800);
        JsonNode slot = data(mvc.perform(post("/api/v1/designs/" + design + "/assets/uploads")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"photo.png\",\"contentType\":"
                        + "\"image/png\",\"sizeBytes\":" + photo.length + ",\"sha256\":\"" + Checksums.sha256Hex(photo)
                        + "\"}")).andExpect(status().isCreated()));
        var signedPut = HttpRequest.newBuilder(URI.create(slot.get("url").asText()))
                .method(slot.get("method").asText(), HttpRequest.BodyPublishers.ofByteArray(photo));
        slot.get("headers").fields().forEachRemaining(h -> {
            if (!h.getKey().equalsIgnoreCase("host") && !h.getKey().equalsIgnoreCase("content-length")) {
                signedPut.header(h.getKey(), h.getValue().asText());
            }
        });
        assertThat(browser.send(signedPut.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        mvc.perform(post("/api/v1/designs/" + design + "/assets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"" + slot.get("uploadId").asText() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.widthPx").value(1000));

        // Off the wall: refused, nothing sent. On it: submitted with a quote request.
        layOutAndRender(logo, surface.uTo() + 500);
        submit("{\"cartons\":20}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.issues[0].rule").exists());
        layOutAndRender(logo, (surface.uFrom() + surface.uTo()) / 2);
        submit("{\"cartons\":20}").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        // Returned, submitted again, approved.
        as(manager, REVIEWER);
        decide(false, "Logo nhỏ quá").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        as(owner, Set.of());
        submit(null).andExpect(status().isOk());
        as(manager, REVIEWER);
        decide(true, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));

        as(owner, Set.of());
        JsonNode snapshot = data(mvc.perform(get("/api/v1/designs/" + design + "/snapshot")).andExpect(status().isOk()));
        String customerSku = snapshot.get("customerSku").asText();
        assertThat(customerSku).startsWith(code + "-" + taxCode + "-");
        // The quote request followed the design; asked again now, it says the new numbers.
        mvc.perform(post("/api/v1/designs/" + design + "/quote-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cartons\":30}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerSku").value(customerSku));
        // A revision starts from the approved layout and images.
        String revision = data(mvc.perform(post("/api/v1/designs/" + design + "/revisions")).andExpect(status().isOk()))
                .get("id").asText();
        mvc.perform(get("/api/v1/designs/" + revision + "/assets")).andExpect(jsonPath("$.data.length()").value(2));

        // The producer's side: the print file by its checksum, from the store, to a browser on another origin.
        UUID snapshotId = UUID.fromString(snapshot.get("id").asText());
        String checksum = snapshot.get("checksum").asText();
        assertThat(designs.verifyForPrinting(snapshotId, checksum).matching()).isTrue();
        var print = designs.fulfillmentDownload(snapshotId, checksum, DesignArtifactRole.PRINT_READY);
        HttpResponse<byte[]> download = browser.send(HttpRequest.newBuilder(URI.create(print.url()))
                .header("Origin", STOREFRONT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(Checksums.sha256Hex(download.body())).isEqualTo(print.checksum());
        assertThat(download.headers().firstValue("Access-Control-Allow-Origin")).isPresent();
        try (PDDocument stamped = Loader.loadPDF(download.body())) {
            assertThat(stamped.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion")).isEqualTo("PDF/X-4");
            assertThat(stamped.getPage(0).getTrimBox().getWidth()).isLessThan(stamped.getPage(0).getMediaBox()
                    .getWidth());
        }
    }
}
