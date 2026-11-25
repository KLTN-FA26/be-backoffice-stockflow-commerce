package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A project's quote request over designs not all approved yet (kltn-docs 12 BR-25, BR-22), drawn into a quote by Sales
 * over HTTP with the real security chain: Sales draw only once every line is approved, the customer drops the lines
 * holding it up or deletes their designs, a quoted request is changed no more, and a drop racing a draw leaves one
 * winner and no deadlock (T15b-T15g). The project closes with its open request only once every line is approved
 * (BR-22, 2026-11-25). Rows as the design module leaves them ({@link CustomerSkuRows}); approving through the review
 * itself is {@code DesignProjectIntegrationTest}'s subject.
 */
@SecuredIntegrationTest
class ProjectQuoteRequestDrawIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private StaffFixtures people;
    private UUID sales;
    private UUID account;
    private UUID customer;
    private LocalDate wanted;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        people = new StaffFixtures(jdbc, tx, entityManager);
        sales = people.staff("SALES_STAFF");
        account = people.staff("CUSTOMER");
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        wanted = LocalDate.now(VIETNAM).plusDays(30);
    }

    @Test
    @DisplayName("T15b: three approved, one returned -> the request stays OPEN, reads 3/4 with the reason, and Sales' "
            + "draw is 409 DESIGN_NOT_APPROVED naming the returned design")
    void aReturnedLineHoldsTheQuote() throws Exception {
        UUID project = project();
        List<UUID> approved = List.of(approved(project), approved(project), approved(project));
        UUID returned = returned(approved(project), "Logo lệch");
        UUID request = projectRequest(project, cartons(approved, returned));

        draw(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_NOT_APPROVED"))
                .andExpect(jsonPath("$.details.designIds.length()").value(1))
                .andExpect(jsonPath("$.details.designIds[0]").value(returned.toString()));
        assertThat(requestStatus(request)).isEqualTo("OPEN");
        assertThat(quotes()).isZero();

        JsonNode row = salesRow(request);
        assertThat(row.path("approvedCount").asInt()).isEqualTo(3);
        assertThat(row.path("lineCount").asInt()).isEqualTo(4);
        JsonNode line = line(row, returned);
        assertThat(line.path("designStatus").asText()).isEqualTo("DRAFT");
        assertThat(line.path("returnReason").asText()).isEqualTo("Logo lệch");
        assertThat(line(row, approved.get(0)).path("returnReason").isMissingNode()
                || line(row, approved.get(0)).path("returnReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("T15c: the customer drops the returned line -> 3 lines, Sales are told it is ready, the draw is 201 with "
            + "3 lines; T15f: changing it after the quote -> 409 QUOTE_REQUEST_ALREADY_QUOTED")
    void droppingTheReturnedLineLetsSalesQuoteTheRest() throws Exception {
        UUID project = project();
        List<UUID> approved = List.of(approved(project), approved(project), approved(project));
        UUID returned = returned(approved(project), "Sửa màu");
        UUID request = projectRequest(project, cartons(approved, returned));

        ask(project, request, approved).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(request.toString()))
                .andExpect(jsonPath("$.data.lines.length()").value(3))
                .andExpect(jsonPath("$.data.approvedCount").value(3))
                .andExpect(jsonPath("$.data.lineCount").value(3));
        Await.until("Sales told the request is ready", ASYNC, () -> people.titles(sales).stream()
                .anyMatch(t -> t.contains("YC-" + label(request))));

        JsonNode quote = data(draw(request).andExpect(status().isCreated()));
        assertThat(quote.path("lines")).hasSize(3);
        assertThat(requestStatus(request)).isEqualTo("CLOSED");

        // T15f: the quote answered it; dropping another line now changes nothing.
        ask(project, request, approved.subList(0, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QUOTE_REQUEST_ALREADY_QUOTED"))
                // A quote still DRAFT is Sales' own: the customer reads its id once it is sent (R5-5).
                .andExpect(jsonPath("$.details.quoteId").doesNotExist());
        assertThat(quote.path("status").asText()).isEqualTo("DRAFT");
        assertThat(lineCount(request)).isEqualTo(3);
    }

    @Test
    @DisplayName("T15d: the returned design fixed and approved again -> the draw is 201 with all 4 lines")
    void aReturnedDesignApprovedAgainIsQuotedWithTheRest() throws Exception {
        UUID project = project();
        List<UUID> approved = List.of(approved(project), approved(project), approved(project));
        UUID returned = returned(approved(project), "Sửa màu");
        UUID request = projectRequest(project, cartons(approved, returned));
        draw(request).andExpect(status().isConflict());

        // Fixed, submitted and approved again, as the review leaves it.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET status = 'APPROVED', review_notes = "
                + "NULL, reviewed_at = NOW() WHERE id = ?", returned));

        JsonNode quote = data(draw(request).andExpect(status().isCreated()));
        assertThat(quote.path("lines")).hasSize(4);
        assertThat(requestStatus(request)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("T15e: deleting a design on the request drops its line; deleting the last one closes the request")
    void deletingADesignDropsItsLine() throws Exception {
        UUID project = project();
        UUID kept = approved(project);
        UUID returned = returned(approved(project), "Sửa màu");
        UUID request = projectRequest(project, Map.of(kept, 10, returned, 20));

        deleteDesign(returned).andExpect(status().isOk());
        assertThat(lineDesigns(request)).containsExactly(kept);
        assertThat(requestStatus(request)).isEqualTo("OPEN");
        Await.until("Sales told the request is ready", ASYNC, () -> people.titles(sales).stream()
                .anyMatch(t -> t.contains("YC-" + label(request))));

        UUID other = project();
        UUID only = returned(approved(other), "Sửa màu");
        UUID emptied = projectRequest(other, Map.of(only, 10));
        deleteDesign(only).andExpect(status().isOk());
        assertThat(lineCount(emptied)).isZero();
        assertThat(jdbc.queryForMap("SELECT status, closed_by, close_reason FROM design.design_project_quote_request "
                + "WHERE id = ?", emptied)).containsEntry("status", "CLOSED").containsEntry("closed_by", null)
                .containsEntry("close_reason", null);
    }

    @Test
    @DisplayName("T15g: the customer drops a line while Sales draw the quote -> both answer, no deadlock, exactly one "
            + "wins: the quote has the lines the request kept")
    void aDropRacingADrawLeavesOneWinner() throws Exception {
        String customerToken = http.token(Role.CUSTOMER, account);
        String salesToken = http.token(Role.SALES_STAFF, sales);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 4; round++) {
                UUID project = project();
                UUID first = approved(project);
                UUID second = approved(project);
                UUID request = projectRequest(project, Map.of(first, 10, second, 20));
                CountDownLatch start = new CountDownLatch(1);
                Future<MockHttpServletResponse> drop = pool.submit(race(start, () -> mvc.perform(post(
                        "/api/v1/design-projects/" + project + "/quote-requests").header("Authorization", customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(request, List.of(first))))));
                Future<MockHttpServletResponse> quote = pool.submit(race(start, () -> mvc.perform(post(
                        "/api/v1/design-project-quote-requests/" + request + "/quote")
                        .header("Authorization", salesToken))));
                start.countDown();
                MockHttpServletResponse dropped = drop.get(60, TimeUnit.SECONDS);
                MockHttpServletResponse drawn = quote.get(60, TimeUnit.SECONDS);

                assertThat(drawn.getStatus()).as("round %d: the draw, %s", round, drawn.getContentAsString())
                        .isEqualTo(201);
                JsonNode lines = json.readTree(drawn.getContentAsString(StandardCharsets.UTF_8)).path("data")
                        .path("lines");
                if (dropped.getStatus() == 200) {
                    assertThat(lines).as("round %d: the drop first", round).hasSize(1);
                } else {
                    assertThat(dropped.getStatus()).as("round %d: %s", round, dropped.getContentAsString())
                            .isEqualTo(409);
                    assertThat(json.readTree(dropped.getContentAsString(StandardCharsets.UTF_8)).path("errorCode")
                            .asText()).isEqualTo("QUOTE_REQUEST_ALREADY_QUOTED");
                    assertThat(lines).as("round %d: the draw first", round).hasSize(2);
                }
                assertThat(lineCount(request)).as("the request keeps what was quoted").isEqualTo(lines.size());
                assertThat(requestStatus(request)).isEqualTo("CLOSED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("archive (BR-22): an open request whose lines are all approved -> 200, the project ARCHIVED, the request "
            + "still open and quoted after")
    void aProjectClosesWithItsApprovedOpenRequest() throws Exception {
        UUID project = project();
        UUID first = approved(project);
        UUID second = approved(project);
        UUID request = projectRequest(project, Map.of(first, 10, second, 20));

        archive(project).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        assertThat(requestStatus(request)).isEqualTo("OPEN");
        JsonNode quote = data(draw(request).andExpect(status().isCreated()));
        assertThat(quote.path("lines")).hasSize(2);
    }

    @Test
    @DisplayName("archive (BR-22): an open request with a returned line -> 409 PROJECT_HAS_UNAPPROVED_QUOTE_REQUEST, "
            + "nothing changed; with a line waiting for review -> 409 DESIGN_PROJECT_HAS_PENDING, as before")
    void aProjectDoesNotCloseWhileItsOpenRequestWaitsOnADesign() throws Exception {
        UUID project = project();
        UUID kept = approved(project);
        UUID returned = returned(approved(project), "Sửa màu");
        UUID request = projectRequest(project, Map.of(kept, 10, returned, 20));
        long version = projectVersion(project);

        archive(project).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROJECT_HAS_UNAPPROVED_QUOTE_REQUEST"))
                .andExpect(jsonPath("$.details.requestId").value(request.toString()))
                .andExpect(jsonPath("$.details.designIds.length()").value(1))
                .andExpect(jsonPath("$.details.designIds[0]").value(returned.toString()));
        assertThat(projectStatus(project)).isEqualTo("ACTIVE");
        assertThat(projectVersion(project)).isEqualTo(version);
        assertThat(requestStatus(request)).isEqualTo("OPEN");
        assertThat(lineCount(request)).isEqualTo(2);

        UUID waiting = Identifiers.newId();
        UUID other = project();
        UUID pending = approved(other);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET status = 'PENDING_REVIEW', "
                + "review_requested_at = NOW() WHERE id = ?", pending));
        projectRequest(other, Map.of(pending, 10), waiting);
        archive(other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_PROJECT_HAS_PENDING"));
        assertThat(projectStatus(other)).isEqualTo("ACTIVE");
        assertThat(requestStatus(waiting)).isEqualTo("OPEN");

        // Dropped from the request, the returned design no longer holds the project open.
        ask(project, request, List.of(kept)).andExpect(status().isOk());
        archive(project).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("archive (BR-22): only a closed or a quoted request, whatever its lines -> 200")
    void aProjectWithOnlyClosedRequestsCloses() throws Exception {
        UUID project = project();
        UUID approvedOne = approved(project);
        UUID returned = returned(approved(project), "Sửa màu");
        UUID closed = projectRequest(project, Map.of(approvedOne, 10, returned, 20));
        http.asRole(Role.SALES_STAFF, sales, post("/api/v1/design-project-quote-requests/" + closed + "/closure")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Khách đặt qua kênh khác\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeReason").value("Khách đặt qua kênh khác"));
        UUID quoted = projectRequest(project, Map.of(approvedOne, 5));
        draw(quoted).andExpect(status().isCreated());

        archive(project).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("D: Sales close a request with a reason the customer reads; no reason -> 400, the request open")
    void salesCloseARequestWithAReason() throws Exception {
        UUID project = project();
        UUID returned = returned(approved(project), "Sửa màu");
        UUID request = projectRequest(project, Map.of(returned, 10));

        closure(request, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        closure(request, "{\"reason\":\"   \"}").andExpect(status().isBadRequest());
        assertThat(requestStatus(request)).isEqualTo("OPEN");

        closure(request, "{\"reason\":\"Thiết kế bị trả không được sửa\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedOutcome").value("CLOSED_BY_STAFF"))
                .andExpect(jsonPath("$.data.closeReason").value("Thiết kế bị trả không được sửa"));
        assertThat(jdbc.queryForMap("SELECT closed_by, close_reason FROM design.design_project_quote_request WHERE "
                + "id = ?", request)).containsEntry("closed_by", sales)
                .containsEntry("close_reason", "Thiết kế bị trả không được sửa");
        // The customer reads why on their project.
        http.asCustomer(account, get("/api/v1/design-projects/" + project + "/quote-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].closeReason").value("Thiết kế bị trả không được sửa"));
        // Without design-quote-requests:UPDATE, nobody closes one.
        UUID elsewhere = project();
        UUID other = projectRequest(elsewhere, Map.of(approved(elsewhere), 1));
        http.asCustomer(account, post("/api/v1/design-project-quote-requests/" + other + "/closure")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- helpers

    private static Callable<MockHttpServletResponse> race(CountDownLatch start, ThrowingSupplier<ResultActions> call) {
        return () -> {
            start.await();
            return call.get().andReturn().getResponse();
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static String label(UUID request) {
        return request.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static Map<UUID, Integer> cartons(List<UUID> approved, UUID returned) {
        Map<UUID, Integer> cartons = new LinkedHashMap<>();
        approved.forEach(design -> cartons.put(design, 10 + cartons.size()));
        cartons.put(returned, 30);
        return cartons;
    }

    private String body(UUID request, List<UUID> designs) {
        return "{\"lines\":[" + designs.stream().map(d -> "{\"designId\":\"" + d + "\",\"cartons\":10}")
                .collect(Collectors.joining(",")) + "],\"desiredDeliveryDate\":\"" + wanted + "\",\"requestId\":\""
                + request + "\"}";
    }

    private ResultActions ask(UUID project, UUID request, List<UUID> designs) throws Exception {
        return http.asCustomer(account, post("/api/v1/design-projects/" + project + "/quote-requests")
                .contentType(MediaType.APPLICATION_JSON).content(body(request, designs)));
    }

    private ResultActions draw(UUID request) throws Exception {
        return http.asRole(Role.SALES_STAFF, sales, post("/api/v1/design-project-quote-requests/" + request + "/quote"));
    }

    private ResultActions closure(UUID request, String body) throws Exception {
        return http.asRole(Role.SALES_STAFF, sales, post("/api/v1/design-project-quote-requests/" + request
                + "/closure").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions archive(UUID project) throws Exception {
        return http.asCustomer(account, post("/api/v1/design-projects/" + project + "/archive")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + projectVersion(project) + "}"));
    }

    private ResultActions deleteDesign(UUID design) throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM design.design_draft WHERE id = ?", Long.class, design);
        return http.asCustomer(account, post("/api/v1/designs/" + design + "/archive")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
    }

    private JsonNode salesRow(UUID request) throws Exception {
        JsonNode rows = data(http.asRole(Role.SALES_STAFF, sales, get("/api/v1/design-project-quote-requests")
                .param("customerId", customer.toString()).param("size", "50")).andExpect(status().isOk()))
                .path("items");
        for (JsonNode row : rows) {
            if (request.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row " + request + " in " + rows);
    }

    private static JsonNode line(JsonNode row, UUID design) {
        for (JsonNode line : row.path("lines")) {
            if (design.toString().equals(line.path("designId").asText())) {
                return line;
            }
        }
        throw new AssertionError("no line " + design + " in " + row);
    }

    private String requestStatus(UUID request) {
        return jdbc.queryForObject("SELECT status FROM design.design_project_quote_request WHERE id = ?", String.class,
                request);
    }

    private long lineCount(UUID request) {
        return jdbc.queryForObject("SELECT count(*) FROM design.design_project_quote_request_line WHERE request_id = ?",
                Long.class, request);
    }

    private List<UUID> lineDesigns(UUID request) {
        return jdbc.queryForList("SELECT draft_id FROM design.design_project_quote_request_line WHERE request_id = ?",
                UUID.class, request);
    }

    private long quotes() {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id = ?", Long.class, customer);
    }

    private String projectStatus(UUID project) {
        return jdbc.queryForObject("SELECT status FROM design.design_project WHERE id = ?", String.class, project);
    }

    private long projectVersion(UUID project) {
        return jdbc.queryForObject("SELECT version FROM design.design_project WHERE id = ?", Long.class, project);
    }

    private UUID project() {
        UUID project = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_project (id, customer_id, owner_user_id, name, status, created_at)
                VALUES (?, ?, ?, 'Bo nhan dien quan', 'ACTIVE', NOW())""", project, customer, account));
        return project;
    }

    /** An approved design of the customer's, with its own SKU, owned by the account and in {@code project}. */
    private UUID approved(UUID project) {
        UUID design = CustomerSkuRows.designOf(jdbc, CustomerSkuRows.issueApproved(jdbc, tx, entityManager, customer,
                50));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET project_id = ?, owner_user_id = ? "
                + "WHERE id = ?", project, account, design));
        return design;
    }

    /** {@code design} returned for changes by the review, as it leaves it: a draft again, with the reason. */
    private UUID returned(UUID design, String reason) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET status = 'DRAFT', review_notes = ?, "
                + "reviewed_at = NOW() WHERE id = ?", reason, design));
        return design;
    }

    private UUID projectRequest(UUID project, Map<UUID, Integer> cartons) {
        UUID id = Identifiers.newId();
        projectRequest(project, cartons, id);
        return id;
    }

    private void projectRequest(UUID project, Map<UUID, Integer> cartons, UUID id) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_project_quote_request (id, project_id, requested_by,
                        desired_delivery_date, status, created_at)
                    VALUES (?, ?, ?, ?, 'OPEN', NOW())""", id, project, account, wanted);
            new ArrayList<>(cartons.entrySet()).forEach(e -> jdbc.update("INSERT INTO "
                    + "design.design_project_quote_request_line (request_id, draft_id, cartons) VALUES (?, ?, ?)", id,
                    e.getKey(), e.getValue()));
        });
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
