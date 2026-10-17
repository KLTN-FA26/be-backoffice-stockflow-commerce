package com.stockflow.customer.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.customer.api.ChangeCustomerStatusCommand;
import com.stockflow.customer.api.CheckoutCustomer;
import com.stockflow.customer.api.CustomerStatusEntry;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.customer.api.CustomerAddressSummary;
import com.stockflow.customer.api.CustomerRegistration;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.ListCustomersQuery;
import com.stockflow.customer.api.RegisterCustomerCommand;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.customer.api.UpdateCustomerCommand;
import com.stockflow.customer.internal.domain.AddressType;
import com.stockflow.customer.internal.domain.CreditProfile;
import com.stockflow.customer.internal.domain.CreditProfileRepository;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerAddress;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.CustomerStatus;
import com.stockflow.customer.internal.domain.CustomerStatusChange;
import com.stockflow.customer.internal.domain.CustomerStatusHistory;
import com.stockflow.customer.internal.repository.CustomerSearchRepository;
import com.stockflow.identity.api.PortalAccountService;
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
    private final PortalAccountService accounts;
    private final CreditProfileRepository creditProfiles;
    private final CustomerStatusHistory history;
    private final java.time.Clock clock;

    CustomerServiceImpl(CustomerRepository customers, CustomerSearchRepository search,
                        PortalAccountService accounts, CreditProfileRepository creditProfiles,
                        CustomerStatusHistory history, java.time.Clock clock) {
        this.customers = customers;
        this.search = search;
        this.accounts = accounts;
        this.creditProfiles = creditProfiles;
        this.history = history;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ commercial terms (SCRUM-427)

    @Override
    @Transactional(readOnly = true)
    public CreditTerms creditTerms(UUID customerId) {
        if (!customers.existsById(customerId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId);
        }
        return termsOf(customerId);
    }

    @Override
    public CreditTerms lockCreditTerms(UUID customerId) {
        customers.findForUpdate(customerId).orElseThrow(() ->
                new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId));
        return termsOf(customerId);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.APPROVE,
            resourceType = "customer-credit-profile", resourceId = "#command.customerId()")
    public CreditTerms saveCreditTerms(SaveCreditTermsCommand command) {
        customers.findForUpdate(command.customerId()).orElseThrow(() ->
                new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + command.customerId()));
        var existing = creditProfiles.findByCustomerId(command.customerId());
        long current = existing.map(CreditProfile::version).orElse(-1L);
        if (command.expectedVersion() != null && command.expectedVersion() != current) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The terms of customer %s changed since they were read".formatted(command.customerId()));
        }
        var now = clock.instant();
        CreditProfile profile = existing.map(found -> {
            found.change(command.allowPrepaid(), command.allowDeposit(), command.allowCredit(), command.defaultTerm(),
                    command.depositPercent(), command.creditLimit(), command.creditTermDays(), command.approvedBy(),
                    now, command.note());
            return found;
        }).orElseGet(() -> CreditProfile.create(command.customerId(), command.allowPrepaid(), command.allowDeposit(),
                command.allowCredit(), command.defaultTerm(), command.depositPercent(), command.creditLimit(),
                command.creditTermDays(), command.approvedBy(), now, command.note()));
        return toTerms(creditProfiles.save(profile));
    }

    private CreditTerms termsOf(UUID customerId) {
        return creditProfiles.findByCustomerId(customerId).map(CustomerServiceImpl::toTerms)
                .orElseGet(() -> CreditTerms.prepaidOnly(customerId));
    }

    private static CreditTerms toTerms(CreditProfile p) {
        return new CreditTerms(p.customerId(), true, p.allowPrepaid(), p.allowDeposit(), p.allowCredit(), p.defaultTerm(),
                p.depositPercent(), p.creditLimit(), p.creditTermDays(), p.currency(), p.approvedBy(), p.approvedAt(),
                p.note(), p.version());
    }

    @Override
    public CustomerRegistration register(RegisterCustomerCommand command) {
        throw new BusinessException(ErrorCode.CUSTOMER_REGISTRATION_DISABLED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findById(UUID customerId) {
        return customers.findById(customerId).map(CustomerServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean segmentExists(UUID segmentId) {
        return customers.segmentExists(segmentId);
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
        return Pages.toResponse(search.search(query.search(), query.status(), query.segmentId(), pageable));
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
        if (command.privileged() && command.taxCode() != null) {
            try {
                customer.changeTaxCode(command.taxCode());
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
            requireTaxCodeFree(customer);
        }
        return saveGuardingTaxCode(customer);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "customer", resourceId = "#command.customerId()")
    public CustomerSummary changeStatus(ChangeCustomerStatusCommand command) {
        Customer customer = requireForUpdate(command.customerId());
        CustomerStatus from = customer.status();
        CustomerStatus target;
        try {
            target = CustomerStatus.valueOf(command.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown customer status " + command.status());
        }
        try {
            if (from == CustomerStatus.INACTIVE && target == CustomerStatus.ACTIVE) {
                boolean activated = customer.userId() == null || !accounts.awaitingActivation(customer.userId());
                customer.reopen(command.reason(), activated);
            } else {
                customer.changeStatus(target);
            }
        } catch (IllegalStateException refused) {
            throw new BusinessException(ErrorCode.INVALID_CUSTOMER_STATUS_TRANSITION, refused.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
        requireTaxCodeFree(customer);
        CustomerSummary saved = saveGuardingTaxCode(customer);
        syncPortalAccess(customer);
        if (customer.status() != from) {
            history.record(new CustomerStatusChange(Identifiers.newId(), customer.id(), from, customer.status(),
                    command.reason(), command.actorUserId(), clock.instant()));
        }
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerStatusEntry> statusHistory(UUID customerId) {
        if (!customers.existsById(customerId)) throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        return history.of(customerId).stream()
                .map(change -> new CustomerStatusEntry(change.from() == null ? null : change.from().name(),
                        change.to().name(), change.reason(), change.changedBy(), change.changedAt()))
                .toList();
    }

    /**
     * kltn-docs 18 §5: a Suspended (BLOCKED) customer may not sign in, and a Closed (INACTIVE) one has
     * closed its account; an ACTIVE one may. The contact's account follows in this transaction - the
     * customer row is locked first, the account second, the order resend and activation use too - so
     * a block ends every live session at commit and a lifted block lets the same password in again.
     * Reinstating keeps no session: the ones a block or closure ended stay ended. A pending customer
     * reopened after closure gets its (passwordless) account back so an invitation can be redeemed.
     */
    private void syncPortalAccess(Customer customer) {
        if (customer.userId() == null) return;
        // A switch expression, so a new status does not compile until somebody decides whether it signs in.
        boolean signsIn = switch (customer.status()) {
            case ACTIVE, PENDING_ACTIVATION -> true;
            case BLOCKED, INACTIVE -> false;
        };
        if (signsIn) {
            accounts.reinstate(customer.userId());
        } else {
            accounts.suspend(customer.userId());
        }
    }

    /**
     * BR-01, decided 16/10/2026: one customer record per tax code in every status. A BLOCKED customer cannot come back
     * under a new record, and a closed one is reopened rather than created again. Checked first for a clear 409, and
     * enforced by uk_customer_tax_code (V20261017000830) for the writer that races past the check.
     */
    private void requireTaxCodeFree(Customer customer) {
        if (customer.taxCode() != null && customers.isTaxCodeTaken(customer.taxCode(), customer.id())) {
            throw new BusinessException(ErrorCode.CUSTOMER_TAX_CODE_ALREADY_EXISTS);
        }
    }

    private CustomerSummary saveGuardingTaxCode(Customer customer) {
        try {
            return toSummary(customers.save(customer));
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            if (CustomerConstraints.isTaxCodeTaken(race)) {
                throw new BusinessException(ErrorCode.CUSTOMER_TAX_CODE_ALREADY_EXISTS);
            }
            throw race;
        }
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

    static CustomerSummary toSummary(Customer customer) {
        return new CustomerSummary(customer.id(), customer.userId(), customer.fullName(), customer.email(),
                customer.phone(), customer.taxCode(), customer.segmentId(), customer.status().name(), customer.version(),
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
