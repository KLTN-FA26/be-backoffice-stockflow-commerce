package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.validation.VietnamPhoneValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Customer profile aggregate. Credentials remain exclusively in the identity module. */
public final class Customer extends AggregateRoot {

    private final UUID id;
    private final UUID userId;
    private String fullName;
    private final String email;
    private String phone;
    private final UUID segmentId;
    private CustomerStatus status;
    private final List<CustomerAddress> addresses;
    private final long version;
    private final Instant createdAt;

    public Customer(UUID id, UUID userId, String fullName, String email, String phone,
                    UUID segmentId, CustomerStatus status, List<CustomerAddress> addresses,
                    long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = userId;
        this.fullName = requireNonBlank(fullName, "fullName");
        this.email = normaliseEmail(email);
        this.phone = normaliseOptional(phone);
        this.segmentId = segmentId;
        this.status = Objects.requireNonNull(status, "status");
        this.addresses = new ArrayList<>(addresses == null ? List.of() : addresses);
        this.version = version;
        this.createdAt = createdAt;
        validateDefaults();
    }

    public static Customer register(UUID id, UUID userId, String fullName, String email, String phone) {
        return new Customer(id, Objects.requireNonNull(userId, "userId"), fullName, email, phone,
                null, CustomerStatus.ACTIVE, List.of(), 0L, null);
    }

    public void updateProfile(String fullName, String phone, long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalStateException("Customer profile changed meanwhile");
        }
        this.fullName = requireNonBlank(fullName, "fullName");
        this.phone = normaliseOptional(phone);
    }

    public void changeStatus(CustomerStatus target) {
        this.status = Objects.requireNonNull(target, "target");
    }

    public CustomerAddress addAddress(CustomerAddress address) {
        if (addresses.stream().anyMatch(existing -> existing.id().equals(address.id()))) {
            throw new IllegalArgumentException("Address already belongs to this customer");
        }
        boolean firstOfType = addresses.stream().noneMatch(existing -> existing.type() == address.type());
        CustomerAddress added = address.withDefault(address.defaultAddress() || firstOfType);
        if (added.defaultAddress()) clearDefault(added.type());
        addresses.add(added);
        validateDefaults();
        return added;
    }

    public CustomerAddress updateAddress(UUID addressId, CustomerAddress replacement,
                                         long expectedVersion) {
        int index = indexOf(addressId);
        CustomerAddress current = addresses.get(index);
        if (current.version() != expectedVersion) {
            throw new IllegalStateException("Address changed meanwhile");
        }
        boolean mustRemainDefault = current.defaultAddress()
                && addresses.stream().noneMatch(other -> !other.id().equals(addressId)
                && other.type() == replacement.type() && other.defaultAddress());
        CustomerAddress updated = replacement.withDefault(replacement.defaultAddress() || mustRemainDefault);
        if (updated.defaultAddress()) clearDefault(updated.type());
        addresses.set(index, updated);
        ensureDefault(current.type());
        ensureDefault(updated.type());
        validateDefaults();
        return updated;
    }

    public void deleteAddress(UUID addressId) {
        CustomerAddress removed = addresses.remove(indexOf(addressId));
        if (removed.defaultAddress()) ensureDefault(removed.type());
        validateDefaults();
    }

    public CustomerAddress setDefaultAddress(UUID addressId) {
        int index = indexOf(addressId);
        CustomerAddress selected = addresses.get(index);
        clearDefault(selected.type());
        CustomerAddress updated = selected.withDefault(true);
        addresses.set(index, updated);
        validateDefaults();
        return updated;
    }

    public CustomerAddress requireAddress(UUID addressId, AddressType requiredType) {
        CustomerAddress address = addresses.stream()
                .filter(candidate -> candidate.id().equals(addressId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Address does not belong to customer"));
        if (address.type() != requiredType) {
            throw new IllegalArgumentException("Address is not a " + requiredType + " address");
        }
        return address;
    }

    public CustomerAddress requireDefault(AddressType type) {
        return addresses.stream().filter(a -> a.type() == type && a.defaultAddress()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Customer has no default " + type + " address"));
    }

    private void clearDefault(AddressType type) {
        for (int i = 0; i < addresses.size(); i++) {
            CustomerAddress current = addresses.get(i);
            if (current.type() == type && current.defaultAddress()) {
                addresses.set(i, current.withDefault(false));
            }
        }
    }

    private void ensureDefault(AddressType type) {
        if (addresses.stream().anyMatch(a -> a.type() == type && a.defaultAddress())) return;
        addresses.stream().filter(a -> a.type() == type)
                .min(Comparator.comparing(CustomerAddress::id))
                .ifPresent(chosen -> addresses.set(indexOf(chosen.id()), chosen.withDefault(true)));
    }

    private int indexOf(UUID addressId) {
        for (int i = 0; i < addresses.size(); i++) {
            if (addresses.get(i).id().equals(addressId)) return i;
        }
        throw new IllegalArgumentException("Address does not belong to customer");
    }

    private void validateDefaults() {
        for (AddressType type : AddressType.values()) {
            long defaults = addresses.stream().filter(a -> a.type() == type && a.defaultAddress()).count();
            if (defaults > 1) throw new IllegalArgumentException("Only one default " + type + " address is allowed");
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normaliseEmail(String value) {
        return requireNonBlank(value, "email").toLowerCase(java.util.Locale.ROOT);
    }

    private static String normaliseOptional(String value) {
        return value == null || value.isBlank() ? null : VietnamPhoneValidator.normalise(value);
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public String phone() { return phone; }
    public UUID segmentId() { return segmentId; }
    public CustomerStatus status() { return status; }
    public List<CustomerAddress> addresses() { return List.copyOf(addresses); }
    public Optional<CustomerAddress> address(UUID addressId) {
        return addresses.stream().filter(a -> a.id().equals(addressId)).findFirst();
    }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
