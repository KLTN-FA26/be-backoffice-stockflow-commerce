package com.stockflow.customer.api;

import com.stockflow.common.api.PageResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the customer module — the only package other modules may import.
 *
 * <p>STARTER STUB. Replace the empty body with the module's real use cases. Two rules from
 * {@code docs/adding-a-module.md} §1:</p>
 * <ul>
 *   <li>declare only what other modules actually call — the shortest surface that works;</li>
 *   <li>every parameter and return type is a record or enum declared in THIS package, never a
 *       domain object or JPA entity. {@code ArchitectureTest.theApiPackageLeaksNothingInternal}
 *       and {@code theApiPublishesNoEntities} enforce it.</li>
 * </ul>
 *
 * <p>To finish the module: add a migration, then the entity + repository adapter, then a
 * controller — see {@code docs/adding-a-module.md} §4.</p>
 */
public interface CustomerService {

    CustomerRegistration register(RegisterCustomerCommand command);

    Optional<CustomerSummary> findById(UUID customerId);

    Optional<CustomerSummary> findByUserId(UUID userId);

    PageResponse<CustomerSummary> list(ListCustomersQuery query);

    CustomerSummary update(UpdateCustomerCommand command);

    CustomerSummary changeStatus(UUID customerId, String status);

    List<CustomerAddressSummary> listAddresses(UUID customerId, UUID actorUserId, boolean privileged);

    CustomerAddressSummary addAddress(UUID customerId, UUID actorUserId, boolean privileged,
                                      SaveAddressCommand command);

    CustomerAddressSummary updateAddress(UUID customerId, UUID addressId, UUID actorUserId,
                                         boolean privileged, SaveAddressCommand command);

    void deleteAddress(UUID customerId, UUID addressId, UUID actorUserId, boolean privileged);

    CustomerAddressSummary setDefaultAddress(UUID customerId, UUID addressId, UUID actorUserId,
                                             boolean privileged);

    /** Resolves defaults and returns immutable checkout data without exposing internal entities. */
    CheckoutCustomer resolveCheckout(UUID customerId, UUID shippingAddressId, UUID billingAddressId);
}
