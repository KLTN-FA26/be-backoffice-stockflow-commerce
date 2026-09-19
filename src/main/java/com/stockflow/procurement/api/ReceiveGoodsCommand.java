package com.stockflow.procurement.api;

import java.util.List;

/** Input to {@link ProcurementService#receiveGoods}. */
public record ReceiveGoodsCommand(List<ReceiveGoodsLineCommand> lines) {

    public ReceiveGoodsCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
