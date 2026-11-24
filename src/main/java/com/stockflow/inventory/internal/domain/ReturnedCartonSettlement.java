package com.stockflow.inventory.internal.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whose each carton of a return is once its refund is decided (WF-05 Q-4; audit round 3, RAD-1). Pure: the cartons
 * waiting and, per SKU, the units back and refunded in; one ownership per carton out.
 *
 * <p>Q-4 is about goods: "không có thoả thuận trả hàng thì hàng vẫn là của khách". Decided per return, a return that
 * refunded one SKU made the cartons of another SKU it refused the company's - sold again with no refund, no adjustment
 * invoice and no custody exit. So the split is per SKU, and within a SKU per carton:</p>
 * <ol>
 *   <li>the refunded units cover first what came back outside a sealed carton - an opened carton's count, loose units:
 *       never put into stock (Q-18), destroyed on the receipt, so the company can only have taken them;</li>
 *   <li>then the sealed cartons in label (LPN) order: a carton the rest of the refund reaches is the company's, whole -
 *       a carton is not split (20 BR-14, tu-quyet "thùng không tách được"), so one partly refunded is taken whole;</li>
 *   <li>a carton the refund does not reach was never agreed back: the customer's, its stock-in reversed. Every carton of
 *       a SKU refunded 0 is one.</li>
 * </ol>
 * <p>{@code ordering.check_return_request_goods_settled} computes the same split in SQL at commit.</p>
 */
public final class ReturnedCartonSettlement {

    /** What the refund decided of one SKU. */
    public record Decided(int unitsBack, int unitsRefunded) {

        public static final Decided NOTHING = new Decided(0, 0);
    }

    private ReturnedCartonSettlement() {
    }

    /**
     * Each carton's ownership: {@link ReturnedCartonOwnership.Ownership#COMPANY} or
     * {@link ReturnedCartonOwnership.Ownership#CUSTOMER_HELD}, in label order per SKU.
     *
     * @param bySku per SKU, what came back and was refunded; a SKU not named refunded nothing
     */
    public static Map<ReturnedCartonOwnership.Waiting, ReturnedCartonOwnership.Ownership> split(
            List<ReturnedCartonOwnership.Waiting> waiting, Map<String, Decided> bySku) {
        Map<String, List<ReturnedCartonOwnership.Waiting>> cartonsBySku = new LinkedHashMap<>();
        waiting.stream().sorted(Comparator.comparing(ReturnedCartonOwnership.Waiting::lpn))
                .forEach(carton -> cartonsBySku.computeIfAbsent(carton.sku(), sku -> new ArrayList<>()).add(carton));
        Map<ReturnedCartonOwnership.Waiting, ReturnedCartonOwnership.Ownership> decided = new LinkedHashMap<>();
        cartonsBySku.forEach((sku, cartons) -> {
            Decided refund = bySku.getOrDefault(sku, Decided.NOTHING);
            int sealed = cartons.stream().mapToInt(ReturnedCartonOwnership.Waiting::quantity).sum();
            int outsideCartons = Math.max(0, refund.unitsBack() - sealed);
            int reach = Math.max(0, refund.unitsRefunded() - outsideCartons);
            int before = 0;
            for (ReturnedCartonOwnership.Waiting carton : cartons) {
                decided.put(carton, before < reach ? ReturnedCartonOwnership.Ownership.COMPANY
                        : ReturnedCartonOwnership.Ownership.CUSTOMER_HELD);
                before += carton.quantity();
            }
        });
        return decided;
    }
}
