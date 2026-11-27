package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.PermissionCode;

import java.util.List;

/**
 * One tool the model may call (design §4).
 *
 * <p>An implementation's {@link #run} must be {@code public} and carry {@code @RequiresPermission} with the resource,
 * action and scope of the endpoint named by {@link #mirrors()}: it is invoked through its Spring proxy, so the
 * permission aspect checks the caller and installs the data scope exactly as it does for that screen.
 * {@code AssistantIntegrationTest.everyToolMirrorsItsScreen} holds every tool to it. Logic the screen applies outside
 * its annotation (a back-office check in the controller, a warehouse rule in a service the {@code api} method skips)
 * is repeated in {@code run}.</p>
 *
 * <p><b>What {@code run} checks is also declared</b>, by {@link #alsoRequires}, {@link #alsoRequiresAnyOf} and
 * {@link #scopeLimit}, next to the check itself. {@link ToolAvailability} reads them with the annotation to tell the
 * panel and the model which tools a user may use before anything runs; a list kept anywhere else drifts from what the
 * tools enforce.</p>
 *
 * <p>{@code run} returns a record of its own, built field by field: never an {@code api} record passed through, so a
 * field another module adds later does not silently reach the model provider.</p>
 */
public interface AssistantTool {

    ToolSpec spec();

    /**
     * The endpoint whose guard this tool carries, as {@code "GET /api/v1/..."}; null for a tool that mirrors no
     * guarded endpoint, which must then use an {@code assistant-*} resource.
     */
    String mirrors();

    /**
     * Permissions {@code run} requires besides its annotation, because its answer shows what more than one screen
     * shows. {@code run} checks this very list with {@code CurrentUser.require}, never a copy of it, so declaring a
     * requirement is enforcing it.
     */
    default List<PermissionCode> alsoRequires() {
        return List.of();
    }

    /**
     * Permissions of which the caller needs at least one besides the annotation, for a screen whose rule is an
     * either-or that {@link #alsoRequires} cannot express; empty when there is none. Reported, not enforced here: the
     * module behind the tool applies its own rule, which may have exceptions a permission list cannot state.
     */
    default List<PermissionCode> alsoRequiresAnyOf() {
        return List.of();
    }

    /** What a user limited to particular warehouses gets from this tool; {@code run}, or what it calls, applies it. */
    default ScopeLimit scopeLimit() {
        return ScopeLimit.NONE;
    }

    /** The tool's answer, serialised to JSON for the model. */
    Object run(ToolArguments arguments);
}
