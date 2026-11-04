package com.stockflow.fulfillment.api;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import java.util.List;
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
    /**
     * Opens the pick and pack task of an order in the warehouse the goods are picked from and assigns it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} with
     *         {@code fieldErrors[assignedUserId]} when the assignee is not an active warehouse staff member
     *         or manager of that warehouse; {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} when the order's
     *         stock is held in more than one warehouse or not in the order's own;
     *         {@code OUT_OF_DATA_SCOPE} for a warehouse the caller is not assigned to
     */
    FulfillmentTask createTask(UUID orderId, UUID assignedUserId, CurrentUser actor);
    /**
     * The tasks this actor may see, newest first; the role decides which ones, the caller's warehouses
     * narrow them, and the query narrows them further.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE} when the query names a
     *         warehouse the caller is not assigned to; {@code UNSUPPORTED_PARAMETER} for an unknown status
     */
    PageResponse<FulfillmentTask> listTasks(CurrentUser actor, FulfillmentTaskQuery query);
    /**
     * The people a task of {@code warehouseId} can be assigned to, by name. Not paged: bounded by one
     * warehouse's staff.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE}, {@code WAREHOUSE_NOT_FOUND}
     */
    List<FulfillmentAssignee> assignees(UUID warehouseId, CurrentUser actor);
    FulfillmentTask assign(UUID taskId, UUID assignedUserId, CurrentUser actor);
    FulfillmentTask findTask(UUID taskId, CurrentUser actor);
    FulfillmentTask startPicking(UUID taskId, CurrentUser actor);
    FulfillmentTask completePicking(UUID taskId, CurrentUser actor);
    FulfillmentArtifactAccess artifactDownload(UUID taskId, UUID orderLineId, String role,
                                                CurrentUser actor);
    /** {@link #completePacking(UUID, PackingCompletion, CurrentUser)} for the whole order. */
    FulfillmentTask completePacking(UUID taskId, CurrentUser actor);

    /**
     * Completes packing (G4-5, kltn-docs 08 step 7-9): every sealed package not shipped yet becomes one shipment,
     * READY_TO_DISPATCH. Everything the order still wants must be in it unless {@code completion.partial()} (08 BR-01).
     * The print lines it carries are checked against their design first (BR-DSG-003): a mismatch puts packing and the
     * order on hold and ships nothing. Shipping everything left marks the order PACKED; part of it records that a
     * package was completed (the order is no longer cancelled, decision 2026-10-17). A print line's goods leave stock
     * with the shipment that completes the line (decision 2026-10-16). Completed again, it answers as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKING_NOT_READY}, {@code PACK_STATE_CONFLICT},
     *         {@code PACKING_TAKEN}, {@code PACKAGE_NOT_SEALED}, {@code PACK_NOTHING_TO_SHIP},
     *         {@code PACKING_INCOMPLETE}, {@code PACK_PARTIAL_NOT_ALLOWED}, {@code ORDER_CANCELLED}
     */
    FulfillmentTask completePacking(UUID taskId, PackingCompletion completion, CurrentUser actor);

    /**
     * QC or a warehouse manager re-verifies the designs of packing held for a mismatch: all matching now, the order's
     * DESIGN_MISMATCH hold is resolved and packing is back IN PROGRESS for the packer to complete (08 §5 Verification
     * Failed → In Progress); still mismatching, it stays on hold with the new evidence.
     */
    FulfillmentTask reverifyDesignIntegrity(UUID taskId, String resolutionNote, CurrentUser actor);
}
