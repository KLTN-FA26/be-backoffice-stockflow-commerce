package com.stockflow.customer.internal.repository;

import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerAddress;
import com.stockflow.customer.internal.entity.AddressJpaEntity;
import com.stockflow.customer.internal.entity.CustomerJpaEntity;

import java.util.List;

final class CustomerPersistenceMapper {

    private CustomerPersistenceMapper() {
    }

    static Customer toDomain(CustomerJpaEntity entity, List<AddressJpaEntity> addresses) {
        return new Customer(entity.getId(), entity.getUserId(), entity.getFullName(), entity.getEmail(),
                entity.getPhone(), entity.getSegmentId(), entity.getStatus(),
                addresses.stream().map(CustomerPersistenceMapper::toDomain).toList(),
                entity.getVersion(), entity.getCreatedAt());
    }

    static CustomerAddress toDomain(AddressJpaEntity entity) {
        return new CustomerAddress(entity.getId(), entity.getType(), entity.getRecipientName(),
                entity.getPhone(), entity.getLine1(), entity.getLine2(), entity.getWardCode(),
                entity.getWardName(), entity.getProvinceCode(), entity.getProvinceName(),
                entity.getCountryCode(), entity.getPostalCode(), entity.isDefaultAddress(),
                entity.getVersion());
    }

    static CustomerJpaEntity toNewEntity(Customer customer) {
        return new CustomerJpaEntity(customer.id(), customer.userId(), customer.fullName(),
                customer.email(), customer.phone(), customer.segmentId(), customer.status());
    }

    static AddressJpaEntity toNewEntity(java.util.UUID customerId, CustomerAddress address) {
        return new AddressJpaEntity(address.id(), customerId, address.type(), address.recipientName(),
                address.phone(), address.line1(), address.line2(), address.wardCode(), address.wardName(),
                address.provinceCode(), address.provinceName(), address.countryCode(), address.postalCode(),
                address.defaultAddress());
    }

    static void apply(Customer customer, CustomerJpaEntity entity) {
        entity.apply(customer.fullName(), customer.phone(), customer.status());
    }

    static void apply(CustomerAddress address, AddressJpaEntity entity) {
        entity.apply(address.type(), address.recipientName(), address.phone(), address.line1(),
                address.line2(), address.wardCode(), address.wardName(), address.provinceCode(),
                address.provinceName(), address.countryCode(), address.postalCode(),
                address.defaultAddress());
    }

    static CustomerSummary toSummary(CustomerJpaEntity entity) {
        return new CustomerSummary(entity.getId(), entity.getUserId(), entity.getFullName(),
                entity.getEmail(), entity.getPhone(), entity.getSegmentId(), entity.getStatus().name(),
                entity.getVersion(), entity.getCreatedAt());
    }
}
