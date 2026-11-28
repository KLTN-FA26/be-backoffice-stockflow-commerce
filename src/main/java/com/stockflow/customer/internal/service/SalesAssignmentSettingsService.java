package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.internal.domain.SalesAssignmentSettings;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A salesperson's assignment settings (sales-owner plan §10.4, D13, Odoo's "pause" and "capacity"): whether they take
 * new customers and leads now, and how many new customers a month. They only steer the managers' suggestions; a
 * manager may still choose a paused salesperson.
 */
@Service
@Transactional
public class SalesAssignmentSettingsService {

    private final SalesAssignmentSettings settings;
    private final SalesStaff salesStaff;
    private final IdentityService identity;

    SalesAssignmentSettingsService(SalesAssignmentSettings settings, SalesStaff salesStaff, IdentityService identity) {
        this.settings = settings;
        this.salesStaff = salesStaff;
        this.identity = identity;
    }

    /** @param version null while the salesperson has the defaults (no row yet) */
    public record SettingView(UUID userId, boolean paused, Integer capacityPerMonth, Long version) {
    }

    /** @throws BusinessException {@code USER_NOT_FOUND} */
    @Transactional(readOnly = true)
    public SettingView get(UUID userId) {
        if (userId == null || identity.userLabels(List.of(userId)).isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return view(settings.of(List.of(userId)).get(userId));
    }

    /**
     * @param expectedVersion the version the screen read, or null to overwrite (a first setting has none)
     * @throws BusinessException {@code USER_NOT_FOUND}; {@code SALES_OWNER_NOT_ASSIGNABLE} for an account that is not
     *         an active Sales agent; {@code VALIDATION_FAILED} for a capacity outside 0..1000;
     *         {@code OPTIMISTIC_LOCK} when someone saved it since {@code expectedVersion}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-assignment-setting", resourceId = "#userId")
    public SettingView save(UUID userId, boolean paused, Integer capacityPerMonth, Long expectedVersion, UUID actor) {
        if (userId == null || identity.userLabels(List.of(userId)).isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (!salesStaff.isActiveSalesAgent(userId)) {
            throw new BusinessException(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE,
                    "Account %s is not an active salesperson".formatted(userId));
        }
        if (capacityPerMonth != null && (capacityPerMonth < 0 || capacityPerMonth > 1000)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A capacity is between 0 and 1000");
        }
        SalesAssignmentSettings.Setting current = settings.forUpdate(userId);
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        return view(settings.save(userId, paused, capacityPerMonth, actor));
    }

    private static SettingView view(SalesAssignmentSettings.Setting setting) {
        return new SettingView(setting.userId(), setting.paused(), setting.capacityPerMonth(),
                setting.version() < 0 ? null : setting.version());
    }
}
