package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant's money features (work features 2.1-2.2) through the real security chain, the seeded roles and the
 * real payment, customer, order and identity reads: a contract signed as {@code BankFeedIntegrationTest} signs it, a
 * transfer waiting for review, the collections list and a reminder drafted by a scripted model.
 *
 * <p>The contract: 2,200,000 dong, 30% (660,000) due three days after signing, the rest before delivery.</p>
 */
@SecuredIntegrationTest
// Signing a contract stores its scan, and no virus scanner runs in tests (ConfirmedOrders).
@org.springframework.test.context.TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class AssistantMoneyIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Order numbers of its own: a band no other test draws from. */
    private static final AtomicLong ORDER_NUMBERS =
            new AtomicLong(3_700_000 + ThreadLocalRandom.current().nextInt(50_000) * 10L);
    private static final String CUSTOMER_NAME = "Công ty TNHH Bao Bì Thử Nghiệm Trợ Lý";

    @Autowired MockMvc mockMvc;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired SalesContracts contracts;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private CurrentUser sales;
    private UUID accountant;
    private UUID customerId;
    private String taxCode;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    private record Signed(UUID orderId, String orderNumber, String contractNumber, List<UUID> installmentIds) {
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", customerId, CUSTOMER_NAME,
                    "assistant-money-" + customerId + "@example.com", taxCode);
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
        // The collections list names whoever looks after the customer (15 BR-22, 2026-11-28): the test's salesperson.
        com.stockflow.support.SalesOwners owners = new com.stockflow.support.SalesOwners(context);
        owners.grant(salesUser, "SALES_STAFF");
        owners.own(customerId, salesUser);
    }

    /** A new order signed today: 30% due three days after signing, the rest before delivery. */
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
        String orderNumber = jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?",
                String.class, orderId);
        return new Signed(orderId, orderNumber, signedView.contractNumber(), ids);
    }

    /** A transfer the bank feed left for the Accountant, as {@code BankTransactionService.review} leaves it. */
    private UUID waitingForReview(long amount, String content) {
        UUID id = Identifiers.newId();
        String reference = "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.bank_transaction (id, provider, provider_transaction_id, source, dedupe_key,
                    account_number, amount, content, bank_reference, transaction_at, received_at, raw, status,
                    review_reason, review_note, decided_at, version, created_at)
                VALUES (?, 'SEPAY', ?, 'WEBHOOK', ?, '0071009999999', ?, ?, ?, NOW(), NOW(), '{}'::jsonb,
                    'NEEDS_REVIEW', 'NO_INSTALLMENT_NAMED', 'The transfer''s content names no contract installment',
                    NOW(), 0, NOW())""", id, "assistant-" + id, "REF:" + reference, amount, content, reference));
        return id;
    }

    private static String compact(String orderNumber) {
        return orderNumber.replace("-", "").toLowerCase();
    }

    // ---- 2.1 match suggestions --------------------------------------------------------------------------------------

    @Test
    @DisplayName("a transfer naming the order without its dashes and the installment's exact amount: that installment "
            + "first, with why, a link and the customer's name; no model is asked")
    void matchSuggestions() throws Exception {
        Signed signed = signed();
        UUID transfer = waitingForReview(660_000, "CONG TY BAO BI THU NGHIEM TRO LY CK TT DON "
                + compact(signed.orderNumber()));

        String body = http.asRole(Role.ACCOUNTANT, accountant,
                        get("/api/v1/assistant/bank-transactions/{id}/match-suggestions", transfer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transaction.id").value(transfer.toString()))
                .andExpect(jsonPath("$.data.transaction.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.transaction.reviewReason").value("NO_INSTALLMENT_NAMED"))
                .andExpect(jsonPath("$.data.ambiguous").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode suggestions = json.readTree(body).path("data").path("suggestions");
        assertThat(suggestions.size()).isBetween(1, 3);
        JsonNode first = suggestions.get(0);
        assertThat(first.path("installmentId").asText()).isEqualTo(signed.installmentIds().getFirst().toString());
        assertThat(first.path("contractNumber").asText()).isEqualTo(signed.contractNumber());
        assertThat(first.path("customerName").asText()).isEqualTo(CUSTOMER_NAME);
        assertThat(first.path("amountDue").decimalValue()).isEqualByComparingTo("660000");
        assertThat(first.path("score").asInt()).isEqualTo(100);
        assertThat(first.path("reasons").toString()).contains("ORDER_NUMBER_IN_CONTENT", "CUSTOMER_NAME_IN_CONTENT",
                "AMOUNT_EXACT", "DUE_SOON");
        assertThat(first.path("href").asText()).isEqualTo("/admin/payment/installments/"
                + signed.installmentIds().getFirst());
        assertThat(body).as("the customer's tax code is compared, never returned").doesNotContain(taxCode);
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("only the bank transfer screen's roles: Sales 403, a portal customer 403, an unknown transfer 404")
    void matchSuggestionsGuard() throws Exception {
        UUID transfer = waitingForReview(1_000_000, "thanh toan tien hang");

        http.asRole(Role.SALES_STAFF, sales.userId(),
                        get("/api/v1/assistant/bank-transactions/{id}/match-suggestions", transfer))
                .andExpect(status().isForbidden());
        http.asCustomer(Identifiers.newId(), get("/api/v1/assistant/bank-transactions/{id}/match-suggestions",
                        transfer))
                .andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant,
                        get("/api/v1/assistant/bank-transactions/{id}/match-suggestions", Identifiers.newId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BANK_TRANSACTION_NOT_FOUND"));
    }

    // ---- 2.2 collections --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the installment due in three days is on the list with its customer, the salesperson who looks "
            + "after the customer and why; ranked by points, bounded, never an installment before delivery")
    void collections() throws Exception {
        Signed signed = signed();

        String body = http.asRole(Role.SALES_STAFF, sales.userId(), get("/api/v1/assistant/collections"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).path("data");
        JsonNode items = data.path("items");
        assertThat(items.size()).isLessThanOrEqualTo(50);
        assertThat(data.path("total").asInt()).isGreaterThanOrEqualTo(items.size());
        List<String> priorities = new ArrayList<>();
        items.forEach(item -> priorities.add(item.path("priority").asText()));
        assertThat(priorities).as("HIGH before MEDIUM before LOW").isSortedAccordingTo(
                java.util.Comparator.comparingInt(p -> List.of("HIGH", "MEDIUM", "LOW").indexOf(p)));
        assertThat(body).doesNotContain(signed.installmentIds().get(1).toString());
        if (!data.path("truncated").asBoolean()) {
            JsonNode mine = null;
            for (JsonNode item : items) {
                if (item.path("installmentId").asText().equals(signed.installmentIds().getFirst().toString())) {
                    mine = item;
                }
            }
            assertThat(mine).as("the installment due in three days").isNotNull();
            assertThat(mine.path("customer").path("name").asText()).isEqualTo(CUSTOMER_NAME);
            assertThat(mine.path("salesperson").path("id").asText()).isEqualTo(sales.userId().toString());
            assertThat(mine.path("daysOverdue").asInt()).isZero();
            assertThat(mine.path("dueDate").asText()).isEqualTo(today.plusDays(3).toString());
            assertThat(mine.path("why").get(0).asText()).isNotBlank();
            assertThat(mine.path("href").asText()).endsWith(signed.installmentIds().getFirst().toString());
        }
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("the installment list's roles only: a warehouse manager 403, a portal customer 403 though it holds "
            + "the permission for its own installments")
    void collectionsGuard() throws Exception {
        UUID manager = tx.execute(s -> ReferenceRows.user(entityManager));

        http.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/assistant/collections"))
                .andExpect(status().isForbidden());
        http.asCustomer(Identifiers.newId(), get("/api/v1/assistant/collections"))
                .andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_MANAGER, manager, post("/api/v1/assistant/collections/{id}/reminder-draft",
                        Identifiers.newId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the reminder: the model sees KH#1 and the installment's facts, never the name or the tax code; the "
            + "draft names the customer, links its sources and flags nothing it was given")
    void reminderDraft() throws Exception {
        Signed signed = signed();
        UUID installment = signed.installmentIds().getFirst();
        when(model.available()).thenReturn(true);
        when(model.next(any())).thenReturn(new LanguageModel.Reply(new Turn.ModelTurn("{}", """
                Kính gửi KH#1,
                Đợt Deposit của hợp đồng %s còn 660.000 đồng. Quý khách vui lòng ghi nội dung chuyển khoản %s DOT1.
                Trân trọng,
                Phòng Kinh doanh""".formatted(signed.contractNumber(), signed.contractNumber()), List.of()),
                LanguageModel.StopReason.END_TURN, LanguageModel.Usage.NONE, "scripted-model"));

        http.asRole(Role.ACCOUNTANT, accountant, post("/api/v1/assistant/collections/{id}/reminder-draft",
                        installment).contentType(MediaType.APPLICATION_JSON).content("{\"tone\":\"firm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.text").value(org.hamcrest.Matchers.startsWith(
                        "Kính gửi " + CUSTOMER_NAME)))
                .andExpect(jsonPath("$.data.draft.unverifiedCodes").isEmpty())
                .andExpect(jsonPath("$.data.draft.model").value("scripted-model"))
                .andExpect(jsonPath("$.data.draft.sources.length()").value(3))
                .andExpect(jsonPath("$.data.draft.sources[2].href").value("/admin/payment/installments/"
                        + installment));

        ArgumentCaptor<LanguageModel.Request> sent = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model).next(sent.capture());
        String question = ((Turn.Question) sent.getValue().history().getFirst()).text();
        assertThat(question).contains("KH#1", signed.contractNumber(), "660000", "\"tone\":\"FIRM\"")
                .doesNotContain(CUSTOMER_NAME, taxCode, customerId.toString());
    }

    @Test
    @DisplayName("the reminder's refusals: another tone 400, an unknown installment 404, one not due yet 409, no "
            + "model 503 ASSISTANT_UNAVAILABLE")
    void reminderDraftRefusals() throws Exception {
        Signed signed = signed();
        UUID installment = signed.installmentIds().getFirst();

        http.asRole(Role.ACCOUNTANT, accountant, post("/api/v1/assistant/collections/{id}/reminder-draft",
                        installment).contentType(MediaType.APPLICATION_JSON).content("{\"tone\":\"ANGRY\"}"))
                .andExpect(status().isBadRequest());
        http.asRole(Role.ACCOUNTANT, accountant, post("/api/v1/assistant/collections/{id}/reminder-draft",
                        Identifiers.newId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_FOUND"));
        // Review finding 4: the installment due before delivery has no due day until the delivery is ready - the
        // customer owes nothing for it yet, and the collections list does not offer it.
        http.asRole(Role.ACCOUNTANT, accountant, post("/api/v1/assistant/collections/{id}/reminder-draft",
                        signed.installmentIds().get(1)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_DUE_SOON"));
        when(model.available()).thenReturn(false);
        http.asRole(Role.ACCOUNTANT, accountant, post("/api/v1/assistant/collections/{id}/reminder-draft",
                        installment).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_UNAVAILABLE"));
        verify(model, never()).next(any());
    }
}
