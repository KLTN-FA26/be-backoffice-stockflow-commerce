package com.stockflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sales chat over HTTP with the real security chain, the real role matrix and the real database (kltn-docs 16,
 * feature F-13): a customer opens a conversation about their own order and writes; Sales take it, answer and write
 * internal notes; the customer never sees a note (16 BR-07); nobody reaches another customer's conversation or names
 * another customer's document (BR-02); a retried send is one message; the moves of 16 §5 and the log of BR-01; and a
 * resolved conversation reopens within its window and continues as a new one after it. Only token decoding is
 * stubbed.
 */
@SecuredIntegrationTest
class ChatHttpIntegrationTest {

    private static final String MINE = "/api/v1/chat/me/conversations";
    private static final String STAFF = "/api/v1/chat/conversations";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private Portal alice;
    private Portal bob;
    private UUID sales;
    private UUID otherSales;
    private UUID manager;

    /** A customer and its portal account. */
    private record Portal(UUID customer, UUID account) {
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        alice = portal(people);
        bob = portal(people);
        sales = people.staff("SALES_STAFF");
        otherSales = people.staff("SALES_STAFF");
        manager = people.staff("SALES_MANAGER");
    }

    @Test
    @DisplayName("16 BR-07: an internal note reaches the staff and never the customer, in any customer response")
    void internalNotesNeverReachTheCustomer() throws Exception {
        UUID conversation = openAsCustomer(alice, null, "Cho toi hoi gia coc giay");
        claim(sales, conversation);
        String note = "Khach nay hay tra cham - ghi chu noi bo";
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + note + "\",\"mentions\":[\"" + manager + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("INTERNAL_NOTE"))
                .andExpect(jsonPath("$.data.visibility").value("STAFF"));
        reply(sales, conversation, "Chao anh, em gui bao gia ngay a", null).andExpect(status().isCreated());

        String customerMessages = body(http.asCustomer(alice.account(),
                get(MINE + "/" + conversation + "/messages")));
        String customerSince = body(http.asCustomer(alice.account(),
                get(MINE + "/" + conversation + "/messages").param("since", "0")));
        String customerDetail = body(http.asCustomer(alice.account(), get(MINE + "/" + conversation)));
        String customerList = body(http.asCustomer(alice.account(), get(MINE)));
        for (String answer : List.of(customerMessages, customerSince, customerDetail, customerList)) {
            assertThat(answer).doesNotContain(note).doesNotContain("INTERNAL_NOTE").doesNotContain(manager.toString());
        }
        assertThat(customerMessages).contains("Chao anh, em gui bao gia ngay a").contains("Cho toi hoi gia coc giay");

        String staffMessages = body(perform(Role.SALES_STAFF, sales, get(STAFF + "/" + conversation + "/messages")));
        assertThat(staffMessages).contains(note).contains("INTERNAL_NOTE");
        // The note's place in the sequence is the staff's: the customer's catch-up sees the two others only.
        JsonNode since = json.readTree(customerSince).path("data").path("items");
        assertThat(since).hasSize(2);
        assertThat(since.get(0).path("seq").asLong()).isLessThan(since.get(1).path("seq").asLong());
    }

    @Test
    @DisplayName("16 BR-02: another customer's conversation, document and files are out of reach; the staff side refuses a customer")
    void aCustomerReachesOnlyTheirOwn() throws Exception {
        UUID bobsOrder = order(bob.customer());
        UUID alicesOrder = order(alice.customer());
        UUID bobsConversation = openAsCustomer(bob, bobsOrder, "Don cua toi");

        http.asCustomer(alice.account(), get(MINE + "/" + bobsConversation))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONVERSATION_NOT_FOUND"));
        http.asCustomer(alice.account(), get(MINE + "/" + bobsConversation + "/messages"))
                .andExpect(status().isNotFound());
        http.asCustomer(alice.account(), post(MINE + "/" + bobsConversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"chen ngang\"}"))
                .andExpect(status().isNotFound());
        http.asCustomer(alice.account(), multipart(MINE + "/" + bobsConversation + "/attachments")
                .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3})))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONVERSATION_NOT_FOUND"));
        http.asCustomer(alice.account(), post(MINE + "/" + bobsConversation + "/reopen"))
                .andExpect(status().isNotFound());
        // Naming Bob's order as the context of her own conversation: the same 404 as an order that does not exist.
        http.asCustomer(alice.account(), post(MINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextKind\":\"ORDER\",\"contextId\":\"" + bobsOrder + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONTEXT_NOT_FOUND"));
        http.asCustomer(alice.account(), get(MINE).param("contextKind", "ORDER").param("contextId",
                bobsOrder.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        // The staff side: a portal account is refused, though it holds chat-conversations.
        http.asCustomer(alice.account(), get(STAFF)).andExpect(status().isForbidden());
        http.asCustomer(alice.account(), get(STAFF + "/" + bobsConversation + "/messages"))
                .andExpect(status().isForbidden());
        http.asCustomer(alice.account(), get("/api/v1/chat/customers/" + bob.customer() + "/overview"))
                .andExpect(status().isForbidden());
        // Sales do not attach Bob's order to Alice's conversation either.
        UUID alicesConversation = openAsCustomer(alice, alicesOrder, "Don cua toi");
        claim(sales, alicesConversation);
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + alicesConversation + "/contexts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ORDER\",\"id\":\"" + bobsOrder + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONTEXT_NOT_FOUND"));

        // Her own order: the conversation is about it, reused when she opens again from the same page, and listed
        // on the order's page.
        UUID again = openAsCustomer(alice, alicesOrder, null);
        assertThat(again).isEqualTo(alicesConversation);
        http.asCustomer(alice.account(), get(MINE).param("contextKind", "ORDER").param("contextId",
                        alicesOrder.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(alicesConversation.toString()));
        assertThat(jdbc.queryForObject("SELECT order_id FROM chat.conversation WHERE id = ?", UUID.class,
                alicesConversation)).as("the legacy order column the consent check reads").isEqualTo(alicesOrder);
        // The customer panel beside it shows her order, through the order module's api.
        perform(Role.SALES_STAFF, sales, get("/api/v1/chat/customers/" + alice.customer() + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders[0].id").value(alicesOrder.toString()))
                .andExpect(jsonPath("$.data.conversations[0].id").value(alicesConversation.toString()));
    }

    @Test
    @DisplayName("a retried send with the same client id is one message; the same id for something else is refused")
    void aRetriedSendIsOneMessage() throws Exception {
        UUID conversation = openAsCustomer(alice, null, null);
        String clientId = "web-" + UUID.randomUUID();
        String first = body(send(alice, conversation, "Gui lai mau", clientId).andExpect(status().isCreated()));
        String retry = body(send(alice, conversation, "Gui lai mau", clientId).andExpect(status().isCreated()));
        assertThat(json.readTree(retry).path("data").path("id").asText())
                .isEqualTo(json.readTree(first).path("data").path("id").asText());
        send(alice, conversation, "Noi dung khac", clientId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_CLIENT_MESSAGE_ID_REUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.message WHERE conversation_id = ?", Long.class,
                conversation)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT last_read_seq FROM chat.participant_read WHERE conversation_id = ?"
                + " AND user_id = ?", Long.class, conversation, alice.account()))
                .as("the sender has read their own message").isEqualTo(1L);
    }

    @Test
    @DisplayName("16 §5 over HTTP: claim, answer, the moves, who may make them, and the log of BR-01")
    void theLifecycleOverHttp() throws Exception {
        UUID conversation = openAsCustomer(alice, null, "Can tu van");
        assertThat(statusOf(conversation)).isEqualTo("QUEUED");
        claim(sales, conversation);
        assertThat(statusOf(conversation)).isEqualTo("ASSIGNED");
        perform(Role.SALES_STAFF, otherSales, post(STAFF + "/" + conversation + "/claim"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_ALREADY_ASSIGNED"));
        reply(otherSales, conversation, "Em tra loi thay", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_NOT_ASSIGNEE"));
        reply(sales, conversation, "Chao anh", null).andExpect(status().isCreated());
        assertThat(statusOf(conversation)).isEqualTo("ACTIVE");
        move(sales, conversation, "CLOSED", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_TRANSITION_NOT_ALLOWED"));
        move(sales, conversation, "REOPENED", null).andExpect(status().isConflict());
        move(sales, conversation, "WAITING_ON_INTERNAL", "Hoi ke toan").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_ON_INTERNAL"));
        move(sales, conversation, "RESOLVED", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_TRANSITION_NOT_ALLOWED"));
        move(sales, conversation, "ACTIVE", null).andExpect(status().isOk());
        move(sales, conversation, "ESCALATED", "Giam gia vuot han muc").andExpect(status().isOk());
        move(sales, conversation, "ACTIVE", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_NOT_ASSIGNEE"));
        perform(Role.SALES_MANAGER, manager, post(STAFF + "/" + conversation + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        // Transfer: the agent in charge hands it to a colleague, who then answers.
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/assignment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"" + otherSales + "\",\"note\":\"Em nghi phep\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignedAgentId").value(otherSales.toString()));
        perform(Role.SALES_STAFF, otherSales, post(STAFF + "/" + conversation + "/assignment")
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":\"" + alice.account() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_AGENT_NOT_AVAILABLE"));
        move(otherSales, conversation, "RESOLVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        String log = body(perform(Role.SALES_STAFF, sales, get(STAFF + "/" + conversation + "/events")));
        assertThat(log).contains("OPENED", "QUEUED", "CLAIMED", "FIRST_RESPONSE", "TRANSFERRED", "STATUS_CHANGED",
                "Giam gia vuot han muc", sales.toString(), otherSales.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_event WHERE conversation_id = ?"
                + " AND type = 'STATUS_CHANGED'", Long.class, conversation))
                .as("NEW>QUEUED>ASSIGNED>ACTIVE>WAITING_ON_INTERNAL>ACTIVE>ESCALATED>ACTIVE>RESOLVED").isEqualTo(8L);
    }

    @Test
    @DisplayName("16 §5: a resolved conversation reopens within its window; after it the customer continues in a new one")
    void reopenWithinTheWindowContinueAfterIt() throws Exception {
        UUID conversation = openAsCustomer(alice, null, "Hoi hoa don");
        claim(sales, conversation);
        reply(sales, conversation, "Da gui", null).andExpect(status().isCreated());
        move(sales, conversation, "RESOLVED", null).andExpect(status().isOk());

        // Within the window: her word reopens it, and Sales take it up again.
        send(alice, conversation, "Toi chua nhan duoc", null).andExpect(status().isCreated());
        assertThat(statusOf(conversation)).isEqualTo("REOPENED");
        reply(sales, conversation, "Em gui lai a", null).andExpect(status().isCreated());
        assertThat(statusOf(conversation)).isEqualTo("ACTIVE");
        move(sales, conversation, "RESOLVED", null).andExpect(status().isOk());
        http.asCustomer(alice.account(), post(MINE + "/" + conversation + "/reopen")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(conversation.toString()))
                .andExpect(jsonPath("$.data.status").value("REOPENED"));
        move(sales, conversation, "ACTIVE", null).andExpect(status().isOk());
        move(sales, conversation, "RESOLVED", null).andExpect(status().isOk());

        // After the window: writing is refused, and reopening starts a new conversation that continues it.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET reopen_until = NOW() - INTERVAL '1"
                + " minute' WHERE id = ?", conversation));
        send(alice, conversation, "Con do khong?", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_CONVERSATION_ENDED"));
        String sequel = body(http.asCustomer(alice.account(), post(MINE + "/" + conversation + "/reopen"))
                .andExpect(status().isOk()));
        JsonNode next = json.readTree(sequel).path("data");
        assertThat(next.path("id").asText()).isNotEqualTo(conversation.toString());
        assertThat(next.path("continuedFromId").asText()).isEqualTo(conversation.toString());
        assertThat(next.path("status").asText()).isIn("QUEUED", "ASSIGNED");
        assertThat(statusOf(conversation)).as("the old one is left as it was").isEqualTo("RESOLVED");
        send(alice, UUID.fromString(next.path("id").asText()), "Con do khong?", null)
                .andExpect(status().isCreated());
    }

    // ---- helpers

    private Portal portal(StaffFixtures people) {
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        return new Portal(customer, account);
    }

    private UUID order(UUID customer) {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, hold_reason)
                VALUES (?, ?, ?, ?, 'ON_HOLD', 1000, 'VND', NOW(), 0, NOW(), 'CREDIT')""", order,
                "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)), customer,
                UUID.randomUUID()));
        return order;
    }

    private UUID openAsCustomer(Portal who, UUID order, String text) throws Exception {
        String context = order == null ? "" : "\"contextKind\":\"ORDER\",\"contextId\":\"" + order + "\",";
        String first = text == null ? "" : "\"text\":\"" + text + "\",";
        String answer = body(http.asCustomer(who.account(), post(MINE).contentType(MediaType.APPLICATION_JSON)
                .content("{" + context + first + "\"subject\":\"Hoi Sale\"}")).andExpect(status().isCreated()));
        return UUID.fromString(json.readTree(answer).path("data").path("id").asText());
    }

    private void claim(UUID agent, UUID conversation) throws Exception {
        perform(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions reply(UUID agent, UUID conversation, String text,
                                                                     String clientId) throws Exception {
        return perform(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(message(text, clientId)));
    }

    private org.springframework.test.web.servlet.ResultActions send(Portal who, UUID conversation, String text,
                                                                    String clientId) throws Exception {
        return http.asCustomer(who.account(), post(MINE + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(message(text, clientId)));
    }

    private org.springframework.test.web.servlet.ResultActions move(UUID agent, UUID conversation, String target,
                                                                    String reason) throws Exception {
        String why = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return perform(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"" + why + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions perform(Role role, UUID user,
                                                                       MockHttpServletRequestBuilder request)
            throws Exception {
        return http.asRole(role, user, request);
    }

    private String statusOf(UUID conversation) {
        return jdbc.queryForObject("SELECT status FROM chat.conversation WHERE id = ?", String.class, conversation);
    }

    private static String message(String text, String clientId) {
        return "{\"text\":\"" + text + "\"" + (clientId == null ? "" : ",\"clientMessageId\":\"" + clientId + "\"")
                + "}";
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
