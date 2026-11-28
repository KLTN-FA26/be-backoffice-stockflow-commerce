package com.stockflow.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /customers} names the province of each customer's default SHIPPING address (TU-2: the customer picker
 * tells same-named customers apart), through the real filter chain, permission aspect and database. Only the default
 * shipping address counts - not a billing default, not a shipping address that is not the default - and the detail of
 * one customer is unchanged.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class CustomerListProvinceIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String token(String role) {
        UUID userId = tx.execute(s -> ReferenceRows.user(entityManager));
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private UUID customer(String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, NOW())""", id, name, "tinh-" + id + "@example.com"));
        return id;
    }

    private void address(UUID customer, String type, String province, String code, boolean isDefault) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, ward_code,
                                              province, province_code, country, is_default, version, created_at)
                VALUES (?, ?, ?, 'Nguyễn Văn An', '0901234567', '12 Lê Lợi', 'Phường Bến Nghé', '26734', ?, ?, 'VN',
                        ?, 0, NOW())""", Identifiers.newId(), customer, type, province, code, isDefault));
    }

    private JsonNode list(String authorization, String search) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/customers").param("q", search)
                        .header("Authorization", authorization)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data").path("items");
    }

    @Test
    @DisplayName("Sales sees the province of the default shipping address on each row; a billing default, a shipping "
            + "address that is not the default and a customer with no address give none")
    void provinceOfTheDefaultShippingAddress() throws Exception {
        String name = "Công ty Bao Bì " + UUID.randomUUID().toString().substring(24).toUpperCase();
        UUID hcm = customer(name);
        address(hcm, "SHIPPING", "Thành phố Hồ Chí Minh", "79", true);
        address(hcm, "SHIPPING", "Thành phố Hà Nội", "01", false);
        address(hcm, "BILLING", "Thành phố Đà Nẵng", "48", true);
        UUID billingOnly = customer(name + " Đại lý");
        address(billingOnly, "BILLING", "Thành phố Đà Nẵng", "48", true);
        UUID bare = customer(name + " Mới");

        JsonNode rows = list(token("SALES_STAFF"), name);

        assertThat(rows).hasSize(3);
        for (JsonNode row : rows) {
            UUID id = UUID.fromString(row.path("customerId").asText());
            if (id.equals(hcm)) {
                assertThat(row.path("defaultShippingProvinceName").asText()).isEqualTo("Thành phố Hồ Chí Minh");
            } else {
                assertThat(id).isIn(billingOnly, bare);
                assertThat(row.has("defaultShippingProvinceName")).as(row.path("fullName").asText()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the detail of one customer carries no province, and a caller without customer permissions gets "
            + "no list at all")
    void detailUnchangedAndOthersRefused() throws Exception {
        UUID id = customer("Công ty Hộp Giấy " + UUID.randomUUID().toString().substring(24).toUpperCase());
        address(id, "SHIPPING", "Thành phố Hồ Chí Minh", "79", true);

        String detail = mvc.perform(get("/api/v1/customers/" + id).header("Authorization", token("SALES_STAFF")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(detail).path("data").has("defaultShippingProvinceName")).isFalse();

        mvc.perform(get("/api/v1/customers").header("Authorization", token("WAREHOUSE_STAFF")))
                .andExpect(status().isForbidden());
    }
}
