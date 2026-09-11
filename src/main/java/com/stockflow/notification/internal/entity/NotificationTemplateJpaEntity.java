package com.stockflow.notification.internal.entity;

import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** JPA mapping of a notification template (table {@code notification.template}). STARTER ENTITY. */
@Entity
@Table(name = "template", schema = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_template_code_locale",
                columnNames = {"code", "locale"}))
public class NotificationTemplateJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected NotificationTemplateJpaEntity() {
    }

    public NotificationTemplateJpaEntity(UUID id, String code, NotificationChannel channel,
                                         String subject, String body, String locale, boolean active) {
        super(id);
        this.code = code;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.locale = locale;
        this.active = active;
    }

    public String getCode() { return code; }
    public NotificationChannel getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getLocale() { return locale; }
    public boolean isActive() { return active; }
}
