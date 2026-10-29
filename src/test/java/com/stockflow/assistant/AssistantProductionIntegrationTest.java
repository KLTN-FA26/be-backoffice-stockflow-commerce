package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Print production through the assistant (ROLE-3): {@code findProductionOrders} end to end with the real security
 * chain, the real permission aspect, real migrations and a scripted model. What it returns is what the production
 * orders screen would show the asker: that screen's permission decides whether, their warehouses decide which.
 *
 * <p>The production orders are inserted as rows, the way a release and the print shop leave them: how they got there
 * is the production module's tests' business. Declares the same mocks as {@link AssistantIntegrationTest}, so the two
 * share one application context.</p>
 */
@SecuredIntegrationTest
class AssistantProductionIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final String TOOL = "findProductionOrders";
    private static final String BLANK = "SOFA-3S-GREY";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String HOLD_NOTE = "Logo bị vỡ nét, chờ khách gửi file mới";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private UUID snapshot;
    private String otherPrefix;
    /**
     * Released to HCM: {@link #held} for a rejected print file, {@link #printing} on the press and overdue, and
     * {@link #finished}, completed after its due date - late, but no longer overdue.
     */
    private String hcmOrder;
    private String held;
    private String printing;
    private String finished;
    /** Released to a warehouse of its own: {@link #elsewhere}, overdue, which a user limited to HCM must not see. */
    private String otherOrder;
    private String elsewhere;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        otherPrefix = "T" + tag.substring(0, 6);
        held = "LSX-IT-" + tag + "-1";
        printing = "LSX-IT-" + tag + "-2";
        elsewhere = "LSX-IT-" + tag + "-3";
        finished = "LSX-IT-" + tag + "-4";
        hcmOrder = orderNumber();
        otherOrder = orderNumber();
        UUID staff = tx.execute(s -> ReferenceRows.user(entityManager));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        tx.executeWithoutResult(s -> {
            UUID other = warehouse(otherPrefix);
            snapshot = snapshot();
            UUID hcm = order(hcmOrder, DemoData.WAREHOUSE_HCM, staff, 33_000_000);
            UUID heldId = production(held, hcm, DemoData.WAREHOUSE_HCM, 20_000, today.plusDays(5));
            jdbc.update("""
                    UPDATE production.production_order SET status = 'ON_HOLD', status_before_hold = 'PENDING_PREPRESS',
                        hold_reason = 'FILE_REJECTED', hold_note = ? WHERE id = ?""", HOLD_NOTE, heldId);
            jdbc.update("""
                    INSERT INTO production.production_order_status_history (id, production_order_id, from_status,
                        to_status, reason, changed_at, changed_by)
                    VALUES (?, ?, 'PENDING_PREPRESS', 'ON_HOLD', ?, ?, ?)""",
                    Identifiers.newId(), heldId, "FILE_REJECTED: " + HOLD_NOTE,
                    Timestamp.from(Instant.now().minusSeconds(3600)), staff);
            UUID printingId = production(printing, hcm, DemoData.WAREHOUSE_HCM, 10_000, today.minusDays(1));
            // 10 000 blanks issued, 6 000 printed: 5 800 good and 200 spoilt (BR-PRD-04).
            jdbc.update("""
                    UPDATE production.production_order SET status = 'PRINTING', prepress_checked_by = ?,
                        prepress_checked_at = NOW() - INTERVAL '2 days', issued_quantity = 10000,
                        printed_quantity = 6000, good_quantity = 5800, scrap_quantity = 200 WHERE id = ?""",
                    staff, printingId);
            UUID finishedId = production(finished, hcm, DemoData.WAREHOUSE_HCM, 3_000, today.minusDays(10));
            jdbc.update("""
                    UPDATE production.production_order SET status = 'COMPLETED', prepress_checked_by = ?,
                        prepress_checked_at = NOW() - INTERVAL '12 days', issued_quantity = 3000,
                        printed_quantity = 3000, good_quantity = 3000, completed_at = NOW() - INTERVAL '1 day'
                    WHERE id = ?""", staff, finishedId);
            UUID away = order(otherOrder, other, staff, 5_000_000);
            UUID elsewhereId = production(elsewhere, away, other, 5_000, today.minusDays(2));
            jdbc.update("""
                    UPDATE production.production_order SET status = 'READY', prepress_checked_by = ?,
                        prepress_checked_at = NOW() WHERE id = ?""", staff, elsewhereId);
        });
    }

    @Test
    @DisplayName("a role holding production-orders:READ gets every LSX of a customer order, and of the order only its "
            + "number")
    void readsTheProductionOfAnOrder() throws Exception {
        assertThat(granted("SALES_STAFF", "production-orders:READ")).as("the seeded grant this case relies on")
                .isTrue();
        UUID seller = staff("SALES_STAFF");
        String nobodys = unusedOrderNumber();
        script(toolUse("toolu_order", arguments("", hcmOrder, "ANY", "ANY", 20)),
                answer("Một LSX đã xong, một LSX đang in, một LSX bị giữ."),
                toolUse("toolu_nobody", arguments("", nobodys, "ANY", "ANY", 20)), answer("Không có đơn này."));
        String conversation = start(Role.SALES_STAFF, seller);

        http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Đơn " + hcmOrder + " in tới đâu rồi, bao giờ xong?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value(TOOL))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));
        http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Còn đơn " + nobodys + " thì sao?"))
                .andExpect(status().isOk());
        assertThat(outcome(conversation, "toolu_nobody")).as("a seller may read orders: told there is no such order")
                .isEqualTo("NOT_FOUND");

        String result = result(conversation, "toolu_order");
        assertThat(JsonPath.<String>read(result, "$.salesOrderNumber")).isEqualTo(hcmOrder);
        assertThat(JsonPath.<List<String>>read(result, "$.productionOrders[*].productionOrderNumber"))
                .as("the earliest due date first").containsExactly(finished, printing, held);
        assertThat(JsonPath.<Integer>read(result, "$.total")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(result, "$.truncated")).isFalse();
        assertThat(JsonPath.<String>read(result, "$.productionOrders[0].status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Boolean>read(result, "$.productionOrders[0].overdue"))
                .as("finished after its due date: late, but nothing is overdue any more").isFalse();
        assertThat(JsonPath.<String>read(result, "$.productionOrders[1].status")).isEqualTo("PRINTING");
        assertThat(JsonPath.<Integer>read(result, "$.productionOrders[1].plannedUnits")).isEqualTo(10_000);
        assertThat(JsonPath.<Integer>read(result, "$.productionOrders[1].goodUnitsPrinted")).isEqualTo(5_800);
        assertThat(JsonPath.<Boolean>read(result, "$.productionOrders[1].overdue")).isTrue();
        assertThat(JsonPath.<String>read(result, "$.productionOrders[2].status")).isEqualTo("ON_HOLD");
        assertThat(JsonPath.<String>read(result, "$.productionOrders[2].holdReason")).isEqualTo("FILE_REJECTED");
        assertThat(JsonPath.<String>read(result, "$.productionOrders[2].warehouseCode")).isEqualTo("HCM");
        assertThat(JsonPath.<String>read(result, "$.productionOrders[2].salesOrderNumber")).isEqualTo(hcmOrder);
        assertThat(JsonPath.<Boolean>read(result, "$.productionOrders[2].overdue")).isFalse();
        // Neither the artwork nor the customer order's own state: QC and purchasing read this without sales-orders.
        assertThat(result).doesNotContain(snapshot.toString(), CHECKSUM, "IN_PRODUCTION", elsewhere);
    }

    @Test
    @DisplayName("a role without production-orders:READ is DENIED, told which permission, and sees no LSX")
    void deniedWithoutTheScreensPermission() throws Exception {
        String role = jdbc.queryForObject("""
                select r.code from identity.app_role r
                where r.code in ('ACCOUNTANT', 'INVENTORY_PLANNER', 'PROCUREMENT_STAFF', 'QC_STAFF', 'SALES_STAFF',
                                 'ORDER_COORDINATOR', 'WAREHOUSE_MANAGER')
                  and exists (select 1 from identity.role_permission rp
                              join identity.permission p on p.id = rp.permission_id
                              where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                  and not exists (select 1 from identity.role_permission rp
                                  join identity.permission p on p.id = rp.permission_id
                                  where rp.role_id = r.id and p.code = 'production-orders:READ')
                order by r.code limit 1""", String.class);
        assertThat(role).as("a role that may ask but may not read production orders").isNotNull();
        UUID asker = staff(role);
        script(toolUse("toolu_denied", arguments("", hcmOrder, "ANY", "ANY", 20)),
                answer("Bạn chưa có quyền xem lệnh sản xuất."));
        String conversation = start(Role.valueOf(role), asker);

        http.asRole(Role.valueOf(role), asker, ask(conversation, "Đơn " + hcmOrder + " in xong chưa?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("DENIED"));

        assertThat(result(conversation, "toolu_denied")).startsWith("NOT_PERMITTED")
                .contains("production-orders:READ")
                .doesNotContain(held, printing);
    }

    @Test
    @DisplayName("a user limited to HCM sees the LSX made in HCM only: another warehouse's order lists none, its LSX "
            + "is not found, and an order number nobody has reads the same")
    void warehouseLimitedUserSeesTheirWarehousesOnly() throws Exception {
        assertThat(granted("QC_STAFF", "production-orders:READ")).as("the seeded grant this case relies on").isTrue();
        assertThat(granted("QC_STAFF", "sales-orders:READ")).as("QC does not read orders under the seeded grants")
                .isFalse();
        UUID qc = staff("QC_STAFF", DemoData.WAREHOUSE_HCM);
        String nobodys = unusedOrderNumber();
        script(toolUse("toolu_late", arguments("", hcmOrder, "OPEN", "OVERDUE", 50)), answer("Một LSX trễ hạn."),
                toolUse("toolu_away", arguments("", otherOrder, "ANY", "ANY", 50)), answer("Không thấy LSX nào."),
                toolUse("toolu_lsx", arguments(elsewhere, "", "ANY", "ANY", 1)), answer("Không tìm thấy."),
                toolUse("toolu_nobody", arguments("", nobodys, "ANY", "ANY", 50)), answer("Không thấy LSX nào."));
        String conversation = start(Role.QC_STAFF, qc);

        for (String question : List.of("LSX nào của đơn " + hcmOrder + " đang trễ hạn?", "Đơn " + otherOrder
                + " in tới đâu rồi?", elsewhere + " đang ở bước nào?", "Đơn " + nobodys + " in tới đâu rồi?")) {
            http.asRole(Role.QC_STAFF, qc, ask(conversation, question)).andExpect(status().isOk());
        }

        assertThat(JsonPath.<List<String>>read(result(conversation, "toolu_late"),
                "$.productionOrders[*].productionOrderNumber"))
                .as("held is not due yet, and finished is done however late").containsExactly(printing);
        String away = result(conversation, "toolu_away");
        assertThat(JsonPath.<List<Object>>read(away, "$.productionOrders")).isEmpty();
        assertThat(JsonPath.<Integer>read(away, "$.total")).isZero();
        assertThat(JsonPath.<String>read(away, "$.note")).contains("HCM");
        assertThat(away).doesNotContain(elsewhere, otherPrefix);
        assertThat(outcome(conversation, "toolu_lsx")).as("the same answer as for a number nobody has")
                .isEqualTo("NOT_FOUND");
        assertThat(result(conversation, "toolu_lsx")).doesNotContain(otherPrefix, otherOrder);
        String nobody = result(conversation, "toolu_nobody");
        assertThat(outcome(conversation, "toolu_nobody")).as("not NOT_FOUND: that would say which orders exist")
                .isEqualTo("OK");
        assertThat(JsonPath.<String>read(nobody, "$.note")).as("worded exactly as for the other warehouse's order")
                .isEqualTo(JsonPath.<String>read(away, "$.note"));
    }

    @Test
    @DisplayName("one LSX by its number: the counts, the hold with its note and where work resumes, and the history")
    void oneProductionOrderByNumber() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        script(toolUse("toolu_held", arguments(held.toLowerCase(Locale.ROOT), "", "ANY", "ANY", 1)),
                answer("Bị giữ vì file in lỗi."),
                toolUse("toolu_press", arguments(printing, "", "ANY", "ANY", 1)), answer("Đang in."),
                toolUse("toolu_done", arguments(finished, "", "ANY", "ANY", 1)), answer("Đã xong."));
        String conversation = start(Role.WAREHOUSE_MANAGER, manager);

        for (String question : List.of("Vì sao " + held + " bị giữ?", printing + " in được bao nhiêu rồi?",
                finished + " xong chưa?")) {
            http.asRole(Role.WAREHOUSE_MANAGER, manager, ask(conversation, question)).andExpect(status().isOk());
        }

        String one = result(conversation, "toolu_held");
        assertThat(JsonPath.<String>read(one, "$.productionOrders[0].productionOrderNumber")).isEqualTo(held);
        // A warehouse manager may not read orders, and the production screens name the order by id only.
        assertThat(one).as("the order number stays out for a caller who may not read orders").doesNotContain(hcmOrder);
        assertThat(JsonPath.<String>read(one, "$.detail.hold.reason")).isEqualTo("FILE_REJECTED");
        assertThat(JsonPath.<String>read(one, "$.detail.hold.note")).isEqualTo(HOLD_NOTE);
        assertThat(JsonPath.<String>read(one, "$.detail.hold.resumesTo")).isEqualTo("PENDING_PREPRESS");
        assertThat(JsonPath.<Object>read(one, "$.detail.hold.since")).isNotNull();
        assertThat(JsonPath.<List<String>>read(one, "$.detail.history[*].to")).containsExactly("ON_HOLD");
        assertThat(JsonPath.<Boolean>read(one, "$.detail.historyTruncated")).isFalse();

        String press = result(conversation, "toolu_press");
        assertThat(JsonPath.<Integer>read(press, "$.detail.blanksIssued")).isEqualTo(10_000);
        assertThat(JsonPath.<Integer>read(press, "$.detail.unitsPrinted")).isEqualTo(6_000);
        assertThat(JsonPath.<Integer>read(press, "$.detail.unitsScrapped")).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(press, "$.detail.blanksStillNeeded"))
                .as("10 000 planned - 5 800 good - 4 000 issued and not printed (BR-PRD-03)").isEqualTo(200);
        // Null fields may be left out of a tool result (spring.jackson.default-property-inclusion): absent or null.
        assertThat(JsonPath.<Map<String, Object>>read(press, "$.detail").get("hold")).isNull();
        assertThat(JsonPath.<Object>read(press, "$.detail.prepressPassedAt")).isNotNull();

        String done = result(conversation, "toolu_done");
        assertThat(JsonPath.<String>read(done, "$.productionOrders[0].status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Object>read(done, "$.detail.completedAt")).isNotNull();
        assertThat(JsonPath.<Map<String, Object>>read(done, "$.detail").get("blanksStillNeeded"))
                .as("a finished order asks the warehouse for nothing").isNull();
        assertThat(one + press + done).doesNotContain(snapshot.toString(), CHECKSUM);
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID warehouse(String prefix) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Production fixture', 'ACTIVE', ?, 'Test road', 'M', 50, 50, 0, NOW())""", id, prefix);
        return id;
    }

    /** A design snapshot of the demo sofa for the demo customer, which every printed line below prints. */
    private UUID snapshot() {
        UUID draft = Identifiers.newId();
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
        jdbc.update("""
                INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                    artifact_manifest)
                VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""", id, draft, CHECKSUM);
        return id;
    }

    /** A paid customer order released to {@code warehouse} and IN_PRODUCTION. */
    private UUID order(String number, UUID warehouse, UUID releasedBy, long total) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, version, created_at, paid_amount,
                                                     paid_in_full_at, warehouse_id, released_at, released_by)
                VALUES (?, ?, ?, ?, 'IN_PRODUCTION', ?, 'VND', NOW(), 0, NOW(), ?, NOW(), ?, NOW(), ?)""",
                id, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), total, total, warehouse, releasedBy);
        return id;
    }

    /** A printed line of {@code order} and its production order, PENDING_PREPRESS as a release creates it. */
    private UUID production(String number, UUID order, UUID warehouse, int planned, LocalDate due) {
        UUID line = Identifiers.newId();
        jdbc.update("""
                INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                 design_checksum) VALUES (?, ?, ?, ?, 1000, 'VND', ?, ?)""",
                line, order, BLANK, planned, snapshot, CHECKSUM);
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku,
                    design_snapshot_id, design_checksum, planned_quantity, due_date, sales_order_id,
                    sales_order_line_id)
                VALUES (?, ?, 'ORDER', 'IN_HOUSE', 'PENDING_PREPRESS', ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, number, warehouse, BLANK, snapshot, CHECKSUM, planned, Date.valueOf(due), order, line);
        return id;
    }

    /** SO-yyyyMMdd-nnnnnn on a day no real order uses, so the sequence never collides with these. */
    private static String orderNumber() {
        return "SO-20991229-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** A well-formed order number on a day no order of any test is given, so it is nobody's. */
    private static String unusedOrderNumber() {
        return "SO-20991201-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private boolean granted(String role, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from identity.role_permission rp
                               join identity.app_role r on r.id = rp.role_id
                               join identity.permission p on p.id = rp.permission_id
                               where r.code = ? and p.code = ?)""", Boolean.class, role, permission));
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

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String result(String conversation, String callId) {
        return jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid "
                + "and call_id = ?", String.class, conversation, callId);
    }

    private String outcome(String conversation, String callId) {
        return jdbc.queryForObject("select outcome from assistant.tool_call where conversation_id = ?::uuid "
                + "and call_id = ?", String.class, conversation, callId);
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private static String arguments(String productionOrderNumber, String salesOrderNumber, String status, String due,
                                    int limit) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("productionOrderNumber", productionOrderNumber).put("salesOrderNumber", salesOrderNumber)
                .put("status", status).put("due", due).put("limit", limit).toString();
    }

    private void script(LanguageModel.Reply first, LanguageModel.Reply... rest) {
        when(model.next(any())).thenReturn(first, rest);
    }

    private static LanguageModel.Reply toolUse(String callId, String arguments) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null,
                List.of(new ToolCall(callId, TOOL, arguments))),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
