package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.domain.WatchRepository;
import com.stockflow.common.i18n.Messages;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.stockflow.support.DemoData.CUSTOMER_ID;
import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant's watch with the real security chain, grants, tools, inbox and database (design §9.1, ADR-0013): it
 * looks as each user, tells them once what is new on their own list - the orders put on hold, the one list watched
 * since the rescope (ADR-0010) - and stops for whoever may no longer be acted for.
 *
 * <p>Its own application context, and so its own database ({@code PostgresContainer}): the held orders are read
 * company-wide, and a database other tests filled with more than one read of them would make every look a partial
 * one, which posts nothing.</p>
 */
@SecuredIntegrationTest
@TestPropertySource(properties = "stockflow.assistant.watch.enabled=false")
class AssistantWatchIntegrationTest {

    private static final String HELD = AssistantWatch.HELD_ORDERS;

    // The two mocks every assistant integration test declares.
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
    @DisplayName("an order put on hold is news once, with its reason; the first look is a baseline, and the reads keep "
            + "no results")
    void aHeldOrderIsNewsOnce() {
        UUID sales = staff("SALES_STAFF");
        AssistantWatch watch = watch(List.of(HELD), List.of(), Map.of(), 3);

        assertThat(watch.look(sales, budget()).posted()).as("what is held already is not news").isZero();
        String number = heldOrder("PAYMENT");

        AssistantWatch.Look seen = watch.look(sales, budget());
        assertThat(seen.posted()).isOne();
        assertThat(seen.newItems()).isOne();
        assertThat(bell(sales)).singleElement().satisfies(message -> {
            assertThat(message.get("body")).as("the order's number and why it is held").isEqualTo(number
                    + " (PAYMENT)");
            assertThat(message.get("title")).asString().startsWith("1 ");
            assertThat(message.get("category")).isEqualTo(AssistantWatch.NEWS);
        });
        assertThat(watch.look(sales, budget()).posted()).as("told once").isZero();
        assertThat(bell(sales)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT result FROM assistant.tool_call WHERE user_id = ? AND channel = "
                        + "'PROACTIVE' AND conversation_id IS NULL", String.class, sales))
                .isNotEmpty().allSatisfy(result -> assertThat(result).isEqualTo(ToolRunRecorder.RESULT_NOT_KEPT));
        assertThat(jdbc.queryForObject("SELECT created_by FROM assistant.watch_item WHERE owner_user_id = ? "
                + "AND item_key = ?", String.class, sales, number + " (PAYMENT)")).as("the trail says who acted")
                .endsWith(" via " + AssistantWatch.ACTOR);
    }

    @Test
    @DisplayName("an order held again for another reason is held anew: news again")
    void heldAgainForAnotherReason() {
        UUID sales = staff("SALES_STAFF");
        AssistantWatch watch = watch(List.of(HELD), List.of(), Map.of(), 3);
        String number = heldOrder("ADDRESS");
        watch.look(sales, budget());

        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET hold_reason = 'CUSTOMER' "
                + "WHERE order_number = ?", number));

        assertThat(watch.look(sales, budget()).posted()).isOne();
        assertThat(bell(sales)).singleElement()
                .satisfies(message -> assertThat(message.get("body")).isEqualTo(number + " (CUSTOMER)"));
    }

    @Test
    @DisplayName("who may not be acted for is not looked at: a disabled account, one whose roles were taken away, a "
            + "role that may not use the assistant; and a list goes only to the roles named for it")
    void onlyWhoMayBeActedFor() {
        AssistantWatch watch = watch(List.of(HELD), List.of(), Map.of(), 3);
        UUID disabled = staff("SALES_STAFF");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?",
                disabled));
        assertThat(watch.look(disabled, budget()).outcome()).isEqualTo(AssistantWatch.Outcome.NOT_ACTED_FOR);

        UUID revoked = staff("SALES_STAFF");
        assertThat(watch.look(revoked, budget()).outcome()).isEqualTo(AssistantWatch.Outcome.LOOKED);
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM identity.user_role WHERE user_id = ?", revoked));
        heldOrder("FRAUD");
        assertThat(watch.look(revoked, budget()).outcome()).as("the role taken away applies at once")
                .isEqualTo(AssistantWatch.Outcome.NOT_ACTED_FOR);
        assertThat(bell(revoked)).isEmpty();

        UUID sales = staff("SALES_STAFF");
        AssistantWatch accountantsOnly = watch(List.of(HELD), List.of(), Map.of(HELD, List.of("ACCOUNTANT")), 3);
        watch.look(sales, budget());
        assertThat(state(sales)).isOne();
        heldOrder("OTHER");
        assertThat(accountantsOnly.look(sales, budget()).posted()).as("not an accountant's").isZero();
        assertThat(state(sales)).as("the list is no longer theirs: getting it back is a first look").isZero();

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
    @DisplayName("a held order it answers about opens a proactive conversation of the user's, offered that question's "
            + "tools; one message points to it; the watch's question is not the user's, their own follow-up is; once "
            + "per item, and no more than the daily cap")
    void answersInAConversationOfTheirs() throws Exception {
        UUID sales = staff("SALES_STAFF");
        AssistantWatch watch = watch(List.of(HELD), List.of(HELD), Map.of(), 1);
        assertThat(watch.look(sales, budget()).answered()).isZero();
        String number = heldOrder("PAYMENT");
        script("findOrder", "{\"orderNumber\":\"" + number + "\"}", "Đơn đang chờ làm rõ thanh toán.");
        long asked = asked(sales);

        AssistantWatch.Look look = watch.look(sales, budget());

        assertThat(look.answered()).isOne();
        UUID conversation = jdbc.queryForObject("""
                SELECT conversation_id FROM assistant.watch_item
                WHERE owner_user_id = ? AND section = ? AND item_key = ?""", UUID.class, sales, HELD,
                number + " (PAYMENT)");
        assertThat(jdbc.queryForMap("SELECT owner_user_id, origin FROM assistant.conversation WHERE id = ?",
                conversation)).containsEntry("owner_user_id", sales).containsEntry("origin", "PROACTIVE");
        assertThat(jdbc.queryForList("SELECT tool_name, outcome, channel FROM assistant.tool_call "
                + "WHERE conversation_id = ?", conversation)).singleElement().satisfies(run -> assertThat(run)
                .containsEntry("tool_name", "findOrder").containsEntry("outcome", "OK")
                .containsEntry("channel", "PROACTIVE"));
        assertThat(bell(sales)).as("told once, by the answer").singleElement().satisfies(message -> {
            assertThat(message.get("category")).isEqualTo(AssistantWatch.ANSWER);
            assertThat(message.get("source_id")).isEqualTo(conversation.toString());
        });
        assertThat(asked(sales)).as("the watch's question is not the user's").isEqualTo(asked);

        script("findOrders", "{\"status\":\"ON_HOLD\",\"holdReason\":\"PAYMENT\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"customer\":\"\",\"limit\":10}", "Còn một đơn chờ làm rõ thanh toán.");
        new SecuredMvc(mockMvc, decoder).asRole(Role.SALES_STAFF, sales,
                        post("/api/v1/assistant/conversations/" + conversation + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"Còn đơn nào chờ làm rõ thanh toán nữa không?\"}"))
                .andExpect(status().isOk());
        assertThat(asked(sales)).as("a follow-up the user asks is theirs").isEqualTo(asked + 1);
        assertThat(jdbc.queryForList("SELECT DISTINCT channel FROM assistant.tool_call WHERE conversation_id = ? "
                + "ORDER BY channel", String.class, conversation)).containsExactly("CHAT", "PROACTIVE");

        assertThat(watch.look(sales, budget()).answered()).as("once per item").isZero();
        heldOrder("ADDRESS");
        AssistantWatch.Look capped = watch.look(sales, budget());
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

    /**
     * An order of the demo customer put on hold for {@code reason}, placed in the 2190s so that it is among the first
     * the newest-first list reads.
     */
    private String heldOrder(String reason) {
        UUID releasedBy = tx.execute(s -> ReferenceRows.user(entityManager));
        String number = "SO-2190%04d-%06d".formatted(ThreadLocalRandom.current().nextInt(101, 1231),
                ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> {
            UUID order = ReferenceRows.orderWithLine(entityManager, "TABLE-OAK-160", "CONFIRMED", CUSTOMER_ID,
                    releasedBy, WAREHOUSE_HCM);
            entityManager.flush();
            jdbc.update("""
                    UPDATE ordering.customer_order SET order_number = ?, status = 'ON_HOLD', hold_reason = ?,
                        status_before_hold = 'CONFIRMED', contract_signed_at = NOW(),
                        placed_at = NOW() + INTERVAL '165 years'
                    WHERE id = ?""", number, reason, order);
        });
        return number;
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
