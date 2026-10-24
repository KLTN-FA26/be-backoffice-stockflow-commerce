package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.service.AssistantTool;
import com.stockflow.assistant.internal.service.AssistantTools;
import com.stockflow.assistant.internal.service.ToolArguments;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant end to end with the real security chain, the real permission aspect, real migrations and a scripted
 * model (design §5.4): what a tool may return is decided by the asker's own permissions and warehouses, a
 * conversation is its owner's alone, and every tool carries the guard of the screen it mirrors.
 */
@SecuredIntegrationTest
class AssistantIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final String BIN = "HCM-A01-1-B";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired AssistantTools tools;
    @Autowired PermissionCatalog catalog;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private SecuredMvc http;
    private String sku;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        sku = "AST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Assistant fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Assistant fixture");
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                        reserved, status, version, created_at) values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), sku, BIN, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        });
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("a question runs a tool under the asker's permissions; the answer, the call and its result are stored")
    void answersFromATool() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        script(toolUse("getStockLevels", "{\"sku\":\"" + sku + "\"}"), answer("Kho HCM còn 10."));
        String conversation = start(Role.INVENTORY_PLANNER, planner);

        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Còn bao nhiêu " + sku + "?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("Kho HCM còn 10."))
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("getStockLevels"))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        Map<String, Object> call = jdbc.queryForMap(
                "select outcome, result from assistant.tool_call where conversation_id = ?::uuid", conversation);
        assertThat(call.get("outcome")).isEqualTo("OK");
        assertThat((String) call.get("result")).contains(sku, "\"warehouseCode\":\"HCM\"", "\"onHand\":10");
        assertThat(jdbc.queryForList("select role from assistant.message where conversation_id = ?::uuid "
                + "order by sequence", String.class, conversation))
                .containsExactly("USER", "ASSISTANT", "TOOL_RESULTS", "ASSISTANT");

        http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + conversation + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[1].toolCalls[0].tool").value("getStockLevels"));
        http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE))
                .andExpect(jsonPath("$.data.items[0].title").value("Còn bao nhiêu " + sku + "?"));
    }

    @Test
    @DisplayName("a tool whose permission the asker lacks is DENIED, and the model is told which one")
    void deniesWhatTheScreenWouldDeny() throws Exception {
        String role = jdbc.queryForObject("""
                select r.code from identity.app_role r
                where r.code in ('ACCOUNTANT', 'SALES_STAFF', 'QC_STAFF', 'PROCUREMENT_STAFF')
                  and exists (select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                              where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                  and not exists (select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                                  where rp.role_id = r.id and p.code = 'inventory-stock-items:READ')
                order by r.code limit 1""", String.class);
        assertThat(role).as("a role that may ask but may not read stock").isNotNull();
        UUID asker = staff(role);
        script(toolUse("getStockLevels", "{\"sku\":\"" + sku + "\"}"), answer("Bạn chưa có quyền xem tồn kho."));
        String conversation = start(Role.valueOf(role), asker);

        http.asRole(Role.valueOf(role), asker, ask(conversation, "Còn bao nhiêu " + sku + "?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("DENIED"));

        String result = jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid",
                String.class, conversation);
        assertThat(result).startsWith("NOT_PERMITTED").contains("inventory-stock-items:READ").doesNotContain(sku);
    }

    @Test
    @DisplayName("a warehouse-limited user gets no company-wide low-stock rows, as on the alerts screen")
    void warehouseLimitedUserSeesNoSkuWideAlerts() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        script(toolUse("findLowStock", "{\"limit\":10}"), answer("Không xem được."));
        String conversation = start(Role.WAREHOUSE_MANAGER, manager);

        http.asRole(Role.WAREHOUSE_MANAGER, manager, ask(conversation, "Mã nào dưới điểm đặt hàng?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid",
                String.class, conversation);
        assertThat(JsonPath.<List<Object>>read(result, "$.skus")).isEmpty();
        assertThat(JsonPath.<String>read(result, "$.note")).contains("company-wide");
    }

    @Test
    @DisplayName("a conversation is its owner's alone: another user's id is not found, for reading and for asking")
    void conversationsArePrivate() throws Exception {
        UUID owner = staff("INVENTORY_PLANNER");
        UUID other = staff("INVENTORY_PLANNER");
        String conversation = start(Role.INVENTORY_PLANNER, owner);

        http.asRole(Role.INVENTORY_PLANNER, other, get(BASE + "/" + conversation + "/messages"))
                .andExpect(status().isNotFound());
        http.asRole(Role.INVENTORY_PLANNER, other, ask(conversation, "Xin chào"))
                .andExpect(status().isNotFound());
        http.asRole(Role.INVENTORY_PLANNER, other, get(BASE))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("a portal customer cannot open the assistant")
    void customersAreRefused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, post(BASE)).andExpect(status().isForbidden());
        http.asCustomer(account, get(BASE)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a second question while one is being answered is refused with ASSISTANT_BUSY")
    void oneQuestionAtATime() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String conversation = start(Role.INVENTORY_PLANNER, planner);
        // Hikari runs with auto-commit off: outside a transaction this update would never commit.
        tx.executeWithoutResult(s -> jdbc.update(
                "update assistant.conversation set answering_since = now() where id = ?::uuid", conversation));

        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Xin chào"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_BUSY"));
    }

    @Test
    @DisplayName("every tool carries exactly the guard of the endpoint it mirrors, and that permission exists")
    void everyToolMirrorsItsScreen() {
        assertThat(tools.all()).isNotEmpty();
        for (AssistantTool tool : tools.all()) {
            RequiresPermission guard = guard(tool);
            String name = tool.spec().name();
            assertThat(guard).as("%s.run carries @RequiresPermission", name).isNotNull();
            assertThat(catalog.isDeclared(PermissionCode.of(guard.resource(), guard.action())))
                    .as("%s guards with %s:%s, which the permission catalog declares", name, guard.resource(),
                            guard.action())
                    .isTrue();
            if (tool.mirrors() == null) {
                assertThat(guard.resource()).as("%s mirrors no endpoint, so it uses an assistant resource", name)
                        .startsWith("assistant-");
                continue;
            }
            RequiresPermission endpoint = endpointGuard(tool.mirrors())
                    .orElseThrow(() -> new AssertionError(name + " mirrors " + tool.mirrors()
                            + ", which is no guarded endpoint"));
            assertThat(List.of(guard.resource(), guard.action(), guard.scope()))
                    .as("%s carries the guard of %s", name, tool.mirrors())
                    .isEqualTo(List.of(endpoint.resource(), endpoint.action(), endpoint.scope()));
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private RequiresPermission guard(AssistantTool tool) {
        try {
            Method run = AopUtils.getTargetClass(tool).getMethod("run", ToolArguments.class);
            return AnnotatedElementUtils.findMergedAnnotation(run, RequiresPermission.class);
        } catch (NoSuchMethodException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private Optional<RequiresPermission> endpointGuard(String mirrors) {
        String[] parts = mirrors.split(" ", 2);
        RequestMethod method = RequestMethod.valueOf(parts[0]);
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getMethodsCondition().getMethods().contains(method)
                    && info.getPathPatternsCondition() != null
                    && info.getPathPatternsCondition().getPatternValues().contains(parts[1])) {
                return Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(
                        entry.getValue().getMethod(), RequiresPermission.class));
            }
        }
        return Optional.empty();
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

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ask(String conversation,
                                                                                                    String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private void script(LanguageModel.Reply first, LanguageModel.Reply... rest) {
        when(model.next(any())).thenReturn(first, rest);
    }

    private static LanguageModel.Reply toolUse(String tool, String arguments) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null,
                List.of(new ToolCall("toolu_" + UUID.randomUUID(), tool, arguments))),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
