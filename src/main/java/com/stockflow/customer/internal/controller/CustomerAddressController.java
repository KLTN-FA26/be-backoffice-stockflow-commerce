package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.internal.controller.dto.CustomerAddressResponse;
import com.stockflow.customer.internal.controller.dto.SaveAddressRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/addresses")
class CustomerAddressController {
    private final CustomerService customers;

    CustomerAddressController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @RequiresPermission(resource = CustomerResources.ADDRESSES, action = Action.READ)
    public ApiResponse<List<CustomerAddressResponse>> list(@PathVariable UUID customerId,
                                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(customers.listAddresses(customerId, user.userId(), privileged(user))
                .stream().map(CustomerWebMapper::toResponse).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = CustomerResources.ADDRESSES, action = Action.CREATE)
    public ApiResponse<CustomerAddressResponse> add(@PathVariable UUID customerId,
                                                    @AuthenticatedUser CurrentUser user,
                                                    @Valid @RequestBody SaveAddressRequest request) {
        return ApiResponse.ok(CustomerWebMapper.toResponse(customers.addAddress(customerId,
                user.userId(), privileged(user), CustomerWebMapper.toCommand(request))));
    }

    @PutMapping("/{addressId}")
    @RequiresPermission(resource = CustomerResources.ADDRESSES, action = Action.UPDATE)
    public ApiResponse<CustomerAddressResponse> update(@PathVariable UUID customerId,
                                                       @PathVariable UUID addressId,
                                                       @AuthenticatedUser CurrentUser user,
                                                       @Valid @RequestBody SaveAddressRequest request) {
        return ApiResponse.ok(CustomerWebMapper.toResponse(customers.updateAddress(customerId,
                addressId, user.userId(), privileged(user), CustomerWebMapper.toCommand(request))));
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(resource = CustomerResources.ADDRESSES, action = Action.DELETE)
    public void delete(@PathVariable UUID customerId, @PathVariable UUID addressId,
                       @AuthenticatedUser CurrentUser user) {
        customers.deleteAddress(customerId, addressId, user.userId(), privileged(user));
    }

    @PostMapping("/{addressId}/default")
    @RequiresPermission(resource = CustomerResources.ADDRESSES, action = Action.UPDATE)
    public ApiResponse<CustomerAddressResponse> makeDefault(@PathVariable UUID customerId,
                                                            @PathVariable UUID addressId,
                                                            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(CustomerWebMapper.toResponse(customers.setDefaultAddress(customerId,
                addressId, user.userId(), privileged(user))));
    }

    private static boolean privileged(CurrentUser user) {
        return user.hasAnyRole(Role.ECOMMERCE_ADMIN, Role.SALES_STAFF);
    }
}
