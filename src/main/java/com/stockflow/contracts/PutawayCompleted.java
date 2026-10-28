package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when a putaway task becomes DONE (SCRUM-92): every unit it was opened
 * for is in a bin. The <b>procurement</b> module consumes it to close the goods receipt once every line that
 * needed putting away has been put away.
 *
 * <p>Per task, at most once: a task is DONE exactly once and nothing is published for a partial putaway. The
 * receipt line is named so the consumer can add up what is done per line without reading the task back.
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; see {@code package-info.java} for the
 * rules on changing this contract.</p>
 */
public record PutawayCompleted(UUID goodsReceiptId, UUID goodsReceiptLineId, UUID putawayTaskId) {
}
