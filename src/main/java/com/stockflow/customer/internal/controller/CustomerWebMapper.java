package com.stockflow.customer.internal.controller;

import com.stockflow.customer.api.CustomerAddressSummary;
import com.stockflow.customer.api.CustomerRegistration;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.RegisterCustomerCommand;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.customer.internal.controller.dto.CustomerAddressResponse;
import com.stockflow.customer.internal.controller.dto.CustomerRegistrationResponse;
import com.stockflow.customer.internal.controller.dto.CustomerResponse;
import com.stockflow.customer.internal.controller.dto.RegisterCustomerRequest;
import com.stockflow.customer.internal.controller.dto.SaveAddressRequest;

final class CustomerWebMapper {
    private CustomerWebMapper() {
    }

    static RegisterCustomerCommand toCommand(RegisterCustomerRequest request) {
        return new RegisterCustomerCommand(request.fullName(), request.email(), request.phone(), request.password());
    }

    static SaveAddressCommand toCommand(SaveAddressRequest request) {
        return new SaveAddressCommand(request.type(), request.recipientName(), request.phone(),
                request.line1(), request.line2(), request.wardCode(), request.wardName(),
                request.provinceCode(), request.provinceName(), request.countryCode(),
                request.postalCode(), request.defaultAddress(), request.version());
    }

    static CustomerResponse toResponse(CustomerSummary summary) {
        return new CustomerResponse(summary.customerId(), summary.userId(), summary.fullName(),
                summary.email(), summary.phone(), summary.segmentId(), summary.status(),
                summary.version(), summary.createdAt());
    }

    static CustomerRegistrationResponse toResponse(CustomerRegistration registration) {
        return new CustomerRegistrationResponse(toResponse(registration.customer()),
                registration.accessToken(), registration.tokenType(), registration.expiresInSeconds());
    }

    static CustomerAddressResponse toResponse(CustomerAddressSummary address) {
        return new CustomerAddressResponse(address.addressId(), address.customerId(), address.type(),
                address.recipientName(), address.phone(), address.line1(), address.line2(),
                address.wardCode(), address.wardName(), address.provinceCode(), address.provinceName(),
                address.countryCode(), address.postalCode(), address.defaultAddress(), address.version());
    }
}
