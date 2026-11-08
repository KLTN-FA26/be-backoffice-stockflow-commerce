package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every write checks the version the user saw (BR-11); who reads a design is the owner or, while pending, a reviewer. */
class DesignWorkflowTest {

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

        assertThatCode(() -> CustomerDesignRules.requireReader(reviewer, owner, DesignStatus.PENDING_REVIEW, true, true))
                .doesNotThrowAnyException();
        for (DesignStatus status : new DesignStatus[] {DesignStatus.DRAFT, DesignStatus.APPROVED, DesignStatus.ARCHIVED}) {
            assertThatThrownBy(() -> CustomerDesignRules.requireReader(reviewer, owner, status, true, true))
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        // Without the right to review, or for a design staff started, nobody but the owner.
        assertThatThrownBy(() -> CustomerDesignRules.requireReader(reviewer, owner, DesignStatus.PENDING_REVIEW, true,
                false)).extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> CustomerDesignRules.requireReader(reviewer, owner, DesignStatus.PENDING_REVIEW, false,
                true)).extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatCode(() -> CustomerDesignRules.requireReader(owner, owner, DesignStatus.APPROVED, true, false))
                .doesNotThrowAnyException();
    }
}
