package com.stockflow.customer.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Specs;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.internal.domain.AddressType;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.entity.AddressJpaEntity;
import com.stockflow.customer.internal.entity.CustomerJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

@Repository
class CustomerRepositoryAdapter implements CustomerRepository, CustomerSearchRepository {

    private final CustomerJpaRepository customers;
    private final AddressJpaRepository addresses;

    CustomerRepositoryAdapter(CustomerJpaRepository customers, AddressJpaRepository addresses) {
        this.customers = customers;
        this.addresses = addresses;
    }

    @Override
    public Optional<Customer> findById(UUID id) {
        return customers.findById(id).map(this::withAddresses);
    }

    @Override
    public Optional<Customer> findForUpdate(UUID id) {
        return customers.lockById(id).map(this::withAddresses);
    }

    @Override
    public Optional<Customer> findByUserId(UUID userId) {
        return customers.findByUserId(userId).map(this::withAddresses);
    }

    @Override
    public Optional<Customer> findByEmail(String email) {
        return customers.findByEmailIgnoreCase(email).map(this::withAddresses);
    }

    @Override
    public boolean existsById(UUID id) {
        return customers.existsById(id);
    }

    @Override
    public Customer save(Customer customer) {
        CustomerJpaEntity row = customers.findById(customer.id()).orElseGet(() ->
                customers.save(CustomerPersistenceMapper.toNewEntity(customer)));
        CustomerPersistenceMapper.apply(customer, row);
        customers.save(row);

        var existing = new HashMap<UUID, AddressJpaEntity>();
        addresses.findByCustomerIdOrderByCreatedAtAsc(customer.id())
                .forEach(address -> existing.put(address.getId(), address));

        for (AddressType type : AddressType.values()) addresses.clearDefault(customer.id(), type);

        var retained = new HashSet<UUID>();
        for (var address : customer.addresses()) {
            retained.add(address.id());
            AddressJpaEntity addressRow = existing.get(address.id());
            if (addressRow == null) {
                addresses.save(CustomerPersistenceMapper.toNewEntity(customer.id(), address));
            } else {
                CustomerPersistenceMapper.apply(address, addressRow);
                addresses.save(addressRow);
            }
        }
        existing.values().stream().filter(rowToDelete -> !retained.contains(rowToDelete.getId()))
                .forEach(addresses::delete);
        addresses.flush();
        return withAddresses(row);
    }

    @Override
    public Page<CustomerSummary> search(String search, String status, Pageable pageable) {
        Specification<CustomerJpaEntity> specification = Specification
                .<CustomerJpaEntity>where(Specs.contains("fullName", search))
                .or(Specs.contains("email", search))
                .or(Specs.contains("phone", search));
        if (status != null && !status.isBlank()) {
            try {
                specification = specification.and(Specs.eq("status",
                        com.stockflow.customer.internal.domain.CustomerStatus.valueOf(status)));
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown customer status " + status);
            }
        }
        return customers.findAll(specification, pageable).map(CustomerPersistenceMapper::toSummary);
    }

    private Customer withAddresses(CustomerJpaEntity entity) {
        return CustomerPersistenceMapper.toDomain(entity,
                addresses.findByCustomerIdOrderByCreatedAtAsc(entity.getId()));
    }
}
