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

/** JPA mapping of a per-user channel preference (table {@code notification.preference}). STARTER ENTITY. */
@Entity
@Table(name = "preference", schema = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_preference_user_channel",
                columnNames = {"user_id", "channel"}))
public class NotificationPreferenceJpaEntity extends BaseEntity {

    /** Cross-module reference to identity. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected NotificationPreferenceJpaEntity() {
    }

    public NotificationPreferenceJpaEntity(UUID id, UUID userId, NotificationChannel channel, boolean enabled) {
        super(id);
        this.userId = userId;
        this.channel = channel;
        this.enabled = enabled;
    }

    public UUID getUserId() { return userId; }
    public NotificationChannel getChannel() { return channel; }
    public boolean isEnabled() { return enabled; }
}
