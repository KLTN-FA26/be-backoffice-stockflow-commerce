package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.service.ChatCardActions;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.design.internal.service.DesignQuoteRequestService;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DS-02a as the presenter walks it, in one go, on the rows db/demo's WF-02 seed ({@code V20261124000600}) leaves: the
 * demo customer's approved design "Ly giấy 12oz logo xanh", its SKU {@code LY-GIAY-DEMO-12OZ-XANH} (cartons of 1,000,
 * never produced), the customer's own price by cartons (from 20 at 1,250, from 50 at 1,150, from 100 at 1,050 a cup) and
 * the conversation about the design that {@code demo.sales} is in charge of - so the seed is proven to be what the demo
 * needs, not only to load. Every step goes through the service the screen calls, on behalf of the seeded account that
 * presses it; the chat's cards are drawn by the real listeners, after commit.
 *
 * <ol>
 *   <li>The customer asks a quote on the design: the system posts the QUOTE_REQUEST card into the conversation about
 *       it (16 §4.2 step 5, BR-11).</li>
 *   <li>Sales press CREATE_QUOTE on it: a DRAFT priced from the customer's own tiers, the plate fee added unpriced (the
 *       SKU was never produced, 14 BR-17); the card ACTED.</li>
 *   <li>Sales type the plate fee and send: the QUOTE card appears.</li>
 *   <li>The customer accepts on the card - below the MOQ refused, 100 cartons taken at the 100-carton tier.</li>
 *   <li>Sales convert, draw the contract of two batches and three instalments (ON_SIGNING 30% / BEFORE_DELIVERY batch 1
 *       / AFTER_DELIVERY batch 2), send it (its card appears), upload the signed scan (the card ACTED).</li>
 *   <li>The accountant records the instalment on signing: the order is CONFIRMED by itself.</li>
 * </ol>
 *
 * <p>The money, from one line: 100 cartons x 1,000 cups x 1,050 = 105,000,000; plate fee 1,500,000; VAT 10% of each,
 * 10,500,000 + 150,000; total with tax 117,150,000 = 35,145,000 on signing + 41,002,500 + 41,002,500.</p>
 *
 * <p>The context is {@link Wf02SalesFlowIntegrationTest}'s (same configuration and mocks), so it starts nothing new.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.provider=smtp", "stockflow.notification.email.customer.enabled=false"})
class Wf02ChatToConfirmedIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    private static final UUID CUSTOMER = DemoData.CUSTOMER_ID;
    private static final UUID CUSTOMER_ACCOUNT = DemoData.demoId("demo:wf02:user:demo.khachhang");
    private static final UUID SALES = DemoData.demoId("demo:wf02:user:demo.sales");
    private static final UUID ACCOUNTANT = DemoData.demoId("demo:wf02:user:demo.ketoan");
    private static final UUID DESIGN = DemoData.demoId("demo:wf02:design");
    private static final UUID CONVERSATION = DemoData.demoId("demo:wf02:conversation:ds02a");
    private static final String SKU = "LY-GIAY-DEMO-12OZ-XANH";

    private static final BigDecimal TOTAL = new BigDecimal("117150000");

    @Autowired DesignQuoteRequestService quoteRequests;
    @Autowired ChatCardActions cardActions;
    @Autowired Quotes quotes;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired PaymentReceiptService receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean JavaMailSenderImpl mail;

    private final CurrentUser portal = new CurrentUser(CUSTOMER_ACCOUNT, "demo.khachhang", Set.of(Role.CUSTOMER),
            Set.of(), DataScope.ALL, Set.of(), Set.of());
    private final CurrentUser sales = new CurrentUser(SALES, "demo.sales", Set.of(Role.SALES_STAFF), Set.of(),
            DataScope.ALL, Set.of(), Set.of());

    @Test
    @DisplayName("DS-02a in demo order on the seed: request on the approved design -> QUOTE_REQUEST card -> CREATE_QUOTE "
            + "-> tier price, plate fee, VAT -> QUOTE card -> accepted >= MOQ -> order + contract of 2 batches -> "
            + "sent, signed -> receipt on signing -> CONFIRMED by itself")
    void ds02aFromTheChatToAConfirmedOrder() throws Exception {
        LocalDate today = LocalDate.now(VIETNAM);

        // 1. The customer asks; the system's card lands in the conversation about the design (BR-11).
        UUID requestId = quoteRequests.request(DESIGN, CUSTOMER_ACCOUNT,
                new DesignQuoteRequestService.QuoteRequestInput(60, today.plusDays(30))).request().id();
        UUID requestCard = awaitCard("QUOTE_REQUEST", "requestId", requestId);
        assertThat(card(requestCard).path("conversation").asText()).isEqualTo(CONVERSATION.toString());
        assertThat(card(requestCard).path("sender").asText()).as("BR-11: the system's").isEqualTo("SYSTEM");
        assertThat(card(requestCard).path("state").asText()).isEqualTo("OPEN");
        assertThat(payload(requestCard).path("designStatus").asText()).isEqualTo("APPROVED");
        assertThat(payload(requestCard).path("customerSku").asText()).isEqualTo(SKU);
        assertThat(payload(requestCard).path("cartons").asInt()).isEqualTo(60);

        // 2. Sales draw up the quote from the card: the customer's own tiers, the plate fee left for them to price.
        assertThat(asStaff(() -> cardActions.staffAct(sales, CONVERSATION, requestCard, action("CREATE_QUOTE", null,
                null))).card().state().name()).isEqualTo("ACTED");
        UUID quoteId = UUID.fromString(payload(requestCard).path("quoteId").asText());
        assertThat(payload(requestCard).path("requestStatus").asText()).as("the request answered").isEqualTo("CLOSED");
        Quotes.QuoteView drafted = quotes.get(sales, quoteId);
        assertThat(drafted.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(drafted.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(SKU);
            assertThat(line.cartons()).as("the cartons asked, a suggestion").isEqualTo(60);
            assertThat(line.unitsPerCarton()).isEqualTo(1000);
            assertThat(line.moq()).as("the first tier is the MOQ").isEqualTo(20);
            assertThat(line.tiers()).extracting(Quotes.Tier::minQuantity).containsExactly(20, 50, 100);
            assertThat(line.proposedUnitPrice()).as("60 cartons: the 50-carton tier").isEqualByComparingTo("1150");
        });
        assertThat(drafted.fees()).singleElement().satisfies(fee -> {
            assertThat(fee.kind()).isEqualTo(FeeKind.PLATE_FEE);
            assertThat(fee.sku()).isEqualTo(SKU);
            assertThat(fee.amount()).as("priced by Sales").isNull();
        });

        // 3. Sales type the plate fee and send: the quote's card appears where the request was.
        Quotes.QuoteView priced = quotes.edit(sales, quoteId, drafted.version(), new Quotes.Draft(drafted.validUntil(),
                null, List.of(new Quotes.Line(SKU, 60, List.of())), drafted.requestedDeliveryDate(),
                List.of(new Quotes.Fee(FeeKind.PLATE_FEE, SKU, new BigDecimal("1500000")))));
        assertThat(priced.proposedTotal()).as("69,000,000 + 1,500,000 + VAT 7,050,000")
                .isEqualByComparingTo("77550000");
        Quotes.QuoteView sent = quotes.send(sales, quoteId, priced.version());
        assertThat(sent.status()).isEqualTo(QuoteStatus.SENT);
        UUID quoteCard = awaitCard("QUOTE", "quoteId", quoteId);
        assertThat(card(quoteCard).path("conversation").asText()).isEqualTo(CONVERSATION.toString());
        assertThat(card(quoteCard).path("state").asText()).isEqualTo("OPEN");
        assertThat(payload(quoteCard).path("status").asText()).isEqualTo("SENT");
        UUID line = UUID.fromString(payload(quoteCard).path("lines").get(0).path("id").asText());
        long version = payload(quoteCard).path("version").asLong();

        // 4. The customer accepts on the card: under the MOQ refused, the card still open; 100 cartons taken.
        assertThatThrownBy(() -> cardActions.customerAct(portal, CONVERSATION, quoteCard, action("ACCEPT", version,
                Map.of(line, 10)))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ));
        assertThat(card(quoteCard).path("state").asText()).isEqualTo("OPEN");
        cardActions.customerAct(portal, CONVERSATION, quoteCard, action("ACCEPT", version, Map.of(line, 100)));
        assertThat(card(quoteCard).path("state").asText()).isEqualTo("ACTED");
        assertThat(payload(quoteCard).path("status").asText()).isEqualTo("ACCEPTED");
        Quotes.QuoteView accepted = quotes.get(sales, quoteId);
        assertThat(accepted.lines().getFirst().acceptedUnitPrice()).as("the 100-carton tier")
                .isEqualByComparingTo("1050");
        assertThat(accepted.acceptedTotal()).as("105,000,000 + 1,500,000 + VAT 10,650,000").isEqualByComparingTo(TOTAL);

        // 5. Sales convert it: the DRAFT order is the accepted quote, line, fee and VAT, to the dong.
        UUID orderId = quotes.convert(sales, quoteId, new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        assertThat(status(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(orders.findById(orderId).orElseThrow().total().amount()).isEqualByComparingTo(TOTAL);
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT sum(round(l.quantity * l.unit_price) + l.tax_amount) FROM ordering.order_line l
                         WHERE l.order_id = o.id)
                     + (SELECT coalesce(sum(f.amount + f.tax_amount), 0) FROM ordering.order_fee_line f
                         WHERE f.order_id = o.id)
                  FROM ordering.customer_order o WHERE o.id = ?""", BigDecimal.class, orderId))
                .as("total with tax = the lines + the fees + each one's VAT").isEqualByComparingTo(TOTAL);
        assertThat(jdbc.queryForObject("SELECT tax_amount FROM ordering.customer_order WHERE id = ?", BigDecimal.class,
                orderId)).isEqualByComparingTo("10650000");

        // ... the contract: 50 + 50 cartons, 30% on signing, batch 1 before its delivery, batch 2 after it.
        UUID orderLine = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class,
                orderId);
        SalesContracts.ContractView drafted2 = asStaff(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                                List.of(new SalesContracts.BatchLine(orderLine, 50))),
                        new SalesContracts.Batch(today.plusDays(35), today.plusDays(37),
                                List.of(new SalesContracts.BatchLine(orderLine, 50)))),
                List.of(new SalesContracts.Installment("Dat coc khi ky", "ON_SIGNING", null, new BigDecimal("30"), 3,
                                null),
                        new SalesContracts.Installment("Truoc giao dot 1", "BEFORE_DELIVERY",
                                new BigDecimal("41002500"), null, null, 1),
                        new SalesContracts.Installment("Sau giao dot 2", "AFTER_DELIVERY", new BigDecimal("41002500"),
                                null, 30, 2)))));
        assertThat(drafted2.paymentPlan()).extracting(i -> i.amount().longValueExact())
                .containsExactly(35_145_000L, 41_002_500L, 41_002_500L);
        assertThat(drafted2.paymentPlan().stream().map(SalesContracts.InstallmentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).as("the plan adds up to the total with tax")
                .isEqualByComparingTo(TOTAL);

        SalesContracts.ContractView sentContract = asStaff(() -> contracts.send(sales, orderId, drafted2.version()));
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);
        UUID contractCard = awaitCard("CONTRACT", "contractId", sentContract.id());
        assertThat(card(contractCard).path("state").asText()).isEqualTo("OPEN");
        assertThat(payload(contractCard).path("paymentPlan")).hasSize(3);

        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = asStaff(() -> contracts.sign(sales, orderId, sentContract.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(status(orderId)).as("it waits for the instalment on signing").isEqualTo(OrderStatus.PENDING_PAYMENT);
        Await.until("the contract card follows the signature", ASYNC,
                () -> "ACTED".equals(card(contractCard).path("state").asText()));

        // 6. The accountant records the money on signing: the order confirms itself.
        UUID onSigning = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND seq = 1",
                UUID.class, orderId);
        PaymentReceipt receipt = receipts.record(new PaymentReceiptService.RecordReceiptCommand(CUSTOMER,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(), today, null,
                List.of(new PaymentReceipt.Payment(onSigning, new BigDecimal("35145000"))), ACCOUNTANT)).receipt();
        assertThat(receipt.amount()).isEqualByComparingTo("35145000");
        Await.until("the order confirmed by its receipt", ASYNC, () -> status(orderId) == OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForList("SELECT to_status FROM ordering.order_status_history WHERE order_id = ? "
                + "ORDER BY occurred_at, created_at", String.class, orderId))
                .containsSubsequence("DRAFT", "PENDING_CONTRACT", "PENDING_PAYMENT", "CONFIRMED");

        // The cards as the conversation shows them now.
        assertThat(card(requestCard).path("state").asText()).isEqualTo("ACTED");
        assertThat(card(quoteCard).path("state").asText()).isEqualTo("ACTED");
        assertThat(card(contractCard).path("state").asText()).isEqualTo("ACTED");
        assertThat(jdbc.queryForList("SELECT note FROM chat.conversation_event WHERE conversation_id = ? AND type = "
                + "'CARD_ACTION' ORDER BY occurred_at", String.class, CONVERSATION))
                .as("BR-01: who pressed what").containsSubsequence("CREATE_QUOTE", "ACCEPT");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private <T> T asStaff(java.util.function.Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private static ChatCardActions.Action action(String action, Long version, Map<UUID, Integer> cartons) {
        return new ChatCardActions.Action(action, version, cartons, null, null, null);
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    /** The card of {@code kind} whose payload names {@code value} as {@code field}, once a listener has posted it. */
    private UUID awaitCard(String kind, String field, UUID value) {
        String sql = "SELECT id FROM chat.message WHERE card_kind = ? AND card_payload ->> ? = ?";
        Await.until(kind + " card of " + value, ASYNC,
                () -> !jdbc.queryForList(sql, UUID.class, kind, field, value.toString()).isEmpty());
        List<UUID> found = jdbc.queryForList(sql, UUID.class, kind, field, value.toString());
        assertThat(found).as("one %s card", kind).hasSize(1);
        return found.getFirst();
    }

    /** A card's conversation, sender, state and payload as stored. */
    private JsonNode card(UUID messageId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT conversation_id, sender_type, card_state, card_payload::text"
                + " AS payload FROM chat.message WHERE id = ?", messageId);
        try {
            return json.createObjectNode().put("conversation", String.valueOf(row.get("conversation_id")))
                    .put("sender", (String) row.get("sender_type")).put("state", (String) row.get("card_state"))
                    .set("payload", json.readTree((String) row.get("payload")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private JsonNode payload(UUID messageId) {
        return card(messageId).path("payload");
    }
}
