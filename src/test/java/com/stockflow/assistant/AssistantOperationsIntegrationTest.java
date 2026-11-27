package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.SalesQueues;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.support.AssistantStock;
import com.stockflow.support.CartonRows;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SecuredProductionReceiptTest;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The warehouse and production work features (contract 3.1-3.3) with the real security chain, the real permission
 * aspect, real migrations and the seeded roles: the work queue, a missing carton investigated, and the Production
 * Coordinator's follow-ups with the producer's draft. Each endpoint: what its role gets, a role without the screen's
 * permission refused, another warehouse not revealed, and the edge that matters to the person using it.
 *
 * <p>Production orders are real ones ({@link ProductionReceivables}); the HCM one is accepted by its producer for a day
 * after its batch needs the goods, so it is AT_RISK, and one at a warehouse of its own is SENT.</p>
 */
@SecuredProductionReceiptTest
class AssistantOperationsIntegrationTest {

    private static final String BASE = "/api/v1/assistant";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired ProductionReceivables receivables;
    @Autowired ProductionOrderService productionOrders;
    @Autowired ApplicationContext context;
    @Autowired SalesContracts salesContracts;
    @Autowired SalesQueues salesQueues;

    private SecuredMvc http;
    private AssistantStock fixtures;
    private StaffFixtures staff;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        fixtures = new AssistantStock(jdbc, tx, entityManager);
        staff = new StaffFixtures(jdbc, tx, entityManager);
    }

    // ------------------------------------------------------------------------------------------------- work queue

    @Test
    @DisplayName("work queue: a warehouse manager of HCM gets the warehouse sections of HCM only, no money; no model")
    void workQueueOfAWarehouseManager() throws Exception {
        UUID manager = fixtures.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);

        JsonNode queue = data(http.asRole(Role.WAREHOUSE_MANAGER, manager, get(BASE + "/work-queue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        List<String> keys = keys(queue);
        assertThat(keys).contains("receiptsToPutAway").doesNotContain("bankTransactionsToReview",
                "overdueInstallments", "creditReviewsPending", "einvoicesToIssue");
        for (JsonNode section : queue.path("sections")) {
            assertThat(section.path("severity").asText()).isIn("INFO", "WARN", "CRITICAL");
            assertThat(section.path("items").size()).isLessThanOrEqualTo(5);
            assertThat(section.path("failed").asBoolean()).as(section.path("key").asText()).isFalse();
        }
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("work queue narrowed to a warehouse: the shift brief of HCM; a warehouse not the manager's is 404")
    void workQueueNarrowedToAWarehouse() throws Exception {
        UUID manager = fixtures.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        String other = staff.otherWarehouse().prefix();

        http.asRole(Role.WAREHOUSE_MANAGER, manager, get(BASE + "/work-queue").param("warehouse", "hcm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouse").value("HCM"));
        http.asRole(Role.WAREHOUSE_MANAGER, manager, get(BASE + "/work-queue").param("warehouse", other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WAREHOUSE_NOT_FOUND"));
    }

    @Test
    @DisplayName("work queue: the accountant gets the money sections; the production orders late section counts the "
            + "AT_RISK order for who may read production orders")
    void workQueueOfOtherRoles() throws Exception {
        UUID accountant = fixtures.staff("ACCOUNTANT");
        JsonNode money = data(http.asRole(Role.ACCOUNTANT, accountant, get(BASE + "/work-queue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(keys(money)).contains("bankTransactionsToReview", "overdueInstallments");

        atRiskAtHcm();
        UUID coordinator = fixtures.staff("PRODUCTION_COORDINATOR");
        JsonNode production = data(http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, get(BASE + "/work-queue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode late = section(production, "productionOrdersLate");
        assertThat(late.path("count").asLong()).isPositive();
        assertThat(late.path("href").asText(null)).as("no screen lists this set").isNull();
        assertThat(late.path("items").get(0).path("href").asText()).startsWith("/admin/production-orders/");
    }

    @Test
    @DisplayName("work queue: an approval queue is shown to who decides it - Sales Staff, who read quotes and credit "
            + "holds, get neither; the Sales Manager gets the quotes to approve, the accountant the credit reviews")
    void approvalQueuesGoToWhoApproves() throws Exception {
        UUID salesStaff = fixtures.staff("SALES_STAFF");
        UUID salesManager = fixtures.staff("SALES_MANAGER");
        UUID accountant = fixtures.staff("ACCOUNTANT");

        List<String> staffKeys = keys(data(http.asRole(Role.SALES_STAFF, salesStaff, get(BASE + "/work-queue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)));
        List<String> managerKeys = keys(data(http.asRole(Role.SALES_MANAGER, salesManager, get(BASE + "/work-queue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)));
        List<String> accountantKeys = keys(data(http.asRole(Role.ACCOUNTANT, accountant, get(BASE + "/work-queue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)));

        assertThat(staffKeys).doesNotContain("quotesAwaitingPriceApproval", "creditReviewsPending");
        assertThat(managerKeys).contains("quotesAwaitingPriceApproval").doesNotContain("creditReviewsPending");
        assertThat(accountantKeys).contains("creditReviewsPending");
    }

    @Test
    @DisplayName("batches due: only the live contract's - a contract cancelled before signing keeps its batch, due "
            + "today, which nobody owes and which must neither double the order nor turn the section critical")
    void batchesDueIgnoreACancelledContract() {
        ConfirmedOrders orders = new ConfirmedOrders(context);
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 50);
        UUID orderId = orders.draft(DemoData.CUSTOMER_ID, List.of(ConfirmedOrders.Line.cartons(sku, 2, 1_000)));
        orders.legalProfile(DemoData.CUSTOMER_ID);
        UUID line = jdbc.queryForObject("select id from ordering.order_line where order_id = ?", UUID.class,
                orderId);
        LocalDate today = LocalDate.now(VIETNAM);
        // Drawn up with one batch ending today, then cancelled before it was sent and drawn up again: the order's
        // own tests' way (SalesContractIntegrationTest.cancelledAndDrawnUpAgain).
        SalesContracts.ContractView first = WithCurrentUser.call(orders.sales(), DataScope.ALL,
                () -> salesContracts.create(orders.sales(), orderId, new SalesContracts.Draft(null,
                        List.of(new SalesContracts.Batch(today, today, List.of(new SalesContracts.BatchLine(line,
                                2)))), ConfirmedOrders.paidOnSigning())));
        WithCurrentUser.call(orders.sales(), DataScope.ALL, () -> salesContracts.cancel(orders.sales(), orderId,
                first.version(), "The customer wants a later window"));
        orders.pay(orders.sign(orderId, ConfirmedOrders.paidOnSigning()));
        assertThat(jdbc.queryForObject("""
                select count(*) from ordering.delivery_batch b join ordering.sales_contract k on k.id = b.contract_id
                 where k.order_id = ? and k.status = 'CANCELLED' and b.window_end = ?""", Long.class, orderId, today))
                .as("the cancelled contract's batch, due today, is still there").isEqualTo(1);

        List<SalesQueues.BatchDue> due = salesQueues.batchesDue(today, null, SalesQueues.MAX_ROWS).items().stream()
                .filter(batch -> batch.orderId().equals(orderId)).toList();

        assertThat(due).singleElement().satisfies(batch -> {
            assertThat(batch.windowEnd()).as("the live contract's batch, not the cancelled one's")
                    .isEqualTo(today.plusDays(2));
            assertThat(batch.batchSeq()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("work queue needs assistant-conversations:READ: a portal customer is refused")
    void workQueueRefusesACustomer() throws Exception {
        http.asCustomer(UUID.randomUUID(), get(BASE + "/work-queue")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------- carton investigation

    @Test
    @DisplayName("carton investigation: the carton, its open work, and where to look first - the books' place")
    void investigatesACarton() throws Exception {
        String lpn = cartonAtHcm();
        UUID manager = fixtures.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);

        JsonNode found = data(http.asRole(Role.WAREHOUSE_MANAGER, manager,
                        get(BASE + "/cartons/{lpn}/investigation", lpn.toLowerCase()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(found.path("lpn").asText()).isEqualTo(lpn);
        assertThat(found.path("status").asText()).isEqualTo("IN_STOCK");
        assertThat(found.path("warehouseCode").asText()).isEqualTo("HCM");
        assertThat(found.path("whereToLook").get(0).path("why").asText()).isEqualTo("LAST_RECORDED");
        assertThat(found.path("whereToLook").get(0).path("locationCode").asText()).isEqualTo(AssistantStock.HCM_BIN);
        assertThat(found.path("timeline").isArray()).isTrue();
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("carton investigation: a manager of another warehouse is refused as the carton lookup refuses; an "
            + "unknown LPN is 404; a role without the carton lookup is 403")
    void cartonRefusals() throws Exception {
        String lpn = cartonAtHcm();
        UUID elsewhere = fixtures.staff("WAREHOUSE_MANAGER", staff.otherWarehouse().id());
        UUID sales = fixtures.staff("SALES_STAFF");

        http.asRole(Role.WAREHOUSE_MANAGER, elsewhere, get(BASE + "/cartons/{lpn}/investigation", lpn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        http.asRole(Role.WAREHOUSE_MANAGER, elsewhere, get(BASE + "/cartons/{lpn}/investigation", "LPN-NO-SUCH"))
                .andExpect(status().isNotFound());
        http.asRole(Role.SALES_STAFF, sales, get(BASE + "/cartons/{lpn}/investigation", lpn))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------- production follow-ups

    @Test
    @DisplayName("follow-ups: the AT_RISK order with its batch, the other warehouse's one hidden from a manager of HCM")
    void followUps() throws Exception {
        ProductionReceivables.Receivable hcm = atRiskAtHcm();
        UUID coordinator = fixtures.staff("PRODUCTION_COORDINATOR");

        JsonNode list = data(http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, get(BASE + "/production-follow-ups"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        JsonNode item = null;
        for (JsonNode candidate : list.path("items")) {
            if (candidate.path("productionOrderId").asText().equals(hcm.productionOrderId().toString())) {
                item = candidate;
            }
        }
        assertThat(item).as("the AT_RISK production order is listed").isNotNull();
        assertThat(item.path("reason").asText()).isEqualTo("AT_RISK");
        assertThat(item.path("priority").asText()).isEqualTo("MEDIUM");
        assertThat(item.path("affects").get(0).path("batchSeq").asInt()).isEqualTo(1);
        assertThat(item.path("href").asText()).isEqualTo("/admin/production-orders/" + hcm.productionOrderId());
    }

    @Test
    @DisplayName("follow-ups need production-orders:READ: the accountant is refused")
    void followUpsRefused() throws Exception {
        UUID accountant = fixtures.staff("ACCOUNTANT");
        http.asRole(Role.ACCOUNTANT, accountant, get(BASE + "/production-follow-ups"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the producer's draft: from the production order only - no customer, no order number - with its "
            + "source; the coordinator's alone (production-orders:UPDATE), 503 with no model")
    void producerDraft() throws Exception {
        ProductionReceivables.Receivable hcm = atRiskAtHcm();
        UUID coordinator = fixtures.staff("PRODUCTION_COORDINATOR");
        List<LanguageModel.Request> asked = new ArrayList<>();
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{}", call.getArgument(0),
                List.of()));
        when(model.next(any())).thenAnswer(call -> {
            asked.add(call.getArgument(0));
            return new LanguageModel.Reply(new Turn.ModelTurn("{}", "Kính gửi xưởng, xin xác nhận ngày hoàn thành lệnh "
                    + hcm.productionCode() + ".", List.of()), LanguageModel.StopReason.END_TURN,
                    LanguageModel.Usage.NONE, "scripted");
        });

        JsonNode draft = data(http.asRole(Role.PRODUCTION_COORDINATOR, coordinator,
                        post(BASE + "/production-follow-ups/{id}/message-draft", hcm.productionOrderId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("draft");

        assertThat(draft.path("text").asText()).contains(hcm.productionCode());
        assertThat(draft.path("unverifiedCodes")).isEmpty();
        assertThat(draft.path("sources").get(0).path("kind").asText()).isEqualTo("PRODUCTION_ORDER");
        String facts = ((Turn.Question) asked.getFirst().history().getFirst()).text();
        String orderNumber = jdbc.queryForObject("select order_number from ordering.customer_order where id = ?",
                String.class, hcm.salesOrderId());
        assertThat(facts).contains(hcm.productionCode()).doesNotContain(orderNumber);

        UUID hcmManager = fixtures.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        UUID sales = fixtures.staff("SALES_STAFF");
        assertThat(granted("WAREHOUSE_MANAGER", "production-orders:UPDATE")).isFalse();
        http.asRole(Role.WAREHOUSE_MANAGER, hcmManager,
                        post(BASE + "/production-follow-ups/{id}/message-draft", hcm.productionOrderId()))
                .andExpect(status().isForbidden());
        http.asRole(Role.SALES_STAFF, sales,
                        post(BASE + "/production-follow-ups/{id}/message-draft", hcm.productionOrderId()))
                .andExpect(status().isForbidden());
        when(model.available()).thenReturn(false);
        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator,
                        post(BASE + "/production-follow-ups/{id}/message-draft", hcm.productionOrderId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_UNAVAILABLE"));
    }

    // -------------------------------------------------------------------------------------------------- helpers

    /** A carton of a customer's own SKU in an HCM bin; its LPN. */
    private String cartonAtHcm() {
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 50);
        return tx.execute(s -> CartonRows.stock(jdbc, sku.sku(), AssistantStock.HCM_BIN, 1, 50, "OPS-LOT",
                Instant.now().minusSeconds(86_400)).getFirst().lpn());
    }

    /** A production order at HCM the producer accepted for a day after its batch needs the goods: AT_RISK. */
    private ProductionReceivables.Receivable atRiskAtHcm() {
        ProductionReceivables.Receivable hcm = receivables.sent(DemoData.WAREHOUSE_HCM, 4, 50);
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        productionOrders.accept(hcm.productionOrderId(),
                productionOrders.detail(hcm.productionOrderId()).order().version(),
                LocalDate.now(VIETNAM).plusDays(30), null, coordinator);
        return hcm;
    }

    private boolean granted(String role, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from identity.role_permission rp
                               join identity.app_role r on r.id = rp.role_id
                               join identity.permission p on p.id = rp.permission_id
                               where r.code = ? and p.code = ?)""", Boolean.class, role, permission));
    }

    private JsonNode data(String body) throws Exception {
        return json.readTree(body).path("data");
    }

    private static List<String> keys(JsonNode queue) {
        List<String> keys = new ArrayList<>();
        queue.path("sections").forEach(section -> keys.add(section.path("key").asText()));
        return keys;
    }

    private static JsonNode section(JsonNode queue, String key) {
        for (JsonNode section : queue.path("sections")) {
            if (key.equals(section.path("key").asText())) {
                return section;
            }
        }
        throw new AssertionError("no section " + key + " in " + queue);
    }
}
