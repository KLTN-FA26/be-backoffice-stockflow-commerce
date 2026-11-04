package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * getCustomerPrice (ROLE-6): the schedule a quote starts from, the tier a quantity falls in by checkout's rule, and of
 * the customer nothing but its name and status.
 */
class GetCustomerPriceToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final CustomerSummary ABC = new CustomerSummary(UUID.randomUUID(), UUID.randomUUID(),
            "Công ty In Ly ABC", "mua-hang@abc.example.vn", "0903123456", "0312345678", UUID.randomUUID(), "ACTIVE",
            3, Instant.now());

    private final CatalogService catalog = mock(CatalogService.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final GetCustomerPriceTool tool = new GetCustomerPriceTool(catalog, customers, users);

    @BeforeEach
    void signedIn() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.SALES_STAFF).scope(DataScope.ALL)
                .can("catalog-customer-prices", Action.READ).can("customer-customers", Action.READ).build()));
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(ABC), 0, CustomerMatch.SEARCHED, 1));
    }

    private static ToolArguments arguments(String customer, String sku, int quantity) {
        return ToolArguments.parse(JSON, "{\"customer\":\"" + customer + "\",\"sku\":\"" + sku + "\",\"quantity\":"
                + quantity + "}");
    }

    /** The customer's own list: from 5 000 units 1 750 ₫, from 10 000 units 1 650 ₫. */
    private void ownPrices() {
        when(catalog.priceSchedule(ABC.customerId(), "EVAL-LID-90")).thenReturn(new PriceSchedule("EVAL-LID-90",
                PriceSchedule.PriceSource.CUSTOMER, 5_000, List.of(new PriceSchedule.Tier(5_000, Money.vnd(1_750)),
                new PriceSchedule.Tier(10_000, Money.vnd(1_650)))));
    }

    @Test
    @DisplayName("the tiers as the screen shows them, and the one a quantity falls in: the last starting at or below it")
    void tierForAQuantity() {
        ownPrices();

        GetCustomerPriceTool.Answer between = tool.run(arguments("ABC", "EVAL-LID-90", 7_500));
        GetCustomerPriceTool.Answer atTheStep = tool.run(arguments("ABC", "EVAL-LID-90", 10_000));
        GetCustomerPriceTool.Answer above = tool.run(arguments("ABC", "EVAL-LID-90", 20_000));

        assertThat(between.source()).isEqualTo("CUSTOMER");
        assertThat(between.moq()).isEqualTo(5_000);
        assertThat(between.currency()).isEqualTo("VND");
        assertThat(between.tiers()).extracting(GetCustomerPriceTool.Tier::minQuantity,
                        GetCustomerPriceTool.Tier::unitPrice)
                .containsExactly(tuple(5_000, BigDecimal.valueOf(1_750)), tuple(10_000, BigDecimal.valueOf(1_650)));
        assertThat(between.quantity()).isEqualTo(7_500);
        assertThat(between.tierForQuantity().minQuantity()).isEqualTo(5_000);
        assertThat(between.belowMoq()).isFalse();
        assertThat(atTheStep.tierForQuantity().unitPrice()).isEqualTo(BigDecimal.valueOf(1_650));
        assertThat(above.tierForQuantity().minQuantity()).isEqualTo(10_000);
        assertThat(between.customerName()).isEqualTo("Công ty In Ly ABC");
        assertThat(between.customerStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("under the first tier there is no price: below the customer's minimum, as checkout refuses it")
    void belowTheMinimum() {
        ownPrices();

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-LID-90", 4_999));

        assertThat(answer.belowMoq()).isTrue();
        assertThat(answer.tierForQuantity()).isNull();
        assertThat(answer.tiers()).hasSize(2);
    }

    @Test
    @DisplayName("no quantity asked: the schedule alone, no tier picked")
    void noQuantity() {
        ownPrices();

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-LID-90", 0));

        assertThat(answer.quantity()).isNull();
        assertThat(answer.tierForQuantity()).isNull();
        assertThat(answer.belowMoq()).isNull();
    }

    @Test
    @DisplayName("the SKU is trimmed and upper-cased first, as the screen does, so a lower-case code still finds its "
            + "price")
    void skuAsTheScreenReadsIt() {
        ownPrices();

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", " eval-lid-90 ", 0));

        verify(catalog).priceSchedule(ABC.customerId(), "EVAL-LID-90");
        assertThat(answer.sku()).isEqualTo("EVAL-LID-90");
    }

    @Test
    @DisplayName("a group or list price comes from one unit on, and says whose it is")
    void groupPrice() {
        when(catalog.priceSchedule(ABC.customerId(), "EVAL-CUP-12OZ")).thenReturn(new PriceSchedule("EVAL-CUP-12OZ",
                PriceSchedule.PriceSource.SEGMENT, 1, List.of(new PriceSchedule.Tier(1, Money.vnd(2_100)))));

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-CUP-12OZ", 300));

        assertThat(answer.source()).isEqualTo("SEGMENT");
        assertThat(answer.moq()).isEqualTo(1);
        assertThat(answer.tierForQuantity().unitPrice()).isEqualTo(BigDecimal.valueOf(2_100));
    }

    @Test
    @DisplayName("a SKU priced nowhere is a note, never a price; any other refusal of the catalog is passed on")
    void noPrice() {
        when(catalog.priceSchedule(ABC.customerId(), "EVAL-NOPE")).thenThrow(
                new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
        when(catalog.priceSchedule(ABC.customerId(), "EVAL-GONE")).thenThrow(
                new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-NOPE", 100));

        assertThat(answer.tiers()).isEmpty();
        assertThat(answer.source()).isNull();
        assertThat(answer.note()).contains("No price is set for EVAL-NOPE", "checking the code");
        assertThat(answer.customerName()).isEqualTo("Công ty In Ly ABC");
        assertThatThrownBy(() -> tool.run(arguments("ABC", "EVAL-GONE", 100)))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    @Test
    @DisplayName("text that names no customer exactly asks for no price at all, and offers the candidates")
    void noCustomerNoPrice() {
        CustomerSummary other = new CustomerSummary(UUID.randomUUID(), null, "Công ty ABC Sài Gòn", "x@example.vn",
                null, null, null, "BLOCKED", 0, Instant.now());
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(ABC, other), 0, CustomerMatch.SEARCHED, 2));

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-LID-90", 10_000));

        verifyNoInteractions(catalog);
        assertThat(answer.tiers()).isEmpty();
        assertThat(answer.customerName()).isNull();
        assertThat(answer.candidates()).extracting(CustomerMatch.Candidate::customerName,
                CustomerMatch.Candidate::status).containsExactly(tuple("Công ty In Ly ABC", "ACTIVE"),
                tuple("Công ty ABC Sài Gòn", "BLOCKED"));
        assertThat(answer.note()).contains("ask which one");
    }

    @Test
    @DisplayName("of the customer only its name and status leave: no e-mail, phone, tax code or id")
    void noContactDetails() throws Exception {
        ownPrices();

        String sent = JSON.writeValueAsString(tool.run(arguments("0312345678", "EVAL-LID-90", 10_000)));

        assertThat(sent).contains("Công ty In Ly ABC")
                .doesNotContain("mua-hang@abc.example.vn", "0903123456", "0312345678", ABC.customerId().toString(),
                        ABC.userId().toString(), ABC.segmentId().toString());
    }

    @Test
    @DisplayName("a portal customer is refused before any customer is looked up")
    void backOfficeOnly() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.CUSTOMER).scope(DataScope.ALL)
                .can("catalog-customer-prices", Action.READ).can("customer-customers", Action.READ).build()));

        assertThatThrownBy(() -> tool.run(arguments("ABC", "EVAL-LID-90", 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(customers, catalog);
    }

    @Test
    @DisplayName("without the customer list, the customer is named exactly; a fragment shows no names and asks no "
            + "price")
    void coordinatorNamesTheCustomerExactly() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.ORDER_COORDINATOR)
                .scope(DataScope.ALL).can("catalog-customer-prices", Action.READ).build()));
        ownPrices();

        GetCustomerPriceTool.Answer fragment = tool.run(arguments("ABC", "EVAL-LID-90", 0));
        GetCustomerPriceTool.Answer exact = tool.run(arguments("Công ty In Ly ABC", "EVAL-LID-90", 0));

        assertThat(fragment.candidates()).isEmpty();
        assertThat(fragment.tiers()).isEmpty();
        assertThat(fragment.note()).doesNotContain("Công ty In Ly ABC");
        assertThat(exact.tiers()).hasSize(2);
        verify(catalog).priceSchedule(eq(ABC.customerId()), anyString());
    }

    @Test
    @DisplayName("a malformed SKU is the model's to correct, not a lookup")
    void malformedSku() {
        assertThatThrownBy(() -> tool.run(arguments("ABC", "ly 12oz", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(catalog);
    }
}
