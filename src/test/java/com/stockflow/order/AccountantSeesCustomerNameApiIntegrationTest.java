package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA finding A5 (plan BE-15) over HTTP: the accountant holds no customer permission (kltn-docs 18 BR-06 keeps the
 * profile behind it), yet works on installments, receipts, orders and credit holds all day - so each of those answers
 * names the customer itself: {@code customerName} and {@code customerCode}, the code being the tax code or, for a
 * customer without one, {@code KH} and eight characters of the id.
 *
 * <p>Runs in the context the secured fulfillment tests share.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class AccountantSeesCustomerNameApiIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CustomerService customers;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID accountant;

    @BeforeEach
    void anAccountant() {
        confirmedOrders = new ConfirmedOrders(context);
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    private UUID customer(String name, String taxCode) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, name, "label-" + id + "@example.com", taxCode));
        return id;
    }

    private static String freshTaxCode() {
        return "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    private static String suffixOf(UUID id) {
        return "KH" + id.toString().replace("-", "").toUpperCase().substring(24);
    }

    @Test
    @DisplayName("the accountant reads the customer's name and tax code on its installments, receipts and orders")
    void namedOnInstallmentsReceiptsAndOrders() throws Exception {
        String taxCode = freshTaxCode();
        UUID customer = customer("Cong ty Ke Toan Thay", taxCode);
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        var confirmed = confirmedOrders.confirmed(customer, ConfirmedOrders.Line.cartons(sku, 10, 40_000));

        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/installments").param("customerId", customer.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerId").value(customer.toString()))
                .andExpect(jsonPath("$.data.items[0].customerName").value("Cong ty Ke Toan Thay"))
                .andExpect(jsonPath("$.data.items[0].customerCode").value(taxCode));
        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/installments/" + confirmed.installmentIds().get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("Cong ty Ke Toan Thay"))
                .andExpect(jsonPath("$.data.customerCode").value(taxCode));
        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/installments/orders/" + confirmed.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installments[0].customerName").value("Cong ty Ke Toan Thay"));

        http().asRole(Role.ACCOUNTANT, accountant,
                get("/api/v1/payment-receipts").param("customerId", customer.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerName").value("Cong ty Ke Toan Thay"))
                .andExpect(jsonPath("$.data.items[0].customerCode").value(taxCode));
        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/payment-receipts/" + confirmed.receiptId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("Cong ty Ke Toan Thay"))
                .andExpect(jsonPath("$.data.customerCode").value(taxCode));

        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/orders").param("customerId", customer.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerName").value("Cong ty Ke Toan Thay"))
                .andExpect(jsonPath("$.data.items[0].customerCode").value(taxCode));
        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/orders/" + confirmed.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.data.customerName").value("Cong ty Ke Toan Thay"))
                .andExpect(jsonPath("$.data.customerCode").value(taxCode));
    }

    @Test
    @DisplayName("a page of rows of several customers is named per row; a customer without a tax code is KH + 8 of its id")
    void namedPerRowOnAMixedPage() throws Exception {
        UUID withTax = customer("Cong ty Co MST", freshTaxCode());
        UUID withoutTax = customer("Cong ty Chua MST", null);
        var skuA = CustomerSkuRows.issue(jdbc, tx, entityManager, withTax, 50);
        var skuB = CustomerSkuRows.issue(jdbc, tx, entityManager, withoutTax, 50);
        var a = confirmedOrders.signed(withTax, ConfirmedOrders.Line.cartons(skuA, 10, 40_000));
        var b = confirmedOrders.signed(withoutTax, ConfirmedOrders.Line.cartons(skuB, 10, 40_000));

        // Both customers' installments on one page: each row carries its own customer's name and code.
        String body = http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/installments").param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var json = com.jayway.jsonpath.JsonPath.<List<java.util.Map<String, Object>>>read(body, "$.data.items");
        var rowA = json.stream().filter(r -> a.orderId().toString().equals(r.get("orderId"))).findFirst().orElseThrow();
        var rowB = json.stream().filter(r -> b.orderId().toString().equals(r.get("orderId"))).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(rowA.get("customerName")).isEqualTo("Cong ty Co MST");
        org.assertj.core.api.Assertions.assertThat(rowB.get("customerName")).isEqualTo("Cong ty Chua MST");
        org.assertj.core.api.Assertions.assertThat(rowB.get("customerCode")).isEqualTo(suffixOf(withoutTax));
    }

    @Test
    @DisplayName("the credit-hold list the accountant decides from names each order's customer")
    void namedOnCreditHolds() throws Exception {
        UUID customer = customer("Cong ty Cho Duyet", freshTaxCode());
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("1000000"), 30, "small limit",
                accountant, null));
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        // Sent while the limit carried it (else it waits for accounting before sending, 15 BR-11), signed over it.
        var held = confirmedOrders.signedHeldForCredit(customer, List.of(new com.stockflow.order.internal.service.SalesContracts
                        .Installment("After delivery", "AFTER_DELIVERY", null, new BigDecimal("100"), 30, null)),
                ConfirmedOrders.Line.cartons(sku, 10, 40_000));

        // Signing holds the order for credit in a listener after commit.
        confirmedOrders.awaitStatus(held.orderId(), com.stockflow.order.api.OrderStatus.ON_HOLD);

        String body = http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/orders/credit-holds").param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rows = com.jayway.jsonpath.JsonPath.<List<java.util.Map<String, Object>>>read(body, "$.data.items");
        @SuppressWarnings("unchecked")
        var order = (java.util.Map<String, Object>) rows.stream()
                .map(r -> r.get("order")).filter(o -> held.orderId().toString()
                        .equals(((java.util.Map<?, ?>) o).get("orderId"))).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(order.get("customerName")).isEqualTo("Cong ty Cho Duyet");
        org.assertj.core.api.Assertions.assertThat(order.get("customerCode")).isNotNull();
    }
}
