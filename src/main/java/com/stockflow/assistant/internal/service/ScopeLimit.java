package com.stockflow.assistant.internal.service;

/**
 * What a tool gives a user limited to particular warehouses (data scope WAREHOUSE), declared beside the rule by
 * {@link AssistantTool#scopeLimit()}.
 *
 * <p>Declared rather than worked out, because the rule lives where nothing outside can read it: some {@code api}
 * methods narrow to the caller's warehouses themselves, some tools refuse company-wide figures in {@code run()}, and
 * the rest answer the same for everyone. {@link ToolAvailability} needs it before anything runs, so a
 * warehouse-limited user is not offered a question whose answer is always empty.</p>
 */
public enum ScopeLimit {

    /** The same answer whatever the caller's warehouses, as on the screen the tool mirrors. */
    NONE,

    /** Company-wide figures only: a warehouse-limited caller is refused, with no rows and a note saying why. */
    COMPANY_WIDE_ONLY,

    /** Only the rows of the caller's own warehouses, possibly none, and nothing company-wide. */
    PARTIAL
}
