package com.stockflow.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.internal.service.BankTransactionService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.TestTokens;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Part C of the credit plan of 2026-11-26 (kltn-docs 15 BR-02, §4.2) against the real database, with SePay faked: its
 * webhook calls are built and signed here and sent over HTTP through the real security chain, and its transaction API
 * is a WireMock server. No network.
 *
 * <p>Every contract is drawn up, sent and signed through {@link SalesContracts}, as {@code InstallmentReceiptIntegrationTest}
 * does: the installments a transfer names are the ones signing opens. The order total is 2,200,000 dong; the plan is
 * 30% on signing (660,000) and the rest before delivery - no credit is needed to sign it.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true",
        "stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false",
        "stockflow.payment.bank-feed.enabled=true",
        "stockflow.payment.bank-feed.account-number=0071009999999",
        "stockflow.payment.bank-feed.webhook.hmac-secret=" + BankFeedIntegrationTest.SECRET,
        "stockflow.payment.bank-feed.api.token=test-api-token",
        // The nightly job never fires on its own here: the catch-up is called by hand.
        "stockflow.payment.bank-feed.catch-up-cron=-"})
class BankFeedIntegrationTest {

    static final String SECRET = "bank-feed-test-secret";
    private static final String WEBHOOK = "/api/v1/bank-transactions/webhooks/sepay";
    private static final String ACCOUNT = "0071009999999";
    private static final UUID FEED_USER = UUID.fromString("0193c000-0000-7000-8000-00000000bf01");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter SEPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    /** Order numbers of its own: a band no other test draws from. */
    private static final AtomicLong ORDER_NUMBERS =
            new AtomicLong(3_100_000 + ThreadLocalRandom.current().nextInt(50_000) * 10L);
    private static final AtomicLong SEPAY_IDS = new AtomicLong(900_000_000L + ThreadLocalRandom.current()
            .nextInt(50_000_000));
    private static final WireMockServer SEPAY = new WireMockServer(options().dynamicPort());

    static {
        SEPAY.start();
    }

    @DynamicPropertySource
    static void sepay(DynamicPropertyRegistry registry) {
        registry.add("stockflow.payment.bank-feed.api.base-url", () -> SEPAY.baseUrl() + "/v2");
    }

    @AfterAll
    static void stopSepay() {
        SEPAY.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentReceiptService receipts;
    @Autowired BankTransactionService bankTransactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;
    @MockitoBean JwtDecoder decoder;

    private CurrentUser sales;
    private UUID accountant;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    private record Signed(UUID orderId, String contractNumber, List<UUID> installmentIds) {
    }

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Chuyen Khoan', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "bank-feed-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
        SEPAY.resetAll();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    /** A new order signed today: 30% on signing, the rest before delivery. */
    private Signed signed() {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            return orderRepository.save(Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()), customerId,
                    Identifiers.newId(), List.of(line), Instant.now())).id().value();
        });
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        List<SalesContracts.Installment> plan = List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("30"), 3, null),
                new SalesContracts.Installment("Rest", "BEFORE_DELIVERY", null, new BigDecimal("70"), null, null));
        SalesContracts.ContractView drafted = WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.create(sales,
                orderId, new SalesContracts.Draft(null, List.of(new SalesContracts.Batch(today.plusDays(21),
                        today.plusDays(23), List.of(new SalesContracts.BatchLine(line, 20)))), plan)));
        SalesContracts.ContractView sent = WithCurrentUser.call(sales, DataScope.ALL,
                () -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signedView = WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.sign(sales,
                orderId, sent.version(), IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf",
                        scan.length, new ByteArrayInputStream(scan)))), today));
        List<UUID> ids = jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq",
                UUID.class, orderId);
        return new Signed(orderId, signedView.contractNumber(), ids);
    }

    private static String reference() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
    }

    /** SePay's webhook body for an incoming transfer, as its documentation gives it. */
    private static String body(long id, String account, long amount, String content, String reference) {
        return """
                {"id":%d,"gateway":"Vietcombank","transactionDate":"%s","accountNumber":"%s","subAccount":"",
                 "code":null,"content":"%s","transferType":"in","description":"CONG TY CK chuyen tien",
                 "transferAmount":%d,"accumulated":0,"referenceCode":"%s"}""".formatted(id,
                SEPAY_TIME.format(Instant.now().minusSeconds(30).atZone(VIETNAM)), account, content, amount, reference);
    }

    private static String sign(long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body)
                .getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions webhook(String body) throws Exception {
        long now = Instant.now().getEpochSecond();
        return mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-SePay-Signature", sign(now, body)).header("X-SePay-Timestamp", String.valueOf(now)));
    }

    private ResultActions asAccountant(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                               request) throws Exception {
        return mvc.perform(request.header("Authorization", TestTokens.bearer(decoder, accountant, Role.ACCOUNTANT)));
    }

    private Map<String, Object> transactionOf(String bankReference) {
        return jdbc.queryForMap("SELECT * FROM payment.bank_transaction WHERE bank_reference = ?", bankReference);
    }

    private long transactionsOf(String bankReference) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.bank_transaction WHERE bank_reference = ?",
                Long.class, bankReference);
    }

    private OrderStatus orderStatus(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private String installmentStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM payment.installment WHERE id = ?", String.class, id);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a transfer naming one installment, for what it owes: a receipt by the feed's user, the order confirms; "
            + "the same call again keeps nothing twice")
    void matchingTransferRecordsTheReceipt() throws Exception {
        Signed signed = signed();
        assertThat(orderStatus(signed.orderId())).isEqualTo(OrderStatus.PENDING_PAYMENT);
        String ref = reference();
        String body = body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 660_000, signed.contractNumber() + " DOT1", ref);

        webhook(body).andExpect(status().isOk()).andExpect(content().json("{\"success\":true}", true));

        Map<String, Object> row = transactionOf(ref);
        assertThat(row.get("status")).isEqualTo("APPLIED");
        assertThat(row.get("reviewed_by")).as("applied by the feed itself").isNull();
        Map<String, Object> receipt = jdbc.queryForMap("SELECT * FROM payment.payment_receipt WHERE id = ?",
                row.get("receipt_id"));
        assertThat(receipt.get("reference")).as("the bank's reference, so BR-03 refuses the same money twice")
                .isEqualTo(ref);
        assertThat(receipt.get("recorded_by")).isEqualTo(FEED_USER);
        assertThat(receipt.get("customer_id")).isEqualTo(customerId);
        assertThat(installmentStatus(signed.installmentIds().getFirst())).isEqualTo("PAID");
        Await.until("the order confirms itself (BR-07)", ASYNC,
                () -> orderStatus(signed.orderId()) == OrderStatus.CONFIRMED);

        webhook(body).andExpect(status().isOk()).andExpect(content().json("{\"success\":true}", true));
        assertThat(transactionsOf(ref)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_receipt WHERE customer_id = ?",
                Long.class, customerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a call whose signature does not hold, or for a provider not taken, is 401 and keeps nothing")
    void unsignedIsRefused() throws Exception {
        String ref = reference();
        String body = body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 1_000, "anything", ref);
        long now = Instant.now().getEpochSecond();
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", "sha256=" + "0".repeat(64))
                        .header("X-SePay-Timestamp", String.valueOf(now)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"));
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Apikey " + SECRET))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bank-transactions/webhooks/casso").contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("X-SePay-Signature", sign(now, body))
                        .header("X-SePay-Timestamp", String.valueOf(now)))
                .andExpect(status().isUnauthorized());
        assertThat(transactionsOf(ref)).isZero();
    }

    @Test
    @DisplayName("money going out is answered success and not kept")
    void outgoingIsNotKept() throws Exception {
        String ref = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 50_000, "phi dich vu", ref).replace("\"in\"", "\"out\""))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true}", true));
        assertThat(transactionsOf(ref)).isZero();
    }

    @Test
    @DisplayName("content naming nothing waits for the Accountant, who assigns it: the receipt is theirs, the order "
            + "confirms")
    void unmatchedIsAssignedByTheAccountant() throws Exception {
        Signed signed = signed();
        String ref = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 660_000, "CONG TY CK thanh toan tien hang", ref))
                .andExpect(status().isOk());
        Map<String, Object> row = transactionOf(ref);
        assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(row.get("review_reason")).isEqualTo("NO_INSTALLMENT_NAMED");
        UUID id = (UUID) row.get("id");

        asAccountant(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/bank-transactions").param("status", "NEEDS_REVIEW").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')].reviewReason".formatted(id))
                        .value("NO_INSTALLMENT_NAMED"));

        asAccountant(post("/api/v1/bank-transactions/{id}/assignment", id).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId":"%s","lines":[{"installmentId":"%s","amount":700000}]}"""
                        .formatted(customerId, signed.installmentIds().getFirst())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BANK_TRANSACTION_OVER_ASSIGNED"));

        asAccountant(post("/api/v1/bank-transactions/{id}/assignment", id).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId":"%s","lines":[{"installmentId":"%s","amount":660000}]}"""
                        .formatted(customerId, signed.installmentIds().getFirst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.automatic").value(false))
                .andExpect(jsonPath("$.data.reviewedBy").value(accountant.toString()));
        Map<String, Object> receipt = jdbc.queryForMap("SELECT * FROM payment.payment_receipt WHERE id = ?",
                transactionOf(ref).get("receipt_id"));
        assertThat(receipt.get("recorded_by")).isEqualTo(accountant);
        assertThat(receipt.get("reference")).isEqualTo(ref);
        Await.until("the order confirms itself", ASYNC, () -> orderStatus(signed.orderId()) == OrderStatus.CONFIRMED);

        asAccountant(post("/api/v1/bank-transactions/{id}/ignore", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"twice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BANK_TRANSACTION_NOT_OPEN"));
    }

    @Test
    @DisplayName("more than the installment owes waits for the Accountant with a hint; the Accountant ignores it")
    void overpaymentWaitsAndIsIgnored() throws Exception {
        Signed signed = signed();
        String ref = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 700_000, signed.contractNumber() + " DOT1", ref))
                .andExpect(status().isOk());
        Map<String, Object> row = transactionOf(ref);
        assertThat(row.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(row.get("review_reason")).isEqualTo("OVERPAYMENT");
        assertThat(row.get("installment_id")).isEqualTo(signed.installmentIds().getFirst());
        assertThat(row.get("customer_id")).isEqualTo(customerId);
        assertThat(installmentStatus(signed.installmentIds().getFirst())).isEqualTo("DUE");

        asAccountant(post("/api/v1/bank-transactions/{id}/ignore", row.get("id"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer sent the wrong amount; refunded in the accounting software\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IGNORED"));
    }

    @Test
    @DisplayName("money for a cancelled order, two installments in one transfer, another account: all wait for review")
    void otherCasesWait() throws Exception {
        Signed cancelled = signed();
        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(cancelled.orderId(), new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", null, sales.userId())));
        Await.until("installments cancelled", ASYNC,
                () -> "CANCELLED".equals(installmentStatus(cancelled.installmentIds().getFirst())));
        String late = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 660_000, cancelled.contractNumber() + " DOT1", late))
                .andExpect(status().isOk());
        assertThat(transactionOf(late).get("review_reason")).isEqualTo("NOT_PAYABLE");

        Signed two = signed();
        String both = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 2_200_000, two.contractNumber() + " DOT1 "
                + two.contractNumber() + " DOT2", both)).andExpect(status().isOk());
        assertThat(transactionOf(both).get("review_reason")).isEqualTo("SEVERAL_INSTALLMENTS");

        String elsewhere = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), "1234567890", 660_000, two.contractNumber() + " DOT1", elsewhere))
                .andExpect(status().isOk());
        assertThat(transactionOf(elsewhere).get("review_reason")).isEqualTo("OTHER_ACCOUNT");
        assertThat(installmentStatus(two.installmentIds().getFirst())).isEqualTo("DUE");
    }

    @Test
    @DisplayName("a receipt from the feed voided: the transfer is back in the queue, to be assigned again")
    void voidedReceiptGoesBackToTheQueue() throws Exception {
        Signed signed = signed();
        String ref = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 660_000, signed.contractNumber() + " DOT1", ref))
                .andExpect(status().isOk());
        UUID receiptId = (UUID) transactionOf(ref).get("receipt_id");
        Await.until("confirmed", ASYNC, () -> orderStatus(signed.orderId()) == OrderStatus.CONFIRMED);

        receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(receiptId, "recorded against the wrong order",
                true, accountant));

        Await.until("the transfer is back in the queue", ASYNC,
                () -> "NEEDS_REVIEW".equals(transactionOf(ref).get("status")));
        Map<String, Object> row = transactionOf(ref);
        assertThat(row.get("review_reason")).isEqualTo("RECEIPT_VOIDED");
        assertThat(row.get("receipt_id")).isNull();
    }

    @Test
    @DisplayName("the catch-up keeps what the webhook missed, not what it delivered, and handles a stranded transfer")
    void catchUpTakesWhatWasMissed() throws Exception {
        Signed delivered = signed();
        Signed missed = signed();
        String deliveredRef = reference();
        webhook(body(SEPAY_IDS.incrementAndGet(), ACCOUNT, 660_000, delivered.contractNumber() + " DOT1",
                deliveredRef)).andExpect(status().isOk());
        String missedRef = reference();
        String at = Instant.now().minusSeconds(3600).atZone(VIETNAM).toOffsetDateTime().toString();
        SEPAY.stubFor(get(urlPathEqualTo("/v2/transactions")).willReturn(okJson("""
                {"status":"success","data":[
                  {"id":"%s","transaction_date":"%s","account_number":"%s","transfer_type":"in","amount_in":660000,
                   "amount_out":0,"transaction_content":"%s DOT1","reference_number":"%s","code":null},
                  {"id":"%s","transaction_date":"%s","account_number":"%s","transfer_type":"in","amount_in":660000,
                   "amount_out":0,"transaction_content":"%s DOT1","reference_number":"%s","code":null}],
                 "meta":{"pagination":{"total":2,"per_page":100,"current_page":1,"last_page":1,"has_more":false}}}
                """.formatted(UUID.randomUUID(), at, ACCOUNT, delivered.contractNumber(), deliveredRef,
                UUID.randomUUID(), at, ACCOUNT, missed.contractNumber(), missedRef))));

        // A transfer a failure left RECEIVED an hour ago: the catch-up handles it again.
        String strandedRef = reference();
        Signed stranded = signed();
        tx.executeWithoutResult(t -> jdbc.update("""
                INSERT INTO payment.bank_transaction (id, provider, provider_transaction_id, source, dedupe_key,
                    account_number, amount, content, bank_reference, transaction_at, received_at, raw, status,
                    version, created_at)
                VALUES (?, 'SEPAY', ?, 'WEBHOOK', ?, ?, 660000, ?, ?, NOW() - interval '1 hour',
                    NOW() - interval '1 hour', '{}'::jsonb, 'RECEIVED', 0, NOW())""", Identifiers.newId(),
                String.valueOf(SEPAY_IDS.incrementAndGet()), "REF:" + strandedRef, ACCOUNT,
                stranded.contractNumber() + " DOT1", strandedRef));

        BankTransactionService.CatchUp run = bankTransactions.catchUp();

        assertThat(run.read()).isEqualTo(2);
        assertThat(run.kept()).as("the delivered one is known by its bank reference").isEqualTo(1);
        assertThat(run.listRead()).isTrue();
        assertThat(run.retried()).isGreaterThanOrEqualTo(1);
        assertThat(transactionsOf(deliveredRef)).isEqualTo(1);
        assertThat(transactionOf(missedRef)).containsEntry("status", "APPLIED").containsEntry("source", "CATCH_UP");
        assertThat(transactionOf(strandedRef).get("status")).isEqualTo("APPLIED");
        SEPAY.verify(getRequestedFor(urlPathEqualTo("/v2/transactions"))
                .withHeader("Authorization", equalTo("Bearer test-api-token"))
                .withQueryParam("transfer_type", equalTo("in"))
                .withQueryParam("per_page", equalTo("100")));
    }
}
