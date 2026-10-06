package com.stockflow.fulfillment.api;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import java.util.UUID;

/**
 * THE public API of the fulfillment module — the only package other modules may import.
 *
 * <p>The API is task-scoped: callers never receive a design storage key and cannot ask for a file
 * without naming an order line that belongs to their assigned task. Two module rules apply:</p>
 * <ul>
 *   <li>declare only what other modules actually call — the shortest surface that works;</li>
 *   <li>every parameter and return type is a record or enum declared in THIS package, never a
 *       domain object or JPA entity. {@code ArchitectureTest.theApiPackageLeaksNothingInternal}
 *       and {@code theApiPublishesNoEntities} enforce it.</li>
 * </ul>
 *
 * <p>To finish the module: add a migration, then the entity + repository adapter, then a
 * controller — see {@code docs/adding-a-module.md} §4.</p>
 */
public interface FulfillmentService {
    FulfillmentTask createTask(UUID orderId, UUID assignedUserId, CurrentUser actor);
    /** The tasks this actor may see, newest first; the role decides which ones. */
    PageResponse<FulfillmentTask> listTasks(CurrentUser actor, int page, int size);
    FulfillmentTask assign(UUID taskId, UUID assignedUserId, CurrentUser actor);
    FulfillmentTask findTask(UUID taskId, CurrentUser actor);
    FulfillmentTask startPicking(UUID taskId, CurrentUser actor);
    FulfillmentTask completePicking(UUID taskId, CurrentUser actor);
    FulfillmentArtifactAccess artifactDownload(UUID taskId, UUID orderLineId, String role,
                                                CurrentUser actor);
    FulfillmentTask completePacking(UUID taskId, CurrentUser actor);
    FulfillmentTask reverifyDesignIntegrity(UUID taskId, String resolutionNote, CurrentUser actor);
}
