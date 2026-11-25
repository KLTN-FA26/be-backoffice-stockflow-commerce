package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.service.DocumentCards;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The back office's request inbox draws a quote from a customer's quote request without the chat (kltn-docs 14 BR-22,
 * 16 BR-11): {@code POST /design-quote-requests/{id}/quote} and {@code POST /design-project-quote-requests/{id}/quote},
 * over HTTP with the real security chain, on rows as the design module leaves them ({@link CustomerSkuRows}). The
 * same operation as the chat card's CREATE_QUOTE ({@code ChatCardsIntegrationTest}): one quote per request, the request
 * closed, and the request's card in the chat answered by it after commit.
 */
@SecuredIntegrationTest
class QuoteFromRequestIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired DocumentCards documentCards;

    private SecuredMvc http;
    private UUID sales;
    private UUID account;
    private UUID customer;
    private LocalDate wanted;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        sales = people.staff("SALES_STAFF");
        account = people.staff("CUSTOMER");
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        wanted = LocalDate.now(VIETNAM).plusDays(30);
    }

    @Test
    @DisplayName("a design's request -> 201 DRAFT of its customer SKU at the cartons and date asked, the request closed, "
            + "its chat card answered; again -> 200 the same quote")
    void designRequest() throws Exception {
        CustomerSkuRows.Issued issued = CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer, 50);
        UUID design = CustomerSkuRows.designOf(jdbc, issued);
        UUID request = designRequest(design, "OPEN");
        // The request's card, as the chat's listener posts it when the customer asks.
        documentCards.designQuoteRequested(request);
        UUID card = jdbc.queryForObject("SELECT id FROM chat.message WHERE card_kind = 'QUOTE_REQUEST' AND"
                + " card_payload ->> 'requestId' = ?", UUID.class, request.toString());

        JsonNode quote = data(draw("design-quote-requests", request, Role.SALES_STAFF, sales)
                .andExpect(status().isCreated()));
        assertThat(quote.path("status").asText()).isEqualTo("DRAFT");
        assertThat(quote.path("customerId").asText()).isEqualTo(customer.toString());
        assertThat(quote.path("requestedDeliveryDate").asText()).isEqualTo(wanted.toString());
        assertThat(quote.path("lines")).hasSize(1);
        JsonNode line = quote.path("lines").get(0);
        assertThat(line.path("sku").asText()).isEqualTo(issued.sku());
        assertThat(line.path("cartons").asInt()).as("the cartons asked, a suggestion").isEqualTo(40);
        assertThat(line.path("units").asInt()).isEqualTo(2000);
        UUID quoteId = UUID.fromString(quote.path("id").asText());
        assertThat(jdbc.queryForMap("SELECT status, closed_by FROM design.design_quote_request WHERE id = ?", request))
                .containsEntry("status", "CLOSED").containsEntry("closed_by", sales);

        // The chat follows after commit: the card shows the quote, acted on by Sales, the quote attached to its
        // conversation - as if Sales had pressed CREATE_QUOTE on it.
        Await.until("the request's card answered", ASYNC, () -> "ACTED".equals(jdbc.queryForObject(
                "SELECT card_state FROM chat.message WHERE id = ?", String.class, card)));
        Map<String, Object> answered = jdbc.queryForMap("SELECT conversation_id, card_payload ->> 'quoteId' AS quote,"
                + " card_payload ->> 'requestStatus' AS request FROM chat.message WHERE id = ?", card);
        assertThat(answered).containsEntry("quote", quoteId.toString()).containsEntry("request", "CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_context WHERE conversation_id = ? AND"
                + " quote_id = ?", Long.class, answered.get("conversation_id"), quoteId)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT type FROM chat.conversation_event WHERE conversation_id = ? AND"
                + " message_id = ?", String.class, answered.get("conversation_id"), card)).contains("CARD_ACTION");

        // Pressed again (or from the chat card): the same quote, nothing new drawn up.
        JsonNode again = data(draw("design-quote-requests", request, Role.SALES_STAFF, sales)
                .andExpect(status().isOk()));
        assertThat(again.path("id").asText()).isEqualTo(quoteId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id = ?", Long.class,
                customer)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a project's request -> one DRAFT of every design; with a design not approved -> 409 DESIGN_NOT_APPROVED")
    void projectRequest() throws Exception {
        CustomerSkuRows.Issued first = CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer, 50);
        CustomerSkuRows.Issued second = CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer, 100);
        UUID project = project();
        UUID approvedA = inProject(CustomerSkuRows.designOf(jdbc, first), project);
        UUID approvedB = inProject(CustomerSkuRows.designOf(jdbc, second), project);
        UUID request = projectRequest(project, Map.of(approvedA, 20, approvedB, 30));

        JsonNode quote = data(draw("design-project-quote-requests", request, Role.SALES_STAFF, sales)
                .andExpect(status().isCreated()));
        assertThat(quote.path("lines")).extracting(l -> l.path("sku").asText() + "x" + l.path("cartons").asInt())
                .containsExactlyInAnyOrder(first.sku() + "x20", second.sku() + "x30");
        assertThat(jdbc.queryForObject("SELECT status FROM design.design_project_quote_request WHERE id = ?",
                String.class, request)).isEqualTo("CLOSED");
        data(draw("design-project-quote-requests", request, Role.SALES_STAFF, sales).andExpect(status().isOk()));

        // Another project, one of whose designs still waits for review: nothing is drawn up, the request stays open.
        UUID other = project();
        UUID approved = inProject(CustomerSkuRows.designOf(jdbc,
                CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer, 50)), other);
        UUID pending = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_draft (id, customer_id, product_id, owner_user_id, status, created_at,
                    current_artifacts, name, project_id)
                VALUES (?, ?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb, 'Coc logo do', ?)""", pending, customer,
                DemoData.PRODUCT_CUP, account, other));
        UUID refused = projectRequest(other, Map.of(approved, 20, pending, 20));
        draw("design-project-quote-requests", refused, Role.SALES_STAFF, sales)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_APPROVED"));
        assertThat(jdbc.queryForObject("SELECT status FROM design.design_project_quote_request WHERE id = ?",
                String.class, refused)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id = ?", Long.class,
                customer)).as("only the first project's").isEqualTo(1L);
    }

    @Test
    @DisplayName("a request closed without a quote -> 409 DESIGN_QUOTE_REQUEST_CLOSED; none -> 404; no sales-quotes:CREATE "
            + "-> 403")
    void refusals() throws Exception {
        UUID design = CustomerSkuRows.designOf(jdbc, CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer,
                50));
        UUID closed = designRequest(design, "CLOSED");
        draw("design-quote-requests", closed, Role.SALES_STAFF, sales).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_QUOTE_REQUEST_CLOSED"));
        draw("design-quote-requests", Identifiers.newId(), Role.SALES_STAFF, sales).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        UUID open = designRequest(CustomerSkuRows.designOf(jdbc, CustomerSkuRows.issueApproved(jdbc, tx, entityManager,
                customer, 50)), "OPEN");
        UUID warehouse = new StaffFixtures(jdbc, tx, entityManager).staff("WAREHOUSE_STAFF");
        draw("design-quote-requests", open, Role.WAREHOUSE_STAFF, warehouse).andExpect(status().isForbidden());
        draw("design-quote-requests", open, Role.CUSTOMER, account).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM design.design_quote_request WHERE id = ?", String.class,
                open)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id = ?", Long.class,
                customer)).isZero();
    }

    @Test
    @DisplayName("R5-5: a closed request says how it ended - QUOTED with its quote (to the customer once sent), or "
            + "CLOSED_BY_STAFF; an open one says nothing")
    void closedOutcome() throws Exception {
        UUID design = CustomerSkuRows.designOf(jdbc, CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer,
                50));
        UUID quoted = designRequest(design, "OPEN");
        JsonNode quote = data(draw("design-quote-requests", quoted, Role.SALES_STAFF, sales)
                .andExpect(status().isCreated()));
        UUID closedByStaff = designRequest(design, "CLOSED");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_quote_request SET closed_by = ? WHERE id = ?",
                sales, closedByStaff));
        UUID open = designRequest(CustomerSkuRows.designOf(jdbc, CustomerSkuRows.issueApproved(jdbc, tx,
                entityManager, customer, 50)), "OPEN");

        JsonNode rows = data(http.asRole(Role.SALES_STAFF, sales, get("/api/v1/design-quote-requests")
                .param("customerId", customer.toString()).param("size", "50"))
                .andExpect(status().isOk())).path("items");
        assertThat(row(rows, quoted).path("closedOutcome").asText()).isEqualTo("QUOTED");
        assertThat(row(rows, quoted).path("quoteId").asText()).isEqualTo(quote.path("id").asText());
        assertThat(row(rows, quoted).path("quoteNumber").asText()).isEqualTo(quote.path("quoteNumber").asText());
        assertThat(row(rows, closedByStaff).path("closedOutcome").asText()).isEqualTo("CLOSED_BY_STAFF");
        assertThat(row(rows, closedByStaff).has("quoteId")).isFalse();
        assertThat(row(rows, open).has("closedOutcome")).isFalse();

        // The customer reads the outcome on their design, but not a quote Sales have not sent yet.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET owner_user_id = ? WHERE id = ?",
                account, design));
        JsonNode mine = data(http.asCustomer(account, get("/api/v1/designs/" + design + "/quote-requests"))
                .andExpect(status().isOk()));
        assertThat(row(mine, quoted).path("closedOutcome").asText()).isEqualTo("QUOTED");
        assertThat(row(mine, quoted).has("quoteId")).as("still Sales' draft").isFalse();

        // A project's request answered by a quote reads the same.
        UUID project = project();
        UUID projectRequest = projectRequest(project, Map.of(inProject(CustomerSkuRows.designOf(jdbc,
                CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer, 50)), project), 20));
        JsonNode projectQuote = data(draw("design-project-quote-requests", projectRequest, Role.SALES_STAFF, sales)
                .andExpect(status().isCreated()));
        JsonNode projectRows = data(http.asRole(Role.SALES_STAFF, sales, get("/api/v1/design-project-quote-requests")
                .param("customerId", customer.toString())).andExpect(status().isOk())).path("items");
        assertThat(row(projectRows, projectRequest).path("closedOutcome").asText()).isEqualTo("QUOTED");
        assertThat(row(projectRows, projectRequest).path("quoteId").asText())
                .isEqualTo(projectQuote.path("id").asText());
    }

    @Test
    @DisplayName("LC-3: the request's draft cancelled before it was sent -> drawing again is 409 DESIGN_QUOTE_REQUEST_CLOSED "
            + "with the way out, and the request reads CLOSED_BY_STAFF; a sent quote still reads QUOTED")
    void aDraftCancelledUnsentClosesTheRequest() throws Exception {
        UUID design = CustomerSkuRows.designOf(jdbc, CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer,
                50));
        UUID request = designRequest(design, "OPEN");
        JsonNode drawn = data(draw("design-quote-requests", request, Role.SALES_STAFF, sales)
                .andExpect(status().isCreated()));
        String quoteNumber = drawn.path("quoteNumber").asText();
        http.asRole(Role.SALES_STAFF, sales, post("/api/v1/quotes/" + drawn.path("id").asText() + "/cancellation")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"version\":" + drawn.path("version").asLong() + ",\"reason\":\"Khach thoi mua\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));

        draw("design-quote-requests", request, Role.SALES_STAFF, sales).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_QUOTE_REQUEST_CLOSED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(quoteNumber)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id = ?", Long.class,
                customer)).as("nothing new drawn up").isEqualTo(1L);

        JsonNode rows = data(http.asRole(Role.SALES_STAFF, sales, get("/api/v1/design-quote-requests")
                .param("customerId", customer.toString()).param("size", "50"))
                .andExpect(status().isOk())).path("items");
        assertThat(row(rows, request).path("closedOutcome").asText()).isEqualTo("CLOSED_BY_STAFF");
        assertThat(row(rows, request).has("quoteId")).isFalse();
    }

    // ---- helpers

    private static JsonNode row(JsonNode rows, UUID id) {
        for (JsonNode row : rows) {
            if (id.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row " + id + " in " + rows);
    }

    private UUID designRequest(UUID design, String status) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_quote_request (id, draft_id, requested_by, cartons, desired_delivery_date,
                    status, closed_at, created_at)
                VALUES (?, ?, ?, 40, ?, ?, CASE WHEN ? = 'CLOSED' THEN NOW() END, NOW())""", id, design, account,
                wanted, status, status));
        return id;
    }

    private UUID project() {
        UUID project = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_project (id, customer_id, owner_user_id, name, status, created_at)
                VALUES (?, ?, ?, 'Bo nhan dien quan', 'ACTIVE', NOW())""", project, customer, account));
        return project;
    }

    private UUID inProject(UUID design, UUID project) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET project_id = ? WHERE id = ?", project,
                design));
        return design;
    }

    private UUID projectRequest(UUID project, Map<UUID, Integer> cartons) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_project_quote_request (id, project_id, requested_by,
                        desired_delivery_date, status, created_at)
                    VALUES (?, ?, ?, ?, 'OPEN', NOW())""", id, project, account, wanted);
            cartons.forEach((design, n) -> jdbc.update("INSERT INTO design.design_project_quote_request_line"
                    + " (request_id, draft_id, cartons) VALUES (?, ?, ?)", id, design, n));
        });
        return id;
    }

    private ResultActions draw(String path, UUID request, Role role, UUID user) throws Exception {
        return http.asRole(role, user, post("/api/v1/" + path + "/" + request + "/quote"));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
