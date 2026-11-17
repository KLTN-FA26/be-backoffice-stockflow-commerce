package com.stockflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.BusinessHours;
import com.stockflow.chat.internal.service.ChatLifecycle;
import com.stockflow.chat.internal.service.ChatProperties;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Package 3's operations of the chat over HTTP with the real security chain and database (kltn-docs 16 §4.1, §5, BR-07,
 * BR-08, BR-09): routing to the customer's last agent, the first-response alert once, auto-close and the reopen window,
 * masking before storing, the edit window, saved replies with variables, the staff's search, the offline customer's
 * notice and the badges; internal notes reach none of the customer's answers (TC-F13-03).
 */
@SecuredIntegrationTest
class ChatOperationsIntegrationTest {

    private static final String MINE = "/api/v1/chat/me/conversations";
    private static final String STAFF = "/api/v1/chat/conversations";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired ChatLifecycle lifecycle;
    @Autowired ChatProperties properties;

    private SecuredMvc http;
    private StaffFixtures people;
    private Portal alice;
    private UUID sales;
    private UUID otherSales;
    private UUID manager;

    private record Portal(UUID customer, UUID account) {
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        people = new StaffFixtures(jdbc, tx, entityManager);
        alice = portal();
        sales = people.staff("SALES_STAFF");
        otherSales = people.staff("SALES_STAFF");
        manager = people.staff("SALES_MANAGER");
    }

    @Test
    @DisplayName("16 §4.1 step 2: no document owner - the Sales who served the customer last; nobody - the queue")
    void routesToTheLastAgentThenTheQueue() throws Exception {
        UUID first = open("Lan dau");
        assertThat(statusOf(first)).as("nobody served her yet: the queue").isEqualTo("QUEUED");
        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + first + "/claim")).andExpect(status().isOk());
        reply(otherSales, first, "Chao chi").andExpect(status().isCreated());
        move(otherSales, first, "RESOLVED");

        UUID second = open("Lan hai");
        assertThat(statusOf(second)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT assigned_agent_id FROM chat.conversation WHERE id = ?", UUID.class,
                second)).as("the agent who looked after her last").isEqualTo(otherSales);

        reply(otherSales, second, "Em day").andExpect(status().isCreated());
        move(otherSales, second, "RESOLVED");
        people.setStatus(otherSales, "DISABLED");
        UUID third = open("Lan ba");
        assertThat(statusOf(third)).as("one who no longer answers customers is skipped").isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("BR-09: a late first answer is logged and told to the supervisors once; an answered one never")
    void firstResponseOverdueOnce() throws Exception {
        UUID late = open("Co ai khong?");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET first_response_due_at = NOW() -"
                + " INTERVAL '1 minute' WHERE id = ?", late));
        long before = people.messages(manager);
        assertThat(lifecycle.overdueFirstResponses()).contains(late);
        assertThat(lifecycle.alertFirstResponse(late)).isTrue();
        assertThat(lifecycle.alertFirstResponse(late)).isFalse();
        assertThat(lifecycle.overdueFirstResponses()).doesNotContain(late);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_event WHERE conversation_id = ? AND"
                + " type = 'FIRST_RESPONSE_OVERDUE'", Long.class, late)).isEqualTo(1L);
        assertThat(people.messages(manager)).as("the supervisor's inbox, once").isEqualTo(before + 1);
        staff(Role.SALES_STAFF, sales, get(STAFF).param("overdue", "true")
                .param("customerId", alice.customer().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + late + "')].firstResponseOverdue").value(true));

        UUID answered = open("Cau hoi khac");
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + answered + "/claim")).andExpect(status().isOk());
        reply(sales, answered, "Em day").andExpect(status().isCreated());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET first_response_due_at = NOW() -"
                + " INTERVAL '1 minute' WHERE id = ?", answered));
        assertThat(lifecycle.alertFirstResponse(answered)).isFalse();
    }

    @Test
    @DisplayName("BR-09: not resolved in 2 working days - logged and told to the supervisors once, not before; counted "
            + "in business hours; a resolved conversation never")
    void resolutionOverdueOnce() throws Exception {
        UUID late = open("Bao gia gap");
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + late + "/claim")).andExpect(status().isOk());
        reply(sales, late, "Em dang xu ly a").andExpect(status().isCreated());

        // Due 2 working days after the question, in the chat's hours: 19 working hours later, never on a Sunday.
        Instant asked = jdbc.queryForObject("SELECT occurred_at FROM chat.conversation_event WHERE conversation_id = ?"
                + " AND type = 'OPENED'", Timestamp.class, late).toInstant();
        Instant due = jdbc.queryForObject("SELECT resolution_due_at FROM chat.conversation WHERE id = ?",
                Timestamp.class, late).toInstant();
        BusinessHours hours = properties.businessHours();
        assertThat(hours.between(asked, due)).isEqualTo(Duration.ofMinutes(2 * 570));
        assertThat(due.atZone(hours.zone()).getDayOfWeek()).isNotEqualTo(DayOfWeek.SUNDAY);
        assertThat(hours.isOpenAt(due) || due.atZone(hours.zone()).toLocalTime().equals(hours.closes())).isTrue();
        assertThat(due).isAfter(asked.plus(Duration.ofHours(19)));

        // Not before the target.
        assertThat(lifecycle.overdueResolutions()).doesNotContain(late);
        assertThat(lifecycle.alertResolution(late)).isFalse();
        staff(Role.SALES_STAFF, sales, get(STAFF).param("overdue", "true")
                .param("customerId", alice.customer().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + late + "')]").isEmpty());

        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET resolution_due_at = NOW() -"
                + " INTERVAL '1 minute' WHERE id = ?", late));
        long before = people.messages(manager);
        assertThat(lifecycle.overdueResolutions()).contains(late);
        assertThat(lifecycle.alertResolution(late)).isTrue();
        assertThat(lifecycle.alertResolution(late)).isFalse();
        assertThat(lifecycle.overdueResolutions()).doesNotContain(late);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_event WHERE conversation_id = ? AND"
                + " type = 'RESOLUTION_OVERDUE'", Long.class, late)).isEqualTo(1L);
        assertThat(people.messages(manager)).as("the supervisor's inbox, once").isEqualTo(before + 1);
        // Answered in time, so it is in the overdue queue for its resolution alone; the customer is shown no deadline.
        staff(Role.SALES_STAFF, sales, get(STAFF).param("overdue", "true")
                .param("customerId", alice.customer().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + late + "')].resolutionOverdue").value(true))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + late + "')].firstResponseOverdue").value(false));
        staff(Role.SALES_STAFF, sales, get(STAFF + "/" + late)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionDueAt").isNotEmpty());
        http.asCustomer(alice.account(), get(MINE + "/" + late)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionDueAt").doesNotExist())
                .andExpect(jsonPath("$.data.resolutionOverdue").value(false));

        UUID resolved = open("Hoi nhanh");   // routed to the Sales who looked after her last (16 §4.1 step 2.2)
        reply(sales, resolved, "Da xong").andExpect(status().isCreated());
        move(sales, resolved, "RESOLVED");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET resolution_due_at = NOW() -"
                + " INTERVAL '1 minute' WHERE id = ?", resolved));
        assertThat(lifecycle.overdueResolutions()).doesNotContain(resolved);
        assertThat(lifecycle.alertResolution(resolved)).isFalse();
    }

    @Test
    @DisplayName("16 §5: silent 7 days while waiting - auto-closed; the reopen window open, then closed for good")
    void autoCloseAndReopenWindow() throws Exception {
        UUID conversation = open("Cho bao gia");
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
        reply(sales, conversation, "Em gui bao gia, anh xem nhe").andExpect(status().isCreated());
        move(sales, conversation, "WAITING_ON_CUSTOMER");
        assertThat(lifecycle.autoClose(conversation)).as("not silent long enough").isFalse();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET status_changed_at = NOW() -"
                + " INTERVAL '8 days' WHERE id = ?", conversation));
        assertThat(lifecycle.silentCustomers()).contains(conversation);
        assertThat(lifecycle.autoClose(conversation)).isTrue();
        assertThat(statusOf(conversation)).isEqualTo("AUTO_CLOSED");
        assertThat(jdbc.queryForObject("SELECT reopen_until > NOW() + INTERVAL '13 days' FROM chat.conversation"
                + " WHERE id = ?", Boolean.class, conversation)).as("14 days to reopen").isTrue();
        assertThat(lifecycle.close(conversation)).as("still within the window").isFalse();

        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET reopen_until = NOW() - INTERVAL"
                + " '1 minute' WHERE id = ?", conversation));
        assertThat(lifecycle.pastReopenWindow()).contains(conversation);
        assertThat(lifecycle.close(conversation)).isTrue();
        assertThat(statusOf(conversation)).isEqualTo("CLOSED");
        send(conversation, "Con do khong?").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_CONVERSATION_ENDED"));
    }

    @Test
    @DisplayName("BR-08: a card number, a CVV and a password are masked before they are stored")
    void masksSensitiveData() throws Exception {
        UUID conversation = open(null);
        send(conversation, "The 4111 1111 1111 1111 CVV: 123, mat khau: Abc@123. Don SO-20261112-000001")
                .andExpect(status().isCreated());
        String stored = jdbc.queryForObject("SELECT body FROM chat.message WHERE conversation_id = ?", String.class,
                conversation);
        assertThat(stored).doesNotContain("4111 1111 1111 1111").contains("**** **** **** 1111")
                .contains("CVV: ***").doesNotContain("Abc@123").contains("SO-20261112-000001");
    }

    @Test
    @DisplayName("Edit and delete: the author, within 5 minutes, a text or a note; never a card, never someone else's")
    void editWindow() throws Exception {
        UUID conversation = open(null);
        UUID message = id(send(conversation, "Toi can 500 thung").andExpect(status().isCreated()));
        http.asCustomer(alice.account(), patch(MINE + "/" + conversation + "/messages/" + message)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Toi can 600 thung\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("Toi can 600 thung"))
                .andExpect(jsonPath("$.data.editedAt").isNotEmpty());
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
        staff(Role.SALES_STAFF, sales, delete(STAFF + "/" + conversation + "/messages/" + message))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_MESSAGE_NOT_EDITABLE"));
        UUID old = sentMinutesAgo(conversation, 6);
        http.asCustomer(alice.account(), delete(MINE + "/" + conversation + "/messages/" + old))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_EDIT_WINDOW_PASSED"));

        UUID second = id(send(conversation, "Nham hoi thoai").andExpect(status().isCreated()));
        JsonNode deleted = json.readTree(body(http.asCustomer(alice.account(),
                delete(MINE + "/" + conversation + "/messages/" + second)).andExpect(status().isOk()))).path("data");
        assertThat(deleted.path("deleted").asBoolean()).isTrue();
        assertThat(deleted.path("body").isNull() || deleted.path("body").isMissingNode()).isTrue();
        assertThat(log(conversation)).contains("MESSAGE_EDITED", "MESSAGE_DELETED");
    }

    @Test
    @DisplayName("TC-F13-03: an internal note is in no customer answer - messages, badge - and is found by the staff's search")
    void notesStayWithTheStaff() throws Exception {
        UUID conversation = open("Hoi báo giá cốc giấy");
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/notes")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Bao gia noi bo chiet khau 7%\"}"))
                .andExpect(status().isCreated());
        reply(sales, conversation, "Em gửi báo giá ngay").andExpect(status().isCreated());

        String mine = body(http.asCustomer(alice.account(), get(MINE + "/" + conversation + "/messages")));
        assertThat(mine).doesNotContain("chiet khau").doesNotContain("INTERNAL_NOTE");
        http.asCustomer(alice.account(), get(MINE + "/unread-count")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").value(1)).andExpect(jsonPath("$.data.conversations").value(1));
        http.asCustomer(alice.account(), get("/api/v1/chat/search").param("q", "bao gia"))
                .andExpect(status().isForbidden());

        String found = body(staff(Role.SALES_STAFF, otherSales, get("/api/v1/chat/search").param("q", "bao gia")
                .param("customerId", alice.customer().toString())).andExpect(status().isOk()));
        JsonNode items = json.readTree(found).path("data").path("items");
        assertThat(items).as("accents ignored, the note included").hasSize(3);
        staff(Role.SALES_STAFF, otherSales, get("/api/v1/chat/search").param("q", "chiet")
                .param("conversationId", conversation.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].message.kind").value("INTERNAL_NOTE"));
    }

    @Test
    @DisplayName("Saved replies: one's own and the shared ones, variables filled on the server when sent")
    void cannedResponses() throws Exception {
        UUID conversation = open("Xin chao");
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
        String created = body(staff(Role.SALES_STAFF, sales, post("/api/v1/chat/canned-responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Chao\",\"body\":\"Chao {customerName}, em la {agentName}. {unknown}\","
                        + "\"shortcut\":\"chao\"}")).andExpect(status().isCreated()));
        UUID canned = UUID.fromString(json.readTree(created).path("data").path("id").asText());
        staff(Role.SALES_STAFF, sales, post("/api/v1/chat/canned-responses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Chung\",\"body\":\"Cua ca nhom\",\"shared\":true}"))
                .andExpect(status().isForbidden());
        staff(Role.SALES_STAFF, otherSales, get("/api/v1/chat/canned-responses")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + canned + "')]").isEmpty());

        String name = jdbc.queryForObject("SELECT full_name FROM customer.customer WHERE id = ?", String.class,
                alice.customer());
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cannedResponseId\":\"" + canned + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.body").value(org.hamcrest.Matchers.startsWith("Chao " + name + ", em la")))
                .andExpect(jsonPath("$.data.body").value(org.hamcrest.Matchers.endsWith("{unknown}")));
        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cannedResponseId\":\"" + canned + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("16 §4.1 step 3: an offline customer hears of Sales' replies once per period; the agent's badge counts")
    void offlineCustomerIsTold() throws Exception {
        UUID conversation = open("Toi se quay lai sau");
        staff(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
        reply(sales, conversation, "Em tra loi day a").andExpect(status().isCreated());
        reply(sales, conversation, "Them mot y nua").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ?"
                + " AND category = 'CHAT'", Long.class, alice.account())).isEqualTo(1L);
        send(conversation, "Cam on").andExpect(status().isCreated());
        staff(Role.SALES_STAFF, sales, get("/api/v1/chat/unread-count")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").value(1));
        http.asCustomer(alice.account(), get(MINE + "/" + conversation)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withinBusinessHours").isBoolean());
    }

    // ---- helpers

    private Portal portal() {
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        return new Portal(customer, account);
    }

    private UUID open(String text) throws Exception {
        String first = text == null ? "" : "\"text\":\"" + text + "\"";
        return id(http.asCustomer(alice.account(), post(MINE).contentType(MediaType.APPLICATION_JSON)
                .content("{" + first + "}")).andExpect(status().isCreated()));
    }

    /**
     * A customer's text sent {@code minutes} ago, written as the row the chat would have stored then: a message's
     * time never changes ({@code tg_message_immutable}), so the edit window is passed by a message born old.
     */
    private UUID sentMinutesAgo(UUID conversation, int minutes) {
        UUID message = com.stockflow.common.id.Identifiers.newId();
        tx.executeWithoutResult(s -> {
            long seq = jdbc.queryForObject("SELECT last_message_seq + 1 FROM chat.conversation WHERE id = ?"
                    + " FOR UPDATE", Long.class, conversation);
            jdbc.update("""
                    INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body,
                                              sent_at, version, created_at)
                    VALUES (?, ?, ?, 'TEXT', 'CUSTOMER', 'CUSTOMER', ?, 'Cu roi', NOW() - make_interval(mins => ?), 0,
                            NOW())""", message, conversation, seq, alice.account(), minutes);
            jdbc.update("UPDATE chat.conversation SET last_message_seq = ?, last_message_at = NOW() WHERE id = ?",
                    seq, conversation);
        });
        return message;
    }

    private ResultActions send(UUID conversation, String text) throws Exception {
        return http.asCustomer(alice.account(), post(MINE + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}"));
    }

    private ResultActions reply(UUID agent, UUID conversation, String text) throws Exception {
        return staff(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}"));
    }

    private void move(UUID agent, UUID conversation, String target) throws Exception {
        String why = "ESCALATED".equals(target) || "WAITING_ON_INTERNAL".equals(target) ? ",\"reason\":\"x\"" : "";
        staff(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"" + why + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions staff(Role role, UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return http.asRole(role, user, request);
    }

    private UUID id(ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(body(result)).path("data").path("id").asText());
    }

    private String statusOf(UUID conversation) {
        return jdbc.queryForObject("SELECT status FROM chat.conversation WHERE id = ?", String.class, conversation);
    }

    private String log(UUID conversation) {
        return String.join(",", jdbc.queryForList("SELECT type FROM chat.conversation_event WHERE conversation_id = ?"
                + " ORDER BY occurred_at", String.class, conversation));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
