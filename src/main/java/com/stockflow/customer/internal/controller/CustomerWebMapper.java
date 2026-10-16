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

    /** With the tax code (or personal id) in full: the staff detail and edit, where it is entered and checked. */
    static CustomerResponse toResponse(CustomerSummary summary) {
        return toResponse(summary, summary.taxCode());
    }

    /**
     * With the tax code masked (review of #109, item 9): a company's tax code or a household's personal id (CCCD) is
     * not needed in full to recognise the customer in a list, after creating or inviting it, or on its own profile,
     * where the customer cannot change it.
     */
    static CustomerResponse toMaskedResponse(CustomerSummary summary) {
        return toResponse(summary, maskTaxCode(summary.taxCode()));
    }

    private static CustomerResponse toResponse(CustomerSummary summary, String taxCode) {
        return new CustomerResponse(summary.customerId(), summary.userId(), summary.fullName(),
                summary.email(), summary.phone(), taxCode, summary.segmentId(), summary.status(),
                summary.version(), summary.createdAt());
    }

    /**
     * The first three and the last three characters kept, every digit between them a {@code *}; a separator stays,
     * so 0312345678-001 reads 031*******-001 and a 12-digit CCCD 001******345. Short codes are masked whole.
     */
    static String maskTaxCode(String taxCode) {
        if (taxCode == null) {
            return null;
        }
        if (taxCode.length() <= 6) {
            return "*".repeat(taxCode.length());
        }
        StringBuilder masked = new StringBuilder(taxCode.length());
        for (int i = 0; i < taxCode.length(); i++) {
            char c = taxCode.charAt(i);
            masked.append(i < 3 || i >= taxCode.length() - 3 || !Character.isLetterOrDigit(c) ? c : '*');
        }
        return masked.toString();
    }

    static CustomerRegistrationResponse toResponse(CustomerRegistration registration) {
        return new CustomerRegistrationResponse(toMaskedResponse(registration.customer()),
                registration.accessToken(), registration.tokenType(), registration.expiresInSeconds());
    }

    static CustomerAddressResponse toResponse(CustomerAddressSummary address) {
        return new CustomerAddressResponse(address.addressId(), address.customerId(), address.type(),
                address.recipientName(), address.phone(), address.line1(), address.line2(),
                address.wardCode(), address.wardName(), address.provinceCode(), address.provinceName(),
                address.countryCode(), address.postalCode(), address.defaultAddress(), address.version());
    }
}
