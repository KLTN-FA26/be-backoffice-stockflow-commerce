package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.service.AssistantActions;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant's suggestions turned into documents (ADR-0011), with the real security chain, grants and procurement:
 * only the owner decides, each confirmation carries the guard of the document's own screen, a document is created
 * once, and what procurement refuses leaves the suggestion open.
 */
@SecuredIntegrationTest
class AssistantActionIntegrationTest {

    private static final String ACTIONS = "/api/v1/assistant/actions";
    private static final String CONVERSATIONS = "/api/v1/assistant/conversations";

    @Autowired MockMvc mockMvc;
    // The two mocks every assistant integration test declares, so they all share one application context.
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired AssistantActions actions;
    @Autowired IdentityService identity;

    private SecuredMvc http;

    /** An active supplier's catalog row: what a proposal of proposeReorder names. */
    private record Offer(String supplierCode, String supplierName, String sku, int quantity) {
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("each confirmation carries exactly the guard of the screen that creates its document")
    void mirrorsTheDocumentScreens() {
        assertThat(describe(guard(RequestMethod.POST, ACTIONS + "/{actionId}/purchase-order")))
                .isEqualTo(describe(guard(RequestMethod.POST, "/api/v1/purchase-orders")));
        assertThat(describe(guard(RequestMethod.POST, ACTIONS + "/{actionId}/goods-receipt")))
                .isEqualTo(describe(guard(RequestMethod.POST, "/api/v1/goods-receipts")));
    }

    @Test
    @DisplayName("its owner confirms a suggested purchase order, a quantity changed: one DRAFT order of the supplier at "
            + "its catalog price; confirming again returns that order, and the suggestion reads CONFIRMED")
    void confirmsAPurchaseOrderOnce() throws Exception {
        Offer offer = offer();
        UUID owner = staff("SYSTEM_ADMIN");
        UUID action = purchaseOrderSuggestion(owner, offer, Instant.now().plus(Duration.ofHours(23)));
        int ordered = offer.quantity() * 2;

        String body = http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(action, offer.sku(), ordered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.submitted.lines[0].quantity").value(ordered))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String number = JsonPath.read(body, "$.data.resultNumber");
        Map<String, Object> order = jdbc.queryForMap("""
                SELECT o.status, s.code AS supplier, o.warehouse_id, l.ordered_qty, l.unit_price, si.current_price
                FROM procurement.purchase_orders o
                JOIN procurement.suppliers s ON s.id = o.supplier_id
                JOIN procurement.purchase_order_lines l ON l.po_id = o.id
                JOIN procurement.supplier_items si ON si.supplier_id = s.id AND si.inventory_item_id = l.inventory_item_id
                WHERE o.po_number = ?""", number);
        assertThat(order).containsEntry("status", "DRAFT").containsEntry("supplier", offer.supplierCode())
                .containsEntry("warehouse_id", WAREHOUSE_HCM);
        assertThat(((Number) order.get("ordered_qty")).intValue()).isEqualTo(ordered);
        assertThat(((java.math.BigDecimal) order.get("unit_price")).compareTo(
                (java.math.BigDecimal) order.get("current_price"))).as("the catalog price").isZero();

        http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(action, offer.sku(), ordered + 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultNumber").value(number));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.purchase_orders WHERE po_number = ?",
                Integer.class, number)).isOne();
        http.asRole(Role.SYSTEM_ADMIN, owner, get(ACTIONS + "?open=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + action + "')]").isEmpty());
        http.asRole(Role.SYSTEM_ADMIN, owner, post(ACTIONS + "/" + action + "/dismissal"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_ACTION_CLOSED"));
    }

    @Test
    @DisplayName("another user's suggestion is not found, whatever that user may do on the purchase-order screen")
    void onlyItsOwner() throws Exception {
        Offer offer = offer();
        UUID action = purchaseOrderSuggestion(staff("SYSTEM_ADMIN"), offer, Instant.now().plus(Duration.ofHours(2)));
        UUID stranger = staff("SYSTEM_ADMIN");

        http.asRole(Role.SYSTEM_ADMIN, stranger, get(ACTIONS + "/" + action)).andExpect(status().isNotFound());
        http.asRole(Role.SYSTEM_ADMIN, stranger, confirmOrder(action, offer.sku(), offer.quantity()))
                .andExpect(status().isNotFound());
        http.asRole(Role.SYSTEM_ADMIN, stranger, post(ACTIONS + "/" + action + "/dismissal"))
                .andExpect(status().isNotFound());
        assertThat(storedStatus(action)).isEqualTo("SUGGESTED");
    }

    @Test
    @DisplayName("a SKU that was not suggested, or a quantity procurement refuses, is refused and the suggestion stays "
            + "open; a role that may not create purchase orders is refused by the screen's guard")
    void refusalsLeaveItOpen() throws Exception {
        Offer offer = offer();
        UUID owner = staff("SYSTEM_ADMIN");
        UUID action = purchaseOrderSuggestion(owner, offer, Instant.now().plus(Duration.ofHours(2)));

        http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(action, "NOT-SUGGESTED", 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        if (offer.quantity() > 1) {
            http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(action, offer.sku(), offer.quantity() - 1))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(storedStatus(action)).isEqualTo("SUGGESTED");

        String role = jdbc.queryForObject("""
                select r.code from identity.app_role r
                where exists (select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                              where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                  and not exists (select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                                  where rp.role_id = r.id and p.code = 'procurement-purchase-orders:CREATE')
                  and r.code <> 'CUSTOMER'
                order by r.code limit 1""", String.class);
        UUID clerk = staff(role);
        UUID theirs = purchaseOrderSuggestion(clerk, offer, Instant.now().plus(Duration.ofHours(2)));
        http.asRole(Role.valueOf(role), clerk, confirmOrder(theirs, offer.sku(), offer.quantity()))
                .andExpect(status().isForbidden());
        http.asRole(Role.valueOf(role), clerk, post(ACTIONS + "/" + theirs + "/dismissal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISMISSED"));

        // The warehouse rule is the purchase-order screen's own: a role of WAREHOUSE scope may order only into its
        // warehouses. No seeded role has that scope and the screen's CREATE (a buyer's scope is the company), so a
        // warehouse manager is given it for this check, through identity as an administrator would (its version
        // moves, and the cached grants with it).
        List<String> managerGrants = granted(identity.roleMatrix("WAREHOUSE_MANAGER"));
        List<String> withOrders = new ArrayList<>(managerGrants);
        withOrders.add("procurement-purchase-orders:CREATE");
        identity.updateRolePermissions(new UpdateRolePermissionsCommand("WAREHOUSE_MANAGER",
                identity.roleMatrix("WAREHOUSE_MANAGER").version(), withOrders));
        try {
            UUID elsewhere = tx.execute(s -> warehouse());
            UUID manager = staff("WAREHOUSE_MANAGER", elsewhere);
            UUID limited = purchaseOrderSuggestion(manager, offer, Instant.now().plus(Duration.ofHours(2)));
            http.asRole(Role.WAREHOUSE_MANAGER, manager, confirmOrder(limited, offer.sku(), offer.quantity()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
            assertThat(storedStatus(limited)).isEqualTo("SUGGESTED");
        } finally {
            identity.updateRolePermissions(new UpdateRolePermissionsCommand("WAREHOUSE_MANAGER",
                    identity.roleMatrix("WAREHOUSE_MANAGER").version(), managerGrants));
        }
    }

    private static List<String> granted(RoleMatrixView matrix) {
        List<String> codes = new ArrayList<>();
        matrix.groups().forEach(group -> group.resources().forEach(resource -> resource.actions().stream()
                .filter(RoleMatrixView.ActionChip::granted)
                .forEach(chip -> codes.add(resource.code() + ":" + chip.action().name()))));
        return codes;
    }

    @Test
    @DisplayName("a suggestion more than a day old reads EXPIRED and cannot be confirmed; dismissed, it is closed")
    void expiredAndDismissed() throws Exception {
        Offer offer = offer();
        UUID owner = staff("SYSTEM_ADMIN");
        UUID action = purchaseOrderSuggestion(owner, offer, Instant.now().minus(Duration.ofMinutes(1)));

        http.asRole(Role.SYSTEM_ADMIN, owner, get(ACTIONS + "/" + action))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));
        http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(action, offer.sku(), offer.quantity()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_ACTION_EXPIRED"));
        http.asRole(Role.SYSTEM_ADMIN, owner, post(ACTIONS + "/" + action + "/dismissal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISMISSED"));
        http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(action, offer.sku(), offer.quantity()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_ACTION_CLOSED"));
    }

    @Test
    @DisplayName("a delivery note's draft, confirmed by a manager of the order's warehouse, becomes a DRAFT receipt "
            + "with its count; one of another warehouse is told the order does not exist")
    void confirmsAGoodsReceipt() throws Exception {
        String number = confirmedOrder();
        UUID manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        UUID action = receiptSuggestion(manager, number);
        UUID elsewhere = tx.execute(s -> warehouse());
        UUID stranger = staff("WAREHOUSE_MANAGER", elsewhere);
        UUID theirs = receiptSuggestion(stranger, number);

        http.asRole(Role.WAREHOUSE_MANAGER, stranger, confirmReceipt(theirs, 1, 5, true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        http.asRole(Role.WAREHOUSE_MANAGER, manager, confirmReceipt(action, 7, 5, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        // The receipt screen's own rule: the SKU is lot- and expiry-tracked (BR-03). Refused, it stays open.
        Timestamp before = Timestamp.from(Instant.now().minusSeconds(1));
        http.asRole(Role.WAREHOUSE_MANAGER, manager, confirmReceipt(action, 1, 5, false))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_LOT_DATA_INVALID"));
        assertThat(storedStatus(action)).isEqualTo("SUGGESTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.goods_receipts r JOIN procurement.purchase_orders o ON o.id = r.po_id
                WHERE o.po_number = ?""", Integer.class, number)).as("the refused count leaves no receipt").isZero();
        // The receipt was created and its lines refused in one transaction: its creation is audited as rolled back,
        // never as a success naming a receipt that does not exist.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log
                WHERE resource_type = 'goods-receipt' AND action = 'CREATE' AND outcome = 'SUCCESS'
                  AND occurred_at >= ? AND resource_id NOT IN (SELECT id::text FROM procurement.goods_receipts)""",
                Integer.class, before)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log
                WHERE resource_type = 'goods-receipt' AND action = 'CREATE' AND outcome = 'FAILURE'
                  AND occurred_at >= ? AND details LIKE '%RolledBack%'""", Integer.class, before)).isOne();
        String body = http.asRole(Role.WAREHOUSE_MANAGER, manager, confirmReceipt(action, 1, 5, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> receipt = jdbc.queryForMap("""
                SELECT r.status, sum(l.received_qty) AS counted
                FROM procurement.goods_receipts r JOIN procurement.goods_receipt_lines l ON l.receipt_id = r.id
                WHERE r.receipt_number = ? GROUP BY r.status""", (String) JsonPath.read(body, "$.data.resultNumber"));
        assertThat(receipt).containsEntry("status", "DRAFT");
        assertThat(((Number) receipt.get("counted")).intValue()).isEqualTo(5);
        assertThat((Boolean) JsonPath.read(body, "$.data.possibleDuplicate")).isFalse();

        // A second reading of the same order (a retake, or a second truck) supersedes nothing; confirming it too
        // creates a second draft receipt, and says so.
        UUID again = receiptSuggestion(manager, number);
        assertThat(storedStatus(action)).isEqualTo("CONFIRMED");
        http.asRole(Role.WAREHOUSE_MANAGER, manager, confirmReceipt(again, 1, 5, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.possibleDuplicate").value(true));
    }

    @Test
    @DisplayName("an answer that proposes a purchase order is stored with its suggestion; asked again, the new one "
            + "supersedes the open one; confirmed, the transcript names the order; the conversation's deletion takes "
            + "its suggestions with it")
    void anAnswerSuggestsWhatItProposes() throws Exception {
        UUID owner = staff("SYSTEM_ADMIN");
        Map<String, Object> policy = jdbc.queryForMap(
                "SELECT reorder_point, safety_stock FROM inventory.inventory_items WHERE sku = 'TABLE-OAK-160'");
        // Far below its target, so proposeReorder proposes an order of it whatever the seed's stock and sales.
        // In a transaction: Hikari runs with auto-commit off, and a bare write is rolled back on release.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.inventory_items SET reorder_point = 5000, "
                + "safety_stock = 5000 WHERE sku = 'TABLE-OAK-160'"));
        try {
            String conversation = start(owner);
            String first = askForReorder(owner, conversation);
            // An open suggestion of the same supplier for a SKU the next answer does not order: another need.
            String key = jdbc.queryForObject("SELECT proposal_key FROM assistant.action WHERE id = ?::uuid",
                    String.class, first);
            UUID otherNeed = Identifiers.newId();
            insert(otherNeed, owner, "PURCHASE_ORDER_DRAFT", key, """
                    {"type":"PURCHASE_ORDER_DRAFT","supplierCode":"X","supplierName":"X",\
                    "generatedAt":"2026-11-04T01:00:00Z","lines":[{"sku":"SOME-OTHER-SKU","quantity":5}]}""",
                    Instant.now().plus(Duration.ofHours(2)));
            String second = askForReorder(owner, conversation);
            assertThat(storedStatus(UUID.fromString(first))).isEqualTo("SUPERSEDED");
            assertThat(storedStatus(UUID.fromString(second))).isEqualTo("SUGGESTED");
            assertThat(storedStatus(otherNeed)).as("it orders what the new one does not").isEqualTo("SUGGESTED");
            assertThat(jdbc.queryForObject("SELECT last_modified_by FROM assistant.action WHERE id = ?::uuid",
                    String.class, first)).as("superseded by its owner's answer").isNotNull();

            Map<String, Object> proposal = jdbc.queryForMap("""
                    SELECT suggested -> 'lines' -> 0 ->> 'sku' AS sku,
                           (suggested -> 'lines' -> 0 ->> 'quantity')::int AS qty
                    FROM assistant.action WHERE id = ?::uuid""", second);
            String sku = (String) proposal.get("sku");
            int quantity = ((Number) proposal.get("qty")).intValue();
            String body = http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(UUID.fromString(second), sku, quantity))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String number = JsonPath.read(body, "$.data.resultNumber");
            http.asRole(Role.SYSTEM_ADMIN, owner, confirmOrder(UUID.fromString(first), sku, quantity))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("ASSISTANT_ACTION_CLOSED"));

            String transcript = http.asRole(Role.SYSTEM_ADMIN, owner,
                            get(CONVERSATIONS + "/" + conversation + "/messages"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Map<String, Object>> shown = JsonPath.read(transcript, "$.data[*].proposals[*]");
            assertThat(shown).extracting(p -> p.get("actionId"), p -> p.get("actionStatus"),
                            p -> p.get("resultNumber"))
                    .containsExactly(tuple(first, "SUPERSEDED", null), tuple(second, "CONFIRMED", number));

            tx.executeWithoutResult(s -> jdbc.update("DELETE FROM assistant.conversation WHERE id = ?::uuid",
                    conversation));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant.action WHERE id IN (?::uuid, ?::uuid)",
                    Integer.class, first, second)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.purchase_orders WHERE po_number = ?",
                    Integer.class, number)).as("the order outlives its suggestion").isOne();
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.inventory_items SET reorder_point = ?, "
                    + "safety_stock = ? WHERE sku = 'TABLE-OAK-160'", policy.get("reorder_point"),
                    policy.get("safety_stock")));
        }
    }

    @Test
    @DisplayName("two confirmations of one suggestion at the same moment create one purchase order, which both name")
    void twoConfirmationsAtOnce() throws Exception {
        Offer offer = offer();
        UUID owner = staff("SYSTEM_ADMIN");
        UUID action = purchaseOrderSuggestion(owner, offer, Instant.now().plus(Duration.ofHours(2)));
        String authorization = http.token(Role.SYSTEM_ADMIN, owner);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(confirmOrder(action, offer.sku(), offer.quantity())
                                    .header("Authorization", authorization))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                }));
            }
            go.countDown();
            Set<String> numbers = new HashSet<>();
            for (Future<String> answer : both) {
                numbers.add(JsonPath.read(answer.get(60, TimeUnit.SECONDS), "$.data.resultNumber"));
            }
            assertThat(numbers).hasSize(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM procurement.purchase_orders
                    WHERE id = (SELECT purchase_order_id FROM assistant.action WHERE id = ?)""", Integer.class,
                    action)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a delivery note's reading older than the retention period is deleted, a recent one kept")
    void readingsAreSweptWithTheConversations() {
        UUID owner = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        UUID old = receiptSuggestion(owner, "PO-RETENTION-OLD");
        UUID recent = receiptSuggestion(owner, "PO-RETENTION-NEW");
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE assistant.action SET created_at = NOW() - INTERVAL '100 days' WHERE id = ?", old));

        actions.deleteStandaloneBefore(Instant.now().minus(Duration.ofDays(90)), 1000);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant.action WHERE id = ?", Integer.class, old))
                .isZero();
        assertThat(storedStatus(recent)).isEqualTo("SUGGESTED");
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    /** Asks for a reorder of TABLE-OAK-160 with the model scripted; the answer's suggestion, as stored. */
    private String askForReorder(UUID owner, String conversation) throws Exception {
        when(model.next(any())).thenReturn(
                new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null,
                        List.of(new ToolCall("toolu_reorder", "proposeReorder",
                                "{\"skus\":[\"TABLE-OAK-160\"],\"salesDays\":30,\"coverDays\":30}"))),
                        LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted"),
                new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}",
                        "Mình đề xuất một PO nháp cho nhà cung cấp của mã này; bạn kiểm rồi xác nhận.", List.of()),
                        LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 0, 0), "scripted"));
        String body = http.asRole(Role.SYSTEM_ADMIN, owner, post(CONVERSATIONS + "/" + conversation + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Nên nhập thêm bàn sồi không?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposals[0].actionStatus").value("SUGGESTED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String action = JsonPath.read(body, "$.data.proposals[0].actionId");
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT owner_user_id, message_id, conversation_id FROM assistant.action WHERE id = ?::uuid", action);
        assertThat(stored).containsEntry("owner_user_id", owner)
                .containsEntry("message_id", UUID.fromString(JsonPath.read(body, "$.data.messageId")))
                .containsEntry("conversation_id", UUID.fromString(conversation));
        return action;
    }

    private String start(UUID owner) throws Exception {
        String body = http.asRole(Role.SYSTEM_ADMIN, owner, post(CONVERSATIONS)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.id");
    }

    private Offer offer() {
        return jdbc.queryForObject("""
                SELECT s.code, s.name, ii.sku, si.moq, si.pack_size
                FROM procurement.supplier_items si
                JOIN procurement.suppliers s ON s.id = si.supplier_id
                JOIN inventory.inventory_items ii ON ii.id = si.inventory_item_id
                WHERE s.status = 'ACTIVE' AND ii.sku = 'TABLE-OAK-160'
                ORDER BY s.code LIMIT 1""", (rs, row) -> {
            int moq = rs.getInt("moq");
            int pack = rs.getInt("pack_size");
            int quantity = (moq + pack - 1) / pack * pack;
            return new Offer(rs.getString("code"), rs.getString("name"), rs.getString("sku"), quantity);
        });
    }

    private UUID purchaseOrderSuggestion(UUID owner, Offer offer, Instant expiresAt) {
        UUID id = Identifiers.newId();
        String suggested = """
                {"type":"PURCHASE_ORDER_DRAFT","supplierCode":"%s","supplierName":"%s",\
                "generatedAt":"2026-11-04T01:00:00Z","lines":[{"sku":"%s","quantity":%d}]}"""
                .formatted(offer.supplierCode(), offer.supplierName(), offer.sku(), offer.quantity());
        insert(id, owner, "PURCHASE_ORDER_DRAFT", "PURCHASE_ORDER_DRAFT:" + offer.supplierCode(), suggested,
                expiresAt);
        return id;
    }

    private UUID receiptSuggestion(UUID owner, String poNumber) {
        UUID id = Identifiers.newId();
        String suggested = """
                {"poNumber":"%s","supplierName":"Xưởng gỗ Việt","lines":[{"poLineNo":1,"sku":"TABLE-OAK-160",\
                "ordered":20,"outstanding":20,"proposedQuantity":5,"lotNumber":null,"expiryDate":null,\
                "readText":"Bàn sồi 160 x5","warnings":[]}],"unmatched":[],"warnings":[]}""".formatted(poNumber);
        insert(id, owner, "GOODS_RECEIPT_DRAFT", "GOODS_RECEIPT_DRAFT:" + poNumber, suggested,
                Instant.now().plus(Duration.ofHours(23)));
        return id;
    }

    private void insert(UUID id, UUID owner, String kind, String key, String suggested, Instant expiresAt) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO assistant.action (id, owner_user_id, kind, proposal_key, suggested, status, expires_at,
                                              created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'SUGGESTED', ?, ?)""", id, owner, kind, key, suggested,
                Timestamp.from(expiresAt), Timestamp.from(expiresAt.minus(Duration.ofHours(24)))));
    }

    private String storedStatus(UUID action) {
        return jdbc.queryForObject("SELECT status FROM assistant.action WHERE id = ?", String.class, action);
    }

    private static MockHttpServletRequestBuilder confirmOrder(UUID action, String sku, int quantity) {
        return post(ACTIONS + "/" + action + "/purchase-order").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"warehouseId":"%s","lines":[{"sku":"%s","quantity":%d}]}""".formatted(WAREHOUSE_HCM, sku,
                        quantity));
    }

    /** @param lot with the lot and expiry date the SKU is tracked by (BR-03); without, the receipt screen refuses */
    private static MockHttpServletRequestBuilder confirmReceipt(UUID action, int line, int quantity, boolean lot) {
        String tracked = lot ? ",\"lotNumber\":\"LOT-AC-1\",\"expiryDate\":\"" + LocalDate.now().plusYears(2) + "\""
                : "";
        return post(ACTIONS + "/" + action + "/goods-receipt").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deliveryNote":"PGH-1","locationCode":"HCM-RCV01",\
                        "lines":[{"poLineNo":%d,"quantity":%d%s}]}""".formatted(line, quantity, tracked));
    }

    private String confirmedOrder() {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        String number = "PO-AC-" + po.toString().substring(24).toUpperCase();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, number, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, 20, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = 'TABLE-OAK-160'""",
                    Identifiers.newId(), po, revision);
        });
        return number;
    }

    private UUID warehouse() {
        UUID id = Identifiers.newId();
        String prefix = "AC" + id.toString().substring(30).toUpperCase();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Action fixture', 'ACTIVE', ?, 'Test road', 'M', 50, 50, 0, NOW())""", id, prefix);
        return id;
    }

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    private static List<Object> describe(RequiresPermission guard) {
        return List.of(guard.resource(), guard.action(), guard.scope());
    }

    private RequiresPermission guard(RequestMethod method, String path) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getMethodsCondition().getMethods().contains(method)
                    && info.getPathPatternsCondition() != null
                    && info.getPathPatternsCondition().getPatternValues().contains(path)) {
                RequiresPermission guard = AnnotatedElementUtils.findMergedAnnotation(entry.getValue().getMethod(),
                        RequiresPermission.class);
                assertThat(guard).as("%s %s is guarded", method, path).isNotNull();
                return guard;
            }
        }
        throw new AssertionError("no handler for " + method + " " + path);
    }
}
