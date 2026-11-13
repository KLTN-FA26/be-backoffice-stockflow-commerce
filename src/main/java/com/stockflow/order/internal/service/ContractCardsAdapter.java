package com.stockflow.order.internal.service;

import com.stockflow.order.api.ContractCards;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link ContractCards} over the contract's own reading ({@link SalesContracts#history}): the card shows what the
 * contract's page shows. An order that does not exist is {@code NOT_FOUND}, as there.
 */
@Component
class ContractCardsAdapter implements ContractCards {

    private final SalesContracts contracts;

    ContractCardsAdapter(SalesContracts contracts) {
        this.contracts = contracts;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Card> contract(UUID orderId, UUID contractId) {
        if (orderId == null || contractId == null) {
            return Optional.empty();
        }
        return contracts.history(orderId).stream().filter(c -> c.id().equals(contractId)).findFirst()
                .map(ContractCardsAdapter::card);
    }

    private static Card card(SalesContracts.ContractView view) {
        return new Card(view.id(), view.contractNumber(), view.orderId(), view.orderNumber(), view.status(),
                view.totals() == null ? null : view.totals().totalAmount(),
                view.totals() == null ? null : view.totals().currency(), view.freeSampleRounds(),
                view.paymentPlan() == null ? java.util.List.of() : view.paymentPlan().stream()
                        .map(i -> new Installment(i.seq(), i.name(), i.milestone(), i.amount(), i.percent(),
                                i.dueDays(), i.deliveryBatchSeq())).toList(),
                view.sentAt(), view.signedOn(), view.cancelledAt(), view.version());
    }
}
