package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getStockLevels given a catalogue variant (golden case U7-03): a variant holds no stock since V20270105000100, so the
 * answer is the stock of the customers' SKUs drawn on it, never "code not found".
 */
class StockLevelsOfVariantTest {

    private static final String VARIANT = "EVAL-KRAFT-12OZ";
    private static final String CUSTOMER_SKU = "EVAL-KRAFT-12OZ-KH";
    private static final UUID OWNER = UUID.randomUUID();

    private final InventoryService inventory = mock(InventoryService.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CurrentUserProvider currentUsers = mock(CurrentUserProvider.class);

    private GetStockLevelsTool tool() {
        return new GetStockLevelsTool(inventory, null, customerSkus, customers, currentUsers);
    }

    private static ToolArguments sku(String code) {
        return ToolArguments.parse(new ObjectMapper(), "{\"sku\":\"" + code + "\"}");
    }

    private void caller(String... resources) {
        Set<PermissionCode> permissions = new java.util.HashSet<>();
        for (String resource : resources) {
            permissions.add(PermissionCode.of(resource, Action.READ));
        }
        when(currentUsers.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "u",
                Set.of(Role.SYSTEM_ADMIN), permissions, DataScope.ALL, Set.of(), Set.of())));
    }

    private static CustomerSku drawn(String code) {
        return new CustomerSku(UUID.randomUUID(), UUID.randomUUID(), code, "Cốc kraft in logo", OWNER,
                UUID.randomUUID(), UUID.randomUUID(), VARIANT, "ACTIVE");
    }

    @Test
    @DisplayName("a variant lists its customers' stocked SKUs with their warehouses and customer; unstocked ones out")
    void variantListsItsCustomersStock() {
        caller(ToolPermissions.INVENTORY_STOCK_ITEMS, ToolPermissions.CUSTOMERS);
        when(inventory.itemIdsOf(List.of(VARIANT))).thenReturn(Map.of());
        when(customerSkus.drawnOn(eq(VARIANT), anyInt())).thenReturn(Optional.of(new CustomerSkus.DrawnSkus(
                List.of(drawn(CUSTOMER_SKU), drawn("EVAL-KRAFT-12OZ-KH2")), 2)));
        when(inventory.itemIdsOf(List.of(CUSTOMER_SKU, "EVAL-KRAFT-12OZ-KH2")))
                .thenReturn(Map.of(CUSTOMER_SKU, UUID.randomUUID()));
        when(inventory.levelsOf(new Sku(CUSTOMER_SKU))).thenReturn(List.of(new StockLevel(CUSTOMER_SKU, "HCM", 120,
                120, 0, 0, 120, 2, 2, 0, 0, 2)));
        when(inventory.orderHoldsByWarehouse(new Sku(CUSTOMER_SKU))).thenReturn(Map.of());
        when(inventory.availableToPromise(List.of(new Sku(CUSTOMER_SKU)))).thenReturn(Map.of(new Sku(CUSTOMER_SKU),
                120));
        when(customers.fullNames(anyCollection())).thenReturn(Map.of(OWNER, "Công ty EVAL Giấy Bao Bì"));

        GetStockLevelsTool.Answer answer = tool().run(sku(VARIANT));

        assertThat(answer.catalogueVariant()).isTrue();
        assertThat(answer.warehouses()).isEmpty();
        assertThat(answer.customerSkusTotal()).isEqualTo(1);
        assertThat(answer.customerSkusTruncated()).isFalse();
        assertThat(answer.customerSkus()).singleElement().satisfies(stock -> {
            assertThat(stock.sku()).isEqualTo(CUSTOMER_SKU);
            assertThat(stock.customer().name()).isEqualTo("Công ty EVAL Giấy Bao Bì");
            assertThat(stock.companyAvailableToPromise()).isEqualTo(120);
            assertThat(stock.warehouses()).singleElement().satisfies(row -> {
                assertThat(row.onHand()).isEqualTo(120);
                assertThat(row.onHandCartons()).isEqualTo(2);
            });
        });
        verify(inventory, never()).levelsOf(new Sku("EVAL-KRAFT-12OZ-KH2"));
    }

    @Test
    @DisplayName("whose SKU it is is named only to a caller who may read customers")
    void customerNamedOnlyWithThePermission() {
        caller(ToolPermissions.INVENTORY_STOCK_ITEMS);
        when(inventory.itemIdsOf(List.of(VARIANT))).thenReturn(Map.of());
        when(customerSkus.drawnOn(eq(VARIANT), anyInt())).thenReturn(Optional.of(new CustomerSkus.DrawnSkus(
                List.of(drawn(CUSTOMER_SKU)), 1)));
        when(inventory.itemIdsOf(List.of(CUSTOMER_SKU))).thenReturn(Map.of(CUSTOMER_SKU, UUID.randomUUID()));
        when(inventory.levelsOf(new Sku(CUSTOMER_SKU))).thenReturn(List.of(level(CUSTOMER_SKU, "HCM")));

        GetStockLevelsTool.Answer answer = tool().run(sku(VARIANT));

        assertThat(answer.customerSkus().getFirst().customer()).isNull();
        verify(customers, never()).fullNames(anyCollection());
    }

    @Test
    @DisplayName("a SKU with no warehouse the user may see (stocked only elsewhere, or no stock at all) is neither "
            + "listed nor counted; the listed ones' company figures are read in one query, never one per SKU")
    void onlyVisibleSkusAndBatchedCompanyFigures() {
        caller(ToolPermissions.INVENTORY_STOCK_ITEMS);
        String elsewhere = VARIANT + "-HN";
        String second = VARIANT + "-KH2";
        when(inventory.itemIdsOf(List.of(VARIANT))).thenReturn(Map.of());
        when(customerSkus.drawnOn(eq(VARIANT), anyInt())).thenReturn(Optional.of(new CustomerSkus.DrawnSkus(
                List.of(drawn(CUSTOMER_SKU), drawn(elsewhere), drawn(second)), 3)));
        when(inventory.itemIdsOf(List.of(CUSTOMER_SKU, elsewhere, second))).thenReturn(Map.of(CUSTOMER_SKU,
                UUID.randomUUID(), elsewhere, UUID.randomUUID(), second, UUID.randomUUID()));
        when(inventory.levelsOf(new Sku(CUSTOMER_SKU))).thenReturn(List.of(level(CUSTOMER_SKU, "HCM")));
        when(inventory.levelsOf(new Sku(elsewhere))).thenReturn(List.of());
        when(inventory.levelsOf(new Sku(second))).thenReturn(List.of(level(second, "HCM")));
        when(inventory.orderHoldsByWarehouse(any(Sku.class))).thenReturn(Map.of());
        when(inventory.availableToPromise(anyCollection())).thenReturn(Map.of(new Sku(CUSTOMER_SKU), 120,
                new Sku(second), 60));

        GetStockLevelsTool.Answer answer = tool().run(sku(VARIANT));

        assertThat(answer.customerSkus()).extracting(GetStockLevelsTool.CustomerSkuStock::sku)
                .containsExactly(CUSTOMER_SKU, second);
        assertThat(answer.customerSkus()).extracting(GetStockLevelsTool.CustomerSkuStock::companyAvailableToPromise)
                .containsExactly(120, 60);
        assertThat(answer.customerSkusTotal()).isEqualTo(2);
        assertThat(answer.customerSkusTruncated()).isFalse();
        verify(inventory, times(1)).availableToPromise(anyCollection());
        verify(inventory, never()).availableToPromise(any(Sku.class));
        verify(inventory, never()).orderHoldsByWarehouse(new Sku(elsewhere));
    }

    @Test
    @DisplayName("customers' SKUs all stocked elsewhere: the warehouses read stop at LEVELS_READ, nothing listed, the "
            + "answer says some were not read")
    void levelReadsAreBounded() {
        caller(ToolPermissions.INVENTORY_STOCK_ITEMS);
        List<CustomerSku> many = java.util.stream.IntStream.range(0, GetStockLevelsTool.LEVELS_READ + 5)
                .mapToObj(n -> drawn(VARIANT + "-" + n)).toList();
        when(inventory.itemIdsOf(List.of(VARIANT))).thenReturn(Map.of());
        when(customerSkus.drawnOn(eq(VARIANT), anyInt())).thenReturn(Optional.of(new CustomerSkus.DrawnSkus(many,
                many.size())));
        Map<String, UUID> items = new java.util.HashMap<>();
        many.forEach(customerSku -> items.put(customerSku.sku(), UUID.randomUUID()));
        when(inventory.itemIdsOf(many.stream().map(CustomerSku::sku).toList())).thenReturn(items);
        when(inventory.levelsOf(any(Sku.class))).thenReturn(List.of());

        GetStockLevelsTool.Answer answer = tool().run(sku(VARIANT));

        assertThat(answer.customerSkus()).isEmpty();
        assertThat(answer.customerSkusTotal()).isZero();
        assertThat(answer.customerSkusTruncated()).isTrue();
        verify(inventory, times(GetStockLevelsTool.LEVELS_READ)).levelsOf(any(Sku.class));
    }

    private static StockLevel level(String sku, String warehouse) {
        return new StockLevel(sku, warehouse, 120, 120, 0, 0, 120, 2, 2, 0, 0, 2);
    }

    @Test
    @DisplayName("a code that is neither a stocked SKU nor a catalogue variant is still NOT_FOUND")
    void unknownStaysNotFound() {
        caller(ToolPermissions.INVENTORY_STOCK_ITEMS);
        when(inventory.itemIdsOf(anyCollection())).thenReturn(Map.of());
        when(customerSkus.drawnOn(eq("EVAL-CUP-12"), anyInt())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool().run(sku("EVAL-CUP-12"))).isInstanceOf(NotFoundException.class);
    }
}
