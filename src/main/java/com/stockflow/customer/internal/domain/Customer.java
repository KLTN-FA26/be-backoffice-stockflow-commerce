package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.validation.VietnamPhoneValidator;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.SalesOwnerState;

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
    private String taxCode;
    private UUID segmentId;
    private CustomerStatus status;
    private final List<CustomerAddress> addresses;
    private SalesOwner salesOwner;
    private final long version;
    private final Instant createdAt;

    public Customer(UUID id, UUID userId, String fullName, String email, String phone,
                    UUID segmentId, CustomerStatus status, List<CustomerAddress> addresses,
                    long version, Instant createdAt) {
        this(id, userId, fullName, email, phone, null, segmentId, status, addresses, version, createdAt);
    }

    /** @param taxCode null only for a customer created before tax codes were recorded */
    public Customer(UUID id, UUID userId, String fullName, String email, String phone, String taxCode,
                    UUID segmentId, CustomerStatus status, List<CustomerAddress> addresses,
                    long version, Instant createdAt) {
        this(id, userId, fullName, email, phone, taxCode, segmentId, status, addresses, SalesOwner.unassigned(),
                version, createdAt);
    }

    /** @param salesOwner the stored Sales owner (sales-owner plan §2.1); {@link SalesOwner#unassigned()} for none */
    public Customer(UUID id, UUID userId, String fullName, String email, String phone, String taxCode,
                    UUID segmentId, CustomerStatus status, List<CustomerAddress> addresses, SalesOwner salesOwner,
                    long version, Instant createdAt) {
        this.salesOwner = Objects.requireNonNull(salesOwner, "salesOwner");
        this.id = Objects.requireNonNull(id, "id");
        this.userId = userId;
        this.fullName = requireNonBlank(fullName, "fullName");
        this.email = normaliseEmail(email);
        this.phone = normaliseOptional(phone);
        this.taxCode = taxCode == null ? null : TaxCode.normalise(taxCode);
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

    /**
     * A business customer Sales onboarded (SCRUM-450, kltn-docs 18 §4.1), linked to the account its
     * contact will activate. It cannot sign in, see prices or order until then.
     *
     * @param companyName the business name; the contact's own name stays on their account
     * @param taxCode     optional: household businesses and small shops are customers too and may
     *                    supply it later (decision 2026-10-12); normalised by {@link TaxCode} when given
     */
    public static Customer onboard(UUID id, UUID userId, String companyName, String email, String phone,
                                   String taxCode) {
        return new Customer(id, Objects.requireNonNull(userId, "userId"), companyName, email, phone,
                taxCode == null || taxCode.isBlank() ? null : TaxCode.normalise(taxCode), null,
                CustomerStatus.PENDING_ACTIVATION, List.of(), 0L, null);
    }

    /**
     * The contact redeemed the invitation. Only a pending customer activates: a blocked or closed
     * one stays as it is - activating an account must not lift a staff decision.
     *
     * @throws IllegalStateException for any other status
     */
    public void activatePortal() {
        if (status != CustomerStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("Only a customer pending activation can activate, not " + status);
        }
        this.status = CustomerStatus.ACTIVE;
    }

    /**
     * Sales records or corrects the tax code. It is never cleared: a customer that has one keeps one.
     *
     * @throws IllegalArgumentException for a malformed code
     */
    public void changeTaxCode(String taxCode) {
        this.taxCode = TaxCode.normalise(taxCode);
    }

    /**
     * Puts the customer in a group, or none (P7: the Sales Manager's decision). Prices already given - a
     * quote, an order - keep theirs; only later pricing reads the new group.
     *
     * @param segmentId an existing group (the caller checks it), or null for none
     * @throws IllegalStateException when the customer changed since {@code expectedVersion} was read
     */
    public void assignSegment(UUID segmentId, long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalStateException("Customer changed meanwhile");
        }
        this.segmentId = segmentId;
    }

    public void updateProfile(String fullName, String phone, long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalStateException("Customer profile changed meanwhile");
        }
        this.fullName = requireNonBlank(fullName, "fullName");
        this.phone = normaliseOptional(phone);
    }

    /**
     * A staff status change. PENDING_ACTIVATION is entered only by onboarding and left for ACTIVE only
     * by activation (kltn-docs 18 §5): staff may close a pending customer (INACTIVE), nothing else. A
     * closed customer leaves INACTIVE only through {@link #reopen}, which needs a reason.
     *
     * @throws IllegalStateException for a transition the lifecycle does not have
     */
    public void changeStatus(CustomerStatus target) {
        changeStatus(target, null);
    }

    /**
     * {@code reason} is required to lift a block (BLOCKED to ACTIVE, decision 2026-10-17), as it is to reopen a closed
     * customer: both give a customer back what a manager had taken away; the history keeps why.
     *
     * @throws IllegalArgumentException lifting a block without a reason
     */
    public void changeStatus(CustomerStatus target, String reason) {
        Objects.requireNonNull(target, "target");
        if (target == status) return;
        if (status == CustomerStatus.BLOCKED && target == CustomerStatus.ACTIVE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required to lift a customer's block");
        }
        if (target == CustomerStatus.PENDING_ACTIVATION
                || (status == CustomerStatus.PENDING_ACTIVATION && target != CustomerStatus.INACTIVE)
                || status == CustomerStatus.INACTIVE) {
            throw new IllegalStateException("A customer cannot go from %s to %s".formatted(status, target));
        }
        this.status = target;
    }

    /**
     * A manager reopens a closed customer (decision 2026-10-12; kltn-docs 18 §5 itself ends Closed at
     * Anonymized). The customer returns to where its contact left off: ACTIVE if the portal account was
     * activated (or there is none), PENDING_ACTIVATION if it never was, so Sales can invite again.
     *
     * @param portalActivated whether the contact's account has a password
     * @throws IllegalStateException unless the customer is INACTIVE
     * @throws IllegalArgumentException without a reason
     */
    public void reopen(String reason, boolean portalActivated) {
        if (status != CustomerStatus.INACTIVE) {
            throw new IllegalStateException("Only a closed customer can be reopened, not " + status);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to reopen a closed customer");
        }
        this.status = userId == null || portalActivated ? CustomerStatus.ACTIVE : CustomerStatus.PENDING_ACTIVATION;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sales owner (sales-owner plan 2026-11-28). Each change returns the history row to keep, or nothing when nothing
    // changed; the service writes the row in the same transaction and publishes the event. The same rules are stated
    // in the database (V20270128000100): the state agrees with the columns, an assigned owner is an active account that
    // is not a portal customer, OTHER carries a note.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Gives the customer {@code newOwner} (BR-S01 change by a manager, BR-S02 at creation, a lead's Sales). Whether the
     * new owner is an active Sales agent (BR-S04) is the service's check: it needs the accounts.
     *
     * @param expectedVersion the version the caller read (case 15: of two managers changing it at once, the later loses)
     * @param batchId         the transfer batch, or null
     * @return the change, or empty when {@code newOwner} already owns it (nothing to record)
     * @throws BusinessException {@code SALES_OWNER_VERSION_CONFLICT}; {@code SALES_OWNER_CHANGE_REASON_REQUIRED}
     *         (BR-S03: no reason, or OTHER without a note); {@code VALIDATION_FAILED} without a new owner
     */
    public Optional<SalesOwnerChange> assignSalesOwner(UUID newOwner, SalesOwnerChangeReason reason, String note,
                                                       long expectedVersion, UUID by, Instant at, UUID batchId) {
        if (version != expectedVersion) {
            throw new BusinessException(ErrorCode.SALES_OWNER_VERSION_CONFLICT,
                    "Customer %s is at version %d, not %d".formatted(id, version, expectedVersion));
        }
        String why = requireReason(reason, note);
        if (newOwner == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the new sales owner");
        }
        if (salesOwner.isAssignedTo(newOwner)) {
            return Optional.empty();
        }
        return Optional.of(moveTo(newOwner, reason, why, by, at, batchId));
    }

    /**
     * A bulk or temporary transfer, or its return (BR-S09, plan §10.1): moves the customer to {@code newOwner} only if
     * it is still {@code expectedFrom}'s - assigned to them, or waiting for an owner after they left. A customer someone
     * changed meanwhile is skipped, not overwritten (case 9); the row lock the service holds makes "still" mean now.
     *
     * @return the change, or empty when the customer is no longer {@code expectedFrom}'s (the caller reports it skipped)
     * @throws BusinessException {@code SALES_OWNER_CHANGE_REASON_REQUIRED}
     */
    public Optional<SalesOwnerChange> transferSalesOwner(UUID expectedFrom, UUID newOwner,
                                                         SalesOwnerChangeReason reason, String note, UUID by,
                                                         Instant at, UUID batchId) {
        String why = requireReason(reason, note);
        Objects.requireNonNull(newOwner, "newOwner");
        if (salesOwner.state() == SalesOwnerState.UNASSIGNED || !salesOwner.userId().equals(expectedFrom)
                || salesOwner.isAssignedTo(newOwner)) {
            return Optional.empty();
        }
        return Optional.of(moveTo(newOwner, reason, why, by, at, batchId));
    }

    /**
     * BR-S08: the owner's account stopped working. The customer waits for a manager; the owner who left is kept.
     *
     * @return the change, or empty when {@code formerOwner} is not the assigned owner any more (already marked, or
     *         moved meanwhile): a retried event changes nothing
     */
    public Optional<SalesOwnerChange> markNeedsSalesOwner(UUID formerOwner, UUID by, Instant at) {
        if (!salesOwner.isAssignedTo(formerOwner)) {
            return Optional.empty();
        }
        salesOwner = new SalesOwner(formerOwner, SalesOwnerState.NEEDS_ASSIGNMENT, salesOwner.since());
        return Optional.of(new SalesOwnerChange(Identifiers.newId(), id, formerOwner, null,
                SalesOwnerState.NEEDS_ASSIGNMENT, SalesOwnerChangeReason.STAFF_LEFT, null, by, at, null));
    }

    /**
     * BR-S05 / D2: a salesperson takes the customer from the queue. Allowed only when it has no active owner: never had
     * one, its owner left, or {@code ownerActive} says the assigned owner's account no longer works.
     *
     * @param ownerActive whether the assigned owner's account is ACTIVE (the service reads it; ignored otherwise)
     * @return the change, or empty when the claimer already owns it
     * @throws BusinessException {@code CUSTOMER_UNASSIGNED_CLAIM_ONLY} when another active salesperson owns it (case 18)
     */
    public Optional<SalesOwnerChange> claimIfUnowned(UUID claimer, boolean ownerActive, Instant at) {
        Objects.requireNonNull(claimer, "claimer");
        if (salesOwner.isAssignedTo(claimer)) {
            return Optional.empty();
        }
        if (salesOwner.state() == SalesOwnerState.ASSIGNED && ownerActive) {
            throw new BusinessException(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY,
                    "Customer %s is owned by another salesperson".formatted(id));
        }
        return Optional.of(moveTo(claimer, SalesOwnerChangeReason.CLAIMED_FROM_QUEUE, null, claimer, at, null));
    }

    private SalesOwnerChange moveTo(UUID newOwner, SalesOwnerChangeReason reason, String note, UUID by, Instant at,
                                    UUID batchId) {
        Objects.requireNonNull(at, "at");
        UUID from = salesOwner.userId();
        salesOwner = new SalesOwner(newOwner, SalesOwnerState.ASSIGNED, at);
        return new SalesOwnerChange(Identifiers.newId(), id, from, newOwner, SalesOwnerState.ASSIGNED, reason, note, by,
                at, batchId);
    }

    /** BR-S03: a reason always; OTHER says what in a note. Returns the note trimmed, or null. */
    private static String requireReason(SalesOwnerChangeReason reason, String note) {
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (reason == null || (reason == SalesOwnerChangeReason.OTHER && trimmed == null)) {
            throw new BusinessException(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED);
        }
        if (trimmed != null && trimmed.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A note is at most 1000 characters");
        }
        return trimmed;
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
    public String taxCode() { return taxCode; }
    public UUID segmentId() { return segmentId; }
    public CustomerStatus status() { return status; }
    public List<CustomerAddress> addresses() { return List.copyOf(addresses); }
    public Optional<CustomerAddress> address(UUID addressId) {
        return addresses.stream().filter(a -> a.id().equals(addressId)).findFirst();
    }
    public SalesOwner salesOwner() { return salesOwner; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
