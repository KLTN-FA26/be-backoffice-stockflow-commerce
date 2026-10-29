package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SupplierItemFixtures;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * getSupplierTerms end to end with the real security chain, the real permission aspect and a scripted model (ROLE-5):
 * a buyer who may read the supplier catalog gets a SKU's terms from the demo supplier GOVIET, and a role that may ask
 * but not read the catalog is DENIED, told which permission it lacks, and shown none of the terms. The SKU is the
 * test's own, listed for GOVIET through the real catalog service, so its figures are exact although other tests list
 * SKUs for GOVIET too.
 */
@SecuredIntegrationTest
class AssistantSupplierTermsIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    /** The demo supplier's primary contact and tax code (V20260928009000): nothing a tool may return. */
    private static final List<String> PERSONAL = List.of("Nguyễn Văn Demo", "0900000002", "sales@goviet.example",
            "0300000001");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired SupplierItemService catalog;

    private SecuredMvc http;
    private String sku;
    private UUID goviet;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        sku = new AlertFixtures(jdbc, tx, entityManager).sku(null, null);
        goviet = jdbc.queryForObject("select id from procurement.suppliers where code = 'GOVIET'", UUID.class);
        SupplierItemFixtures.list(catalog, goviet, sku, new BigDecimal("1800"), 500, 50, 21);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("a buyer who may read the supplier catalog gets the SKU's terms: who sells it, MOQ, pack, lead time")
    void buyerReadsTheTermsOfASku() throws Exception {
        assertThat(holds("PROCUREMENT_STAFF", "procurement-supplier-items:READ")).isTrue();
        UUID buyer = staff("PROCUREMENT_STAFF");
        script(toolUse("getSupplierTerms", "{\"sku\":\"" + sku + "\",\"supplier\":\"\"}"),
                answer("GOVIET: MOQ 500, pack 50, 21 days."));
        String conversation = start(Role.PROCUREMENT_STAFF, buyer);

        http.asRole(Role.PROCUREMENT_STAFF, buyer, ask(conversation, sku + " mua của ai, MOQ bao nhiêu?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("getSupplierTerms"))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation);
        assertThat(JsonPath.<List<String>>read(result, "$.terms[*].supplierCode")).containsExactly("GOVIET");
        assertThat(JsonPath.<Number>read(result, "$.terms[0].moq").intValue()).isEqualTo(500);
        assertThat(JsonPath.<Number>read(result, "$.terms[0].packSize").intValue()).isEqualTo(50);
        assertThat(JsonPath.<Number>read(result, "$.terms[0].leadTimeDays").intValue()).isEqualTo(21);
        assertThat(JsonPath.<Number>read(result, "$.terms[0].unitPriceBeforeTax").doubleValue()).isEqualTo(1800);
        assertThat(JsonPath.<String>read(result, "$.terms[0].supplierStatus")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Boolean>read(result, "$.terms[0].orderable")).isTrue();
        assertThat(result).doesNotContain(goviet.toString());
    }

    @Test
    @DisplayName("asked by supplier, the buyer gets what GOVIET lists, newest listing first, and none of its people")
    void buyerReadsASuppliersCatalog() throws Exception {
        UUID buyer = staff("PROCUREMENT_STAFF");
        script(toolUse("getSupplierTerms", "{\"sku\":\"\",\"supplier\":\"GOVIET\"}"), answer("GOVIET bán " + sku));
        String conversation = start(Role.PROCUREMENT_STAFF, buyer);

        http.asRole(Role.PROCUREMENT_STAFF, buyer, ask(conversation, "GOVIET đang bán những mã nào?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation);
        assertThat(JsonPath.<String>read(result, "$.terms[0].sku")).isEqualTo(sku);
        long total = JsonPath.<Number>read(result, "$.total").longValue();
        int shown = JsonPath.<List<Object>>read(result, "$.terms").size();
        assertThat(JsonPath.<Boolean>read(result, "$.truncated")).isEqualTo(total > shown);
        assertThat(result).doesNotContain(PERSONAL.toArray(String[]::new)).doesNotContain(goviet.toString());
    }

    @Test
    @DisplayName("a role that may ask but not read the supplier catalog is DENIED, told which permission, no terms")
    void deniesWhatTheCatalogScreenWouldDeny() throws Exception {
        String role = jdbc.queryForObject("""
                select r.code from identity.app_role r
                where r.code in ('ORDER_COORDINATOR', 'SALES_STAFF', 'ACCOUNTANT', 'QC_STAFF', 'INVENTORY_PLANNER',
                                 'WAREHOUSE_MANAGER')
                  and exists (select 1 from identity.role_permission rp
                                join identity.permission p on p.id = rp.permission_id
                              where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                  and not exists (select 1 from identity.role_permission rp
                                    join identity.permission p on p.id = rp.permission_id
                                  where rp.role_id = r.id and p.code = 'procurement-supplier-items:READ')
                order by r.code limit 1""", String.class);
        assertThat(role).as("a role that may ask but may not read the supplier catalog").isNotNull();
        UUID asker = staff(role);
        script(toolUse("getSupplierTerms", "{\"sku\":\"" + sku + "\",\"supplier\":\"GOVIET\"}"),
                answer("Bạn chưa có quyền xem danh mục nhà cung cấp."));
        String conversation = start(Role.valueOf(role), asker);

        http.asRole(Role.valueOf(role), asker, ask(conversation, sku + " mua của GOVIET giá bao nhiêu?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("DENIED"));

        assertThat(result(conversation)).startsWith("NOT_PERMITTED").contains("procurement-supplier-items:READ")
                .doesNotContain(sku, "1800", "GOVIET");
    }

    // ------------------------------------------------------------------------------------------------------------

    private boolean holds(String role, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from identity.role_permission rp
                                 join identity.app_role r on r.id = rp.role_id
                                 join identity.permission p on p.id = rp.permission_id
                                where r.code = ? and p.code = ?)""", Boolean.class, role, permission));
    }

    private String result(String conversation) {
        return jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid",
                String.class, conversation);
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
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
