package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;

/**
 * One tool the model may call (design §4).
 *
 * <p>An implementation's {@link #run} must be {@code public} and carry {@code @RequiresPermission} with the resource,
 * action and scope of the endpoint named by {@link #mirrors()}: it is invoked through its Spring proxy, so the
 * permission aspect checks the caller and installs the data scope exactly as it does for that screen.
 * {@code AssistantToolPermissionTest} holds every tool to it. Logic the screen applies outside its annotation (a
 * back-office check in the controller, a warehouse rule in a service the {@code api} method skips) is repeated in
 * {@code run}.</p>
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

    /** The tool's answer, serialised to JSON for the model. */
    Object run(ToolArguments arguments);
}
