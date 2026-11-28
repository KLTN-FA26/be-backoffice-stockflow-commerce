package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.internal.domain.Lead;
import com.stockflow.customer.internal.domain.OwnerChangeRequest;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the sales-owner plan's endpoints (API contract "Endpoints", BE-A), kept together: each is a
 * handful of fields. A missing reason is left to the service so it answers the contract's own code
 * ({@code SALES_OWNER_CHANGE_REASON_REQUIRED}) rather than a generic validation error.
 */
public final class SalesOwnerRequests {

    private SalesOwnerRequests() {
    }

    /** {@code PUT /customers/{id}/sales-owner}: {@code version} is the customer's, as the card read it (case 15). */
    public record AssignSalesOwner(UUID salesOwnerId, SalesOwnerChangeReason reason, @Size(max = 1000) String note,
                                   boolean notifyCustomer, @NotNull Long version) {
    }

    /** {@code POST /customers/sales-owner-transfers} (BR-S09, §10.1). */
    public record Transfer(@NotNull UUID requestId, @NotNull UUID fromUserId, List<UUID> toUserIds,
                           List<UUID> customerIds, SalesOwnerChangeReason reason, @Size(max = 1000) String note,
                           boolean temporary, boolean notifyCustomer) {
    }

    /** {@code PUT /sales/staff/{userId}/assignment-settings} (D13); {@code version} optional. */
    public record AssignmentSettings(boolean paused, @Min(0) @Max(1000) Integer capacityPerMonth, Long version) {
    }

    /** {@code POST /customers/me/sales-owner-change-requests} (§10.5, D11). */
    public record AskOwnerChange(OwnerChangeRequest.Reason reason, @Size(max = 1000) String note,
                                 UUID preferredSalesUserId) {
    }

    /** {@code POST /sales-owner-change-requests/{id}/approval}. */
    public record ApproveOwnerChange(UUID newSalesOwnerId, @Size(max = 1000) String note) {
    }

    /** {@code POST /sales-owner-change-requests/{id}/rejection}: the note is required (the customer reads it). */
    public record RejectOwnerChange(@Size(max = 1000) String note) {
    }

    /** {@code PUT /leads/{id}/assignment}. */
    public record AssignLead(UUID salesUserId) {
    }

    /** {@code POST /leads/{id}/contact}. */
    public record ContactLead(@Size(max = 1000) String note) {
    }

    /** {@code POST /leads/{id}/disqualification}: the reason is required. */
    public record DisqualifyLead(@Size(max = 1000) String reason) {
    }

    /**
     * {@code POST /public/leads}: the storefront's form, with its limits (API contract "Additions from the storefront
     * agent"). {@code website} is the honeypot: the storefront never sends it.
     */
    public record SubmitLead(
            @NotBlank @Size(max = 200) String companyName,
            @Pattern(regexp = "\\d{10}(-\\d{3})?", message = "A tax code is 10 digits, or 10-3") String taxCode,
            @NotBlank @Size(max = 100) String contactName,
            @Size(max = 100) String contactTitle,
            @VietnamPhone String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 100) String province,
            @Size(max = 4) List<Lead.@NotNull Interest> interests,
            @Size(max = 100) String monthlyVolume,
            @Size(max = 1000) String note,
            @NotNull @AssertTrue Boolean consent,
            @Size(max = 500) String website) {
    }

    /** The public form's answer, whatever became of the submission. */
    public record LeadReceipt(boolean received) {
    }
}
