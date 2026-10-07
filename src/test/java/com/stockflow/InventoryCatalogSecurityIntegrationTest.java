package com.stockflow;

import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real filter chain, JWT authority converter and permission aspects; only token decoding is stubbed. */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="stockflow.security.enabled=true")
class InventoryCatalogSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    private String token(String permission) {
        String token=UUID.randomUUID().toString();
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg","RS256")
                .subject(UUID.randomUUID().toString()).claim("scope_level","ALL")
                .claim("permissions",List.of(permission)).build());
        return "Bearer "+token;
    }
    private UUID product() {
        UUID id=UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into product.product(id,code,name,name_en,brand,tax_class,status,created_at)
                values (?,?,'Table','Table','Test','STANDARD','DRAFT',now())
                """,id,"SEC-"+id));
        return id;
    }
    @Test void anonymousCanReadPublicCatalogButCannotManageProducts() throws Exception {
        mvc.perform(get("/api/v1/public/catalog/products")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/"+UUID.randomUUID()+"/ecommerce")).andExpect(status().isUnauthorized());
    }
    @Test void readPermissionCannotEditOrPublish() throws Exception {
        UUID id=product(); String read=token("product-products:READ");
        mvc.perform(get("/api/v1/products/"+id+"/ecommerce").header("Authorization",read)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/products/"+id+"/ecommerce").header("Authorization",read)
                .contentType("application/json").content("{\"revision\":0,\"slug\":\"read-only\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products/"+id+"/publication").header("Authorization",read))
                .andExpect(status().isForbidden());
    }
    @Test void updateCanEditButCannotPublishAndDraftRemainsPrivate() throws Exception {
        UUID id=product(); String update=token("product-products:UPDATE"); String slug="private-"+id;
        mvc.perform(put("/api/v1/products/"+id+"/ecommerce").header("Authorization",update)
                .contentType("application/json").content("{\"revision\":0,\"slug\":\""+slug+"\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/products/"+id+"/publication").header("Authorization",update))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/public/catalog/products/"+slug)).andExpect(status().isNotFound());
    }
    @Test void approvePermissionDoesNotBypassPublicationPrerequisites() throws Exception {
        mvc.perform(post("/api/v1/products/"+product()+"/publication")
                .header("Authorization",token("product-products:APPROVE"))).andExpect(status().isConflict());
    }
    @Test void inventoryControlsRequireTheMatchingPermission() throws Exception {
        String path="/api/v1/products/"+UUID.randomUUID()+"/skus/"+UUID.randomUUID()+"/inventory-control";
        String body="{\"version\":0,\"reorderPoint\":5,\"removalStrategy\":\"FIFO\",\"trackingMode\":\"NONE\",\"expiryTracked\":false}";
        mvc.perform(put(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(path).header("Authorization",token("product-products:READ"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
