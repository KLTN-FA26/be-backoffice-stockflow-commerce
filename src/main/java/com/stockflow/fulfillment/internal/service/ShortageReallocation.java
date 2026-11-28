package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The warehouse manager's reallocation of a shortage (kltn-docs 07 §4.4 steps 10-11): a SHORT pick line, or a line whose
 * carton the check station removed as damaged (08 §4.2 step 4). See {@link PickLists#reallocateShort}.
 *
 * <p>WF4-F3: a reallocation the warehouse cannot cover ({@code INSUFFICIENT_STOCK}) only answers the error. It no longer
 * puts the order back on hold: the order was held when the carton was removed or reported short, and if Sales has lifted
 * that hold (partial shipment already agreed), a failed top-up must not stop a shipment that is ready to dispatch.</p>
 */
@Component
public class ShortageReallocation {

    private final PickLists pickLists;

    ShortageReallocation(PickLists pickLists) {
        this.pickLists = pickLists;
    }

    /**
     * @return the new lines
     * @throws com.stockflow.common.error.BusinessException as {@link PickLists#reallocateShort}; nothing changes when it refuses
     */
    public List<PickLists.PickLineView> reallocate(UUID taskId, UUID lineId, CurrentUser actor) {
        return pickLists.reallocateShort(taskId, lineId, actor);
    }
}
