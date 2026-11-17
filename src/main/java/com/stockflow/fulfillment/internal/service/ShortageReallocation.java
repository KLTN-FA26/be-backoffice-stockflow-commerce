package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The warehouse manager's reallocation of a shortage (kltn-docs 07 §4.4 steps 10-11): a SHORT pick line, or a line whose
 * carton the check station removed as damaged (08 §4.2 step 4). See {@link PickLists#reallocateShort}.
 *
 * <p>Deliberately not transactional: when the warehouse has no whole carton to replace a removed one, the reallocation
 * rolls back ({@code INSUFFICIENT_STOCK}) and the order is then held {@code INVENTORY} in a transaction of its own
 * ({@link PickLists#holdForUnreplacedCarton}), so the refusal is answered and the hold is kept. A short pick's order is
 * held from the report already; nothing changes for it.</p>
 */
@Component
public class ShortageReallocation {

    private final PickLists pickLists;

    ShortageReallocation(PickLists pickLists) {
        this.pickLists = pickLists;
    }

    /**
     * @return the new lines
     * @throws BusinessException as {@link PickLists#reallocateShort}
     */
    public List<PickLists.PickLineView> reallocate(UUID taskId, UUID lineId, CurrentUser actor) {
        try {
            return pickLists.reallocateShort(taskId, lineId, actor);
        } catch (BusinessException refused) {
            if (refused.errorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                pickLists.holdForUnreplacedCarton(taskId, lineId, actor);
            }
            throw refused;
        }
    }
}
