package com.stockflow.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.document.DocumentSamples;
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
import com.stockflow.support.VatOfTheDay;
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
 * The agreement to adjust an invoice, drafted over HTTP (TT 91/2026/TT-BTC Điều 10) with the real security chain, the
 * real role matrix and the sandbox provider: the accountant prints it from the draft adjustment, the buyer reads it
 * once the adjustment is issued to them - not before, and never another customer's.
 *
 * <p>The invoice corrected is a real one - ordered, paid, delivered and issued through the sandbox, as
 * {@code EInvoiceCorrectionEndpointsIntegrationTest} makes its own. The annotations, the properties and the two
 * overridden beans are that class's exactly, so the two share one application context.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false", "stockflow.notification.email.customer.enabled=false"})
class EInvoiceAgreementDraftHttpTest {

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
    private UUID buyer;
    private UUID stranger;
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
        buyer = tx.execute(s -> ReferenceRows.user(entityManager));
        stranger = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        confirmedOrders.legalProfile(customer);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ?, user_id = ? WHERE id = ?", "03"
                    + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)), buyer, customer);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", stranger, other);
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
                ConfirmedOrders.Line.cartons(sku, 2, 2_000).withTax(VatOfTheDay.standard(jdbc)));
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

    private ResultActions asAccountant(String path, Object body) throws Exception {
        return http.asRole(Role.ACCOUNTANT, accountant, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    @DisplayName("the agreement of an adjustment: drafted for the accountant from the draft correction - the original "
            + "invoice's number, the reason, the quantity before and after, the totals; an invoice that corrects "
            + "nothing 409; the buyer reads it only once the adjustment is issued; another customer 404, a customer on "
            + "the staff path and a role without the permission 403, anonymous 401")
    void agreementOfAnAdjustment() throws Exception {
        EInvoice.Snapshot original = issuedOriginal();
        JsonNode draft = data(asAccountant(EINVOICES + original.id() + "/adjustments", Map.of("basis", "ERROR",
                "reason", "Ghi sai số lượng so với biên bản giao nhận", "lines",
                List.of(Map.of("originalLineId", original.lines().getFirst().id(), "quantity", -1))))
                .andExpect(status().isOk()));
        String adjustment = draft.path("id").asText();
        String staffPath = EINVOICES + adjustment + "/agreement/draft";
        String ownPath = EINVOICES + "me/" + adjustment + "/agreement/draft";
        String unit = original.lines().getFirst().unit();

        String agreement = DocumentSamples.downloaded(http.asRole(Role.ACCOUNTANT, accountant, get(staffPath)),
                "wired-invoice-adjustment-agreement", "Biên bản thỏa thuận điều chỉnh hóa đơn BBTT-");
        assertThat(agreement).contains("BIÊN BẢN THỎA THUẬN ĐIỀU CHỈNH HÓA ĐƠN",
                "Căn cứ Nghị định số 254/2026/NĐ-CP", "Căn cứ Thông tư số 91/2026/TT-BTC",
                "ký hiệu " + original.symbol() + ", số " + "%08d".formatted(original.invoiceNumber()),
                "Ghi sai số lượng so với biên bản giao nhận", "Số lượng - " + original.lines().getFirst().itemName(),
                "2 " + unit, "1 " + unit, "Tổng cộng tiền thanh toán", "điều chỉnh giảm", "ĐẠI DIỆN BÊN A",
                "ĐẠI DIỆN BÊN B");

        http.asRole(Role.ACCOUNTANT, accountant, get(EINVOICES + original.id() + "/agreement/draft"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_CORRECTION_NOT_APPLICABLE"));
        // A draft is the back office's: the buyer learns of the correction when it is issued.
        http.asCustomer(buyer, get(ownPath)).andExpect(status().isNotFound());
        http.asCustomer(buyer, get(staffPath)).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, staff, get(staffPath)).andExpect(status().isForbidden());
        http.anonymous(get(staffPath)).andExpect(status().isUnauthorized());

        // Attached and issued: the buyer's copy carries the number the signed agreement was recorded under.
        ByteArrayOutputStream scan = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(scan);
        }
        JsonNode agreed = data(mvc.perform(multipart(HttpMethod.PUT, EINVOICES + adjustment + "/agreement")
                        .file(new MockMultipartFile("file", "bien-ban.pdf", "application/pdf", scan.toByteArray()))
                        .param("number", "BB-01/2026")
                        .param("date", BusinessCalendar.date(Instant.now()).toString())
                        .param("version", Long.toString(draft.path("version").asLong()))
                        .header("Authorization", http.token(Role.ACCOUNTANT, accountant)))
                .andExpect(status().isOk()));
        asAccountant(EINVOICES + adjustment + "/issuance", Map.of("version", agreed.path("version").asLong()))
                .andExpect(status().isOk());
        assertThat(FirstSend.awaited(issuance, queries, UUID.fromString(adjustment)).status())
                .isEqualTo(EInvoiceStatus.ISSUED);

        assertThat(DocumentSamples.downloaded(http.asCustomer(buyer, get(ownPath)), "wired-invoice-agreement-issued",
                "Biên bản thỏa thuận điều chỉnh hóa đơn BB-01-2026.pdf")).contains(
                "BIÊN BẢN THỎA THUẬN ĐIỀU CHỈNH HÓA ĐƠN",
                "Số: BB-01/2026");
        http.asCustomer(stranger, get(ownPath)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_NOT_FOUND"));
    }
}
