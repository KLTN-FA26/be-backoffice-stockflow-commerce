package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.domain.WatchRepository;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.RunAs;
import com.stockflow.notification.api.Inbox;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant's watch with the real security chain, grants, tools, inbox and database (design §9.1, ADR-0012): it
 * looks as each user, tells them once what is new on their own lists, never what their screens would not show them,
 * and stops for whoever may no longer be acted for.
 */
@SecuredIntegrationTest
class AssistantWatchIntegrationTest {

    private static final String LATE = AssistantWatch.OVERDUE_PURCHASE_ORDERS;

    // The two mocks every assistant integration test declares, so they all share one application context.
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired RunAs runAs;
    @Autowired AssistantTools tools;
    @Autowired ToolRunRecorder recorder;
    @Autowired WatchRepository watched;
    @Autowired Inbox inbox;
    @Autowired AssistantService assistant;
    @Autowired ConversationService conversations;
    @Autowired ToolAvailability availability;
    @Autowired AssistantProperties assistantProperties;
    @Autowired Messages messages;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("a purchase order gone late is news once, posted only to who may see its warehouse; the first look "
            + "is a baseline, and the reads keep no results")
    void aLateOrderIsNewsOnceInItsWarehouse() {
        UUID mine = tx.execute(s -> warehouse());
        UUID hcm = staff("WAREHOUSE_MANAGER", mine);
        UUID elsewhere = tx.execute(s -> warehouse());
        UUID other = staff("WAREHOUSE_MANAGER", elsewhere);
        AssistantWatch watch = watch(List.of(LATE), List.of(), Map.of(), 3);

        assertThat(watch.look(hcm, budget()).posted()).as("what is late already is not news").isZero();
        assertThat(watch.look(other, budget()).outcome()).isEqualTo(AssistantWatch.Outcome.LOOKED);
        String number = lateOrder(mine, 1);

        AssistantWatch.Look seen = watch.look(hcm, budget());
        assertThat(seen.posted()).isOne();
        assertThat(seen.newItems()).isOne();
        assertThat(bell(hcm)).singleElement().satisfies(message -> {
            assertThat(message.get("body")).as("the order's number, as it is written").isEqualTo(number);
            assertThat(message.get("title")).asString().startsWith("1 ");
            assertThat(message.get("category")).isEqualTo(AssistantWatch.NEWS);
        });
        assertThat(watch.look(other, budget()).posted()).as("another warehouse's order").isZero();
        assertThat(bell(other)).isEmpty();
        assertThat(watch.look(hcm, budget()).posted()).as("told once").isZero();
        assertThat(bell(hcm)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT result FROM assistant.tool_call WHERE user_id = ? AND channel = "
                        + "'PROACTIVE' AND conversation_id IS NULL", String.class, hcm))
                .isNotEmpty().allSatisfy(result -> assertThat(result).isEqualTo(ToolRunRecorder.RESULT_NOT_KEPT));
        assertThat(jdbc.queryForObject("SELECT created_by FROM assistant.watch_item WHERE owner_user_id = ? "
                + "AND item_key = ?", String.class, hcm, number)).as("the trail says who acted").endsWith(" via "
                + AssistantWatch.ACTOR);
    }

    @Test
    @DisplayName("a late order that is not the latest of many is still seen: the list is read whole, not the card's "
            + "ten most late")
    void aNewlyLateOrderAmongManyOlder() {
        UUID mine = tx.execute(s -> warehouse());
        UUID hcm = staff("WAREHOUSE_MANAGER", mine);
        AssistantWatch watch = watch(List.of(LATE), List.of(), Map.of(), 3);
        for (int i = 0; i < 10; i++) {
            lateOrder(mine, 400 + i);
        }
        watch.look(hcm, budget());
        String justLate = lateOrder(mine, 1);

        assertThat(watch.look(hcm, budget()).posted()).isOne();
        assertThat(bell(hcm)).singleElement()
                .satisfies(message -> assertThat(message.get("body")).isEqualTo(justLate));
    }

    @Test
    @DisplayName("a manager moved to another warehouse is not told that warehouse's old news; its new news, yes")
    void aChangedViewIsABaseline() {
        UUID elsewhere = tx.execute(s -> warehouse());
        lateOrder(elsewhere, 30);
        UUID manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        AssistantWatch watch = watch(List.of(LATE), List.of(), Map.of(), 3);
        watch.look(manager, budget());

        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.user_warehouse SET warehouse_id = ? "
                + "WHERE user_id = ?", elsewhere, manager));
        assertThat(watch.look(manager, budget()).posted()).as("late before the move").isZero();
        String late = lateOrder(elsewhere, 1);
        assertThat(watch.look(manager, budget()).posted()).isOne();
        assertThat(bell(manager)).singleElement().satisfies(message -> assertThat(message.get("body")).isEqualTo(late));
    }

    @Test
    @DisplayName("who may not be acted for is not looked at: a disabled account, one whose roles were taken away, a "
            + "role that may not use the assistant; and a list goes only to the roles named for it")
    void onlyWhoMayBeActedFor() {
        AssistantWatch watch = watch(List.of(LATE), List.of(), Map.of(), 3);
        UUID mine = tx.execute(s -> warehouse());
        UUID disabled = staff("WAREHOUSE_MANAGER", mine);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?",
                disabled));
        assertThat(watch.look(disabled, budget()).outcome()).isEqualTo(AssistantWatch.Outcome.NOT_ACTED_FOR);

        UUID revoked = staff("WAREHOUSE_MANAGER", mine);
        assertThat(watch.look(revoked, budget()).outcome()).isEqualTo(AssistantWatch.Outcome.LOOKED);
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM identity.user_role WHERE user_id = ?", revoked));
        lateOrder(mine, 1);
        assertThat(watch.look(revoked, budget()).outcome()).as("the role taken away applies at once")
                .isEqualTo(AssistantWatch.Outcome.NOT_ACTED_FOR);
        assertThat(bell(revoked)).isEmpty();

        UUID manager = staff("WAREHOUSE_MANAGER", mine);
        AssistantWatch buyersOnly = watch(List.of(LATE), List.of(), Map.of(LATE, List.of("PROCUREMENT_STAFF")), 3);
        watch.look(manager, budget());
        assertThat(state(manager)).isOne();
        lateOrder(mine, 1);
        assertThat(buyersOnly.look(manager, budget()).posted()).as("not a buyer's").isZero();
        assertThat(state(manager)).as("the list is no longer theirs: getting it back is a first look").isZero();

        String role = jdbc.query("""
                select r.code from identity.app_role r
                where r.code <> 'CUSTOMER' and not exists (
                    select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                    where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                order by r.code limit 1""", rs -> rs.next() ? rs.getString(1) : null);
        Assumptions.assumeTrue(role != null, "every seeded staff role may use the assistant");
        assertThat(watch.look(staff(role), budget()).outcome()).isEqualTo(AssistantWatch.Outcome.NO_ASSISTANT);
    }

    @Test
    @DisplayName("a late order it answers about opens a proactive conversation of the user's, offered that question's "
            + "tools; one message points to it; the watch's question is not the user's, their own follow-up is; once "
            + "per item, and no more than the daily cap")
    void answersInAConversationOfTheirs() throws Exception {
        UUID mine = tx.execute(s -> warehouse());
        UUID manager = staff("WAREHOUSE_MANAGER", mine);
        AssistantWatch watch = watch(List.of(LATE), List.of(LATE), Map.of(), 1);
        assertThat(watch.look(manager, budget()).answered()).isZero();
        String number = lateOrder(mine, 1);
        script("findPurchaseOrders", "{\"status\":\"CONFIRMED\",\"poNumber\":\"" + number
                + "\",\"supplier\":\"\",\"limit\":5}", "PO này đang trễ; bạn liên hệ nhà cung cấp.");
        long asked = asked(manager);

        AssistantWatch.Look look = watch.look(manager, budget());

        assertThat(look.answered()).isOne();
        UUID conversation = jdbc.queryForObject("""
                SELECT conversation_id FROM assistant.watch_item
                WHERE owner_user_id = ? AND section = ? AND item_key = ?""", UUID.class, manager, LATE, number);
        assertThat(jdbc.queryForMap("SELECT owner_user_id, origin FROM assistant.conversation WHERE id = ?",
                conversation)).containsEntry("owner_user_id", manager).containsEntry("origin", "PROACTIVE");
        assertThat(jdbc.queryForList("SELECT tool_name, outcome, channel FROM assistant.tool_call "
                + "WHERE conversation_id = ?", conversation)).singleElement().satisfies(run -> assertThat(run)
                .containsEntry("tool_name", "findPurchaseOrders").containsEntry("outcome", "OK")
                .containsEntry("channel", "PROACTIVE"));
        assertThat(bell(manager)).as("told once, by the answer").singleElement().satisfies(message -> {
            assertThat(message.get("category")).isEqualTo(AssistantWatch.ANSWER);
            assertThat(message.get("source_id")).isEqualTo(conversation.toString());
        });
        assertThat(asked(manager)).as("the watch's question is not the user's").isEqualTo(asked);

        script("findGoodsReceipts", "{\"status\":\"ANY\",\"receiptNumber\":\"\",\"limit\":5}",
                "Chưa có phiếu nhập nào cho PO này.");
        new SecuredMvc(mockMvc, decoder).asRole(Role.WAREHOUSE_MANAGER, manager,
                        post("/api/v1/assistant/conversations/" + conversation + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"Đã có phiếu nhập nào cho PO này chưa?\"}"))
                .andExpect(status().isOk());
        assertThat(asked(manager)).as("a follow-up the user asks is theirs").isEqualTo(asked + 1);
        assertThat(jdbc.queryForList("SELECT DISTINCT channel FROM assistant.tool_call WHERE conversation_id = ? "
                + "ORDER BY channel", String.class, conversation)).containsExactly("CHAT", "PROACTIVE");

        assertThat(watch.look(manager, budget()).answered()).as("once per item").isZero();
        lateOrder(mine, 1);
        AssistantWatch.Look capped = watch.look(manager, budget());
        assertThat(capped.newItems()).isOne();
        assertThat(capped.answered()).as("one proactive conversation a day").isZero();
        assertThat(capped.posted()).as("the news is still posted").isOne();
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private AssistantWatch watch(List<String> post, List<String> analyse, Map<String, List<String>> roles,
                                 int dailyAnalyses) {
        return new AssistantWatch(runAs, tools, recorder, watched, inbox, assistant, conversations, availability,
                new AssistantWatchProperties(true, post, analyse, roles, dailyAnalyses, 5, Duration.ofMinutes(30),
                        Duration.ofDays(4), List.of()),
                assistantProperties, messages, json, clock, transactions);
    }

    private AssistantWatch.Budget budget() {
        return new AssistantWatch.Budget(5, clock.instant().plus(Duration.ofHours(1)));
    }

    /** One tool call, then an answer: what the scripted model replies to the next question. */
    private void script(String tool, String arguments, String answer) {
        when(model.next(any())).thenReturn(
                new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null,
                        List.of(new ToolCall("toolu_" + tool, tool, arguments))),
                        LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted"),
                new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", answer, List.of()),
                        LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 0, 0), "scripted"));
    }

    private long asked(UUID user) {
        return conversations.questionsSince(user, BusinessDate.startOfDay(clock.instant()));
    }

    private List<Map<String, Object>> bell(UUID user) {
        return jdbc.queryForList("SELECT category, title, body, link, source_id, dedupe_key "
                + "FROM notification.user_notification WHERE recipient_id = ? AND category LIKE 'ASSISTANT_%' "
                + "ORDER BY created_at", user);
    }

    private int state(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM assistant.watch_state WHERE owner_user_id = ?",
                Integer.class, user);
    }

    /** A purchase order sent to its supplier, expected {@code daysLate} days ago. */
    private String lateOrder(UUID warehouse, int daysLate) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        String number = "PO-WT-" + po.toString().substring(24).toUpperCase();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, expected_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at,
                        confirmed_by, active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE - ? - 30, CURRENT_DATE - ?, NOW(), ?, NOW(), ?,
                           NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, number, warehouse, daysLate, daysLate, submitter, approver, approver, revision);
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
        String prefix = "WT" + id.toString().substring(30).toUpperCase();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Watch fixture', 'ACTIVE', ?, 'Test road', 'M', 50, 50, 0, NOW())""", id, prefix);
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
}
