package com.stockflow.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WF-05 E5 (plan §1.4 "Khách xem hoặc tải hoá đơn của mình trên portal") over HTTP with the real security chain, the
 * real role matrix and the real database: a signed-in customer lists the e-invoices issued to it and gets a link to
 * each one's PDF and XML; another customer's invoice, its own draft and no invoice at all answer the same 404
 * {@code EINVOICE_NOT_FOUND}; the back office's endpoints refuse a portal account although it holds
 * {@code einvoices:READ} (V20261120000600).
 *
 * <p>Two customers, each with a portal account and an invoice issued to it through the sandbox provider for a real
 * delivered order; the first also has a delivery whose invoice is still a draft. Only token decoding is stubbed.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false", "stockflow.notification.email.customer.enabled=false"})
class CustomerEInvoicePortalIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final String MINE = "/api/v1/einvoices/me";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    /** The test profile's local store signs no browser URL; the spy answers an https one, as MinIO would. */
    @MockitoSpyBean FileStorage presigner;
    @Autowired OrderService orders;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired com.stockflow.invoicing.internal.service.EInvoiceCorrections corrections;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private ConfirmedOrders confirmedOrders;
    private StaffFixtures people;
    private UUID staff;
    private UUID accountant;
    private final List<UUID> placed = new ArrayList<>();

    /** A customer, its portal account and its SKU. */
    private record Portal(UUID customer, UUID account, CustomerSkuRows.Issued sku) {
    }

    @BeforeEach
    void presignedUrls() {
        doAnswer(call -> "https://minio.test/" + call.getArgument(0) + "?X-Amz-Signature=test").when(presigner)
                .presignedDownloadUrl(anyString(), any(Duration.class), nullable(DownloadDisposition.class));
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        confirmedOrders = new ConfirmedOrders(context);
        people = new StaffFixtures(jdbc, tx, entityManager);
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = people.staff("ACCOUNTANT");
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> jdbc.queryForList(
                "SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'", UUID.class, id)
                .forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"))));
    }

    private Portal portalCustomer() {
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        confirmedOrders.legalProfile(customer);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET user_id = ?, tax_code = ? WHERE id = ?", account, "03"
                    + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)), customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    "ke.toan@" + customer + ".example.com", customer);
        });
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 300);
        return new Portal(customer, account, sku);
    }

    /** {@code cartons} cartons delivered to the customer; the draft the listener drew up. */
    private EInvoice.Snapshot draft(Portal portal, int cartons) {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(portal.customer(),
                ConfirmedOrders.Line.cartons(portal.sku(), cartons, 2_000).withTax(com.stockflow.support.VatOfTheDay.standard(jdbc)));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(confirmed.orderId(), staff);
        OrderSummary order = orders.markPacked(confirmed.orderId(), staff);
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), cartons * 50)), Instant.now(),
                staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class, shipment) == 1);
        return queries.get(jdbc.queryForObject("SELECT id FROM invoicing.einvoice WHERE shipment_id = ?", UUID.class,
                shipment));
    }

    private EInvoice.Snapshot issued(Portal portal, int cartons) {
        EInvoice.Snapshot draft = draft(portal, cartons);
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        return issued;
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a customer lists the invoices issued to it - not its drafts, not another customer's - and downloads "
            + "each one's PDF and XML")
    void aCustomerListsAndDownloadsItsOwnInvoices() throws Exception {
        Portal mine = portalCustomer();
        Portal other = portalCustomer();
        EInvoice.Snapshot first = issued(mine, 2);
        EInvoice.Snapshot second = issued(mine, 1);
        EInvoice.Snapshot myDraft = draft(mine, 1);
        EInvoice.Snapshot theirs = issued(other, 3);

        JsonNode page = body(http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2)));
        List<String> ids = new ArrayList<>();
        page.at("/data/items").forEach(item -> ids.add(item.path("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
        JsonNode item = StreamSupport.stream(page.at("/data/items").spliterator(), false)
                .filter(candidate -> candidate.path("id").asText().equals(first.id().toString()))
                .findFirst().orElseThrow();
        assertThat(item.path("status").asText()).isEqualTo("ISSUED");
        assertThat(item.path("type").asText()).isEqualTo("ORIGINAL");
        assertThat(item.path("orderId").asText()).isEqualTo(first.orderId().toString());
        assertThat(item.path("orderNumber").asText()).isEqualTo(first.orderNumber());
        assertThat(item.path("symbol").asText()).isEqualTo(first.symbol());
        assertThat(item.path("templateCode").asText()).isEqualTo(first.templateCode());
        assertThat(item.path("invoiceNumber").asInt()).isEqualTo(first.invoiceNumber());
        assertThat(item.path("subtotal").decimalValue()).isEqualByComparingTo("200000");
        // At the STANDARD rate of the day (HĐ-7: 8% through 2026, 10% after).
        BigDecimal tax = com.stockflow.support.VatOfTheDay.tax(200_000, com.stockflow.support.VatOfTheDay.standard(jdbc));
        assertThat(item.path("taxTotal").decimalValue()).isEqualByComparingTo(tax);
        assertThat(item.path("grandTotal").decimalValue()).isEqualByComparingTo(tax.add(BigDecimal.valueOf(200_000)));
        assertThat(item.path("issueDate").asText()).isEqualTo(first.issueDate().toString());
        // Nothing of the seller's work on it, and nothing of anybody else's.
        String raw = page.toString();
        assertThat(raw).doesNotContain(myDraft.id().toString(), theirs.id().toString(), theirs.orderNumber(),
                "issueDueAt", "warnings", "rejection", "transactionId", "lookupCode", "FileKey");

        // Paged like every list.
        http.asCustomer(mine.account(), get(MINE).param("page", "0").param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        // The files of its own invoices: a link per kind.
        for (EInvoice.Snapshot invoice : List.of(first, second)) {
            for (String kind : List.of("PDF", "XML")) {
                http.asCustomer(mine.account(), get(MINE + "/" + invoice.id() + "/files/" + kind))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.url").isNotEmpty());
            }
        }

        // BE-60: one invoice by its id, the same shape as its row on the list.
        JsonNode one = body(http.asCustomer(mine.account(), get(MINE + "/" + first.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(first.id().toString()))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.invoiceNumber").value(first.invoiceNumber())));
        assertThat(one.path("data")).as("the list item, field for field").isEqualTo(item);
        assertThat(one.toString()).doesNotContain("issueDueAt", "warnings", "rejection", "transactionId",
                "lookupCode", "FileKey");

        // The other customer sees its own one, and only that.
        JsonNode theirPage = body(http.asCustomer(other.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(theirs.id().toString())));
        assertThat(theirPage.toString()).doesNotContain(first.id().toString(), second.id().toString());
    }

    /** The one item of {@code page} with {@code id}. */
    private static JsonNode item(JsonNode page, UUID id) {
        return StreamSupport.stream(page.at("/data/items").spliterator(), false)
                .filter(candidate -> candidate.path("id").asText().equals(id.toString()))
                .findFirst().orElseThrow(() -> new AssertionError(id + " is not on the page"));
    }

    /** A replacement of {@code original} renaming its goods, agreed in writing, still a draft. */
    private EInvoice.Snapshot agreedReplacement(EInvoice.Snapshot original) throws Exception {
        EInvoice.Snapshot draft = corrections.replace(new com.stockflow.invoicing.internal.service.EInvoiceCorrections
                .Replace(original.id(), "sai ten hang", null, List.of(
                new com.stockflow.invoicing.internal.service.EInvoiceCorrections.LineChange(
                        original.lines().getFirst().id(), null, null, null, "Ly giay in logo (ten dung)"))));
        java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(pdf);
        }
        byte[] bytes = pdf.toByteArray();
        return corrections.attachAgreement(draft.id(), draft.version(), "BB-01/2026",
                java.time.LocalDate.now(ConfirmedOrders.VIETNAM), new com.stockflow.common.storage.FileUpload(
                        "bien-ban.pdf", "application/pdf", bytes.length, new java.io.ByteArrayInputStream(bytes)));
    }

    @Test
    @DisplayName("orderId narrows the list to one of the customer's orders; another customer's order, or none, is an "
            + "empty page and not an error")
    void theListByOrder() throws Exception {
        Portal mine = portalCustomer();
        Portal other = portalCustomer();
        EInvoice.Snapshot first = issued(mine, 2);
        EInvoice.Snapshot second = issued(mine, 1);
        EInvoice.Snapshot theirs = issued(other, 1);

        http.asCustomer(mine.account(), get(MINE).param("orderId", first.orderId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.id().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        http.asCustomer(mine.account(), get(MINE).param("orderId", second.orderId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second.id().toString()));
        // Another customer's order reads exactly as an order with no invoice: no row, no error, nothing of theirs.
        for (UUID foreign : List.of(theirs.orderId(), UUID.randomUUID())) {
            String answer = http.asCustomer(mine.account(), get(MINE).param("orderId", foreign.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andReturn().getResponse().getContentAsString();
            assertThat(answer).doesNotContain(theirs.id().toString(), theirs.orderNumber());
        }
        // Its owner reads it by the same id.
        http.asCustomer(other.account(), get(MINE).param("orderId", theirs.orderId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(theirs.id().toString()));
        // Without it, the whole list, as before.
        http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("a replaced invoice names its issued replacement and the replacement names it; a replacement still a "
            + "draft or at the provider is named by nothing")
    void theReplacementChain() throws Exception {
        Portal mine = portalCustomer();
        EInvoice.Snapshot original = issued(mine, 2);
        EInvoice.Snapshot unrelated = issued(mine, 1);

        EInvoice.Snapshot replacement = agreedReplacement(original);
        JsonNode drafted = body(http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2)));
        assertThat(item(drafted, original.id()).path("status").asText()).isEqualTo("ISSUED");
        assertThat(item(drafted, original.id()).has("replacedBy")).as("a draft replaces nothing yet").isFalse();
        assertThat(drafted.toString()).doesNotContain(replacement.id().toString(), replacement.code());

        EInvoice.Snapshot issuedReplacement = FirstSend.issue(issuance, queries, replacement.id(),
                replacement.version(), accountant);
        assertThat(issuedReplacement.status()).isEqualTo(EInvoiceStatus.ISSUED);

        JsonNode page = body(http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3)));
        JsonNode replaced = item(page, original.id());
        assertThat(replaced.path("status").asText()).isEqualTo("REPLACED");
        assertThat(replaced.at("/replacedBy/id").asText()).isEqualTo(replacement.id().toString());
        assertThat(replaced.at("/replacedBy/code").asText()).isEqualTo(replacement.code());
        assertThat(replaced.has("replaces")).isFalse();
        JsonNode standing = item(page, replacement.id());
        assertThat(standing.path("type").asText()).isEqualTo("REPLACEMENT");
        assertThat(standing.at("/replaces/id").asText()).isEqualTo(original.id().toString());
        assertThat(standing.at("/replaces/code").asText()).isEqualTo(original.code());
        assertThat(standing.has("replacedBy")).isFalse();
        assertThat(item(page, unrelated.id()).has("replaces")).isFalse();
        assertThat(item(page, unrelated.id()).has("replacedBy")).isFalse();
        // The links come with the order filter too.
        http.asCustomer(mine.account(), get(MINE).param("orderId", original.orderId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + original.id() + "')].replacedBy.id")
                        .value(replacement.id().toString()));
    }

    @Test
    @DisplayName("another customer's invoice, the customer's own draft and no invoice at all are the same 404 "
            + "EINVOICE_NOT_FOUND - the portal does not say which invoices exist")
    void anotherCustomersInvoiceIsNotFound() throws Exception {
        Portal mine = portalCustomer();
        Portal other = portalCustomer();
        EInvoice.Snapshot theirs = issued(other, 1);
        EInvoice.Snapshot myDraft = draft(mine, 1);

        String notMine = null;
        for (UUID id : List.of(theirs.id(), myDraft.id(), UUID.randomUUID())) {
            for (String kind : List.of("PDF", "XML")) {
                String answer = http.asCustomer(mine.account(), get(MINE + "/" + id + "/files/" + kind))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.errorCode").value("EINVOICE_NOT_FOUND"))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn().getResponse().getContentAsString();
                assertThat(answer).doesNotContain(theirs.orderNumber(), theirs.code(), myDraft.code(), "einvoices/");
                JsonNode parsed = json.readTree(answer);
                String shape = parsed.path("errorCode").asText() + "|" + parsed.path("message").asText();
                assertThat(notMine == null ? shape : notMine).as("one answer for all three").isEqualTo(shape);
                notMine = shape;
            }
            // BE-60: reading one invoice by its id answers the same 404.
            String read = http.asCustomer(mine.account(), get(MINE + "/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("EINVOICE_NOT_FOUND"))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(read).doesNotContain(theirs.orderNumber(), theirs.code(), myDraft.code());
            // WF-05 item b: the notices of an invoice are read under the same rule as its files.
            http.asCustomer(mine.account(), get(MINE + "/" + id + "/error-notices"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("EINVOICE_NOT_FOUND"));
        }
        // And it is not on the list either.
        http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        // Its owner reads it.
        http.asCustomer(other.account(), get(MINE + "/" + theirs.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(theirs.id().toString()));
        http.asCustomer(other.account(), get(MINE + "/" + theirs.id() + "/files/PDF")).andExpect(status().isOk());
        http.asCustomer(other.account(), get(MINE + "/" + theirs.id() + "/error-notices")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Issued to me now: mine to download.
        EInvoice.Snapshot nowIssued = FirstSend.issue(issuance, queries, myDraft.id(), myDraft.version(), accountant);
        assertThat(nowIssued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        http.asCustomer(mine.account(), get(MINE + "/" + myDraft.id() + "/files/XML")).andExpect(status().isOk());
        http.asCustomer(other.account(), get(MINE + "/" + myDraft.id() + "/files/XML"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("audit r3 EINV-5 (decision §3 HĐ-8: không gửi khách hoá đơn lỗi): an issued invoice whose seller's "
            + "signature does not hold is not on the customer's list, not readable by its id, its files not "
            + "downloadable - the accountant still reads it, with the warning")
    void anInvoiceWhoseSignatureFailsIsNotShownToTheCustomer() throws Exception {
        Portal mine = portalCustomer();
        EInvoice.Snapshot good = issued(mine, 1);
        EInvoice.Snapshot faulty = issued(mine, 2);
        // As EInvoiceIssuanceSteps records it when the issued XML's signature fails: the issued row is guarded, so the
        // warning is written here past the guard, in one transaction.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE invoicing.einvoice SET warnings = 'SIGNATURE_INVALID' WHERE id = ?", faulty.id());
        });

        JsonNode page = body(http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1)));
        assertThat(page.at("/data/items/0/id").asText()).isEqualTo(good.id().toString());
        http.asCustomer(mine.account(), get(MINE).param("orderId", faulty.orderId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        http.asCustomer(mine.account(), get(MINE + "/" + faulty.id())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("EINVOICE_NOT_FOUND"));
        for (String kind : List.of("PDF", "XML")) {
            http.asCustomer(mine.account(), get(MINE + "/" + faulty.id() + "/files/" + kind))
                    .andExpect(status().isNotFound());
        }
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/einvoices/" + faulty.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0]").value("SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("audit r3 EINV-3 (HĐ-1 step 3: 'đang tra lại tới ngày ...'): the invoice's latest transaction let go "
            + "is shown with until when it is watched and when it was found issued after all; none, nothing")
    void theWatchOfALetGoTransactionIsShown() throws Exception {
        Portal mine = portalCustomer();
        EInvoice.Snapshot invoice = issued(mine, 1);
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/einvoices/" + invoice.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.watchUntil").doesNotExist())
                .andExpect(jsonPath("$.data.watchFoundAt").doesNotExist());
        Instant older = Instant.parse("2026-11-01T03:00:00Z");
        Instant newer = Instant.parse("2026-12-01T03:00:00Z");
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO invoicing.einvoice_transaction_watch (einvoice_id, transaction_id, provider, let_go_as,
                                                                      watch_until, created_at)
                    VALUES (?, 't-old', 'SANDBOX', 'WITHDRAWN_NO_PROVIDER_RECORD', ?, now() - interval '2 days')""",
                    invoice.id(), java.sql.Timestamp.from(older));
            jdbc.update("""
                    INSERT INTO invoicing.einvoice_transaction_watch (einvoice_id, transaction_id, provider, let_go_as,
                                                                      watch_until, created_at)
                    VALUES (?, 't-new', 'SANDBOX', 'WITHDRAWN_RESULT_UNCONFIRMED', ?, now())""",
                    invoice.id(), java.sql.Timestamp.from(newer));
        });

        assertThat(queries.watch(invoice)).hasValueSatisfying(watch -> {
            assertThat(watch.transactionId()).isEqualTo("t-new");
            assertThat(watch.watchUntil()).isEqualTo(newer);
            assertThat(watch.foundAt()).isNull();
        });
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/einvoices/" + invoice.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.watchUntil").value(newer.toString()))
                .andExpect(jsonPath("$.data.watchFoundAt").doesNotExist());

        // Confirmation audit CF-9: the older watch then finds the provider holding t-old. The newer watch is still
        // asking, and the duplicate is shown all the same.
        Instant found = Instant.parse("2026-11-20T03:00:00Z");
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE invoicing.einvoice_transaction_watch SET found_at = ?, found_number = 41
                 WHERE einvoice_id = ? AND transaction_id = 't-old'""", java.sql.Timestamp.from(found), invoice.id()));
        assertThat(queries.watch(invoice)).hasValueSatisfying(watch -> {
            assertThat(watch.transactionId()).isEqualTo("t-new");
            assertThat(watch.watchUntil()).isEqualTo(newer);
            assertThat(watch.foundAt()).isEqualTo(found);
        });
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/einvoices/" + invoice.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.watchFoundAt").value(found.toString()));
    }

    @Test
    @DisplayName("the back office's e-invoice endpoints refuse a portal account, its own invoice included; the portal "
            + "endpoints want a signed-in customer")
    void theBackOfficeIsNotThePortal() throws Exception {
        Portal mine = portalCustomer();
        Portal other = portalCustomer();
        EInvoice.Snapshot own = issued(mine, 1);
        EInvoice.Snapshot theirs = issued(other, 1);

        for (String path : List.of("/api/v1/einvoices", "/api/v1/einvoices/" + theirs.id(),
                "/api/v1/einvoices/" + own.id(), "/api/v1/einvoices/" + theirs.id() + "/files/PDF",
                "/api/v1/einvoices/" + own.id() + "/files/PDF", "/api/v1/einvoices/" + theirs.id() + "/provider-calls",
                "/api/v1/einvoices/" + own.id() + "/signature",
                "/api/v1/einvoices/register?from=2026-01-01&to=2027-12-31")) {
            String answer = http.asCustomer(mine.account(), get(path)).andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(answer).as(path).doesNotContain(theirs.code(), theirs.orderNumber(), own.code());
        }
        http.asCustomer(mine.account(), get("/api/v1/einvoices/register/export?from=2026-01-01&to=2027-12-31"))
                .andExpect(status().isForbidden());
        http.asCustomer(mine.account(), get("/api/v1/einvoice-settings")).andExpect(status().isForbidden());

        // The accountant still reads both in the back office.
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/einvoices/" + theirs.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(theirs.id().toString()));
        // The built-in sandbox provider signs nothing; an XML signed by a real provider checks out, and only in the
        // back office.
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/einvoices/" + theirs.id() + "/signature"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NOT_SIGNED"))
                .andExpect(jsonPath("$.data.valid").value(false));
        byte[] sepaySigned;
        try (var in = getClass().getResourceAsStream("/einvoice/sepay-sandbox-357207.xml")) {
            sepaySigned = in.readAllBytes();
        }
        var upload = new org.springframework.mock.web.MockMultipartFile("file", "357207.xml", "application/xml",
                sepaySigned);
        http.asRole(Role.ACCOUNTANT, accountant, multipart("/api/v1/einvoices/signature-checks").file(upload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("VALID"))
                .andExpect(jsonPath("$.data.signer").value("MẮT BÃO - KIỂM THỬ HĐĐT CÓ MÃ"))
                .andExpect(jsonPath("$.data.chainChecked").value(false));
        http.asCustomer(mine.account(), multipart("/api/v1/einvoices/signature-checks").file(upload))
                .andExpect(status().isForbidden());

        // Not signed in; signed in without the permission; signed in with it but no customer behind the account.
        http.anonymous(get(MINE)).andExpect(status().isUnauthorized());
        http.anonymous(get(MINE + "/" + own.id() + "/files/PDF")).andExpect(status().isUnauthorized());
        UUID picker = people.staff("WAREHOUSE_STAFF");
        http.asRole(Role.WAREHOUSE_STAFF, picker, get(MINE)).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, picker, get(MINE + "/" + own.id() + "/files/PDF"))
                .andExpect(status().isForbidden());
        String staffAnswer = http.asRole(Role.ACCOUNTANT, accountant, get(MINE + "/" + own.id() + "/files/PDF"))
                .andExpect(status().is4xxClientError()).andReturn().getResponse().getContentAsString();
        assertThat(staffAnswer).doesNotContain("einvoices/");
    }
}
