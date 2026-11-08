package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every write checks the version the user saw (BR-11); who reads a design is its owner, a reviewer while it waits, and
 * the sales staff while a quote request on it is open and it waits or is approved (plan D7).
 */
class DesignWorkflowTest {

    private static final DesignReadRights REVIEWER = new DesignReadRights(true, false);
    private static final DesignReadRights SALES = new DesignReadRights(false, true);
    private static final BooleanSupplier OPEN = () -> true;
    private static final BooleanSupplier NONE_OPEN = () -> false;

    @Test
    void aStaleVersionIsAConflict() {
        assertThatCode(() -> DesignWorkflow.requireVersion(3, 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DesignWorkflow.requireVersion(4, 3))
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void aReviewerReadsACustomersDesignOnlyWhileItWaitsForReview() {
        UUID owner = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();

        assertThatCode(() -> CustomerDesignRules.requireReader(reviewer, owner, DesignStatus.PENDING_REVIEW, true,
                REVIEWER, NONE_OPEN)).doesNotThrowAnyException();
        for (DesignStatus status : new DesignStatus[] {DesignStatus.DRAFT, DesignStatus.APPROVED, DesignStatus.ARCHIVED}) {
            assertThatThrownBy(() -> CustomerDesignRules.requireReader(reviewer, owner, status, true, REVIEWER,
                    NONE_OPEN)).extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        // Without the right to review, or for a design staff started, nobody but the owner.
        assertThatThrownBy(() -> CustomerDesignRules.requireReader(reviewer, owner, DesignStatus.PENDING_REVIEW, true,
                DesignReadRights.NONE, OPEN)).extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> CustomerDesignRules.requireReader(reviewer, owner, DesignStatus.PENDING_REVIEW, false,
                REVIEWER, OPEN)).extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatCode(() -> CustomerDesignRules.requireReader(owner, owner, DesignStatus.APPROVED, true,
                DesignReadRights.NONE, NONE_OPEN)).doesNotThrowAnyException();
    }

    @Test
    void theSalesStaffReadADesignWhileTheyHaveAnOpenRequestOnItToAnswer() {
        UUID owner = UUID.randomUUID();
        UUID sales = UUID.randomUUID();

        for (DesignStatus status : new DesignStatus[] {DesignStatus.PENDING_REVIEW, DesignStatus.APPROVED}) {
            assertThatCode(() -> CustomerDesignRules.requireReader(sales, owner, status, true, SALES, OPEN))
                    .doesNotThrowAnyException();
            // No open request: nothing to answer, nothing to read.
            assertThatThrownBy(() -> CustomerDesignRules.requireReader(sales, owner, status, true, SALES, NONE_OPEN))
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        // A returned design is its owner's alone even with its request still open (B40); a deleted one is nobody's.
        for (DesignStatus status : new DesignStatus[] {DesignStatus.DRAFT, DesignStatus.ARCHIVED}) {
            assertThatThrownBy(() -> CustomerDesignRules.requireReader(sales, owner, status, true, SALES, OPEN))
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        // The owner and a reviewer are never asked about requests.
        BooleanSupplier never = () -> {
            throw new AssertionError("asked");
        };
        assertThatCode(() -> CustomerDesignRules.requireReader(owner, owner, DesignStatus.DRAFT, true,
                DesignReadRights.NONE, never)).doesNotThrowAnyException();
        assertThatCode(() -> CustomerDesignRules.requireReader(sales, owner, DesignStatus.PENDING_REVIEW, true,
                new DesignReadRights(true, true), never)).doesNotThrowAnyException();
    }
}
