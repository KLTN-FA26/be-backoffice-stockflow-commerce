package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.internal.controller.dto.CustomerResponse;
import com.stockflow.customer.internal.service.CustomerLegalProfileService;
import com.stockflow.customer.internal.service.SalesOwnerViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The staff customer list names the province of each customer's default shipping address, to tell customers of the
 * same name apart - to a caller who may read addresses, and to nobody else (the list must show no more than
 * {@code GET /customers/{id}/addresses} would).
 */
class CustomerListProvinceTest {

    private final CustomerService customers = mock(CustomerService.class);
    private final CustomerController controller = new CustomerController(customers,
            mock(CustomerLegalProfileService.class), mock(SalesOwnerViews.class));

    private static CustomerSummary summary(UUID id, String name) {
        return new CustomerSummary(id, null, name, name.toLowerCase().replace(' ', '.') + "@example.com",
                "0901000000", "0312345678", null, "ACTIVE", 0, Instant.parse("2026-11-01T02:00:00Z"));
    }

    private static CurrentUser staff(PermissionCode... permissions) {
        return new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_STAFF), Set.of(permissions),
                DataScope.ALL, Set.of());
    }

    private final UUID withAddress = UUID.randomUUID();
    private final UUID withoutAddress = UUID.randomUUID();

    private void listing() {
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(summary(withAddress, "Quán trà Lá"),
                summary(withoutAddress, "Quán trà Lá chi nhánh")), 0, 20, 2));
        when(customers.defaultShippingProvinceNames(any())).thenReturn(Map.of(withAddress, "Thành phố Hồ Chí Minh"));
    }

    @Test
    @DisplayName("a caller who reads customers and addresses sees the province of each default shipping address; a "
            + "customer with none has no field")
    void provinceForACallerWhoMayReadAddresses() {
        listing();
        CurrentUser user = staff(PermissionCode.of("customer-customers", Action.READ),
                PermissionCode.of("customer-addresses", Action.READ));

        ApiResponse<PageResponse<CustomerResponse>> response = controller.list(user, 0, 20, null, null, null, null, null, null, null, null);

        List<CustomerResponse> rows = response.data().items();
        assertThat(rows).extracting(CustomerResponse::defaultShippingProvinceName)
                .containsExactly("Thành phố Hồ Chí Minh", null);
        assertThat(rows.get(0).taxCode()).as("the tax code stays masked in a list").isEqualTo("031****678");
    }

    @Test
    @DisplayName("a caller who reads customers but not addresses is told no province, and the addresses are not even read")
    void noProvinceWithoutTheAddressPermission() {
        listing();
        CurrentUser user = staff(PermissionCode.of("customer-customers", Action.READ));

        ApiResponse<PageResponse<CustomerResponse>> response = controller.list(user, 0, 20, null, null, null, null, null, null, null, null);

        assertThat(response.data().items()).extracting(CustomerResponse::defaultShippingProvinceName)
                .containsOnlyNulls();
        verify(customers, never()).defaultShippingProvinceNames(any());
    }

    @Test
    @DisplayName("an empty page asks for no provinces")
    void emptyPage() {
        when(customers.list(any())).thenReturn(PageResponse.empty(0, 20));
        CurrentUser user = staff(PermissionCode.of("customer-customers", Action.READ),
                PermissionCode.of("customer-addresses", Action.READ));

        assertThat(controller.list(user, 0, 20, null, null, null, null, null, null, null, null).data().items()).isEmpty();
        verify(customers, never()).defaultShippingProvinceNames(any());
    }
}
