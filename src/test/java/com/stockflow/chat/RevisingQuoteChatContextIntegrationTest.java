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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A quote Sales are revising after the customer asked for changes (DRAFT, sent before) is still the customer's quote -
 * {@code CHANGES_REQUESTED} on its own pages (Quote#revisingSentQuote) - so the customer may open a conversation about
 * it and its chip says so, with the status their portal links from; Sales see their draft. A first draft, never sent,
 * stays out of the customer's reach (16 BR-02).
 */
@SecuredIntegrationTest
class RevisingQuoteChatContextIntegrationTest {

    private static final String MINE = "/api/v1/chat/me/conversations";
    private static final String STAFF = "/api/v1/chat/conversations";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private UUID customer;
    private UUID account;
    private UUID sales;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        account = people.staff("CUSTOMER");
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        sales = people.staff("SALES_STAFF");
    }

    @Test
    @DisplayName("a revised quote opens a conversation, its chip CHANGES_REQUESTED to the customer and DRAFT to Sales; "
            + "a first draft is not found")
    void aRevisedQuoteIsTheCustomersToTalkAbout() throws Exception {
        UUID revising = quote(true);
        String opened = body(http.asCustomer(account, post(MINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextKind\":\"QUOTE\",\"contextId\":\"" + revising + "\",\"subject\":\"Bao gia\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contexts[0].refId").value(revising.toString()))
                .andExpect(jsonPath("$.data.contexts[0].status").value("CHANGES_REQUESTED")));
        UUID conversation = UUID.fromString(json.readTree(opened).path("data").path("id").asText());
        http.asCustomer(account, get(MINE + "/" + conversation)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contexts[0].status").value("CHANGES_REQUESTED"));

        JsonNode staffView = json.readTree(body(http.asRole(Role.SALES_STAFF, sales,
                get(STAFF + "/" + conversation)).andExpect(status().isOk()))).path("data");
        assertThat(staffView.path("contexts").get(0).path("status").asText())
                .as("Sales see the draft they are writing").isEqualTo("DRAFT");

        UUID firstDraft = quote(false);
        http.asCustomer(account, post(MINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextKind\":\"QUOTE\",\"contextId\":\"" + firstDraft + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONTEXT_NOT_FOUND"));
    }

    /**
     * A DRAFT quote of the customer's: a revision reopened after revision 1 was sent ({@code sentBefore}: revision 2,
     * {@code sent_at} kept from the send, as {@code Quote.reopen} leaves it), or a first draft never sent.
     */
    private UUID quote(boolean sentBefore) {
        UUID quote = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until,
                    sent_at, sent_by, version, created_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, NOW() + interval '7 days', ?, ?, 0, NOW() - interval '1 day')""",
                quote, "QT-RV-" + quote.toString().substring(24), UUID.randomUUID(), customer, sentBefore ? 2 : 1,
                sentBefore ? java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)) : null,
                sentBefore ? sales : null));
        return quote;
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
