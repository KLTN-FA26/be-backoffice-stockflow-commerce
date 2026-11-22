package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.payment.api.VietQr;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.VatOfTheDay;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WF-02 round 5 (BE-R5-2 ... R5-12) over HTTP with the real security chain: what the back office and the storefront
 * read beside a quote, an order, its contract and its installments, so a screen no longer shows a bare SKU or id. One
 * customer's SKU issued from a design named "Ly R5" ({@link CustomerSkuRows}), quoted, accepted, converted and signed
 * through the services the controllers call. Runs in the context {@code AccountantSeesCustomerNameApiIntegrationTest}
 * shares: signing a contract uploads its scan, which needs no inspection service here.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class Round5ReadModelsIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;

    private SecuredMvc http;
    private ConfirmedOrders confirmedOrders;
    private UUID salesUser;
    private UUID accountant;
    private UUID portalUser;
    private CurrentUser sales;
    private CurrentUser portal;
    private UUID customerId;
    private String customerName;
    private CustomerSkuRows.Issued issued;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        salesUser = people.staff("SALES_STAFF");
        accountant = people.staff("ACCOUNTANT");
        portalUser = people.staff("CUSTOMER");
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        customerId = Identifiers.newId();
        customerName = "Cong ty R5 " + customerId.toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, user_id, full_name, email, phone, tax_code, status, version,
                        created_at)
                    VALUES (?, ?, ?, ?, '0901234567', ?, 'ACTIVE', 0, NOW())""",
                    customerId, portalUser, customerName, "r5-" + customerId + "@example.com",
                    "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, 100000000, 30, ?, NOW())""", Identifiers.newId(), customerId, accountant);
        });
        customers.addAddress(customerId, salesUser, true, new SaveAddressCommand("SHIPPING", "Nguyen Van A",
                "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        confirmedOrders = new ConfirmedOrders(context);
        confirmedOrders.legalProfile(customerId);
        // A first production (a plate fee) of a STANDARD product, 10 cups a carton, drawn from a design with a name.
        issued = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10, "STANDARD", false);
        UUID design = CustomerSkuRows.designOf(jdbc, issued);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET name = 'Ly R5' WHERE id = ?", design));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private Quotes.Draft draft(BigDecimal plateFee) {
        return new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null, List.of(new Quotes.Line(issued.sku(), 3,
                List.of(new Quotes.Tier(2, new BigDecimal("3000")), new Quotes.Tier(4, new BigDecimal("2800"))))),
                null, plateFee == null ? List.of() : List.of(new Quotes.Fee(FeeKind.PLATE_FEE, issued.sku(), plateFee)));
    }

    /** Drafted, priced and sent; accepted at 4 cartons. */
    private Quotes.QuoteView accepted() {
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, draft(null)));
        Quotes.QuoteView priced = quotes.edit(sales, drafted.id(), drafted.version(), draft(new BigDecimal("500000")));
        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), priced.version());
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        return quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 4)));
    }

    @Test
    @DisplayName("R5-2/3/8: a quote row carries its total as the detail adds it up, its line count and first item; a line "
            + "its design and carton; a step who made it")
    void quoteRowsAndLines() throws Exception {
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, draft(null)));
        Quotes.QuoteView priced = quotes.edit(sales, drafted.id(), drafted.version(), draft(new BigDecimal("500000")));
        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), priced.version());

        JsonNode detail = data(http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/quotes/" + sent.id())));
        BigDecimal proposed = detail.path("proposedTotal").decimalValue();
        assertThat(proposed).as("3 cartons of 10 at 3,000 and a plate fee, with VAT").isPositive();
        JsonNode line = detail.path("lines").get(0);
        assertThat(line.path("designName").asText()).isEqualTo("Ly R5");
        assertThat(line.path("unitsPerCarton").asInt()).isEqualTo(10);
        assertThat(line.has("previewUrl")).as("no preview file on this design: none (nulls are left out)").isFalse();
        assertThat(detail.path("history")).allSatisfy(step ->
                assertThat(step.path("changedByName").asText()).isEqualTo("test-" + salesUser));

        // The staff list and the customer's own list read the same total as the detail, for the whole page at once.
        for (var reader : List.of(Map.entry(Role.SALES_STAFF, salesUser), Map.entry(Role.CUSTOMER, portalUser))) {
            JsonNode row = data(http.asRole(reader.getKey(), reader.getValue(), get("/api/v1/quotes")
                    .param("customerId", customerId.toString()))).path("items").get(0);
            assertThat(row.path("id").asText()).isEqualTo(sent.id().toString());
            assertThat(row.path("total").decimalValue()).isEqualByComparingTo(proposed);
            assertThat(row.path("lineCount").asInt()).isEqualTo(1);
            assertThat(row.path("firstItemName").asText()).isEqualTo("Ly R5");
        }

        // The customer's copy names no account behind a step.
        JsonNode seen = data(http.asCustomer(portalUser, get("/api/v1/quotes/" + sent.id())));
        assertThat(seen.path("history")).allSatisfy(step -> assertThat(step.has("changedByName")).isFalse());
    }

    @Test
    @DisplayName("R5-2/3/7/9/11/12: an order points back at its quote; its lines, its contract's schedule and plan carry "
            + "the design, the carton, the due day and the days overdue; transfer instructions carry the chat's VietQR")
    void orderContractAndInstallments() throws Exception {
        Quotes.QuoteView accepted = accepted();
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();

        JsonNode quoteRow = data(http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/quotes")
                .param("customerId", customerId.toString()))).path("items").get(0);
        assertThat(quoteRow.path("status").asText()).isEqualTo("CONVERTED");
        assertThat(quoteRow.path("total").decimalValue()).as("the accepted total once accepted")
                .isEqualByComparingTo(accepted.acceptedTotal());

        JsonNode order = data(http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/orders/" + orderId)));
        assertThat(order.path("sourceQuoteId").asText()).isEqualTo(accepted.id().toString());
        assertThat(order.path("sourceQuoteNumber").asText()).isEqualTo(accepted.quoteNumber());
        assertThat(order.path("lines").get(0).path("designName").asText()).isEqualTo("Ly R5");
        assertThat(order.path("lines").get(0).path("unitsPerCarton").asInt()).isEqualTo(10);
        assertThat(order.path("lines").get(0).path("cartons").asInt()).isEqualTo(4);

        ConfirmedOrders.Signed signed = confirmedOrders.sign(orderId, ConfirmedOrders.paidOnSigning());
        UUID installment = signed.seq(1);
        // Fell due five days ago and still unpaid: moved back with the triggers off, the one way to a day in the past
        // without a fake clock (as Wf02SalesFlowIntegrationTest does).
        LocalDate today = LocalDate.now(VIETNAM);
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE payment.installment SET due_date = ? WHERE id = ?", today.minusDays(5), installment);
        });

        JsonNode installments = data(http.asRole(Role.ACCOUNTANT, accountant,
                get("/api/v1/installments/orders/" + orderId))).path("installments");
        assertThat(installments.get(0).path("dueDate").asText()).isEqualTo(today.minusDays(5).toString());
        assertThat(installments.get(0).path("daysOverdue").asInt()).isEqualTo(5);

        JsonNode contract = data(http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/orders/" + orderId
                + "/contract")));
        assertThat(contract.path("paymentPlan").get(0).path("dueDate").asText())
                .isEqualTo(today.minusDays(5).toString());
        assertThat(contract.path("paymentPlan").get(0).path("daysOverdue").asInt()).isEqualTo(5);
        JsonNode batchLine = contract.path("deliverySchedule").get(0).path("lines").get(0);
        assertThat(batchLine.path("designName").asText()).isEqualTo("Ly R5");
        assertThat(batchLine.path("unitsPerCarton").asInt()).isEqualTo(10);

        // The customer's order page reads the same, and pays by the code the chat's transfer card draws.
        JsonNode mine = data(http.asCustomer(portalUser, get("/api/v1/orders/me/" + orderId)));
        assertThat(mine.path("sourceQuoteNumber").asText()).isEqualTo(accepted.quoteNumber());
        assertThat(mine.path("lines").get(0).path("designName").asText()).isEqualTo("Ly R5");
        JsonNode transfer = data(http.asCustomer(portalUser, get("/api/v1/installments/me/" + installment
                + "/transfer-instructions")));
        assertThat(transfer.path("vietQr").asText()).isEqualTo(VietQr.payload("970000",
                transfer.path("accountNumber").asText(), transfer.path("amountDue").decimalValue(),
                transfer.path("reference").asText()));

        // R5-11: the accountant finds the customer on the receivables report by name; on-signing overdue shows apart.
        JsonNode report = data(http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/receivables")
                .param("search", customerName)));
        assertThat(report.path("rows").path("items")).hasSize(1);
        assertThat(report.path("rows").path("items").get(0).path("customerId").asText())
                .isEqualTo(customerId.toString());
        assertThat(report.path("rows").path("items").get(0).path("signingOverdue").decimalValue()).isPositive();
        assertThat(data(http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/receivables")
                .param("search", "khong-co-ai-" + customerId))).path("rows").path("items")).isEmpty();
    }

    @Test
    @DisplayName("R5-6: a cancellation request on the queue carries its order, customer, total, what was paid, the "
            + "order's status and whether printing started")
    void cancellationQueue() throws Exception {
        var confirmed = confirmedOrders.confirmed(customerId, ConfirmedOrders.Line.cartons(issued, 10, 40_000));
        CancellationOutcome asked = orders.cancelOwn(confirmed.orderId(), customerId,
                CancellationReasonCode.CUSTOMER_REQUEST, "ordered twice", portalUser);
        assertThat(asked.result()).isEqualTo(CancellationOutcome.Result.REQUESTED);
        BigDecimal total = orders.findById(confirmed.orderId()).orElseThrow().total().amount();

        JsonNode row = data(http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/orders/cancellation-requests")
                .param("orderId", confirmed.orderId().toString()))).path("items").get(0);
        assertThat(row.path("requestId").asText()).isEqualTo(asked.requestId().toString());
        assertThat(row.path("orderNumber").asText()).isEqualTo(confirmed.orderNumber());
        assertThat(row.path("customerName").asText()).isEqualTo(customerName);
        assertThat(row.path("orderTotal").decimalValue()).isEqualByComparingTo(total);
        assertThat(row.path("paidTotal").decimalValue()).as("paid in full on signing").isEqualByComparingTo(total);
        assertThat(row.path("orderStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(row.path("printingStarted").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("R5-10: a customer reads their own legal profile; none recorded is data null; staff have no own one")
    void ownLegalProfile() throws Exception {
        http.asCustomer(portalUser, get("/api/v1/customers/me/legal-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.legalName").value(customerName))
                .andExpect(jsonPath("$.data.registeredAddress").isNotEmpty())
                .andExpect(jsonPath("$.data.representativeName").isNotEmpty());

        UUID otherAccount = new StaffFixtures(jdbc, tx, entityManager).staff("CUSTOMER");
        UUID other = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Cong ty chua ho so', ?, 'ACTIVE', 0, NOW())""", other, otherAccount,
                "r5-none-" + other + "@example.com"));
        http.asCustomer(otherAccount, get("/api/v1/customers/me/legal-profile"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/customers/me/legal-profile"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    @DisplayName("R5-4: the quote editor's customer SKUs carry the pieces per carton and today's VAT rate")
    void customerSkusForTheQuoteEditor() throws Exception {
        BigDecimal standard = VatOfTheDay.standard(jdbc);
        JsonNode skus = data(http.asRole(Role.SALES_STAFF, salesUser, get("/api/v1/customer-skus")
                .param("customerId", customerId.toString())));
        assertThat(skus).hasSize(1);
        assertThat(skus.get(0).path("sku").asText()).isEqualTo(issued.sku());
        assertThat(skus.get(0).path("unitsPerCarton").asInt()).isEqualTo(10);
        assertThat(skus.get(0).path("taxClass").asText()).isEqualTo("STANDARD");
        assertThat(skus.get(0).path("taxRate").decimalValue()).isEqualByComparingTo(standard);
    }
}
