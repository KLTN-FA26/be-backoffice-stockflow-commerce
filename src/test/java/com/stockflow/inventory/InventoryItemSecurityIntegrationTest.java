package com.stockflow.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestTokens;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who may read and who may edit an inventory item, through the real filter chain: warehouse staff read but cannot
 * write, the planner and the warehouse manager write, and holding only the product module's UPDATE is not enough
 * because the item has its own permission resource.
 */
@SecuredIntegrationTest
class InventoryItemSecurityIntegrationTest {

    private static final String POLICY_BODY =
            "{\"version\":%d,\"reorderPoint\":20,\"removalStrategy\":\"FEFO\",\"trackingMode\":\"NONE\","
                    + "\"expiryTracked\":false}";
    private static final String LOGISTICS_BODY =
            "{\"version\":%d,\"unitOfMeasure\":\"EACH\",\"storageClass\":\"NORMAL\",\"requiresAdultSignature\":false,"
                    + "\"qcRequired\":false}";
    /** The role whose grants the last test rewrites; restored afterwards, as InventoryCatalogSecurityIntegrationTest does. */
    private static final String REWRITTEN_ROLE = "INVENTORY_PLANNER";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired IdentityService identity;

    private SecuredMvc mvc;
    private UUID itemId;
    private List<String> originalPermissions;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        String sku = "SEC-IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        itemId = tx.execute(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Security fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Security fixture','')", Identifiers.newId(), product, sku);
            return jdbc.queryForObject("select id from inventory.inventory_items where sku=?", UUID.class, sku);
        });
        originalPermissions = identity.roleMatrix(REWRITTEN_ROLE).groups().stream()
                .flatMap(group -> group.resources().stream())
                .flatMap(resource -> resource.actions().stream()
                        .filter(RoleMatrixView.ActionChip::granted)
                        .map(action -> resource.code() + ":" + action.action().name()))
                .toList();
    }

    @AfterEach
    void restorePermissions() {
        identity.updateRolePermissions(new UpdateRolePermissionsCommand(
                REWRITTEN_ROLE, identity.roleMatrix(REWRITTEN_ROLE).version(), originalPermissions));
    }

    private long version() {
        return jdbc.queryForObject("select version from inventory.inventory_items where id=?", Long.class, itemId);
    }

    private String url(String tail) {
        return "/api/v1/inventory/items/" + itemId + tail;
    }

    @Test
    void warehouseStaffReadsButCannotWrite() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.asRole(Role.WAREHOUSE_STAFF, user, get(url(""))).andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_STAFF, user, get("/api/v1/inventory/items")).andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_STAFF, user, get(url("/packaging-preview")).param("quantity", "2"))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_STAFF, user, put(url("/logistics")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGISTICS_BODY.formatted(version())))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_STAFF, user, put(url("/policy")).contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY.formatted(version())))
                .andExpect(status().isForbidden());
    }

    @Test
    void plannerAndManagerWrite() throws Exception {
        for (Role role : List.of(Role.INVENTORY_PLANNER, Role.WAREHOUSE_MANAGER)) {
            mvc.asRole(role, UUID.randomUUID(), put(url("/policy")).contentType(MediaType.APPLICATION_JSON)
                            .content(POLICY_BODY.formatted(version())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.anonymous(get(url(""))).andExpect(status().isUnauthorized());
        mvc.anonymous(get(url("/packaging-preview")).param("quantity", "2")).andExpect(status().isUnauthorized());
    }

    @Test
    void productUpdateAloneIsNotEnough() throws Exception {
        identity.updateRolePermissions(new UpdateRolePermissionsCommand(REWRITTEN_ROLE,
                identity.roleMatrix(REWRITTEN_ROLE).version(), List.of("product-products:UPDATE")));
        String token = TestTokens.bearer(decoder, UUID.randomUUID(), Map.of("scope_level", "ALL"), REWRITTEN_ROLE);
        mockMvc.perform(put(url("/policy")).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY.formatted(version())))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(url("/logistics")).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGISTICS_BODY.formatted(version())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(url("/packaging-preview")).param("quantity", "2").header("Authorization", token))
                .andExpect(status().isForbidden());
    }
}
