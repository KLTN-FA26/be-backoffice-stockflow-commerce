package com.stockflow.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.DownloadDisposition;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.service.EInvoiceIssuance;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
import com.stockflow.invoicing.internal.service.FirstSend;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.ShipmentRows;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WF-05 E4 over HTTP (TT 91/2026/TT-BTC Điều 10; plans/E0-legal.md rows 1-3 and khoản 6) with the real security chain,
 * the real role matrix, the real database - its triggers included - and the sandbox provider: an ACCOUNTANT account
 * adjusts an issued invoice, is refused until the agreement with the buyer is attached, and issues the adjustment;
 * replaces another; and notifies a wrong buyer name without drawing any invoice up. The rules themselves are pinned
 * one by one without a database in {@code EInvoiceCorrectionsTest} and {@code EInvoiceCorrectionTest}; here the point
 * is that the endpoints, their permissions and the database agree with them.
 *
 * <p>Every invoice corrected is a real one: 2 cartons of 50 at 2,000 a piece taxed 10% - 220,000 - ordered, paid,
 * delivered and issued through the sandbox. Issuing answers SUBMITTING and the provider is called on another thread,
 * so the result is awaited ({@link FirstSend}), never slept for.</p>
 *
 * <p>The annotations, the properties and the two overridden beans are {@code CustomerEInvoicePortalIntegrationTest}'s
 * exactly, so the two classes share one application context.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false", "stockflow.notification.email.customer.enabled=false"})
class EInvoiceCorrectionEndpointsIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final String EINVOICES = "/api/v1/einvoices/";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    /** The test profile's local store signs no browser URL; the spy answers an https one, as MinIO would. */
    @MockitoSpyBean FileStorage presigner;
    @Autowired OrderService orders;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private ConfirmedOrders confirmedOrders;
    private UUID staff;
    private UUID accountant;
    private UUID customer;
    private CustomerSkuRows.Issued sku;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void anAccountantAndACustomer() {
        doAnswer(call -> "https://minio.test/" + call.getArgument(0) + "?X-Amz-Signature=test").when(presigner)
                .presignedDownloadUrl(anyString(), any(Duration.class), nullable(DownloadDisposition.class));
        http = new SecuredMvc(mvc, decoder);
        confirmedOrders = new ConfirmedOrders(context);
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = new StaffFixtures(jdbc, tx, entityManager).staff("ACCOUNTANT");
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        confirmedOrders.legalProfile(customer);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", "03"
                    + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)), customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    "ke.toan@" + customer + ".example.com", customer);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 500);
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> jdbc.queryForList(
                "SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'", UUID.class, id)
                .forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"))));
    }

    /** Two cartons delivered to the customer and their invoice issued through the sandbox. */
    private EInvoice.Snapshot issuedOriginal() {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 2, 2_000).withTax(BigDecimal.TEN));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(confirmed.orderId(), staff);
        OrderSummary order = orders.markPacked(confirmed.orderId(), staff);
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 100)), Instant.now(), staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class, shipment) == 1);
        EInvoice.Snapshot draft = queries.get(jdbc.queryForObject(
                "SELECT id FROM invoicing.einvoice WHERE shipment_id = ?", UUID.class, shipment));
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        return issued;
    }

    private ResultActions asAccountant(String path) throws Exception {
        return http.asRole(Role.ACCOUNTANT, accountant, get(path));
    }

    private ResultActions asAccountant(String path, Object body) throws Exception {
        return http.asRole(Role.ACCOUNTANT, accountant, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** {@code quantity} on the invoice's goods line: what changes on an adjustment, what it should say on a replacement. */
    private static Map<String, Object> goodsLine(EInvoice.Snapshot invoice, int quantity) {
        return Map.of("originalLineId", invoice.lines().getFirst().id(), "quantity", quantity);
    }

    /**
     * The signed agreement with the buyer attached to a draft correction, as the accountant uploads it.
     *
     * @return the correction's version afterwards
     */
    private long attachAgreement(String correctionId, long version) throws Exception {
        // A real, empty page: the upload is opened as a PDF before it is stored.
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf);
        }
        // The multipart builder is not the one SecuredMvc signs, so it is signed here.
        JsonNode agreed = data(mvc.perform(multipart(HttpMethod.PUT, EINVOICES + correctionId + "/agreement")
                        .file(new MockMultipartFile("file", "bien-ban.pdf", "application/pdf", pdf.toByteArray()))
                        .param("number", "BB-01/2026")
                        .param("date", BusinessCalendar.date(Instant.now()).toString())
                        .param("version", Long.toString(version))
                        .header("Authorization", http.token(Role.ACCOUNTANT, accountant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.agreement.number").value("BB-01/2026")));
        asAccountant(EINVOICES + correctionId + "/agreement").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty());
        return agreed.path("version").asLong();
    }

    /** {@code POST /issuance}, and the correction once the provider has answered. */
    private EInvoice.Snapshot issue(String correctionId, long version) throws Exception {
        asAccountant(EINVOICES + correctionId + "/issuance", Map.of("version", version)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTING"));
        return FirstSend.awaited(issuance, queries, UUID.fromString(correctionId));
    }

    @Test
    @DisplayName("an adjustment: drawn up as a draft naming the original; 409 EINVOICE_AGREEMENT_REQUIRED until the "
            + "agreement is attached; issued, the original is flagged adjusted, stays ISSUED and is never replaced")
    void anAdjustmentWaitsForTheAgreementAndFlagsTheOriginal() throws Exception {
        EInvoice.Snapshot original = issuedOriginal();

        JsonNode draft = data(asAccountant(EINVOICES + original.id() + "/adjustments", Map.of("basis", "ERROR",
                "reason", "sai so luong", "lines", List.of(goodsLine(original, -1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.originalEInvoiceId").value(original.id().toString()))
                .andExpect(jsonPath("$.data.correctionBasis").value("ERROR"))
                .andExpect(jsonPath("$.data.agreementRequired").value(true))
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(-1)));
        String adjustment = draft.path("id").asText();
        assertThat(draft.path("grandTotal").decimalValue()).as("one carton off, with its tax").isEqualByComparingTo("-110000");
        assertThat(draft.path("shipmentId").asText()).isEqualTo(original.shipmentId().toString());

        // Without the agreement it is not sent, and stays the draft it was.
        asAccountant(EINVOICES + adjustment + "/issuance", Map.of("version", draft.path("version").asLong()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_AGREEMENT_REQUIRED"));
        asAccountant(EINVOICES + adjustment).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        asAccountant(EINVOICES + original.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adjusted").value(false));

        EInvoice.Snapshot issued = issue(adjustment, attachAgreement(adjustment, draft.path("version").asLong()));

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.invoiceNumber()).as("numbered on in the original's series").isGreaterThan(original.invoiceNumber());
        asAccountant(EINVOICES + original.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.adjusted").value(true))
                .andExpect(jsonPath("$.data.invoiceNumber").value(original.invoiceNumber()));
        asAccountant(EINVOICES + original.id() + "/corrections").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(adjustment));
        // Khoản 6 điểm a: adjusted once, it is not replaced afterwards.
        asAccountant(EINVOICES + original.id() + "/replacements", Map.of("reason", "sai so luong", "lines",
                List.of(goodsLine(original, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_CORRECTION_FORM_FIXED"));
    }

    @Test
    @DisplayName("a replacement: drawn up as a draft while the original stays in force and takes no adjustment; "
            + "issued, the original is REPLACED and no longer corrected")
    void aReplacementReplacesTheOriginalWhenItIsIssued() throws Exception {
        EInvoice.Snapshot original = issuedOriginal();

        JsonNode draft = data(asAccountant(EINVOICES + original.id() + "/replacements", Map.of("reason",
                "sai so luong", "lines", List.of(goodsLine(original, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("REPLACEMENT"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.originalEInvoiceId").value(original.id().toString()))
                .andExpect(jsonPath("$.data.agreementRequired").value(true))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(1)));
        String replacement = draft.path("id").asText();
        assertThat(draft.path("grandTotal").decimalValue()).as("the invoice as it should have been").isEqualByComparingTo("110000");

        asAccountant(EINVOICES + original.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ISSUED"));
        asAccountant(EINVOICES + original.id() + "/adjustments", Map.of("basis", "ERROR", "reason", "sai don gia",
                "lines", List.of(goodsLine(original, -1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_CORRECTION_FORM_FIXED"));
        asAccountant(EINVOICES + replacement + "/issuance", Map.of("version", draft.path("version").asLong()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_AGREEMENT_REQUIRED"));

        EInvoice.Snapshot issued = issue(replacement, attachAgreement(replacement, draft.path("version").asLong()));

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        asAccountant(EINVOICES + original.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REPLACED"))
                .andExpect(jsonPath("$.data.adjusted").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ? AND status = "
                + "'ISSUED' AND type IN ('ORIGINAL', 'REPLACEMENT')", Long.class, original.shipmentId()))
                .as("one invoice in force per shipment: the replacement").isEqualTo(1);
        asAccountant(EINVOICES + original.id() + "/adjustments", Map.of("basis", "ERROR", "reason", "sai don gia",
                "lines", List.of(goodsLine(original, -1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_NOT_IN_FORCE"));
    }

    @Test
    @DisplayName("E0 row 1: a wrong buyer name is notified on 04/SS-HDDT and answered by the provider - no invoice is "
            + "drawn up and the issued one does not change")
    void aWrongBuyerNameIsNotifiedNotCorrected() throws Exception {
        EInvoice.Snapshot original = issuedOriginal();

        JsonNode notice = data(asAccountant(EINVOICES + original.id() + "/error-notices", Map.of("reason",
                "sai ten nguoi mua", "correctBuyerName", "Cong ty TNHH Tra Sua Sai Gon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.einvoiceId").value(original.id().toString()))
                .andExpect(jsonPath("$.data.formCode").value("04/SS-HDDT"))
                .andExpect(jsonPath("$.data.subject").value("BUYER_NAME_ADDRESS"))
                .andExpect(jsonPath("$.data.correctBuyerName").value("Cong ty TNHH Tra Sua Sai Gon"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.recordedBy").value(accountant.toString())));
        assertThat(notice.path("responseCode").asText()).startsWith("SBX-TBSS-");

        asAccountant(EINVOICES + original.id() + "/error-notices").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(notice.path("id").asText()));
        asAccountant(EINVOICES + original.id() + "/corrections").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        EInvoice.Snapshot after = queries.get(original.id());
        assertThat(after.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(after.adjusted()).isFalse();
        assertThat(after.buyer()).as("the invoice keeps the buyer it was issued to").isEqualTo(original.buyer());
        assertThat(after.version()).as("a notice does not touch the invoice").isEqualTo(original.version());
    }
}
