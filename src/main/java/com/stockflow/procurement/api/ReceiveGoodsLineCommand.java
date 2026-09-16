package com.stockflow.procurement.api;

import java.util.UUID;

/** One line of a {@link ReceiveGoodsCommand} — how much arrived against a given PO line. */
public record ReceiveGoodsLineCommand(UUID lineId, int quantity) {
}
