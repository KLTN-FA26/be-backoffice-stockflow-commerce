package com.stockflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.service.ChatCards;
import com.stockflow.chat.internal.service.ConversationWriter;
import com.stockflow.chat.internal.service.DocumentCards;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.design.api.QuoteRequests;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.payment.api.CustomerInstallments;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Package 3's cards over HTTP with the real security chain and database (kltn-docs 16 §3 Output, BR-01, BR-03, BR-06,
 * BR-10, BR-11; TC-F13-01, TC-F13-02). The documents' own modules are stood in for by their {@code api}
 * ({@link QuoteCards}, {@link QuoteRequests}, {@link SampleRoundCards}, {@link CustomerInstallments}) - what is under
 * test is that the chat calls them, on behalf of the right person, and shows and logs what they answer. The cards are
 * drawn by calling {@link DocumentCards} as the event listeners do, so nothing waits on an async thread.
 */
@SecuredIntegrationTest
class ChatCardsIntegrationTest {

    private static final String MINE = "/api/v1/chat/me/conversations";
    private static final String STAFF = "/api/v1/chat/conversations";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean QuoteCards quotes;
    @MockitoBean QuoteRequests quoteRequests;
    @MockitoBean SampleRoundCards samples;
    @MockitoBean CustomerInstallments installments;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired DocumentCards documentCards;
    @Autowired ChatCards cards;
    @Autowired ConversationWriter writer;
    @Autowired ConversationRepository conversations;

    private SecuredMvc http;
    private Portal alice;
    private Portal bob;
    private UUID sales;

    private record Portal(UUID customer, UUID account) {
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        alice = portal(people);
        bob = portal(people);
        sales = people.staff("SALES_STAFF");
    }

    @Test
    @DisplayName("TC-F13-02: a quote request card is the system's; Sales draw up a quote from it only once the design is approved")
    void quoteRequestCard() throws Exception {
        UUID design = design(alice);
        UUID requestId = Identifiers.newId();
        when(quoteRequests.designRequest(requestId)).thenReturn(Optional.of(request(requestId, design, alice,
                "PENDING_REVIEW", null)));
        documentCards.designQuoteRequested(requestId);
        documentCards.designQuoteRequested(requestId);

        List<Map<String, Object>> posted = jdbc.queryForList("SELECT id, conversation_id, sender_type, sender_id,"
                + " card_state FROM chat.message WHERE card_kind = 'QUOTE_REQUEST' AND conversation_id IN"
                + " (SELECT id FROM chat.conversation WHERE customer_id = ?)", alice.customer());
        assertThat(posted).as("one card however often the event comes").hasSize(1);
        assertThat(posted.get(0).get("sender_type")).as("BR-11: system-generated").isEqualTo("SYSTEM");
        assertThat(posted.get(0).get("sender_id")).isNull();
        UUID conversation = (UUID) posted.get(0).get("conversation_id");
        UUID card = (UUID) posted.get(0).get("id");
        assertThat(jdbc.queryForObject("SELECT first_response_due_at IS NOT NULL FROM chat.conversation WHERE id = ?",
                Boolean.class, conversation)).as("a quote request asks Sales: the clock runs").isTrue();
        claim(sales, conversation);

        // The design waits for review: Sales cannot quote it yet.
        cardAction(Role.SALES_STAFF, sales, conversation, card, "{\"action\":\"CREATE_QUOTE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_DESIGN_NOT_APPROVED"));
        verify(quotes, never()).draft(any(), any());

        // Approved (the design module's decision): the card follows, and Sales draw up the draft from it.
        when(quoteRequests.designRequest(requestId)).thenReturn(Optional.of(request(requestId, design, alice,
                "APPROVED", "ACME-CUP-01")));
        documentCards.designChanged(design);
        UUID quote = quoteRow(alice);
        when(quotes.draft(any(), any())).thenReturn(quoteCard(quote, alice, "DRAFT", 1));
        String acted = body(cardAction(Role.SALES_STAFF, sales, conversation, card,
                "{\"action\":\"CREATE_QUOTE\"}").andExpect(status().isOk()));
        JsonNode view = json.readTree(acted).path("data").path("card");
        assertThat(view.path("state").asText()).isEqualTo("ACTED");
        assertThat(view.path("payload").path("quoteId").asText()).isEqualTo(quote.toString());
        ArgumentCaptor<QuoteCards.Draft> drafted = ArgumentCaptor.forClass(QuoteCards.Draft.class);
        verify(quotes).draft(any(), drafted.capture());
        assertThat(drafted.getValue().requestId()).as("a retried click draws up the same quote").isEqualTo(draftId(card));
        assertThat(drafted.getValue().customerId()).isEqualTo(alice.customer());
        assertThat(drafted.getValue().lines()).containsExactly(new QuoteCards.DraftLine("ACME-CUP-01", 40));
        assertThat(drafted.getValue().requestedDeliveryDate()).isEqualTo(LocalDate.now().plusDays(30));
        verify(quoteRequests).close(requestId, sales);
        assertThat(log(conversation)).contains("CARD_SENT", "CARD_ACTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_context WHERE conversation_id = ?"
                + " AND quote_id = ?", Long.class, conversation, quote)).as("the quote's card will land here").isEqualTo(1L);

        // The customer has no such action; nor is there an endpoint that posts this card from a person.
        cardAction(null, alice.account(), conversation, card, "{\"action\":\"CREATE_QUOTE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_CARD_ACTION_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("16 §4.3: the quote card - the customer accepts on it through the quote module; Sales cannot accept for them")
    void quoteCard() throws Exception {
        UUID conversation = openAsCustomer(alice, "Bao gia coc giay");
        claim(sales, conversation);
        UUID quote = quoteRow(alice);
        when(quotes.quote(quote)).thenReturn(Optional.of(quoteCard(quote, alice, "SENT", 1)));
        documentCards.quoteChanged(new QuoteStatusChanged(quote, "QT-1", alice.customer(), 1, "DRAFT", "SENT", sales,
                Instant.now()));
        documentCards.quoteChanged(new QuoteStatusChanged(quote, "QT-1", alice.customer(), 1, "DRAFT", "SENT", sales,
                Instant.now()));
        UUID card = jdbc.queryForObject("SELECT id FROM chat.message WHERE conversation_id = ? AND card_kind = 'QUOTE'",
                UUID.class, conversation);
        assertThat(jdbc.queryForObject("SELECT sender_id FROM chat.message WHERE id = ?", UUID.class, card))
                .as("Sales' action, drawn by the system into the customer's open conversation").isEqualTo(sales);

        String shown = body(http.asCustomer(alice.account(), get(MINE + "/" + conversation + "/messages")));
        assertThat(shown).contains("\"customerActions\":[\"ACCEPT\",\"REQUEST_CHANGES\"]");

        cardAction(Role.SALES_STAFF, sales, conversation, card, "{\"action\":\"ACCEPT\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_CARD_ACTION_NOT_ALLOWED"));
        UUID line = quoteCard(quote, alice, "SENT", 1).lines().get(0).id();
        when(quotes.accept(any(CurrentUser.class), eq(quote), eq(3L), any()))
                .thenReturn(quoteCard(quote, alice, "ACCEPTED", 1));
        cardAction(null, alice.account(), conversation, card,
                "{\"action\":\"ACCEPT\",\"version\":3,\"cartons\":{\"" + line + "\":50}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.card.state").value("ACTED"))
                .andExpect(jsonPath("$.data.card.payload.status").value("ACCEPTED"));
        verify(quotes).accept(any(CurrentUser.class), eq(quote), eq(3L), eq(Map.of(line, 50)));
        assertThat(jdbc.queryForObject("SELECT actor_type || ':' || note FROM chat.conversation_event WHERE"
                + " conversation_id = ? AND type = 'CARD_ACTION'", String.class, conversation))
                .as("BR-01: logged with who acted").isEqualTo("CUSTOMER:ACCEPT");
        // Acted on: the card offers nothing more.
        cardAction(null, alice.account(), conversation, card, "{\"action\":\"REQUEST_CHANGES\",\"note\":\"x\"}")
                .andExpect(status().isConflict());
        // Bob reaches neither the conversation nor the card (TC-F13-01).
        cardAction(null, bob.account(), conversation, card, "{\"action\":\"ACCEPT\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BR-10: a print sample card is answered by its customer only; a reprint says why")
    void sampleApprovalCard() throws Exception {
        UUID conversation = openAsCustomer(alice, "Mau in");
        claim(sales, conversation);
        UUID round = Identifiers.newId();
        UUID card = systemCard(conversation, CardKind.SAMPLE_APPROVAL, "{\"roundId\":\"" + round
                + "\",\"status\":\"WAITING\",\"roundNo\":1}");
        cardAction(Role.SALES_STAFF, sales, conversation, card, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_CARD_ACTION_NOT_ALLOWED"));
        cardAction(null, alice.account(), conversation, card, "{\"action\":\"REQUEST_REPRINT\"}")
                .andExpect(status().isBadRequest());
        when(samples.answer(alice.customer(), alice.account(), round, false, "Lech mau logo"))
                .thenReturn(round(round, alice, "REPRINT_REQUESTED"));
        cardAction(null, alice.account(), conversation, card,
                "{\"action\":\"REQUEST_REPRINT\",\"note\":\"Lech mau logo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.card.state").value("ACTED"))
                .andExpect(jsonPath("$.data.card.payload.status").value("REPRINT_REQUESTED"));
        verify(samples, never()).answer(any(), eq(sales), any(), any(Boolean.class), any());
    }

    @Test
    @DisplayName("BR-06: transfer details only as the system's card - account from configuration, VietQR; never typed")
    void transferDetailsCard() throws Exception {
        UUID conversation = openAsCustomer(alice, "Chuyen khoan");
        claim(sales, conversation);
        UUID installment = Identifiers.newId();
        CustomerInstallments.Transfer transfer = new CustomerInstallments.Transfer(installment, alice.customer(),
                Identifiers.newId(), "HD-20261112-000001-1", Identifiers.newId(), "SO-20261112-000001", 1, "Dot 1",
                new BigDecimal("15000000"), new BigDecimal("15000000"), "VND", LocalDate.now(), "DUE", false,
                "HD-20261112-000001-1 DOT1");
        JsonNode payload = tx.execute(s -> documentCards.transferPayload(transfer, false));
        assertThat(payload.path("bank").path("accountNumber").asText()).isEqualTo("0000000000");
        assertThat(payload.path("transferContent").asText()).isEqualTo("HD-20261112-000001-1 DOT1");
        String qr = payload.path("vietQr").asText();
        assertThat(qr).startsWith("000201010212").contains("A000000727").contains("9700000110000000000")
                .contains("540815000000").contains("HD-20261112-000001-1 DOT1").contains("6304");
        assertThat(qr.substring(qr.length() - 4)).matches("[0-9A-F]{4}");

        // Bob's installment is not Alice's conversation's to send (BR-02).
        UUID bobs = Identifiers.newId();
        when(installments.transfer(bobs)).thenReturn(Optional.of(new CustomerInstallments.Transfer(bobs,
                bob.customer(), Identifiers.newId(), "HD-X", Identifiers.newId(), "SO-X", 1, "Dot 1", BigDecimal.TEN,
                BigDecimal.TEN, "VND", null, "DUE", false, "HD-X DOT1")));
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/cards/transfer-details")
                .contentType(MediaType.APPLICATION_JSON).content("{\"installmentId\":\"" + bobs + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONTEXT_NOT_FOUND"));
        // Typing the company's account number is refused: the card is the way.
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Anh chuyen vao STK 0000 000 000 giup em\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("16 §4.6: an order status card lands in the customer's open conversation, once per event, the newer superseding")
    void orderStatusCard() throws Exception {
        UUID conversation = openAsCustomer(alice, "Hoi giao hang");
        UUID order = order(alice);
        ShipmentStatusChanged handed = shipment(order, alice, "HANDED_OVER");
        documentCards.shipmentChanged(handed);
        documentCards.shipmentChanged(handed);
        documentCards.shipmentChanged(shipment(order, alice, "DELIVERED"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT card_state, card_payload->>'status' AS status"
                + " FROM chat.message WHERE conversation_id = ? AND card_kind = 'ORDER_STATUS' ORDER BY seq",
                conversation);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("card_state")).isEqualTo("SUPERSEDED");
        assertThat(rows.get(1).get("status")).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation WHERE customer_id = ?", Long.class,
                alice.customer())).as("no conversation opened for it").isEqualTo(1L);
    }

    @Test
    @DisplayName("CSAT: a resolved conversation is rated once, on the survey card; not before it is resolved")
    void csat() throws Exception {
        UUID conversation = openAsCustomer(alice, "Cam on");
        claim(sales, conversation);
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Da xu ly\"}")).andExpect(status().isCreated());
        http.asCustomer(alice.account(), post(MINE + "/" + conversation + "/csat")
                .contentType(MediaType.APPLICATION_JSON).content("{\"score\":5}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_CSAT_NOT_OPEN"));
        perform(Role.SALES_STAFF, sales, post(STAFF + "/" + conversation + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}")).andExpect(status().isOk());
        UUID survey = jdbc.queryForObject("SELECT id FROM chat.message WHERE conversation_id = ? AND card_kind ="
                + " 'CSAT_SURVEY'", UUID.class, conversation);
        cardAction(null, alice.account(), conversation, survey, "{\"action\":\"RATE\",\"score\":4,\"comment\":\"Nhanh\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.card.state").value("ACTED"));
        http.asCustomer(alice.account(), post(MINE + "/" + conversation + "/csat")
                .contentType(MediaType.APPLICATION_JSON).content("{\"score\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CHAT_CSAT_ALREADY_GIVEN"));
        assertThat(jdbc.queryForObject("SELECT csat_score FROM chat.conversation WHERE id = ?", Integer.class,
                conversation)).isEqualTo(4);
        assertThat(log(conversation)).contains("CSAT_SUBMITTED");
        perform(Role.SALES_MANAGER, people().staff("SALES_MANAGER"), get("/api/v1/chat/reports/agents")
                .param("from", LocalDate.now().minusDays(1).toString()).param("to", LocalDate.now().plusDays(1).toString())
                .param("agentId", sales.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total.csatAverage").value(4.0))
                .andExpect(jsonPath("$.data.total.answered").value(1));
    }

    // ---- helpers

    private StaffFixtures people() {
        return new StaffFixtures(jdbc, tx, entityManager);
    }

    private Portal portal(StaffFixtures people) {
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        return new Portal(customer, account);
    }

    private UUID design(Portal who) {
        UUID design = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_draft (id, customer_id, product_id, owner_user_id, status, created_at,
                    current_artifacts, name)
                VALUES (?, ?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb, 'Coc logo xanh')""", design, who.customer(),
                DemoData.PRODUCT_SOFA, who.account()));
        return design;
    }

    private UUID quoteRow(Portal who) {
        UUID quote = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until,
                    version, created_at)
                VALUES (?, ?, ?, ?, 'DRAFT', 1, NOW() + interval '7 days', 0, NOW())""", quote,
                "QT-CHAT-" + quote.toString().substring(24), UUID.randomUUID(), who.customer()));
        return quote;
    }

    private UUID order(Portal who) {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, hold_reason)
                VALUES (?, ?, ?, ?, 'ON_HOLD', 1000, 'VND', NOW(), 0, NOW(), 'CREDIT')""", order,
                "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)), who.customer(),
                UUID.randomUUID()));
        return order;
    }

    private static QuoteRequests.DesignRequest request(UUID id, UUID design, Portal who, String status, String sku) {
        return new QuoteRequests.DesignRequest(id, design, "Coc logo xanh", status, who.customer(), "CUP-12OZ", sku,
                null, 40, LocalDate.now().plusDays(30), "OPEN", Instant.now(), null);
    }

    private static QuoteCards.Card quoteCard(UUID id, Portal who, String status, int revision) {
        UUID line = UUID.nameUUIDFromBytes(("line" + id).getBytes(StandardCharsets.UTF_8));
        return new QuoteCards.Card(id, "QT-1", who.customer(), status, revision, Instant.now().plusSeconds(86_400),
                null, List.of(new QuoteCards.Line(line, 1, "ACME-CUP-01", 40, 50, 2000, new BigDecimal("1500"),
                new BigDecimal("3000000"), null)), new BigDecimal("3240000"), null, null, null, null, Instant.now(),
                3);
    }

    private static SampleRoundCards.Card round(UUID round, Portal who, String status) {
        return new SampleRoundCards.Card(round, 1, Identifiers.newId(), "PO-1", "SAMPLE_PENDING", Identifiers.newId(),
                who.customer(), status, null, Instant.now(), Instant.now(), List.of("ACME-CUP-01"), List.of(),
                List.of());
    }

    private static ShipmentStatusChanged shipment(UUID order, Portal who, String status) {
        return new ShipmentStatusChanged(Identifiers.newId(), Identifiers.newId(), "SH-1", order, "SO-1",
                who.customer(), null, null, status, null, 2, "Xe tai", null, null, Instant.now());
    }

    private UUID systemCard(UUID conversation, CardKind kind, String payload) {
        return tx.execute(s -> cards.postInto(writer.lock(conversation), null, kind, cards.readPayload(payload),
                null, Actor.system()).messageId());
    }

    private static UUID draftId(UUID card) {
        return UUID.nameUUIDFromBytes(("chat-quote-request-card:" + card).getBytes(StandardCharsets.UTF_8));
    }

    private UUID openAsCustomer(Portal who, String text) throws Exception {
        String answer = body(http.asCustomer(who.account(), post(MINE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isCreated()));
        return UUID.fromString(json.readTree(answer).path("data").path("id").asText());
    }

    private void claim(UUID agent, UUID conversation) throws Exception {
        perform(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk());
    }

    private ResultActions cardAction(Role role, UUID user, UUID conversation, UUID card, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = post((role == null ? MINE : STAFF) + "/" + conversation + "/cards/"
                + card + "/actions").contentType(MediaType.APPLICATION_JSON).content(body);
        return role == null ? http.asCustomer(user, request) : perform(role, user, request);
    }

    private ResultActions perform(Role role, UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return http.asRole(role, user, request);
    }

    private String log(UUID conversation) {
        return String.join(",", jdbc.queryForList("SELECT type FROM chat.conversation_event WHERE conversation_id = ?"
                + " ORDER BY occurred_at", String.class, conversation));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
