package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.SampleRound;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SampleRoundCards} over {@link ProductionSampleService}: the card's answer is the service's
 * {@link ProductionSampleService#answer}, after the same check the customer's sample page makes - an inactive customer
 * answers nothing (13 BR-03), and is told the round does not exist, as there.
 */
@Component
class SampleRoundCardsAdapter implements SampleRoundCards {

    private static final String ACTIVE = "ACTIVE";

    private final ProductionSampleService samples;
    private final CustomerService customers;

    SampleRoundCardsAdapter(ProductionSampleService samples, CustomerService customers) {
        this.samples = samples;
        this.customers = customers;
    }

    @Override
    public Optional<Card> round(UUID roundId) {
        if (roundId == null) {
            return Optional.empty();
        }
        return samples.ofRound(roundId).flatMap(view -> card(view, roundId));
    }

    @Override
    public Card answer(UUID customerId, UUID userId, UUID roundId, boolean approved, String note) {
        boolean active = customers.findById(customerId).map(c -> ACTIVE.equals(c.status())).orElse(false);
        if (!active) {
            throw new BusinessException(ErrorCode.SAMPLE_ROUND_NOT_FOUND, "No print sample round " + roundId);
        }
        ProductionSampleService.RoundsView view = samples.answer(customerId, userId, roundId,
                approved ? SampleRound.Result.APPROVED : SampleRound.Result.REPRINT_REQUESTED, note);
        return card(view, roundId).orElseThrow();
    }

    private static Optional<Card> card(ProductionSampleService.RoundsView view, UUID roundId) {
        ProductionOrder production = view.order();
        List<SampleRound> rounds = view.rounds().rounds();
        return rounds.stream().filter(r -> r.id().equals(roundId)).findFirst().map(round -> new Card(round.id(),
                round.roundNo(), production.id(), production.code(), production.status().name(),
                production.salesOrderId(), production.customerId(),
                round.result() == null ? "WAITING" : round.result().name(), round.physicalSampleSentOn(),
                round.openedAt(), round.answeredAt(),
                production.lines().stream().map(ProductionOrder.Line::sku).distinct().toList(),
                round.media().stream().map(m -> new Media(m.id(), m.fileName(), m.contentType(), m.video())).toList(),
                rounds.stream().filter(r -> r.roundNo() < round.roundNo())
                        .sorted(Comparator.comparingInt(SampleRound::roundNo))
                        .map(r -> new Earlier(r.id(), r.roundNo(), r.result() == null ? null : r.result().name(),
                                r.answeredAt())).toList()));
    }
}
