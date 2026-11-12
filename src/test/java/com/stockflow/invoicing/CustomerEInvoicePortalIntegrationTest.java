package com.stockflow.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.service.EInvoiceIssuance;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private StaffFixtures people;
    private UUID staff;
    private UUID accountant;
    private final List<UUID> placed = new ArrayList<>();

    /** A customer, its portal account and its SKU. */
    private record Portal(UUID customer, UUID account, CustomerSkuRows.Issued sku) {
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
                ConfirmedOrders.Line.cartons(portal.sku(), cartons, 2_000).withTax(BigDecimal.TEN));
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
        EInvoice.Snapshot issued = issuance.issue(draft.id(), draft.version(), accountant);
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
        JsonNode item = null;
        for (JsonNode candidate : page.at("/data/items")) {
            if (candidate.path("id").asText().equals(first.id().toString())) {
                item = candidate;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("status").asText()).isEqualTo("ISSUED");
        assertThat(item.path("type").asText()).isEqualTo("ORIGINAL");
        assertThat(item.path("orderId").asText()).isEqualTo(first.orderId().toString());
        assertThat(item.path("orderNumber").asText()).isEqualTo(first.orderNumber());
        assertThat(item.path("symbol").asText()).isEqualTo(first.symbol());
        assertThat(item.path("templateCode").asText()).isEqualTo(first.templateCode());
        assertThat(item.path("invoiceNumber").asInt()).isEqualTo(first.invoiceNumber());
        assertThat(item.path("subtotal").decimalValue()).isEqualByComparingTo("200000");
        assertThat(item.path("taxTotal").decimalValue()).isEqualByComparingTo("20000");
        assertThat(item.path("grandTotal").decimalValue()).isEqualByComparingTo("220000");
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

        // The other customer sees its own one, and only that.
        JsonNode theirPage = body(http.asCustomer(other.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(theirs.id().toString())));
        assertThat(theirPage.toString()).doesNotContain(first.id().toString(), second.id().toString());
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
        }
        // And it is not on the list either.
        http.asCustomer(mine.account(), get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        // Its owner reads it.
        http.asCustomer(other.account(), get(MINE + "/" + theirs.id() + "/files/PDF")).andExpect(status().isOk());

        // Issued to me now: mine to download.
        EInvoice.Snapshot nowIssued = issuance.issue(myDraft.id(), myDraft.version(), accountant);
        assertThat(nowIssued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        http.asCustomer(mine.account(), get(MINE + "/" + myDraft.id() + "/files/XML")).andExpect(status().isOk());
        http.asCustomer(other.account(), get(MINE + "/" + myDraft.id() + "/files/XML"))
                .andExpect(status().isNotFound());
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
