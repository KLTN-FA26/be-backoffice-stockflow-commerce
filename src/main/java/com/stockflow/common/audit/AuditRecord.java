package com.stockflow.common.audit;

import java.util.UUID;

/**
 * One line of the audit trail as it is read back: the {@link AuditEntry} that was written, the id it was stored under -
 * so a line of a search can be opened on its own ({@link AuditHistory#find}) - and the retention class fixed when it
 * was written. Not on {@code AuditEntry} itself: the entry is what the aspect writes, before any id exists, and the
 * retention class is the store's, denormalised so a later reclassification never shortens an entry's life.
 */
public record AuditRecord(UUID id, AuditEntry entry, boolean securityRelevant) {
}
