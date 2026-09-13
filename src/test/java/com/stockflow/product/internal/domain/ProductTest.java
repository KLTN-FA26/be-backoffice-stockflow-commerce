package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the approval workflow (SCRUM-57/WBS 3.1.1.3), same style as {@code StockItemTest}:
 * no Spring, no database, the aggregate's own guarantees only.
 */
class ProductTest {

    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private static Product draftWithCategory() {
        return Product.draft("SOFA-3S-GREY", "Sofa xám", "Grey sofa", CATEGORY,
                null, null, "StockFlow", TaxClass.STANDARD, false, List.of(),
                null, null, null, null);
    }

    private static Product draftWithoutCategory() {
        return Product.draft("SOFA-3S-GREY", "Sofa xám", "Grey sofa", null,
                null, null, "StockFlow", TaxClass.STANDARD, false, List.of(),
                null, null, null, null);
    }

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        @DisplayName("moves DRAFT to PENDING_APPROVAL and records who/when")
        void submitMovesToPendingApproval() {
            Product product = draftWithCategory();

            product.submit(SUBMITTER, NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.PENDING_APPROVAL);
            assertThat(product.submittedBy()).isEqualTo(SUBMITTER);
            assertThat(product.submittedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("rejected without a category (BR-PRD-001 minimal subset)")
        void submitWithoutCategoryRejected() {
            Product product = draftWithoutCategory();

            assertThatThrownBy(() -> product.submit(SUBMITTER, NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
            assertThat(product.status()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("rejected from a non-DRAFT status")
        void submitFromNonDraftRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            assertThatThrownBy(() -> product.submit(SUBMITTER, NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("moves PENDING_APPROVAL to APPROVED when approver differs from submitter")
        void approveByDifferentUserSucceeds() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            product.approve(APPROVER, NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.APPROVED);
            assertThat(product.approvedBy()).isEqualTo(APPROVER);
            assertThat(product.approvedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("registers a ProductEvent.Approved carrying submitter and approver")
        void approvePublishesEvent() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            product.approve(APPROVER, NOW);

            List<?> events = product.pullDomainEvents();
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(ProductEvent.Approved.class);
            ProductEvent.Approved approved = (ProductEvent.Approved) events.get(0);
            assertThat(approved.payload().productId()).isEqualTo(product.id().value());
            assertThat(approved.payload().submittedBy()).isEqualTo(SUBMITTER);
            assertThat(approved.payload().approvedBy()).isEqualTo(APPROVER);
        }

        @Test
        @DisplayName("BR-PRD-003: rejected when the approver is the submitter")
        void selfApprovalRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            assertThatThrownBy(() -> product.approve(SUBMITTER, NOW))
                    .isInstanceOf(SelfApprovalNotAllowedException.class);
            assertThat(product.status()).isEqualTo(ProductStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("rejected from DRAFT (nothing submitted yet)")
        void approveFromDraftRejected() {
            Product product = draftWithCategory();

            assertThatThrownBy(() -> product.approve(APPROVER, NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("moves PENDING_APPROVAL back to DRAFT, clears the submission, keeps the reason")
        void rejectReturnsToDraft() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            product.reject(APPROVER, "Missing dimensions", NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.DRAFT);
            assertThat(product.rejectionReason()).isEqualTo("Missing dimensions");
            assertThat(product.submittedBy()).isNull();
            assertThat(product.submittedAt()).isNull();
        }

        @Test
        @DisplayName("a resubmit after rejection starts clean")
        void resubmitAfterRejectionSucceeds() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);
            product.reject(APPROVER, "Missing dimensions", NOW);

            product.submit(SUBMITTER, NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.PENDING_APPROVAL);
            assertThat(product.rejectionReason()).isNull();
        }

        @Test
        @DisplayName("rejected from DRAFT (nothing submitted yet)")
        void rejectFromDraftRejected() {
            Product product = draftWithCategory();

            assertThatThrownBy(() -> product.reject(APPROVER, "reason", NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("updateDetails")
    class UpdateDetails {

        @Test
        @DisplayName("allowed while DRAFT")
        void updateWhileDraftSucceeds() {
            Product product = draftWithCategory();

            product.updateDetails("Sofa mới", "New sofa", CATEGORY, null, null,
                    "StockFlow", TaxClass.REDUCED, true, List.of(), null, null, null, null);

            assertThat(product.name()).isEqualTo("Sofa mới");
            assertThat(product.taxClass()).isEqualTo(TaxClass.REDUCED);
        }

        @Test
        @DisplayName("rejected once PENDING_APPROVAL")
        void updateWhilePendingApprovalRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            assertThatThrownBy(() -> product.updateDetails("x", "x", CATEGORY, null, null,
                    "StockFlow", TaxClass.STANDARD, false, List.of(), null, null, null, null))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }

        @Test
        @DisplayName("rejected once APPROVED")
        void updateWhileApprovedRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);
            product.approve(APPROVER, NOW);

            assertThatThrownBy(() -> product.updateDetails("x", "x", CATEGORY, null, null,
                    "StockFlow", TaxClass.STANDARD, false, List.of(), null, null, null, null))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("weight/dimensions (SCRUM-74)")
    class WeightAndDimensions {

        @Test
        @DisplayName("set on updateDetails and read back")
        void updateSetsWeightAndDimensions() {
            Product product = draftWithCategory();

            product.updateDetails("Sofa xám", "Grey sofa", CATEGORY, null, null,
                    "StockFlow", TaxClass.STANDARD, false, List.of(),
                    new BigDecimal("25.500"), new BigDecimal("200.00"),
                    new BigDecimal("90.00"), new BigDecimal("85.00"));

            assertThat(product.weightKg()).isEqualByComparingTo("25.500");
            assertThat(product.lengthCm()).isEqualByComparingTo("200.00");
            assertThat(product.widthCm()).isEqualByComparingTo("90.00");
            assertThat(product.heightCm()).isEqualByComparingTo("85.00");
        }

        @Test
        @DisplayName("null weight/dimensions at creation is allowed - not yet known")
        void nullWeightAndDimensionsAllowedAtCreation() {
            Product product = draftWithCategory();

            assertThat(product.weightKg()).isNull();
            assertThat(product.lengthCm()).isNull();
        }

        @Test
        @DisplayName("zero or negative weight rejected")
        void nonPositiveWeightRejected() {
            assertThatThrownBy(() -> Product.draft("SOFA-3S-GREY", "Sofa xám", "Grey sofa",
                    CATEGORY, null, null, "StockFlow", TaxClass.STANDARD, false, List.of(),
                    BigDecimal.ZERO, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> Product.draft("SOFA-3S-GREY", "Sofa xám", "Grey sofa",
                    CATEGORY, null, null, "StockFlow", TaxClass.STANDARD, false, List.of(),
                    new BigDecimal("-1"), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero or negative dimension rejected")
        void nonPositiveDimensionRejected() {
            assertThatThrownBy(() -> Product.draft("SOFA-3S-GREY", "Sofa xám", "Grey sofa",
                    CATEGORY, null, null, "StockFlow", TaxClass.STANDARD, false, List.of(),
                    null, BigDecimal.ZERO, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
