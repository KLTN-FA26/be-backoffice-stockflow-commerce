package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of a design project (kltn-docs 12 BR-17, BR-22; B42, B43). */
class DesignProjectRulesTest {

    private static void refused(Runnable rule, ErrorCode code) {
        assertThatThrownBy(rule::run).extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(code);
    }

    @Test
    void aProjectHoldsTwentyDesignsAndThirtyLibraryFiles() {
        assertThatCode(() -> DesignProjectRules.requireRoomForDesign(19)).doesNotThrowAnyException();
        refused(() -> DesignProjectRules.requireRoomForDesign(20), ErrorCode.DESIGN_PROJECT_FULL);
        assertThatCode(() -> DesignProjectRules.requireRoomForAsset(29)).doesNotThrowAnyException();
        refused(() -> DesignProjectRules.requireRoomForAsset(30), ErrorCode.DESIGN_PROJECT_LIBRARY_FULL);
    }

    @Test
    void aClosedProjectTakesNothingAndIsItsOwnersAlone() {
        refused(() -> DesignProjectRules.requireActive(ProjectStatus.ARCHIVED), ErrorCode.DESIGN_PROJECT_ARCHIVED);
        UUID owner = UUID.randomUUID();
        assertThatCode(() -> DesignProjectRules.requireOwner(owner, owner)).doesNotThrowAnyException();
        refused(() -> DesignProjectRules.requireOwner(UUID.randomUUID(), owner), ErrorCode.NOT_FOUND);
        refused(() -> DesignProjectRules.requireOwner(null, owner), ErrorCode.NOT_FOUND);
    }

    @Test
    void aDesignJoinsOneProjectAndAWaitingOneStaysWhereItIs() {
        assertThatCode(() -> DesignProjectRules.requireJoinable(DesignStatus.PENDING_REVIEW, null))
                .doesNotThrowAnyException();
        refused(() -> DesignProjectRules.requireJoinable(DesignStatus.DRAFT, UUID.randomUUID()),
                ErrorCode.DESIGN_IN_ANOTHER_PROJECT);
        refused(() -> DesignProjectRules.requireJoinable(DesignStatus.ARCHIVED, null), ErrorCode.NOT_FOUND);
        refused(() -> DesignProjectRules.requireNotPending(DesignStatus.PENDING_REVIEW),
                ErrorCode.DESIGN_PENDING_IN_PROJECT);
        assertThatCode(() -> DesignProjectRules.requireNotPending(DesignStatus.APPROVED)).doesNotThrowAnyException();
    }
}
