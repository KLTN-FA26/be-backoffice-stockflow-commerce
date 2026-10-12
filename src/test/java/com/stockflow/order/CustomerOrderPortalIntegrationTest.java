package com.stockflow.order;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-245/264: the customer order portal, through the real filter chain, permission aspect and data
 * scope (a CUSTOMER role, so {@code sales-orders:READ} with scope OWN) against Postgres. Two customers
 * each have a sign-in account and a customer profile of their own; orders belong to the profile, not
 * to the account, which is what the old {@code /orders/me} and the OWN scope had to be reconciled with.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as InventoryCatalogSecurityIntegrationTest, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class CustomerOrderPortalIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Sku TABLE = new Sku("TABLE-OAK-160");
    private static final String PRIVATE_NOTE = "private-note-for-staff-eyes-only";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired CustomerOrders customerOrders;
    @Autowired ProductionRecords productionRecords;
    @Autowired CreditChecks creditChecks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID userA;
    private UUID customerA;
    private UUID userB;
    private UUID customerB;
    private UUID staffUser;

    /** A's orders in the order they were placed: two lines, one line, one line (this one cancelled). */
    private OrderSummary twoLines;
    private OrderSummary oneLine;
    private OrderSummary cancelled;
    private OrderSummary ofB;

    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userA = tx.execute(s -> ReferenceRows.user(entityManager));
        userB = tx.execute(s -> ReferenceRows.user(entityManager));
        staffUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerA = customerOf(userA);
        customerB = customerOf(userB);

        twoLines = place(customerA, new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(12_000_000), null),
                new PlaceOrderCommand.Line(TABLE, 2, Money.vnd(9_000_000), null));
        oneLine = place(customerA, new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(12_000_000), null));
        cancelled = place(customerA, new PlaceOrderCommand.Line(TABLE, 1, Money.vnd(9_000_000), null));
        orders.cancelOwn(cancelled.orderId(), customerA, CancellationReasonCode.CUSTOMER_REQUEST, PRIVATE_NOTE, null);
        ofB = place(customerB, new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(12_000_000), null));
    }

    @AfterEach
    void releaseStock() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            if (orders.findById(id).orElseThrow().status() == OrderStatus.PENDING_PAYMENT) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
    }

    private UUID customerOf(UUID user) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Portal customer', ?, 'ACTIVE', 0, NOW())""", id, user, "portal-" + id + "@example.com"));
        return id;
    }

    private OrderSummary place(UUID customer, PlaceOrderCommand.Line... lines) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, List.of(lines)));
        placed.add(order.orderId());
        return order;
    }

    /** Signs in as this account with these roles; the permissions and the scope come from the role matrix. */
    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private String customerA() {
        return token(userA, "CUSTOMER");
    }

    /** An order of A's already in production, inserted as a row: how it got there is not what is tested here. */
    private UUID[] orderInProduction() {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID order = Identifiers.newId();
        UUID printLine = Identifiers.newId();
        UUID stockLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customerA, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at, paid_amount,
                                                         paid_in_full_at, warehouse_id, released_at, released_by)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', 30000, 'VND', NOW(), 0, NOW(), 30000, NOW(), ?, NOW(), ?)""",
                    order, orderNumber(), customerA, UUID.randomUUID(), DemoData.WAREHOUSE_HCM, staffUser);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, 'SOFA-3S-GREY', 10, 2000, 'VND', ?, ?)""",
                    printLine, order, snapshot, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, 'TABLE-OAK-160', 1, 10000, 'VND')""", stockLine, order);
        });
        return new UUID[] {order, printLine, stockLine};
    }

    /** One production order's good units for a line, as the production module reports them. */
    private void produced(UUID order, UUID line, int good) {
        UUID productionOrder = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id)
                    SELECT ?, ?, 'ORDER', 'IN_HOUSE', 'PENDING_PREPRESS', ?, 'SOFA-3S-GREY', l.design_snapshot_id,
                           l.design_checksum, ?, l.order_id, l.id
                      FROM ordering.order_line l WHERE l.id = ?""",
                    productionOrder, "LSX-IT-" + productionOrder.toString().substring(24), DemoData.WAREHOUSE_HCM, good, line);
            productionRecords.recordCompleted(productionOrder, line, good, Instant.now());
        });
    }

    private UUID heldOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'ON_HOLD', 1000, 'VND', NOW(), 0, NOW())""",
                    order, orderNumber(), customerA, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, 'TABLE-OAK-160', 1, 1000, 'VND')""", Identifiers.newId(), order);
        });
        return order;
    }

    /** SO-yyyyMMdd-nnnnnn on a day no real order uses, so the sequence never collides with these. */
    private static String orderNumber() {
        return "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private static CustomerOrders.Query query(String search, List<OrderStatus> statuses, String sort) {
        return new CustomerOrders.Query(0, 100, statuses, search, null, null, sort);
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a customer's OWN scope does not hide their own orders from the service")
    void ownScopeKeepsOwnOrders() {
        CurrentUser customer = TestUsers.builder().id(userA).role(com.stockflow.common.security.Role.CUSTOMER)
                .scope(DataScope.OWN).build();

        PageResponse<CustomerOrders.Card> mine = WithCurrentUser.call(customer, DataScope.OWN,
                () -> customerOrders.myOrders(customerA, query(null, List.of(), null)));
        CustomerOrders.Detail detail = WithCurrentUser.call(customer, DataScope.OWN,
                () -> customerOrders.myOrder(customerA, twoLines.orderId()));

        assertThat(mine.items()).extracting(CustomerOrders.Card::orderId)
                .containsExactlyInAnyOrder(twoLines.orderId(), oneLine.orderId(), cancelled.orderId());
        assertThat(detail.orderId()).isEqualTo(twoLines.orderId());
    }

    @Test
    @DisplayName("the list holds only the signed-in customer's orders, as cards, newest first")
    void listIsOwnOrdersOnly() throws Exception {
        String authorization = customerA();

        var body = mvc.perform(get("/api/v1/orders/me").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                // Newest first: the cancelled order was placed last.
                .andExpect(jsonPath("$.data.items[0].orderId").value(cancelled.orderId().toString()))
                .andExpect(jsonPath("$.data.items[2].orderId").value(twoLines.orderId().toString()))
                .andExpect(jsonPath("$.data.items[2].orderNumber").value(twoLines.orderNumber()))
                .andExpect(jsonPath("$.data.items[2].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.items[2].statusGroup").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.items[2].total").value(30_000_000))
                .andExpect(jsonPath("$.data.items[2].currency").value("VND"))
                .andExpect(jsonPath("$.data.items[2].lineCount").value(2))
                .andExpect(jsonPath("$.data.items[2].deliveryState").value("NOT_SHIPPED"))
                .andExpect(jsonPath("$.data.items[1].lineCount").value(1))
                .andExpect(jsonPath("$.data.items[0].statusGroup").value("CANCELLED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(ofB.orderId().toString()).doesNotContain(ofB.orderNumber())
                .doesNotContain("reservationIds").doesNotContain("createdBy").doesNotContain("customerId")
                .doesNotContain(PRIVATE_NOTE);
        // B sees B's order and none of A's.
        mvc.perform(get("/api/v1/orders/me").header("Authorization", token(userB, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ofB.orderId().toString()));
    }

    @Test
    @DisplayName("status filter (repeatable), search by part of the number, placed dates and sort")
    void filtersAndSort() throws Exception {
        String authorization = customerA();

        mvc.perform(get("/api/v1/orders/me").param("status", "CANCELLED").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(cancelled.orderId().toString()));
        mvc.perform(get("/api/v1/orders/me").param("status", "PENDING_PAYMENT", "CANCELLED")
                        .header("Authorization", authorization))
                .andExpect(jsonPath("$.data.items.length()").value(3));
        mvc.perform(get("/api/v1/orders/me").param("status", "SHIPPED").header("Authorization", authorization))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        String number = oneLine.orderNumber();
        mvc.perform(get("/api/v1/orders/me").param("search", number.substring(number.length() - 6).toLowerCase())
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(oneLine.orderId().toString()));
        // Another customer's order number finds nothing, whatever is typed.
        mvc.perform(get("/api/v1/orders/me").param("search", ofB.orderNumber()).header("Authorization", authorization))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mvc.perform(get("/api/v1/orders/me").param("placedFrom", TODAY.toString()).param("placedTo", TODAY.toString())
                        .header("Authorization", authorization))
                .andExpect(jsonPath("$.data.items.length()").value(3));
        mvc.perform(get("/api/v1/orders/me").param("placedFrom", TODAY.plusDays(1).toString())
                        .header("Authorization", authorization))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        var ascending = customerOrders.myOrders(customerA, query(null, List.of(), "orderNumber,asc")).items();
        assertThat(ascending).extracting(CustomerOrders.Card::orderNumber).isSorted();
        mvc.perform(get("/api/v1/orders/me").param("sort", "orderNumber,asc").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderId").value(twoLines.orderId().toString()));
        mvc.perform(get("/api/v1/orders/me").param("sort", "totalAmount,desc").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderId").value(twoLines.orderId().toString()));
    }

    @Test
    @DisplayName("an unknown sort field and an inverted date range are 400")
    void badQueries() throws Exception {
        String authorization = customerA();

        // SortWhitelist's own answer, the same as on the back-office list: UNSUPPORTED_PARAMETER, not VALIDATION_FAILED.
        mvc.perform(get("/api/v1/orders/me").param("sort", "contactPhone,asc").header("Authorization", authorization))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        mvc.perform(get("/api/v1/orders/me").param("placedFrom", TODAY.toString())
                        .param("placedTo", TODAY.minusDays(1).toString()).header("Authorization", authorization))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertThatThrownBy(() -> customerOrders.myOrders(customerA,
                new CustomerOrders.Query(0, 20, List.of(), null, TODAY, TODAY.minusDays(1), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> customerOrders.myOrders(customerA, query(null, List.of(), "contactPhone,asc")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER));
    }

    @Test
    @DisplayName("the detail: lines, timeline oldest first, payment, delivery and production of a stock line")
    void detail() throws Exception {
        var body = mvc.perform(get("/api/v1/orders/me/" + twoLines.orderId()).header("Authorization", customerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(twoLines.orderId().toString()))
                .andExpect(jsonPath("$.data.orderNumber").value(twoLines.orderNumber()))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.statusGroup").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.holdReasonPublic").doesNotExist())
                .andExpect(jsonPath("$.data.paymentTerm").value("PREPAID"))
                .andExpect(jsonPath("$.data.total").value(30_000_000))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.deliveryState").value("NOT_SHIPPED"))
                .andExpect(jsonPath("$.data.payment.status").value("UNPAID"))
                .andExpect(jsonPath("$.data.payment.paidAmount").value(0))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[?(@.sku == 'TABLE-OAK-160')].quantity").value(2))
                .andExpect(jsonPath("$.data.lines[?(@.sku == 'TABLE-OAK-160')].lineTotal").value(18_000_000))
                .andExpect(jsonPath("$.data.lines[0].production.state").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data.lines[0].production.goodQuantity").value(0))
                .andExpect(jsonPath("$.data.lines[1].production.state").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data.timeline.length()").value(1))
                .andExpect(jsonPath("$.data.timeline[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.timeline[0].statusGroup").value("AWAITING_PAYMENT"))
                .andReturn().getResponse().getContentAsString();

        // Nothing internal to the shop.
        assertThat(body).doesNotContain("reservationIds").doesNotContain("designChecksum").doesNotContain("createdBy")
                .doesNotContain("lastModifiedBy").doesNotContain("warehouseId").doesNotContain("contactEmail")
                .doesNotContain("contactPhone").doesNotContain("customerId");
    }

    @Test
    @DisplayName("a cancelled order's timeline ends with the reason code and none of the free text")
    void cancelledTimeline() throws Exception {
        var body = mvc.perform(get("/api/v1/orders/me/" + cancelled.orderId()).header("Authorization", customerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.statusGroup").value("CANCELLED"))
                .andExpect(jsonPath("$.data.deliveryState").value("NOT_SHIPPED"))
                .andExpect(jsonPath("$.data.timeline.length()").value(2))
                .andExpect(jsonPath("$.data.timeline[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.timeline[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$.data.timeline[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.timeline[1].statusGroup").value("CANCELLED"))
                .andExpect(jsonPath("$.data.timeline[1].reasonCode").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.data.timeline[1].reason").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PRIVATE_NOTE).doesNotContain("cancellation");
        // The history behind it does hold the text; that is why the timeline does not copy from it.
        assertThat(orders.history(cancelled.orderId()).get(1).reason()).contains(PRIVATE_NOTE);
        var timeline = customerOrders.myOrder(customerA, cancelled.orderId()).timeline();
        assertThat(timeline).extracting(CustomerOrders.TimelineEntry::occurredAt).isSorted();
    }

    @Test
    @DisplayName("an order in production shows each print line's state and good units; a stock line needs none")
    void productionState() throws Exception {
        UUID[] ids = orderInProduction();
        String authorization = customerA();

        mvc.perform(get("/api/v1/orders/me/" + ids[0]).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusGroup").value("IN_PRODUCTION"))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[1] + "')].production.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[1] + "')].production.goodQuantity").value(0))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[2] + "')].production.state").value("NOT_REQUIRED"))
                // No history was recorded for a row inserted by hand: one entry, the status it is in.
                .andExpect(jsonPath("$.data.timeline.length()").value(1))
                .andExpect(jsonPath("$.data.timeline[0].status").value("IN_PRODUCTION"));

        produced(ids[0], ids[1], 4);
        mvc.perform(get("/api/v1/orders/me/" + ids[0]).header("Authorization", authorization))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[1] + "')].production.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[1] + "')].production.goodQuantity").value(4));

        // The rest of the line is finished (a line has one production order here; its good units grow to cover it).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.order_line_production SET good_quantity = 10 WHERE order_line_id = ?", ids[1]));
        mvc.perform(get("/api/v1/orders/me/" + ids[0]).header("Authorization", authorization))
                .andExpect(jsonPath("$.data.statusGroup").value("IN_PRODUCTION"))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[1] + "')].production.state").value("DONE"))
                .andExpect(jsonPath("$.data.lines[?(@.lineId == '" + ids[1] + "')].production.goodQuantity").value(10));
    }

    @Test
    @DisplayName("an order on hold says whether it awaits a credit decision, never why internally")
    void holdReason() throws Exception {
        UUID underReview = heldOrder();
        UUID awaitingCredit = heldOrder();
        tx.executeWithoutResult(s -> creditChecks.record(new CreditChecks.Check(awaitingCredit, Instant.now(),
                new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("1000"), CreditChecks.Outcome.OVER_LIMIT,
                null, null, "internal credit note")));
        String authorization = customerA();

        var credit = mvc.perform(get("/api/v1/orders/me/" + awaitingCredit).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.statusGroup").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data.holdReasonPublic").value("AWAITING_CREDIT_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        assertThat(credit).doesNotContain("internal credit note");
        mvc.perform(get("/api/v1/orders/me/" + underReview).header("Authorization", authorization))
                .andExpect(jsonPath("$.data.statusGroup").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data.holdReasonPublic").value("UNDER_REVIEW"));
    }

    @Test
    @DisplayName("someone else's order and an unknown id are both 404, and say the same thing")
    void otherCustomersOrderIsNotFound() throws Exception {
        String asB = token(userB, "CUSTOMER");

        var other = mvc.perform(get("/api/v1/orders/me/" + twoLines.orderId()).header("Authorization", asB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        UUID unknown = UUID.randomUUID();
        var missing = mvc.perform(get("/api/v1/orders/me/" + unknown).header("Authorization", asB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertThat(other).doesNotContain(twoLines.orderNumber());
        // Same code and the same wording: nothing tells "not yours" from "not there".
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(other, "$.message").replace(twoLines.orderId().toString(), "ID"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.<String>read(missing, "$.message").replace(unknown.toString(), "ID"));
        assertThatThrownBy(() -> customerOrders.myOrder(customerB, twoLines.orderId()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("an account with no customer profile (staff) gets 404 CUSTOMER_NOT_FOUND; no token is 401")
    void noProfileAndNoToken() throws Exception {
        String staff = token(staffUser, "SALES_STAFF");

        mvc.perform(get("/api/v1/orders/me").header("Authorization", staff))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        mvc.perform(get("/api/v1/orders/me/" + twoLines.orderId()).header("Authorization", staff))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        mvc.perform(get("/api/v1/orders/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/me/" + twoLines.orderId())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the other order routes still resolve: /{id}/history for the owner, /me/history is a client error")
    void routesDoNotCollide() throws Exception {
        String authorization = customerA();

        mvc.perform(get("/api/v1/orders/" + cancelled.orderId() + "/history").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/orders/" + twoLines.orderId()).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(twoLines.orderId().toString()));
        mvc.perform(get("/api/v1/orders/me/history").header("Authorization", authorization))
                .andExpect(status().is4xxClientError());
    }
}
