package com.stockflow.customer.internal.entity;

import com.stockflow.customer.internal.domain.CustomerStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a customer row (table {@code customer.customer}). Not the domain model.
 *
 * <p>STARTER ENTITY. Core identity fields only. {@code segmentId} is a plain UUID reference to
 * {@code customer.segment} within the same schema. Addresses live in their own table
 * ({@link AddressJpaEntity}) rather than as a mapped child collection here — a first-pass choice;
 * whether Address should be folded into the Customer aggregate is a TODO for when the write use
 * cases exist.</p>
 */
@Entity
@Table(name = "customer", schema = "customer",
        uniqueConstraints = @UniqueConstraint(name = "uk_customer_email", columnNames = "email"))
public class CustomerJpaEntity extends BaseEntity {

    /** Logical cross-schema reference to identity.app_user; deliberately no database FK. */
    @Column(name = "user_id", unique = true)
    private UUID userId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    /** Same-schema reference to {@code customer.segment}. Nullable: not every customer is segmented. */
    @Column(name = "segment_id")
    private UUID segmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CustomerStatus status;

    /** Required by JPA. Application code uses the id-taking constructor. */
    protected CustomerJpaEntity() {
    }

    public CustomerJpaEntity(UUID id, UUID userId, String fullName, String email, String phone,
                             UUID segmentId, CustomerStatus status) {
        super(id);
        this.userId = userId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.segmentId = segmentId;
        this.status = status;
    }

    public void apply(String fullName, String phone, CustomerStatus status) {
        this.fullName = fullName;
        this.phone = phone;
        this.status = status;
    }

    public UUID getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public UUID getSegmentId() { return segmentId; }
    public CustomerStatus getStatus() { return status; }

    // TODO: loyalty points, tax id, default-address pointer, marketing-consent flags — add when the
    //       corresponding use cases are specified. See docs/business-design for the field list.
}
