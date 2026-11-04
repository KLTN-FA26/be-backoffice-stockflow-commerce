package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignWorkflowTest {
    private final UUID owner = UUID.randomUUID(), editor = UUID.randomUUID(), reviewer = UUID.randomUUID(), artifact = UUID.randomUUID();
    @Test void independentReviewerCanApproveCurrentArtifact() {
        assertThatCode(() -> DesignWorkflow.requireReview(DesignStatus.DRAFT, artifact, artifact, reviewer, owner, editor, reviewer))
                .doesNotThrowAnyException();
    }
    @Test void editorCannotReviewOwnWorkEvenWhenAssignedReviewer() {
        assertThatThrownBy(() -> DesignWorkflow.requireReview(DesignStatus.DRAFT, artifact, artifact, editor, owner, editor, editor))
                .isInstanceOf(BusinessException.class);
    }
    @Test void customerDecisionRequiresCurrentReviewedArtifact() {
        assertThatCode(() -> DesignWorkflow.requireConfirmation(DesignStatus.SUBMITTED, artifact, artifact, true, owner, owner))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DesignWorkflow.requireConfirmation(DesignStatus.SUBMITTED, artifact, UUID.randomUUID(), true, owner, owner))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DesignWorkflow.requireConfirmation(DesignStatus.SUBMITTED, artifact, artifact, false, owner, owner))
                .isInstanceOf(BusinessException.class);
    }
    @Test void staffCannotConfirmForCustomerAndOldScreensCannotWrite() {
        assertThatThrownBy(() -> DesignWorkflow.requireConfirmation(DesignStatus.SUBMITTED, artifact, artifact, true, editor, owner))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DesignWorkflow.requireVersion(4, 3)).isInstanceOf(BusinessException.class);
    }
}
