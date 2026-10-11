package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.TaxClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        return Product.draft("CUP-12OZ", "Ly giấy 12oz", "Paper cup 12oz", null, CATEGORY,
                null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE);
    }

    private static Product draftWithoutCategory() {
        return Product.draft("CUP-12OZ", "Ly giấy 12oz", "Paper cup 12oz", null, null,
                null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE);
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

            product.updateDetails("Ly mới", "New cup", null, CATEGORY, null, null, null,
                    TaxClass.REDUCED, null);

            assertThat(product.name()).isEqualTo("Ly mới");
            assertThat(product.kind()).isEqualTo(ProductKind.STANDARD);
            assertThat(product.taxClass()).isEqualTo(TaxClass.REDUCED);
        }

        @Test
        @DisplayName("rejected once PENDING_APPROVAL")
        void updateWhilePendingApprovalRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            assertThatThrownBy(() -> product.updateDetails("x", "x", null, CATEGORY, null, null, null,
                    TaxClass.STANDARD, ProductKind.STANDARD))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }

        @Test
        @DisplayName("rejected once APPROVED")
        void updateWhileApprovedRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);
            product.approve(APPROVER, NOW);

            assertThatThrownBy(() -> product.updateDetails("x", "x", null, CATEGORY, null, null, null,
                    TaxClass.STANDARD, ProductKind.STANDARD))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("code")
    class Code {

        @Test
        @DisplayName("must fit ck_products_code: upper case, at most 50 characters")
        void codeRules() {
            assertThatThrownBy(() -> Product.draft("cup-12oz", "Ly", null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Product.draft("C".repeat(51), "Ly", null, null, null, null, null, null, null,
                    null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(Product.draft("CUP_12-OZ", "Ly", null, null, null, null, null, null, null, null).kind())
                    .isEqualTo(ProductKind.STANDARD);
        }
    }

    @Nested
    @DisplayName("discontinue (SCRUM-85)")
    class Discontinue {

        @Test
        @DisplayName("moves APPROVED to DISCONTINUED and registers the event")
        void discontinueFromApprovedSucceeds() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);
            product.approve(APPROVER, NOW);
            product.pullDomainEvents(); // drain the Approved event so only Discontinued remains

            product.discontinue(NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.DISCONTINUED);
            assertThat(product.discontinuedAt()).isEqualTo(NOW);
            List<?> events = product.pullDomainEvents();
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(ProductEvent.Discontinued.class);
            ProductEvent.Discontinued discontinued = (ProductEvent.Discontinued) events.get(0);
            assertThat(discontinued.payload().productId()).isEqualTo(product.id().value());
            assertThat(discontinued.payload().discontinuedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("DISCONTINUED is terminal - cannot transition anywhere from it")
        void discontinuedIsTerminal() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);
            product.approve(APPROVER, NOW);
            product.discontinue(NOW);

            assertThatThrownBy(() -> product.discontinue(NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }

        @Test
        @DisplayName("rejected from DRAFT")
        void discontinueFromDraftRejected() {
            Product product = draftWithCategory();

            assertThatThrownBy(() -> product.discontinue(NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }

        @Test
        @DisplayName("rejected from PENDING_APPROVAL")
        void discontinueFromPendingApprovalRejected() {
            Product product = draftWithCategory();
            product.submit(SUBMITTER, NOW);

            assertThatThrownBy(() -> product.discontinue(NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }
}
