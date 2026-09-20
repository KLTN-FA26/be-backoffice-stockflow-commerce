package com.stockflow.customer.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.customer.api.CheckoutCustomer;
import com.stockflow.customer.api.CustomerAddressSummary;
import com.stockflow.customer.api.CustomerRegistration;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.ListCustomersQuery;
import com.stockflow.customer.api.RegisterCustomerCommand;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.customer.api.UpdateCustomerCommand;
import com.stockflow.customer.internal.domain.AddressType;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerAddress;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.CustomerStatus;
import com.stockflow.customer.internal.repository.CustomerSearchRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.RegisterAccountCommand;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Customer profile and address-book transaction boundary. */
@Service
@Transactional
class CustomerServiceImpl implements CustomerService {

    private static final SortWhitelist SORT = SortWhitelist
            .of("fullName", "email", "createdAt", "status")
            .withDefault("createdAt", Sort.Direction.DESC);

    private final CustomerRepository customers;
    private final CustomerSearchRepository search;
    private final IdentityService identities;

    CustomerServiceImpl(CustomerRepository customers, CustomerSearchRepository search,
                        IdentityService identities) {
        this.customers = customers;
        this.search = search;
        this.identities = identities;
    }

    @Override
    public CustomerRegistration register(RegisterCustomerCommand command) {
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        if (customers.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
        }
        var account = identities.registerCustomer(new RegisterAccountCommand(
                email, command.password(), command.fullName()));
        Customer saved = customers.save(Customer.register(Identifiers.newId(), account.userId(),
                command.fullName(), email, command.phone()));
        return new CustomerRegistration(toSummary(saved), account.token().accessToken(),
                account.token().tokenType(), account.token().expiresInSeconds());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findById(UUID customerId) {
        return customers.findById(customerId).map(CustomerServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findByUserId(UUID userId) {
        return customers.findByUserId(userId).map(CustomerServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> list(ListCustomersQuery query) {
        var pageable = Pages.of(query.page(), query.size(), SORT.parse(query.sort()));
        return Pages.toResponse(search.search(query.search(), query.status(), pageable));
    }

    @Override
    public CustomerSummary update(UpdateCustomerCommand command) {
        Customer customer = requireForUpdate(command.customerId());
        authorise(customer, command.actorUserId(), command.privileged());
        try {
            customer.updateProfile(command.fullName(), command.phone(), command.expectedVersion());
        } catch (IllegalStateException stale) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, stale.getMessage());
        }
        return toSummary(customers.save(customer));
    }

    @Override
    public CustomerSummary changeStatus(UUID customerId, String status) {
        Customer customer = requireForUpdate(customerId);
        try {
            customer.changeStatus(CustomerStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown customer status " + status);
        }
        return toSummary(customers.save(customer));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerAddressSummary> listAddresses(UUID customerId, UUID actorUserId,
                                                      boolean privileged) {
        Customer customer = require(customerId);
        authorise(customer, actorUserId, privileged);
        return customer.addresses().stream().map(address -> toSummary(customerId, address)).toList();
    }

    @Override
    public CustomerAddressSummary addAddress(UUID customerId, UUID actorUserId, boolean privileged,
                                             SaveAddressCommand command) {
        Customer customer = requireForUpdate(customerId);
        authorise(customer, actorUserId, privileged);
        CustomerAddress added = customer.addAddress(toAddress(Identifiers.newId(), command, 0L));
        Customer saved = customers.save(customer);
        return toSummary(customerId, saved.address(added.id()).orElseThrow());
    }

    @Override
    public CustomerAddressSummary updateAddress(UUID customerId, UUID addressId, UUID actorUserId,
                                                boolean privileged, SaveAddressCommand command) {
        Customer customer = requireForUpdate(customerId);
        authorise(customer, actorUserId, privileged);
        CustomerAddress current = customer.address(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
        long expectedVersion = command.expectedVersion() == null ? current.version() : command.expectedVersion();
        try {
            customer.updateAddress(addressId, toAddress(addressId, command, current.version()), expectedVersion);
        } catch (IllegalStateException stale) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, stale.getMessage());
        }
        Customer saved = customers.save(customer);
        return toSummary(customerId, saved.address(addressId).orElseThrow());
    }

    @Override
    public void deleteAddress(UUID customerId, UUID addressId, UUID actorUserId, boolean privileged) {
        Customer customer = requireForUpdate(customerId);
        authorise(customer, actorUserId, privileged);
        if (customer.address(addressId).isEmpty()) throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        customer.deleteAddress(addressId);
        customers.save(customer);
    }

    @Override
    public CustomerAddressSummary setDefaultAddress(UUID customerId, UUID addressId,
                                                    UUID actorUserId, boolean privileged) {
        Customer customer = requireForUpdate(customerId);
        authorise(customer, actorUserId, privileged);
        if (customer.address(addressId).isEmpty()) throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        customer.setDefaultAddress(addressId);
        Customer saved = customers.save(customer);
        return toSummary(customerId, saved.address(addressId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutCustomer resolveCheckout(UUID customerId, UUID shippingAddressId,
                                            UUID billingAddressId) {
        Customer customer = require(customerId);
        if (customer.status() != CustomerStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CONFLICT, "Customer is not allowed to place orders");
        }
        CustomerAddress shipping = shippingAddressId == null
                ? customer.addresses().stream()
                    .filter(a -> a.type() == AddressType.SHIPPING && a.defaultAddress())
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.SHIPPING_ADDRESS_REQUIRED))
                : chosenAddress(customer, shippingAddressId, AddressType.SHIPPING);
        CustomerAddress billing = billingAddressId == null
                ? customer.addresses().stream()
                    .filter(a -> a.type() == AddressType.BILLING && a.defaultAddress())
                    .findFirst().orElse(shipping)
                : chosenAddress(customer, billingAddressId, AddressType.BILLING);
        return new CheckoutCustomer(customer.id(), customer.fullName(), customer.email(), customer.phone(),
                toCheckoutAddress(shipping), toCheckoutAddress(billing));
    }

    /** An address the caller named: 404 if it is not this customer's, 400 if it is the wrong kind. */
    private static CustomerAddress chosenAddress(Customer customer, UUID addressId, AddressType type) {
        CustomerAddress address = customer.address(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
        if (address.type() != type) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Address is not a " + type + " address");
        }
        return address;
    }

    private Customer require(UUID customerId) {
        return customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private Customer requireForUpdate(UUID customerId) {
        return customers.findForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private static void authorise(Customer customer, UUID actorUserId, boolean privileged) {
        if (!privileged && (actorUserId == null || !actorUserId.equals(customer.userId()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static CustomerAddress toAddress(UUID id, SaveAddressCommand command, long version) {
        try {
            return new CustomerAddress(id,
                    AddressType.valueOf(command.type().trim().toUpperCase(Locale.ROOT)),
                    command.recipientName(), command.phone(), command.line1(), command.line2(),
                    command.wardCode(), command.wardName(), command.provinceCode(),
                    command.provinceName(), command.countryCode(), command.postalCode(),
                    command.defaultAddress(), version);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private static CustomerSummary toSummary(Customer customer) {
        return new CustomerSummary(customer.id(), customer.userId(), customer.fullName(), customer.email(),
                customer.phone(), customer.segmentId(), customer.status().name(), customer.version(),
                customer.createdAt());
    }

    private static CustomerAddressSummary toSummary(UUID customerId, CustomerAddress address) {
        return new CustomerAddressSummary(address.id(), customerId, address.type().name(),
                address.recipientName(), address.phone(), address.line1(), address.line2(),
                address.wardCode(), address.wardName(), address.provinceCode(), address.provinceName(),
                address.countryCode(), address.postalCode(), address.defaultAddress(), address.version());
    }

    private static CheckoutCustomer.CheckoutAddress toCheckoutAddress(CustomerAddress address) {
        return new CheckoutCustomer.CheckoutAddress(address.recipientName(), address.phone(),
                address.line1(), address.line2(), address.wardCode(), address.wardName(),
                address.provinceCode(), address.provinceName(), address.countryCode(),
                address.postalCode());
    }
}
