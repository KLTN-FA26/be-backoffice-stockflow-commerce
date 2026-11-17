package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.OrderService;
import com.stockflow.support.AssistantStock;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/assistant/today} with the real security chain, the real permission aspect, the real tools and
 * the seeded roles (design §9): each role's card holds the sections its own tools answer and nothing else, and a tool
 * that fails costs its own sections and nothing more.
 *
 * <p>Its own application context, and so its own database ({@code PostgresContainer}), for one reason: {@link #orders}
 * spies on the order module so that one test can make {@code findOrders} fail. A spy calls the real bean in every other
 * test.</p>
 *
 * <p>The rows are inserted as the modules leave them. The card lists the first rows of tables the demo seed and the
 * other tests here fill too, so the fixtures sort first by construction: orders placed in the 2190s.</p>
 */
@SecuredIntegrationTest
class AssistantTodayIntegrationTest {

    private static final String TODAY = "/api/v1/assistant/today";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CONTACT_NAME = "Trần Thị Liên Hệ";
    private static final String CONTACT_EMAIL = "lienhe@khach-hang.vn";
    private static final String CONTACT_PHONE = "0987654321";
    private static final String ADDRESS = "99 Đường Thử Nghiệm";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @MockitoSpyBean OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private AssistantStock fixtures;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        fixtures = new AssistantStock(jdbc, tx, entityManager);
    }

    @Test
    @DisplayName("Sales get the held orders, SO- numbers with the hold reason and a masked note, the orders waiting for "
            + "the signing instalment and those waiting for stock; never a contact, no model, no question counted")
    void sales() throws Exception {
        String sku = fixtures.sku();
        String held = order(sku, "ON_HOLD", "PAYMENT", "Khách hẹn chuyển khoản, gọi 0903 123 456");
        String waiting = order(sku, "PENDING_PAYMENT", null, null);
        String awaitingStock = order(sku, "AWAITING_STOCK", null, null);
        UUID sales = fixtures.staff("SALES_STAFF");

        JsonNode card = card(Role.SALES_STAFF, sales);

        assertThat(keys(card)).startsWith("heldOrders", "awaitingPayment", "awaitingStock");
        assertThat(card.path("generatedAt").asText()).isNotBlank();
        JsonNode heldOrders = section(card, "heldOrders");
        assertThat(heldOrders.path("tool").asText()).isEqualTo("findOrders");
        assertThat(heldOrders.path("outcome").asText()).isEqualTo("OK");
        assertThat(column(heldOrders, "orderNumber")).isNotEmpty().contains(held)
                .allSatisfy(number -> assertThat(number).startsWith("SO-"));
        JsonNode order = row(heldOrders, "orderNumber", held);
        assertThat(order.path("hold.reason").asText()).isEqualTo("PAYMENT");
        assertThat(order.path("hold.statusBeforeHold").asText()).isEqualTo("CONFIRMED");
        assertThat(order.path("hold.note").asText()).contains("[phone]").doesNotContain("0903 123 456");
        assertThat(column(section(card, "awaitingPayment"), "orderNumber")).contains(waiting);
        assertThat(section(card, "awaitingPayment").path("note").asText()).as("what waiting for payment leaves out")
                .isNotBlank();
        assertThat(column(section(card, "awaitingStock"), "orderNumber")).contains(awaitingStock);
        assertThat(card.toString()).doesNotContain(CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE, ADDRESS);

        verify(model, never()).next(any());
        assertThat(jdbc.queryForObject("select count(*) from assistant.conversation where owner_user_id = ?",
                Long.class, sales)).as("no conversation").isZero();
        assertThat(jdbc.queryForList("""
                SELECT tool_name FROM assistant.tool_call
                WHERE channel = 'TODAY' AND user_id = ? AND conversation_id IS NULL ORDER BY id""", String.class,
                sales)).as("every read recorded, as the user's").contains("findOrders");
        http.asRole(Role.SALES_STAFF, sales, get("/api/v1/assistant/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.askedToday").value(0));
    }

    @Test
    @DisplayName("a tool that fails leaves its sections FAILED with a note and no rows, and the rest of the card still "
            + "answers")
    void aFailingToolCostsOnlyItsSections() throws Exception {
        doThrow(new IllegalStateException("order store unreachable")).when(orders).list(any());
        UUID admin = fixtures.staff("SYSTEM_ADMIN");

        JsonNode card = card(Role.SYSTEM_ADMIN, admin);

        assertThat(keys(card)).containsExactly("heldOrders", "awaitingPayment", "awaitingStock", "receiptsToPutAway");
        for (String key : List.of("heldOrders", "awaitingPayment", "awaitingStock")) {
            JsonNode failed = section(card, key);
            assertThat(failed.path("outcome").asText()).as(key).isEqualTo("FAILED");
            assertThat(failed.path("table").isNull()).as("no rows").isTrue();
            assertThat(failed.path("note").asText()).isNotBlank();
        }
        assertThat(section(card, "receiptsToPutAway").path("outcome").asText()).isEqualTo("OK");
        assertThat(card.toString()).doesNotContain("order store unreachable");
    }

    @Test
    @DisplayName("one card a minute per user: a second is 429 RATE_LIMITED, and another user's card is not held up")
    void oneCardAMinute() throws Exception {
        UUID sales = fixtures.staff("SALES_STAFF");
        UUID colleague = fixtures.staff("SALES_STAFF");

        http.asRole(Role.SALES_STAFF, sales, get(TODAY)).andExpect(status().isOk());
        http.asRole(Role.SALES_STAFF, sales, get(TODAY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        http.asRole(Role.SALES_STAFF, colleague, get(TODAY)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a portal customer gets no card")
    void customersAreRefused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, get(TODAY)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    private JsonNode card(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, get(TODAY)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private static List<String> keys(JsonNode card) {
        List<String> keys = new ArrayList<>();
        card.path("sections").forEach(section -> keys.add(section.path("key").asText()));
        return keys;
    }

    private static JsonNode section(JsonNode card, String key) {
        for (JsonNode section : card.path("sections")) {
            if (section.path("key").asText().equals(key)) {
                return section;
            }
        }
        throw new AssertionError("no section " + key + " in " + keys(card));
    }

    private static List<String> column(JsonNode section, String column) {
        List<String> values = new ArrayList<>();
        section.path("table").path("rows").forEach(row -> values.add(row.path(column).asText()));
        return values;
    }

    private static JsonNode row(JsonNode section, String column, String value) {
        for (JsonNode row : section.path("table").path("rows")) {
            if (row.path(column).asText().equals(value)) {
                return row;
            }
        }
        throw new AssertionError("no row with " + column + " " + value + " in " + section);
    }

    /**
     * One order of {@code sku} in {@code status}, its contract signed, placed in the 2190s so that it is among the
     * newest, with a contact block and a shipping address that must never reach the card.
     */
    private String order(String sku, String status, String holdReason, String holdNote) {
        UUID releasedBy = tx.execute(s -> ReferenceRows.user(entityManager));
        LocalDate placedOn = LocalDate.of(2190, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3_000));
        String number = "SO-" + placedOn.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + (1_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        Timestamp placed = Timestamp.from(placedOn.atTime(9, 0).atZone(VIETNAM).toInstant());
        tx.executeWithoutResult(s -> {
            // Written in its status where the status table allows no edge into it; a hold is put on a confirmed one, and
            // AWAITING_STOCK (which needs a signed contract and a chosen shipping warehouse) is reached from CONFIRMED.
            boolean viaConfirmed = holdReason != null || "AWAITING_STOCK".equals(status);
            UUID id = ReferenceRows.orderWithLine(entityManager, sku, viaConfirmed ? "CONFIRMED" : status,
                    DemoData.CUSTOMER_ID, releasedBy, DemoData.WAREHOUSE_HCM);
            entityManager.flush();
            jdbc.update("""
                    UPDATE ordering.customer_order SET order_number = ?, status = ?, placed_at = ?, hold_reason = ?,
                        status_before_hold = ?, hold_note = ?, contract_signed_at = NOW(), warehouse_id = ?,
                        shipping_warehouse_id = ?, shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = ?,
                        contact_name = ?, contact_email = ?, contact_phone = ?, shipping_recipient_name = ?,
                        shipping_phone = ?, shipping_line1 = ?, shipping_ward_code = '26734',
                        shipping_ward_name = 'Phường Bến Nghé', shipping_province_code = '79',
                        shipping_province_name = 'Thành phố Hồ Chí Minh', shipping_country_code = 'VN'
                    WHERE id = ?""",
                    number, status, placed, holdReason, holdReason == null ? null : "CONFIRMED", holdNote,
                    DemoData.WAREHOUSE_HCM, DemoData.WAREHOUSE_HCM, releasedBy, CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE, CONTACT_NAME, CONTACT_PHONE,
                    ADDRESS, id);
        });
        return number;
    }
}
