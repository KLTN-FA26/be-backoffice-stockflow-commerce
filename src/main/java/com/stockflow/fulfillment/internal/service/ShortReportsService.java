package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.inventory.spi.ShortReports;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Answers inventory with the reason recorded on the pick line of a carton reported short (07 §4.4 step 9). */
@Component
class ShortReportsService implements ShortReports {

    private final PickLineJpaRepository pickLines;

    ShortReportsService(PickLineJpaRepository pickLines) {
        this.pickLines = pickLines;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Reason> latestReasonOf(String lpn) {
        // Line ids are UUIDv7: the newest report has the greatest id, even when its line was reallocated away since.
        return pickLines.findFirstByLpnIgnoreCaseAndShortReasonIsNotNullOrderByIdDesc(lpn)
                .map(line -> Reason.valueOf(line.getShortReason().name()));
    }
}
