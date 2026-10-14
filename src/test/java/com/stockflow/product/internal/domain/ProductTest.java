package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the approval workflow (SCRUM-57/WBS 3.1.1.3), same style as {@code StockItemTest}:
 * no Spring, no database, the aggregate's own guarantees only.
 */
class ProductTest {

    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    /** A draft that can be submitted: it has a category and a brand. */
    private static Product draftWithCategory() {
        return Product.draft("CUP-12OZ", "Ly giấy 12oz", "Paper cup 12oz", BRAND, CATEGORY,
                null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE);
    }

    private static Product draftWithoutCategory() {
        return Product.draft("CUP-12OZ", "Ly giấy 12oz", "Paper cup 12oz", BRAND, null,
                null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE);
    }

    private static Product draftWithoutBrand() {
        return Product.draft("CUP-12OZ", "Ly giấy 12oz", "Paper cup 12oz", null, CATEGORY,
                null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE);
    }

    private static Product approved() {
        Product product = draftWithCategory();
        product.submit(SUBMITTER, NOW);
        product.approve(APPROVER, NOW);
        product.pullDomainEvents();
        return product;
    }

    /** A product as the database holds it in {@code status}, with the evidence that status needs. */
    private static Product storedAs(ProductStatus status) {
        boolean approvedOnce = status != ProductStatus.DRAFT && status != ProductStatus.PENDING_APPROVAL;
        return new Product(ProductId.newId(), "CUP-12OZ", "Ly giấy 12oz", null, BRAND, CATEGORY, null, null, null,
                TaxClass.STANDARD, ProductKind.STANDARD, status, 7L,
                status == ProductStatus.DRAFT ? null : SUBMITTER, status == ProductStatus.DRAFT ? null : NOW,
                approvedOnce ? APPROVER : null, approvedOnce ? NOW : null, null,
                status == ProductStatus.DISCONTINUED ? NOW : null,
                status == ProductStatus.DISCONTINUED ? "end of line" : null,
                status == ProductStatus.INACTIVE ? NOW : null, status == ProductStatus.INACTIVE ? "recall" : null);
    }

    private static void assertRefused(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
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

        @Test
        @DisplayName("rejected without a brand: PRODUCT_BRAND_REQUIRED naming brandId (kltn-docs 01 §3)")
        void submitWithoutBrandRejected() {
            Product product = draftWithoutBrand();

            assertThatThrownBy(() -> product.submit(SUBMITTER, NOW)).isInstanceOfSatisfying(BusinessException.class,
                    e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_BRAND_REQUIRED);
                        assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                            assertThat(f.field()).isEqualTo("brandId");
                            assertThat(f.code()).isEqualTo("PRODUCT_BRAND_REQUIRED");
                        });
                    });
            assertThat(product.status()).isEqualTo(ProductStatus.DRAFT);
            assertThat(product.submittedBy()).isNull();
        }

        @Test
        @DisplayName("a DRAFT may be saved without a brand, and submits once one is chosen")
        void brandIsNeededOnlyToSubmit() {
            Product product = draftWithoutBrand();
            product.updateDetails("Ly giấy 12oz", "Paper cup 12oz", BRAND, CATEGORY, null, null, null,
                    TaxClass.STANDARD, ProductKind.CUSTOMIZABLE);

            product.submit(SUBMITTER, NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("without a category the category refusal still comes first, as before")
        void categoryRefusalKeepsItsCode() {
            Product product = Product.draft("CUP-12OZ", "Ly", null, null, null, null, null, null, null, null);

            assertRefused(() -> product.submit(SUBMITTER, NOW), ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
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
        @DisplayName("names the fields that changed, none when nothing did")
        void updateReportsChangedFields() {
            Product product = draftWithCategory();

            assertThat(product.updateDetails("Ly mới", "Paper cup 12oz", BRAND, CATEGORY, "mô tả", null, null,
                    TaxClass.REDUCED, ProductKind.CUSTOMIZABLE))
                    .containsExactly("name", "shortDescription", "taxClass");
            assertThat(product.updateDetails("Ly mới", "Paper cup 12oz", BRAND, CATEGORY, "mô tả", null, null,
                    TaxClass.REDUCED, ProductKind.CUSTOMIZABLE)).isEmpty();
            assertThat(product.updateDetails("Ly mới", "Paper cup 12oz", null, null, "mô tả", null, null,
                    TaxClass.REDUCED, null)).containsExactly("brandId", "categoryId", "kind");
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
    @DisplayName("discontinue (SCRUM-85, SCRUM-461)")
    class Discontinue {

        @Test
        @DisplayName("moves APPROVED to DISCONTINUED, keeps the reason and registers the event with it")
        void discontinueFromApprovedSucceeds() {
            Product product = approved();

            product.discontinue("  Hết dòng sản phẩm  ", NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.DISCONTINUED);
            assertThat(product.discontinuedAt()).isEqualTo(NOW);
            assertThat(product.discontinueReason()).isEqualTo("Hết dòng sản phẩm");
            List<?> events = product.pullDomainEvents();
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(ProductEvent.Discontinued.class);
            ProductEvent.Discontinued discontinued = (ProductEvent.Discontinued) events.get(0);
            assertThat(discontinued.payload().productId()).isEqualTo(product.id().value());
            assertThat(discontinued.payload().discontinuedAt()).isEqualTo(NOW);
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = ProductStatus.class, names = {"APPROVED", "PUBLISHED", "INACTIVE"})
        @DisplayName("allowed from every status that has been approved and is not over")
        void discontinueFromSellableStatuses(ProductStatus from) {
            Product product = storedAs(from);

            product.discontinue("end of line", NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.DISCONTINUED);
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = ProductStatus.class, names = {"DRAFT", "PENDING_APPROVAL", "DISCONTINUED"})
        @DisplayName("refused from DRAFT, PENDING_APPROVAL and DISCONTINUED (terminal)")
        void discontinueFromOtherStatusesRefused(ProductStatus from) {
            Product product = storedAs(from);

            assertThatThrownBy(() -> product.discontinue("end of line", NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
            assertThat(product.status()).isEqualTo(from);
            assertThat(product.pullDomainEvents()).isEmpty();
        }

        @Test
        @DisplayName("a reason is required: null, blank and over 500 characters are refused on the reason field")
        void discontinueNeedsAReason() {
            for (String bad : new String[]{null, "", "   ", "x".repeat(501)}) {
                Product product = approved();

                assertThatThrownBy(() -> product.discontinue(bad, NOW)).isInstanceOfSatisfying(BusinessException.class,
                        e -> {
                            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                            assertThat(e.fieldErrors()).singleElement()
                                    .satisfies(f -> assertThat(f.field()).isEqualTo("reason"));
                        });
                assertThat(product.status()).isEqualTo(ProductStatus.APPROVED);
                assertThat(product.discontinueReason()).isNull();
                assertThat(product.pullDomainEvents()).isEmpty();
            }
            assertThatCode(() -> approved().discontinue("x".repeat(500), NOW)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DISCONTINUED is terminal - cannot transition anywhere from it")
        void discontinuedIsTerminal() {
            Product product = approved();
            product.discontinue("end of line", NOW);

            assertThatThrownBy(() -> product.discontinue("again", NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
            assertRefused(() -> product.suspend("recall", NOW), ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
            assertRefused(() -> product.resume(NOW), ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("a suspended product can still be discontinued, and keeps when and why it was suspended")
        void discontinueFromInactive() {
            Product product = approved();
            product.suspend("recall", NOW);
            product.pullDomainEvents();

            product.discontinue("supplier gone", NOW.plusSeconds(60));

            assertThat(product.status()).isEqualTo(ProductStatus.DISCONTINUED);
            assertThat(product.suspendReason()).isEqualTo("recall");
            assertThat(product.suspendedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("suspend and resume (SCRUM-461, kltn-docs 01 §5.1 Inactive)")
    class SuspendAndResume {

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = ProductStatus.class, names = {"APPROVED", "PUBLISHED"})
        @DisplayName("suspend moves an approved or published product to INACTIVE with when and why")
        void suspendFromSellableStatuses(ProductStatus from) {
            Product product = storedAs(from);

            product.suspend("  Nhà cung cấp thu hồi lô hàng ", NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.INACTIVE);
            assertThat(product.suspendedAt()).isEqualTo(NOW);
            assertThat(product.suspendReason()).isEqualTo("Nhà cung cấp thu hồi lô hàng");
            assertThat(product.approvedBy()).isEqualTo(APPROVER);
        }

        @Test
        @DisplayName("suspend tells the catalog to look again, through a CatalogListingChanged event")
        void suspendWakesTheCatalog() {
            Product product = approved();

            product.suspend("recall", NOW);

            assertThat(product.pullDomainEvents()).singleElement().isInstanceOfSatisfying(ProductEvent.CatalogChanged.class,
                    e -> {
                        assertThat(e.payload()).isEqualTo(new CatalogListingChanged(product.id().value()));
                        assertThat(e.aggregateId()).isEqualTo(product.id().value().toString());
                    });
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = ProductStatus.class, names = {"DRAFT", "PENDING_APPROVAL", "INACTIVE", "DISCONTINUED"})
        @DisplayName("suspend refused from DRAFT, PENDING_APPROVAL, INACTIVE (already) and DISCONTINUED")
        void suspendFromOtherStatusesRefused(ProductStatus from) {
            Product product = storedAs(from);

            assertThatThrownBy(() -> product.suspend("recall", NOW))
                    .isInstanceOf(InvalidProductStatusTransitionException.class);
            assertThat(product.status()).isEqualTo(from);
        }

        @Test
        @DisplayName("suspend needs a reason of 1 to 500 characters")
        void suspendNeedsAReason() {
            for (String bad : new String[]{null, "", "  ", "x".repeat(501)}) {
                Product product = approved();

                assertThatThrownBy(() -> product.suspend(bad, NOW)).isInstanceOfSatisfying(BusinessException.class,
                        e -> {
                            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                            assertThat(e.fieldErrors()).singleElement()
                                    .satisfies(f -> assertThat(f.field()).isEqualTo("reason"));
                        });
                assertThat(product.status()).isEqualTo(ProductStatus.APPROVED);
                assertThat(product.suspendedAt()).isNull();
            }
        }

        @Test
        @DisplayName("resume returns INACTIVE to APPROVED, forgets the suspension and wakes the catalog")
        void resumeFromInactive() {
            Product product = approved();
            product.suspend("recall", NOW);
            product.pullDomainEvents();

            product.resume(NOW.plusSeconds(3600));

            assertThat(product.status()).isEqualTo(ProductStatus.APPROVED);
            assertThat(product.suspendedAt()).isNull();
            assertThat(product.suspendReason()).isNull();
            assertThat(product.pullDomainEvents()).singleElement().isInstanceOf(ProductEvent.CatalogChanged.class);
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = ProductStatus.class, names = {"DRAFT", "PENDING_APPROVAL", "APPROVED", "PUBLISHED", "DISCONTINUED"})
        @DisplayName("resume refused from every status but INACTIVE")
        void resumeFromOtherStatusesRefused(ProductStatus from) {
            Product product = storedAs(from);

            assertThatThrownBy(() -> product.resume(NOW)).isInstanceOf(InvalidProductStatusTransitionException.class);
            assertThat(product.status()).isEqualTo(from);
        }

        @Test
        @DisplayName("a suspended product is still maintained: its variants and media accept changes")
        void inactiveAcceptsChanges() {
            assertThat(storedAs(ProductStatus.INACTIVE).acceptsChanges()).isTrue();
            assertThat(storedAs(ProductStatus.APPROVED).acceptsChanges()).isTrue();
            assertThat(storedAs(ProductStatus.PENDING_APPROVAL).acceptsChanges()).isFalse();
            assertThat(storedAs(ProductStatus.DISCONTINUED).acceptsChanges()).isFalse();
        }

        @Test
        @DisplayName("a suspended product has no edit: updateDetails is DRAFT-only")
        void inactiveIsNotEditable() {
            Product product = storedAs(ProductStatus.INACTIVE);

            assertThatThrownBy(() -> product.updateDetails("x", "x", BRAND, CATEGORY, null, null, null,
                    TaxClass.STANDARD, ProductKind.STANDARD)).isInstanceOf(InvalidProductStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("requireDeletable (kltn-docs 01 BR-02)")
    class Deletable {

        @Test
        @DisplayName("a DRAFT product may be deleted, also one sent back by a rejection")
        void draftIsDeletable() {
            assertThatCode(() -> draftWithCategory().requireDeletable()).doesNotThrowAnyException();
            Product rejected = draftWithCategory();
            rejected.submit(SUBMITTER, NOW);
            rejected.reject(APPROVER, "Missing dimensions", NOW);
            assertThatCode(rejected::requireDeletable).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ProductStatus.class, names = {"PENDING_APPROVAL", "APPROVED", "PUBLISHED", "INACTIVE", "DISCONTINUED"})
        @DisplayName("every other status is PRODUCT_NOT_DELETABLE")
        void otherStatusesAreNotDeletable(ProductStatus status) {
            assertRefused(storedAs(status)::requireDeletable, ErrorCode.PRODUCT_NOT_DELETABLE);
        }
    }

    @Nested
    @DisplayName("requireVersion (SCRUM-461)")
    class Version {

        @Test
        @DisplayName("the version the caller read passes; any other is OPTIMISTIC_LOCK")
        void versionIsCompared() {
            Product product = storedAs(ProductStatus.APPROVED);

            assertThatCode(() -> product.requireVersion(7L)).doesNotThrowAnyException();
            assertRefused(() -> product.requireVersion(6L), ErrorCode.OPTIMISTIC_LOCK);
            assertRefused(() -> product.requireVersion(8L), ErrorCode.OPTIMISTIC_LOCK);
        }
    }
}
