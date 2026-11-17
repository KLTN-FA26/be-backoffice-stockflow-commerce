package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.internal.domain.DesignProjectRules;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.domain.TextStyle;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectTextJpaEntity;
import com.stockflow.design.internal.repository.DesignProjectTextJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts of a design project's library (FE request 2026-11-13 E-1, accepted beyond kltn-docs 12 BR-18, whose library
 * holds logos and pictures only): a text with its type style, saved once and placed by the studio on any design of the
 * project. At most {@value DesignProjectRules#MAX_TEXTS} a project, apart from its files; added and removed only while
 * the project is open, as files are. A text is never changed: placing it copies it into a layout, so removing it here
 * leaves every design as it is.
 */
@Service
@Transactional
public class DesignProjectTextService {

    private final DesignProjectService projects;
    private final DesignProjectTextJpaRepository texts;

    public DesignProjectTextService(DesignProjectService projects, DesignProjectTextJpaRepository texts) {
        this.projects = projects;
        this.texts = texts;
    }

    /** A library text as the studio lists it. */
    public record LibraryTextView(UUID id, UUID projectId, String text, TextStyle style, Instant createdAt) {
    }

    /** The library's texts, the newest first, to the project's owner. */
    @Transactional(readOnly = true)
    public List<LibraryTextView> list(UUID projectId, UUID actor) {
        projects.own(projectId, actor);
        return texts.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(DesignProjectTextService::view).toList();
    }

    /** Saves a text into the library of an open project with room, under the project's lock. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    public LibraryTextView add(UUID projectId, UUID actor, String text, TextStyle style) {
        DesignProjectJpaEntity project = projects.lockActive(projectId, actor);
        DesignProjectRules.requireRoomForText(texts.countByProjectId(projectId));
        DesignProjectTextJpaEntity saved = texts.saveAndFlush(new DesignProjectTextJpaEntity(projectId, text, style));
        projects.record(project, actor, ProjectEventType.TEXT_ADDED, saved.getId() + " " + shown(text));
        return view(saved);
    }

    /** Removes a text from the library of an open project; designs it was placed on keep their copy. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    public void remove(UUID projectId, UUID textId, UUID actor) {
        DesignProjectJpaEntity project = projects.lockActive(projectId, actor);
        DesignProjectTextJpaEntity text = texts.findByIdAndProjectId(textId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        texts.delete(text);
        texts.flush();
        projects.record(project, actor, ProjectEventType.TEXT_REMOVED, text.getId() + " " + shown(text.getText()));
    }

    /** Enough of the text to recognise it in the history. */
    private static String shown(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= 80 ? line : line.substring(0, 77) + "...";
    }

    private static LibraryTextView view(DesignProjectTextJpaEntity t) {
        return new LibraryTextView(t.getId(), t.getProjectId(), t.getText(), t.getStyle(), t.getCreatedAt());
    }
}
