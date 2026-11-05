package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.RoleGrants;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Personal MCP tokens end to end (ADR-0013), with the real security chain: a token made with the sign-in token opens
 * the MCP endpoint and nothing else, carries its owner's grants as they stand at each call, and stops when revoked,
 * expired, or when its owner's account may not sign in any more. Also the protocol's structured results, against the
 * schema the server lists, and the Origin a page made to resolve to this server sends.
 *
 * <p>Only the JWT decoder is replaced, as in {@code AssistantMcpIntegrationTest}; it refuses a personal token as the
 * real one does, so a token sent where only JWTs go fails exactly as it would.</p>
 */
@SecuredIntegrationTest
class AssistantMcpTokenIntegrationTest {

    private static final String MCP = "/api/v1/assistant/mcp";
    private static final String TOKENS = "/api/v1/assistant/mcp-tokens";
    private static final String BIN = "HCM-A01-1-B";
    private static final String VERSION = "2025-11-25";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private String sku;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        // As the real decoder: a personal token is not a JWT.
        when(decoder.decode(startsWith("sfmcp_"))).thenThrow(new BadJwtException("Malformed token"));
        // Opt-in (V20261105001100): the roles these cases sign in with, as an administrator would grant them.
        RoleGrants.grant(jdbc, tx, "assistant-mcp:CREATE", "INVENTORY_PLANNER", "SALES_STAFF");
        sku = "MCPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "MCP token fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "MCP token fixture");
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                        reserved, status, version, created_at) values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), sku, BIN, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        });
    }

    @Test
    @DisplayName("a token made with the sign-in token opens the MCP endpoint as its owner: the same tools, a tool call "
            + "recorded as theirs, the token's last use noted")
    void aTokenOpensTheMcpEndpoint() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String token = made(planner, Role.INVENTORY_PLANNER, "Claude Code");

        List<String> withToken = names(result(withToken(token, mcp(request(2, "tools/list", "{}")))).path("tools"));
        List<String> signedIn = names(result(http.asRole(Role.INVENTORY_PLANNER, planner,
                mcp(request(2, "tools/list", "{}")))).path("tools"));
        JsonNode answer = result(withToken(token, mcp(request(3, "tools/call",
                "{\"name\":\"getStockLevels\",\"arguments\":{\"sku\":\"" + sku + "\"}}"))));

        assertThat(withToken).isEqualTo(signedIn).contains("getStockLevels");
        assertThat(answer.path("isError").booleanValue()).isFalse();
        assertThat(answer.path("content").get(0).path("text").asText()).contains(sku, "\"onHand\":10");
        assertThat(jdbc.queryForList("""
                SELECT outcome FROM assistant.tool_call WHERE channel = 'MCP' AND user_id = ?""", String.class,
                planner)).containsExactly("OK");
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM assistant.mcp_token WHERE owner_user_id = ?",
                Timestamp.class, planner)).isNotNull();
    }

    @Test
    @DisplayName("a token opens nothing but the MCP endpoint: not the assistant's other endpoints, and not the token "
            + "endpoints, so it cannot make another")
    void aTokenOpensNothingElse() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String token = made(planner, Role.INVENTORY_PLANNER, "Cursor");

        withToken(token, get("/api/v1/assistant/capabilities")).andExpect(status().isUnauthorized());
        withToken(token, get(TOKENS)).andExpect(status().isUnauthorized());
        withToken(token, post(TOKENS).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"another\"}"))
                .andExpect(status().isUnauthorized());
        withToken(token, get("/api/v1/inventory/stock-items")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant.mcp_token WHERE owner_user_id = ?",
                Integer.class, planner)).isEqualTo(1);
    }

    @Test
    @DisplayName("a revoked token, an expired one, an unknown one and a malformed one are all 401")
    void deadTokensAreRefused() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String revoked = made(planner, Role.INVENTORY_PLANNER, "revoked");
        String expired = made(planner, Role.INVENTORY_PLANNER, "expired");
        UUID revokedId = tokenId(revoked);

        http.asRole(Role.INVENTORY_PLANNER, planner, post(TOKENS + "/" + revokedId + "/revocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE assistant.mcp_token SET created_at = now() - INTERVAL '3 days',
                                               expires_at = now() - INTERVAL '1 day'
                WHERE token_hash = ?""", hash(expired)));

        for (String dead : List.of(revoked, expired, "sfmcp_" + "Z".repeat(43), "sfmcp_short")) {
            withToken(dead, mcp(request(1, "ping", "{}"))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("the token carries its owner's grants as they stand at each call: a role changed changes the tools, "
            + "an account locked stops it, with no one revoking the token")
    void theOwnersGrantsAsTheyStandNow() throws Exception {
        UUID user = staff("INVENTORY_PLANNER");
        String token = made(user, Role.INVENTORY_PLANNER, "laptop");
        assertThat(names(result(withToken(token, mcp(request(2, "tools/list", "{}")))).path("tools")))
                .contains("getStockLevels").doesNotContain("findOrder");

        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM identity.user_role WHERE user_id = ?", user);
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = 'SALES_STAFF'""",
                    user);
        });
        assertThat(names(result(withToken(token, mcp(request(3, "tools/list", "{}")))).path("tools")))
                .contains("findOrder").doesNotContain("getStockLevels");

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE identity.app_user SET locked_until = now() + INTERVAL '1 hour' WHERE id = ?", user));
        withToken(token, mcp(request(4, "ping", "{}"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the forced-change gate (SCRUM-474) refuses a token while the owner's password change is pending, as "
            + "it refuses a sign-in token; the flag alone, set with no reset, revokes nothing")
    void theForcedChangeGateRefusesATokenWhilePending() throws Exception {
        UUID user = staff("INVENTORY_PLANNER");
        String token = made(user, Role.INVENTORY_PLANNER, "laptop");

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE identity.app_user SET must_change_password = TRUE WHERE id = ?", user));
        withToken(token, mcp(request(1, "ping", "{}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE identity.app_user SET must_change_password = FALSE WHERE id = ?", user));
        withToken(token, mcp(request(2, "ping", "{}"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator's password reset revokes the owner's tokens for good (ADR-0013): a token minted by "
            + "whoever knew the old password does not come back when the owner sets a new one")
    void aPasswordResetRevokesTheTokens() throws Exception {
        UUID admin = staff("SYSTEM_ADMIN");
        UUID user = staff("INVENTORY_PLANNER");
        String token = made(user, Role.INVENTORY_PLANNER, "laptop");

        http.asRole(Role.SYSTEM_ADMIN, admin, post("/api/v1/identity/users/" + user + "/password-reset")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM assistant.mcp_token WHERE owner_user_id = ?", Timestamp.class, user))
                .isNotNull());

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE identity.app_user SET must_change_password = FALSE WHERE id = ?", user));
        withToken(token, mcp(request(1, "ping", "{}"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the token is shown once: the list has its hint and status, never the token; another user's token is "
            + "not found; a stored answer is refused (Idempotency-Key 400); an eleventh live token is 409")
    void theTokenEndpoints() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        UUID other = staff("SALES_STAFF");
        String token = made(planner, Role.INVENTORY_PLANNER, "desktop");

        String listed = http.asRole(Role.INVENTORY_PLANNER, planner, get(TOKENS)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode mine = json.readTree(listed).path("data");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("hint").asText()).isEqualTo(token.substring(token.length() - 4));
        assertThat(mine.get(0).path("status").asText()).isEqualTo("LIVE");
        assertThat(mine.get(0).has("token")).isFalse();
        assertThat(listed).doesNotContain(token.substring(6));

        http.asRole(Role.SALES_STAFF, other, post(TOKENS + "/" + tokenId(token) + "/revocation"))
                .andExpect(status().isNotFound());
        http.asRole(Role.INVENTORY_PLANNER, planner, post(TOKENS).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "mcp-token-key-1").content("{\"name\":\"retried\"}"))
                .andExpect(status().isBadRequest());
        http.asRole(Role.INVENTORY_PLANNER, planner, post(TOKENS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"long\",\"expiresInDays\":91}"))
                .andExpect(status().isBadRequest());

        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 9; i++) {
                jdbc.update("""
                        INSERT INTO assistant.mcp_token (id, owner_user_id, name, token_hash, token_hint, expires_at)
                        VALUES (?, ?, ?, ?, 'abcd', now() + INTERVAL '1 day')""", Identifiers.newId(), planner,
                        "filler " + i, hash("sfmcp_filler-" + planner + "-" + i));
            }
        });
        mockMvc.perform(post(TOKENS).header("Authorization", recentSignIn(Role.INVENTORY_PLANNER, planner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"eleventh\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_MCP_TOKEN_LIMIT"));
    }

    @Test
    @DisplayName("a token is made only just after signing in: a sign-in older than 15 minutes, or a token that says "
            + "nothing of it, is 403 RECENT_SIGN_IN_REQUIRED and nothing is made")
    void madeOnlyJustAfterSigningIn() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String hourOld = TestTokens.bearer(decoder, planner,
                Map.of("iat", Instant.now().minus(java.time.Duration.ofHours(1))), Role.INVENTORY_PLANNER.authority());

        mockMvc.perform(post(TOKENS).header("Authorization", hourOld).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"stale\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RECENT_SIGN_IN_REQUIRED"));
        http.asRole(Role.INVENTORY_PLANNER, planner, post(TOKENS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"no time\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RECENT_SIGN_IN_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant.mcp_token WHERE owner_user_id = ?",
                Integer.class, planner)).isZero();
    }

    @Test
    @DisplayName("opt-in: a role the business has not granted assistant-mcp:CREATE may chat but neither make a token "
            + "nor use MCP; SYSTEM_ADMIN holds it from the migration")
    void optIn() throws Exception {
        UUID maker = staff("PRODUCTION_STAFF");

        mockMvc.perform(post(TOKENS).header("Authorization", recentSignIn(Role.PRODUCTION_STAFF, maker))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"floor\"}"))
                .andExpect(status().isForbidden());
        http.asRole(Role.PRODUCTION_STAFF, maker, mcp(request(1, "ping", "{}"))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForList("""
                SELECT role.code FROM identity.app_role role
                JOIN identity.role_permission grant_row ON grant_row.role_id = role.id
                JOIN identity.permission permission ON permission.id = grant_row.permission_id
                WHERE permission.code = 'assistant-mcp:CREATE' AND grant_row.created_by = 'flyway'""", String.class))
                .containsExactly("SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("an administrator sees a user's tokens without the tokens themselves and revokes them all at once; "
            + "the next call with any of them is 401; making and revoking are in the audit trail, never the token")
    void anAdministratorRevokesAUsersTokens() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        UUID admin = staff("SYSTEM_ADMIN");
        String first = made(planner, Role.INVENTORY_PLANNER, "laptop");
        String second = made(planner, Role.INVENTORY_PLANNER, "desktop");

        String listed = http.asRole(Role.SYSTEM_ADMIN, admin, get(TOKENS + "/users/" + planner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(listed).path("data")).hasSize(2);
        assertThat(listed).doesNotContain(first.substring(6), second.substring(6));
        http.asRole(Role.INVENTORY_PLANNER, planner, get(TOKENS + "/users/" + planner))
                .andExpect(status().isForbidden());

        http.asRole(Role.SYSTEM_ADMIN, admin, post(TOKENS + "/users/" + planner + "/revocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(2));

        withToken(first, mcp(request(1, "ping", "{}"))).andExpect(status().isUnauthorized());
        withToken(second, mcp(request(2, "ping", "{}"))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log
                WHERE resource_type = 'mcp_token' AND action = 'GRANT' AND outcome = 'SUCCESS'
                  AND resource_id IN (SELECT id::text FROM assistant.mcp_token WHERE owner_user_id = ?)""",
                Integer.class, planner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log
                WHERE resource_type = 'user' AND action = 'REVOKE' AND resource_id = ?""", Integer.class,
                planner.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE details LIKE ? OR resource_id LIKE ?""", Integer.class,
                "%" + first.substring(6) + "%", "%" + first.substring(6) + "%")).isZero();
    }

    @Test
    @DisplayName("from 2025-11-25 a call's structuredContent is the text's JSON and valid against the outputSchema "
            + "tools/list gave the tool")
    void structuredContentMatchesTheListedSchema() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String token = made(planner, Role.INVENTORY_PLANNER, "inspector");

        JsonNode tools = result(withToken(token, mcp(request(2, "tools/list", "{}")))).path("tools");
        JsonNode schema = null;
        for (JsonNode tool : tools) {
            assertThat(tool.path("outputSchema").path("type").asText()).as(tool.path("name").asText())
                    .isEqualTo("object");
            if (tool.path("name").asText().equals("getStockLevels")) {
                schema = tool.path("outputSchema");
            }
        }
        JsonNode answer = result(withToken(token, mcp(request(3, "tools/call",
                "{\"name\":\"getStockLevels\",\"arguments\":{\"sku\":\"" + sku + "\"}}"))));

        assertThat(schema).isNotNull();
        JsonNode structured = answer.path("structuredContent");
        assertThat(structured).isEqualTo(json.readTree(answer.path("content").get(0).path("text").asText()));
        valid(structured, schema, "getStockLevels");
    }

    @Test
    @DisplayName("a page whose host name was made to resolve to this server sends an Origin matching the Host: the "
            + "CORS filter sees no cross-origin request, the endpoint still refuses it with 403")
    void aReboundPageIsRefused() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String token = made(planner, Role.INVENTORY_PLANNER, "rebinding");

        withToken(token, mcp(request(1, "ping", "{}")).with(request -> {
            request.setServerName("attacker.example");
            return request;
        }).header("Origin", "http://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32600));
        // Refused before authentication: 403, not the 401 a request without a token would otherwise get.
        mockMvc.perform(mcp(request(2, "ping", "{}")).with(request -> {
            request.setServerName("attacker.example");
            return request;
        }).header("Origin", "http://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32600));
        withToken(token, mcp(request(3, "ping", "{}"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------------------

    /** A sign-in token issued a moment ago, as the login endpoint issues one. */
    private String recentSignIn(Role role, UUID user) {
        return TestTokens.bearer(decoder, user, Map.of("iat", Instant.now().minusSeconds(30)), role.authority());
    }

    /** Makes a token as the user just signed in, and returns it. */
    private String made(UUID user, Role role, String name) throws Exception {
        String body = mockMvc.perform(post(TOKENS).header("Authorization", recentSignIn(role, user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("LIVE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = json.readTree(body).path("data").path("token").asText();
        assertThat(token).matches("^sfmcp_[A-Za-z0-9_-]{43}$");
        return token;
    }

    private UUID tokenId(String token) {
        return jdbc.queryForObject("SELECT id FROM assistant.mcp_token WHERE token_hash = ?", UUID.class, hash(token));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ResultActions withToken(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder mcp(String body) {
        return post(MCP).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", VERSION)
                .content(body);
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private JsonNode result(ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = json.readTree(body);
        assertThat(response.has("error")).as("a JSON-RPC result, not an error: %s", body).isFalse();
        return response.path("result");
    }

    private static List<String> names(JsonNode listed) {
        List<String> names = new ArrayList<>();
        listed.forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    /** The subset of JSON Schema the server writes: type, properties, required, items, enum. */
    private static void valid(JsonNode node, JsonNode schema, String path) {
        String type = schema.path("type").asText("");
        switch (type) {
            case "object" -> {
                assertThat(node.isObject()).as(path).isTrue();
                schema.path("required").forEach(name -> assertThat(node.has(name.asText()))
                        .as("%s has %s", path, name.asText()).isTrue());
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode property = schema.path("properties").path(field.getKey());
                    assertThat(property.isMissingNode()).as("%s.%s is in the schema", path, field.getKey()).isFalse();
                    valid(field.getValue(), property, path + "." + field.getKey());
                }
            }
            case "array" -> {
                assertThat(node.isArray()).as(path).isTrue();
                node.forEach(item -> valid(item, schema.path("items"), path + "[]"));
            }
            case "string" -> assertThat(node.isTextual()).as(path).isTrue();
            case "integer" -> assertThat(node.isIntegralNumber()).as(path).isTrue();
            case "number" -> assertThat(node.isNumber()).as(path).isTrue();
            case "boolean" -> assertThat(node.isBoolean()).as(path).isTrue();
            default -> assertThat(type).as("%s has a type", path).isNotEmpty();
        }
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }
}
