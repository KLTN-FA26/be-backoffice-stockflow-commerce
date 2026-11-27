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
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
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
        when(catalog.customerPrice(ABC.customerId(), "EVAL-LID-90")).thenReturn(Optional.of(new PriceSchedule(
                "EVAL-LID-90", 5_000, List.of(new PriceSchedule.Tier(5_000, Money.vnd(1_750)),
                new PriceSchedule.Tier(10_000, Money.vnd(1_650))))));
    }

    @Test
    @DisplayName("the tiers as the screen shows them, and the one a quantity falls in: the last starting at or below it")
    void tierForAQuantity() {
        ownPrices();

        GetCustomerPriceTool.Answer between = tool.run(arguments("ABC", "EVAL-LID-90", 7_500));
        GetCustomerPriceTool.Answer atTheStep = tool.run(arguments("ABC", "EVAL-LID-90", 10_000));
        GetCustomerPriceTool.Answer above = tool.run(arguments("ABC", "EVAL-LID-90", 20_000));

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
        assertThat(between.customerName().name()).isEqualTo("Công ty In Ly ABC");
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

        verify(catalog).customerPrice(ABC.customerId(), "EVAL-LID-90");
        assertThat(answer.sku()).isEqualTo("EVAL-LID-90");
    }

    @Test
    @DisplayName("a SKU without the customer's own price is a note, never a price - there is no list or group price "
            + "behind it; any refusal of the catalog is passed on")
    void noPrice() {
        when(catalog.customerPrice(ABC.customerId(), "EVAL-NOPE")).thenReturn(Optional.empty());
        when(catalog.customerPrice(ABC.customerId(), "EVAL-GONE")).thenThrow(
                new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-NOPE", 100));

        assertThat(answer.tiers()).isEmpty();
        assertThat(answer.moq()).isNull();
        assertThat(answer.note()).contains("no price of its own for EVAL-NOPE", "no list or group price",
                "checking the code");
        assertThat(answer.customerName().name()).isEqualTo("Công ty In Ly ABC");
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
        assertThat(answer.candidates()).extracting(candidate -> candidate.customerName().name(),
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
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.SALES_STAFF)
                .scope(DataScope.ALL).can("catalog-customer-prices", Action.READ).build()));
        ownPrices();

        GetCustomerPriceTool.Answer fragment = tool.run(arguments("ABC", "EVAL-LID-90", 0));
        GetCustomerPriceTool.Answer exact = tool.run(arguments("Công ty In Ly ABC", "EVAL-LID-90", 0));

        assertThat(fragment.candidates()).isEmpty();
        assertThat(fragment.tiers()).isEmpty();
        assertThat(fragment.note()).doesNotContain("Công ty In Ly ABC");
        assertThat(exact.tiers()).hasSize(2);
        verify(catalog).customerPrice(eq(ABC.customerId()), anyString());
    }

    @Test
    @DisplayName("the answer says what the tiers count and how many pieces a carton holds, so 10,000 cups is never "
            + "read as 10,000 cartons; the quantity description says so too")
    void measureAndCarton() {
        UomConversionService units = mock(UomConversionService.class);
        // The variant's one carton (01 BR-03): the base unit has no row of its own since V20261226000200.
        when(units.conversionsOf(any())).thenReturn(java.util.Map.of("EVAL-LID-90", List.of(unit("CARTON", 50))));
        when(catalog.customerPrice(ABC.customerId(), "EVAL-LID-90")).thenReturn(Optional.of(new PriceSchedule(
                "EVAL-LID-90", 100, List.of(new PriceSchedule.Tier(100, Money.vnd(1_750)),
                new PriceSchedule.Tier(200, Money.vnd(1_650))), PriceSchedule.Measure.CARTON)));
        GetCustomerPriceTool withUnits = new GetCustomerPriceTool(catalog, customers, users, units);

        GetCustomerPriceTool.Answer answer = withUnits.run(arguments("ABC", "EVAL-LID-90", 200));

        assertThat(answer.measure()).isEqualTo("CARTON");
        assertThat(answer.unitsPerCarton()).isEqualTo(50);
        assertThat(answer.tierForQuantity().minQuantity()).isEqualTo(200);
        assertThat(tool.spec().toString()).contains("never a number of cartons", "unitsPerCarton");

        // Units that cannot be read leave the size out; the price still answers.
        when(units.conversionsOf(any())).thenThrow(new IllegalStateException("database gone"));
        GetCustomerPriceTool.Answer unread = withUnits.run(arguments("ABC", "EVAL-LID-90", 0));
        assertThat(unread.unitsPerCarton()).isNull();
        assertThat(unread.measure()).isEqualTo("CARTON");
        assertThat(unread.tiers()).hasSize(2);
    }

    @Test
    @DisplayName("a price kept on a stock variant counts pieces, and says so")
    void unitMeasure() {
        when(catalog.customerPrice(ABC.customerId(), "EVAL-LID-90")).thenReturn(Optional.of(new PriceSchedule(
                "EVAL-LID-90", 5_000, List.of(new PriceSchedule.Tier(5_000, Money.vnd(1_750))),
                PriceSchedule.Measure.UNIT)));

        GetCustomerPriceTool.Answer answer = tool.run(arguments("ABC", "EVAL-LID-90", 0));

        assertThat(answer.measure()).isEqualTo("UNIT");
        assertThat(answer.unitsPerCarton()).isNull();
    }

    private static UomConversion unit(String code, int factor) {
        return new UomConversion(UUID.randomUUID(), "EVAL-LID-90", code, factor, null, null, null, null, true, 0);
    }

    /** ABC's own SKU drawn on the catalogue variant LY-KRAFT-12OZ, and the variant's standard price by carton. */
    private CustomerSkus standardPrices() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.SALES_STAFF).scope(DataScope.ALL)
                .can("catalog-customer-prices", Action.READ).can("customer-customers", Action.READ)
                .can("catalog-standard-prices", Action.READ).build()));
        CustomerSkus skus = mock(CustomerSkus.class);
        when(skus.find("LY-KRAFT-12OZ-ABC-001")).thenReturn(Optional.of(new CustomerSku(UUID.randomUUID(),
                UUID.randomUUID(), "LY-KRAFT-12OZ-ABC-001", "Ly kraft 12oz in logo ABC", ABC.customerId(),
                UUID.randomUUID(), UUID.randomUUID(), "LY-KRAFT-12OZ", "ACTIVE")));
        when(skus.find("LY-KRAFT-12OZ")).thenReturn(Optional.empty());
        when(catalog.customerPrice(eq(ABC.customerId()), anyString())).thenReturn(Optional.empty());
        when(catalog.standardPrice("LY-KRAFT-12OZ")).thenReturn(Optional.of(new PriceSchedule("LY-KRAFT-12OZ", 50,
                List.of(new PriceSchedule.Tier(50, Money.vnd(1_900)), new PriceSchedule.Tier(200, Money.vnd(1_700))),
                PriceSchedule.Measure.CARTON)));
        return skus;
    }

    @Test
    @DisplayName("13 BR-04 (2026-11-26): without the customer's own price, the standard price of the variant its SKU "
            + "was drawn on, said to be one - and the tier for the cartons asked read from it")
    void standardPriceBehindTheCustomersSku() {
        GetCustomerPriceTool withSkus = new GetCustomerPriceTool(catalog, customers, users, null, standardPrices());

        GetCustomerPriceTool.Answer answer = withSkus.run(arguments("ABC", "ly-kraft-12oz-abc-001", 120));

        assertThat(answer.priceKind()).isEqualTo(GetCustomerPriceTool.PriceKind.STANDARD_PRICE);
        assertThat(answer.standardPriceOf()).isEqualTo("LY-KRAFT-12OZ");
        assertThat(answer.sku()).isEqualTo("LY-KRAFT-12OZ-ABC-001");
        assertThat(answer.measure()).isEqualTo("CARTON");
        assertThat(answer.moq()).isEqualTo(50);
        assertThat(answer.tierForQuantity().unitPrice()).isEqualTo(BigDecimal.valueOf(1_900));
        assertThat(answer.note()).isNull();
        assertThat(tool.spec().toString()).contains("STANDARD_PRICE", "not a price agreed with this customer");
    }

    @Test
    @DisplayName("the customer's own price wins whenever there is one: the standard price is never read")
    void ownPriceFirst() {
        CustomerSkus skus = standardPrices();
        when(catalog.customerPrice(ABC.customerId(), "LY-KRAFT-12OZ-ABC-001")).thenReturn(Optional.of(
                new PriceSchedule("LY-KRAFT-12OZ-ABC-001", 100, List.of(new PriceSchedule.Tier(100, Money.vnd(1_650))),
                        PriceSchedule.Measure.CARTON)));
        GetCustomerPriceTool withSkus = new GetCustomerPriceTool(catalog, customers, users, null, skus);

        GetCustomerPriceTool.Answer answer = withSkus.run(arguments("ABC", "LY-KRAFT-12OZ-ABC-001", 0));

        assertThat(answer.priceKind()).isEqualTo(GetCustomerPriceTool.PriceKind.CUSTOMER_PRICE);
        assertThat(answer.standardPriceOf()).isNull();
        assertThat(answer.tiers()).hasSize(1);
        verifyNoInteractions(skus);
        org.mockito.Mockito.verify(catalog, org.mockito.Mockito.never()).standardPrice(anyString());
    }

    @Test
    @DisplayName("asked about the catalogue variant itself: its standard price, with a note that the customer is "
            + "quoted for its own SKU drawn on it")
    void variantItself() {
        GetCustomerPriceTool withSkus = new GetCustomerPriceTool(catalog, customers, users, null, standardPrices());

        GetCustomerPriceTool.Answer answer = withSkus.run(arguments("ABC", "LY-KRAFT-12OZ", 0));

        assertThat(answer.priceKind()).isEqualTo(GetCustomerPriceTool.PriceKind.STANDARD_PRICE);
        assertThat(answer.standardPriceOf()).isEqualTo("LY-KRAFT-12OZ");
        assertThat(answer.note()).contains("catalogue variant", "own SKU drawn on it");
    }

    @Test
    @DisplayName("another customer's SKU gets no price, not even the standard one, and its owner is not named")
    void anotherCustomersSku() {
        CustomerSkus skus = standardPrices();
        UUID otherCustomer = UUID.randomUUID();
        when(skus.find("LY-KRAFT-12OZ-XYZ-004")).thenReturn(Optional.of(new CustomerSku(UUID.randomUUID(),
                UUID.randomUUID(), "LY-KRAFT-12OZ-XYZ-004", "Ly kraft 12oz in logo XYZ", otherCustomer,
                UUID.randomUUID(), UUID.randomUUID(), "LY-KRAFT-12OZ", "ACTIVE")));
        GetCustomerPriceTool withSkus = new GetCustomerPriceTool(catalog, customers, users, null, skus);

        GetCustomerPriceTool.Answer answer = withSkus.run(arguments("ABC", "LY-KRAFT-12OZ-XYZ-004", 100));

        assertThat(answer.priceKind()).isNull();
        assertThat(answer.tiers()).isEmpty();
        assertThat(answer.note()).contains("not one of this customer's own SKUs", "never say whose");
        org.mockito.Mockito.verify(catalog, org.mockito.Mockito.never()).standardPrice(anyString());
    }

    @Test
    @DisplayName("a caller who may not open the standard-price screen is told one may exist, never what it is")
    void standardPriceIsTheStandardPriceScreens() {
        CustomerSkus skus = standardPrices();
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.ACCOUNTANT).scope(DataScope.ALL)
                .can("catalog-customer-prices", Action.READ).can("customer-customers", Action.READ).build()));
        GetCustomerPriceTool withSkus = new GetCustomerPriceTool(catalog, customers, users, null, skus);

        GetCustomerPriceTool.Answer answer = withSkus.run(arguments("ABC", "LY-KRAFT-12OZ-ABC-001", 100));

        assertThat(answer.priceKind()).isNull();
        assertThat(answer.tiers()).isEmpty();
        assertThat(answer.note()).contains("catalog-standard-prices:READ", "never a price");
        org.mockito.Mockito.verify(catalog, org.mockito.Mockito.never()).standardPrice(anyString());
    }

    @Test
    @DisplayName("neither price: a note, never a price")
    void neither() {
        CustomerSkus skus = standardPrices();
        when(catalog.standardPrice("LY-KRAFT-12OZ")).thenReturn(Optional.empty());
        GetCustomerPriceTool withSkus = new GetCustomerPriceTool(catalog, customers, users, null, skus);

        GetCustomerPriceTool.Answer answer = withSkus.run(arguments("ABC", "LY-KRAFT-12OZ-ABC-001", 100));

        assertThat(answer.priceKind()).isNull();
        assertThat(answer.tiers()).isEmpty();
        assertThat(answer.note()).contains("no standard price", "no list or group price");
    }

    @Test
    @DisplayName("a malformed SKU is the model's to correct, not a lookup")
    void malformedSku() {
        assertThatThrownBy(() -> tool.run(arguments("ABC", "ly 12oz", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(catalog);
    }
}
