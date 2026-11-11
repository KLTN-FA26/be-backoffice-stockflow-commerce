package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;

/**
 * The rules of a design project (kltn-docs 12 §4.9-§4.11, §5.4; BR-17..BR-22; B42, B43). The table states the ones a
 * row can: a design in one project of its own customer, unmoved while it waits for review.
 */
public final class DesignProjectRules {

    /** Designs a project holds, archived ones not counted (B42). */
    public static final int MAX_DESIGNS = 20;
    /** Files a project's library holds (B43). */
    public static final int MAX_ASSETS = 30;
    /** A batch is at least two drafts (BR-19); a single one is submitted on its own. */
    public static final int MIN_BATCH = 2;

    private DesignProjectRules() {
    }

    /** A project is its owner's alone (BR-17); to anyone else it does not exist. */
    public static void requireOwner(UUID actor, UUID owner) {
        if (actor == null || !actor.equals(owner)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    /** A closed project takes no design, no batch and no quote request, and is not renamed (BR-22). */
    public static void requireActive(ProjectStatus status) {
        if (status != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_ARCHIVED, "This design project is closed");
        }
    }

    /** Room for one more design beside {@code designs} not archived (B42). */
    public static void requireRoomForDesign(long designs) {
        if (designs >= MAX_DESIGNS) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_FULL,
                    "A design project holds at most " + MAX_DESIGNS + " designs; remove one first");
        }
    }

    /** Room for one more library file beside {@code files} (B43). */
    public static void requireRoomForAsset(long files) {
        if (files >= MAX_ASSETS) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_LIBRARY_FULL,
                    "A design project's library holds at most " + MAX_ASSETS + " files; remove one first");
        }
    }

    /**
     * Whether a design may join a project (BR-17): not archived, and in no project yet. A design already in this very
     * project is the caller's to treat as done.
     */
    public static void requireJoinable(DesignStatus status, UUID currentProject) {
        if (status == DesignStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (currentProject != null) {
            throw new BusinessException(ErrorCode.DESIGN_IN_ANOTHER_PROJECT, "This design belongs to another project");
        }
    }

    /** A design waiting for review neither leaves its project nor moves to another (BR-17). */
    public static void requireNotPending(DesignStatus status) {
        if (status == DesignStatus.PENDING_REVIEW) {
            throw new BusinessException(ErrorCode.DESIGN_PENDING_IN_PROJECT,
                    "A design waiting for review stays in its project until it is decided");
        }
    }
}
